package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountEntry;
import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountType;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.classic.ClassicButton;
import com.mentalfrostbyte.jello.gui.classic.ClassicParticles;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Classic's Account Manager, as the old client drew it ({@code ClassicAltScreen}), on Sigma-Modern's accounts.
 *
 * <p>The main menu's backdrop and drifting particles under a dark wash. The current name top left, the title
 * ("Account Manager - N alts") and a coloured status line top centre, a translucent list of 52 px rows in the
 * middle (a pink "Microsoft" or a red "Cracked" under each name, a frame around the picked one), and along the
 * bottom a search box and a toolbar of flat buttons. Reload, Back, Login, Direct Login, Add, Random and Remove
 * keep their old jobs; the old "Edit" (which edited a stored e-mail and password) became "Launcher", which goes
 * back to the launcher's own account. Click a row to pick it, click it again or double-click to log in.</p>
 */
public final class ClassicAltManagerScreen extends Screen implements TextEntryScreen {
    private static final int WHITE = 0xFFFEFEFE;
    private static final int BLACK = 0xFF010101;
    private static final int MID_GREY = 0xFF999999;
    private static final int ROW_HEIGHT = 52;
    private static final String[] WORDS = {
        "Swift", "Brave", "Lucky", "Sneaky", "Frosty", "Silent", "Cosmic", "Rusty", "Pixel", "Blocky", "Mighty", "Shady", "Golden", "Wild"
    };
    private static final String[] NOUNS = {
        "Fox", "Wolf", "Creeper", "Miner", "Knight", "Ghost", "Dragon", "Panda", "Falcon", "Golem", "Ninja", "Otter", "Tiger", "Moth"
    };

    private final Screen parent;
    private final AccountOps ops = new AccountOps(this::refresh);
    private final ClassicParticles particles = new ClassicParticles();
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.CLASSIC);
    private final LegacyTextField search = new LegacyTextField(LegacyTextField.Style.CLASSIC, 0, 0, 140, 32, Face.CLASSIC, 20, "Search...");
    private final ClassicButton reload = new ClassicButton("Reload", 0, 0, 120, 40, BLACK);
    private final ClassicButton back = new ClassicButton("Back", 0, 48, 120, 40, BLACK);
    private final ClassicButton login = new ClassicButton("Login", 135, 0, 200, 40, BLACK);
    private final ClassicButton direct = new ClassicButton("Direct Login", 351, 0, 200, 40, BLACK);
    private final ClassicButton add = new ClassicButton("Add", 567, 0, 200, 40, BLACK);
    private final ClassicButton random = new ClassicButton("Random", 135, 48, 146, 40, BLACK);
    private final ClassicButton remove = new ClassicButton("Remove", 297, 48, 146, 40, BLACK);
    private final ClassicButton launcher = new ClassicButton("Launcher", 459, 48, 146, 40, BLACK);

    private List<AccountEntry> rows = new ArrayList<>();
    private String selectedId;
    private String pressedId;
    private boolean mouseDown;
    private long lastClickMillis;
    private String lastClickId;

    public ClassicAltManagerScreen(final Screen parent) {
        super(Component.literal("Account Manager"));
        this.parent = parent;
        this.ops.setStatus("Idle...");
        this.search.onChange(field -> this.refresh());
        this.refresh();

        int debugRow = Integer.getInteger("sigma.debug.altSelect", -1);
        if (debugRow >= 0 && debugRow < this.rows.size()) {
            this.selectedId = this.rows.get(debugRow).getId();
        }
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
        return this.search.focused();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private void refresh() {
        this.rows = this.ops.list(AccountOps.Sort.DATE_ADDED, this.search.text());
        if (this.selectedId != null && this.ops.find(this.selectedId).isEmpty()) {
            this.selectedId = null;
        }
    }

    private AccountEntry selected() {
        return this.selectedId == null ? null : this.ops.find(this.selectedId).orElse(null);
    }

    // ------------------------------------------------------------------ layout

    private int listX(final int w) {
        return 100;
    }

    private int listW(final int w) {
        return w - 200;
    }

    private int listY() {
        return 69;
    }

    private int listH(final int h) {
        return h - 169;
    }

    private int toolbarX(final int w) {
        return (w - 790) / 2 + 16;
    }

    private int toolbarY(final int h) {
        return h - 94;
    }

    private int contentHeight() {
        return this.rows.size() * ROW_HEIGHT + 8;
    }

    private void layout(final int w, final int h) {
        int gx = this.toolbarX(w);
        int gy = this.toolbarY(h);
        ClassicButton[] buttons = {this.reload, this.back, this.login, this.direct, this.add, this.random, this.remove, this.launcher};
        int[][] origin = {{0, 0}, {0, 48}, {135, 0}, {351, 0}, {567, 0}, {135, 48}, {297, 48}, {459, 48}};
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].x = gx + origin[i][0];
            buttons[i].y = gy + origin[i][1];
        }
        this.search.x = (w - 790) / 2 - 140;
        this.search.y = h - 40;
        boolean has = this.selected() != null && !this.ops.busy();
        this.login.enabled = has;
        this.remove.enabled = has;
        this.launcher.enabled = !this.ops.busy();
        this.random.enabled = !this.ops.busy();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            this.layout(w, h);

            c.image(LegacyTexture.CLASSIC_BACKGROUND, -10, -10, w + 20, h + 20, 0xFFFFFFFF);
            c.fill(0, 0, w, h, LegacyCanvas.alpha(BLACK, 0.23F));
            this.particles.draw(c, 0, 0);

            this.drawList(c, w, h, mx, my);

            c.vanilla(this.minecraft.getUser().getName(), 20, 20, 0xFFDDDDDD, false, false);
            c.vanilla("Account Manager - " + this.ops.manager().accounts().size() + " alts", w / 2.0F, 20, WHITE, false, true);
            c.vanilla(Component.literal(this.ops.status()).withColor(this.statusColor()), w / 2.0F, 40, WHITE, true, true);

            this.search.draw(c, 1.0F);
            for (ClassicButton button : new ClassicButton[] {this.reload, this.back, this.login, this.direct, this.add, this.random, this.remove, this.launcher}) {
                button.draw(c, mx, my, this.mouseDown);
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

    private void drawList(final LegacyCanvas c, final int w, final int h, final double mx, final double my) {
        int x = this.listX(w);
        int y = this.listY();
        int lw = this.listW(w);
        int lh = this.listH(h);
        c.fill(x, y, x + lw, y + lh, LegacyCanvas.alpha(MID_GREY, 0.35F));
        ClassicButton.frame(c, x, y, x + lw, y + lh, 2, LegacyCanvas.alpha(WHITE, 0.14F));

        int contentH = this.contentHeight();
        this.scroll.clamp(contentH, lh);
        c.scissor(x, y, x + lw, y + lh);
        for (int i = 0; i < this.rows.size(); i++) {
            AccountEntry account = this.rows.get(i);
            int rowTop = y + 4 + i * ROW_HEIGHT - this.scroll.offset();
            if (rowTop + ROW_HEIGHT < y || rowTop > y + lh) {
                continue;
            }
            this.drawRow(c, account, x + 4, rowTop, lw - 8, mx, my);
        }
        c.unscissor();
        this.scroll.draw(c, x + lw, y, lh, contentH, lh, mx >= x && mx < x + lw && my >= y && my < y + lh, 1.0F);
    }

    private void drawRow(final LegacyCanvas c, final AccountEntry account, final int x, final int top, final int w, final double mx, final double my) {
        boolean selected = account.getId().equals(this.selectedId);
        boolean hovered = mx >= x && mx < x + w && my >= top && my < top + ROW_HEIGHT;
        boolean pressed = hovered && this.mouseDown && account.getId().equals(this.pressedId);
        if (selected || pressed || hovered) {
            c.fill(x, top, x + w, top + ROW_HEIGHT, LegacyCanvas.alpha(WHITE, 0.05F));
        }
        int frame;
        if (pressed) {
            frame = LegacyCanvas.alpha(MID_GREY, 0.65F);
        } else if (hovered && selected) {
            frame = LegacyCanvas.alpha(MID_GREY, 0.5F);
        } else if (hovered) {
            frame = LegacyCanvas.alpha(BLACK, 0.3F);
        } else if (selected) {
            frame = LegacyCanvas.alpha(MID_GREY, 0.3F);
        } else {
            frame = 0;
        }
        if (frame != 0) {
            ClassicButton.frame(c, x, top, x + w, top + ROW_HEIGHT, 2, frame);
        }

        c.scissor(x, top, x + w, top + ROW_HEIGHT);
        float cx = x + w / 2.0F;
        // The name is centred on its line (a font's height is 18 px at 2x), the tag under it hangs from its.
        c.vanilla(account.getName(), cx, top + 20 - 9, LegacyCanvas.alpha(BLACK, 0.4F), false, true);
        c.vanilla(account.getName(), cx, top + 18 - 9, WHITE, false, true);
        if (account.getType() == AccountType.MICROSOFT) {
            c.vanilla("Microsoft", cx, top + 32, 0xFFFF55FF, true, true);
        } else {
            c.vanilla("Cracked", cx, top + 29, 0xFFFF5555, true, true);
        }
        if (this.ops.isCurrent(account)) {
            c.vanilla("In use", x + w - 12 - c.vanillaWidth("In use"), top + 18 - 9, 0xFF55FF55, true, false);
        }
        c.unscissor();
    }

    // ------------------------------------------------------------------ actions

    private void loginSelected() {
        AccountEntry account = this.selected();
        if (account != null) {
            this.ops.use(account, null);
        }
    }

    private void loginRandom() {
        String name = WORDS[new Random().nextInt(WORDS.length)] + NOUNS[new Random().nextInt(NOUNS.length)] + (10 + new Random().nextInt(90));
        AccountEntry account = this.ops.addOffline(name);
        this.selectedId = account.getId();
        this.ops.setStatus("Logging in with a random name...");
        this.ops.use(account, null);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        this.mouseDown = true;
        this.search.mouseClicked(mx, my);
        if (this.search.contains(mx, my)) {
            return true;
        }

        if (this.reload.contains(mx, my)) {
            this.ops.manager().load();
            this.refresh();
            this.ops.setStatus("Reloaded.");
        } else if (this.back.contains(mx, my)) {
            this.onClose();
        } else if (this.login.contains(mx, my)) {
            this.loginSelected();
        } else if (this.direct.contains(mx, my)) {
            this.minecraft.gui.setScreen(new ClassicAltPromptScreen(this, ClassicAltPromptScreen.Mode.DIRECT, this.ops));
        } else if (this.add.contains(mx, my)) {
            this.minecraft.gui.setScreen(new ClassicAltPromptScreen(this, ClassicAltPromptScreen.Mode.ADD, this.ops));
        } else if (this.random.contains(mx, my)) {
            this.loginRandom();
        } else if (this.remove.contains(mx, my)) {
            AccountEntry account = this.selected();
            if (account != null) {
                this.ops.delete(account);
                this.selectedId = null;
                this.refresh();
            }
        } else if (this.launcher.contains(mx, my)) {
            this.ops.useLauncherIdentity();
        } else {
            return this.clickRow(mx, my, doubleClick) || super.mouseClicked(event, doubleClick);
        }
        return true;
    }

    private boolean clickRow(final double mx, final double my, final boolean doubleClick) {
        int w = this.minecraft.getWindow().getGuiScaledWidth() * this.minecraft.getWindow().getGuiScale();
        int h = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        int x = this.listX(w);
        int y = this.listY();
        int lw = this.listW(w);
        int lh = this.listH(h);
        if (this.scroll.press(mx, my, x + lw, y, lh, this.contentHeight(), lh)) {
            return true;
        }
        if (mx < x || mx >= x + lw || my < y || my >= y + lh) {
            return false;
        }
        int index = (int) ((my - y - 4 + this.scroll.offset()) / ROW_HEIGHT);
        if (index < 0 || index >= this.rows.size()) {
            return false;
        }
        AccountEntry account = this.rows.get(index);
        long now = System.currentTimeMillis();
        boolean again = account.getId().equals(this.selectedId);
        boolean quick = account.getId().equals(this.lastClickId) && now - this.lastClickMillis < 400;
        this.lastClickId = account.getId();
        this.lastClickMillis = now;
        this.selectedId = account.getId();
        this.pressedId = account.getId();
        if (again || doubleClick || quick) {
            this.ops.use(account, null);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        this.search.mouseDragged(LegacyCanvas.toLegacy(event.x()));
        int h = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        this.scroll.drag(LegacyCanvas.toLegacy(event.y()), this.listY(), this.listH(h), this.contentHeight(), this.listH(h));
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.mouseDown = false;
        this.scroll.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        int h = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        this.scroll.wheel(scrollY, this.contentHeight(), this.listH(h));
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.search.keyPressed(event)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && !this.search.focused()) {
            this.loginSelected();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        return this.search.charTyped(event) || super.charTyped(event);
    }
}
