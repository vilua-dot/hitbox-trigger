package dev.trigger.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class TriggerBot {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private Thread tickThread;
    private final HumanTiming timing = new HumanTiming();

    // resolved minecraft references
    private Object minecraftClient;
    private Field crosshairTargetField;
    private Field playerField;
    private Class<?> entityHitResultClass;
    private Class<?> playerEntityClass;
    private Method getEntityMethod;

    // mouse click simulation
    private Method mouseCallbackMethod;
    private Object mouseCallbackTarget;
    private long glfwWindow;

    // crit detection
    private Field onGroundField;
    private Method getVelocityMethod;
    private Field velocityYField;
    private Method isTouchingWaterMethod;
    private Method isClimbingMethod;
    private Method hasVehicleMethod;
    private Method hasStatusEffectMethod;
    private Object blindnessEffect;
    private Method getAttackCooldownProgressMethod;

    // GLFW
    private Method glfwGetKey;
    private boolean glfwResolved = false;

    private static final int GLFW_MOUSE_BUTTON_LEFT = 0;
    private static final int GLFW_PRESS = 1;
    private static final int GLFW_RELEASE = 0;
    private static final int TOGGLE_KEY = 0x9B; // INSERT
    private static final int TICK_INTERVAL_MS = 5;

    // crit timing — tracks when crit conditions first became true
    private long critReadySince = 0;
    private boolean wasCritReady = false;
    private boolean wasTargetValid = false;

    public void start() {
        if (running.getAndSet(true)) return;
        tickThread = new Thread(this::tickLoop, "jvm-gc-worker");
        tickThread.setDaemon(true);
        tickThread.start();
    }

    public void stop() {
        running.set(false);
        if (tickThread != null) {
            tickThread.interrupt();
            tickThread = null;
        }
        minecraftClient = null;
        crosshairTargetField = null;
        playerField = null;
        entityHitResultClass = null;
        playerEntityClass = null;
        getEntityMethod = null;
        mouseCallbackMethod = null;
        mouseCallbackTarget = null;
        onGroundField = null;
        getVelocityMethod = null;
        velocityYField = null;
        isTouchingWaterMethod = null;
        isClimbingMethod = null;
        hasVehicleMethod = null;
        hasStatusEffectMethod = null;
        blindnessEffect = null;
        getAttackCooldownProgressMethod = null;
    }

    private void tickLoop() {
        sleep(3000);

        if (!resolveReferences()) {
            running.set(false);
            return;
        }

        enabled.set(true);
        boolean wasTogglePressed = false;

        while (running.get()) {
            try {
                boolean togglePressed = isToggleKeyDown();
                if (togglePressed && !wasTogglePressed) {
                    boolean nowEnabled = !enabled.get();
                    enabled.set(nowEnabled);
                    if (nowEnabled) timing.resetSession();
                }
                wasTogglePressed = togglePressed;

                if (!enabled.get()) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                Object player = playerField.get(minecraftClient);
                if (player == null) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // check crosshair target
                Object target = crosshairTargetField.get(minecraftClient);
                boolean targetValid = false;
                Object targetEntity = null;

                if (target != null && entityHitResultClass.isInstance(target)) {
                    Object entity = getEntityMethod.invoke(target);
                    if (entity != null && playerEntityClass.isInstance(entity) && entity != player) {
                        targetValid = true;
                        targetEntity = entity;
                    }
                }

                // target acquisition tracking
                if (targetValid && !wasTargetValid) {
                    timing.onTargetAcquired();
                } else if (!targetValid && wasTargetValid) {
                    timing.onTargetLost();
                }
                wasTargetValid = targetValid;

                if (!targetValid) {
                    timing.onIdleTick(TICK_INTERVAL_MS);
                    critReadySince = 0;
                    wasCritReady = false;
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // check crit conditions
                boolean critReady = canCriticalHit(player);

                if (critReady && !wasCritReady) {
                    critReadySince = System.currentTimeMillis();
                } else if (!critReady) {
                    critReadySince = 0;
                }
                wasCritReady = critReady;

                if (!critReady || critReadySince == 0) {
                    timing.onIdleTick(TICK_INTERVAL_MS);
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // human reaction check — has enough time passed since stimulus?
                if (!timing.isReactionComplete(critReadySince)) {
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // intentional miss chance
                if (timing.shouldMiss()) {
                    timing.onIdleTick(TICK_INTERVAL_MS);
                    sleep(TICK_INTERVAL_MS);
                    continue;
                }

                // fire — simulate real mouse click with human hold time
                simulateClick(timing.getClickHoldTime());
                timing.onAttackExecuted();

                // reset crit tracking for next swing
                critReadySince = 0;
                wasCritReady = false;

            } catch (Exception e) {
                sleep(50);
            }

            sleep(TICK_INTERVAL_MS);
        }
    }

    private boolean canCriticalHit(Object player) {
        try {
            if (onGroundField != null && onGroundField.getBoolean(player)) return false;

            if (getVelocityMethod != null && velocityYField != null) {
                Object vel = getVelocityMethod.invoke(player);
                if (velocityYField.getDouble(vel) >= 0.0) return false;
            }

            if (isTouchingWaterMethod != null && (boolean) isTouchingWaterMethod.invoke(player))
                return false;

            if (isClimbingMethod != null && (boolean) isClimbingMethod.invoke(player))
                return false;

            if (hasVehicleMethod != null && (boolean) hasVehicleMethod.invoke(player))
                return false;

            if (hasStatusEffectMethod != null && blindnessEffect != null
                    && (boolean) hasStatusEffectMethod.invoke(player, blindnessEffect))
                return false;

            if (getAttackCooldownProgressMethod != null) {
                float cd = (float) getAttackCooldownProgressMethod.invoke(player, 0.5f);
                if (cd < 0.9f) return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void simulateClick(int holdTimeMs) {
        try {
            if (mouseCallbackMethod == null || mouseCallbackTarget == null) return;

            mouseCallbackMethod.invoke(mouseCallbackTarget,
                    glfwWindow, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);

            sleep(holdTimeMs);

            mouseCallbackMethod.invoke(mouseCallbackTarget,
                    glfwWindow, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0);
        } catch (Exception ignored) {}
    }

    // --- reference resolution ---

    private boolean resolveReferences() {
        try {
            Class<?> mcClass = findClass(
                "net.minecraft.client.MinecraftClient",
                "net.minecraft.class_310", "dsd");
            if (mcClass == null) return false;

            Method getInstance = findMethod(mcClass, "getInstance", 0);
            if (getInstance != null) {
                getInstance.setAccessible(true);
                minecraftClient = getInstance.invoke(null);
            } else {
                for (Field f : mcClass.getDeclaredFields()) {
                    if (f.getType() == mcClass) {
                        f.setAccessible(true);
                        minecraftClient = f.get(null);
                        break;
                    }
                }
            }
            if (minecraftClient == null) return false;

            Class<?> hitResultClass = findClass(
                "net.minecraft.util.hit.HitResult",
                "net.minecraft.class_239", "dxo");
            crosshairTargetField = findFieldByType(mcClass, hitResultClass);
            if (crosshairTargetField == null) return false;
            crosshairTargetField.setAccessible(true);

            entityHitResultClass = findClass(
                "net.minecraft.util.hit.EntityHitResult",
                "net.minecraft.class_3966", "dxm");
            if (entityHitResultClass == null) return false;

            Class<?> entityClass = findClass(
                "net.minecraft.entity.Entity",
                "net.minecraft.class_1297", "bsr");

            getEntityMethod = findMethod(entityHitResultClass, "getEntity", 0);
            if (getEntityMethod == null && entityClass != null) {
                for (Method m : entityHitResultClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && entityClass.isAssignableFrom(m.getReturnType())) {
                        getEntityMethod = m;
                        break;
                    }
                }
            }
            if (getEntityMethod == null) return false;
            getEntityMethod.setAccessible(true);

            playerEntityClass = findClass(
                "net.minecraft.entity.player.PlayerEntity",
                "net.minecraft.class_1657", "caj");
            if (playerEntityClass == null) return false;

            Class<?> clientPlayerClass = findClass(
                "net.minecraft.client.network.ClientPlayerEntity",
                "net.minecraft.class_746", "fgu");
            playerField = findFieldByType(mcClass, clientPlayerClass);
            if (playerField == null) playerField = findFieldByType(mcClass, playerEntityClass);
            if (playerField == null) return false;
            playerField.setAccessible(true);

            // mouse callback
            Class<?> mouseClass = findClass(
                "net.minecraft.client.Mouse",
                "net.minecraft.class_312", "dse");
            if (mouseClass == null) return false;

            Field mouseField = findFieldByType(mcClass, mouseClass);
            if (mouseField == null) return false;
            mouseField.setAccessible(true);
            mouseCallbackTarget = mouseField.get(minecraftClient);
            if (mouseCallbackTarget == null) return false;

            for (Method m : mouseClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 4) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0] == long.class && p[1] == int.class && p[2] == int.class && p[3] == int.class) {
                        mouseCallbackMethod = m;
                        break;
                    }
                }
            }
            if (mouseCallbackMethod == null) {
                mouseCallbackMethod = findMethod(mouseClass, "onMouseButton", 4);
                if (mouseCallbackMethod == null)
                    mouseCallbackMethod = findMethod(mouseClass, "method_1611", 4);
            }
            if (mouseCallbackMethod == null) return false;
            mouseCallbackMethod.setAccessible(true);

            // GLFW window handle
            Class<?> windowClass = findClass(
                "net.minecraft.client.util.Window",
                "net.minecraft.class_1041", "eml");
            if (windowClass != null) {
                Field wf = findFieldByType(mcClass, windowClass);
                if (wf != null) {
                    wf.setAccessible(true);
                    Object win = wf.get(minecraftClient);
                    for (Field f : windowClass.getDeclaredFields()) {
                        if (f.getType() == long.class) {
                            f.setAccessible(true);
                            long h = f.getLong(win);
                            if (h != 0) { glfwWindow = h; break; }
                        }
                    }
                }
            }
            if (glfwWindow == 0) return false;

            // --- crit detection ---
            Class<?> livingEntityClass = findClass(
                "net.minecraft.entity.LivingEntity",
                "net.minecraft.class_1309", "bth");

            if (entityClass != null) {
                onGroundField = findFieldByName(entityClass, "onGround");
                if (onGroundField == null) onGroundField = findFieldByName(entityClass, "field_6002");
                if (onGroundField == null) {
                    for (Field f : entityClass.getDeclaredFields()) {
                        if (f.getType() == boolean.class) { onGroundField = f; break; }
                    }
                }
                if (onGroundField != null) onGroundField.setAccessible(true);
            }

            Class<?> vec3dClass = findClass(
                "net.minecraft.util.math.Vec3d",
                "net.minecraft.class_243", "dxs");

            if (entityClass != null) {
                getVelocityMethod = findMethod(entityClass, "getVelocity", 0);
                if (getVelocityMethod == null)
                    getVelocityMethod = findMethod(entityClass, "method_18798", 0);
                if (getVelocityMethod == null && vec3dClass != null) {
                    for (Method m : entityClass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0 && vec3dClass.isAssignableFrom(m.getReturnType())) {
                            getVelocityMethod = m; break;
                        }
                    }
                }
                if (getVelocityMethod != null) getVelocityMethod.setAccessible(true);
            }

            if (vec3dClass != null) {
                velocityYField = findFieldByName(vec3dClass, "y");
                if (velocityYField == null) {
                    int idx = 0;
                    for (Field f : vec3dClass.getDeclaredFields()) {
                        if (f.getType() == double.class && idx++ == 1) { velocityYField = f; break; }
                    }
                }
                if (velocityYField != null) velocityYField.setAccessible(true);
            }

            if (entityClass != null) {
                isTouchingWaterMethod = findMethod(entityClass, "isTouchingWater", 0);
                if (isTouchingWaterMethod == null)
                    isTouchingWaterMethod = findMethod(entityClass, "method_5799", 0);
                if (isTouchingWaterMethod != null) isTouchingWaterMethod.setAccessible(true);
            }

            if (livingEntityClass != null) {
                isClimbingMethod = findMethod(livingEntityClass, "isClimbing", 0);
                if (isClimbingMethod == null)
                    isClimbingMethod = findMethod(livingEntityClass, "method_6101", 0);
                if (isClimbingMethod != null) isClimbingMethod.setAccessible(true);
            }

            if (entityClass != null) {
                hasVehicleMethod = findMethod(entityClass, "hasVehicle", 0);
                if (hasVehicleMethod == null)
                    hasVehicleMethod = findMethod(entityClass, "method_5765", 0);
                if (hasVehicleMethod != null) hasVehicleMethod.setAccessible(true);
            }

            if (livingEntityClass != null) {
                for (Method m : livingEntityClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getReturnType() == boolean.class) {
                        String pn = m.getParameterTypes()[0].getName();
                        if (pn.contains("RegistryEntry") || pn.contains("class_6880")
                                || m.getName().equals("hasStatusEffect") || m.getName().equals("method_6059")) {
                            hasStatusEffectMethod = m;
                            hasStatusEffectMethod.setAccessible(true);
                            break;
                        }
                    }
                }
            }

            Class<?> statusEffectsClass = findClass(
                "net.minecraft.entity.effect.StatusEffects",
                "net.minecraft.class_1294", "bso");
            if (statusEffectsClass != null) {
                for (Field f : statusEffectsClass.getDeclaredFields()) {
                    if (f.getName().equals("BLINDNESS") || f.getName().equals("field_5918")) {
                        f.setAccessible(true);
                        blindnessEffect = f.get(null);
                        break;
                    }
                }
                if (blindnessEffect == null) {
                    for (Field f : statusEffectsClass.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                && f.getType().getName().contains("RegistryEntry")) {
                            f.setAccessible(true);
                            Object e = f.get(null);
                            if (e != null && e.toString().contains("blindness")) {
                                blindnessEffect = e; break;
                            }
                        }
                    }
                }
            }

            if (playerEntityClass != null) {
                getAttackCooldownProgressMethod = findMethod(playerEntityClass, "getAttackCooldownProgress", 1);
                if (getAttackCooldownProgressMethod == null)
                    getAttackCooldownProgressMethod = findMethod(playerEntityClass, "method_6048", 1);
                if (getAttackCooldownProgressMethod == null) {
                    for (Method m : playerEntityClass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1 && m.getReturnType() == float.class
                                && m.getParameterTypes()[0] == float.class) {
                            getAttackCooldownProgressMethod = m; break;
                        }
                    }
                }
                if (getAttackCooldownProgressMethod != null)
                    getAttackCooldownProgressMethod.setAccessible(true);
            }

            return onGroundField != null && getVelocityMethod != null && velocityYField != null;

        } catch (Exception e) {
            return false;
        }
    }

    // --- GLFW toggle ---

    private boolean isToggleKeyDown() {
        try {
            if (!glfwResolved) {
                Class<?> gc = Class.forName("org.lwjgl.glfw.GLFW");
                glfwGetKey = gc.getMethod("glfwGetKey", long.class, int.class);
                glfwResolved = true;
            }
            if (glfwGetKey == null) return false;
            return (int) glfwGetKey.invoke(null, glfwWindow, TOGGLE_KEY) == 1;
        } catch (Exception e) {
            glfwResolved = true;
            return false;
        }
    }

    // --- reflection utilities ---

    private Class<?> findClass(String... names) {
        for (String name : names) {
            try { return Class.forName(name); } catch (ClassNotFoundException ignored) {}
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
        if (clazz.getSuperclass() != null) return findMethod(clazz.getSuperclass(), name, paramCount);
        return null;
    }

    private Field findFieldByType(Class<?> clazz, Class<?> type) {
        if (type == null) return null;
        for (Field f : clazz.getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) return f;
        }
        return null;
    }

    private Field findFieldByName(Class<?> clazz, String name) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findFieldByName(clazz.getSuperclass(), name);
            return null;
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
