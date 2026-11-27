package com.mcrelay.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Java Agent 入口。
 *
 * 核心架构：通过 MC 的 ClassLoader 重新加载 agent 所有类。
 *
 * 问题：Forge 用 ModuleClassLoader (JPMS) 加载 Netty，agent 用 AppClassLoader
 * 加载 Netty，两个 Channel 类不同，无法转换。
 *
 * 解决：创建 URLClassLoader(agent.jar, parent=MC ClassLoader)，
 * 这样 agent 代码引用的 io.netty.channel.Channel 会解析为 MC 的版本。
 *
 * RelayAgent 本身由 AppClassLoader 加载（JVM 要求），
 * 但它通过反射在新的 ClassLoader 中加载 Bootstrap 并执行。
 */
public class RelayAgent {

    public static void agentmain(String agentArgs, Instrumentation inst) {
        writeMarker("agentmain called, args=" + agentArgs);

        // 阶段 1：找到 MC 的 ClassLoader
        ClassLoader mcCL = findMcClassLoader(inst);
        writeMarker("MC ClassLoader: " + mcCL);

        if (mcCL != null) {
            try {
                // 阶段 2：获取 agent JAR 路径
                URL jarUrl = RelayAgent.class.getProtectionDomain().getCodeSource().getLocation();
                writeMarker("Agent JAR URL: " + jarUrl);

                if (jarUrl != null) {
                    // 阶段 3：创建对 com.mcrelay.agent.* 子优先、其余委托给 MC ClassLoader 的加载器。
                    // 1.8.9/1.12.2 Forge 的 LaunchClassLoader 会把未知包委派回 AppClassLoader，
                    // 普通父优先 URLClassLoader 会导致 Bootstrap 被 AppClassLoader 加载而看不到 MC 的类。
                    URLClassLoader agentCL = new URLClassLoader(new URL[]{jarUrl}, mcCL) {
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                            synchronized (getClassLoadingLock(name)) {
                                if (name.startsWith("com.mcrelay.agent.")
                                    && !name.equals("com.mcrelay.agent.RelayAgent")) {
                                    Class<?> c = findLoadedClass(name);
                                    if (c == null) {
                                        c = findClass(name);
                                    }
                                    if (resolve) resolveClass(c);
                                    return c;
                                }
                                return super.loadClass(name, resolve);
                            }
                        }
                    };

                    writeMarker("Created child-first agent ClassLoader: " + agentCL +
                        " (parent=" + agentCL.getParent() + ")");

                    // 阶段 4：通过 agentCL 加载并初始化 Reflect
                    try {
                        Class<?> reflectClass = Class.forName(
                            "com.mcrelay.agent.util.Reflect", true, agentCL);
                        Method setInstMethod = reflectClass.getMethod(
                            "setInstrumentation", Instrumentation.class);
                        setInstMethod.invoke(null, inst);
                        writeMarker("Reflect.setInstrumentation done (through agentCL)");
                    } catch (Throwable t) {
                        writeMarker("Reflect init through agentCL failed: " + t);
                    }

                    // 阶段 5：通过 agentCL 加载 Bootstrap 并执行
                    try {
                        Class<?> bootstrapClass = Class.forName(
                            "com.mcrelay.agent.Bootstrap", true, agentCL);
                        Object bootstrap = bootstrapClass.getDeclaredConstructor().newInstance();
                        Method startMethod = bootstrapClass.getMethod(
                            "start", String.class, Instrumentation.class);

                        final Method m = startMethod;
                        final Object b = bootstrap;
                        final String args = agentArgs == null ? "" : agentArgs;
                        final Instrumentation i = inst;

                        Thread t = new Thread(() -> {
                            try {
                                writeMarker("Bootstrap thread starting (through agentCL)");
                                m.invoke(b, args, i);
                            } catch (Throwable e) {
                                writeMarker("FATAL: " + e.getClass().getName() + ": " + e.getMessage());
                                Throwable cause = e.getCause();
                                if (cause != null) {
                                    writeMarker("  cause: " + cause.getClass().getName() +
                                        ": " + cause.getMessage());
                                    for (StackTraceElement ste : cause.getStackTrace()) {
                                        writeMarker("    at " + ste);
                                    }
                                }
                            }
                        }, "mc-relay-bootstrap");
                        t.setContextClassLoader(agentCL);
                        t.setDaemon(true);
                        t.start();

                        writeMarker("Bootstrap launched successfully through MC ClassLoader!");
                        return;
                    } catch (Throwable t) {
                        writeMarker("Failed to launch Bootstrap through agentCL: " + t);
                        t.printStackTrace();
                    }
                }
            } catch (Throwable t) {
                writeMarker("ClassLoader isolation approach failed: " + t);
                t.printStackTrace();
            }
        }

        // 后备方案：直接用系统 ClassLoader 启动（可能遇到 ClassLoader 隔离问题）
        writeMarker("Falling back to direct launch (may have ClassLoader issues)");
        try {
            com.mcrelay.agent.util.Reflect.setInstrumentation(inst);
        } catch (Throwable t) {
            writeMarker("Reflect.setInstrumentation FAILED: " + t);
        }

        Thread t = new Thread(() -> {
            try {
                writeMarker("Bootstrap thread starting (fallback)");
                new Bootstrap().start(agentArgs == null ? "" : agentArgs, inst);
            } catch (Throwable e) {
                writeMarker("FATAL: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }, "mc-relay-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        agentmain(agentArgs, inst);
    }

    /**
     * 扫描所有已加载类，找到 MC 的 ClassLoader。
     * 优先找 net.minecraft.client.Minecraft 的 ClassLoader。
     * 如果找不到，找任意 net.minecraft.* 类的 ClassLoader。
     */
    private static ClassLoader findMcClassLoader(Instrumentation inst) {
        try {
            Class<?>[] loaded = inst.getAllLoadedClasses();

            // 优先找 Minecraft 主类（Mojmap/Forge/MCP 或 Fabric class_310）
            for (Class<?> c : loaded) {
                String n = c.getName();
                if (n.equals("net.minecraft.client.Minecraft") || n.equals("net.minecraft.class_310")) {
                    ClassLoader cl = c.getClassLoader();
                    writeMarker("Found MC ClassLoader via " + n + ": " + cl);
                    return cl;
                }
            }

            // 找任意 net.minecraft 类
            for (Class<?> c : loaded) {
                if (c.getName().startsWith("net.minecraft.") && c.getClassLoader() != null) {
                    ClassLoader cl = c.getClassLoader();
                    writeMarker("Found MC ClassLoader via " + c.getName() + ": " + cl);
                    return cl;
                }
            }

            // 找 GameProfile 或 Netty Channel 的 ClassLoader（兼容完全混淆环境）
            for (Class<?> c : loaded) {
                if ((c.getName().equals("com.mojang.authlib.GameProfile")
                    || c.getName().equals("io.netty.channel.Channel")) && c.getClassLoader() != null) {
                    ClassLoader cl = c.getClassLoader();
                    writeMarker("Found MC ClassLoader via " + c.getName() + ": " + cl);
                    return cl;
                }
            }

            writeMarker("No MC ClassLoader found in loaded classes");
        } catch (Throwable t) {
            writeMarker("findMcClassLoader failed: " + t);
        }
        return null;
    }

    /** 每次注入（每个 JVM）第一次写时清空，避免文件无限增长。 */
    private static volatile boolean markerTruncated = false;

    private static void writeMarker(String msg) {
        try {
            boolean append = markerTruncated;
            markerTruncated = true;
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream("mc-relay-loaded.txt", append), StandardCharsets.UTF_8))) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                pw.println("[" + ts + "] " + msg);
            }
        } catch (Exception ignored) {}
    }
}
