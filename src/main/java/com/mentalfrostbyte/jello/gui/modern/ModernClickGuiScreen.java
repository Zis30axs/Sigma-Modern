package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClickGuiInteractions;
import com.mentalfrostbyte.jello.gui.SigmaClickGui;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * SigmaModern's ClickGUI: a single frosted workspace card, a category sidebar and a content pane.
 *
 * <p>The {@code Jello-Modern.html} reference used a horizontal category tab bar, which fit its ~1500px
 * browser viewport. A Minecraft window at typical GUI scale gives this screen far less width, and a fixed
 * count of categories (nine) in a shrinking horizontal strip just truncates - a literal port of that bar
 * overlapped its own labels at normal window sizes. A vertical sidebar is the native pattern for this
 * instead: its width doesn't depend on how many categories there are or how long their names are, and it's
 * how most Minecraft ClickGUIs already present a category list. Everything else the reference does well -
 * the glass card, the module-detail drill-in, the search view - carries over unchanged.</p>
 */
public final class ModernClickGuiScreen extends Screen implements SigmaClickGui, ModernBlurredBackdrop, com.mentalfrostbyte.jello.gui.TextEntryScreen {
    // Public so GuiScreenInteractionSmoke (a different package) can compute click targets from the exact
    // same numbers this screen lays out with, instead of duplicating magic constants that could drift.
    public static final int HEADER_H = 44;
    public static final int SECTION_H = 24;
    public static final int SEARCH_FIELD_H = 40;
    public static final int ROW_H = 40;
    public static final int FOOTER_H = 24;
    public static final int PAD = 14;
    public static final int SIDEBAR_W = 128;
    public static final int SIDEBAR_GAP = 12;
    public static final int SIDEBAR_ITEM_H = 30;
    private static final int SIDEBAR_ITEM_MIN_H = 20;
    private static final float COMPASS_PX_PER_DEG = 2.4F;

    private enum View { CATEGORY, DETAIL, SEARCH }

    private final ModuleManager modules;
    private final ClickGuiInteractions interactions = new ClickGuiInteractions();
    private final java.util.Map<Object, Float> anim = new java.util.HashMap<>();

    private View view = View.CATEGORY;
    private ModuleCategory activeCategory = ModuleCategory.values()[0];
    private Module detailModule;
    private String search = "";
    private int scroll;
    private int contentHeight;
    private float panelHeight;
    private long lastFrame;
    private float seconds;
    private float time;
    private final long openStart = System.nanoTime();
    private long contentChangeStart;
    private final ModernSnow snow = new ModernSnow(140, 5400F);
    // The music player lives in its own window, pulled out from the right edge; the card makes room for it.
    private final ModernMusicDrawer music = new ModernMusicDrawer(282);

    private record Hit(int x, int y, int w, int h, Object key, Module module, Setting<?> setting) {}
    private record SidebarHit(int x, int y, int w, int h, ModuleCategory category) {}

    public ModernClickGuiScreen(ModuleManager modules) {
        super(Component.literal("SigmaModern"));
        this.modules = modules;
    }

    @Override public boolean isPauseScreen() { return false; }

    // --- geometry -----------------------------------------------------------------------------------

    // With the music window out, the card narrows and moves over (down to 300 wide; past that the window overlaps it).
    public int panelWidth() { return Math.max(300, Math.min(this.width - PAD * 2 - Math.round(this.music.reserve()), 620)); }
    public int panelX() { return Math.max(PAD, (this.width - Math.round(this.music.reserve()) - panelWidth()) / 2); }
    // Tall enough that the compass sits below the custom window caption (ModernWindowFrame.CAPTION_H).
    private int topbarH() { return this.height < 260 ? 0 : 46; }
    public int panelY() { return topbarH() + (topbarH() > 0 ? 14 : 10); }
    // Leaves room under the card for the global key hints line.
    private int maxPanelHeight() { return Math.max(160, this.height - panelY() - 26); }

    private Hit headerHit() {
        int w = panelWidth();
        return new Hit(panelX() + w - 36, panelY() + 5, 34, 34, "search", null, null);
    }

    /** Extra header space below the title row, before the row list: the section heading, or the search field. */
    private int subheaderHeight() {
        return switch (this.view) {
            case CATEGORY -> SECTION_H;
            case SEARCH -> SEARCH_FIELD_H;
            case DETAIL -> 0;
        };
    }

    /** Row content sits right of the sidebar in CATEGORY view; DETAIL/SEARCH use the full content width. */
    private int rowContentX() {
        int x = panelX() + PAD;
        if (this.view == View.CATEGORY) x += SIDEBAR_W + SIDEBAR_GAP;
        return x;
    }

    private int rowContentW() {
        int w = panelWidth() - PAD * 2;
        if (this.view == View.CATEGORY) w -= SIDEBAR_W + SIDEBAR_GAP;
        return w;
    }

    /**
     * Sidebar rows shrink (down to {@code SIDEBAR_ITEM_MIN_H}) so every category fits inside the tallest card
     * the window allows - otherwise the last categories would be clipped off, and unclickable, on short windows.
     */
    public int sidebarItemHeight() {
        int available = maxPanelHeight() - HEADER_H - FOOTER_H - 4;
        return Math.max(SIDEBAR_ITEM_MIN_H, Math.min(SIDEBAR_ITEM_H, available / ModuleCategory.values().length));
    }

    private int sidebarHeight() { return ModuleCategory.values().length * sidebarItemHeight(); }

    private List<SidebarHit> sidebarItems() {
        List<SidebarHit> out = new ArrayList<>();
        ModuleCategory[] categories = ModuleCategory.values();
        int x = panelX() + PAD, y = panelY() + HEADER_H, itemH = sidebarItemHeight();
        for (int i = 0; i < categories.length; i++) {
            out.add(new SidebarHit(x, y + i * itemH, SIDEBAR_W, itemH, categories[i]));
        }
        return out;
    }

    private List<Hit> rows() {
        List<Hit> hits = new ArrayList<>();
        int contentX = rowContentX();
        int contentW = rowContentW();
        int y = panelY() + HEADER_H + subheaderHeight() - this.scroll;

        if (this.view == View.DETAIL) {
            Module m = this.detailModule;
            hits.add(new Hit(contentX, y, contentW, 56, "detail-toggle", m, null));
            y += 64;
            for (Setting<?> setting : m.settings()) {
                if (!setting.isVisible()) continue;
                int h = setting instanceof NumberSetting ? 52 : 40;
                hits.add(new Hit(contentX, y, contentW, h, setting, m, setting));
                y += h;
            }
            hits.add(new Hit(contentX, y, contentW, 40, "keybind", m, null));
            y += 40;
        } else {
            List<Module> pool = this.view == View.SEARCH ? filteredModules() : this.modules.byCategory(this.activeCategory);
            if (pool.isEmpty()) {
                hits.add(new Hit(contentX, y, contentW, 30, "empty", null, null));
                y += 30;
            } else {
                for (Module module : pool) {
                    hits.add(new Hit(contentX, y, contentW, ROW_H, module, module, null));
                    y += ROW_H;
                }
            }
        }
        this.contentHeight = y + this.scroll - (panelY() + HEADER_H + subheaderHeight());
        return hits;
    }

    private List<Module> filteredModules() {
        String q = this.search.trim().toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        for (Module m : this.modules.all()) {
            if (q.isEmpty() || m.getName().toLowerCase(Locale.ROOT).contains(q)
                || m.getDescription().toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        return out;
    }

    private int visibleContentTop() {
        return panelY() + HEADER_H + subheaderHeight();
    }

    // --- rendering ------------------------------------------------------------------------------------

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
        // With no world behind it, the ClickGUI sits on the same painted night as the main menu - blurred
        // below, since it's drawn before the blur boundary; the snow drawn later stays sharp in front.
        if (this.minecraft.level == null) {
            ModernScene.shared().render(g, this.width, this.height, 0F, 0F, this.time);
            // blurBeforeThisStratum() blurs earlier strata only; the scene must end its own stratum to be blurred.
            g.nextStratum();
        }
        GuiVisuals.blurBackground(g);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    /**
     * The panel height this frame's layout calls for, independent of the animated {@link #panelHeight}
     * used only for drawing. Hit-testing (mouse clicks/scroll) uses this directly rather than the animated
     * value, because that value is only ever set from {@link #extractRenderState} - a click can otherwise
     * arrive (for example from {@code GuiScreenInteractionSmoke}, which drives input synchronously right
     * after the screen is created) before this screen has rendered even once.
     */
    private int targetPanelHeight() {
        rows();
        // In CATEGORY view the panel must stay tall enough to show every sidebar entry, even if the
        // selected category itself has few (or zero) modules.
        int neededContent = this.view == View.CATEGORY ? Math.max(this.contentHeight, sidebarHeight()) : this.contentHeight;
        int viewport = maxPanelHeight() - HEADER_H - subheaderHeight() - FOOTER_H;
        return Math.min(maxPanelHeight(),
            HEADER_H + subheaderHeight() + Math.min(neededContent, Math.max(viewport, ROW_H)) + FOOTER_H);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        placeMusic();
        boolean overMusic = this.music.contains(mouseX, mouseY);
        int mx = overMusic ? -10000 : mouseX, my = overMusic ? -10000 : mouseY;
        long now = System.nanoTime();
        this.seconds = this.lastFrame == 0 ? 0 : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
        this.lastFrame = now;
        this.time += this.seconds;
        float open = ModernStyle.easeOut((now - this.openStart) / 1_000_000_000F / 0.32F);

        try (var fade = ModernStyle.alphaScope(open)) {
            ModernStyle.fill(g, 0, 0, this.width, this.height, 0x40071019);
            this.snow.update(this.width, this.height, this.seconds, 6F);
            this.snow.render(g, 0F, 0.55F);
            if (topbarH() > 0 && this.minecraft.player != null) drawCompass(g);
        }

        int targetHeight = targetPanelHeight();
        this.panelHeight = this.panelHeight <= 1 ? targetHeight : ModernStyle.smooth(this.panelHeight, targetHeight, this.seconds, 14);
        int panelX = panelX(), panelY = panelY(), panelW = panelWidth(), panelH = Math.round(this.panelHeight);

        int maxScroll = Math.max(0, this.contentHeight - (targetHeight - HEADER_H - subheaderHeight() - FOOTER_H));
        List<Hit> hits = rows();
        if (this.scroll > maxScroll) { this.scroll = maxScroll; hits = rows(); }

        // Opening: the card rises a little as it fades in. Hit-testing ignores the transient offset.
        g.pose().pushMatrix();
        try (var fade = ModernStyle.alphaScope(open)) {
            g.pose().translate(0F, (1F - open) * 16F);
            ModernStyle.glassCard(g, panelX, panelY, panelW, panelH, 12);
            drawWatermark(g, panelX, panelY, panelW, panelH);
            drawHeader(g, panelX, panelY, panelW, mx, my);

            // Everything below the header - sidebar, section heading/search field, and the row/detail list -
            // shares one scissor clipped to the card's own (possibly still-animating) bounds. The card's
            // height is capped to the window, so a category with a short module list can still leave the
            // sidebar taller than the space actually available; without this, the sidebar's own tail end
            // would draw straight through the footer instead of just being clipped like everything else here.
            int chromeTop = panelY + HEADER_H;
            int contentBottom = panelY + panelH - FOOTER_H;
            g.enableScissor(panelX + 4, chromeTop, panelX + panelW - 4, Math.max(chromeTop, contentBottom));
            if (this.view == View.CATEGORY) drawSidebar(g, mx, my);
            // Switching category or view cross-fades the content pane and nudges it up into place.
            float content = this.contentChangeStart == 0L ? 1F
                : ModernStyle.easeOut((now - this.contentChangeStart) / 1_000_000_000F / 0.22F);
            g.pose().pushMatrix();
            try (var contentFade = ModernStyle.alphaScope(content)) {
                g.pose().translate(0F, (1F - content) * 7F);
                if (this.view == View.CATEGORY) drawSectionHeading(g);
                else if (this.view == View.SEARCH) drawSearchField(g);
                if (this.view == View.DETAIL) drawDetail(g, hits, mx, my);
                else drawRows(g, hits, mx, my);
            } finally {
                g.pose().popMatrix();
            }
            g.disableScissor();

            drawFooter(g, panelX, panelY, panelW, panelH);
        } finally {
            g.pose().popMatrix();
        }
        if (topbarH() > 0) {
            try (var fade = ModernStyle.alphaScope(open)) {
                drawGlobalHints(g);
            }
        }
        try (var fade = ModernStyle.alphaScope(open)) {
            placeMusic();
            this.music.render(g, mouseX, mouseY);
        }
    }

    /** The music window's band: level with the card's top (clear of the window caption), down to above the hints. */
    private void placeMusic() {
        this.music.place(this.width, Math.max(panelY(), ModernWindowFrame.reservedTop() + 6), this.height - 26);
    }

    /** Test seams for {@code GuiScreenInteractionSmoke}. */
    public int[] musicTabBounds() {
        placeMusic();
        return this.music.tabBounds();
    }

    public boolean isMusicOpen() {
        return this.music.isOpen();
    }

    public int[] musicControl(String name) {
        placeMusic();
        return this.music.control(name);
    }

    public static void closeMusicNow() {
        ModernMusicDrawer.setOpenNow(false);
        ModernMusicView.resetPages();
    }

    public String musicPage() {
        return ModernMusicView.pageName();
    }

    public boolean isMusicTyping() {
        return this.music.isTyping();
    }

    /** Test seam: whether the ClickGUI itself is on its category view (not its own search or a module's detail). */
    public boolean isOnCategoryView() {
        return this.view == View.CATEGORY;
    }

    /**
     * A large, very faint faceted Σ in the card's bottom-right corner - the same mark the main menu and the
     * window caption carry, low enough in opacity to read as texture rather than content. Sized from the
     * card's width, not its (animated) height, so it never re-rasterizes while the card resizes.
     */
    private void drawWatermark(GuiGraphicsExtractor g, int panelX, int panelY, int panelW, int panelH) {
        float size = Math.min(120F, panelW * 0.2F);
        ModernIcons.draw(g, ModernIcons.Icon.SIGMA, panelX + panelW - 20 - size * 0.9F, panelY + panelH - FOOTER_H - 10 - size,
            size, 0x16BFE8FF);
    }

    private void drawHeader(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        if (this.view == View.DETAIL) {
            ModernTypography.draw(g, "‹ " + this.activeCategory.getDisplayName(), x + PAD, y + 16, ModernStyle.BLUE, false);
        } else {
            String label = this.view == View.SEARCH ? "SEARCH" : "MODULES";
            ModernStyle.fill(g, x + PAD, y + 14, x + PAD + 3, y + 26, ModernStyle.GLOW);
            ModernTypography.draw(g, ModernStyle.spaced(label), x + PAD + 11, y + 16, ModernStyle.INK_MUTED, false);
        }
        Hit search = headerHit();
        float hover = animate(search.key(), ModernStyle.inside(mx, my, search.x(), search.y(), search.w(), search.h()) ? 1 : 0);
        if (this.view != View.SEARCH) {
            ModernStyle.rounded(g, search.x(), search.y(), search.w(), search.h(), 8, (Math.round(16 + hover * 20) << 24) | 0x1C394A);
            ModernTypography.draw(g, "/", search.x() + 13, search.y() + 11, ModernStyle.INK_MUTED, false);
        }
        ModernStyle.fill(g, x + 1, y + HEADER_H - 1, x + w - 1, y + HEADER_H, 0x1EBFEEFF);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        List<SidebarHit> items = sidebarItems();
        SidebarHit activeItem = items.get(this.activeCategory.ordinal());
        float pillY = animate("sidebar-pill-y", activeItem.y());
        int py = Math.round(pillY), ph = activeItem.h() - 4;
        // The active category reads as backlit ice - a soft glow, a faint tinted fill and a left accent
        // bar - rather than a flat highlight block, echoing the same left-bar language an enabled module
        // row already uses so "selected"/"on" means the same thing everywhere in this screen.
        ModernStyle.halo(g, activeItem.x(), py, activeItem.w(), ph, 7, ModernStyle.GLOW, 0.4F);
        ModernStyle.rounded(g, activeItem.x(), py, activeItem.w(), ph, 7, 0x33BFE8FF);
        ModernStyle.rounded(g, activeItem.x() - 1, py + 4, 2, ph - 8, 1, ModernStyle.GLOW);
        for (SidebarHit s : items) {
            boolean active = s.category() == this.activeCategory;
            int color = active ? ModernStyle.INK : ModernStyle.INK_MUTED;
            boolean anyEnabled = this.modules.byCategory(s.category()).stream().anyMatch(Module::isEnabled);
            String name = ModernTypography.fit(s.category().getDisplayName(), s.w() - (anyEnabled ? 30 : 20));
            ModernTypography.draw(g, name, s.x() + 12, s.y() + (s.h() - 4 - 8) / 2, color, false);
            if (anyEnabled) ModernStyle.statusDot(g, s.x() + s.w() - 16, s.y() + (s.h() - 4) / 2 - 2, 5, ModernStyle.BLUE, true);
        }
        int dividerX = panelX() + PAD + SIDEBAR_W + SIDEBAR_GAP / 2;
        ModernStyle.fill(g, dividerX, panelY() + HEADER_H, dividerX + 1, panelY() + HEADER_H + sidebarHeight(), 0x1EBFEEFF);
    }

    private void drawSectionHeading(GuiGraphicsExtractor g) {
        int x = rowContentX(), w = rowContentW(), y = panelY() + HEADER_H + 6;
        ModernTypography.draw(g, ModernStyle.spaced(this.activeCategory.getDisplayName().toUpperCase(Locale.ROOT)),
            x, y, ModernStyle.INK_MUTED, false);
        int enabledInCat = (int)this.modules.byCategory(this.activeCategory).stream().filter(Module::isEnabled).count();
        int totalInCat = this.modules.byCategory(this.activeCategory).size();
        String count = enabledInCat + " / " + totalInCat + " enabled";
        ModernTypography.draw(g, count, x + w - ModernTypography.width(count), y, ModernStyle.INK_MUTED, false);
        ModernStyle.fill(g, x, y + 18, x + w, y + 19, 0x1EBFEEFF);
    }

    private void drawSearchField(GuiGraphicsExtractor g) {
        int x = rowContentX(), y = panelY() + HEADER_H + 6, w = rowContentW(), h = 28;
        ModernStyle.rounded(g, x, y, w, h, 8, 0x30243746);
        String shown = this.search.isEmpty() ? "Type to search every category..." : this.search;
        int color = this.search.isEmpty() ? ModernStyle.INK_MUTED : ModernStyle.INK;
        ModernTypography.draw(g, ModernTypography.fit(shown, w - 24), x + 12, y + 9, color, false);
    }

    private void drawRows(GuiGraphicsExtractor g, List<Hit> hits, int mx, int my) {
        if (hits.size() == 1 && "empty".equals(hits.get(0).key())) {
            Hit h = hits.get(0);
            String message = this.view == View.SEARCH ? "No modules match" : "No modules in this category";
            ModernTypography.draw(g, message, h.x(), h.y() + 6, ModernStyle.INK_MUTED, false);
            return;
        }
        for (Hit h : hits) {
            Module m = h.module();
            boolean hoverable = my >= visibleContentTop() && my < panelY() + Math.round(this.panelHeight) - FOOTER_H;
            float target = hoverable && ModernStyle.inside(mx, my, h.x(), h.y(), h.w(), h.h()) ? 1 : 0;
            float hover = animate(m, target);
            int rowAlpha = Math.round(hover * 22) + (m.isEnabled() ? 26 : 0);
            if (rowAlpha > 0) ModernStyle.rounded(g, h.x() + 2, h.y() + 1, h.w() - 4, h.h() - 3, 6, rowAlpha << 24 | 0x3D9DDC);
            if (m.isEnabled()) ModernStyle.rounded(g, h.x() + 3, h.y() + 8, 2, h.h() - 16, 1, ModernStyle.BLUE);
            int nameColor = m.isEnabled() ? ModernStyle.BLUE : ModernStyle.INK;
            ModernTypography.draw(g, ModernTypography.fit(m.getName(), h.w() - 70), h.x() + 12, h.y() + 6, nameColor, false);
            String desc = ModernTypography.fit(m.getDescription(), h.w() - 70);
            if (!desc.isEmpty()) ModernTypography.draw(g, desc, h.x() + 12, h.y() + 22, ModernStyle.INK_MUTED, false);
            float on = animate("row-on-" + m, m.isEnabled() ? 1 : 0);
            ModernStyle.toggle(g, h.x() + h.w() - 50, h.y() + h.h() / 2 - 8, 28, 16, on);
            ModernTypography.draw(g, "›", h.x() + h.w() - 18, h.y() + h.h() / 2 - 4, ModernStyle.INK_MUTED, false);
        }
    }

    private void drawDetail(GuiGraphicsExtractor g, List<Hit> hits, int mx, int my) {
        Module m = this.detailModule;
        for (Hit h : hits) {
            if ("detail-toggle".equals(h.key())) {
                float scale = 1.6F;
                g.pose().pushMatrix();
                g.pose().translate(h.x(), h.y());
                g.pose().scale(scale, scale);
                ModernTypography.draw(g, m.getName(), 0, 0, ModernStyle.INK, false);
                g.pose().popMatrix();
                ModernTypography.draw(g, m.getDescription(), h.x(), h.y() + 30, ModernStyle.INK_MUTED, false);
                float on = animate("detail-on-" + m, m.isEnabled() ? 1 : 0);
                ModernStyle.toggle(g, h.x() + h.w() - 44, h.y() + 6, 44, 24, on);
            } else if ("keybind".equals(h.key())) {
                boolean binding = this.interactions.isBinding(m);
                String label = binding ? "Press a key… (Esc to cancel)" : "Keybind";
                ModernTypography.draw(g, label, h.x(), h.y() + 6, binding ? ModernStyle.BLUE : ModernStyle.INK, false);
                if (!binding) ModernTypography.draw(g, m.getKeybind().mode().name(), h.x(), h.y() + 22, ModernStyle.INK_MUTED, false);
                String value = binding ? "…" : this.interactions.keybindDisplay(m.getKeybind());
                int vw = ModernTypography.width(value);
                ModernStyle.rounded(g, h.x() + h.w() - vw - 22, h.y() + 6, vw + 16, 22, 6, 0x30243746);
                ModernTypography.draw(g, value, h.x() + h.w() - vw - 14, h.y() + 12, ModernStyle.INK, false);
            } else if (h.setting() != null) {
                drawSetting(g, h);
            }
        }
    }

    private void drawSetting(GuiGraphicsExtractor g, Hit h) {
        Setting<?> s = h.setting();
        ModernTypography.draw(g, s.getName(), h.x(), h.y() + 4, ModernStyle.INK, false);
        String desc = ModernTypography.fit(s.getDescription(), h.w());
        if (!desc.isEmpty()) ModernTypography.draw(g, desc, h.x(), h.y() + 18, ModernStyle.INK_MUTED, false);
        if (s instanceof BooleanSetting bool) {
            float on = animate(s, bool.get() ? 1 : 0);
            ModernStyle.toggle(g, h.x() + h.w() - 40, h.y() + 8, 40, 22, on);
        } else if (s instanceof NumberSetting n) {
            String value = formatNumber(n);
            ModernTypography.draw(g, value, h.x() + h.w() - ModernTypography.width(value), h.y() + 4, ModernStyle.BLUE, false);
            int trackY = h.y() + 34, trackH = 4;
            ModernStyle.fill(g, h.x(), trackY, h.x() + h.w(), trackY + trackH, 0x306C96B0);
            float frac = (n.get() - n.getMin()) / Math.max(1e-6F, n.getMax() - n.getMin());
            int fillW = Math.round(h.w() * frac);
            ModernStyle.fill(g, h.x(), trackY, h.x() + fillW, trackY + trackH, ModernStyle.BLUE);
            ModernStyle.rounded(g, h.x() + fillW - 4, trackY - 3, 8, 10, 4, 0xFFFFFFFF);
        } else if (s instanceof EnumSetting<?> enumSetting) {
            String value = this.interactions.displayValue(enumSetting);
            drawValuePill(g, h, value, false);
        } else if (s instanceof ColorSetting color) {
            boolean editing = this.interactions.isEditing(s);
            String value = this.interactions.displayValue(color);
            int vw = ModernTypography.width(value);
            ModernStyle.rounded(g, h.x() + h.w() - vw - 34, h.y() + 2, 16, 16, 4, color.get());
            drawValueText(g, h.x() + h.w() - vw - 14, h.y() + 4, value, editing);
        } else if (s instanceof TextSetting) {
            boolean editing = this.interactions.isEditing(s);
            drawValuePill(g, h, this.interactions.displayValue(s), editing);
        }
    }

    private void drawValuePill(GuiGraphicsExtractor g, Hit h, String value, boolean editing) {
        int vw = ModernTypography.width(value);
        ModernStyle.rounded(g, h.x() + h.w() - vw - 22, h.y() + 1, vw + 16, 18, 5, editing ? 0x40278DCC : 0x24243746);
        ModernTypography.draw(g, value, h.x() + h.w() - vw - 14, h.y() + 5, editing ? ModernStyle.BLUE : ModernStyle.INK, false);
    }

    private void drawValueText(GuiGraphicsExtractor g, int x, int y, String value, boolean editing) {
        ModernTypography.draw(g, value, x, y, editing ? ModernStyle.BLUE : ModernStyle.INK, false);
    }

    private String formatNumber(NumberSetting n) {
        return String.format(Locale.ROOT, "%." + n.getDecimalPlaces() + "f", n.get());
    }

    private void drawFooter(GuiGraphicsExtractor g, int panelX, int panelY, int panelW, int panelH) {
        int y = panelY + panelH - FOOTER_H;
        ModernStyle.fill(g, panelX + 1, y, panelX + panelW - 1, y + 1, 0x1EBFEEFF);
        int enabled = (int)this.modules.all().stream().filter(Module::isEnabled).count();
        ModernStyle.statusDot(g, panelX + PAD, y + 9, 6, ModernStyle.BLUE, true);
        ModernTypography.draw(g, enabled + " enabled", panelX + PAD + 12, y + 6, ModernStyle.INK_MUTED, false);
        if (panelW < 420) return;
        String hint = switch (this.view) {
            case CATEGORY -> "Left: toggle   Right / ›: settings";
            case DETAIL -> "Esc: back · changes apply instantly";
            case SEARCH -> "Type to filter every category";
        };
        ModernTypography.draw(g, hint, panelX + panelW - PAD - ModernTypography.width(hint), y + 6, ModernStyle.INK_MUTED, false);
    }

    /**
     * A tape of world bearings scrolled by the player's heading: ticks every 5 degrees, labels every 15,
     * cardinal letters at the 45s. The dark backing keeps it legible over bright daytime sky.
     */
    private void drawCompass(GuiGraphicsExtractor g) {
        // Minecraft yaw 0 faces south (Direction.fromYRot); a compass bearing 0 is north.
        float yaw = this.minecraft.player == null ? 0 : this.minecraft.player.getYRot();
        float heading = (((yaw + 180F) % 360) + 360) % 360;
        float cx = this.width / 2F;
        int y = ModernWindowFrame.CAPTION_H + 5;
        // Narrow the visible arc on small windows so the tape never runs into the screen edges.
        float span = Math.min(75F, (this.width / 2F - 40F) / COMPASS_PX_PER_DEG);
        if (span < 20F) return;
        float half = span * COMPASS_PX_PER_DEG;
        ModernStyle.rounded(g, Math.round(cx - half - 12), y - 4, Math.round(half * 2 + 24), 22, 11, 0x70050F1A);

        int first = (int) Math.ceil((heading - span) / 5F) * 5;
        for (int a = first; a <= heading + span; a += 5) {
            float off = a - heading;
            float x = cx + off * COMPASS_PX_PER_DEG;
            float edge = 1F - (float) Math.pow(Math.abs(off) / span, 3);
            int bearing = ((a % 360) + 360) % 360;
            boolean major = bearing % 15 == 0;
            int tick = bearing % 45 == 0 ? 4 : major ? 3 : 2;
            g.pose().pushMatrix();
            g.pose().translate(x, 0);
            ModernStyle.fill(g, 0, y + 15 - tick, 1, y + 15, ModernTypography.fade(ModernStyle.MUTED, edge * (major ? 0.75F : 0.4F)));
            g.pose().popMatrix();
            if (!major) continue;

            String label = compassLabel(bearing);
            boolean letter = bearing % 45 == 0;
            float scale = letter ? 1F : 0.82F;
            float near = Math.max(0F, 1F - Math.abs(off) / 12F);
            int base = bearing % 90 == 0 ? ModernStyle.TEXT : ModernStyle.MUTED;
            int color = ModernTypography.fade(ModernStyle.mix(base, ModernStyle.ACCENT, near), edge * (letter ? 1F : 0.8F));
            float w = ModernTypography.width(ModernTypography.Face.TEXT, label, scale);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, label, x - w / 2F + 0.5F, y + (1F - scale) * 5.5F, scale, color);
        }
        ModernStyle.halo(g, Math.round(cx) - 1, y + 10, 2, 7, 1, ModernStyle.GLOW, 0.5F);
        ModernStyle.fill(g, Math.round(cx) - 1, y + 10, Math.round(cx) + 1, y + 17, ModernStyle.ACCENT);
    }

    private static String compassLabel(int bearing) {
        return switch (bearing) {
            case 0 -> "N";
            case 45 -> "NE";
            case 90 -> "E";
            case 135 -> "SE";
            case 180 -> "S";
            case 225 -> "SW";
            case 270 -> "W";
            case 315 -> "NW";
            default -> String.valueOf(bearing);
        };
    }

    private void drawGlobalHints(GuiGraphicsExtractor g) {
        String hints = "/  Search      Esc  Back      R-Shift  Close";
        int w = ModernTypography.width(hints);
        // A small dark pill keeps the pale hints legible over bright snow or a bright world.
        ModernStyle.rounded(g, (this.width - w) / 2 - 10, this.height - 20, w + 20, 16, 8, 0x8C050F1A);
        ModernTypography.draw(g, hints, (this.width - w) / 2, this.height - 16, ModernStyle.MUTED, false);
    }

    private float animate(Object key, float target) {
        float current = this.anim.getOrDefault(key, target);
        float next = ModernStyle.smooth(current, target, this.seconds, 14);
        this.anim.put(key, next);
        return next;
    }

    /**
     * Test seam for {@code GuiScreenInteractionSmoke}: scrolls the detail view so its keybind row is
     * actually within the clickable content strip (a module with enough settings can otherwise push it
     * past {@link #targetPanelHeight()}), then returns that row's current on-screen bounds as
     * {@code {x, y, w, h}}, or {@code null} outside the detail view.
     */
    public int[] scrollToKeybindRow() {
        if (this.view != View.DETAIL) return null;
        int viewport = targetPanelHeight() - HEADER_H - FOOTER_H;
        this.scroll = Math.max(0, this.contentHeight - viewport);
        for (Hit h : rows()) if ("keybind".equals(h.key())) return new int[]{h.x(), h.y(), h.w(), h.h()};
        return null;
    }

    // --- input ------------------------------------------------------------------------------------

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (this.interactions.mouseClickedBinding(e)) return true;
        placeMusic();
        if (this.music.mouseClicked(e.x(), e.y(), e.button())) return true;
        Hit search = headerHit();
        if (this.view != View.SEARCH && ModernStyle.inside(e.x(), e.y(), search.x(), search.y(), search.w(), search.h())) {
            openSearch();
            return true;
        }
        int chromeTop = panelY() + HEADER_H;
        int contentBottom = panelY() + targetPanelHeight() - FOOTER_H;
        if (this.view == View.CATEGORY && e.y() >= chromeTop && e.y() < contentBottom) {
            for (SidebarHit s : sidebarItems()) {
                if (ModernStyle.inside(e.x(), e.y(), s.x(), s.y(), s.w(), s.h())) {
                    if (s.category() != this.activeCategory) { this.activeCategory = s.category(); this.scroll = 0; markContentChanged(); }
                    return true;
                }
            }
        }
        int contentTop = visibleContentTop();
        if (e.y() < contentTop || e.y() >= contentBottom) return super.mouseClicked(e, twice);
        for (Hit h : rows()) {
            if (!ModernStyle.inside(e.x(), e.y(), h.x(), h.y(), h.w(), h.h())) continue;
            if (this.view == View.DETAIL) return handleDetailClick(h, e);
            if (h.module() != null) {
                if (e.button() == 2) { this.interactions.startBind(h.module()); return true; }
                if (e.button() == 1 || e.x() >= h.x() + h.w() - 24) openDetail(h.module());
                else h.module().toggle();
                return true;
            }
        }
        return super.mouseClicked(e, twice);
    }

    private boolean handleDetailClick(Hit h, MouseButtonEvent e) {
        if ("detail-toggle".equals(h.key())) { this.detailModule.toggle(); return true; }
        if ("keybind".equals(h.key())) { this.interactions.handleKeybindClick(this.detailModule, e.button()); return true; }
        if (h.setting() != null) {
            this.interactions.handleSettingClick(h.setting(), (int)e.x(), h.x(), h.x() + h.w());
            return true;
        }
        return true;
    }

    private void openDetail(Module module) {
        this.detailModule = module;
        this.view = View.DETAIL;
        this.scroll = 0;
        markContentChanged();
    }

    private void openSearch() {
        this.view = View.SEARCH;
        this.search = "";
        this.scroll = 0;
        markContentChanged();
    }

    private void closeSearchOrDetail() {
        this.view = View.CATEGORY;
        this.scroll = 0;
        markContentChanged();
    }

    private void markContentChanged() {
        this.contentChangeStart = System.nanoTime();
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        placeMusic();
        if (this.music.mouseScrolled(x, y, sy)) return true;
        int targetHeight = targetPanelHeight();
        int viewport = targetHeight - HEADER_H - subheaderHeight() - FOOTER_H;
        int maxScroll = Math.max(0, this.contentHeight - viewport);
        this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - (int)(sy * 24)));
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (this.music.mouseDragged(e.x(), e.y())) return true;
        return this.interactions.mouseDragged(e) || super.mouseDragged(e, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        this.interactions.mouseReleased();
        if (this.music.mouseReleased()) return true;
        return super.mouseReleased(e);
    }

    @Override public boolean charTyped(CharacterEvent e) {
        if (this.music.charTyped(e)) return true;
        if (this.interactions.charTyped(e)) return true;
        if (this.view == View.SEARCH && e.isAllowedChatCharacter()) {
            this.search = this.search + e.codepointAsString();
            this.scroll = 0;
            return true;
        }
        return false;
    }

    @Override public boolean keyPressed(KeyEvent e) {
        // The music window's search field, while focused, owns every key (Right Shift types a capital, not closes).
        if (this.music.isTyping() && this.music.keyPressed(e)) return true;
        if (this.interactions.keyPressed(e)) return true;
        if (e.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) { onClose(); return true; }
        if (this.view != View.SEARCH && this.music.keyPressed(e)) return true;
        if (this.view == View.SEARCH && e.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (!this.search.isEmpty()) this.search = this.search.substring(0, this.search.length() - 1);
            this.scroll = 0;
            return true;
        }
        if (e.isEscape() && this.view != View.CATEGORY) { closeSearchOrDetail(); return true; }
        if (e.key() == GLFW.GLFW_KEY_SLASH && this.view == View.CATEGORY) { openSearch(); return true; }
        return super.keyPressed(e);
    }

    @Override public void removed() { this.interactions.mouseReleased(); this.music.blur(); Client.getInstance().saveConfig(); super.removed(); }

    @Override public boolean isTypingText() { return this.music.isTyping(); }

    @Override public boolean preeditUpdated(net.minecraft.client.input.@org.jspecify.annotations.Nullable PreeditEvent e) {
        return this.music.preeditUpdated(e) || super.preeditUpdated(e);
    }

    /**
     * Debug-only: jumps to a named view so {@code -Dsigma.debug.screenshotAfterFrames} can capture a view
     * other than whatever {@code GuiScreenInteractionSmoke} happens to leave the screen on. Read by
     * {@link com.mentalfrostbyte.Client#openDebugGuiIfRequested} when {@code -Dsigma.debug.modernPreviewView}
     * is set to {@code DETAIL}, {@code SEARCH}, {@code MUSIC} (pulls the music window out) or {@code INGAME} (closes the screen, for the in-game HUD);
     * any other value is ignored.
     */
    public void debugPreview(String view) {
        if ("DETAIL".equalsIgnoreCase(view)) {
            this.modules.all().stream().findFirst().ifPresent(this::openDetail);
        } else if ("SEARCH".equalsIgnoreCase(view)) {
            openSearch();
        } else if ("MUSIC".equalsIgnoreCase(view)) {
            ModernMusicDrawer.setOpenNow(true);
        } else if ("INGAME".equalsIgnoreCase(view)) {
            onClose();
        }
    }
}
