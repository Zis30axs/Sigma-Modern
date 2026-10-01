package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.account.ClassicAltManagerScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.classic.ClassicParticles;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.util.math.Easing;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Sigma Classic's main menu, as the old client drew it ({@code ClassicMainScreen}).
 *
 * <p>Everything is in framebuffer pixels through {@link LegacyCanvas}. The scene reacts to the pointer in
 * three depths: the backdrop moves by 1/200 of the pointer's travel, the wordmark and buttons by 1/40, and the
 * drifting particles by 1/12, so they slide against each other. The pointer is followed with a lag (each frame
 * closes 5.5 % of the gap). On opening, the whole screen rises 5 px into place over 175 ms.</p>
 *
 * <p>The seven buttons (four above, three below) are 114x140 with a 100 px icon and a label under it. Under the
 * pointer, a button springs up 25 px and overshoots slightly (a bezier with control points outside 0..1); when
 * the pointer leaves, it springs back the other way. The spring plays once per visit: it restarts only after
 * the previous one finished.</p>
 */
public final class ClassicMainMenuScreen extends SigmaMainMenuScreen {

    private static final LegacyTexture[] ICONS = {
        LegacyTexture.CLASSIC_SINGLEPLAYER, LegacyTexture.CLASSIC_MULTIPLAYER, LegacyTexture.CLASSIC_OPTIONS, LegacyTexture.CLASSIC_LANGUAGE,
        LegacyTexture.CLASSIC_ACCOUNTS, LegacyTexture.CLASSIC_SWITCH, LegacyTexture.CLASSIC_EXIT
    };
    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Options", "Language", "Accounts", "Switch", "Exit"};

    private static final int WHITE = 0xFFFEFEFE;
    private static final int BLACK = 0xFF010101;
    private static final int BOX_W = 114;
    private static final int BOX_H = 140;

    private final Animation[] hover = new Animation[ACTIONS.length];
    private final Animation intro = new Animation(175, 325, Animation.Direction.FORWARDS);
    private final ClassicParticles particles = new ClassicParticles();
    private final String credits;

    private float followX = -1;
    private float followY = -1;
    private long lastNanos = System.nanoTime();

    public ClassicMainMenuScreen() {
        super(Component.literal("Sigma Classic"));
        for (int i = 0; i < this.hover.length; i++) {
            this.hover[i] = new Animation(300, 300, Animation.Direction.BACKWARDS);
        }
        List<String> names = new ArrayList<>(List.of("LeakedPvP", "Omikron"));
        Collections.shuffle(names);
        this.credits = "by " + names.get(0) + ", " + names.get(1);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int width = c.width();
            int height = c.height();
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();

            long now = System.nanoTime();
            float frames = Math.min(6.0F, (now - this.lastNanos) / 1.0E9F * 60.0F);
            this.lastNanos = now;
            if (this.followX < 0) {
                this.followX = width / 2.0F;
                this.followY = height / 2.0F;
            }
            float ease = 1.0F - (float) Math.pow(1.0 - 0.055, frames);
            this.followX += ((float) mx - this.followX) * ease;
            this.followY += ((float) my - this.followY) * ease;

            // The screen rises into place as it opens: everything starts 5 px low, the backdrop cancels that out
            // and the wordmark and buttons start 5 px lower still.
            int rise = Math.round((1.0F - Easing.easeOutQuad(this.intro.calcPercent(), 0.0F, 1.0F, 1.0F)) * 5.0F);
            c.push();
            c.translate(0, rise);

            // Backdrop: 1/200 of the pointer's travel, a little oversized so the edge never shows.
            float bgX = (int) (-width / 200 + this.followX / 200.0F);
            float bgY = (int) (-height / 100 + this.followY / 100.0F) - rise;
            c.image(LegacyTexture.CLASSIC_BACKGROUND, -10 + bgX, -10 + bgY, width + 20, height + 20, 0xFFFFFFFF);

            // Particles: 1/12.
            this.particles.draw(c, (int) (-width / 12 + this.followX / 12.0F), (int) (-height / 12 + this.followY / 12.0F));

            // Wordmark and buttons: 1/40.
            float groupX = (int) (-width / 40 + this.followX / 40.0F);
            float groupY = (int) (-height / 40 + this.followY / 40.0F) + rise;
            this.drawGroup(c, width, height, groupX, groupY, mx, my);

            String copyright = "© Sigma Prod";
            c.text(Face.JELLO_LIGHT, 18, copyright, 10, 8, LegacyCanvas.alpha(WHITE, 1.0F));
            c.text(Face.CLASSIC, 17, this.credits, 130, 9, LegacyCanvas.alpha(WHITE, 0.5F));

            String version = "Sigma " + Client.FULL_VERSION + " for Minecraft " + this.minecraft.getLaunchedVersion();
            c.text(Face.CLASSIC, 20, "Hello," + this.minecraft.getUser().getName(), 10, height - 55, WHITE);
            c.text(Face.CLASSIC, 20, "You are using the latest version", 10, height - 31, WHITE);
            c.text(Face.CLASSIC, 20, version, width - c.textWidth(Face.CLASSIC, 20, version) - 9, height - 31, WHITE);
            c.pop();
        }
    }

    private void drawGroup(final LegacyCanvas c, final int width, final int height, final float gx, final float gy, final double mx, final double my) {
        int groupX = (width - 480) / 2;
        int groupY = height / 2 - 230;
        c.image(LegacyTexture.CLASSIC_BIG, groupX + (480 - 300) / 2.0F + gx, groupY + 30 + gy, 300, 97, WHITE);

        for (int i = 0; i < ACTIONS.length; i++) {
            float x = buttonX(width, i) + gx;
            float y = buttonY(height, i) + gy;
            boolean hovered = mx >= x && mx < x + BOX_W && my >= y && my < y + BOX_H;
            Animation spring = this.hover[i];
            if (hovered && spring.calcPercent() < 0.1F) {
                spring.changeDirection(Animation.Direction.FORWARDS);
            } else if (!hovered && spring.calcPercent() == 1.0F) {
                spring.changeDirection(Animation.Direction.BACKWARDS);
            }
            float p = spring.calcPercent();
            float motion = spring.getDirection() == Animation.Direction.BACKWARDS
                ? SmoothInterpolator.interpolate(p, 0.81, 0.38, 0.32, -1.53)
                : SmoothInterpolator.interpolate(p, 0.68, 2.32, 0.06, 0.48);
            float lift = -25.0F * motion;

            c.image(ICONS[i], x + 20, y + lift, 100, 100, WHITE);
            String label = ACTIONS[i];
            float labelX = x + 12 - (c.textWidth(Face.CLASSIC, 20, label) - BOX_W) / 2.0F;
            float labelY = y + lift + 102;
            c.text(Face.CLASSIC, 20, label, labelX, labelY + 1, LegacyCanvas.alpha(BLACK, 0.5F));
            c.text(Face.CLASSIC, 20, label, labelX, labelY, WHITE);
        }
    }

    private static int buttonX(final int width, final int index) {
        int groupX = (width - 480) / 2;
        if (index < 4) {
            return groupX - 4 + index * 116;
        }
        return groupX + 36 + (index - 4) * 128;
    }

    private static int buttonY(final int height, final int index) {
        int groupY = height / 2 - 230;
        return index < 4 ? groupY + 150 : groupY + 300;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        int width = this.minecraft.getWindow().getGuiScaledWidth() * this.minecraft.getWindow().getGuiScale();
        int height = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        float gx = (int) (-width / 40 + this.followX / 40.0F);
        float gy = (int) (-height / 40 + this.followY / 40.0F);
        for (int i = 0; i < ACTIONS.length; i++) {
            float x = buttonX(width, i) + gx;
            float y = buttonY(height, i) + gy;
            if (mx < x || mx >= x + BOX_W || my < y || my >= y + BOX_H) {
                continue;
            }
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            switch (i) {
                case 0 -> this.openSingleplayer();
                case 1 -> this.openMultiplayer();
                case 2 -> this.openOptions();
                case 3 -> this.openLanguage();
                case 4 -> this.minecraft.gui.setScreen(new ClassicAltManagerScreen(this));
                case 5 -> this.openModeSelect();
                case 6 -> this.quitGame();
                default -> throw new IllegalStateException("Unexpected Classic menu action " + i);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
