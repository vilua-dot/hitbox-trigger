package dev.jvm.runtime;

import java.lang.instrument.Instrumentation;

public class ServiceProvider {

    private static NativeCallback handler;
    static Instrumentation inst;

    public static void agentmain(String args, Instrumentation i) {
        inst = i;
        start();
    }

    public static void start() {
        if (handler != null) return;
        handler = new NativeCallback();
        handler.start();
    }

    public static void unload() {
        if (handler != null) {
            handler.stop();
            handler = null;
        }
    }
}
