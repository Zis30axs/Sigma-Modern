package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's Spotlight: a white search bar, 675 x 60, a quarter of the way down the screen. It is always ready for
 * typing; the first module whose name starts with what was typed is shown completed in faint type with whether it is on,
 * and Enter switches it and closes the bar.
 */
public final class JelloSpotlightScreen extends Screen implements TextEntryScreen {
    private static final int WIDTH = 675;
    private static final int HEIGHT = 60;
    private static final int INK = 0xFF010101;
    private static final int WHITE = 0xFFFEFEFE;

    private final Animation appear = new Animation(150, 150);
    private final LegacyTextField query = new LegacyTextField(LegacyTextField.Style.JELLO, 0, 0, WIDTH - 60, HEIGHT - 2, Face.JELLO_LIGHT, 25, "Search...");

    public JelloSpotlightScreen() {
        super(Component.literal("Spotlight"));
        this.query.setUnderline(false);
        this.query.setFocused(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTypingText() {
        return true;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        this.extractTransparentBackground(graphics);
    }

    /** The first module whose name starts with what is typed, or null when nothing is typed or nothing matches. */
    @Nullable Module match() {
        String typed = this.query.text();
        if (typed.isEmpty()) {
            return null;
        }

        String wanted = typed.toLowerCase(Locale.ROOT);
        for (Module module : Client.getInstance().getModuleManager().all()) {
            if (module.getName().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                return module;
            }
        }

        return null;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        super.extractRenderState(graphics, guiMouseX, guiMouseY, partialTick);
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            float alpha = this.appear.calcPercent();
            int x = (c.width() - WIDTH) / 2;
            int y = (int) (c.height() * 0.25F);
            c.outerGlow(x + 5, y + 5, WIDTH - 10, HEIGHT - 10, 30.0F, 0.4F * alpha);
            c.outerGlow(x + 5, y + 5, WIDTH - 10, HEIGHT - 10, 9.0F, 0.9F * alpha);
            c.rounded(x, y, WIDTH, HEIGHT, 10, LegacyCanvas.alpha(WHITE, 0.97F * alpha));
            c.image(LegacyTexture.JELLO_SEARCH, x + 20, y + 20, 20, 20, LegacyCanvas.alpha(INK, 0.3F * alpha));

            this.query.x = x + 50;
            this.query.y = y;
            this.query.draw(c, alpha);

            Module match = this.match();
            String typed = this.query.text();
            if (match != null) {
                // The completion sits behind what was typed: its own name's rest, and what the module is doing.
                String rest = match.getName().substring(typed.length());
                String ghost = typed + rest + (match.isEnabled() ? " - Enabled" : " - Disabled");
                float height = LegacyFonts.height(Face.JELLO_LIGHT, 25.0F);
                c.text(Face.JELLO_LIGHT, 25.0F, ghost, x + 54, y + (HEIGHT - 2) / 2.0F - height / 2.0F, LegacyCanvas.alpha(INK, 0.25F * alpha));
            }
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            Module match = this.match();
            if (match != null) {
                match.toggle();
            }
            this.onClose();
            return true;
        }

        this.query.keyPressed(event);
        return true;
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        return this.query.charTyped(event);
    }
}
