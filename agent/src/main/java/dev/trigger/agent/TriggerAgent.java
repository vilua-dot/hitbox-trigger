package dev.trigger.agent;

import java.lang.instrument.Instrumentation;

public class TriggerAgent {

    private static TriggerBot bot;

    // called by native loader via JNI (instrumentation will be null)
    // also works with standard java agent attach
    public static void agentmain(String args, Instrumentation inst) {
        start();
    }

    public static void start() {
        if (bot != null) return;
        bot = new TriggerBot();
        bot.start();
    }

    public static void unload() {
        if (bot != null) {
            bot.stop();
            bot = null;
        }
    }
}
