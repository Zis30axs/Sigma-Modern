package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountEntry;
import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountType;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyDialog;
import com.mentalfrostbyte.jello.gui.legacy.LegacyDropdown;
import com.mentalfrostbyte.jello.gui.legacy.LegacyLabelButton;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyBlurredImage;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's Alt Manager, as the old client drew it ({@code AltManagerScreen}), on Sigma-Modern's accounts.
 *
 * <p>A near-white screen over the panorama, blurred to a soft wash of colour that drifts with the pointer. On the
 * left a list of account cards - a floating white card each, the account's face in a round frame, its name in
 * light type, what kind of account it is - that fly in from the left one after another and grow a small arrow
 * on the side when selected. On the right a card with the selected account as a 3D figure that turns to follow
 * the pointer and its name under it (or, with nothing selected, the watercolour welcome picture). Sort, search
 * and Add sit along the top; Add and the right-click menu open Jello's dialogs.</p>
 *
 * <p>Click a card to select it, click the selected card (or double-click) to log in as it, right-click for the
 * menu. Escape goes back. The old "email:password" and "cookie/token" logins are gone with the old account
 * store: Add takes a Microsoft sign-in or an offline name.</p>
 */
public final class JelloAltManagerScreen extends Screen implements TextEntryScreen, ModernBlurredBackdrop {
    private static final String PANORAMA = "/assets/minecraft/textures/gui/sigma/legacy/jello/background/panorama5.png";
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;
    private static final int GREY = 0xFF999999;
    private static final int BLUE = 0xFF3B99FD;
    private static final int TITLE_OFFSET = 30;
    private static final int ROW_HEIGHT = 100;
    private static final int ROW_PITCH = ROW_HEIGHT + TITLE_OFFSET / 2;
    private static final int LIST_TOP = 114;

    private final Screen parent;
    private final AccountOps ops = new AccountOps(this::refresh);
    private final LegacyLabelButton addButton = new LegacyLabelButton("Add +", 0, 43, 70, 30, Face.JELLO_LIGHT, 25, BLUE);
    private final LegacyTextField search = new LegacyTextField(LegacyTextField.Style.JELLO, 0, 44, 150, 32, Face.JELLO_LIGHT, 18, "Search...");
    private final LegacyDropdown sort;
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private final LegacyDialog addDialog;
    private final LegacyDialog deleteDialog;
    private final LegacyDialog optionsDialog;
    private final Map<String, Animation> arrival = new HashMap<>();
    private final Map<String, Animation> selection = new HashMap<>();
    private final Map<String, Float> current = new HashMap<>();
    private final long openedAt = System.currentTimeMillis();

    private List<AccountEntry> rows = new ArrayList<>();
    private AccountOps.Sort sortMode = AccountOps.Sort.DATE_ADDED;
    private String selectedId;
    private String contextId;
    private long lastClickMillis;
    private String lastClickId;
    private boolean animateRows = true;

    private LegacyBlurredImage panorama;
    private float bgX;
    private float bgY;
    private boolean bgPlaced;
    private Model.Simple wideModel;
    private Model.Simple slimModel;

    public JelloAltManagerScreen(final Screen parent) {
        super(Component.literal("Alt Manager"));
        this.parent = parent;
        this.selectedId = this.ops.manager().selectedId();

        List<String> options = new ArrayList<>();
        for (AccountOps.Sort mode : AccountOps.Sort.values()) {
            options.add(mode.label);
        }
        this.sort = new LegacyDropdown(0, 44, 200, 32, options, 0);
        this.sort.onSelect(index -> {
            this.sortMode = AccountOps.Sort.values()[index];
            this.animateRows = false;
            this.refresh();
        });
        this.search.onChange(field -> {
            this.animateRows = false;
            this.refresh();
        });

        this.addDialog = new LegacyDialog(240,
            new LegacyDialog.Row(LegacyDialog.Kind.HEADER, "Add Alt", 50),
            new LegacyDialog.Row(LegacyDialog.Kind.LINE, "Sign in with Microsoft, or", 15),
            new LegacyDialog.Row(LegacyDialog.Kind.LINE, "add an offline name.", 25),
            new LegacyDialog.Row(LegacyDialog.Kind.FIELD, "Username", 50),
            new LegacyDialog.Row(LegacyDialog.Kind.BUTTON, "Add offline", 50),
            new LegacyDialog.Row(LegacyDialog.Kind.BUTTON, "Microsoft login", 50));
        this.addDialog.onButton(row -> {
            if (row == 4) {
                this.addOffline();
            } else if (row == 5) {
                this.ops.microsoftLogin(account -> {
                    this.selectedId = account.getId();
                    this.addDialog.close();
                    this.animateRows = false;
                    this.refresh();
                });
            }
        });

        this.deleteDialog = new LegacyDialog(240,
            new LegacyDialog.Row(LegacyDialog.Kind.HEADER, "Delete?", 50),
            new LegacyDialog.Row(LegacyDialog.Kind.LINE, "Are you sure you want", 15),
            new LegacyDialog.Row(LegacyDialog.Kind.LINE, "to delete this alt?", 40),
            new LegacyDialog.Row(LegacyDialog.Kind.BUTTON, "Delete", 50));
        this.deleteDialog.onButton(row -> {
            this.ops.find(this.contextId).ifPresent(account -> {
                this.ops.delete(account);
                if (account.getId().equals(this.selectedId)) {
                    this.selectedId = null;
                }
            });
            this.contextId = null;
            this.deleteDialog.close();
            this.animateRows = false;
            this.refresh();
        });

        this.optionsDialog = new LegacyDialog(240,
            new LegacyDialog.Row(LegacyDialog.Kind.HEADER, "Account", 50),
            new LegacyDialog.Row(LegacyDialog.Kind.LINE, "", 30),
            new LegacyDialog.Row(LegacyDialog.Kind.BUTTON, "Log in", 50),
            new LegacyDialog.Row(LegacyDialog.Kind.BUTTON, "Delete", 50));
        this.optionsDialog.onButton(row -> {
            this.optionsDialog.close();
            if (row == 2) {
                this.ops.find(this.contextId).ifPresent(account -> this.ops.use(account, null));
            } else {
                this.deleteDialog.open();
            }
        });

        this.refresh();

        // -Dsigma.debug.altSelect=<row> selects that account; -Dsigma.debug.altDialog=add|delete|options opens a dialog.
        int debugRow = Integer.getInteger("sigma.debug.altSelect", -1);
        if (debugRow >= 0 && debugRow < this.rows.size()) {
            this.selectedId = this.rows.get(debugRow).getId();
            this.selection.get(this.selectedId).changeDirection(Animation.Direction.FORWARDS);
            this.selection.get(this.selectedId).setProgress(1.0F);
        }
        String debugDialog = System.getProperty("sigma.debug.altDialog", "");
        switch (debugDialog) {
            case "add" -> this.addDialog.open();
            case "delete" -> this.deleteDialog.open();
            case "options" -> {
                this.optionsDialog.setText(1, this.rows.isEmpty() ? "" : this.rows.get(0).getName());
                this.optionsDialog.open();
            }
            default -> {
            }
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
        return this.search.focused() || this.addDialog.typing();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private void refresh() {
        this.rows = this.ops.list(this.sortMode, this.search.text());
        for (AccountEntry account : this.rows) {
            this.arrival.computeIfAbsent(account.getId(), id -> new Animation(800, 300, this.animateRows ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS));
            if (!this.animateRows) {
                this.arrival.get(account.getId()).changeDirection(Animation.Direction.FORWARDS);
                this.arrival.get(account.getId()).setProgress(1.0F);
            }
            this.selection.computeIfAbsent(account.getId(), id -> new Animation(814, 114, Animation.Direction.BACKWARDS));
        }
        if (this.selectedId != null && this.ops.find(this.selectedId).isEmpty()) {
            this.selectedId = null;
        }
    }

    // ------------------------------------------------------------------ layout

    private int listWidth(final int w) {
        return (int) (w * 0.65F) - 4;
    }

    private int viewX(final int w) {
        return (int) (w * 0.65F);
    }

    private int viewWidth(final int w) {
        return (int) (w * 0.35F) - TITLE_OFFSET;
    }

    private int listHeight(final int h) {
        return h - 119 - TITLE_OFFSET;
    }

    private int contentHeight() {
        return this.rows.isEmpty() ? 0 : this.rows.size() * ROW_PITCH - TITLE_OFFSET / 2 + 5;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        int guiScale = this.minecraft.getWindow().getGuiScale();
        float modelX0 = 0;
        float modelY0 = 0;
        float modelX1 = 0;
        float modelY1 = 0;
        AccountEntry selected = this.selectedId == null ? null : this.ops.find(this.selectedId).orElse(null);
        boolean dialogOpen = this.addDialog.visible() || this.deleteDialog.visible() || this.optionsDialog.visible();

        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int W = c.width();
            int H = c.height();
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            double hx = dialogOpen ? -1000 : mx;
            double hy = dialogOpen ? -1000 : my;

            this.drawBackground(c, mx, my);

            int viewX = this.viewX(W);
            int viewW = this.viewWidth(W);
            int listH = this.listHeight(H);
            c.floatingCard(viewX, LIST_TOP, viewW, listH, WHITE);

            // Title
            int titleColor = LegacyCanvas.alpha(INK, 0.8F);
            c.text(Face.JELLO_LIGHT, 40, "Jello", TITLE_OFFSET, TITLE_OFFSET, titleColor);
            c.text(Face.JELLO_LIGHT, 25, "Alt Manager", TITLE_OFFSET + 87, TITLE_OFFSET + 15, titleColor);

            this.drawRows(c, W, H, hx, hy, selected);

            // Right panel
            if (selected == null) {
                int imgW = viewW - 30;
                int imgH = imgW * 342 / 460;
                c.image(LegacyTexture.ALT_WELCOME, viewX + 5, (H - imgH) / 2 - 60, imgW, imgH, WHITE);
            } else {
                int nameY = LIST_TOP + H / 12 + 280 + H / 12;
                String name = selected.getName();
                float nameW = c.textWidth(Face.JELLO_LIGHT, 36, name);
                c.text(Face.JELLO_LIGHT, 36, name, viewX + (viewW - nameW) / 2.0F, nameY, LegacyCanvas.alpha(INK, 0.7F));
                String kind = selected.getType() == AccountType.MICROSOFT ? "Microsoft account" : "Offline account";
                float kindW = c.textWidth(Face.JELLO_LIGHT, 18, kind);
                c.text(Face.JELLO_LIGHT, 18, kind, viewX + (viewW - kindW) / 2.0F, nameY + 50, LegacyCanvas.alpha(GREY, 1.0F));

                int modelH = 290;
                int modelW = 200;
                modelX0 = viewX + (viewW - modelW) / 2.0F;
                modelY0 = nameY - modelH - 15;
                modelX1 = modelX0 + modelW;
                modelY1 = modelY0 + modelH;
            }

            // Header controls
            int viewLeft = viewX;
            this.sort.x = viewLeft - 220;
            this.search.x = viewLeft;
            this.addButton.x = W - 90;
            this.search.draw(c, 1.0F);
            this.addButton.draw(c, hx, hy, 1.0F, true);
            this.sort.draw(c, hx, hy, 1.0F);

            // Status line
            String status = this.ops.status();
            if (!status.isEmpty()) {
                c.text(Face.JELLO_LIGHT, 14, status, TITLE_OFFSET + 2, 92, LegacyCanvas.alpha(GREY, 1.0F));
            }
        }

        if (selected != null) {
            this.drawModel(graphics, selected, modelX0 / guiScale, modelY0 / guiScale, modelX1 / guiScale, modelY1 / guiScale, guiScale);
        }

        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            this.updateDialogs(c);
            double dx = LegacyCanvas.mouseX();
            double dy = LegacyCanvas.mouseY();
            this.addDialog.draw(c, dx, dy);
            this.deleteDialog.draw(c, dx, dy);
            this.optionsDialog.draw(c, dx, dy);
        }
    }

    private void updateDialogs(final LegacyCanvas c) {
        if (this.addDialog.visible()) {
            String code = this.ops.deviceCode();
            if (!code.isEmpty()) {
                this.addDialog.setText(1, "Enter this code at microsoft.com/link:");
                this.addDialog.setText(2, code);
            } else if (this.ops.signingIn()) {
                this.addDialog.setText(1, "Opening your browser...");
                this.addDialog.setText(2, "");
            } else {
                this.addDialog.setText(1, "Sign in with Microsoft, or");
                this.addDialog.setText(2, "add an offline name.");
            }
            this.addDialog.setButtonEnabled(5, !this.ops.busy());
            this.addDialog.setButtonEnabled(4, !this.ops.busy());
        }
    }

    private void drawBackground(final LegacyCanvas c, final double mx, final double my) {
        int W = c.width();
        int H = c.height();
        float targetX = (float) -mx;
        float targetY = (float) (my / W * -114.0);
        if (!this.bgPlaced) {
            this.bgX = targetX;
            this.bgY = targetY;
            this.bgPlaced = true;
        }
        if (this.panorama == null) {
            this.panorama = LegacyBlurredImage.of(PANORAMA, 0.25F, 30, 1.1F);
        }
        Identifier tex = this.panorama.texture();
        if (tex != null) {
            c.image(tex, this.panorama.width(), this.panorama.height(), this.bgX, this.bgY, W * 2, H + 114, 0xFFFFFFFF);
        }
        this.bgX += (targetX - this.bgX) * 0.5F;
        this.bgY += (targetY - this.bgY) * 0.5F;
        c.fill(0, 0, W, H, LegacyCanvas.alpha(WHITE, 0.95F));
    }

    private void drawRows(final LegacyCanvas c, final int W, final int H, final double mx, final double my, final AccountEntry selected) {
        int listW = this.listWidth(W);
        int listH = this.listHeight(H);
        int contentH = this.contentHeight();
        this.scroll.clamp(contentH, listH);
        int cardX = TITLE_OFFSET;
        int cardW = listW - TITLE_OFFSET * 2 + 4;

        // Rows fly in from the left one after another, but only from the top of an unscrolled list.
        float lead = 1.0F;
        long nowOpen = System.currentTimeMillis() - this.openedAt;
        for (int i = 0; i < this.rows.size(); i++) {
            AccountEntry account = this.rows.get(i);
            Animation arrive = this.arrival.get(account.getId());
            int top = LIST_TOP + i * ROW_PITCH - this.scroll.offset();
            float slide = 0.0F;
            if (this.scroll.offset() == 0 && top <= H && nowOpen > 0) {
                if (lead > 0.2F) {
                    arrive.changeDirection(Animation.Direction.FORWARDS);
                }
                slide = -((1.0F - SmoothInterpolator.interpolate(arrive.calcPercent(), 0.51, 0.82, 0.0, 0.99)) * (cardW + 30));
                lead = arrive.calcPercent();
            } else {
                arrive.changeDirection(Animation.Direction.FORWARDS);
            }
            this.drawRow(c, account, cardX + Math.round(slide), top, cardW, mx, my, account.getId().equals(this.selectedId), H);
        }
        this.scroll.draw(c, listW, LIST_TOP, listH, contentH, listH, mx >= 0 && mx < listW && my >= LIST_TOP && my < LIST_TOP + listH, 1.0F);
    }

    private void drawRow(final LegacyCanvas c, final AccountEntry account, final int x, final int top, final int w, final double mx, final double my, final boolean selected, final int H) {
        // A row scrolled under the list's top edge is cut there, its card shrinking and fading as it goes.
        int visibleTop = Math.max(LIST_TOP, top);
        int visibleHeight = ROW_HEIGHT - (visibleTop - top);
        if (visibleHeight <= 0 || top > H - TITLE_OFFSET) {
            return;
        }
        float fade = Math.min(50, visibleHeight) / 50.0F;
        Animation pick = this.selection.get(account.getId());
        pick.changeDirection(selected ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float pickT = pick.calcPercent();

        c.floatingCard(x, visibleTop, w, Math.max(20, visibleHeight), LegacyCanvas.alpha(WHITE, fade));
        c.scissor(x, visibleTop, x + w + 20, visibleTop + visibleHeight);
        this.drawFace(c, account, x, top, fade);

        int nameColor = LegacyCanvas.fade(INK, 1.0F);
        c.text(Face.JELLO_LIGHT, 25, account.getName(), x + 110, top + 18, nameColor);
        boolean microsoft = account.getType() == AccountType.MICROSOFT;
        c.text(Face.JELLO_LIGHT, 14, microsoft ? "Microsoft account" : "Offline account", x + 110, top + 50, LegacyCanvas.fade(GREY, 1.0F));
        if (microsoft) {
            c.text(Face.JELLO_LIGHT, 14, "Premium account", x + 110, top + 65, LegacyCanvas.fade(INK, 1.0F));
        } else {
            c.text(Face.JELLO_LIGHT, 14, "Used " + account.getUseCount() + (account.getUseCount() == 1 ? " time" : " times"), x + 110, top + 65, LegacyCanvas.fade(GREY, 1.0F));
        }

        // Status marks: a tick on the account in use, a cross that flashes on a failed login, a spinner while logging in.
        boolean active = this.ops.isCurrent(account);
        float fadeIn = this.current.merge(account.getId(), active ? 1.0F : 0.0F, (old, target) -> Math.max(0.0F, Math.min(1.0F, old + (target - old) * 0.33F)));
        boolean failed = this.ops.failedRecently(account);
        long since = System.currentTimeMillis() - this.ops.failedAtMillis();
        float flash = failed ? (since < 600 ? 1.0F : Math.max(0.0F, 1.0F - (since - 600) / 400.0F)) : 0.0F;
        c.image(LegacyTexture.ALT_ERROR, x + w - 45, top + 42, 17, 17, LegacyCanvas.alpha(WHITE, flash * fade));
        c.image(LegacyTexture.ALT_ACTIVE, x + w - 45, top + 45, 17, 13, LegacyCanvas.alpha(WHITE, fadeIn * fade));
        if (account.getId().equals(this.ops.switchingId())) {
            float turn = (System.currentTimeMillis() / 75 % 12) * 30.0F;
            c.push();
            float cx = x + w - 50 + 15;
            float cy = top + 35 + 15;
            c.translate(cx, cy);
            c.graphics().pose().rotate((float) Math.toRadians(turn));
            c.translate(-cx, -cy);
            c.image(LegacyTexture.LOADING_INDICATOR, x + w - 50, top + 35, 30, 30, LegacyCanvas.alpha(INK, fade));
            c.pop();
        }
        c.unscissor();

        if (pickT > 0.0F && visibleHeight > 55) {
            c.image(LegacyTexture.ALT_SELECT, x + w, top + 26 * ROW_HEIGHT / 100.0F, 18 * pickT, 47 * ROW_HEIGHT / 100.0F, WHITE);
        }
    }

    /** The account's face: its 8x8 head (hat layer too) in a round frame, feathered so it doesn't end in a hard square. */
    private void drawFace(final LegacyCanvas c, final AccountEntry account, final int x, final int top, final float fade) {
        PlayerSkin skin = skin(account);
        PlayerFaceExtractor.extractRenderState(c.graphics(), skin, x + 13, top + 13, 75);
        c.innerFeather(x + 13, top + 13, 75, 75, 20, 1.0F);
        c.image(LegacyTexture.ALT_RING, x + 1, top, 100, 100, LegacyCanvas.alpha(WHITE, 1.0F));
    }

    private static PlayerSkin skin(final AccountEntry account) {
        ResolvableProfile profile = ResolvableProfile.createUnresolved(account.getProfileId());
        return net.minecraft.client.Minecraft.getInstance().playerSkinRenderCache().getOrDefault(profile).playerSkin();
    }

    /** The selected account as a figure, turning to face the pointer. Drawn in GUI units: the model isn't part of the pose stack. */
    private void drawModel(final GuiGraphicsExtractor graphics, final AccountEntry account, final float x0, final float y0, final float x1, final float y1, final int guiScale) {
        if (this.wideModel == null) {
            this.wideModel = new Model.Simple(this.minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
            this.slimModel = new Model.Simple(this.minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
        }
        PlayerSkin skin = skin(account);
        Model.Simple model = skin.model() == PlayerModelType.SLIM ? this.slimModel : this.wideModel;
        double mx = LegacyCanvas.mouseX();
        double my = LegacyCanvas.mouseY();
        float cx = (x0 + x1) / 2.0F * guiScale;
        float cy = (y0 + y1) / 2.0F * guiScale;
        int screenW = this.minecraft.getWindow().getGuiScaledWidth() * guiScale;
        int screenH = this.minecraft.getWindow().getGuiScaledHeight() * guiScale;
        float yaw = (float) Math.toDegrees(Math.atan((mx - cx) / (screenW / 2.0))) * 0.6F;
        float pitch = (float) -Math.toDegrees(Math.atan((my - cy) / (screenH / 2.0))) * 0.5F;
        float height = y1 - y0;
        graphics.skin(model, skin.body().texturePath(), 0.97F * height / 2.125F, pitch, yaw, -1.0625F,
            Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
    }

    // ------------------------------------------------------------------ input

    private void addOffline() {
        try {
            AccountEntry account = this.ops.addOffline(this.addDialog.field(3));
            this.selectedId = account.getId();
            this.addDialog.setField(3, "");
            this.addDialog.close();
            this.animateRows = false;
            this.refresh();
        } catch (IllegalArgumentException failure) {
            this.ops.setStatus(failure.getMessage());
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        for (LegacyDialog dialog : new LegacyDialog[] {this.addDialog, this.deleteDialog, this.optionsDialog}) {
            if (dialog.isOpen()) {
                dialog.mouseClicked(mx, my);
                return true;
            }
        }

        if (this.sort.isOpen() || this.sort.contains(mx, my)) {
            this.sort.mouseClicked(mx, my);
            return true;
        }
        this.search.mouseClicked(mx, my);
        if (this.search.contains(mx, my)) {
            return true;
        }
        if (event.button() == 0 && this.addButton.contains(mx, my)) {
            this.ops.setStatus("");
            this.addDialog.open();
            this.addDialog.focusFirstField();
            return true;
        }

        int W = this.minecraft.getWindow().getGuiScaledWidth() * this.minecraft.getWindow().getGuiScale();
        int H = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        int listW = this.listWidth(W);
        int listH = this.listHeight(H);
        if (event.button() == 0 && this.scroll.press(mx, my, listW, LIST_TOP, listH, this.contentHeight(), listH)) {
            return true;
        }
        int cardX = TITLE_OFFSET;
        int cardW = listW - TITLE_OFFSET * 2 + 4;
        for (int i = 0; i < this.rows.size(); i++) {
            int top = LIST_TOP + i * ROW_PITCH - this.scroll.offset();
            int visibleTop = Math.max(LIST_TOP, top);
            int visibleHeight = ROW_HEIGHT - (visibleTop - top);
            if (mx >= cardX && mx < cardX + cardW && my >= visibleTop && my < visibleTop + visibleHeight && my < LIST_TOP + listH + TITLE_OFFSET) {
                AccountEntry account = this.rows.get(i);
                if (event.button() != 0) {
                    this.contextId = account.getId();
                    this.optionsDialog.setText(1, account.getName());
                    this.optionsDialog.open();
                    return true;
                }
                boolean again = account.getId().equals(this.selectedId);
                long now = System.currentTimeMillis();
                boolean quick = account.getId().equals(this.lastClickId) && now - this.lastClickMillis < 400;
                this.lastClickId = account.getId();
                this.lastClickMillis = now;
                this.selectedId = account.getId();
                if (again || doubleClick || quick) {
                    this.ops.use(account, null);
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        this.addDialog.mouseDragged(mx);
        this.search.mouseDragged(mx);
        int H = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        this.scroll.drag(my, LIST_TOP, this.listHeight(H), this.contentHeight(), this.listHeight(H));
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.scroll.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        int H = this.minecraft.getWindow().getGuiScaledHeight() * this.minecraft.getWindow().getGuiScale();
        this.scroll.wheel(scrollY, this.contentHeight(), this.listHeight(H));
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        for (LegacyDialog dialog : new LegacyDialog[] {this.addDialog, this.deleteDialog, this.optionsDialog}) {
            if (dialog.isOpen()) {
                return dialog.keyPressed(event);
            }
        }
        if (this.search.keyPressed(event)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (this.search.focused()) {
                this.search.setFocused(false);
                return true;
            }
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        for (LegacyDialog dialog : new LegacyDialog[] {this.addDialog, this.deleteDialog, this.optionsDialog}) {
            if (dialog.isOpen()) {
                return dialog.charTyped(event);
            }
        }
        return this.search.charTyped(event) || super.charTyped(event);
    }
}
