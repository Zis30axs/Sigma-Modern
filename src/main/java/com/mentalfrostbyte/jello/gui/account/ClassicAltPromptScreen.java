package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountEntry;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.classic.ClassicButton;
import com.mentalfrostbyte.jello.gui.classic.ClassicParticles;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Classic alt manager's "Add Alt" and "Add Login" pages ({@code AddAltScreen}, {@code DirectLoginScreen}):
 * the main menu's backdrop almost blacked out (a 95 % wash with a hint of red), a heading and a coloured status
 * line at the top, a 400 px field and a column of 400x40 buttons.
 *
 * <p>{@link Mode#ADD} takes a Microsoft sign-in or an offline name and adds it to the list; {@link Mode#DIRECT}
 * takes a name and logs in as it at once. (The old pages took an e-mail and password, or a session token, which
 * the account store no longer holds.)</p>
 */
public final class ClassicAltPromptScreen extends Screen implements TextEntryScreen {
    public enum Mode {
        ADD("Add Alt"), DIRECT("Add Login");

        private final String title;

        Mode(final String title) {
            this.title = title;
        }
    }

    private static final int WHITE = 0xFFFEFEFE;
    private static final int MID_GREY = 0xFF999999;

    private final Screen parent;
    private final Mode mode;
    private final AccountOps ops;
    private final ClassicParticles particles = new ClassicParticles();
    private final LegacyTextField username = new LegacyTextField(LegacyTextField.Style.CLASSIC, 0, 114, 400, 45, Face.CLASSIC, 20, "Username");
    private final ClassicButton primary;
    private final ClassicButton microsoft;
    private final ClassicButton back;
    private boolean mouseDown;

    public ClassicAltPromptScreen(final Screen parent, final Mode mode, final AccountOps ops) {
        super(Component.literal(mode.title));
        this.parent = parent;
        this.mode = mode;
        this.ops = ops;
        this.primary = new ClassicButton(mode == Mode.ADD ? "Add Offline" : "Login", 0, 194, 400, 40, MID_GREY);
        this.microsoft = new ClassicButton("Microsoft Login", 0, 244, 400, 40, MID_GREY);
        this.back = new ClassicButton("Back", 0, mode == Mode.ADD ? 294 : 244, 400, 40, MID_GREY);
        this.username.setMaxLength(16);
        this.username.setFocused(true);
        ops.setStatus("Idle...");
    }

    /** The screen paints its whole backdrop itself, so vanilla's panorama and blur stay out of it. */
    @Override
    public void extractBackground(final net.minecraft.client.gui.GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTypingText() {
        return this.username.focused();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private void run() {
        try {
            AccountEntry account = this.ops.addOffline(this.username.text());
            this.username.setText("");
            if (this.mode == Mode.DIRECT) {
                this.ops.use(account, null);
            }
        } catch (IllegalArgumentException failure) {
            this.ops.setStatus("Failed: " + failure.getMessage());
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            int x = (w - 400) / 2;

            c.image(LegacyTexture.CLASSIC_BACKGROUND, 0, 0, w, h, 0xFFFFFFFF);
            c.fill(0, 0, w, h, LegacyCanvas.alpha(0xFF880008, 0.1F));
            c.fill(0, 0, w, h, LegacyCanvas.alpha(0xFF010101, 0.95F));
            this.particles.draw(c, 0, 0);

            c.vanilla(this.mode.title, w / 2.0F, 38, WHITE, false, true);
            c.vanilla(Component.literal(this.ops.status()).withColor(this.statusColor()), w / 2.0F, 58, WHITE, true, true);

            this.username.x = x;
            this.username.draw(c, 1.0F);
            this.primary.x = x;
            this.back.x = x;
            this.primary.enabled = !this.ops.busy();
            this.primary.draw(c, mx, my, this.mouseDown);
            if (this.mode == Mode.ADD) {
                this.microsoft.x = x;
                this.microsoft.enabled = !this.ops.busy();
                this.microsoft.draw(c, mx, my, this.mouseDown);
            }
            this.back.draw(c, mx, my, this.mouseDown);

            String code = this.ops.deviceCode();
            if (!code.isEmpty()) {
                c.vanilla("Code: " + code + " (copied)", w / 2.0F, this.back.y + 60, 0xFFFFFF55, true, true);
            }
        }
    }

    private int statusColor() {
        String status = this.ops.status().toLowerCase(Locale.ROOT);
        if (status.contains("fail") || status.contains("switch accounts") || status.contains("return to the title")) {
            return 0xFFFF5555;
        }
        if (status.startsWith("logging in") || status.startsWith("starting") || status.startsWith("opening") || status.startsWith("finish")) {
            return 0xFF55FFFF;
        }
        if (status.startsWith("logged in") || status.startsWith("added") || status.startsWith("deleted")) {
            return 0xFF55FF55;
        }
        return 0xFFAAAAAA;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        this.mouseDown = true;
        this.username.mouseClicked(mx, my);
        if (this.username.contains(mx, my)) {
            return true;
        }
        if (this.primary.contains(mx, my)) {
            this.run();
        } else if (this.mode == Mode.ADD && this.microsoft.contains(mx, my)) {
            this.ops.microsoftLogin(account -> {
            });
        } else if (this.back.contains(mx, my)) {
            this.onClose();
        } else {
            return super.mouseClicked(event, doubleClick);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        this.username.mouseDragged(LegacyCanvas.toLegacy(event.x()));
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.mouseDown = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.username.keyPressed(event)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            this.run();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        return this.username.charTyped(event) || super.charTyped(event);
    }
}
