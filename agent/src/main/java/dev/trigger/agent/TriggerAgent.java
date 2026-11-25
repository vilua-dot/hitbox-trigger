package dev.trigger.agent;

import java.lang.instrument.Instrumentation;

/**
 * Entry point — loaded via VirtualMachine.loadAgent into a running Minecraft JVM.
 * Stores the Instrumentation handle and spins up the triggerbot on the render thread.
 */
public class TriggerAgent {

    private static Instrumentation instrumentation;
    private static TriggerBot bot;

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        bot = new TriggerBot(inst);
        bot.start();
    }

    public static void unload() {
        if (bot != null) {
            bot.stop();
            bot = null;
        }
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
