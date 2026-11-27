package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.gui.modern.ModernRelayScreen;
import com.mentalfrostbyte.jello.module.impl.misc.Relay;
import com.mentalfrostbyte.jello.relay.RelayCoordinator;
import com.mentalfrostbyte.jello.relay.RelayJvmInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * Local chat command {@code .relay} supporting both:
 * <ul>
 *   <li>Hot-injection &amp; takeover of external vanilla/modded Minecraft processes:
 *       {@code .relay gui}, {@code .relay list [all]}, {@code .relay inject <pid> [port]},
 *       {@code .relay takeover <pid> [port]}, {@code .relay connect [host:port]}</li>
 *   <li>Embedded host relay management:
 *       {@code .relay status|on|off|kick|port <port>}</li>
 * </ul>
 */
public final class RelayCommand implements Command {

    private static final List<String> SUBCOMMANDS = List.of(
        "gui", "list", "takeover", "inject", "connect", "status", "on", "off", "kick", "port"
    );

    @Override
    public String name() {
        return "relay";
    }

    @Override
    public String usage() {
        return "[gui|list|takeover <pid> [port]|inject <pid> [port]|connect [host:port]|status|on|off|kick|port <port>]";
    }

    @Override
    public String description() {
        return "Hot-inject into a vanilla Minecraft process and take over its session, or manage local Relay";
    }

    @Override
    public String descriptionZh() {
        return "扫描原版 Minecraft 进程进行热注入与一键接管，或管理本机 Relay";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        Relay relay = ctx.modules().get(Relay.class);
        RelayCoordinator coordinator = RelayCoordinator.get();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "gui", "ui" -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> mc.gui.setScreen(new ModernRelayScreen(mc.gui.screen())));
                    ctx.reply(ctx.t("Opening Session Relay hot-injector...", "正在打开会话热注入面板..."));
                }
            }
            case "list", "ps" -> {
                boolean showAll = args.length >= 2 && "all".equalsIgnoreCase(args[1]);
                List<RelayJvmInjector.JvmTarget> targets = RelayJvmInjector.listProcesses(showAll);
                if (targets.isEmpty()) {
                    ctx.reply(ctx.t(
                        "No attachable Minecraft JVM found (use " + ctx.prefix() + "relay list all to show all Java processes).",
                        "未发现可注入的其它 Minecraft 进程（可用 " + ctx.prefix() + "relay list all 查看全部 Java 进程）。"
                    ));
                    return;
                }
                ctx.reply(ctx.t(
                    "Attachable JVM processes (" + targets.size() + "):",
                    "可热注入的目标进程 (" + targets.size() + ")："
                ));
                for (RelayJvmInjector.JvmTarget t : targets) {
                    String ver = t.guessedVersion() != null ? " §b[MC " + t.guessedVersion() + "]" : "";
                    ctx.raw("  §ePID " + t.pid() + "§r" + ver + " §7— §f" + t.title());
                }
                ctx.raw(ctx.t(
                    "  §7Use §b" + ctx.prefix() + "relay takeover <pid>§7 to hot-inject & join.",
                    "  §7输入 §b" + ctx.prefix() + "relay takeover <pid>§7 即可一键热注入并接管。"
                ));
            }
            case "inject", "takeover" -> {
                boolean autoConnect = "takeover".equals(sub);
                if (args.length < 2) {
                    ctx.error(ctx.t(
                        "Usage: " + ctx.prefix() + "relay " + sub + " <pid> [port]",
                        "用法：" + ctx.prefix() + "relay " + sub + " <pid> [端口]"
                    ));
                    return;
                }
                String pid = args[1].trim();
                int port = args.length >= 3 ? Relay.parsePortOrDefault(args[2], -1) : relay.parsedPort();
                if (port < 1024 || port > 65535) {
                    ctx.error(ctx.t("Port must be between 1024 and 65535.", "端口必须在 1024 到 65535 之间。"));
                    return;
                }
                ctx.reply(ctx.t(
                    "Hot-injecting into PID §e" + pid + "§r (port §b" + port + "§r)...",
                    "正在向宿主进程 PID §e" + pid + "§r 热注入 Agent（端口 §b" + port + "§r）..."
                ));
                Thread worker = new Thread(() -> {
                    try {
                        RelayJvmInjector.inject(pid, port, line -> {
                        });
                        RelayJvmInjector.ProbeStatus probe = RelayJvmInjector.waitForRelayReady("127.0.0.1", port, 8_000L);
                        if (autoConnect) {
                            RelayJvmInjector.connectAndTakeover(null, "127.0.0.1", port, probe);
                        } else {
                            ctx.reply(ctx.t(
                                "Hot-injection complete on §b127.0.0.1:" + port + "§r.",
                                "热注入完成，代理监听于 §b127.0.0.1:" + port + "§r。"
                            ));
                        }
                    } catch (final Throwable err) {
                        ctx.error(ctx.t("Hot-injection failed: ", "热注入失败：") + err.getMessage());
                    }
                }, "Sigma-Relay-CmdInject");
                worker.setDaemon(true);
                worker.start();
            }
            case "connect", "join" -> {
                String rawEndpoint = args.length >= 2 ? args[1].trim() : ("127.0.0.1:" + relay.parsedPort());
                int colon = rawEndpoint.lastIndexOf(':');
                String host = colon > 0 ? rawEndpoint.substring(0, colon).trim() : "127.0.0.1";
                int port = Relay.parsePortOrDefault(colon >= 0 ? rawEndpoint.substring(colon + 1) : rawEndpoint, -1);
                if (port < 1 || port > 65535) {
                    ctx.error(ctx.t("Invalid endpoint: " + rawEndpoint, "无效的代理地址：" + rawEndpoint));
                    return;
                }
                ctx.reply(ctx.t(
                    "Probing & connecting to §b" + host + ":" + port + "§r...",
                    "正在探测协议版本并连接 §b" + host + ":" + port + "§r..."
                ));
                Thread worker = new Thread(() -> {
                    RelayJvmInjector.ProbeStatus probe = RelayJvmInjector.probeRelayStatus(host, port, 1_500);
                    RelayJvmInjector.connectAndTakeover(null, host, port, probe);
                }, "Sigma-Relay-CmdConnect");
                worker.setDaemon(true);
                worker.start();
            }
            case "status" -> {
                boolean enabled = relay.isEnabled();
                int port = coordinator.isProxyRunning() ? coordinator.boundPort() : relay.parsedPort();
                String bind = relay.isAllowLan() ? "0.0.0.0:" + port : "127.0.0.1:" + port;
                String controller = coordinator.controllerName();
                if (!enabled) {
                    ctx.reply(ctx.t(
                        "Local Host Relay is §cOFF§r (configured endpoint §b" + bind + "§r).",
                        "本机宿主 Relay 当前 §c已关闭§r（配置端点 §b" + bind + "§r）。"
                    ));
                    return;
                }
                if (controller != null) {
                    ctx.reply(ctx.t(
                        "Relay §aACTIVE§r on §b" + bind + "§r — controlled by §e" + controller
                            + "§r (S->B " + coordinator.relayedServerToControllerPackets()
                            + ", B->S " + coordinator.relayedControllerToServerPackets() + ").",
                        "Relay §a运行中§r (§b" + bind + "§r) — 当前由 §e" + controller
                            + "§r 接管 (S->B " + coordinator.relayedServerToControllerPackets()
                            + ", B->S " + coordinator.relayedControllerToServerPackets() + ")。"
                    ));
                } else {
                    ctx.reply(ctx.t(
                        "Relay §aLISTENING§r on §b" + bind + "§r — waiting for controller.",
                        "Relay §a监听中§r (§b" + bind + "§r) — 等待控制端连接。"
                    ));
                }
            }
            case "on", "start" -> {
                relay.setEnabled(true);
                ctx.saveConfig();
                ctx.reply(ctx.t(
                    "Relay §aenabled§r on port §b" + relay.parsedPort() + "§r.",
                    "Relay §a已开启§r，端口 §b" + relay.parsedPort() + "§r。"
                ));
            }
            case "off", "stop" -> {
                relay.setEnabled(false);
                ctx.saveConfig();
                ctx.reply(ctx.t("Relay §cdisabled§r.", "Relay §c已关闭§r。"));
            }
            case "kick" -> {
                String who = coordinator.controllerName();
                if (coordinator.kickController(ctx.t("Kicked by host", "已被宿主移出接管"))) {
                    ctx.reply(ctx.t(
                        "Disconnected controller §e" + who + "§r; host control restored.",
                        "已断开控制端 §e" + who + "§r，宿主已恢复操控权。"
                    ));
                } else {
                    ctx.error(ctx.t("No controller is currently connected.", "当前没有已连接的控制端。"));
                }
            }
            case "port" -> {
                if (args.length < 2) {
                    ctx.reply(ctx.t(
                        "Current Relay port: §b" + relay.parsedPort() + "§r",
                        "当前 Relay 端口：§b" + relay.parsedPort() + "§r"
                    ));
                    return;
                }
                int parsed;
                try {
                    parsed = Integer.parseInt(args[1].trim());
                } catch (final NumberFormatException badNumber) {
                    ctx.error(ctx.t("Invalid port: " + args[1], "无效端口号：" + args[1]));
                    return;
                }
                if (parsed < 1024 || parsed > 65535) {
                    ctx.error(ctx.t("Port must be between 1024 and 65535.", "端口必须在 1024 到 65535 之间。"));
                    return;
                }
                relay.setPortValue(parsed);
                if (relay.isEnabled() && coordinator.isProxyRunning()) {
                    coordinator.startProxy(parsed, relay.isAllowLan());
                }
                ctx.saveConfig();
                ctx.reply(ctx.t(
                    "Relay port set to §b" + parsed + "§r.",
                    "Relay 端口已设为 §b" + parsed + "§r。"
                ));
            }
            default -> ctx.error(ctx.t(
                "Usage: " + ctx.prefix() + "relay " + usage(),
                "用法：" + ctx.prefix() + "relay " + usage()
            ));
        }
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String candidate : SUBCOMMANDS) {
                if (candidate.startsWith(prefix)) {
                    out.add(candidate);
                }
            }
            return out;
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if ("port".equals(first)) {
                return List.of("25577");
            }
            if ("connect".equals(first) || "join".equals(first)) {
                return List.of("127.0.0.1:25577");
            }
            if ("inject".equals(first) || "takeover".equals(first)) {
                String prefix = args[1];
                List<String> pids = new ArrayList<>();
                for (RelayJvmInjector.JvmTarget t : RelayJvmInjector.listProcesses(false)) {
                    if (t.pid().startsWith(prefix)) {
                        pids.add(t.pid());
                    }
                }
                return pids;
            }
        }
        return List.of();
    }
}
