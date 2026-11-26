package dev.jvm.runtime;

import java.io.File;
import java.lang.reflect.Method;

public class Attacher {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("pid required");
            System.exit(1);
        }
        try {
            String pid = args[0];
            String jarPath = new File(Attacher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();

            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Method attach = vmClass.getMethod("attach", String.class);
            Object vm = attach.invoke(null, pid);
            try {
                Method loadAgent = vmClass.getMethod("loadAgent", String.class);
                loadAgent.invoke(vm, jarPath);
            } finally {
                Method detach = vmClass.getMethod("detach");
                detach.invoke(vm);
            }
        } catch (Exception e) {
            System.err.println("attach failed: " + e.getMessage());
            if (e.getCause() != null)
                System.err.println("cause: " + e.getCause().getMessage());
            System.exit(2);
        }
    }
}
