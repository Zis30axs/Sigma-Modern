package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.account.JelloAltManagerScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.jello.JelloBackdrop;
import com.mentalfrostbyte.jello.gui.jello.JelloChangelog;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyLabelButton;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.util.math.Easing;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's main menu, as the old client drew it (its {@code MainMenuScreen} + {@code JelloMainMenu}).
 *
 * <p>Everything is laid out in framebuffer pixels through {@link LegacyCanvas}, so the numbers below are the
 * old ones. The scene behind is {@link JelloBackdrop}: three layers that slide against each other with the
 * pointer, and floating bubbles. Over it:</p>
 * <ul>
 *   <li>the 336x178 "Jello" wordmark, and five 128 px icons at 122 px pitch that swell 20 % and rise on hover,
 *       with a soft glow behind and their label under them;</li>
 *   <li>Exit, Changelog and Switch as text with a growing underline;</li>
 *   <li>Changelog and Exit both blur and dim the scene and shrink the menu to 93 %; Changelog then shows the
 *       release list, Exit a random goodbye and a quote, and closes the game two seconds later.</li>
 * </ul>
 */
public final class JelloMainMenuScreen extends SigmaMainMenuScreen {

    private static final LegacyTexture[] ICONS = {
        LegacyTexture.ICON_SINGLEPLAYER, LegacyTexture.ICON_MULTIPLAYER, LegacyTexture.ICON_REALMS,
        LegacyTexture.ICON_OPTIONS, LegacyTexture.ICON_ALT
    };
    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Realms", "Options", "Alt Manager"};

    private static final String[] GOODBYE_TITLES = {
        "Goodbye.", "See you soon.", "Bye!", "Au revoir", "See you!", "Ciao!", "Adios", "Farewell", "See you later!",
        "Have a good day!", "See you arround.", "See you tomorrow!", "Goodbye, friend.", "Logging out.", "Signing off!",
        "Shutting down.", "Was good to see you!"
    };
    private static final String[] GOODBYE_MESSAGES = {
        "The two hardest things to say in life are hello for the first time and goodbye for the last.",
        "Don’t cry because it’s over, smile because it happened.",
        "It’s time to say goodbye, but I think goodbyes are sad and I’d much rather say hello. Hello to a new adventure.",
        "We’ll meet again, Don’t know where, don’t know when, But I know we’ll meet again, some sunny day.",
        "This is not a goodbye but a 'see you soon'.",
        "You are my hardest goodbye.",
        "Goodbyes are not forever, are not the end; it simply means I’ll miss you until we meet again.",
        "Good friends never say goodbye. They simply say \"See you soon\".",
        "Every goodbye always makes the next hello closer.",
        "Where's the good in goodbye?",
        "And I'm sorry, so sorry. But, I have to say goodbye."
    };
    private static final String[] GOODBYE_MESSAGES_FR = {
        "Mon salut jamais dans la fuite, avant d'm'éteindre, faut m'débrancher",
        "Prêt à partir pour mon honneur"
    };

    private static final int WHITE = 0xFFFEFEFE;
    private static final int BLACK = 0xFF010101;
    private static final long EXIT_DELAY_MS = 2000L;

    private final JelloBackdrop backdrop = new JelloBackdrop();
    private final JelloChangelog changelog = new JelloChangelog();
    private final Animation overlay = new Animation(200, 200, Animation.Direction.BACKWARDS);
    private final Animation goodbye = new Animation(200, 200, Animation.Direction.BACKWARDS);
    private final Animation[] iconHover = new Animation[ACTIONS.length];
    private final LegacyLabelButton exit;
    private final LegacyLabelButton changelogButton;
    private final LegacyLabelButton switchButton;
    private final String goodbyeTitle;
    private final String goodbyeMessage;

    private int pressed = -1;
    private long exitAtMillis = -1L;

    public JelloMainMenuScreen() {
        super(Component.literal("Sigma Jello"));
        for (int i = 0; i < this.iconHover.length; i++) {
            this.iconHover[i] = new Animation(160, 140, Animation.Direction.BACKWARDS);
        }
        this.exit = new LegacyLabelButton("Exit", 30, 24, 50, 50, Face.JELLO_LIGHT, 20, LegacyCanvas.alpha(WHITE, 0.4F));
        this.changelogButton = new LegacyLabelButton("Changelog", 90, 24, 110, 50, Face.JELLO_LIGHT, 20, LegacyCanvas.alpha(WHITE, 0.7F));
        this.switchButton = new LegacyLabelButton("Switch", 220, 24, 50, 50, Face.JELLO_LIGHT, 20, LegacyCanvas.alpha(WHITE, 0.7F));

        Random random = new Random();
        this.goodbyeTitle = GOODBYE_TITLES[random.nextInt(GOODBYE_TITLES.length)];
        int messages = GOODBYE_MESSAGES.length + (isFrench() ? GOODBYE_MESSAGES_FR.length : 0);
        int pick = random.nextInt(messages);
        this.goodbyeMessage = pick < GOODBYE_MESSAGES.length ? GOODBYE_MESSAGES[pick] : GOODBYE_MESSAGES_FR[pick - GOODBYE_MESSAGES.length];

        // -Dsigma.debug.jelloMenu=changelog|goodbye: start with that panel open (goodbye without closing the game).
        String debug = System.getProperty("sigma.debug.jelloMenu");
        if ("changelog".equals(debug)) {
            this.overlay.changeDirection(Animation.Direction.FORWARDS);
            this.changelog.setOpen(true);
        } else if ("goodbye".equals(debug)) {
            this.overlay.changeDirection(Animation.Direction.FORWARDS);
            this.goodbye.changeDirection(Animation.Direction.FORWARDS);
        }
    }

    private static boolean isFrench() {
        Locale locale = Locale.getDefault(Locale.Category.DISPLAY);
        return locale.getLanguage().equals(Locale.FRENCH.getLanguage());
    }

    @Override
    public void tick() {
        if (this.exitAtMillis >= 0L && System.currentTimeMillis() >= this.exitAtMillis) {
            this.exitAtMillis = -1L;
            this.quitGame();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            float transition = this.transition();

            this.backdrop.draw(c, mx, my, transition);
            this.drawMenu(c, mx, my, 1.0F - transition, transition);
            this.changelog.draw(c, mx, my, 1.0F);
            if (this.goodbye.getDirection() == Animation.Direction.FORWARDS || this.goodbye.calcPercent() > 0.0F) {
                float p = this.goodbye.calcPercent();
                c.textCentered(Face.JELLO_MEDIUM, 50, this.goodbyeTitle, c.width() / 2.0F, c.height() / 2.0F - 30, LegacyCanvas.alpha(WHITE, p));
                c.textCentered(Face.JELLO_LIGHT, 18, "\"" + this.goodbyeMessage + "\"", c.width() / 2.0F, c.height() / 2.0F + 30,
                    LegacyCanvas.alpha(WHITE, p * 0.5F));
            }
        }
    }

    /** How far the menu has given way to the overlay (changelog or goodbye), eased as the old client did. */
    private float transition() {
        float p = this.overlay.calcPercent();
        return this.overlay.getDirection() == Animation.Direction.BACKWARDS
            ? Easing.easeInCubic(p, 0.0F, 1.0F, 1.0F)
            : Easing.easeOutCubic(p, 0.0F, 1.0F, 1.0F);
    }

    private void drawMenu(final LegacyCanvas c, final double mx, final double my, final float alpha, final float transition) {
        if (alpha <= 0.0F) {
            return;
        }
        boolean live = this.overlay.calcPercent() == 0.0F;
        int width = c.width();
        int height = c.height();
        float shrink = 1.0F - 0.07F * transition;

        c.push();
        c.scaleAbout(shrink, shrink, width / 2.0F, height / 2.0F);

        c.image(LegacyTexture.JELLO_LOGO, width / 2 - LegacyTexture.JELLO_LOGO.width / 2, height / 2 - LegacyTexture.JELLO_LOGO.height,
            LegacyTexture.JELLO_LOGO.width, LegacyTexture.JELLO_LOGO.height, LegacyCanvas.alpha(WHITE, alpha));

        for (int i = 0; i < ICONS.length; i++) {
            this.drawIcon(c, i, iconX(width, i), iconY(height), mx, my, alpha, live);
        }

        this.softText(c, "© Sigma Prod", 10, height - 31, alpha);
        String version = "Jello for Sigma " + Client.FULL_VERSION + "  -  Minecraft " + this.minecraft.getLaunchedVersion();
        this.softText(c, version, width - Math.round(c.textWidth(Face.JELLO_LIGHT, 20, version)) - 9, height - 31, alpha);

        this.switchButton.draw(c, mx, my, alpha, live);
        this.changelogButton.draw(c, mx, my, alpha, live);
        this.exit.draw(c, mx, my, alpha, live);
        c.pop();
    }

    /** Text with the old client's crisp black under-print. */
    private void softText(final LegacyCanvas c, final String text, final float x, final float y, final float alpha) {
        c.text(Face.JELLO_LIGHT, 20, text, x, y, LegacyCanvas.alpha(BLACK, alpha * 0.5F));
        c.text(Face.JELLO_LIGHT, 20, text, x, y, LegacyCanvas.alpha(WHITE, alpha));
    }

    private static int iconX(final int width, final int index) {
        return width / 2 - 305 + index * 128 + index * -6;
    }

    private static int iconY(final int height) {
        return height / 2 + 14;
    }

    private void drawIcon(final LegacyCanvas c, final int index, final int x, final int y, final double mx, final double my, final float alpha, final boolean live) {
        final int size = 128;
        boolean hovered = live && mx >= x && mx < x + size && my >= y && my < y + size;
        Animation hover = this.iconHover[index];
        hover.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float progress = hover.calcPercent();
        float motion = hover.getDirection() == Animation.Direction.BACKWARDS
            ? SmoothInterpolator.interpolate(progress, 0.45, 0.02, 0.59, 0.28)
            : SmoothInterpolator.interpolate(progress, 0.24, 0.88, 0.3, 1.0);

        float w = (float) (size * (1.0 + motion * 0.2));
        float h = w;
        float dx = x - (w - size) / 2.0F;
        float dy = (float) (y - (h - size) / 2.0F - (size / 2.0F * motion) * 0.2);

        // The icon is square, so it fills the box; the glow behind it reaches 85 px past.
        float glow = 85.0F;
        c.image(LegacyTexture.JELLO_SHADOW, dx - glow, dy - glow, w + glow * 2, h + glow * 2, LegacyCanvas.alpha(WHITE, progress * 0.7F * alpha));
        float press = this.pressed == index ? 0.1F : 0.0F;
        c.image(ICONS[index], dx, dy, w, h,
            LegacyCanvas.alpha(LegacyCanvas.shiftTowardsOther(WHITE, BLACK, 1.0F - press), alpha));

        if (motion > 0.0F) {
            String label = ACTIONS[index];
            float textW = c.textWidth(Face.JELLO_LIGHT, 25, label);
            float scale = 0.8F + motion * 0.2F;
            c.push();
            c.translate(x + size / 2.0F - textW / 2.0F, y + size - 40);
            c.scale(scale, scale);
            float textH = c.textHeight(Face.JELLO_LIGHT, 25);
            float tx = (1.0F - scale) * textW / 2.0F + 1.0F;
            c.image(LegacyTexture.JELLO_SHADOW, tx - textW / 2.0F, textH / 3.0F, textW * 2, textH * 3, LegacyCanvas.alpha(WHITE, motion * 0.6F * alpha));
            c.text(Face.JELLO_LIGHT, 25, label, tx, 40, LegacyCanvas.alpha(WHITE, motion * 0.6F * alpha));
            c.pop();
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());

        if (this.changelog.isOpen()) {
            return this.changelog.press(mx, my) || super.mouseClicked(event, doubleClick);
        }
        if (this.overlay.calcPercent() != 0.0F) {
            return true;
        }

        if (this.exit.contains(mx, my)) {
            this.click();
            this.beginExit();
            return true;
        }
        if (this.changelogButton.contains(mx, my)) {
            this.click();
            this.overlay.changeDirection(Animation.Direction.FORWARDS);
            this.changelog.setOpen(true);
            return true;
        }
        if (this.switchButton.contains(mx, my)) {
            this.click();
            this.openModeSelect();
            return true;
        }

        int width = this.minecraft.getWindow().getGuiScaledWidth() * this.minecraft.getWindow().getGuiScale();
        int height = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        for (int i = 0; i < ICONS.length; i++) {
            int x = iconX(width, i);
            int y = iconY(height);
            if (mx >= x && mx < x + 128 && my >= y && my < y + 128) {
                this.pressed = i;
                this.click();
                switch (i) {
                    case 0 -> this.openSingleplayer();
                    case 1 -> this.openMultiplayer();
                    case 2 -> this.openRealms();
                    case 3 -> this.openOptions();
                    case 4 -> this.minecraft.gui.setScreen(new JelloAltManagerScreen(this));
                    default -> throw new IllegalStateException("Unexpected Jello menu action " + i);
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        this.changelog.drag(LegacyCanvas.toLegacy(event.y()));
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.pressed = -1;
        this.changelog.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        if (this.changelog.isOpen()) {
            this.changelog.wheel(scrollY);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && this.overlay.calcPercent() > 0.0F) {
            // Backs out of the changelog - and of a goodbye that hasn't closed the game yet.
            this.exitAtMillis = -1L;
            this.goodbye.changeDirection(Animation.Direction.BACKWARDS);
            this.overlay.changeDirection(Animation.Direction.BACKWARDS);
            this.changelog.setOpen(false);
            return true;
        }
        return super.keyPressed(event);
    }

    private void beginExit() {
        this.overlay.changeDirection(Animation.Direction.FORWARDS);
        this.goodbye.changeDirection(Animation.Direction.FORWARDS);
        this.exitAtMillis = System.currentTimeMillis() + EXIT_DELAY_MS;
    }

    private void click() {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
