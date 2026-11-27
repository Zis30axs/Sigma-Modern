package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.impl.misc.Relay;
import com.mentalfrostbyte.jello.relay.RelayJvmInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's built-in hot-injector and session takeover screen for {@code mc-relay}.
 *
 * <p>Scans running vanilla / Forge / Fabric / NeoForge Minecraft client JVMs on the local machine via
 * {@link RelayJvmInjector}, compiles &amp; caches the Java-8 {@code mc-relay-agent.jar} automatically,
 * hot-attaches into the selected host process, detects its exact Minecraft protocol version, configures
 * {@code ViaFabricPlus}, and connects {@code Sigma-Modern} to take over the live session in one click.</p>
 */
public final class ModernRelayScreen extends Screen implements ModernBlurredBackdrop {

    private static final int BODY_TOP = ModernPage.TOP + 42;
    private static final int ROW_H = 38;
    private static final int ROW_GAP = 4;

    private final @Nullable Screen parent;
    private final ModernBackdrop backdrop = new ModernBackdrop(150, 4200F);
    private final long openStart = System.nanoTime();
    private final List<String> logs = new CopyOnWriteArrayList<>();

    private List<RelayJvmInjector.JvmTarget> processes = List.of();
    private int selectedIndex = -1;
    private boolean showAllJvms;
    private float scroll;
    private volatile boolean busy;
    private volatile String statusLine = "";
    private volatile boolean statusError;

    private String endpointText = "127.0.0.1:" + Relay.configuredPort();
    private @Nullable EditBox endpointInput;
    private @Nullable ModernButton takeoverButton;
    private @Nullable ModernButton injectOnlyButton;
    private @Nullable ModernButton connectDirectButton;

    public ModernRelayScreen(final @Nullable Screen parent) {
        super(Component.literal(ModernVersions.text("Session Relay", "会话热注入接管")));
        this.parent = parent;
        this.statusLine = ModernVersions.text(
            "Select a running Minecraft client to hot-inject and take over.",
            "选择已进入服务器的原版/启动器 Minecraft 进程，一键热注入并接管。"
        );
    }

    private record Layout(
        boolean wide,
        int margin,
        int listX,
        int listY,
        int listW,
        int listH,
        int detailX,
        int detailY,
        int detailW,
        int detailH
    ) {
    }

    private Layout layout() {
        int margin = ModernPage.margin(this.width);
        boolean wide = this.width >= 560 && this.height >= 260;
        if (wide) {
            int listW = Math.max(240, Math.min(340, Math.round((this.width - 2 * margin - 12) * 0.48F)));
            int cardH = this.height - BODY_TOP - 12;
            int detailX = margin + listW + 12;
            int detailW = this.width - margin - detailX;
            return new Layout(true, margin, margin, BODY_TOP, listW, cardH, detailX, BODY_TOP, detailW, cardH);
        }
        int cardW = this.width - 2 * margin;
        int listH = Math.max(100, (this.height - BODY_TOP - 18) / 2);
        int detailY = BODY_TOP + listH + 8;
        int detailH = this.height - detailY - 10;
        return new Layout(false, margin, margin, BODY_TOP, cardW, listH, margin, detailY, cardW, detailH);
    }

    @Override
    protected void init() {
        refreshProcessList();
        buildWidgets();
    }

    @Override
    protected void repositionElements() {
        GuiEventListener focused = this.getFocused();
        EditBox previousBox = this.endpointInput;
        this.clearWidgets();
        buildWidgets();
        if (focused != null && !this.children().contains(focused)) {
            this.setFocused(focused == previousBox ? this.endpointInput : null);
        }
    }

    private void buildWidgets() {
        Layout l = layout();
        this.addRenderableWidget(new ModernButton(
            l.margin(), ModernPage.TOP, 22, 22, CommonComponents.GUI_BACK,
            ModernIcons.Icon.BACK, ModernButton.Kind.ICON, this::onClose
        ));

        int right = this.width - l.margin();
        int y = ModernPage.TOP;
        right -= 22;
        this.addRenderableWidget(new ModernButton(
            right, y, 22, 22, Component.translatable("selectServer.refresh"),
            ModernIcons.Icon.REFRESH, ModernButton.Kind.ICON, this::refreshProcessList
        ));

        Component filterLabel = Component.literal(this.showAllJvms
            ? ModernVersions.text("All JVMs", "全部 Java 进程")
            : ModernVersions.text("Minecraft Only", "仅显示 MC 进程"));
        int filterW = ModernButton.widthFor(filterLabel, true);
        right -= 6 + filterW;
        this.addRenderableWidget(new ModernButton(
            right, y, filterW, 22, filterLabel, ModernIcons.Icon.LAYERS, ModernButton.Kind.GHOST,
            () -> {
                this.showAllJvms = !this.showAllJvms;
                refreshProcessList();
                repositionElements();
            }
        ));

        int pad = 16;
        int dx = l.detailX() + pad;
        int dw = l.detailW() - 2 * pad;
        int inputY = l.detailY() + 90;

        this.endpointInput = new EditBox(
            this.font, dx + 8, inputY + 7, Math.max(80, dw - 16), 12,
            this.endpointInput, Component.literal("127.0.0.1:25577")
        );
        this.endpointInput.setMaxLength(64);
        this.endpointInput.setBordered(false);
        this.endpointInput.setValue(this.endpointText);
        this.endpointInput.setResponder(val -> this.endpointText = val.trim());
        this.addRenderableWidget(this.endpointInput);

        int bottom = l.detailY() + l.detailH() - 14;
        Component takeoverLabel = Component.literal(ModernVersions.text("Inject & Takeover", "热注入并接管"));
        Component injectLabel = Component.literal(ModernVersions.text("Inject Only", "仅热注入"));
        Component connectLabel = Component.literal(ModernVersions.text("Connect Port", "直连代理端口"));

        this.takeoverButton = new ModernButton(
            dx, bottom - 50, dw, 24, takeoverLabel,
            ModernIcons.Icon.PLAY, ModernButton.Kind.PRIMARY, () -> startInject(true)
        );
        int half = (dw - 6) / 2;
        this.injectOnlyButton = new ModernButton(
            dx, bottom - 22, half, 22, injectLabel,
            ModernIcons.Icon.PLUS, ModernButton.Kind.SECONDARY, () -> startInject(false)
        );
        this.connectDirectButton = new ModernButton(
            dx + half + 6, bottom - 22, dw - half - 6, 22, connectLabel,
            ModernIcons.Icon.ENTER, ModernButton.Kind.SECONDARY, this::startDirectConnect
        );

        this.addRenderableWidget(this.takeoverButton);
        this.addRenderableWidget(this.injectOnlyButton);
        this.addRenderableWidget(this.connectDirectButton);
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean hasSelection = this.selectedIndex >= 0 && this.selectedIndex < this.processes.size();
        if (this.takeoverButton != null) {
            this.takeoverButton.active = !this.busy && hasSelection;
        }
        if (this.injectOnlyButton != null) {
            this.injectOnlyButton.active = !this.busy && hasSelection;
        }
        if (this.connectDirectButton != null) {
            this.connectDirectButton.active = !this.busy && parsePortFromEndpoint() > 0;
        }
    }

    private void refreshProcessList() {
        String previousPid = selectedTarget() != null ? selectedTarget().pid() : null;
        try {
            this.processes = RelayJvmInjector.listProcesses(this.showAllJvms);
            this.selectedIndex = -1;
            for (int i = 0; i < this.processes.size(); i++) {
                if (this.processes.get(i).pid().equals(previousPid)) {
                    this.selectedIndex = i;
                    break;
                }
            }
            if (this.selectedIndex < 0 && !this.processes.isEmpty()) {
                this.selectedIndex = 0;
            }
            appendLog(ModernVersions.text(
                "Scanned " + this.processes.size() + " candidate JVM process(es).",
                "已扫描到 " + this.processes.size() + " 个候选目标进程。"
            ));
        } catch (final Throwable err) {
            this.processes = List.of();
            this.selectedIndex = -1;
            this.statusError = true;
            this.statusLine = ModernVersions.text("JVM scan failed: ", "扫描进程失败：") + err.getMessage();
            appendLog("ERROR: " + err);
        }
        updateButtonStates();
    }

    private RelayJvmInjector.@Nullable JvmTarget selectedTarget() {
        return (this.selectedIndex >= 0 && this.selectedIndex < this.processes.size())
            ? this.processes.get(this.selectedIndex)
            : null;
    }

    private String parseHostFromEndpoint() {
        String raw = this.endpointText == null ? "" : this.endpointText.trim();
        int colon = raw.lastIndexOf(':');
        if (colon > 0) {
            return raw.substring(0, colon).trim();
        }
        return "127.0.0.1";
    }

    private int parsePortFromEndpoint() {
        String raw = this.endpointText == null ? "" : this.endpointText.trim();
        if (raw.isEmpty()) {
            return Relay.configuredPort();
        }
        int colon = raw.lastIndexOf(':');
        String portPart = colon >= 0 ? raw.substring(colon + 1).trim() : raw;
        return Relay.parsePortOrDefault(portPart, -1);
    }

    private void startInject(final boolean autoTakeover) {
        RelayJvmInjector.JvmTarget target = selectedTarget();
        if (target == null || this.busy) {
            return;
        }
        int port = parsePortFromEndpoint();
        if (port < 1024 || port > 65535) {
            this.statusError = true;
            this.statusLine = ModernVersions.text("Invalid port (use 1024-65535).", "端口无效（请填写 1024-65535）。");
            return;
        }

        this.busy = true;
        this.statusError = false;
        this.statusLine = ModernVersions.text(
            "Hot-injecting into PID " + target.pid() + " ...",
            "正在向宿主进程 PID " + target.pid() + " 热注入 Agent..."
        );
        updateButtonStates();

        Thread worker = new Thread(() -> {
            try {
                RelayJvmInjector.inject(target.pid(), port, this::appendLog);
                appendLog(ModernVersions.text(
                    "Waiting for relay proxy on 127.0.0.1:" + port + " ...",
                    "正在探测 127.0.0.1:" + port + " 代理与协议版本..."
                ));
                RelayJvmInjector.ProbeStatus probe = RelayJvmInjector.waitForRelayReady("127.0.0.1", port, 8_000L);
                if (probe != null) {
                    appendLog(ModernVersions.text(
                        "Proxy ready: MC " + probe.versionName() + " (protocol " + probe.protocol()
                            + ") -> VFP " + probe.resolvedProtocolVersion().getName(),
                        "代理已就绪：宿主版本 " + probe.versionName() + " (协议 " + probe.protocol()
                            + ") -> 自动匹配 " + probe.resolvedProtocolVersion().getName()
                    ));
                } else {
                    appendLog(ModernVersions.text(
                        "Agent loaded; proxy still initializing (ensure host client has joined a server).",
                        "Agent 已注入；若代理尚未响应，请确认宿主客户端已进入目标服务器。"
                    ));
                }

                this.statusError = false;
                if (autoTakeover) {
                    this.statusLine = ModernVersions.text(
                        "Connecting to 127.0.0.1:" + port + " ...",
                        "正在连接并接管 127.0.0.1:" + port + " ..."
                    );
                    RelayJvmInjector.connectAndTakeover(this.parent, "127.0.0.1", port, probe);
                } else {
                    this.statusLine = ModernVersions.text(
                        "Injected PID " + target.pid() + " on 127.0.0.1:" + port,
                        "已成功注入 PID " + target.pid() + "（端口 127.0.0.1:" + port + "）"
                    );
                }
            } catch (final Throwable failure) {
                this.statusError = true;
                this.statusLine = ModernVersions.text("Injection failed: ", "热注入失败：") + failure.getMessage();
                appendLog("ERROR: " + failure.getMessage());
            } finally {
                this.busy = false;
                if (this.minecraft != null) {
                    this.minecraft.execute(this::updateButtonStates);
                }
            }
        }, "Sigma-Relay-HotInject");
        worker.setDaemon(true);
        worker.start();
    }

    private void startDirectConnect() {
        if (this.busy) {
            return;
        }
        String host = parseHostFromEndpoint();
        int port = parsePortFromEndpoint();
        if (port < 1 || port > 65535) {
            this.statusError = true;
            this.statusLine = ModernVersions.text("Invalid host:port.", "无效的地址或端口。");
            return;
        }

        this.busy = true;
        this.statusError = false;
        this.statusLine = ModernVersions.text(
            "Probing " + host + ":" + port + " ...",
            "正在探测 " + host + ":" + port + " 协议版本..."
        );
        updateButtonStates();

        Thread worker = new Thread(() -> {
            try {
                RelayJvmInjector.ProbeStatus probe = RelayJvmInjector.probeRelayStatus(host, port, 1_500);
                if (probe != null) {
                    appendLog(ModernVersions.text(
                        "Detected MC " + probe.versionName() + " (" + probe.protocol() + ") -> "
                            + probe.resolvedProtocolVersion().getName(),
                        "探测到版本 " + probe.versionName() + " (" + probe.protocol() + ") -> "
                            + probe.resolvedProtocolVersion().getName()
                    ));
                }
                RelayJvmInjector.connectAndTakeover(this.parent, host, port, probe);
            } finally {
                this.busy = false;
                if (this.minecraft != null) {
                    this.minecraft.execute(this::updateButtonStates);
                }
            }
        }, "Sigma-Relay-DirectConnect");
        worker.setDaemon(true);
        worker.start();
    }

    private void appendLog(final String line) {
        this.logs.add(line);
        while (this.logs.size() > 16) {
            this.logs.removeFirst();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    // --- input -----------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        Layout l = layout();
        double mx = event.x(), my = event.y();
        int listInnerTop = l.listY() + 26;
        int listInnerBottom = l.listY() + l.listH() - 8;
        if (mx >= l.listX() + 8 && mx <= l.listX() + l.listW() - 8 && my >= listInnerTop && my <= listInnerBottom) {
            int relY = (int) (my - listInnerTop + this.scroll);
            int idx = relY / (ROW_H + ROW_GAP);
            if (idx >= 0 && idx < this.processes.size()) {
                this.selectedIndex = idx;
                updateButtonStates();
                if (doubleClick && !this.busy) {
                    startInject(true);
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        Layout l = layout();
        if (mouseX >= l.listX() && mouseX <= l.listX() + l.listW()
            && mouseY >= l.listY() && mouseY <= l.listY() + l.listH()) {
            int contentH = this.processes.size() * (ROW_H + ROW_GAP);
            int viewH = Math.max(1, l.listH() - 34);
            float maxScroll = Math.max(0F, contentH - viewH);
            this.scroll = Math.clamp(this.scroll - (float) scrollY * 18F, 0F, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // --- rendering -------------------------------------------------------------------------------------

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
        this.backdrop.render(g, this.minecraft, this.width, this.height, mouseX, mouseY);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
        Layout l = layout();
        float open = ModernStyle.easeOut((System.nanoTime() - this.openStart) / 1_000_000_000F / 0.35F);

        try (var fade = ModernStyle.alphaScope(open)) {
            ModernPage.header(g, l.margin() + 32, ModernPage.TOP + 1, "MC-RELAY", this.title.getString(), 1.55F);
            g.pose().pushMatrix();
            try {
                g.pose().translate(0F, (1F - open) * 8F);
                ModernPage.card(g, l.listX(), l.listY(), l.listW(), l.listH());
                ModernPage.card(g, l.detailX(), l.detailY(), l.detailW(), l.detailH());
                drawProcessList(g, l, mouseX, mouseY);
                drawDetailCard(g, l);
            } finally {
                g.pose().popMatrix();
            }
            super.extractRenderState(g, mouseX, mouseY, a);
        }
    }

    private void drawProcessList(final GuiGraphicsExtractor g, final Layout l, final int mouseX, final int mouseY) {
        ModernPage.label(
            g,
            l.listX() + 14,
            l.listY() + 10,
            ModernVersions.text("TARGET MINECRAFT PROCESSES", "可热注入的目标进程")
        );

        int top = l.listY() + 26;
        int bottom = l.listY() + l.listH() - 8;
        int rowX = l.listX() + 10;
        int rowW = l.listW() - 20;

        if (this.processes.isEmpty()) {
            String empty = ModernVersions.text("No other Minecraft JVM found", "未检测到其它运行中的 Minecraft 进程");
            String sub = ModernVersions.text(
                "Launch vanilla/launcher client first, then click Refresh",
                "请先启动原版/第三方启动器客户端并进服，再点击右上角刷新"
            );
            float cx = l.listX() + l.listW() / 2F;
            float cy = (top + bottom) / 2F - 12F;
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, empty,
                cx - ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, empty, 1.15F) / 2F,
                cy, 1.15F, 0xFFD9EAF5);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, sub,
                cx - ModernTypography.width(ModernTypography.Face.TEXT, sub, 0.85F) / 2F,
                cy + 18F, 0.85F, 0xFF7D9EB3);
            return;
        }

        g.enableScissor(l.listX() + 4, top, l.listX() + l.listW() - 4, bottom);
        try {
            for (int i = 0; i < this.processes.size(); i++) {
                int ry = Math.round(top + i * (ROW_H + ROW_GAP) - this.scroll);
                if (ry + ROW_H < top || ry > bottom) {
                    continue;
                }
                RelayJvmInjector.JvmTarget proc = this.processes.get(i);
                boolean selected = i == this.selectedIndex;
                boolean hovered = mouseX >= rowX && mouseX <= rowX + rowW && mouseY >= ry && mouseY <= ry + ROW_H;

                int bg = selected ? 0x52369AD4 : (hovered ? 0x29FFFFFF : 0x1A07131E);
                ModernStyle.rounded(g, rowX, ry, rowW, ROW_H, 7, bg);
                if (selected) {
                    ModernStyle.rounded(g, rowX, ry + 6, 3, ROW_H - 12, 1, 0xFF8BD8FF);
                }

                ModernIcons.draw(g, ModernIcons.Icon.LAN, rowX + 10F, ry + 11F, 16F,
                    proc.likelyMinecraft() ? 0xFF8FE8D8 : 0xFF8AA8BC);

                int textLeft = rowX + 32;
                int badgeSpace = proc.guessedVersion() != null ? 58 : 8;
                int maxTitleW = Math.max(40, rowW - 38 - badgeSpace);
                ModernTypography.draw(
                    g, ModernTypography.Face.TEXT,
                    ModernTypography.ellipsize(proc.title(), maxTitleW),
                    textLeft, ry + 7F, 1.0F, 0xFFF2F8FC
                );

                String pidLine = "PID " + proc.pid() + "  ·  " + proc.commandLine();
                ModernTypography.draw(
                    g, ModernTypography.Face.TEXT,
                    ModernTypography.ellipsize(pidLine, rowW - 42),
                    textLeft, ry + 21F, 0.82F, 0xFF7FA0B5
                );

                if (proc.guessedVersion() != null) {
                    String verBadge = "MC " + proc.guessedVersion();
                    int chipW = Math.round(ModernTypography.width(ModernTypography.Face.TEXT, verBadge, 0.8F)) + 12;
                    ModernPage.chip(g, rowX + rowW - chipW - 8, ry + 7, verBadge, 0xFFD6F1FF, 0x3D2E9BD6);
                }
            }
        } finally {
            g.disableScissor();
        }
    }

    private void drawDetailCard(final GuiGraphicsExtractor g, final Layout l) {
        int pad = 16;
        int x = l.detailX() + pad;
        int y = l.detailY() + 12;
        int w = l.detailW() - 2 * pad;

        RelayJvmInjector.JvmTarget selected = selectedTarget();
        if (selected != null) {
            ModernTypography.draw(
                g, ModernTypography.Face.DISPLAY,
                ModernTypography.ellipsize(selected.title(), Math.round(w / 1.3F)),
                x, y, 1.3F, 0xFFF2F8FC
            );
            int chipX = x + ModernPage.chip(g, x, y + 20, "PID " + selected.pid(), 0xFFCFF7EF, 0x3326B8A0) + 5;
            if (selected.guessedVersion() != null) {
                ModernPage.chip(g, chipX, y + 20, "MC " + selected.guessedVersion(), 0xFFD6F1FF, 0x3D2E9BD6);
            }
            ModernTypography.draw(
                g, ModernTypography.Face.TEXT,
                ModernTypography.ellipsize(selected.commandLine(), w),
                x, y + 40F, 0.85F, 0xFF7FA0B5
            );
        } else {
            ModernTypography.draw(
                g, ModernTypography.Face.DISPLAY,
                ModernVersions.text("Direct / LAN Relay", "代理端点连接"),
                x, y, 1.3F, 0xFFF2F8FC
            );
            ModernTypography.draw(
                g, ModernTypography.Face.TEXT,
                ModernVersions.text(
                    "Select a host JVM on the left, or enter host:port below to connect directly.",
                    "可在左侧选择本机原版进程热注入，或在下方输入 host:port 直连已开启代理的宿主。"
                ),
                x, y + 24F, 0.88F, 0xFF8AA8BC
            );
        }

        // Endpoint / port input box chrome
        int inputLabelY = l.detailY() + 76;
        ModernPage.label(g, x, inputLabelY, ModernVersions.text("RELAY ENDPOINT / PORT", "代理地址与端口 (HOST:PORT)"));
        int boxY = l.detailY() + 90;
        ModernStyle.rounded(g, x, boxY, w, 24, 6, 0x59050F1A);
        ModernStyle.rounded(g, x, boxY + 23, w, 1, 0, 0x389BD8FA);

        // Status banner
        int statusY = boxY + 32;
        int statusColor = this.statusError ? ModernRows.BAD : (this.busy ? ModernRows.FAIR : 0xFF9EE8C8);
        ModernTypography.draw(
            g, ModernTypography.Face.TEXT,
            ModernTypography.ellipsize(this.statusLine, w),
            x, statusY, 0.9F, statusColor
        );

        // Log console panel above buttons
        int logTop = statusY + 16;
        int logBottom = l.detailY() + l.detailH() - 72;
        if (logBottom - logTop >= 28) {
            ModernStyle.rounded(g, x, logTop, w, logBottom - logTop, 7, 0x45040C15);
            int maxLines = Math.max(1, (logBottom - logTop - 10) / 11);
            int start = Math.max(0, this.logs.size() - maxLines);
            float ly = logTop + 6F;
            for (int i = start; i < this.logs.size(); i++) {
                String line = this.logs.get(i);
                int lineColor = line.startsWith("ERROR") ? ModernRows.BAD : 0xFFA9C3D4;
                ModernTypography.draw(
                    g, ModernTypography.Face.TEXT,
                    ModernTypography.ellipsize(line, Math.round((w - 14) / 0.82F)),
                    x + 7F, ly, 0.82F, lineColor
                );
                ly += 11F;
            }
        }
    }
}
