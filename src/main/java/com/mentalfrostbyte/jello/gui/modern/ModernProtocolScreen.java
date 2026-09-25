package com.mentalfrostbyte.jello.gui.modern;

import com.mojang.blaze3d.platform.InputConstants;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.screen.VFPScreen;
import com.viaversion.viafabricplus.screen.impl.ReportIssuesScreen;
import com.viaversion.viafabricplus.screen.impl.ServerListScreen;
import com.viaversion.viafabricplus.screen.impl.SettingsScreen;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's ViaFabricPlus version picker, standing in for both {@code ProtocolSelectionScreen} (the
 * global target version) and {@code PerServerVersionScreen} (a version forced for one saved server).
 *
 * <p>The versions come in four eras, each marked by its own block - grass for Modern, dirt path for the
 * Middle years, cobblestone for Legacy, bedrock for Bedrock - with tabs that jump to (and follow) each era,
 * and a one-line note on the versions that mark something ({@link ModernVersions}). The card on the left
 * shows the current version, or previews whichever row the pointer rests on; choosing one sets it off with
 * a small burst of ice.</p>
 *
 * <p>Nothing here decides anything ViaFabricPlus doesn't: the global target only changes on a click (or
 * Enter), and only while not connected, exactly like VFP's own list - hovering and arrow keys just move a
 * preview, because every change fires VFP's reload listeners. The header opens VFP's own settings, server
 * lists and issue reporter, which return here.</p>
 */
public final class ModernProtocolScreen extends Screen implements ModernBlurredBackdrop {
    private static final int ROW_H = 18, SECTION_H = 34, STAGE_H = 18, PINNED_H = 28, SECTION_GAP = 10, TABS_H = 24;
    private static final int BODY_TOP = ModernPage.TOP + 42;
    private static final String EYEBROW = "VIAFABRICPLUS";

    private enum Kind { RESET, AUTO, SECTION, STAGE, ROW }

    private record Item(Kind kind, int y, int h, ModernVersions.@Nullable Entry entry, ModernVersions.@Nullable Era era,
                        ModernVersions.@Nullable Stage stage) {
        boolean selectable() {
            return this.kind == Kind.RESET || this.kind == Kind.AUTO || this.kind == Kind.ROW;
        }

        Object key() {
            return this.kind == Kind.RESET ? Kind.RESET : Objects.requireNonNull(this.entry).version();
        }
    }

    private record Layout(boolean wide, int margin, int heroX, int heroY, int heroW, int heroH,
                          int listX, int listY, int listW, int listH, int viewTop, int viewBottom, int rowX, int rowW,
                          int searchX, int searchW) {
        int viewHeight() {
            return Math.max(0, this.viewBottom - this.viewTop);
        }
    }

    private final @Nullable Screen parent;
    private final boolean perServer;
    private final Supplier<ProtocolVersion> current;
    private final Consumer<ProtocolVersion> apply;
    private final ModernBackdrop backdrop = new ModernBackdrop(150, 4200F);
    private final long openStart = System.nanoTime();

    private List<ModernVersions.Entry> entries = List.of();
    private int registrySize = -1;
    private final List<Item> items = new ArrayList<>();
    private final Map<ModernVersions.Era, Integer> sectionTop = new EnumMap<>(ModernVersions.Era.class);
    private final Map<ModernVersions.Era, Integer> sectionCount = new EnumMap<>(ModernVersions.Era.class);
    private int contentHeight;
    private int nameColumn = 60;
    private float scroll, scrollTarget;
    private boolean userScrolled;
    private @Nullable EditBox search;
    private String query = "";

    private int focusIndex = -1;
    private boolean keyboardFocus;
    private @Nullable Object hoverKey;
    private long hoverSince;
    private @Nullable Object heroKey;
    private long heroStart;
    private @Nullable Object pulseKey;
    private long pulseStart, denyStart;
    private float underlineX = -1, underlineW;
    private float lastMouseX, lastMouseY;
    private float heroBlockCx, heroBlockCy;

    private ModernProtocolScreen(@Nullable Screen parent, boolean perServer, Component title,
                                 Supplier<ProtocolVersion> current, Consumer<ProtocolVersion> apply) {
        super(title);
        this.parent = parent;
        this.perServer = perServer;
        this.current = current;
        this.apply = apply;
    }

    /** The global target version, as VFP's {@code ProtocolSelectionScreen} sets it. */
    public static ModernProtocolScreen global(@Nullable Screen parent) {
        return new ModernProtocolScreen(parent, false, Component.literal(ModernVersions.text("Protocol", "协议版本")),
            ProtocolTranslator::getTargetVersion, ProtocolTranslator::setTargetVersion);
    }

    /** A version forced for one saved server; {@code null} means "follow the global target". */
    public static ModernProtocolScreen perServer(@Nullable Screen parent, Consumer<ProtocolVersion> apply, Supplier<ProtocolVersion> current) {
        return new ModernProtocolScreen(parent, true, Component.translatable("screen.viafabricplus.force_version"), current, apply);
    }

    // --- model ----------------------------------------------------------------------------------------

    private void refreshEntries() {
        this.registrySize = ProtocolVersion.getProtocols().size();
        this.entries = ModernVersions.build();
    }

    private void rebuildItems() {
        this.items.clear();
        this.sectionTop.clear();
        this.sectionCount.clear();
        int y = 0;
        if (this.perServer && this.query.isEmpty()) {
            this.items.add(new Item(Kind.RESET, y, PINNED_H, null, null, null));
            y += PINNED_H;
        }
        for (ModernVersions.Entry entry : this.entries) {
            if (entry.auto() && ModernVersions.matches(entry, this.query)) {
                this.items.add(new Item(Kind.AUTO, y, PINNED_H, entry, entry.era(), null));
                y += PINNED_H;
            }
        }
        float widest = 0F;
        for (ModernVersions.Era era : ModernVersions.Era.values()) {
            List<ModernVersions.Entry> section = new ArrayList<>();
            for (ModernVersions.Entry entry : this.entries) {
                if (!entry.auto() && entry.era() == era && ModernVersions.matches(entry, this.query)) section.add(entry);
            }
            this.sectionCount.put(era, section.size());
            if (section.isEmpty()) continue;
            if (y > 0) y += SECTION_GAP;
            this.sectionTop.put(era, y);
            this.items.add(new Item(Kind.SECTION, y, SECTION_H, null, era, null));
            y += SECTION_H;
            ModernVersions.Stage stage = null;
            for (ModernVersions.Entry entry : section) {
                if (entry.stage() != null && entry.stage() != stage) {
                    stage = entry.stage();
                    this.items.add(new Item(Kind.STAGE, y, STAGE_H, null, era, stage));
                    y += STAGE_H;
                }
                this.items.add(new Item(Kind.ROW, y, ROW_H, entry, era, entry.stage()));
                y += ROW_H;
                // Size the column for ordinary release names; the odd long special name just pushes its own note along.
                if (!entry.special()) widest = Math.max(widest, ModernTypography.width(ModernTypography.Face.TEXT, entry.version().getName(), 1F));
            }
        }
        this.contentHeight = y + 6;
        Layout l = layout();
        this.nameColumn = Math.round(Math.max(60F, Math.min(widest, l.rowW() * 0.42F)));
        this.scrollTarget = clampScroll(this.scrollTarget, l);
        this.scroll = clampScroll(this.scroll, l);
        if (this.focusIndex >= this.items.size()) this.focusIndex = -1;
    }

    private @Nullable ProtocolVersion currentVersion() {
        return this.current.get();
    }

    private boolean isCurrent(Item item) {
        ProtocolVersion version = currentVersion();
        if (item.kind() == Kind.RESET) return version == null;
        return item.entry() != null && item.entry().version().equals(version);
    }

    private boolean locked() {
        // VFP refuses to change the global target while a connection exists: that would pull the protocol
        // out from under it. A forced per-server version only applies on the next connect, so it's never locked.
        return !this.perServer && this.minecraft.getConnection() != null;
    }

    private ModernVersions.@Nullable Entry entryFor(@Nullable ProtocolVersion version) {
        if (version == null) return null;
        for (ModernVersions.Entry entry : this.entries) if (entry.version().equals(version)) return entry;
        return null;
    }

    /** The item for the current version - from the list if it's there (it may be filtered out), else built for the hero. */
    private @Nullable Item currentItem() {
        for (Item item : this.items) if (item.selectable() && isCurrent(item)) return item;
        ProtocolVersion version = currentVersion();
        if (version == null) return this.perServer ? new Item(Kind.RESET, 0, 0, null, null, null) : null;
        ModernVersions.Entry entry = entryFor(version);
        if (entry == null) return null;
        return new Item(entry.auto() ? Kind.AUTO : Kind.ROW, 0, 0, entry, entry.era(), entry.stage());
    }

    // --- layout -----------------------------------------------------------------------------------------

    private Layout layout() {
        int margin = ModernPage.margin(this.width);
        boolean wide = this.width >= 560 && this.height >= 250;
        int toolsLeft = this.perServer ? this.width - margin : this.width - margin - 22 - 2 * 28;
        float titleW = Math.max(ModernTypography.width(ModernTypography.Face.DISPLAY, this.title.getString(), 1.55F),
            ModernTypography.width(ModernTypography.Face.TEXT, ModernStyle.spaced(EYEBROW), 0.9F) + 7F);
        int searchRight = toolsLeft - 8;
        int searchW = Math.min(190, searchRight - (margin + 32 + Math.round(titleW) + 16));
        if (searchW < 90) searchW = 0;
        int searchX = searchRight - searchW;

        int heroX = margin, heroY = BODY_TOP, heroW, heroH, listX, listY, listW, listH;
        if (wide) {
            heroW = Math.max(220, Math.min(300, Math.round(this.width * 0.34F)));
            heroH = this.height - BODY_TOP - 12;
            listX = margin + heroW + 12;
            listY = BODY_TOP;
            listW = this.width - margin - listX;
            listH = heroH;
        } else {
            heroW = this.width - 2 * margin;
            heroH = 46;
            listX = margin;
            listY = BODY_TOP + heroH + 6;
            listW = heroW;
            listH = this.height - listY - 12;
        }
        int viewTop = listY + 8 + TABS_H + 6;
        int viewBottom = Math.max(viewTop, listY + listH - 8);
        return new Layout(wide, margin, heroX, heroY, heroW, heroH, listX, listY, listW, listH, viewTop, viewBottom,
            listX + 14, listW - 30, searchX, searchW);
    }

    private float clampScroll(float value, Layout l) {
        return Math.max(0F, Math.min(value, Math.max(0, this.contentHeight - l.viewHeight())));
    }

    private void centerOn(@Nullable Item item) {
        if (item == null || item.h() == 0) return;
        Layout l = layout();
        this.scrollTarget = this.scroll = clampScroll(item.y() - l.viewHeight() / 2F + item.h() / 2F, l);
    }

    private void revealItem(Item item) {
        Layout l = layout();
        if (item.y() < this.scrollTarget + 4) this.scrollTarget = clampScroll(item.y() - 4, l);
        else if (item.y() + item.h() > this.scrollTarget + l.viewHeight() - 4) this.scrollTarget = clampScroll(item.y() + item.h() - l.viewHeight() + 4, l);
    }

    // --- lifecycle --------------------------------------------------------------------------------------

    @Override
    protected void init() {
        refreshEntries();
        buildWidgets();
        rebuildItems();
        centerOn(currentItem());
    }

    /**
     * Only the first open runs {@link #init()}; a resize, or coming back from VFP's settings / server lists /
     * issue reporter, lands here and rebuilds the widgets around the same list, scroll, search and focus.
     */
    @Override
    protected void repositionElements() {
        GuiEventListener focused = this.getFocused();
        EditBox oldSearch = this.search;
        this.clearWidgets();
        buildWidgets();
        rebuildItems();
        if (focused != null && !this.children().contains(focused)) this.setFocused(focused == oldSearch ? this.search : null);
    }

    private void buildWidgets() {
        Layout l = layout();
        this.addRenderableWidget(new ModernButton(l.margin(), ModernPage.TOP, 22, 22, CommonComponents.GUI_BACK, ModernIcons.Icon.BACK,
            ModernButton.Kind.ICON, this::onClose));
        if (!this.perServer) {
            int x = this.width - l.margin() - 22;
            this.addRenderableWidget(new ModernButton(x, ModernPage.TOP, 22, 22, Component.translatable("report.viafabricplus.button"),
                ModernIcons.Icon.CHAT, ModernButton.Kind.ICON, () -> ReportIssuesScreen.INSTANCE.open(this)));
            x -= 28;
            ModernButton servers = new ModernButton(x, ModernPage.TOP, 22, 22, ServerListScreen.INSTANCE.getTitle(),
                ModernIcons.Icon.SERVER, ModernButton.Kind.ICON, () -> ServerListScreen.INSTANCE.open(this));
            servers.active = this.minecraft.getConnection() == null;
            this.addRenderableWidget(servers);
            x -= 28;
            this.addRenderableWidget(new ModernButton(x, ModernPage.TOP, 22, 22, Component.translatable("base.viafabricplus.settings"),
                ModernIcons.Icon.GEAR, ModernButton.Kind.ICON, () -> SettingsScreen.INSTANCE.open(this)));
        }
        if (l.searchW() > 0) {
            this.search = new EditBox(this.font, l.searchX() + 22, ModernPage.TOP + 7, l.searchW() - 30, 10, this.search,
                Component.literal(ModernVersions.text("Search versions", "搜索版本")));
            this.search.setBordered(false);
            this.search.setResponder(value -> {
                this.query = value.strip();
                this.focusIndex = -1;
                this.keyboardFocus = false;
                this.scrollTarget = 0F;
                rebuildItems();
            });
            this.addRenderableWidget(this.search);
        } else {
            this.search = null;
        }
    }

    @Override
    public void onClose() {
        // The same hand-back as VFPScreen#onClose, so VFP screens that opened this one get reopened properly.
        if (this.parent instanceof VFPScreen vfp) {
            vfp.open(vfp.prevScreen);
        } else {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    // --- choosing ---------------------------------------------------------------------------------------

    private void choose(Item item) {
        if (!item.selectable()) return;
        if (locked()) {
            this.denyStart = System.nanoTime();
            return;
        }
        this.apply.accept(item.kind() == Kind.RESET ? null : Objects.requireNonNull(item.entry()).version());
        AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
        this.pulseKey = item.key();
        this.pulseStart = System.nanoTime();
    }

    private void moveFocus(int delta) {
        List<Integer> selectable = new ArrayList<>();
        for (int i = 0; i < this.items.size(); i++) if (this.items.get(i).selectable()) selectable.add(i);
        if (selectable.isEmpty()) return;
        int position = selectable.indexOf(this.focusIndex);
        if (position < 0 || !this.keyboardFocus) {
            Item start = currentItem();
            position = start == null ? -1 : selectable.indexOf(this.items.indexOf(start));
            if (position < 0) position = delta > 0 ? -1 : selectable.size();
        }
        position = Math.max(0, Math.min(selectable.size() - 1, position + delta));
        this.focusIndex = selectable.get(position);
        this.keyboardFocus = true;
        revealItem(this.items.get(this.focusIndex));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean typing = this.search != null && this.search.isFocused();
        Layout l = layout();
        int page = Math.max(1, l.viewHeight() / ROW_H - 2);
        switch (key) {
            case InputConstants.KEY_UP -> { moveFocus(-1); return true; }
            case InputConstants.KEY_DOWN -> { moveFocus(1); return true; }
            case InputConstants.KEY_PAGEUP -> { moveFocus(-page); return true; }
            case InputConstants.KEY_PAGEDOWN -> { moveFocus(page); return true; }
            case InputConstants.KEY_HOME -> { if (!typing) { moveFocus(-this.items.size()); return true; } }
            case InputConstants.KEY_END -> { if (!typing) { moveFocus(this.items.size()); return true; } }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (this.keyboardFocus && this.focusIndex >= 0) {
                    choose(this.items.get(this.focusIndex));
                    return true;
                }
                if (typing && !this.query.isEmpty()) {
                    // Type "1.8", press Enter: take the first match.
                    for (Item item : this.items) {
                        if (item.selectable() && item.kind() != Kind.RESET) {
                            choose(item);
                            return true;
                        }
                    }
                }
            }
            default -> { }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        Layout l = layout();
        double mx = event.x(), my = event.y();
        ModernVersions.Era tab = tabAt(mx, my, l);
        if (tab != null) {
            Integer top = this.sectionTop.get(tab);
            if (top != null) {
                this.scrollTarget = clampScroll(top, l);
                this.userScrolled = true;
                AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
            }
            return true;
        }
        Item item = itemAt(mx, my, l);
        if (item != null && item.selectable() && event.button() == 0) {
            this.keyboardFocus = false;
            this.focusIndex = this.items.indexOf(item);
            choose(item);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        Layout l = layout();
        if (ModernStyle.inside(mx, my, l.listX(), l.listY(), l.listW(), l.listH())) {
            this.scrollTarget = clampScroll(this.scrollTarget - (float)scrollY * ROW_H * 2.5F, l);
            this.userScrolled = true;
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    private @Nullable Item itemAt(double mx, double my, Layout l) {
        if (mx < l.rowX() - 6 || mx >= l.rowX() + l.rowW() + 4 || my < l.viewTop() || my >= l.viewBottom()) return null;
        float contentY = (float)my - l.viewTop() + this.scroll;
        for (Item item : this.items) if (contentY >= item.y() && contentY < item.y() + item.h()) return item;
        return null;
    }

    private ModernVersions.@Nullable Era tabAt(double mx, double my, Layout l) {
        int tabsX = l.listX() + 8, tabsY = l.listY() + 8, tabW = (l.listW() - 16) / ModernVersions.Era.values().length;
        if (my < tabsY || my >= tabsY + TABS_H || mx < tabsX || mx >= tabsX + tabW * ModernVersions.Era.values().length) return null;
        ModernVersions.Era era = ModernVersions.Era.values()[(int)((mx - tabsX) / tabW)];
        return this.sectionCount.getOrDefault(era, 0) > 0 ? era : null;
    }

    private ModernVersions.Era activeEra() {
        ModernVersions.Era active = null;
        for (ModernVersions.Era era : ModernVersions.Era.values()) {
            Integer top = this.sectionTop.get(era);
            if (top == null) continue;
            if (active == null || top <= this.scroll + 12) active = era;
        }
        return active == null ? ModernVersions.Era.MODERN : active;
    }

    // --- rendering ------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.backdrop.render(g, this.minecraft, this.width, this.height, mouseX, mouseY);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (ProtocolVersion.getProtocols().size() != this.registrySize) {
            // ViaFabricPlus finishes registering versions on a background thread; pick up late arrivals.
            refreshEntries();
            rebuildItems();
            if (!this.userScrolled) centerOn(currentItem());
        }
        Layout l = layout();
        float dt = this.backdrop.dt();
        long now = System.nanoTime();
        this.scroll = ModernStyle.smooth(this.scroll, this.scrollTarget, dt, 16F);
        if (Math.abs(this.scroll - this.scrollTarget) < 0.05F) this.scroll = this.scrollTarget;
        if (Math.abs(mouseX - this.lastMouseX) + Math.abs(mouseY - this.lastMouseY) > 0.5F && itemAt(mouseX, mouseY, l) != null) {
            this.keyboardFocus = false;
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // The card previews the row under a resting pointer - not rows sliding under it while the list scrolls.
        boolean settled = Math.abs(this.scrollTarget - this.scroll) < 0.75F;
        Item hovered = settled ? itemAt(mouseX, mouseY, l) : null;
        if (hovered != null && !hovered.selectable()) hovered = null;
        Object key = hovered == null ? null : hovered.key();
        if (!Objects.equals(key, this.hoverKey)) {
            this.hoverKey = key;
            this.hoverSince = now;
        }
        Item subject = null;
        if (hovered != null && now - this.hoverSince > 120_000_000L) subject = hovered;
        else if (this.keyboardFocus && this.focusIndex >= 0) subject = this.items.get(this.focusIndex);
        if (subject == null) subject = currentItem();

        float open = ModernStyle.easeOut((now - this.openStart) / 1_000_000_000F / 0.4F);
        try (var fade = ModernStyle.alphaScope(open)) {
            ModernPage.header(g, l.margin() + 32, ModernPage.TOP + 1, EYEBROW, this.title.getString(), 1.55F);
            if (this.search != null) {
                ModernPage.fieldPill(g, l.searchX(), ModernPage.TOP + 1, l.searchW(), 20, this.search.isFocused(),
                    ModernVersions.text("Search versions", "搜索版本"), this.search.getValue().isEmpty());
            }
            g.pose().pushMatrix();
            g.pose().translate(0F, (1F - open) * 10F);
            ModernPage.card(g, l.heroX(), l.heroY(), l.heroW(), l.heroH());
            ModernPage.card(g, l.listX(), l.listY(), l.listW(), l.listH());
            drawHero(g, l, subject, now);
            drawTabs(g, l, mouseX, mouseY);
            drawList(g, l, hovered, now);
            g.pose().popMatrix();
            super.extractRenderState(g, mouseX, mouseY, a);
            drawPulse(g, now);
        }
    }

    // --- hero -------------------------------------------------------------------------------------------

    private record Hero(ModernBlocks.Block block, String name, String era, int accent, String edition, int protocol,
                        @Nullable String note, boolean noteIsBlurb, @Nullable String includes, boolean current, boolean isNative,
                        float timeline, ModernVersions.@Nullable Era eraKind) {}

    private @Nullable Hero hero(@Nullable Item item) {
        if (item == null) return null;
        if (item.kind() == Kind.RESET) {
            ProtocolVersion global = ProtocolTranslator.getTargetVersion();
            ModernVersions.Entry entry = entryFor(global);
            return new Hero(entry == null ? ModernBlocks.Block.GRASS : entry.block(), ModernVersions.text("Global version", "跟随全局版本"),
                ModernVersions.text("DEFAULT", "默认"), ModernStyle.GLOW, ModernVersions.text("No version forced", "不强制版本"), -1,
                ModernVersions.text("Connects with the global target, now ", "按全局目标版本连接，当前为 ") + global.getName(), false,
                null, isCurrent(item), false, -1F, null);
        }
        ModernVersions.Entry entry = Objects.requireNonNull(item.entry());
        ModernVersions.Era era = entry.era();
        String edition = era == ModernVersions.Era.BEDROCK ? ModernVersions.text("Bedrock Edition", "基岩版")
            : entry.auto() ? ModernVersions.text("Java Edition, 1.7+", "Java 版，1.7+")
            : entry.special() ? ModernVersions.text("Java Edition - special", "Java 版 · 特殊版本")
            : ModernVersions.text("Java Edition", "Java 版");
        ModernVersions.Note note = ModernVersions.note(entry.version());
        String eraLabel = entry.auto() ? ModernVersions.text("AUTOMATIC", "自动") : era.label();
        return new Hero(entry.block(), entry.version().getName(), eraLabel, entry.auto() ? ModernStyle.GLOW : era.accent, edition,
            ModernVersions.protocolNumber(entry), note != null ? note.text() : entry.auto() ? null : era.blurb(), note == null,
            ModernVersions.included(entry.version()), isCurrent(item), entry.version().equals(ProtocolTranslator.NATIVE_VERSION),
            timelinePosition(entry), entry.auto() ? null : era);
    }

    /** Where a Java version sits between the oldest (0) and newest (1) listed; -1 for Auto Detect and Bedrock. */
    private float timelinePosition(ModernVersions.Entry entry) {
        if (entry.auto() || entry.era() == ModernVersions.Era.BEDROCK) return -1F;
        List<ModernVersions.Entry> java = javaEntries();
        int index = java.indexOf(entry);
        return index < 0 || java.size() < 2 ? -1F : 1F - index / (float)(java.size() - 1);
    }

    private List<ModernVersions.Entry> javaEntries() {
        List<ModernVersions.Entry> java = new ArrayList<>();
        for (ModernVersions.Entry e : this.entries) if (!e.auto() && e.era() != ModernVersions.Era.BEDROCK) java.add(e);
        return java;
    }

    /**
     * The Java timeline, oldest to newest: one segment per era, as wide as the number of versions in it, lit
     * for the version's own era, with a marker at its place and the era boundaries labelled underneath.
     */
    private void drawTimeline(GuiGraphicsExtractor g, float x, float y, float w, Hero hero) {
        List<ModernVersions.Entry> java = javaEntries();
        if (java.size() < 2) return;
        ModernVersions.Era[] order = {ModernVersions.Era.LEGACY, ModernVersions.Era.MIDDLE, ModernVersions.Era.MODERN};
        ModernPage.label(g, x, y, ModernVersions.text("TIMELINE", "时间线"));
        float barY = y + 13F;
        float segX = x;
        ModernVersions.Era heroEra = hero.eraKind();
        for (ModernVersions.Era era : order) {
            int count = 0;
            for (ModernVersions.Entry e : java) if (e.era() == era) count++;
            if (count == 0) continue;
            float segW = w * count / java.size();
            int color = era == heroEra ? era.accent : ModernTypography.fade(era.accent, 0.35F);
            ModernStyle.rounded(g, Math.round(segX), Math.round(barY), Math.max(2, Math.round(segW) - 2), 4, 2, color);
            String edge = era == ModernVersions.Era.LEGACY ? ModernVersions.text("Classic", "经典版")
                : era == ModernVersions.Era.MIDDLE ? "1.9" : "1.17";
            ModernTypography.draw(g, ModernTypography.Face.TEXT, edge, segX, barY + 8F, 0.75F, 0xFF6F8FA4);
            segX += segW;
        }
        String newest = java.getFirst().version().getName();
        ModernTypography.draw(g, ModernTypography.Face.TEXT, newest, x + w - ModernTypography.width(ModernTypography.Face.TEXT, newest, 0.75F),
            barY + 8F, 0.75F, 0xFF6F8FA4);
        if (hero.timeline() >= 0F) {
            float mx = x + w * hero.timeline();
            ModernStyle.halo(g, Math.round(mx) - 3, Math.round(barY) - 2, 7, 8, 4, ModernStyle.GLOW, 0.6F);
            ModernStyle.rounded(g, Math.round(mx) - 3, Math.round(barY) - 2, 7, 8, 3, 0xFFFFFFFF);
        }
    }

    private void drawHero(GuiGraphicsExtractor g, Layout l, @Nullable Item subject, long now) {
        Object key = subject == null ? null : subject.key();
        if (!Objects.equals(key, this.heroKey)) {
            this.heroKey = key;
            this.heroStart = now;
        }
        Hero hero = hero(subject);
        if (hero == null) return;
        float change = ModernStyle.easeOut((now - this.heroStart) / 1_000_000_000F / 0.22F);
        try (var fade = ModernStyle.alphaScope(change)) {
            g.pose().pushMatrix();
            g.pose().translate(0F, (1F - change) * 5F);
            if (l.wide()) drawHeroCard(g, l, hero, now);
            else drawHeroStrip(g, l, hero, now);
            g.pose().popMatrix();
        }
    }

    private void drawHeroCard(GuiGraphicsExtractor g, Layout l, Hero hero, long now) {
        int x = l.heroX(), y = l.heroY(), w = l.heroW(), h = l.heroH(), pad = 18;
        float time = this.backdrop.time();
        float size = 58F;
        float bob = (float)Math.sin(time * 1.6F) * 1.5F;
        float bx = x + pad, by = y + pad + 2 + bob;
        this.heroBlockCx = bx + size / 2F;
        this.heroBlockCy = by + size / 2F;
        float breathe = 0.2F + 0.07F * (float)Math.sin(time * 1.3F);
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, bx - size * 0.55F, by - size * 0.5F, size * 2.1F, Math.round(255 * breathe) << 24 | (hero.accent() & 0xFFFFFF));
        ModernStyle.rounded(g, Math.round(bx + 10 - bob), Math.round(y + pad + size + 6), Math.round(size - 20 + bob * 2), 4, 2, 0x38000000);
        ModernBlocks.draw(g, hero.block(), bx, by, size);

        float tx = x + pad + size + 14;
        float textW = x + w - pad - tx;
        String eraLabel = ModernVersions.chinese() ? hero.era() : ModernStyle.spaced(hero.era().toUpperCase(java.util.Locale.ROOT));
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(eraLabel, Math.round(textW / 0.85F)), tx, y + pad + 6, 0.85F, hero.accent());
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(hero.edition(), Math.round(textW)), tx, y + pad + 20, 1F, 0xFFD9E8F1);
        float lineY = y + pad + 34;
        if (hero.protocol() >= 0) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernVersions.text("Protocol ", "协议号 ") + hero.protocol(), tx, lineY, 0.9F, 0xFF7F9FB4);
            lineY += 14;
        }
        if (hero.isNative()) ModernPage.chip(g, Math.round(tx), Math.round(lineY), ModernVersions.text("NATIVE", "原生"), 0xFF0E2A3B, 0xE67FE3FF);

        float nameY = y + pad + size + 18;
        float maxNameW = w - 2 * pad;
        float nameScale = Math.max(1.2F, Math.min(2.2F, maxNameW / Math.max(1F, ModernTypography.width(ModernTypography.Face.DISPLAY, hero.name(), 1F))));
        String name = ModernTypography.width(ModernTypography.Face.DISPLAY, hero.name(), nameScale) > maxNameW
            ? ModernTypography.wrap(ModernTypography.Face.DISPLAY, hero.name(), nameScale, maxNameW, 1).getFirst() : hero.name();
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, name, x + pad, nameY, nameScale, 0xFFF2F8FC);
        float cy = nameY + 11F * nameScale + 6F;
        ModernStyle.fill(g, x + pad, Math.round(cy), x + pad + 22, Math.round(cy) + 1, hero.accent());
        cy += 9F;

        float statusTop = y + h - 36;
        float timelineTop = statusTop - 38;
        boolean timeline = hero.timeline() >= 0F && timelineTop - cy > 40;
        float textBottom = timeline ? timelineTop - 6 : statusTop;
        if (hero.note() != null) {
            ModernTypography.Face face = hero.noteIsBlurb() ? ModernTypography.Face.TEXT : ModernTypography.Face.DISPLAY_ITALIC;
            float scale = hero.noteIsBlurb() ? 1F : 1.12F, lineH = hero.noteIsBlurb() ? 12F : 14F;
            int color = hero.noteIsBlurb() ? 0xFF9DBCD0 : 0xFFE3F2FA;
            int maxLines = Math.max(1, Math.min(4, (int)((textBottom - cy - 8) / lineH)));
            List<String> lines = ModernTypography.wrap(face, hero.note(), scale, maxNameW, maxLines);
            for (String line : lines) {
                ModernTypography.draw(g, face, line, x + pad, cy, scale, color);
                cy += lineH;
            }
            cy += 8F;
        }
        if (hero.includes() != null && cy + 22 < textBottom) {
            ModernPage.label(g, x + pad, cy, ModernVersions.text("INCLUDES", "包含版本"));
            cy += 11F;
            int maxLines = Math.max(1, Math.min(3, (int)((textBottom - cy - 4) / 11F)));
            for (String line : ModernTypography.wrap(ModernTypography.Face.TEXT, hero.includes(), 0.9F, maxNameW, maxLines)) {
                ModernTypography.draw(g, ModernTypography.Face.TEXT, line, x + pad, cy, 0.9F, 0xFF7F9FB4);
                cy += 11F;
            }
        }

        if (timeline) drawTimeline(g, x + pad, timelineTop, maxNameW, hero);
        ModernStyle.fill(g, x + pad, Math.round(statusTop), x + w - pad, Math.round(statusTop) + 1, 0x1AFFFFFF);
        drawStatus(g, x + pad, statusTop + 11F, maxNameW, hero, now);
    }

    private void drawStatus(GuiGraphicsExtractor g, float x, float y, float maxW, Hero hero, long now) {
        float deny = (now - this.denyStart) / 1_000_000_000F / 0.45F;
        if (this.denyStart != 0L && deny < 1F) x += (float)Math.sin(deny * 38F) * (1F - deny) * 3.5F;
        ModernIcons.Icon icon;
        int color;
        String text;
        if (locked()) {
            icon = ModernIcons.Icon.LOCK;
            color = deny < 1F && this.denyStart != 0L ? ModernRows.BAD : ModernRows.FAIR;
            text = ModernVersions.text("Connected - leave to switch versions", "已连接服务器，退出后才能切换");
        } else if (hero.current()) {
            icon = ModernIcons.Icon.CHECK;
            color = ModernStyle.GLOW;
            text = this.perServer ? ModernVersions.text("Used for this server", "此服务器正在使用")
                : ModernVersions.text("Current target version", "当前目标版本");
        } else {
            icon = ModernIcons.Icon.ENTER;
            color = 0xFF9DBCD0;
            text = this.keyboardFocus ? ModernVersions.text("Press Enter to switch", "按 Enter 切换")
                : ModernVersions.text("Click to switch", "点击切换");
        }
        ModernIcons.draw(g, icon, x, y - 0.5F, 10F, color);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(text, Math.round(maxW - 15)), x + 15F, y, 1F, color);
    }

    private void drawHeroStrip(GuiGraphicsExtractor g, Layout l, Hero hero, long now) {
        int x = l.heroX(), y = l.heroY(), w = l.heroW(), h = l.heroH();
        float bob = (float)Math.sin(this.backdrop.time() * 1.6F);
        float size = 30F, bx = x + 10, by = y + (h - size) / 2F + bob * 0.8F;
        this.heroBlockCx = bx + size / 2F;
        this.heroBlockCy = by + size / 2F;
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, bx - 12F, by - 12F, size + 24F, 0x33000000 | (hero.accent() & 0xFFFFFF));
        ModernBlocks.draw(g, hero.block(), bx, by, size);
        float tx = x + 50, room = w - 50 - 30;
        float scale = Math.max(1F, Math.min(1.4F, room / Math.max(1F, ModernTypography.width(ModernTypography.Face.DISPLAY, hero.name(), 1F))));
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, ModernTypography.wrap(ModernTypography.Face.DISPLAY, hero.name(), scale, room, 1).getFirst(),
            tx, y + 7, scale, 0xFFF2F8FC);
        String sub = hero.era() + (hero.note() == null ? "" : "  ·  " + hero.note());
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(sub, Math.round(room / 0.9F)), tx, y + 28, 0.9F, 0xFF8FB0C4);
        ModernIcons.Icon icon = locked() ? ModernIcons.Icon.LOCK : hero.current() ? ModernIcons.Icon.CHECK : ModernIcons.Icon.ENTER;
        int color = locked() ? ModernRows.FAIR : hero.current() ? ModernStyle.GLOW : 0xFF7F9FB4;
        ModernIcons.draw(g, icon, x + w - 22F, y + h / 2F - 6F, 12F, color);
    }

    /** The burst when a version is chosen: a flash of the era's light and a ring of ice shards flying out. */
    private void drawPulse(GuiGraphicsExtractor g, long now) {
        if (this.pulseStart == 0L) return;
        float t = (now - this.pulseStart) / 1_000_000_000F / 0.75F;
        if (t >= 1F) return;
        float e = ModernStyle.easeOut(t);
        int accent = ModernStyle.GLOW;
        Item item = currentItem();
        if (item != null && item.entry() != null && !item.entry().auto()) accent = item.entry().era().accent;
        float cx = this.heroBlockCx, cy = this.heroBlockCy;
        float r = 18F + 58F * e;
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r, cy - r, r * 2F, Math.round(150 * (1F - t)) << 24 | (accent & 0xFFFFFF));
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4 + 0.35;
            float d = 14F + 46F * e;
            ModernIcons.drawRotated(g, ModernIcons.Icon.CRYSTAL, cx + (float)Math.cos(angle) * d, cy + (float)Math.sin(angle) * d,
                8F - 3F * t, (float)angle + t * 2.2F, ModernTypography.fade(0xFFE6F7FF, 1F - t));
        }
    }

    // --- tabs and list ----------------------------------------------------------------------------------

    private void drawTabs(GuiGraphicsExtractor g, Layout l, int mouseX, int mouseY) {
        ModernVersions.Era[] eras = ModernVersions.Era.values();
        int tabsX = l.listX() + 8, tabsY = l.listY() + 8, tabW = (l.listW() - 16) / eras.length;
        ModernVersions.Era active = activeEra();
        ModernVersions.Era hoveredTab = tabAt(mouseX, mouseY, l);
        for (int i = 0; i < eras.length; i++) {
            ModernVersions.Era era = eras[i];
            int x = tabsX + i * tabW;
            int count = this.sectionCount.getOrDefault(era, 0);
            if (era == hoveredTab) ModernStyle.rounded(g, x + 1, tabsY, tabW - 2, TABS_H - 2, 7, 0x17FFFFFF);
            try (var dim = ModernStyle.alphaScope(count > 0 ? 1F : 0.4F)) {
                ModernBlocks.draw(g, era.block, x + 7F, tabsY + 5F, 12F);
                String label = era.label();
                String number = String.valueOf(count);
                float numberW = ModernTypography.width(ModernTypography.Face.TEXT, number, 0.85F);
                int room = Math.round(tabW - 24 - numberW - 10);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(label, room), x + 23F, tabsY + 6.5F, 1F,
                    era == active ? 0xFFF2F8FC : 0xFF9DBCD0);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, number, x + tabW - 7F - numberW, tabsY + 7.5F, 0.85F, 0xFF6F8FA4);
            }
        }
        int index = active.ordinal();
        float targetX = tabsX + index * tabW + 8F, targetW = tabW - 16F;
        float dt = this.backdrop.dt();
        this.underlineX = this.underlineX < 0 ? targetX : ModernStyle.smooth(this.underlineX, targetX, dt, 14F);
        this.underlineW = this.underlineW <= 0 ? targetW : ModernStyle.smooth(this.underlineW, targetW, dt, 14F);
        ModernStyle.fill(g, tabsX, tabsY + TABS_H, tabsX + tabW * eras.length, tabsY + TABS_H + 1, 0x14FFFFFF);
        ModernStyle.rounded(g, Math.round(this.underlineX), tabsY + TABS_H - 1, Math.round(this.underlineW), 2, 1, active.accent);
    }

    private void drawList(GuiGraphicsExtractor g, Layout l, @Nullable Item hovered, long now) {
        if (l.viewHeight() <= 0) return;
        g.enableScissor(l.listX() + 2, l.viewTop(), l.listX() + l.listW() - 2, l.viewBottom());
        try {
            if (this.items.isEmpty()) {
                String empty = ModernVersions.text("No version matches", "没有匹配的版本");
                ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, empty,
                    l.listX() + (l.listW() - ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, empty, 1.3F)) / 2F,
                    l.viewTop() + l.viewHeight() / 2F - 8F, 1.3F, 0xFF9DBCD0);
            }
            boolean locked = locked();
            for (int i = 0; i < this.items.size(); i++) {
                Item item = this.items.get(i);
                float iy = l.viewTop() + item.y() - this.scroll;
                if (iy + item.h() < l.viewTop() || iy > l.viewBottom()) continue;
                int y = Math.round(iy);
                switch (item.kind()) {
                    case SECTION -> drawSection(g, l, item, y);
                    case STAGE -> drawStage(g, l, item, y);
                    default -> drawRow(g, l, item, y, item == hovered, this.keyboardFocus && i == this.focusIndex, locked, now);
                }
            }
            // Soft edges where rows slide under the tabs and the card's bottom.
            ModernStyle.fillGradient(g, l.listX() + 2, l.viewTop(), l.listX() + l.listW() - 2, l.viewTop() + 8, 0x800A1B29, 0x000A1B29);
            ModernStyle.fillGradient(g, l.listX() + 2, l.viewBottom() - 8, l.listX() + l.listW() - 2, l.viewBottom(), 0x000A1B29, 0x800A1B29);
        } finally {
            g.disableScissor();
        }
        if (this.contentHeight > l.viewHeight()) {
            int viewH = l.viewHeight();
            int thumbH = Math.max(18, viewH * viewH / this.contentHeight);
            float max = this.contentHeight - viewH;
            int thumbY = l.viewTop() + Math.round((viewH - thumbH) * (this.scroll / max));
            int trackX = l.listX() + l.listW() - 8;
            ModernStyle.rounded(g, trackX, l.viewTop(), 2, viewH, 1, 0x0FFFFFFF);
            ModernStyle.rounded(g, trackX - 1, thumbY, 4, thumbH, 2, 0x59DDF3FF);
        }
    }

    private void drawSection(GuiGraphicsExtractor g, Layout l, Item item, int y) {
        ModernVersions.Era era = Objects.requireNonNull(item.era());
        int x = l.rowX();
        ModernBlocks.draw(g, era.block, x - 2F, y + 7F, 20F);
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, era.label(), x + 24F, y + 7F, 1.45F, 0xFFEAF4FA);
        String count = String.valueOf(this.sectionCount.getOrDefault(era, 0));
        float countW = ModernTypography.width(ModernTypography.Face.TEXT, count, 0.9F);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, count, x + l.rowW() - countW, y + 13F, 0.9F, 0xFF6F8FA4);
        int lineY = y + SECTION_H - 4;
        ModernStyle.fill(g, x, lineY, x + l.rowW(), lineY + 1, 0x12FFFFFF);
        ModernStyle.fill(g, x, lineY, x + 36, lineY + 1, ModernTypography.fade(era.accent, 0.7F));
    }

    private void drawStage(GuiGraphicsExtractor g, Layout l, Item item, int y) {
        String label = ModernVersions.stageLabel(Objects.requireNonNull(item.stage()));
        if (!ModernVersions.chinese()) label = ModernStyle.spaced(label);
        float x = l.rowX() + 20F;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, label, x, y + 6F, 0.78F, 0xFF6F92A8);
        int lineX = Math.round(x + ModernTypography.width(ModernTypography.Face.TEXT, label, 0.78F) + 8F);
        ModernStyle.fill(g, lineX, y + 10, l.rowX() + l.rowW(), y + 11, 0x0FFFFFFF);
    }

    private void drawRow(GuiGraphicsExtractor g, Layout l, Item item, int y, boolean hovered, boolean focused, boolean locked, long now) {
        boolean pinned = item.kind() != Kind.ROW;
        boolean current = isCurrent(item);
        int x0 = l.rowX() - 6, w0 = l.rowW() + 8;
        int top = pinned ? y + 2 : y + 1, h = pinned ? item.h() - 6 : item.h() - 2;
        float flash = Objects.equals(this.pulseKey, item.key()) ? 1F - Math.min(1F, (now - this.pulseStart) / 1_000_000_000F / 0.6F) : 0F;
        if (pinned) ModernStyle.rounded(g, x0, top, w0, h, 8, 0x10FFFFFF);
        if (current) {
            ModernStyle.rounded(g, x0, top, w0, h, 7, ModernStyle.mix(0x2ECDEBFF, 0x70CDEBFF, flash));
            ModernStyle.rounded(g, x0 + 1, top + 4, 2, h - 8, 1, ModernStyle.GLOW);
        } else if (focused) {
            ModernStyle.rounded(g, x0, top, w0, h, 7, 0x24BFE8FF);
            ModernStyle.rounded(g, x0 + 1, top + 4, 2, h - 8, 1, 0x99BFE8FF);
        } else if (hovered) {
            ModernStyle.rounded(g, x0, top, w0, h, 7, locked ? 0x0CFFFFFF : 0x17FFFFFF);
        }

        try (var dim = ModernStyle.alphaScope(locked && !current ? 0.55F : 1F)) {
            int x = l.rowX();
            float textY = top + h / 2F - 5F;
            String name;
            @Nullable String note;
            if (item.kind() == Kind.RESET) {
                ModernIcons.draw(g, ModernIcons.Icon.REFRESH, x + 1F, top + h / 2F - 6F, 12F, ModernStyle.GLOW);
                name = ModernVersions.text("Follow the global version", "跟随全局版本");
                note = ProtocolTranslator.getTargetVersion().getName();
            } else {
                ModernVersions.Entry entry = Objects.requireNonNull(item.entry());
                float size = pinned ? 14F : 11F;
                ModernBlocks.draw(g, entry.block(), x + (pinned ? 0F : 1.5F), top + h / 2F - size / 2F, size);
                name = entry.version().getName();
                ModernVersions.Note n = ModernVersions.note(entry.version());
                note = n == null ? null : n.text();
            }
            int right = x + l.rowW();
            if (current) {
                ModernIcons.draw(g, ModernIcons.Icon.CHECK, right - 10F, top + h / 2F - 5F, 10F, ModernStyle.GLOW);
                right -= 16;
            }
            if (item.entry() != null && item.entry().version().equals(ProtocolTranslator.NATIVE_VERSION)) {
                String badge = ModernVersions.text("NATIVE", "原生");
                int badgeW = Math.round(ModernTypography.width(ModernTypography.Face.TEXT, badge, 0.9F)) + 12;
                ModernPage.chip(g, right - badgeW, top + h / 2 - 7, badge, 0xFF0E2A3B, 0xD97FE3FF);
                right -= badgeW + 6;
            }
            boolean special = item.entry() != null && item.entry().special() && !pinned && item.entry().era() != ModernVersions.Era.BEDROCK;
            int nameColor = current ? 0xFFFFFFFF : special ? 0xFFEBD9A8 : 0xFFD6E4EE;
            float nameX = x + (pinned ? 22F : 20F);
            if (pinned) {
                float nameW = Math.min(ModernTypography.width(ModernTypography.Face.TEXT, name, 1.05F), right - nameX - 8);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(name, Math.round(nameW / 1.05F)), nameX, textY - 0.5F, 1.05F, nameColor);
                if (note != null) {
                    float noteX = nameX + nameW + 10F;
                    if (right - noteX > 30) ModernTypography.draw(g, ModernTypography.Face.TEXT,
                        ModernTypography.ellipsize(note, Math.round((right - noteX - 4) / 0.9F)), noteX, textY + 0.6F, 0.9F, 0xFF7F9FB4);
                }
            } else {
                float nameW = Math.min(ModernTypography.width(ModernTypography.Face.TEXT, name, 1F), right - nameX - 8);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(name, Math.round(nameW)), nameX, textY, 1F, nameColor);
                if (note != null) {
                    float noteX = nameX + Math.max(this.nameColumn, nameW) + 12F;
                    if (right - noteX > 30) ModernTypography.draw(g, ModernTypography.Face.TEXT,
                        ModernTypography.ellipsize(note, Math.round((right - noteX - 4) / 0.9F)), noteX, textY + 0.6F, 0.9F,
                        current ? 0xFFA9C4D6 : 0xFF7F9FB4);
                }
            }
        }
    }
}
