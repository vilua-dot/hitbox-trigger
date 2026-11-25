package dev.trigger.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class TriggerBot {

    private final Instrumentation inst;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private Thread tickThread;

    // resolved references — cached after first lookup
    private Object minecraftClient;
    private Field crosshairTargetField;
    private Field playerField;
    private Class<?> entityHitResultClass;
    private Class<?> playerEntityClass;
    private Method getEntityMethod;

    // mouse callback simulation — calls Mouse.onMouseButton directly
    private Method mouseCallbackMethod;
    private Object mouseCallbackTarget;
    private long glfwWindow;

    // timing config
    private static final int MIN_DELAY_MS = 50;
    private static final int MAX_DELAY_MS = 150;
    private static final int TICK_INTERVAL_MS = 10;
    private static final double MISS_CHANCE = 0.03;
    private static final int CLICK_HOLD_MIN_MS = 30;
    private static final int CLICK_HOLD_MAX_MS = 80;

    // GLFW constants
    private static final int GLFW_MOUSE_BUTTON_LEFT = 0;
    private static final int GLFW_PRESS = 1;
    private static final int GLFW_RELEASE = 0;
    private static final int TOGGLE_KEY = 0x9B;

    public TriggerBot(Instrumentation inst) {
        this.inst = inst;
    }

    public void start() {
        if (running.getAndSet(true)) return;

        tickThread = new Thread(this::tickLoop, "jvm-gc-worker"); // innocuous thread name
        tickThread.setDaemon(true);
        tickThread.start();
    }

    public void stop() {
        running.set(false);
        if (tickThread != null) {
            tickThread.interrupt();
            tickThread = null;
        }
        // null out all references so GC can clean
        minecraftClient = null;
        crosshairTargetField = null;
        playerField = null;
        entityHitResultClass = null;
        playerEntityClass = null;
        getEntityMethod = null;
        mouseCallbackMethod = null;
        mouseCallbackTarget = null;
    }

    private void tickLoop() {
        // wait for minecraft to fully initialize
        sleep(3000);

        if (!resolveReferences()) {
            running.set(false);
            return;
        }

        enabled.set(true);
        long lastAttackTime = 0;
        long nextDelay = randomDelay();
        boolean wasTogglePressed = false;

        while (running.get()) {
            try {
                // toggle check via GLFW (handled through reflection)
                boolean togglePressed = isToggleKeyDown();
                if (togglePressed && !wasTogglePressed) {
                    enabled.set(!enabled.get());
                }
                wasTogglePressed = togglePressed;

                if (!enabled.get()) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                long now = System.currentTimeMillis();

                if (now - lastAttackTime < nextDelay) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // check crosshair target
                Object target = crosshairTargetField.get(minecraftClient);
                if (target == null || !entityHitResultClass.isInstance(target)) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                Object entity = getEntityMethod.invoke(target);
                if (entity == null || !playerEntityClass.isInstance(entity)) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // don't attack self
                Object player = playerField.get(minecraftClient);
                if (entity == player) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // human variance: small chance to skip (miss)
                if (ThreadLocalRandom.current().nextDouble() < MISS_CHANCE) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // simulate real mouse click — goes through GLFW → Mouse → attack pipeline
                simulateClick();

                lastAttackTime = now;
                nextDelay = randomDelay();

            } catch (Exception e) {
                // silent — no logging, no trace
                sleep(100);
            }

            sleep(TICK_INTERVAL_MS);
        }
    }

    private boolean resolveReferences() {
        try {
            // find MinecraftClient class — works with both mapped and intermediary names
            Class<?> mcClass = findClass(
                "net.minecraft.client.MinecraftClient",
                "net.minecraft.class_310",
                "dsd"  // 1.21.1 obfuscated
            );
            if (mcClass == null) return false;

            // get the singleton instance
            Method getInstance = findMethod(mcClass, "getInstance", 0);
            if (getInstance == null) {
                // try field-based singleton
                for (Field f : mcClass.getDeclaredFields()) {
                    if (f.getType() == mcClass) {
                        f.setAccessible(true);
                        minecraftClient = f.get(null);
                        break;
                    }
                }
            } else {
                getInstance.setAccessible(true);
                minecraftClient = getInstance.invoke(null);
            }
            if (minecraftClient == null) return false;

            // resolve crosshairTarget field
            Class<?> hitResultClass = findClass(
                "net.minecraft.util.hit.HitResult",
                "net.minecraft.class_239",
                "dxo"
            );
            crosshairTargetField = findFieldByType(mcClass, hitResultClass);
            if (crosshairTargetField == null) return false;
            crosshairTargetField.setAccessible(true);

            // resolve EntityHitResult
            entityHitResultClass = findClass(
                "net.minecraft.util.hit.EntityHitResult",
                "net.minecraft.class_3966",
                "dxm"
            );
            if (entityHitResultClass == null) return false;

            // getEntity method on EntityHitResult
            getEntityMethod = findMethod(entityHitResultClass, "getEntity", 0);
            if (getEntityMethod == null) {
                // try to find method that returns Entity
                Class<?> entityClass = findClass(
                    "net.minecraft.entity.Entity",
                    "net.minecraft.class_1297",
                    "bsr"
                );
                for (Method m : entityHitResultClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && entityClass != null
                            && entityClass.isAssignableFrom(m.getReturnType())) {
                        getEntityMethod = m;
                        break;
                    }
                }
            }
            if (getEntityMethod == null) return false;
            getEntityMethod.setAccessible(true);

            // resolve PlayerEntity
            playerEntityClass = findClass(
                "net.minecraft.entity.player.PlayerEntity",
                "net.minecraft.class_1657",
                "caj"
            );
            if (playerEntityClass == null) return false;

            // resolve player field on MinecraftClient (ClientPlayerEntity type)
            Class<?> clientPlayerClass = findClass(
                "net.minecraft.client.network.ClientPlayerEntity",
                "net.minecraft.class_746",
                "fgu"
            );
            playerField = findFieldByType(mcClass, clientPlayerClass);
            if (playerField == null) {
                // fallback — find any field assignable to PlayerEntity
                playerField = findFieldByType(mcClass, playerEntityClass);
            }
            if (playerField == null) return false;
            playerField.setAccessible(true);

            // resolve Mouse class and its onMouseButton callback
            // this is the method GLFW calls on a real hardware click
            Class<?> mouseClass = findClass(
                "net.minecraft.client.Mouse",
                "net.minecraft.class_312",
                "dse"
            );
            if (mouseClass == null) return false;

            // find the Mouse instance on MinecraftClient
            Field mouseField = findFieldByType(mcClass, mouseClass);
            if (mouseField == null) return false;
            mouseField.setAccessible(true);
            mouseCallbackTarget = mouseField.get(minecraftClient);
            if (mouseCallbackTarget == null) return false;

            // find onMouseButton(long window, int button, int action, int mods)
            for (Method m : mouseClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 4) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params[0] == long.class && params[1] == int.class
                            && params[2] == int.class && params[3] == int.class) {
                        mouseCallbackMethod = m;
                        break;
                    }
                }
            }
            // fallback: try known names
            if (mouseCallbackMethod == null) {
                for (String name : new String[]{"onMouseButton", "method_1611"}) {
                    mouseCallbackMethod = findMethod(mouseClass, name, 4);
                    if (mouseCallbackMethod != null) break;
                }
            }
            if (mouseCallbackMethod == null) return false;
            mouseCallbackMethod.setAccessible(true);

            // resolve GLFW window handle for the callback's first arg
            Class<?> windowClass = findClass(
                "net.minecraft.client.util.Window",
                "net.minecraft.class_1041",
                "eml"
            );
            if (windowClass != null) {
                Field windowField = findFieldByType(mcClass, windowClass);
                if (windowField != null) {
                    windowField.setAccessible(true);
                    Object window = windowField.get(minecraftClient);
                    for (Field f : windowClass.getDeclaredFields()) {
                        if (f.getType() == long.class) {
                            f.setAccessible(true);
                            long handle = f.getLong(window);
                            if (handle != 0) {
                                glfwWindow = handle;
                                break;
                            }
                        }
                    }
                }
            }

            return glfwWindow != 0;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simulates a real left mouse click via Minecraft's Mouse callback.
     * The click flows through: Mouse.onMouseButton → keybinding → doAttack
     * which is the exact same path as a hardware click. The server sees a
     * normal attack packet indistinguishable from real input.
     */
    private void simulateClick() {
        try {
            if (mouseCallbackMethod != null && mouseCallbackTarget != null) {
                // call Mouse.onMouseButton(window, button, action, mods) directly
                // this is what GLFW calls when a real click happens
                mouseCallbackMethod.invoke(mouseCallbackTarget,
                        glfwWindow, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);

                // hold for a human-like duration
                int holdTime = CLICK_HOLD_MIN_MS
                        + ThreadLocalRandom.current().nextInt(CLICK_HOLD_MAX_MS - CLICK_HOLD_MIN_MS);
                sleep(holdTime);

                // release
                mouseCallbackMethod.invoke(mouseCallbackTarget,
                        glfwWindow, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0);
            }
        } catch (Exception ignored) {}
    }

    // --- Toggle key detection via GLFW ---

    private Method glfwGetKey;
    private Object windowHandle;
    private boolean glfwResolved = false;

    private boolean isToggleKeyDown() {
        try {
            if (!glfwResolved) {
                resolveGlfw();
                glfwResolved = true;
            }
            if (glfwGetKey == null || windowHandle == null) return false;

            int state = (int) glfwGetKey.invoke(null, windowHandle, TOGGLE_KEY);
            return state == 1; // GLFW_PRESS
        } catch (Exception e) {
            return false;
        }
    }

    private void resolveGlfw() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
            glfwGetKey = glfwClass.getMethod("glfwGetKey", long.class, int.class);

            // get window handle from MinecraftClient
            Class<?> windowClass = findClass(
                "net.minecraft.client.util.Window",
                "net.minecraft.class_1041",
                "eml"
            );
            if (windowClass != null && minecraftClient != null) {
                Field windowField = findFieldByType(minecraftClient.getClass(), windowClass);
                if (windowField != null) {
                    windowField.setAccessible(true);
                    Object window = windowField.get(minecraftClient);
                    // get the GLFW handle (long field)
                    for (Field f : windowClass.getDeclaredFields()) {
                        if (f.getType() == long.class) {
                            f.setAccessible(true);
                            long handle = f.getLong(window);
                            if (handle != 0) {
                                windowHandle = handle;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // --- Utility methods ---

    private Class<?> findClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
            // also try through loaded classes via instrumentation
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().equals(name)) return c;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, int paramCount) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        // check superclasses
        if (clazz.getSuperclass() != null) {
            return findMethod(clazz.getSuperclass(), name, paramCount);
        }
        return null;
    }

    private Field findFieldByType(Class<?> clazz, Class<?> type) {
        for (Field f : clazz.getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) {
                return f;
            }
        }
        return null;
    }

    private Object getFieldValue(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private Object getFieldByType(Object obj, String typeNameContains) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeNameContains)) {
                try {
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private Object getEnumConstant(Class<?> enumClass, int index) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants != null && index < constants.length) {
            return constants[index];
        }
        return null;
    }

    private long randomDelay() {
        return MIN_DELAY_MS + ThreadLocalRandom.current().nextLong(MAX_DELAY_MS - MIN_DELAY_MS);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
