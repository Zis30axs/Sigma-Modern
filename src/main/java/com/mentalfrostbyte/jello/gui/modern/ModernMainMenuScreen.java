package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.account.SigmaAccountScreen;
import com.mentalfrostbyte.jello.gui.mainmenu.SigmaMainMenuScreen;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * SigmaModern's title screen: a painted winter night ({@link ModernScene}) under falling snow, a faceted Σ
 * with a serif wordmark, two primary destinations as flat serif rows, and a dock of secondary actions that
 * stays tucked below the bottom edge - only its grip showing - until the pointer comes near it.
 *
 * <p>Everything eases: the whole screen fades up from the night-sky color with each element arriving in
 * turn, and choosing a destination fades back out before navigating (the next screen then fades in via
 * {@link ModernTransitions}). Returning from a sub-screen gets a short fade rather than replaying the whole
 * entrance, and a resize changes nothing.</p>
 *
 * <p>Destinations are real {@link Button} widgets - Tab reaches them, Enter activates them, narration reads
 * them - and a focused dock button raises the dock the same as the pointer does.</p>
 */
public final class ModernMainMenuScreen extends SigmaMainMenuScreen implements com.mentalfrostbyte.jello.gui.TextEntryScreen {
    private static final boolean DEBUG_DOCK = Boolean.getBoolean("sigma.debug.modernMenuDock");
    private static final float EXIT_SECONDS = 0.28F;
    private static final int DOCK_H = 50;
    private static final int DOCK_BUTTON_W = 72;
    private static final int NIGHT = 0x050F1A;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);

    private final ModernSnow snow = new ModernSnow(320, 2100F);
    private final List<DockButton> dockButtons = new ArrayList<>();
    // The music player's pull-out window, the same one the ClickGUI has (and in the same place: open stays open).
    private final ModernMusicDrawer music = new ModernMusicDrawer(282);
    private final long introStart = System.nanoTime();
    private long lastFrame;
    private float dt;
    private float time;
    private float intro;
    private float parallaxX, parallaxY;
    private float wind;
    private float lastMouseX = Float.NaN;
    private float dock;
    private Runnable exitAction;
    private long exitStart;
    private long returnStart;
    private boolean navigated;

    public ModernMainMenuScreen() {
        super(Component.literal("SigmaModern"));
    }

    private record Layout(boolean compact, int margin, int heroTop, float mark, float wordScale,
                          int itemsTop, int itemH, int itemW) {}

    private Layout layout() {
        boolean compact = this.height < 300;
        int margin = Math.max(24, Math.min(64, this.width / 16));
        float mark = compact ? 30F : Math.max(38F, Math.min(64F, this.height * 0.115F));
        int heroTop = compact ? 32 : Math.max(50, Math.round(this.height * 0.14F));
        // The display face's cap height at its 11px design size is 0.72em ≈ 7.92px.
        float wordScale = mark * 0.88F / 7.92F;
        int itemsTop = heroTop + Math.round(mark) + (compact ? 14 : 44);
        int itemH = compact ? 32 : 44;
        int itemW = Math.min(330, Math.max(210, this.width / 3));
        return new Layout(compact, margin, heroTop, mark, wordScale, itemsTop, itemH, itemW);
    }

    @Override
    protected void init() {
        if (this.navigated) {
            // Back from a sub-screen: a short fade up, not a replay of the whole entrance.
            this.navigated = false;
            this.exitAction = null;
            this.returnStart = System.nanoTime();
        }
        Layout l = layout();
        this.addRenderableWidget(new MenuItem(l.margin(), l.itemsTop(), l.itemW(), l.itemH(), 0,
            "Singleplayer", "A world of your own", () -> leave(this::openSingleplayer)));
        MenuItem multiplayer = this.addRenderableWidget(new MenuItem(l.margin(), l.itemsTop() + l.itemH() + 6, l.itemW(), l.itemH(), 1,
            "Multiplayer", "Find your next adventure", () -> leave(this::openMultiplayer)));
        multiplayer.active = this.minecraft.allowsMultiplayer();
        if (!multiplayer.active) multiplayer.setTooltip(Tooltip.create(Component.translatable("title.multiplayer.disabled")));

        this.dockButtons.clear();
        this.dockButtons.add(this.addRenderableWidget(new DockButton("Settings", ModernIcons.Icon.GEAR, () -> leave(this::openOptions))));
        this.dockButtons.add(this.addRenderableWidget(new DockButton("Accounts", ModernIcons.Icon.PERSON,
            () -> leave(() -> this.minecraft.gui.setScreen(new SigmaAccountScreen(this, SigmaAccountScreen.Style.JELLO))))));
        this.dockButtons.add(this.addRenderableWidget(new DockButton("Modes", ModernIcons.Icon.MODES, () -> leave(this::openModeSelect))));
        this.dockButtons.add(this.addRenderableWidget(new DockButton("Quit", ModernIcons.Icon.POWER, () -> leave(this::quitGame))));
        placeDock();
    }

    // --- navigation -----------------------------------------------------------------------------------

    private void leave(Runnable action) {
        if (this.exitAction != null) return;
        this.exitAction = action;
        this.exitStart = System.nanoTime();
    }

    /** Runs the pending destination once the fade-out has finished. Called from {@link #tick()}, never mid-render. */
    private void finishLeaving() {
        Runnable action = this.exitAction;
        if (action == null || this.navigated) return;
        this.navigated = true;
        ModernTransitions.fadeInNextScreen();
        action.run();
        if (this.minecraft.gui.screen() == this) {
            // The destination declined to open (e.g. multiplayer became unavailable): fade back in here.
            this.navigated = false;
            this.exitAction = null;
            this.returnStart = System.nanoTime();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.exitAction != null && !this.navigated && seconds(this.exitStart) >= EXIT_SECONDS) finishLeaving();
    }

    private static float seconds(long since) {
        return (System.nanoTime() - since) / 1_000_000_000F;
    }

    /** 0..1 arrival of an element whose entrance starts {@code delay} seconds into the intro. */
    private float entrance(float delay) {
        return ModernStyle.easeOut((this.intro - delay) / 0.65F);
    }

    // --- rendering ------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        placeMusic();
        // Nothing under the music window reacts to the pointer while it's over the window.
        boolean overMusic = this.music.contains(mouseX, mouseY);
        int mx = overMusic ? -10000 : mouseX, my = overMusic ? -10000 : mouseY;
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0 ? 0F : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
        this.lastFrame = now;
        this.time += this.dt;
        this.intro = seconds(this.introStart);
        float exit = this.exitAction == null ? 0F : ModernStyle.easeOut(seconds(this.exitStart) / EXIT_SECONDS);

        this.parallaxX = ModernStyle.smooth(this.parallaxX, Math.max(-1F, Math.min(1F, mouseX / (float)Math.max(1, this.width) * 2F - 1F)), this.dt, 2.4F);
        this.parallaxY = ModernStyle.smooth(this.parallaxY, Math.max(-1F, Math.min(1F, mouseY / (float)Math.max(1, this.height) * 2F - 1F)), this.dt, 2.4F);
        if (this.dt > 0F && !Float.isNaN(this.lastMouseX)) {
            float gust = Math.max(-70F, Math.min(70F, (mouseX - this.lastMouseX) / this.dt * 0.12F));
            this.wind = ModernStyle.smooth(this.wind, gust + this.parallaxX * 8F, this.dt, 1.6F);
        }
        this.lastMouseX = mouseX;

        ModernScene.shared().render(g, this.width, this.height, this.parallaxX, this.parallaxY, this.time);
        this.snow.update(this.width, this.height, this.dt, this.wind);
        this.snow.render(g, this.parallaxX * 10F, 1F);
        drawScrim(g);

        Layout l = layout();
        updateDock(mx, my);
        try (var fade = ModernStyle.alphaScope(1F - exit)) {
            drawHero(g, l);
            drawClock(g, l);
            drawDock(g);
            // Registered widgets keep mouse, Tab, Enter and narration on the same geometry they're drawn at.
            super.extractRenderState(g, mx, my, tick);
            drawFooter(g, l);
            try (var arrive = ModernStyle.alphaScope(entrance(1.0F))) {
                this.music.render(g, mouseX, mouseY);
            }
        }

        float introOverlay = 1F - ModernStyle.easeOut(this.intro / 0.75F);
        float returnOverlay = this.returnStart == 0L ? 0F : 1F - ModernStyle.easeOut(seconds(this.returnStart) / 0.35F);
        float overlay = Math.max(exit, Math.max(introOverlay, returnOverlay));
        if (overlay > 0.004F) {
            g.nextStratum();
            g.fill(0, 0, this.width, this.height, Math.round(255 * overlay) << 24 | NIGHT);
        }
    }

    /** A soft darkening toward the left edge, so the hero and menu read over any part of the scene. */
    private void drawScrim(GuiGraphicsExtractor g) {
        // Two-pixel strips: wider ones band visibly over the bright snowfield.
        int reach = Math.round(this.width * 0.6F);
        for (int x = 0; x < reach; x += 2) {
            float t = x / (float)reach;
            int alpha = Math.round(140 * (1F - t) * (1F - t));
            if (alpha > 0) ModernStyle.fill(g, x, 0, x + 2, this.height, alpha << 24 | NIGHT);
        }
    }

    private void drawHero(GuiGraphicsExtractor g, Layout l) {
        float mark = l.mark();
        float x = l.margin(), top = l.heroTop();
        float markIn = entrance(0.15F), wordIn = entrance(0.28F), italicIn = entrance(0.4F), tagIn = entrance(0.5F);

        if (!l.compact()) {
            try (var a = ModernStyle.alphaScope(entrance(0.05F))) {
                float ey = top - 18 + (1F - entrance(0.05F)) * 6F;
                ModernStyle.fill(g, Math.round(x), Math.round(ey), Math.round(x) + 2, Math.round(ey) + 9, ModernStyle.GLOW);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernStyle.spaced("MINECRAFT " + this.minecraft.getLaunchedVersion().toUpperCase(Locale.ROOT)),
                    x + 8, ey, 1F, 0xFFA9C8DC);
            }
        }

        try (var a = ModernStyle.alphaScope(markIn)) {
            float my = top + (1F - markIn) * 14F;
            float breathe = 0.22F + 0.08F * (float)Math.sin(this.time * 1.4F);
            ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, x - mark * 0.9F, my - mark * 0.9F, mark * 2.8F, Math.round(255 * breathe) << 24 | 0xBFE8FF);
            ModernIcons.draw(g, ModernIcons.Icon.SIGMA, x, my, mark, 0xFFD6F1FF);
        }

        float wordX = x + mark * 0.92F + 12F;
        float capTop = top + mark * 0.06F;
        float wordY = capTop - 1.08F * l.wordScale();
        float baseline = wordY + 9F * l.wordScale();
        try (var a = ModernStyle.alphaScope(wordIn)) {
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY, "Sigma", wordX, wordY + (1F - wordIn) * 12F, l.wordScale(), 0xFFF2F8FC);
        }
        float italicScale = l.wordScale() * 0.6F;
        float italicX = wordX + ModernTypography.width(ModernTypography.Face.DISPLAY, "Sigma", l.wordScale()) + l.wordScale() * 3.2F;
        try (var a = ModernStyle.alphaScope(italicIn)) {
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, "Modern", italicX + (1F - italicIn) * 10F,
                baseline - 9F * italicScale, italicScale, 0xFFA8DDFA);
        }
        if (!l.compact()) {
            // Below the wordmark's descenders (0.26em under the baseline), not just below the Σ.
            float tagY = baseline + 0.26F * 11F * l.wordScale() + 3F;
            try (var a = ModernStyle.alphaScope(tagIn)) {
                ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, "A new perspective, carved in ice.",
                    wordX, tagY + (1F - tagIn) * 8F, 1.25F, 0xFF9DBCD0);
            }
        }
    }

    private void drawClock(GuiGraphicsExtractor g, Layout l) {
        if (l.compact() || this.width < 420) return;
        // The clock sits where the music window slides out; it steps aside as the window comes.
        float in = entrance(0.6F) * (1F - Math.min(1F, this.music.reserve() / 60F));
        if (in <= 0.01F) return;
        LocalDateTime now = LocalDateTime.now();
        String clock = CLOCK.format(now), date = DATE.format(now);
        float scale = 2.6F;
        float right = this.width - l.margin();
        float y = l.heroTop() - 6F + (1F - in) * 8F;
        try (var a = ModernStyle.alphaScope(in)) {
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY, clock, right - ModernTypography.width(ModernTypography.Face.DISPLAY, clock, scale),
                y, scale, 0xE8EEF6FB);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, date, right - ModernTypography.width(ModernTypography.Face.TEXT, date, 1F),
                y + 11F * scale + 2F, 1F, 0xFFA2BFD1);
        }
    }

    private void drawFooter(GuiGraphicsExtractor g, Layout l) {
        if (this.width < 470) return;
        String footer = "Sigma " + Client.FULL_VERSION + "  ·  26.2";
        try (var a = ModernStyle.alphaScope(entrance(0.9F) * (1F - this.dock * 0.7F))) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, footer, this.width - l.margin() - ModernTypography.width(footer),
                this.height - 17F, 1F, 0xFF7F9FB4);
        }
    }

    // --- music window ------------------------------------------------------------------------------

    /** Under the window caption, down to above the raised dock. */
    private void placeMusic() {
        this.music.place(this.width, ModernWindowFrame.reservedTop() + 10, this.height - DOCK_H - 22);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        placeMusic();
        if (this.exitAction == null && this.music.mouseClicked(event.x(), event.y(), event.button())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (this.music.mouseDragged(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (this.music.mouseReleased()) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        placeMusic();
        if (this.music.mouseScrolled(x, y, scrollY)) return true;
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (this.music.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (this.music.charTyped(event)) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean preeditUpdated(net.minecraft.client.input.@org.jspecify.annotations.Nullable PreeditEvent event) {
        return this.music.preeditUpdated(event) || super.preeditUpdated(event);
    }

    @Override
    public boolean isTypingText() {
        return this.music.isTyping();
    }

    @Override
    public void removed() {
        this.music.blur();
        super.removed();
    }

    // --- dock ---------------------------------------------------------------------------------------

    private int dockWidth() {
        return this.dockButtons.size() * DOCK_BUTTON_W + 16;
    }

    private int dockTop() {
        int raised = this.height - DOCK_H - 12;
        int hidden = this.height + 4;
        return Math.round(hidden + (raised - hidden) * ModernStyle.easeOut(this.dock));
    }

    private void placeDock() {
        int left = (this.width - dockWidth()) / 2 + 8, top = dockTop() + 5;
        for (int i = 0; i < this.dockButtons.size(); i++) {
            this.dockButtons.get(i).setX(left + i * DOCK_BUTTON_W);
            this.dockButtons.get(i).setY(top);
        }
    }

    /**
     * Raises the dock while the pointer is within the bottom band (not the last few pixels - that strip is the
     * window's resize border, where GLFW stops reporting motion), keeps it up while the pointer stays over it,
     * and always raises it while one of its buttons has keyboard focus so Tab never lands on a hidden button.
     */
    private void updateDock(int mx, int my) {
        boolean hovered = GLFW.glfwGetWindowAttrib(this.minecraft.getWindow().handle(), GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;
        int left = (this.width - dockWidth()) / 2;
        boolean nearBottom = hovered && my >= this.height - 46;
        boolean overDock = hovered && this.dock > 0.35F && my >= dockTop() - 20 && mx >= left - 30 && mx < left + dockWidth() + 30;
        boolean focused = this.dockButtons.stream().anyMatch(Button::isFocused);
        boolean up = (DEBUG_DOCK || nearBottom || overDock || focused) && this.exitAction == null && this.intro > 0.6F;
        this.dock = ModernStyle.smooth(this.dock, up ? 1F : 0F, this.dt, up ? 11F : 7F);
        placeDock();
    }

    private void drawDock(GuiGraphicsExtractor g) {
        int w = dockWidth(), x = (this.width - w) / 2, top = dockTop();
        float show = entrance(0.95F);
        try (var a = ModernStyle.alphaScope(show)) {
            if (top < this.height) {
                ModernStyle.darkGlass(g, x, top, w, DOCK_H, 16, 0xD20A1B29);
                ModernStyle.fill(g, x + 16, top + 1, x + w - 16, top + 2, 0x33DDF3FF);
            }
            // The grip rides on the dock's top edge, so "that line" is literally what rises into the dock.
            float pulse = this.dock > 0.02F ? 0F : 0.18F * (0.5F + 0.5F * (float)Math.sin(this.time * 2.4F));
            int gripAlpha = Math.round(255 * (0.42F + pulse) * (1F - this.dock * 0.55F));
            int gripY = Math.min(top - 9, this.height - 7);
            // The grip sits on the bright snowfield, so it's navy ink; once the dock is up it's on dark glass.
            int gripColor = this.dock > 0.5F ? 0xCDEFFF : 0x1E3F57;
            ModernStyle.rounded(g, this.width / 2 - 20, gripY, 40, 4, 2, gripAlpha << 24 | gripColor);
            float hint = 1F - Math.min(1F, this.dock * 3F);
            if (hint > 0.01F && this.height >= 260) {
                try (var h = ModernStyle.alphaScope(hint * 0.8F)) {
                    String label = ModernStyle.spaced("MENU");
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, label, this.width / 2F - ModernTypography.width(label) / 2F,
                        gripY - 13F, 1F, 0xFF3F5F75);
                }
            }
        }
    }

    // --- widgets --------------------------------------------------------------------------------------

    /** A primary destination: an index, a serif title and a subtitle, with a sliding hover. */
    private final class MenuItem extends Button {
        private final int index;
        private final String subtitle;
        private float hover;

        private MenuItem(int x, int y, int w, int h, int index, String title, String subtitle, Runnable action) {
            super(x, y, w, h, Component.literal(title), ignored -> action.run(), DEFAULT_NARRATION);
            this.index = index;
            this.subtitle = subtitle;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
            float appear = entrance(0.58F + this.index * 0.1F);
            this.hover = ModernStyle.smooth(this.hover, this.active && (this.isHovered() || this.isFocused()) ? 1F : 0F, dt, 12F);
            boolean compact = this.getHeight() < 40;
            float titleScale = compact ? 1.3F : 1.6F;
            try (var a = ModernStyle.alphaScope(appear * (this.active ? 1F : 0.45F))) {
                int x = this.getX(), y = Math.round(this.getY() + (1F - appear) * 12F), w = this.getWidth(), h = this.getHeight();
                if (this.hover > 0.01F) {
                    try (var band = ModernStyle.alphaScope(this.hover)) {
                        ModernStyle.halo(g, x, y, w, h, 10, ModernStyle.GLOW, 0.35F);
                        ModernStyle.rounded(g, x, y, w, h, 10, 0x24CDEBFF);
                        ModernStyle.rounded(g, x, y + 8, 3, h - 16, 1, ModernStyle.GLOW);
                    }
                }
                float shift = this.hover * 6F;
                float titleY = y + (compact ? 6F : 5F);
                // Share the title's baseline (text is drawn with its baseline 9 units below its y).
                float indexY = titleY + 9F * titleScale - 9F;
                ModernTypography.draw(g, ModernTypography.Face.TEXT, String.format(Locale.ROOT, "%02d", this.index + 1),
                    x + 12F + shift * 0.5F, indexY, 1F, 0xFF8CCBEA);
                float textX = x + 34F + shift;
                ModernTypography.draw(g, ModernTypography.Face.TEXT, this.getMessage().getString(), textX, titleY, titleScale,
                    this.active ? 0xFFF2F8FC : 0xFF9FB4C4);
                if (!compact) {
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, this.subtitle, textX, y + 5F + 11F * titleScale + 1F, 1F, 0xFF97B6CA);
                }
                float underline = (w - 46F) * this.hover;
                if (underline > 0.5F) ModernStyle.fill(g, Math.round(textX), y + h - 3, Math.round(textX + underline), y + h - 2, 0x9960CAFF);
                try (var arrow = ModernStyle.alphaScope(this.hover)) {
                    ModernIcons.draw(g, ModernIcons.Icon.ARROW, x + w - 26F - (1F - this.hover) * 8F, y + h / 2F - 6F, 12F, 0xFFBFE8FF);
                }
            }
        }
    }

    /** A dock action: a flat icon over a small label, positioned every frame from the dock's current height. */
    private final class DockButton extends Button {
        private final ModernIcons.Icon icon;
        private float hover;

        private DockButton(String label, ModernIcons.Icon icon, Runnable action) {
            // Positioned by placeDock() at the end of init() and every frame after.
            super(0, 0, DOCK_BUTTON_W, DOCK_H - 10, Component.literal(label), ignored -> action.run(), DEFAULT_NARRATION);
            this.icon = icon;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
            if (this.getY() >= ModernMainMenuScreen.this.height) return;
            this.hover = ModernStyle.smooth(this.hover, this.isHovered() || this.isFocused() ? 1F : 0F, dt, 14F);
            int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
            boolean quit = this.icon == ModernIcons.Icon.POWER;
            if (this.hover > 0.01F) {
                int tint = quit ? 0x40E24556 : 0x26CDEBFF;
                try (var a = ModernStyle.alphaScope(this.hover)) {
                    ModernStyle.rounded(g, x + 4, y, w - 8, h, 10, tint);
                }
            }
            int iconColor = ModernStyle.mix(0xFFB9D5E6, quit ? 0xFFFF9AA6 : 0xFFFFFFFF, this.hover);
            float lift = this.hover * 2F;
            ModernIcons.draw(g, this.icon, x + w / 2F - 8F, y + 5F - lift, 16F, iconColor);
            String label = this.getMessage().getString();
            ModernTypography.draw(g, ModernTypography.Face.TEXT, label, x + w / 2F - ModernTypography.width(label) / 2F, y + h - 14F, 1F,
                ModernStyle.mix(0xFF9DBCD0, 0xFFF2F8FC, this.hover));
        }
    }
}
