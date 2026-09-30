package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.ClickGuiInteractions;
import com.mentalfrostbyte.jello.gui.SigmaClickGui;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.click.ClickGuiHandler;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.Easing;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's ClickGUI, as the old client drew it ({@code ClickGuiScreen} + {@code PanelGroup} + {@code ModListView}).
 *
 * <p>The world behind blurs and dims (20 %), and one white card per module category floats over it in a
 * grid - 200 px wide, 350 tall, a soft white glow round the edge - with the category's name in light grey type
 * at the top and its modules as 30 px rows underneath. A module that is on is a blue row with white text; one
 * that is off is plain with dark text. Left-click switches a module, right-click opens its settings
 * ({@link JelloSettingsPage}). Cards can be dragged by their heading and scroll when they hold more than fits.</p>
 *
 * <p>The GUI opens by flying its cards in: they start half again as large and pushed out from the middle, and
 * shrink into place with a springy overshoot as they fade up. Closing plays a quick fade back. The old client's
 * 4-card rows assumed six categories; with nine, the cards wrap by the width of the window instead.</p>
 */
public class JelloClickGuiScreen extends Screen implements SigmaClickGui, ModernBlurredBackdrop, TextEntryScreen {
    static final int PANEL_W = 200;
    static final int PANEL_H = 350;
    static final int HEADER = 60;
    static final int ROW = 30;
    static final int WHITE = 0xFFFEFEFE;
    static final int BLACK = 0xFF010101;
    static final int BLUE = 0xFF29A6FF;
    static final int BLUE_HOVER = 0xFF29B8FF;
    private static final int OFF_ROW = 0x70F5F5F5;
    private static final int OFF_ROW_HOVER = 0x00CACACA;

    /** Where each card was left, kept for the session so reopening puts them back. */
    private static final Map<ModuleCategory, int[]> POSITIONS = new EnumMap<>(ModuleCategory.class);

    private final ModuleManager modules;
    private final ClickGuiInteractions interactions = new ClickGuiInteractions();
    private final List<Panel> panels = new ArrayList<>();
    private final Animation open = new Animation(450, 125, Animation.Direction.FORWARDS);
    private JelloSettingsPage page;
    private boolean closing;
    private int layoutWidth = -1;
    private int layoutHeight = -1;
    private boolean layoutMusic;
    private Panel dragged;
    private float dragOffsetX;
    private float dragOffsetY;
    private Panel scrollDragged;
    private final JelloMusicPanel music = new JelloMusicPanel();
    private long lastFrame;

    public JelloClickGuiScreen(final ModuleManager modules) {
        super(Component.literal("Jello ClickGUI"));
        this.modules = modules;
        for (ModuleCategory category : ModuleCategory.values()) {
            List<Module> mods = modules.byCategory(category);
            if (!mods.isEmpty()) {
                this.panels.add(new Panel(category, mods));
            }
        }
        // -Dsigma.debug.jelloSettings=<Module>: start with that module's settings open.
        String debug = System.getProperty("sigma.debug.jelloSettings");
        if (debug != null) {
            for (Module module : modules.all()) {
                if (module.getName().equalsIgnoreCase(debug)) {
                    this.page = new JelloSettingsPage(this, module, this.interactions);
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTypingText() {
        return this.page != null && this.page.typing() || this.music.typing();
    }

    ModuleManager modules() {
        return this.modules;
    }

    /** The world behind is blurred here, once, before anything of the GUI is drawn. */
    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        GuiVisuals.blurBackground(graphics);
    }

    // ------------------------------------------------------------------ animation

    private float factor() {
        float p = this.open.calcPercent();
        return this.closing ? Easing.easeOutQuad(p, 0.0F, 1.0F, 1.0F)
            : (float) (Math.pow(2.0, -10.0F * p) * Math.sin((p - 0.25F) * (Math.PI * 2)) + 1.0);
    }

    @Override
    public boolean beginClose() {
        if (this.closing) {
            return true;
        }
        this.closing = true;
        this.open.changeDirection(Animation.Direction.BACKWARDS);
        return true;
    }

    @Override
    public void onClose() {
        if (!this.beginClose()) {
            ClickGuiHandler.close();
        }
    }

    @Override
    public void tick() {
        if (this.closing && this.open.calcPercent() <= 0.0F) {
            ClickGuiHandler.close();
        }
    }

    // ------------------------------------------------------------------ layout

    private void layout(final int width, final int height) {
        // The cards keep to the space beside the music window while it is out, as they did in the old client.
        boolean music = this.music.isOpen(width);
        if (width == this.layoutWidth && height == this.layoutHeight && music == this.layoutMusic) {
            return;
        }
        this.layoutWidth = width;
        this.layoutHeight = height;
        this.layoutMusic = music;
        int usable = music ? Math.max(PANEL_W + 60, width - JelloMusicPanel.W - 40) : width;
        int perRow = Math.max(1, (usable - 30) / (PANEL_W + 10));
        int rows = (this.panels.size() + perRow - 1) / perRow;
        int panelH = rows <= 2 ? PANEL_H : Math.max(200, (height - 60 + 20 * (rows - 1)) / rows);
        int index = 0;
        for (Panel panel : this.panels) {
            panel.h = panelH;
            int[] saved = POSITIONS.get(panel.category);
            if (saved != null) {
                panel.x = Math.min(saved[0], Math.max(0, width - PANEL_W));
                panel.y = Math.min(saved[1], Math.max(0, height - 60));
            } else {
                panel.x = 30 + (index % perRow) * (PANEL_W + 10);
                panel.y = 30 + (index / perRow) * (panelH - 20);
            }
            index++;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            this.layout(w, h);
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            float af = this.factor();
            float alpha = Math.max(0.0F, Math.min(1.0F, af));

            c.fill(0, 0, w, h, LegacyCanvas.alpha(BLACK, 0.2F * Math.max(0.0F, af)));

            // The cards dim a little while a module's settings are open in front of them.
            float behind = 1.0F - (this.page == null ? 0.0F : this.page.visibility() * 0.1F);
            boolean interactive = this.page == null || !this.page.visible();
            for (Panel panel : this.panels) {
                float cx = panel.x + PANEL_W / 2.0F;
                float cy = panel.y + panel.h / 2.0F;
                float scale = 1.5F - af * 0.5F;
                c.push();
                c.translate((cx - w / 2.0F) * (1.0F - af) * 0.5F, (cy - h / 2.0F) * (1.0F - af) * 0.5F);
                c.scaleAbout(scale, scale, cx, cy);
                panel.draw(c, interactive ? mx : -10_000, interactive ? my : -10_000, alpha * behind);
                c.pop();
            }

            long now = System.nanoTime();
            float dt = this.lastFrame == 0L ? 0.0F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
            this.lastFrame = now;
            this.music.draw(c, interactive ? mx : -10_000, interactive ? my : -10_000, alpha * behind, dt);
            this.musicButton(c, interactive ? mx : -10_000, interactive ? my : -10_000, alpha * behind);

            if (this.page != null) {
                this.page.draw(c, mx, my, alpha);
                if (this.page.finished()) {
                    this.page = null;
                }
            }
        }
    }

    /** The button in the bottom-right corner that brings the music window out and puts it away. */
    private void musicButton(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        int w = c.width();
        int h = c.height();
        int x = w - 146;
        int y = h - 55;
        boolean hover = mx >= x && mx < x + 70 && my >= y && my < y + 41;
        c.rounded(x, y, 70, 41, 10, LegacyCanvas.alpha(WHITE, (hover ? 0.2F : 0.1F) * alpha));
        c.textCentered(Face.JELLO_LIGHT, 18.0F, "Music", x + 35.0F, y + 20.5F, LegacyCanvas.alpha(WHITE, alpha));
    }

    private boolean musicButtonHit(final double mx, final double my) {
        int x = this.width * this.minecraft.getWindow().getGuiScale() - 146;
        int y = this.height * this.minecraft.getWindow().getGuiScale() - 55;
        return mx >= x && mx < x + 70 && my >= y && my < y + 41;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (this.closing) {
            return true;
        }
        if (this.interactions.mouseClickedBinding(event)) {
            return true;
        }
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        if (this.page != null) {
            this.page.mouseClicked(mx, my, event, this.width * this.minecraft.getWindow().getGuiScale(), this.height * this.minecraft.getWindow().getGuiScale());
            return true;
        }
        if (event.button() == 0 && this.musicButtonHit(mx, my)) {
            this.music.toggle();
            return true;
        }
        if (this.music.mouseClicked(mx, my, event.button())) {
            return true;
        }

        for (int i = this.panels.size() - 1; i >= 0; i--) {
            Panel panel = this.panels.get(i);
            if (!panel.contains(mx, my)) {
                continue;
            }
            // The card that was pressed comes to the front.
            this.panels.remove(i);
            this.panels.add(panel);
            if (my < panel.y + HEADER) {
                if (event.button() == 0) {
                    this.dragged = panel;
                    this.dragOffsetX = (float) (mx - panel.x);
                    this.dragOffsetY = (float) (my - panel.y);
                }
                return true;
            }
            if (event.button() == 0 && panel.scroll.press(mx, my, panel.x + PANEL_W, panel.y + HEADER, panel.h - HEADER, panel.contentHeight(), panel.h - HEADER)) {
                this.scrollDragged = panel;
                return true;
            }
            Module module = panel.moduleAt(my);
            if (module != null) {
                if (event.button() == 0) {
                    module.toggle();
                } else if (event.button() == 1) {
                    this.page = new JelloSettingsPage(this, module, this.interactions);
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (this.interactions.mouseDragged(event)) {
            return true;
        }
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        if (this.page != null) {
            this.page.mouseDragged(mx, my);
            return true;
        }
        this.music.mouseDragged(mx, my);
        if (this.dragged != null) {
            int w = this.width * this.minecraft.getWindow().getGuiScale();
            int h = this.height * this.minecraft.getWindow().getGuiScale();
            this.dragged.x = (int) Math.max(0, Math.min(w - PANEL_W, mx - this.dragOffsetX));
            this.dragged.y = (int) Math.max(0, Math.min(h - HEADER, my - this.dragOffsetY));
            POSITIONS.put(this.dragged.category, new int[] {this.dragged.x, this.dragged.y});
            return true;
        }
        if (this.scrollDragged != null) {
            Panel p = this.scrollDragged;
            p.scroll.drag(my, p.y + HEADER, p.h - HEADER, p.contentHeight(), p.h - HEADER);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.interactions.mouseReleased();
        this.music.mouseReleased();
        this.dragged = null;
        if (this.scrollDragged != null) {
            this.scrollDragged.scroll.release();
            this.scrollDragged = null;
        }
        if (this.page != null) {
            this.page.mouseReleased();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        double mx = LegacyCanvas.toLegacy(x);
        double my = LegacyCanvas.toLegacy(y);
        if (this.page != null) {
            this.page.wheel(mx, my, scrollY);
            return true;
        }
        if (this.music.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
        for (int i = this.panels.size() - 1; i >= 0; i--) {
            Panel panel = this.panels.get(i);
            if (panel.contains(mx, my)) {
                panel.scroll.wheel(scrollY, panel.contentHeight(), panel.h - HEADER);
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.interactions.keyPressed(event)) {
            return true;
        }
        if (this.page != null && this.page.keyPressed(event)) {
            return true;
        }
        if (this.music.keyPressed(event)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (this.page != null) {
                this.page.close();
            } else {
                this.beginClose();
            }
            return true;
        }
        if (InputConstants.getKey(event).equals(ClickGuiHandler.OPEN_KEY) && this.page == null) {
            this.beginClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        if (this.interactions.charTyped(event)) {
            return true;
        }
        return this.page != null && this.page.charTyped(event) || this.music.charTyped(event) || super.charTyped(event);
    }

    // ------------------------------------------------------------------ one card

    /** One category's card ({@code PanelGroup}). */
    static final class Panel {
        final ModuleCategory category;
        final List<Module> modules;
        final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);
        final Map<Module, Float> hover = new HashMap<>();
        int x;
        int y;
        int h = PANEL_H;
        private long lastNanos;

        Panel(final ModuleCategory category, final List<Module> modules) {
            this.category = category;
            this.modules = modules;
        }

        boolean contains(final double mx, final double my) {
            return mx >= this.x && mx < this.x + PANEL_W && my >= this.y && my < this.y + this.h;
        }

        int contentHeight() {
            return this.modules.size() * ROW;
        }

        Module moduleAt(final double my) {
            int index = (int) ((my - this.y - HEADER + this.scroll.offset()) / ROW);
            return index >= 0 && index < this.modules.size() && my >= this.y + HEADER ? this.modules.get(index) : null;
        }

        void draw(final LegacyCanvas c, final double mx, final double my, final float alpha) {
            long now = System.nanoTime();
            float dt = this.lastNanos == 0 ? 0.0F : Math.min(0.1F, (now - this.lastNanos) / 1.0E9F);
            this.lastNanos = now;

            c.outerGlow(this.x, this.y, PANEL_W, this.h, 20, alpha);
            c.fill(this.x, this.y, this.x + PANEL_W, this.y + this.h, LegacyCanvas.alpha(WHITE, alpha));
            float titleH = c.textHeight(Face.JELLO_LIGHT, 25);
            c.text(Face.JELLO_LIGHT, 25, this.category.getDisplayName(), this.x + 20, this.y + 30 - titleH / 2.0F, LegacyCanvas.alpha(BLACK, alpha * 0.5F));

            int viewH = this.h - HEADER;
            this.scroll.clamp(this.contentHeight(), viewH);
            c.scissor(this.x, this.y + HEADER, this.x + PANEL_W, this.y + this.h);
            for (int i = 0; i < this.modules.size(); i++) {
                Module module = this.modules.get(i);
                int top = this.y + HEADER + i * ROW - this.scroll.offset();
                if (top + ROW < this.y + HEADER || top > this.y + this.h) {
                    continue;
                }
                boolean hovered = mx >= this.x && mx < this.x + PANEL_W && my >= Math.max(top, this.y + HEADER) && my < Math.min(top + ROW, this.y + this.h);
                float h = this.hover.getOrDefault(module, 0.0F);
                h = Math.max(0.0F, Math.min(1.0F, h + (hovered ? 6.0F : -6.0F) * dt));
                this.hover.put(module, h);

                boolean on = module.isEnabled();
                int row = LegacyCanvas.shiftTowardsOther(on ? BLUE : OFF_ROW, on ? BLUE_HOVER : OFF_ROW_HOVER, 1.0F - h);
                c.fill(this.x, top, this.x + PANEL_W, top + ROW, LegacyCanvas.fade(row, alpha));
                float textH = c.textHeight(Face.JELLO_LIGHT, 20);
                c.text(Face.JELLO_LIGHT, 20, module.getName(), this.x + (on ? 30 : 22), top + ROW / 2.0F - textH / 2.0F,
                    LegacyCanvas.alpha(on ? WHITE : BLACK, alpha));
            }
            c.unscissor();

            this.scroll.draw(c, this.x + PANEL_W, this.y + HEADER, viewH, this.contentHeight(), viewH, this.contains(mx, my), alpha);
            if (this.scroll.offset() > 0) {
                c.image(LegacyTexture.SHADOW_BOTTOM, this.x, this.y + HEADER, PANEL_W, 18, LegacyCanvas.alpha(WHITE, alpha * 0.5F));
            }
        }
    }
}
