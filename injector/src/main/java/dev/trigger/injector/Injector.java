package dev.trigger.injector;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.util.List;

public class Injector {

    public static void main(String[] args) {
        try {
            String agentPath = resolveAgentPath(args);
            String pid = findMinecraftPid();

            if (pid == null) {
                System.out.println("[!] minecraft process not found");
                System.exit(1);
            }

            System.out.println("[+] found minecraft pid: " + pid);
            System.out.println("[+] attaching...");

            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentPath);
            vm.detach();

            System.out.println("[+] agent loaded — detached clean");

        } catch (Exception e) {
            System.out.println("[!] injection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String resolveAgentPath(String[] args) {
        if (args.length > 0) {
            return new File(args[0]).getAbsolutePath();
        }
        File agentJar = new File("agent/build/libs/agent-1.0.0.jar");
        if (!agentJar.exists()) {
            agentJar = new File("agent.jar");
        }
        if (!agentJar.exists()) {
            System.out.println("[!] agent jar not found — pass path as argument");
            System.exit(1);
        }
        return agentJar.getAbsolutePath();
    }

    private static String findMinecraftPid() {
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        for (VirtualMachineDescriptor vmd : vms) {
            String name = vmd.displayName().toLowerCase();
            if (name.contains("minecraft") || name.contains("net.fabricmc")
                    || name.contains("net.minecraft") || name.contains("GradleStart")
                    || name.contains("knot") || name.contains("launchwrapper")) {
                return vmd.id();
            }
        }
        // fallback: check all java processes for minecraft main classes
        for (VirtualMachineDescriptor vmd : vms) {
            String name = vmd.displayName();
            if (name.contains("Main") && !name.contains("Injector")) {
                // could be minecraft with a generic launcher
                return vmd.id();
            }
        }
        return null;
    }
}
