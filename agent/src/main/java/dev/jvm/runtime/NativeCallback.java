package dev.jvm.runtime;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class NativeCallback {

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean on = new AtomicBoolean(false);
    private Thread worker;
    private final ThreadScheduler scheduler = new ThreadScheduler();

    private Object ctx;
    private Field viewField;
    private Field entityField;
    private Class<?> hitClass;
    private Class<?> actorClass;
    private Method resolveMethod;

    private Method eventDispatch;
    private Object eventTarget;
    private long handle;

    private Field groundField;
    private Method velocityMethod;
    private Field vyField;
    private Method waterMethod;
    private Method climbMethod;
    private Method mountMethod;
    private Method effectMethod;
    private Object effectRef;
    private Method cooldownMethod;

    private Method keyMethod;
    private boolean keyResolved = false;
    private ClassLoader mcLoader;

    private static PrintWriter dbg;
    static {
        try {
            String p = System.getProperty("java.io.tmpdir") + java.io.File.separator + "vdbg.log";
            dbg = new PrintWriter(new FileWriter(p, false), true);
        } catch (Exception e) { dbg = null; }
    }
    private static void log(String s) { if (dbg != null) { dbg.println(s); dbg.flush(); } }

    private static final int BTN = 0;
    private static final int ACT_DOWN = 1;
    private static final int ACT_UP = 0;
    private static final int BIND = 0x9B;
    private static final int PANIC = 0x71;  // F2 — panic kill
    private static final int INTERVAL = 5;

    private long stateMark = 0;
    private boolean wasReady = false;
    private boolean wasValid = false;
    private long critWaitStart = 0;
    private int critWindowMs = 0;

    public void start() {
        log("start() called");
        if (active.getAndSet(true)) { log("already active"); return; }
        worker = new Thread(this::process, "jvm-gc-worker");
        worker.setDaemon(true);
        worker.start();
        log("worker thread started");
    }

    public void stop() {
        active.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        wipeRefs();
    }

    private void wipeRefs() {
        ctx = null;
        viewField = null;
        entityField = null;
        hitClass = null;
        actorClass = null;
        resolveMethod = null;
        eventDispatch = null;
        eventTarget = null;
        groundField = null;
        velocityMethod = null;
        vyField = null;
        waterMethod = null;
        climbMethod = null;
        mountMethod = null;
        effectMethod = null;
        effectRef = null;
        cooldownMethod = null;
        keyMethod = null;
        keyResolved = false;
    }

    private void panic() {
        active.set(false);
        on.set(false);
        wipeRefs();
        worker = null;
        ServiceProvider.unload();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private boolean isPanicActive() {
        try {
            if (keyMethod == null) return false;
            return (int) keyMethod.invoke(null, handle, PANIC) == 1;
        } catch (Exception e) { return false; }
    }

    private void process() {
        log("process() enter, sleeping 3s");
        pause(3000);
        log("calling resolve()");
        if (!resolve()) { log("resolve FAILED"); active.set(false); return; }
        log("resolve OK, entering main loop");
        on.set(true);
        boolean wasKey = false;

        while (active.get()) {
            try {
                if (isPanicActive()) {
                    panic();
                    return;
                }

                boolean keyDown = isKeyActive();
                if (keyDown && !wasKey) {
                    boolean next = !on.get();
                    on.set(next);
                    if (next) scheduler.reset();
                }
                wasKey = keyDown;

                if (!on.get()) { pause(INTERVAL); continue; }

                Object actor = entityField.get(ctx);
                if (actor == null) { pause(INTERVAL); continue; }

                Object view = viewField.get(ctx);
                boolean valid = false;
                Object target = null;

                if (view != null && hitClass.isInstance(view)) {
                    Object entity = resolveMethod.invoke(view);
                    if (entity != null && actorClass.isInstance(entity) && entity != actor) {
                        valid = true;
                        target = entity;
                    }
                }

                if (valid && !wasValid) scheduler.onReady();
                else if (!valid && wasValid) scheduler.onCancel();
                wasValid = valid;

                if (!valid) {
                    scheduler.onIdle(INTERVAL);
                    stateMark = 0;
                    wasReady = false;
                    critWaitStart = 0;
                    pause(INTERVAL);
                    continue;
                }

                boolean ready = validateState(actor);

                if (ready && !wasReady) stateMark = System.currentTimeMillis();
                else if (!ready) stateMark = 0;
                wasReady = ready;

                if (!ready || stateMark == 0) {
                    scheduler.onIdle(INTERVAL);
                    pause(INTERVAL);
                    continue;
                }

                if (!scheduler.isElapsed(stateMark)) { pause(INTERVAL); continue; }

                if (scheduler.shouldDefer()) {
                    scheduler.onIdle(INTERVAL);
                    pause(INTERVAL);
                    continue;
                }

                dispatchEvent(scheduler.getHoldDuration());
                scheduler.onExecuted();
                stateMark = 0;
                wasReady = false;

            } catch (Exception e) {
                pause(50);
            }
            pause(INTERVAL);
        }
    }

    private boolean isCooldownReady(Object actor) {
        try {
            if (cooldownMethod != null) {
                float cd = (float) cooldownMethod.invoke(actor, 0.5f);
                if (cd < 0.9f) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean isCritState(Object actor) {
        try {
            if (groundField != null && groundField.getBoolean(actor)) return false;
            if (velocityMethod != null && vyField != null) {
                Object v = velocityMethod.invoke(actor);
                if (vyField.getDouble(v) >= 0.0) return false;
            }
            if (waterMethod != null && (boolean) waterMethod.invoke(actor)) return false;
            if (climbMethod != null && (boolean) climbMethod.invoke(actor)) return false;
            if (mountMethod != null && (boolean) mountMethod.invoke(actor)) return false;
            if (effectMethod != null && effectRef != null
                    && (boolean) effectMethod.invoke(actor, effectRef)) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean validateState(Object actor) {
        if (!isCooldownReady(actor)) {
            critWaitStart = 0;
            return false;
        }
        if (isCritState(actor)) {
            critWaitStart = 0;
            return true;
        }
        long now = System.currentTimeMillis();
        if (critWaitStart == 0) {
            critWaitStart = now;
            critWindowMs = 180 + (int)(ThreadLocalRandom.current().nextGaussian() * 40 + 100);
            critWindowMs = Math.max(120, Math.min(500, critWindowMs));
            return false;
        }
        if (now - critWaitStart < critWindowMs) return false;
        critWaitStart = 0;
        return true;
    }

    private void dispatchEvent(int duration) {
        try {
            if (eventDispatch == null || eventTarget == null) return;
            eventDispatch.invoke(eventTarget, handle, BTN, ACT_DOWN, 0);
            pause(duration);
            eventDispatch.invoke(eventTarget, handle, BTN, ACT_UP, 0);
        } catch (Exception ignored) {}
    }

    private boolean resolve() {
        try {
            mcLoader = findMcLoader();
            log("mcLoader=" + mcLoader);
            if (mcLoader == null) {
                log("listing all thread classloaders:");
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    ClassLoader cl = t.getContextClassLoader();
                    log("  thread=" + t.getName() + " cl=" + cl);
                }
                return false;
            }

            Class<?> mc = fc(
                "net.minecraft.client.MinecraftClient",
                "net.minecraft.class_310", "dsd");
            log("mc=" + mc);
            if (mc == null) return false;

            Method gi = fm(mc, "getInstance", 0);
            if (gi != null) { gi.setAccessible(true); ctx = gi.invoke(null); }
            else {
                for (Field f : mc.getDeclaredFields()) {
                    if (f.getType() == mc) { f.setAccessible(true); ctx = f.get(null); break; }
                }
            }
            log("ctx=" + ctx);
            if (ctx == null) return false;

            Class<?> hr = fc(
                "net.minecraft.util.hit.HitResult",
                "net.minecraft.class_239", "dxo");
            log("hr=" + hr);
            viewField = fft(mc, hr);
            log("viewField=" + viewField);
            if (viewField == null) return false;
            viewField.setAccessible(true);

            hitClass = fc(
                "net.minecraft.util.hit.EntityHitResult",
                "net.minecraft.class_3966", "dxm");
            log("hitClass=" + hitClass);
            if (hitClass == null) return false;

            Class<?> ec = fc(
                "net.minecraft.entity.Entity",
                "net.minecraft.class_1297", "bsr");

            resolveMethod = fm(hitClass, "getEntity", 0);
            if (resolveMethod == null && ec != null) {
                for (Method m : hitClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && ec.isAssignableFrom(m.getReturnType())) {
                        resolveMethod = m; break;
                    }
                }
            }
            log("resolveMethod=" + resolveMethod);
            if (resolveMethod == null) return false;
            resolveMethod.setAccessible(true);

            actorClass = fc(
                "net.minecraft.entity.player.PlayerEntity",
                "net.minecraft.class_1657", "caj");
            if (actorClass == null) return false;

            Class<?> cp = fc(
                "net.minecraft.client.network.ClientPlayerEntity",
                "net.minecraft.class_746", "fgu");
            entityField = fft(mc, cp);
            if (entityField == null) entityField = fft(mc, actorClass);
            if (entityField == null) return false;
            entityField.setAccessible(true);

            Class<?> ic = fc(
                "net.minecraft.client.Mouse",
                "net.minecraft.class_312", "dse");
            if (ic == null) return false;

            Field icf = fft(mc, ic);
            if (icf == null) return false;
            icf.setAccessible(true);
            eventTarget = icf.get(ctx);
            if (eventTarget == null) return false;

            for (Method m : ic.getDeclaredMethods()) {
                if (m.getParameterCount() == 4) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0] == long.class && p[1] == int.class && p[2] == int.class && p[3] == int.class) {
                        eventDispatch = m; break;
                    }
                }
            }
            if (eventDispatch == null) {
                eventDispatch = fm(ic, "onMouseButton", 4);
                if (eventDispatch == null) eventDispatch = fm(ic, "method_1611", 4);
            }
            log("eventDispatch=" + eventDispatch);
            if (eventDispatch == null) return false;
            eventDispatch.setAccessible(true);

            Class<?> wc = fc(
                "net.minecraft.client.util.Window",
                "net.minecraft.class_1041", "eml");
            if (wc != null) {
                Field wf = fft(mc, wc);
                if (wf != null) {
                    wf.setAccessible(true);
                    Object win = wf.get(ctx);
                    for (Field f : wc.getDeclaredFields()) {
                        if (f.getType() == long.class) {
                            f.setAccessible(true);
                            long h = f.getLong(win);
                            if (h != 0) { handle = h; break; }
                        }
                    }
                }
            }
            log("handle=" + handle);
            if (handle == 0) return false;

            Class<?> le = fc(
                "net.minecraft.entity.LivingEntity",
                "net.minecraft.class_1309", "bth");

            if (ec != null) {
                groundField = ffn(ec, "onGround");
                if (groundField == null) groundField = ffn(ec, "field_6002");
                if (groundField == null) {
                    for (Field f : ec.getDeclaredFields()) {
                        if (f.getType() == boolean.class) { groundField = f; break; }
                    }
                }
                if (groundField != null) groundField.setAccessible(true);
            }

            Class<?> vc = fc(
                "net.minecraft.util.math.Vec3d",
                "net.minecraft.class_243", "dxs");

            if (ec != null) {
                velocityMethod = fm(ec, "getVelocity", 0);
                if (velocityMethod == null) velocityMethod = fm(ec, "method_18798", 0);
                if (velocityMethod == null && vc != null) {
                    for (Method m : ec.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0 && vc.isAssignableFrom(m.getReturnType())) {
                            velocityMethod = m; break;
                        }
                    }
                }
                if (velocityMethod != null) velocityMethod.setAccessible(true);
            }

            if (vc != null) {
                vyField = ffn(vc, "y");
                if (vyField == null) {
                    int n = 0;
                    for (Field f : vc.getDeclaredFields()) {
                        if (f.getType() == double.class && n++ == 1) { vyField = f; break; }
                    }
                }
                if (vyField != null) vyField.setAccessible(true);
            }

            if (ec != null) {
                waterMethod = fm(ec, "isTouchingWater", 0);
                if (waterMethod == null) waterMethod = fm(ec, "method_5799", 0);
                if (waterMethod != null) waterMethod.setAccessible(true);
            }

            if (le != null) {
                climbMethod = fm(le, "isClimbing", 0);
                if (climbMethod == null) climbMethod = fm(le, "method_6101", 0);
                if (climbMethod != null) climbMethod.setAccessible(true);
            }

            if (ec != null) {
                mountMethod = fm(ec, "hasVehicle", 0);
                if (mountMethod == null) mountMethod = fm(ec, "method_5765", 0);
                if (mountMethod != null) mountMethod.setAccessible(true);
            }

            if (le != null) {
                for (Method m : le.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getReturnType() == boolean.class) {
                        String pn = m.getParameterTypes()[0].getName();
                        if (pn.contains("RegistryEntry") || pn.contains("class_6880")
                                || m.getName().equals("hasStatusEffect") || m.getName().equals("method_6059")) {
                            effectMethod = m;
                            effectMethod.setAccessible(true);
                            break;
                        }
                    }
                }
            }

            Class<?> se = fc(
                "net.minecraft.entity.effect.StatusEffects",
                "net.minecraft.class_1294", "bso");
            if (se != null) {
                for (Field f : se.getDeclaredFields()) {
                    if (f.getName().equals("BLINDNESS") || f.getName().equals("field_5918")) {
                        f.setAccessible(true);
                        effectRef = f.get(null);
                        break;
                    }
                }
                if (effectRef == null) {
                    for (Field f : se.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                && f.getType().getName().contains("RegistryEntry")) {
                            f.setAccessible(true);
                            Object e = f.get(null);
                            if (e != null && e.toString().contains("blindness")) {
                                effectRef = e; break;
                            }
                        }
                    }
                }
            }

            if (actorClass != null) {
                cooldownMethod = fm(actorClass, "getAttackCooldownProgress", 1);
                if (cooldownMethod == null) cooldownMethod = fm(actorClass, "method_6048", 1);
                if (cooldownMethod == null) {
                    for (Method m : actorClass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1 && m.getReturnType() == float.class
                                && m.getParameterTypes()[0] == float.class) {
                            cooldownMethod = m; break;
                        }
                    }
                }
                if (cooldownMethod != null) cooldownMethod.setAccessible(true);
            }

            log("groundField=" + groundField + " velocityMethod=" + velocityMethod + " vyField=" + vyField);
            log("cooldownMethod=" + cooldownMethod);
            boolean ok = groundField != null && velocityMethod != null && vyField != null;
            log("resolve result=" + ok);
            return ok;

        } catch (Exception e) { log("resolve exception: " + e); return false; }
    }

    private boolean isKeyActive() {
        try {
            if (!keyResolved) {
                Class<?> gc = Class.forName("org.lwjgl.glfw.GLFW");
                keyMethod = gc.getMethod("glfwGetKey", long.class, int.class);
                keyResolved = true;
            }
            if (keyMethod == null) return false;
            return (int) keyMethod.invoke(null, handle, BIND) == 1;
        } catch (Exception e) { keyResolved = true; return false; }
    }

    private ClassLoader findMcLoader() {
        java.lang.instrument.Instrumentation inst = ServiceProvider.inst;
        if (inst != null) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if (n.equals("net.minecraft.client.MinecraftClient")
                        || n.equals("net.minecraft.class_310")) {
                    log("found mc class via inst: " + n + " loader=" + c.getClassLoader());
                    return c.getClassLoader();
                }
            }
            log("inst scan: no MinecraftClient found, trying pattern match");
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if (n.startsWith("net.minecraft.client.") && n.contains("Client")) {
                    log("pattern match: " + n + " loader=" + c.getClassLoader());
                    return c.getClassLoader();
                }
            }
        }
        log("inst not available, trying thread classloaders");
        String[] probes = {
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310"
        };
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            if (cl == null) continue;
            for (String p : probes) {
                try { cl.loadClass(p); return cl; } catch (ClassNotFoundException ignored) {}
            }
        }
        return null;
    }

    private Class<?> fc(String... names) {
        for (String n : names) {
            try {
                if (mcLoader != null) return Class.forName(n, true, mcLoader);
                return Class.forName(n);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private Method fm(Class<?> c, String name, int pc) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == pc) {
                m.setAccessible(true); return m;
            }
        }
        if (c.getSuperclass() != null) return fm(c.getSuperclass(), name, pc);
        return null;
    }

    private Field fft(Class<?> c, Class<?> t) {
        if (t == null) return null;
        for (Field f : c.getDeclaredFields()) {
            if (t.isAssignableFrom(f.getType())) return f;
        }
        return null;
    }

    private Field ffn(Class<?> c, String name) {
        try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (NoSuchFieldException e) {
            if (c.getSuperclass() != null) return ffn(c.getSuperclass(), name);
            return null;
        }
    }

    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
