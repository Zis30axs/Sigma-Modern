package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The credits page of the options: a near-black sheet with a title and the credits scrolling under it, a line at a time
 * in Helvetica Neue Light, the headings (lines between asterisks) in the Medium weight. The text is
 * {@code assets/minecraft/sigma/credits.txt}.
 */
public final class JelloCreditsScreen extends Screen {
    private static final int WHITE = 0xFFFEFEFE;
    private static final int LINE = 20;
    private static final int TOP = 100;

    private final Animation animation = new Animation(300, 300);
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private final List<String> lines = load();
    private final @Nullable Screen parent;

    public JelloCreditsScreen(final @Nullable Screen parent) {
        super(Component.literal("Credits"));
        this.parent = parent;
    }

    private static List<String> load() {
        try (InputStream in = JelloCreditsScreen.class.getResourceAsStream("/assets/minecraft/sigma/credits.txt")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        } catch (IOException ignored) {
            // Falls through to the short version.
        }

        return List.of("Development by Sigma Production");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        this.extractTransparentBackground(graphics);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(new JelloOptionsScreen(this.parent));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        super.extractRenderState(graphics, guiMouseX, guiMouseY, partialTick);
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            float p = this.animation.calcPercent();
            c.fill(0, 0, w, h, LegacyCanvas.alpha(0xFF010101, p * 0.95F));
            c.text(Face.JELLO_MEDIUM, 40.0F, "Credits and third party licensing information", 40, 40, LegacyCanvas.alpha(WHITE, p));

            int view = h - TOP;
            int content = 40 + this.lines.size() * LINE + 40;
            this.scroll.clamp(content, view);
            c.scissor(0, TOP, w, h);
            try {
                int y = TOP + 40 - this.scroll.offset();
                for (String line : this.lines) {
                    if (y + LINE >= TOP && y < h) {
                        boolean heading = line.startsWith("*") && line.endsWith("*") && line.length() > 1;
                        String text = heading ? line.substring(1, line.length() - 1) : line;
                        c.text(heading ? Face.JELLO_MEDIUM : Face.JELLO_LIGHT, 20.0F, text, 40, y, LegacyCanvas.alpha(WHITE, p));
                    }
                    y += LINE;
                }
            } finally {
                c.unscissor();
            }
            this.scroll.draw(c, w - 10, TOP, view, content, view, LegacyCanvas.mouseY() > TOP, p);
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        int h = this.height * this.minecraft.getWindow().getGuiScale();
        int view = h - TOP;
        int content = 40 + this.lines.size() * LINE + 40;
        if (event.button() == 0 && this.scroll.press(mx, my, this.width * this.minecraft.getWindow().getGuiScale() - 10, TOP, view, content, view)) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        int h = this.height * this.minecraft.getWindow().getGuiScale();
        int view = h - TOP;
        this.scroll.drag(LegacyCanvas.toLegacy(event.y()), TOP, view, 40 + this.lines.size() * LINE + 40, view);
        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.scroll.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        int h = this.height * this.minecraft.getWindow().getGuiScale();
        this.scroll.wheel(scrollY, 40 + this.lines.size() * LINE + 40, h - TOP);
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
    }
}
