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
    private Method attackMethod;
    private Field crosshairTargetField;
    private Field playerField;
    private Class<?> entityHitResultClass;
    private Class<?> playerEntityClass;
    private Method getEntityMethod;
    private Field optionsField;
    private Object keyAttack;
    private Method isKeyPressedMethod;

    // timing config
    private static final int MIN_DELAY_MS = 50;
    private static final int MAX_DELAY_MS = 150;
    private static final int TICK_INTERVAL_MS = 10;
    private static final double MISS_CHANCE = 0.03; // 3% intentional miss for human variance

    // keybind: toggle with INSERT key
    private static final int TOGGLE_KEY = 0x9B; // VK_INSERT in GLFW terms

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
        attackMethod = null;
        crosshairTargetField = null;
        playerField = null;
        entityHitResultClass = null;
        playerEntityClass = null;
        getEntityMethod = null;
        optionsField = null;
        keyAttack = null;
        isKeyPressedMethod = null;
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

                // attack
                doAttack(entity);
                // swing hand
                try {
                    Object interactionManager = getFieldValue(minecraftClient, "interactionManager");
                    if (interactionManager == null) {
                        // try mapped name
                        interactionManager = getFieldByType(minecraftClient, "ClientPlayerInteractionManager");
                    }
                    // swing via player
                    if (player != null) {
                        Method swingHand = findMethod(player.getClass(), "swingHand", 1);
                        if (swingHand != null) {
                            Object mainHand = getEnumConstant(swingHand.getParameterTypes()[0], 0);
                            swingHand.invoke(player, mainHand);
                        }
                    }
                } catch (Exception ignored) {
                    // swing is cosmetic, attack still landed
                }

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

            // resolve doAttack or attackEntity
            // MinecraftClient.doAttack() -> private boolean method_1536() / doAttack
            attackMethod = null;
            // first try: find doAttack (returns boolean, no params)
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    String name = m.getName();
                    if (name.equals("doAttack") || name.equals("method_1536")) {
                        attackMethod = m;
                        break;
                    }
                }
            }
            // fallback: use interactionManager.attackEntity
            if (attackMethod == null) {
                Class<?> interactionClass = findClass(
                    "net.minecraft.client.network.ClientPlayerInteractionManager",
                    "net.minecraft.class_636",
                    "fgs"
                );
                if (interactionClass != null) {
                    Class<?> entityClass = findClass(
                        "net.minecraft.entity.Entity",
                        "net.minecraft.class_1297",
                        "bsr"
                    );
                    for (Method m : interactionClass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 2
                                && playerEntityClass.isAssignableFrom(m.getParameterTypes()[0])
                                && entityClass.isAssignableFrom(m.getParameterTypes()[1])) {
                            attackMethod = m;
                            break;
                        }
                    }
                }
            }
            if (attackMethod != null) {
                attackMethod.setAccessible(true);
            }

            return attackMethod != null;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Invokes the attack — routes through doAttack() or interactionManager.attackEntity
     */
    private void doAttack(Object entity) throws Exception {
        if (attackMethod.getDeclaringClass().getSimpleName().contains("MinecraftClient")
                || attackMethod.getDeclaringClass().getSimpleName().contains("class_310")) {
            // doAttack() on MinecraftClient — no args, it reads crosshairTarget internally
            attackMethod.invoke(minecraftClient);
        } else {
            // attackEntity on interaction manager
            Object interactionManager = getFieldByType(minecraftClient, "InteractionManager");
            Object player = playerField.get(minecraftClient);
            if (interactionManager != null && player != null) {
                attackMethod.invoke(interactionManager, player, entity);
            }
        }
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
