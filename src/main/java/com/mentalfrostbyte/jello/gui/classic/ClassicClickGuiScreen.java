package com.mentalfrostbyte.jello.gui.classic;

import com.mentalfrostbyte.jello.gui.ClickGuiInteractions;
import com.mentalfrostbyte.jello.gui.SigmaClickGui;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.click.ClickGuiHandler;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyKeys;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.Easing;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Sigma Classic's ClickGUI, as the old client drew it ({@code ClassicClickGui}, {@code CategoryHolder},
 * {@code ModuleSettingGroup}).
 *
 * <p>The world behind blurs, and a light grey panel (90 % opaque) opens in the middle of it. First page, "Sigma":
 * six 170x130 tiles, each a 64 px icon over a label that turns red under the pointer. A tile opens the second
 * page, a 592x692 panel of that group's modules in three columns - name, "Bind" and its key, a slide-switch, and a
 * gear that spins while the pointer is on it - with the hovered module's description along the bottom. The gear
 * slides that module's settings up over the list ({@link ClassicSettingsPage}). The X at the top right steps back
 * (settings, then the list, then the GUI itself).</p>
 *
 * <p>The old grid had six tiles for six categories; there are nine now, so Player takes Item too, Visuals
 * takes Interface, and Others takes Exploit. A panel taller than the window shrinks to fit rather than
 * clipping.</p>
 */
public class ClassicClickGuiScreen extends Screen implements SigmaClickGui, TextEntryScreen {
    static final int PAGE1_W = 396;
    static final int PAGE1_H = 520;
    static final int PAGE2_W = 592;
    static final int PAGE2_H = 692;
    static final int LIST_X = 36;
    static final int LIST_Y = 62;
    static final int LIST_W = 536;
    static final int LIST_BOTTOM = 46;
    private static final int CELL_W = 170;
    private static final int CELL_H = 80;
    static final int INK = 0xFF010101;
    static final int WHITE = 0xFFFEFEFE;

    private record Group(String label, LegacyTexture icon, LegacyTexture hover, int x, int y, ModuleCategory... categories) {
    }

    private static final Group[] GROUPS = {
        new Group("Combat", LegacyTexture.GUI_COMBAT, LegacyTexture.GUI_COMBAT_HOVER, 24, 58, ModuleCategory.COMBAT),
        new Group("Movement", LegacyTexture.GUI_MOVEMENT, LegacyTexture.GUI_MOVEMENT_HOVER, 24, 208, ModuleCategory.MOVEMENT),
        new Group("World", LegacyTexture.GUI_WORLD, LegacyTexture.GUI_WORLD_HOVER, 24, 358, ModuleCategory.WORLD),
        new Group("Player", LegacyTexture.GUI_PLAYER, LegacyTexture.GUI_PLAYER_HOVER, 201, 58, ModuleCategory.PLAYER, ModuleCategory.ITEM),
        new Group("Visuals", LegacyTexture.GUI_VISUALS, LegacyTexture.GUI_VISUALS_HOVER, 201, 208, ModuleCategory.RENDER, ModuleCategory.INTERFACE),
        new Group("Others", LegacyTexture.GUI_OTHERS, LegacyTexture.GUI_OTHERS_HOVER, 201, 358, ModuleCategory.MISC, ModuleCategory.EXPLOIT)
    };

    private final ModuleManager modules;
    private final ClickGuiInteractions interactions = new ClickGuiInteractions();
    private final Animation open = new Animation(500, 200, Animation.Direction.FORWARDS);
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.CLASSIC);
    private final java.util.Map<Module, Animation> switches = new java.util.HashMap<>();
    private final java.util.Map<Module, Animation> gears = new java.util.HashMap<>();
    private Group group;
    private List<Module> listed = List.of();
    private ClassicSettingsPage settings;
    private boolean closing;
    private Animation fadeOut;
    private boolean scrollDrag;

    // The panel's place on screen for the frame being drawn: local coordinates map to legacy pixels by this.
    private float scale = 1.0F;
    private float centerX;
    private float centerY;
    private int panelW = PAGE1_W;
    private int panelH = PAGE1_H;

    public ClassicClickGuiScreen(final ModuleManager modules) {
        super(Component.literal("Classic ClickGUI"));
        this.modules = modules;
        // -Dsigma.debug.classicGroup=<label>[:Module]: start on that group's list, optionally with a module's settings open.
        String debug = System.getProperty("sigma.debug.classicGroup");
        if (debug != null) {
            String[] parts = debug.split(":");
            for (Group candidate : GROUPS) {
                if (candidate.label().equalsIgnoreCase(parts[0])) {
                    this.enter(candidate);
                    if (parts.length > 1) {
                        for (Module module : this.listed) {
                            if (module.getName().equalsIgnoreCase(parts[1])) {
                                this.settings = new ClassicSettingsPage(module, this.interactions);
                            }
                        }
                    }
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
        return this.settings != null && this.settings.typing();
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        GuiVisuals.blurBackground(graphics);
    }

    private void enter(final Group group) {
        this.group = group;
        List<Module> list = new ArrayList<>();
        for (ModuleCategory category : group.categories()) {
            list.addAll(this.modules.byCategory(category));
        }
        this.listed = list;
        this.scroll.setOffset(0);
        this.settings = null;
        this.open.changeDirection(Animation.Direction.FORWARDS);
        this.open.setProgress(0.0F);
    }

    private void back() {
        if (this.settings != null) {
            this.settings.close();
        } else if (this.group != null) {
            this.group = null;
            this.listed = List.of();
            this.open.changeDirection(Animation.Direction.FORWARDS);
            this.open.setProgress(0.0F);
        } else {
            this.beginClose();
        }
    }

    @Override
    public boolean beginClose() {
        if (!this.closing) {
            this.closing = true;
            this.fadeOut = new Animation(200, 200, Animation.Direction.BACKWARDS);
            this.fadeOut.setProgress(1.0F);
        }
        return true;
    }

    @Override
    public void onClose() {
        this.beginClose();
    }

    @Override
    public void tick() {
        if (this.closing && this.fadeOut.calcPercent() <= 0.0F) {
            ClickGuiHandler.close();
        }
    }

    // ------------------------------------------------------------------ geometry

    private void place(final int w, final int h) {
        this.panelW = this.group == null ? PAGE1_W : PAGE2_W;
        this.panelH = this.group == null ? PAGE1_H : PAGE2_H;
        this.scale = Math.min(1.0F, Math.min((h - 20) / (float) this.panelH, (w - 20) / (float) this.panelW));
        this.centerX = w / 2.0F;
        this.centerY = h / 2.0F;
    }

    private double localX(final double mx) {
        return (mx - this.centerX) / this.scale + this.panelW / 2.0;
    }

    private double localY(final double my) {
        return (my - this.centerY) / this.scale + this.panelH / 2.0;
    }

    private int listH() {
        return this.panelH - LIST_Y - LIST_BOTTOM;
    }

    private int contentH() {
        int rows = (this.listed.size() + 2) / 3;
        return 10 + rows * CELL_H;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            this.place(w, h);
            double mx = this.localX(LegacyCanvas.mouseX());
            double my = this.localY(LegacyCanvas.mouseY());

            float t = Easing.easeOutCubic(this.open.calcPercent(), 0.0F, 1.0F, 1.0F);
            float alpha = t * (this.closing ? this.fadeOut.calcPercent() : 1.0F);
            float grow = this.scale * (0.92F + 0.08F * t);
            c.fill(0, 0, w, h, LegacyCanvas.alpha(INK, 0.35F * (this.closing ? this.fadeOut.calcPercent() : 1.0F) * (this.group == null ? t : 1.0F)));

            c.push();
            c.translate(this.centerX, this.centerY);
            c.scale(grow, grow);
            c.translate(-this.panelW / 2.0F, -this.panelH / 2.0F);

            c.rounded(0, 0, this.panelW, this.panelH, 3, LegacyCanvas.alpha(0xFFD9D9D9, 0.9F * alpha));
            if (this.group == null) {
                this.drawCategories(c, mx, my, alpha);
            } else {
                this.drawModules(c, mx, my, alpha);
            }
            c.pop();
        }
    }

    private void drawCategories(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        float titleW = c.textWidth(Face.CLASSIC, 28, "Sigma");
        c.text(Face.CLASSIC, 28, "Sigma", (this.panelW - titleW) / 2.0F, 10, LegacyCanvas.alpha(0xFF303030, alpha));
        for (Group group : GROUPS) {
            boolean hover = mx >= group.x() && mx < group.x() + CELL_W && my >= group.y() && my < group.y() + 130;
            c.image(hover ? group.hover() : group.icon(), group.x() + (CELL_W - 64) / 2.0F, group.y() + 10, 64, 64, LegacyCanvas.alpha(WHITE, alpha));
            float labelW = c.textWidth(Face.CLASSIC, 25, group.label());
            c.text(Face.CLASSIC, 25, group.label(), group.x() + (CELL_W - labelW) / 2.0F, group.y() + 130 - 50,
                LegacyCanvas.alpha(hover ? 0xFFFB200D : 0xFF1D1D1D, alpha));
        }
        this.drawExit(c, this.panelW - 41, 9, mx, my, alpha);
    }

    private void drawExit(final LegacyCanvas c, final int x, final int y, final double mx, final double my, final float alpha) {
        boolean hover = mx >= x && mx < x + 30 && my >= y && my < y + 30;
        c.image(hover ? LegacyTexture.GUI_XMARK_HOVER : LegacyTexture.GUI_XMARK, x, y, 30, 30, LegacyCanvas.alpha(WHITE, alpha));
    }

    private void drawModules(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        float titleW = c.textWidth(Face.CLASSIC, 25, this.group.label());
        c.text(Face.CLASSIC, 25, this.group.label(), (this.panelW - titleW) / 2.0F, 18, LegacyCanvas.alpha(INK, alpha));
        this.drawExit(c, this.panelW - 47, 18, mx, my, alpha);

        int listH = this.listH();
        this.scroll.clamp(this.contentH(), listH);
        boolean insideList = mx >= LIST_X && mx < LIST_X + LIST_W && my >= LIST_Y && my < LIST_Y + listH;
        Module hovered = null;
        c.scissor(LIST_X, LIST_Y, LIST_X + LIST_W, LIST_Y + listH);
        for (int i = 0; i < this.listed.size(); i++) {
            Module module = this.listed.get(i);
            int x = LIST_X + 4 + CELL_W * (i % 3);
            int y = LIST_Y + 10 + CELL_H * (i / 3) - this.scroll.offset();
            if (y + CELL_H < LIST_Y || y > LIST_Y + listH) {
                continue;
            }
            if (insideList && mx >= x && mx < x + CELL_W && my >= y && my < y + CELL_H) {
                hovered = module;
            }
            this.drawCard(c, module, x, y, mx, my, alpha);
        }
        c.unscissor();
        this.scroll.draw(c, LIST_X + LIST_W, LIST_Y, listH, this.contentH(), listH, insideList, alpha);

        if (hovered != null && this.settings == null) {
            c.text(Face.CLASSIC, 17, hovered.getDescription(), 20, this.panelH - 26, LegacyCanvas.alpha(0xFF222222, alpha));
            c.scissor(5, this.panelH - 27, 17, this.panelH - 3);
            c.image(LegacyTexture.GUI_XMARK, 5, this.panelH - 27, 24, 24, LegacyCanvas.alpha(WHITE, alpha));
            c.unscissor();
        }

        if (this.settings != null) {
            this.settings.draw(c, mx, my, alpha, this.panelW, this.panelH);
            if (this.settings.finished()) {
                this.settings = null;
            }
        }
    }

    private void drawCard(final LegacyCanvas c, final Module module, final int x, final int y, final double mx, final double my, final float alpha) {
        boolean on = module.isEnabled();
        c.text(Face.CLASSIC, 17, module.getName(), x + 10, y + 8, LegacyCanvas.alpha(INK, alpha * (on ? 0.9F : 0.5F)));
        boolean binding = this.interactions.isBinding(module);
        c.text(Face.CLASSIC, 15, "Bind", x + 15, y + 33, LegacyCanvas.alpha(INK, alpha));
        c.text(Face.CLASSIC, 15, binding ? "..." : LegacyKeys.name(module.getKeybind()), x + 15, y + 52, LegacyCanvas.alpha(INK, alpha * 0.7F));
        boolean hasSettings = !module.settings().isEmpty();
        if (hasSettings) {
            c.text(Face.CLASSIC, 12, "Settings", x + 84, y + 34, LegacyCanvas.alpha(INK, alpha));
        }

        // The slide-switch: 20 frames of a 40x18 sprite, played forward as it turns on and back as it turns off.
        Animation slide = this.switches.computeIfAbsent(module, m -> {
            Animation a = new Animation(200, 200, m.isEnabled() ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            a.setProgress(m.isEnabled() ? 1.0F : 0.0F);
            return a;
        });
        slide.changeDirection(on ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        int frame = Math.round(19 * slide.calcPercent());
        c.region(LegacyTexture.GUI_CHECKBOX.id, LegacyTexture.GUI_CHECKBOX.width, LegacyTexture.GUI_CHECKBOX.height, frame * 40, 0, 40, 18,
            x + 114, y + 9, 40, 18, LegacyCanvas.alpha(WHITE, alpha));

        if (hasSettings) {
            boolean over = mx >= x + 132 && mx < x + 152 && my >= y + 32 && my < y + 52;
            Animation spin = this.gears.computeIfAbsent(module, m -> new Animation(1200, 1200, Animation.Direction.BACKWARDS));
            spin.changeDirection(over ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            if (spin.calcPercent() == 1.0F && over) {
                this.gears.put(module, new Animation(1200, 1200, Animation.Direction.FORWARDS));
            }
            float cx = x + 142;
            float cy = y + 42;
            c.push();
            c.translate(cx, cy);
            c.graphics().pose().rotate((float) Math.toRadians(spin.calcPercent() * 360.0F));
            c.translate(-cx, -cy);
            c.image(over ? LegacyTexture.GUI_GEAR_HOVER : LegacyTexture.GUI_GEAR, x + 132, y + 32, 20, 20, LegacyCanvas.alpha(WHITE, alpha));
            c.pop();
        }
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
        int w = this.width * this.minecraft.getWindow().getGuiScale();
        int h = this.height * this.minecraft.getWindow().getGuiScale();
        this.place(w, h);
        double mx = this.localX(LegacyCanvas.toLegacy(event.x()));
        double my = this.localY(LegacyCanvas.toLegacy(event.y()));

        if (this.group == null) {
            if (mx >= this.panelW - 41 && mx < this.panelW - 11 && my >= 9 && my < 39) {
                this.beginClose();
                return true;
            }
            for (Group candidate : GROUPS) {
                if (mx >= candidate.x() && mx < candidate.x() + CELL_W && my >= candidate.y() && my < candidate.y() + 130) {
                    this.enter(candidate);
                    return true;
                }
            }
            if (mx < 0 || mx >= this.panelW || my < 0 || my >= this.panelH) {
                this.beginClose();
            }
            return true;
        }

        if (this.settings != null && this.settings.mouseClicked(mx, my, event.button(), this.panelW, this.panelH)) {
            return true;
        }
        if (mx >= this.panelW - 47 && mx < this.panelW - 17 && my >= 18 && my < 48) {
            this.back();
            return true;
        }
        if (this.settings != null) {
            return true;
        }
        int listH = this.listH();
        if (event.button() == 0 && this.scroll.press(mx, my, LIST_X + LIST_W, LIST_Y, listH, this.contentH(), listH)) {
            this.scrollDrag = true;
            return true;
        }
        if (mx >= LIST_X && mx < LIST_X + LIST_W && my >= LIST_Y && my < LIST_Y + listH) {
            for (int i = 0; i < this.listed.size(); i++) {
                Module module = this.listed.get(i);
                int x = LIST_X + 4 + CELL_W * (i % 3);
                int y = LIST_Y + 10 + CELL_H * (i / 3) - this.scroll.offset();
                if (mx < x || mx >= x + CELL_W || my < y || my >= y + CELL_H) {
                    continue;
                }
                boolean hasSettings = !module.settings().isEmpty();
                if (mx >= x + 114 && mx < x + 154 && my >= y + 9 && my < y + 27) {
                    module.toggle();
                } else if (hasSettings && (event.button() == 1 || mx >= x + 132 && mx < x + 152 && my >= y + 32 && my < y + 52)) {
                    this.settings = new ClassicSettingsPage(module, this.interactions);
                } else if (mx >= x + 10 && mx < x + 80 && my >= y + 30 && my < y + 70) {
                    this.interactions.handleKeybindClick(module, event.button());
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        double mx = this.localX(LegacyCanvas.toLegacy(event.x()));
        double my = this.localY(LegacyCanvas.toLegacy(event.y()));
        if (this.settings != null && this.settings.mouseDragged(mx, my)) {
            return true;
        }
        if (this.scrollDrag) {
            this.scroll.drag(my, LIST_Y, this.listH(), this.contentH(), this.listH());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.scrollDrag = false;
        this.scroll.release();
        if (this.settings != null) {
            this.settings.mouseReleased();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        if (this.settings != null) {
            this.settings.wheel(scrollY);
        } else if (this.group != null) {
            this.scroll.wheel(scrollY, this.contentH(), this.listH());
        }
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.interactions.keyPressed(event)) {
            return true;
        }
        if (this.settings != null && this.settings.keyPressed(event)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.back();
            return true;
        }
        if (InputConstants.getKey(event).equals(ClickGuiHandler.OPEN_KEY) && this.settings == null) {
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
        return this.settings != null && this.settings.charTyped(event) || super.charTyped(event);
    }
}
