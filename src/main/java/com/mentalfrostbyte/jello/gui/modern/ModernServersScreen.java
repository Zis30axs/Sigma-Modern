package com.mentalfrostbyte.jello.gui.modern;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.screen.impl.ProtocolSelectionScreen;
import com.viaversion.viafabricplus.settings.impl.GeneralSettings;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's multiplayer page. The same two-card layout as {@link ModernWorldsScreen}: saved servers
 * (and LAN games) on the left, the selected server's detail on the right - its icon, live status and
 * latency, MOTD, player count and sample, version - with Join as the lit action. The target protocol that
 * ViaFabricPlus will translate to sits in the header as a chip that opens its protocol picker.
 *
 * <p>This is vanilla's {@link JoinMultiplayerScreen} underneath: the saved-server list, LAN discovery,
 * status pinging, add/edit/delete/direct-connect flows and ViaFabricPlus's join handling are all inherited
 * unchanged; the list is vanilla's {@link ServerSelectionList}, drawn through {@link ModernRows}.</p>
 */
public final class ModernServersScreen extends JoinMultiplayerScreen implements ModernBlurredBackdrop {
    private static final int ROW_W = 305;   // ServerSelectionList#getRowWidth
    private static final int CARD_W = ROW_W + 30;
    private static final int BODY_TOP = ModernPage.TOP + 42;
    // Debug seam for screenshots: select the first saved server.
    private static final boolean DEBUG_SELECT_FIRST = Boolean.getBoolean("sigma.debug.selectFirst");

    private final ModernBackdrop backdrop = new ModernBackdrop(150, 4200F);
    private final long openStart = System.nanoTime();
    private @Nullable ModernButton join, edit, delete;
    private @Nullable Object shownKey;
    private long detailStart;

    public ModernServersScreen(Screen lastScreen) {
        super(lastScreen);
    }

    private record Layout(boolean wide, int margin, int cardX, int cardY, int cardW, int cardH, int detailX, int detailW) {}

    private Layout layout() {
        int margin = ModernPage.margin(this.width);
        boolean wide = this.width - 2 * margin - CARD_W - 12 >= 220 && this.height >= 250;
        if (wide) {
            int cardH = this.height - BODY_TOP - 12;
            int detailX = margin + CARD_W + 12;
            return new Layout(true, margin, margin, BODY_TOP, CARD_W, cardH, detailX, this.width - margin - detailX);
        }
        int cardW = Math.min(this.width - 2 * margin, CARD_W);
        int cardH = this.height - BODY_TOP - 12 - 34;
        return new Layout(false, margin, (this.width - cardW) / 2, BODY_TOP, cardW, cardH, 0, 0);
    }

    @Override
    protected void init() {
        this.initServerModel();
        Layout l = layout();
        this.serverSelectionList = new ServerSelectionList(this, this.minecraft, l.cardW() - 4, l.cardH() - 8, l.cardY() + 6, 36);
        this.serverSelectionList.updateOnlineServers(this.getServers());
        this.buildWidgets();
    }

    /**
     * A screen is only {@link #init()}ed once; a resize, or coming back from add/edit/delete/direct connect
     * (whose callbacks have already updated the list), lands here and rebuilds the page's widgets around the
     * same list and server model - re-running init would reload the saved servers and start a second LAN
     * detector.
     */
    @Override
    protected void repositionElements() {
        net.minecraft.client.gui.components.events.GuiEventListener focused = this.getFocused();
        this.clearWidgets();
        this.buildWidgets();
        if (focused != null && !this.children().contains(focused)) this.setFocused(null);
    }

    private void buildWidgets() {
        Layout l = layout();
        this.addRenderableWidget(new ModernButton(l.margin(), ModernPage.TOP, 22, 22, CommonComponents.GUI_BACK, ModernIcons.Icon.BACK,
            ModernButton.Kind.ICON, this::onClose));

        // Header actions, right-aligned: [target version] [direct connect] [add] [refresh].
        int right = this.width - l.margin();
        int y = ModernPage.TOP;
        right -= 22;
        this.addRenderableWidget(new ModernButton(right, y, 22, 22, Component.translatable("selectServer.refresh"), ModernIcons.Icon.REFRESH,
            ModernButton.Kind.ICON, this::refreshServerList));
        Component add = Component.translatable("selectServer.add"), direct = Component.translatable("selectServer.direct");
        if (l.wide()) {
            int addW = labelWidth(add), directW = labelWidth(direct);
            right -= 6 + addW;
            this.addRenderableWidget(new ModernButton(right, y, addW, 22, add, ModernIcons.Icon.PLUS, ModernButton.Kind.SECONDARY, this::openAddServer));
            right -= 6 + directW;
            this.addRenderableWidget(new ModernButton(right, y, directW, 22, direct, ModernIcons.Icon.ENTER, ModernButton.Kind.SECONDARY, this::openDirectJoin));
        } else {
            right -= 28;
            this.addRenderableWidget(new ModernButton(right, y, 22, 22, add, ModernIcons.Icon.PLUS, ModernButton.Kind.ICON, this::openAddServer));
            right -= 28;
            this.addRenderableWidget(new ModernButton(right, y, 22, 22, direct, ModernIcons.Icon.ENTER, ModernButton.Kind.ICON, this::openDirectJoin));
        }
        // ViaFabricPlus's protocol picker, shown as the version it will translate to. Its "Off" orientation
        // setting hides the vanilla button; it hides this chip the same way.
        if (GeneralSettings.INSTANCE.multiplayerScreenButtonOrientation.getIndex() != 0) {
            Component version = Component.literal(ProtocolTranslator.getTargetVersion().getName());
            int chipW = Math.min(110, labelWidth(version));
            right -= 6 + chipW;
            ModernButton chip = new ModernButton(right, y, chipW, 22, version, ModernIcons.Icon.BRANCH, ModernButton.Kind.GHOST,
                () -> ProtocolSelectionScreen.INSTANCE.open(this));
            // The chip wears the block of the target version's era, as the version picker does.
            for (ModernVersions.Entry entry : ModernVersions.build()) {
                if (entry.version().equals(ProtocolTranslator.getTargetVersion())) {
                    chip.withBlock(entry.block());
                    break;
                }
            }
            chip.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("base.viafabricplus.viafabricplus")));
            this.addRenderableWidget(chip);
        }

        this.serverSelectionList.updateSizeAndPosition(l.cardW() - 4, l.cardH() - 8, l.cardX() + 2, l.cardY() + 6);
        this.addRenderableWidget(this.serverSelectionList);

        Component joinLabel = Component.translatable("selectServer.select");
        if (l.wide()) {
            int x = l.detailX() + 18, w = l.detailW() - 36, bottom = l.cardY() + l.cardH() - 16;
            this.join = new ModernButton(x, bottom - 52, w, 24, joinLabel, ModernIcons.Icon.PLAY, ModernButton.Kind.PRIMARY, this::joinSelected);
            int half = (w - 6) / 2;
            this.edit = new ModernButton(x, bottom - 22, half, 22, Component.translatable("selectServer.edit"), ModernIcons.Icon.EDIT,
                ModernButton.Kind.SECONDARY, this::openEditSelected);
            this.delete = new ModernButton(x + half + 6, bottom - 22, w - half - 6, 22, Component.translatable("selectServer.delete"), ModernIcons.Icon.TRASH,
                ModernButton.Kind.DANGER, this::confirmDeleteSelected);
        } else {
            int size = 24, gap = 6, joinW = Math.max(104, ModernButton.widthFor(joinLabel, true));
            int x = (this.width - (joinW + 2 * (size + gap))) / 2, by = this.height - 12 - 28;
            this.join = new ModernButton(x, by, joinW, size, joinLabel, ModernIcons.Icon.PLAY, ModernButton.Kind.PRIMARY, this::joinSelected);
            x += joinW + gap;
            this.edit = new ModernButton(x, by, size, size, Component.translatable("selectServer.edit"), ModernIcons.Icon.EDIT, ModernButton.Kind.ICON,
                this::openEditSelected);
            x += size + gap;
            this.delete = new ModernButton(x, by, size, size, Component.translatable("selectServer.delete"), ModernIcons.Icon.TRASH, ModernButton.Kind.ICON,
                this::confirmDeleteSelected);
        }
        this.addRenderableWidget(this.join);
        this.addRenderableWidget(this.edit);
        this.addRenderableWidget(this.delete);
        this.onSelectedChange();
    }

    private int labelWidth(Component label) {
        return ModernButton.widthFor(label, true);
    }

    /** Same enablement as vanilla's footer: join anything but the LAN header; edit/delete saved servers only. */
    @Override
    protected void onSelectedChange() {
        if (this.join == null || this.edit == null || this.delete == null) return;
        ServerSelectionList.Entry entry = this.serverSelectionList == null ? null : this.serverSelectionList.getSelected();
        boolean saved = entry instanceof ServerSelectionList.OnlineServerEntry;
        this.join.active = entry != null && !(entry instanceof ServerSelectionList.LANHeader);
        this.edit.active = saved;
        this.delete.active = saved;
    }

    // --- rendering ------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.backdrop.render(g, this.minecraft, this.width, this.height, mouseX, mouseY);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (DEBUG_SELECT_FIRST && this.serverSelectionList.getSelected() == null && !this.serverSelectionList.children().isEmpty()
            && this.serverSelectionList.children().getFirst() instanceof ServerSelectionList.OnlineServerEntry first) {
            this.serverSelectionList.setSelected(first);
        }
        Layout l = layout();
        float open = ModernStyle.easeOut((System.nanoTime() - this.openStart) / 1_000_000_000F / 0.4F);
        ServerSelectionList.Entry entry = this.serverSelectionList.getSelected();
        boolean chosen = entry instanceof ServerSelectionList.OnlineServerEntry || entry instanceof ServerSelectionList.NetworkServerEntry;
        boolean show = !l.wide() || chosen;
        if (this.join != null) this.join.visible = show;
        if (this.edit != null) this.edit.visible = show && (!l.wide() || entry instanceof ServerSelectionList.OnlineServerEntry);
        if (this.delete != null) this.delete.visible = show && (!l.wide() || entry instanceof ServerSelectionList.OnlineServerEntry);

        try (var fade = ModernStyle.alphaScope(open)) {
            ModernPage.header(g, l.margin() + 32, ModernPage.TOP + 1, "MULTIPLAYER", this.title.getString(), 1.55F);
            g.pose().pushMatrix();
            g.pose().translate(0F, (1F - open) * 10F);
            ModernPage.card(g, l.cardX(), l.cardY(), l.cardW(), l.cardH());
            if (l.wide()) {
                ModernPage.card(g, l.detailX(), l.cardY(), l.detailW(), l.cardH());
                this.drawDetail(g, l, entry);
            } else {
                int shelfW = this.join == null ? 0 : this.join.getWidth() + 2 * 30 + 16;
                ModernStyle.darkGlass(g, (this.width - shelfW) / 2, this.height - 12 - 32, shelfW, 32, 16, 0xB00A1B29);
            }
            g.pose().popMatrix();
            super.extractRenderState(g, mouseX, mouseY, a);
        }
    }

    private void drawDetail(GuiGraphicsExtractor g, Layout l, ServerSelectionList.@Nullable Entry entry) {
        Object key = entry instanceof ServerSelectionList.OnlineServerEntry online ? online.getServerData()
            : entry instanceof ServerSelectionList.NetworkServerEntry lan ? lan.getLanServer() : null;
        if (!Objects.equals(key, this.shownKey)) {
            this.shownKey = key;
            this.detailStart = System.nanoTime();
        }
        float change = ModernStyle.easeOut((System.nanoTime() - this.detailStart) / 1_000_000_000F / 0.25F);
        int x = l.detailX(), y = l.cardY(), w = l.detailW(), h = l.cardH();
        try (var fade = ModernStyle.alphaScope(change)) {
            g.pose().pushMatrix();
            g.pose().translate(0F, (1F - change) * 6F);
            if (entry instanceof ServerSelectionList.OnlineServerEntry online) {
                this.drawServerDetail(g, x, y, w, online);
            } else if (entry instanceof ServerSelectionList.NetworkServerEntry lan) {
                this.drawLanDetail(g, x, y, w, lan.getLanServer());
            } else {
                this.drawEmptyDetail(g, x, y, w, h);
            }
            g.pose().popMatrix();
        }
    }

    private void drawEmptyDetail(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        float cx = x + w / 2F, cy = y + h / 2F - 30F;
        float breathe = 0.2F + 0.06F * (float)Math.sin(this.backdrop.time() * 1.3F);
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - 42F, cy - 42F, 84F, Math.round(255 * breathe) << 24 | 0xBFE8FF);
        ModernIcons.draw(g, ModernIcons.Icon.SERVER, cx - 18F, cy - 18F, 36F, 0xFFBFE3F5);
        String heading = "Choose a server";
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, heading,
            cx - ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, heading, 1.5F) / 2F, cy + 28F, 1.5F, 0xFFE3F2FA);
        int count = this.getServers().size();
        String sub = count == 1 ? "1 saved server" : count + " saved servers";
        ModernTypography.draw(g, ModernTypography.Face.TEXT, sub, cx - ModernTypography.width(ModernTypography.Face.TEXT, sub, 1F) / 2F, cy + 50F, 1F, 0xFF8FB0C4);
        String hint = "Shift + arrows reorder the list";
        ModernTypography.draw(g, ModernTypography.Face.TEXT, hint, cx - ModernTypography.width(ModernTypography.Face.TEXT, hint, 0.9F) / 2F,
            cy + 64F, 0.9F, 0xFF6F8FA4);
    }

    private void drawServerDetail(GuiGraphicsExtractor g, int x, int y, int w, ServerSelectionList.OnlineServerEntry entry) {
        ServerData data = entry.getServerData();
        int pad = 18, iconSize = 64;
        int ix = x + pad, iy = y + pad;
        ModernStyle.halo(g, ix, iy, iconSize, iconSize, 8, ModernStyle.GLOW, 0.35F);
        ModernRows.serverIcon(g, entry.iconTexture(), entry.iconTexture() != FaviconTexture.MISSING_LOCATION, ix, iy, iconSize);

        int tx = ix + iconSize + 14, tw = x + w - pad - tx;
        float nameScale = 1.45F;
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, ModernTypography.ellipsize(data.name, Math.round(tw / nameScale)), tx, iy + 2F, nameScale, 0xFFF2F8FC);
        String address = this.minecraft.options.hideServerAddress ? Component.translatable("selectServer.hiddenAddress").getString() : data.ip;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(address, tw), tx, iy + 22F, 1F, 0xFF7F9FB4);

        // Live status: signal bars and a word for the state.
        int sy = iy + 40;
        Component state;
        int stateColor;
        switch (data.state()) {
            case SUCCESSFUL -> {
                state = Component.translatable("multiplayer.status.ping", data.ping);
                stateColor = data.ping < 300L ? ModernRows.GOOD : data.ping < 1000L ? ModernRows.FAIR : ModernRows.BAD;
            }
            case UNREACHABLE -> {
                state = Component.translatable("multiplayer.status.no_connection");
                stateColor = ModernRows.BAD;
            }
            case INCOMPATIBLE -> {
                state = Component.translatable("multiplayer.status.incompatible");
                stateColor = ModernRows.FAIR;
            }
            default -> {
                state = Component.translatable("multiplayer.status.pinging");
                stateColor = 0xFF9DBCD0;
            }
        }
        int textX = tx;
        if (!entry.isPingingDisabled()) {
            ModernRows.signal(g, data, tx, sy + 1);
            textX += 22;
        }
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(state.getString(), x + w - pad - textX), textX, sy + 1F, 1F, stateColor);

        int fy = iy + iconSize + 14;
        ModernStyle.fill(g, x + pad, fy - 6, x + w - pad, fy - 5, 0x1AFFFFFF);
        // MOTD (or, with pinging disabled by ViaFabricPlus, the address - the same fallback the row uses).
        Component motd = entry.isPingingDisabled() ? Component.nullToEmpty(data.ip) : data.motd;
        ModernStyle.rounded(g, x + pad, fy, w - 2 * pad, 30, 7, 0x40050F1A);
        ModernRows.styledLines(g, motd, x + pad + 8, fy + 4.5F, w - 2 * pad - 16, 11F, 2, 0xFFB5CCDA);

        int col = (w - 2 * pad) / 2, ry = fy + 40;
        ModernPage.label(g, x + pad, ry, "PLAYERS");
        String players = data.players == null ? "-" : data.players.online() + " / " + data.players.max();
        ModernTypography.draw(g, ModernTypography.Face.TEXT, players, x + pad, ry + 10F, 1F, 0xFFD9E8F1);
        ModernPage.label(g, x + pad + col, ry, "VERSION");
        boolean incompatible = entry.isPingingDisabled() || data.state() == ServerData.State.INCOMPATIBLE;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(data.version.getString(), col - 6), x + pad + col, ry + 10F, 1F,
            incompatible ? ModernRows.BAD : 0xFFD9E8F1);

        int chipY = ry + 28;
        ProtocolVersion forced = data.viaFabricPlus$forcedVersion();
        int chipX = x + pad;
        if (forced != null) {
            chipX += ModernPage.chip(g, chipX, chipY, "Forced " + forced.getName(), 0xFFD6F1FF, 0x3D2E9BD6) + 5;
        }
        List<Component> sample = data.playerList;
        for (int i = 0; i < sample.size() && chipX < x + w - pad - 40; i++) {
            String name = sample.get(i).getString();
            if (name.isBlank()) continue;
            chipX += ModernPage.chip(g, chipX, chipY, ModernTypography.ellipsize(name, 90), 0xFFD9E8F1, 0x1FFFFFFF) + 5;
        }
    }

    private void drawLanDetail(GuiGraphicsExtractor g, int x, int y, int w, LanServer lan) {
        int pad = 18, iconSize = 64;
        int ix = x + pad, iy = y + pad;
        ModernStyle.halo(g, ix, iy, iconSize, iconSize, 8, 0x8FE8D8, 0.35F);
        ModernStyle.rounded(g, ix - 1, iy - 1, iconSize + 2, iconSize + 2, 5, 0x3DFFFFFF);
        ModernStyle.rounded(g, ix, iy, iconSize, iconSize, 4, 0xFF123A3A);
        ModernIcons.draw(g, ModernIcons.Icon.LAN, ix + 14F, iy + 12F, 36F, 0xFF8FE8D8);
        int tx = ix + iconSize + 14, tw = x + w - pad - tx;
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, ModernTypography.ellipsize(lan.getMotd(), Math.round(tw / 1.45F)), tx, iy + 2F, 1.45F, 0xFFF2F8FC);
        String address = this.minecraft.options.hideServerAddress ? Component.translatable("selectServer.hiddenAddress").getString() : lan.getAddress();
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(address, tw), tx, iy + 22F, 1F, 0xFF7F9FB4);
        ModernPage.chip(g, tx, iy + 40, Component.translatable("lanServer.title").getString(), 0xFFCFF7EF, 0x3326B8A0);
    }
}
