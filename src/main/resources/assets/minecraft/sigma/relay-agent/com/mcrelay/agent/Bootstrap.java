package com.mcrelay.agent;

import com.mcrelay.agent.adapter.AdapterRegistry;
import com.mcrelay.agent.adapter.RelayAdapter;
import com.mcrelay.agent.proxy.ProxyServer;
import com.mcrelay.agent.util.ChatHelper;
import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;
import io.netty.channel.Channel;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

/**
 * 启动流程（v5: 通过 MC ClassLoader 加载，解决 ClassLoader 隔离）：
 *  1. 初始化 Reflect (Instrumentation)
 *  2. 检测 MC 版本 / 加载器
 *  3. 等待并定位客户端到服务器的 Netty Channel
 *  4. 安装字节级中继 Handler（S→A 复制 / A→S 抑制 / B→S 注入）
 *  5. 启动代理服务器 P 监听端口，等待 B 连入
 *
 * v5 核心改进：所有 agent 类通过 URLClassLoader(parent=MC CL) 加载，
 * 因此 Channel.class、Connection.class 等都解析为 MC 的版本，
 * 类型匹配和直接转换都能正常工作。
 */
public class Bootstrap {

    private final RelayCoordinator coordinator = new RelayCoordinator();
    private int port = 25577;

    private static Class<?> mcClass;
    private static Class<?> cplClass;    // ClientPacketListener
    private static Class<?> connClass;   // net.minecraft.network.Connection

    /**
     * Agent 启动入口。
     * @param args 参数字符串 (port=25577)
     * @param inst Instrumentation，由 JVM 提供
     */
    public void start(String args, Instrumentation inst) {
        parseArgs(args);
        McLog.info("MC-Relay agent 启动 (v5)，参数=" + args + "，端口=" + port);

        // 幂等重注入支持：若同一进程已注入相同端口，直接刷新通道，不重复绑定端口；
        // 若更换了端口，先优雅关闭旧 ProxyServer 再绑定新端口。
        synchronized (System.getProperties()) {
            Object activePort = System.getProperties().get("mcrelay.active.port");
            Object reattach = System.getProperties().get("mcrelay.active.reattach");
            Object stopper = System.getProperties().get("mcrelay.active.stop");
            if (String.valueOf(port).equals(activePort) && reattach instanceof Runnable) {
                McLog.info("MC-Relay 已在端口 " + port + " 运行，执行通道重挂载");
                ((Runnable) reattach).run();
                return;
            }
            if (stopper instanceof java.io.Closeable) {
                try {
                    ((java.io.Closeable) stopper).close();
                } catch (Throwable ignored) {}
            }
        }

        // 初始化 Reflect（通过 agentCL 加载的版本）
        try {
            Reflect.setInstrumentation(inst);
            McLog.info("Reflect 已初始化");
        } catch (Throwable t) {
            McLog.error("Reflect 初始化失败", t);
        }

        // 验证 ClassLoader
        try {
            Class<?> mcChannel = Reflect.tryCls("io.netty.channel.Channel");
            Class<?> agentChannel = Channel.class;
            McLog.info("Channel 类验证: Reflect找到=" + mcChannel +
                " (CL=" + (mcChannel != null ? mcChannel.getClassLoader() : "null") + ")" +
                ", Agent引用=" + agentChannel +
                " (CL=" + agentChannel.getClassLoader() + ")" +
                ", 相同=" + (mcChannel == agentChannel));
        } catch (Throwable ignored) {}

        ChatHelper.send("§b[MC-Relay] §aAgent 已加载 (v5)，正在初始化...");

        // 步骤 1：检测版本
        VersionDetect.Info ver;
        try {
            ver = VersionDetect.detect();
            McLog.info("检测到 " + ver);
            ChatHelper.send("§b[MC-Relay] §f版本: " + ver.version + " / " + ver.loader);
        } catch (Throwable t) {
            McLog.error("版本检测失败", t);
            ver = new VersionDetect.Info("unknown", VersionDetect.Loader.UNKNOWN);
        }

        // 步骤 2：预加载关键类（兼容 Forge/NeoForge/Fabric/原版混淆 1.8.9–26.2）
        try {
            mcClass = Reflect.findMinecraftClass();
            McLog.info("Minecraft 类: " + mcClass.getName() +
                " (CL=" + mcClass.getClassLoader() + ")");
            ChatHelper.send("§b[MC-Relay] §aMinecraft 类已定位");
        } catch (Throwable t) {
            McLog.error("找不到 Minecraft 类", t);
            ChatHelper.send("§b[MC-Relay] §c找不到 Minecraft 类!");
            return;
        }

        try {
            cplClass = Reflect.tryCls("net.minecraft.client.multiplayer.ClientPacketListener");
            if (cplClass == null) cplClass = Reflect.tryCls("net.minecraft.client.network.NetHandlerPlayClient");
            if (cplClass == null) cplClass = Reflect.tryCls("net.minecraft.client.multiplayer.NetHandlerPlayClient");
            if (cplClass == null) cplClass = Reflect.tryCls("net.minecraft.class_634"); // Fabric ClientPlayNetworkHandler
            McLog.info("ClientPacketListener 类: " + (cplClass != null ? cplClass.getName() : "null"));
        } catch (Throwable ignored) {}

        try {
            connClass = Reflect.tryCls("net.minecraft.network.Connection");
            if (connClass == null) connClass = Reflect.tryCls("net.minecraft.network.NetworkManager");
            if (connClass == null) connClass = Reflect.tryCls("net.minecraft.class_2535"); // Fabric ClientConnection
            McLog.info("Connection 类: " + (connClass != null ? connClass.getName() : "null"));
        } catch (Throwable ignored) {}

        // 步骤 3：先启动代理服务器 P（使外部 Sigma-Modern 探测立即可达，即使玩家尚在进服途中），再等待/挂载 Channel
        ProxyServer startedServer = null;
        try {
            RelayAdapter adapter = AdapterRegistry.forVersion(ver);
            ProxyServer server = new ProxyServer(coordinator, adapter, port);
            server.start();
            startedServer = server;
            McLog.info("代理服务器 P 已在 0.0.0.0:" + port + " 监听");
            ChatHelper.send("§b[MC-Relay] §a§l代理服务器已开启! 端口: " + port);
        } catch (Throwable t) {
            McLog.error("启动代理服务器失败", t);
            ChatHelper.send("§b[MC-Relay] §c启动代理失败: " + t.getMessage());
            return;
        }

        final ProxyServer activeServer = startedServer;
        synchronized (System.getProperties()) {
            System.getProperties().put("mcrelay.active.port", String.valueOf(port));
            System.getProperties().put("mcrelay.active.reattach", (Runnable) () -> {
                Channel latest = findCurrentChannel();
                if (latest != null && latest.isActive()) {
                    RelayHandler.install(latest, coordinator);
                    ChatHelper.send("§b[MC-Relay] §a已在端口 " + port + " 就绪并刷新网络通道!");
                } else {
                    ChatHelper.send("§b[MC-Relay] §e代理已在端口 " + port + " 运行，等待进入服务器...");
                }
            });
            System.getProperties().put("mcrelay.active.stop", (java.io.Closeable) () -> {
                channelMonitorRunning = false;
                activeServer.stop();
            });
        }

        // 步骤 4：等待 Channel（玩家进入服务器）
        ChatHelper.send("§b[MC-Relay] §f正在定位服务器通道...");
        Channel channel = waitForChannel();
        if (channel == null) {
            McLog.error("无法定位 Netty Channel");
            ChatHelper.send("§b[MC-Relay] §c错误: 无法定位网络通道，请确保已进入服务器");
            return;
        }
        McLog.info("已定位 Netty Channel: " + channel);

        // 步骤 5：安装中继 Handler
        try {
            RelayHandler.install(channel, coordinator);
            McLog.info("中继 Handler 已安装");
            ChatHelper.send("§b[MC-Relay] §a网络通道已就绪! Sigma-Modern 可直接接管 (127.0.0.1:" + port + ")");
        } catch (Throwable t) {
            McLog.error("安装中继 Handler 失败", t);
            ChatHelper.send("§b[MC-Relay] §c安装 Handler 失败: " + t.getMessage());
            return;
        }

        // 设置 channel 重装回调（当 A 重连服务器时，检测新 channel 并重装 handler）
        coordinator.setReinstallCallback(newChannel -> {
            if (newChannel != null) {
                McLog.info("重装 handler 到新 channel: " + newChannel);
                RelayHandler.install(newChannel, coordinator);
            } else {
                startChannelMonitor();
            }
        });

        // 步骤 6：启动 channel 监控线程（检测 A 重连）
        startChannelMonitor();
    }

    private volatile boolean channelMonitorRunning = false;

    /**
     * 启动 channel 监控线程。
     *
     * 每 2 秒检查一次 A 的服务器 channel 是否仍然 active。
     * 如果 channel 已关闭（A 被踢或主动断开），则等待 A 重新进入服务器，
     * 找到新 channel 后重装 relay handler。
     *
     * 这解决了 "A 被踢后重连，B→S 转发失败" 的问题。
     */
    private synchronized void startChannelMonitor() {
        if (channelMonitorRunning) return;
        channelMonitorRunning = true;

        Thread monitor = new Thread(() -> {
            McLog.info("Channel 监控线程已启动");
            boolean notified = false;

            while (true) {
                try {
                    sleep(2000);

                    Channel sc = coordinator.getServerChannel();
                    if (sc != null && sc.isActive()) {
                        // channel 仍然 active，一切正常
                        if (notified) {
                            McLog.info("A 的服务器 channel 已恢复: " + sc);
                            notified = false;
                        }
                        continue;
                    }

                    // channel 已关闭，需要寻找新 channel
                    if (!notified) {
                        McLog.warn("A 的服务器 channel 已关闭，等待 A 重新进入服务器...");
                        ChatHelper.send("§b[MC-Relay] §eA 的连接已断开，等待重新进入服务器...");
                        notified = true;
                    }

                    // 尝试寻找新 channel（复用 waitForChannel 的逻辑）
                    Channel newChannel = findCurrentChannel();
                    if (newChannel != null && newChannel.isActive()) {
                        McLog.info("检测到 A 的新服务器 channel: " + newChannel);
                        ChatHelper.send("§b[MC-Relay] §a检测到 A 重新连接，正在重装中继 Handler...");

                        // 重装 handler
                        RelayHandler.install(newChannel, coordinator);
                        McLog.info("中继 Handler 已重装到新 channel");
                        ChatHelper.send("§b[MC-Relay] §a中继 Handler 已重装! B 可以重新连接");
                        notified = false;
                    }

                } catch (Throwable t) {
                    McLog.warn("Channel 监控异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }, "mc-relay-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 查找 A 当前的服务器 channel（非阻塞，找不到返回 null）。
     * 复用 waitForChannel 的对象链: Minecraft → ClientPacketListener → Connection → Channel
     */
    private Channel findCurrentChannel() {
        try {
            Object mc = Reflect.findMinecraftInstance();
            if (mc == null) return null;

            // 1) 标准路径: Minecraft -> ClientPacketListener / NetHandlerPlayClient -> Connection / NetworkManager -> Channel
            Object cpl = null;
            if (cplClass != null) {
                cpl = Reflect.invokeByReturnType(mc, cplClass);
                if (cpl == null) cpl = Reflect.findFieldByType(mc, cplClass);
            }
            if (cpl == null) {
                cpl = Reflect.invokeAny(mc, new String[]{
                    "getConnection", "getNetHandler", "connection", "m_91390_", "func_147114_u", "method_1562"
                });
            }
            if (cpl == null) {
                Object player = Reflect.fieldAny(mc, "player", "thePlayer", "field_71439_g", "f_91074_", "field_1724");
                if (player != null) {
                    cpl = Reflect.fieldAny(player, "connection", "sendQueue", "field_71174_a", "f_108617_", "field_3944");
                }
            }

            if (cpl != null) {
                Object conn = null;
                if (connClass != null) {
                    conn = Reflect.findFieldByType(cpl, connClass);
                    if (conn == null) conn = Reflect.invokeByReturnType(cpl, connClass);
                }
                if (conn == null) {
                    conn = Reflect.fieldAny(cpl, "connection", "networkManager", "netManager", "f_17509_", "field_147302_e", "field_3687");
                }
                if (conn != null) {
                    Channel ch = (Channel) Reflect.findFieldByType(conn, Channel.class);
                    if (ch == null) ch = (Channel) Reflect.fieldAny(conn, "channel", "f_12946_", "field_150746_k", "field_11651");
                    if (ch == null) ch = scanForChannelByRuntimeName(conn);
                    if (ch != null && ch.isActive()) return ch;
                }
            }

            // 2) 原版完全混淆环境终极路径：从 mc 出发做 3 层对象图扫描定位活跃的客户端 Netty Channel
            Channel deep = findActiveChannelDeep(mc, 3, new java.util.IdentityHashMap<Object, Boolean>());
            if (deep != null && deep.isActive()) {
                return deep;
            }
        } catch (Throwable t) {
            McLog.warn("findCurrentChannel 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return null;
    }

    private Channel findActiveChannelDeep(Object root, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (root == null || depth < 0 || visited.put(root, Boolean.TRUE) != null) return null;
        if (root instanceof Channel) {
            Channel ch = (Channel) root;
            if (ch.isActive() && (ch.pipeline().get("encoder") != null || ch.pipeline().get("decoder") != null)) {
                return ch;
            }
            return null;
        }
        if (depth == 0) return null;
        Class<?> cls = root.getClass();
        if (cls.isArray() || cls.isEnum()) return null;
        String cn = cls.getName();
        if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
            || cn.startsWith("com.mcrelay.") || cn.startsWith("org.lwjgl.")
            || cn.startsWith("com.google.")) return null;

        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (java.lang.reflect.Field f : cur.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft == String.class || ft.isArray() || ft.isEnum()) continue;
                    f.setAccessible(true);
                    Object val = f.get(root);
                    if (val == null) continue;
                    Channel found = findActiveChannelDeep(val, depth - 1, visited);
                    if (found != null) return found;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private void parseArgs(String args) {
        if (args == null || args.isEmpty()) return;
        Map<String, String> kv = new HashMap<>();
        for (String pair : args.split(",")) {
            int i = pair.indexOf('=');
            if (i > 0) kv.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
        if (kv.containsKey("port")) {
            try { port = Integer.parseInt(kv.get("port")); } catch (Exception ignored) {}
        }
    }

    private Channel waitForChannel() {
        McLog.info("等待玩家连接到服务器...");
        int attempt = 0;

        while (true) {
            attempt++;
            try {
                Reflect.rescanClassLoader();
                Channel ch = findCurrentChannel();
                if (ch != null && ch.isActive()) {
                    McLog.info("成功定位 Netty Channel: " + ch +
                        " (CL=" + ch.getClass().getClassLoader() + ")");
                    ChatHelper.send("§b[MC-Relay] §a网络通道已激活!");
                    return ch;
                }
                if (attempt == 1) {
                    McLog.warn("未检测到活跃服务器连接，等待进入服务器...");
                    ChatHelper.send("§b[MC-Relay] §e未检测到服务器连接，请进入一个服务器");
                } else if (attempt % 15 == 0) {
                    ChatHelper.send("§b[MC-Relay] §7仍在等待进入服务器... (" + attempt + "秒)");
                }

            } catch (Throwable t) {
                McLog.warn("等待 Channel 第" + attempt + "次失败: " +
                    t.getClass().getSimpleName() + ": " + t.getMessage());
                if (attempt == 1) {
                    ChatHelper.send("§b[MC-Relay] §e第1次尝试异常: " + t.getClass().getSimpleName());
                }
                if (attempt == 120) {
                    McLog.error("等待 Channel 连续失败 120 次，放弃", t);
                    ChatHelper.send("§b[MC-Relay] §c无法定位网络通道（120秒超时）");
                    return null;
                }
            }
            sleep(1000);
        }
    }

    /**
     * 按运行时类名扫描 Channel 字段（终极后备方案）。
     * 遍历对象所有字段，检查值的运行时类名是否包含 "Channel" 或 "Socket"。
     */
    @SuppressWarnings("unchecked")
    private Channel scanForChannelByRuntimeName(Object obj) {
        try {
            Class<?> cur = obj.getClass();
            while (cur != null) {
                for (java.lang.reflect.Field f : cur.getDeclaredFields()) {
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val != null) {
                            String className = val.getClass().getName().toLowerCase();
                            if (className.contains("channel") || className.contains("socket")) {
                                McLog.info("scanForChannel: " + cur.getSimpleName() +
                                    "." + f.getName() + " = " + val.getClass().getName());
                                if (val instanceof Channel) {
                                    return (Channel) val;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
