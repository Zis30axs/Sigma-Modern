package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.Easing;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's Keybind Manager: a keyboard on screen. Pressing a cap - on screen, or the real key - opens a card under it
 * listing what answers to that key and lets you remove one or add another; a cap with something on it is set in a
 * heavier weight. Adding opens a list of modules to pick from, with a search field.
 *
 * <p>It works on the modules' own {@link Keybind}: the same key the ClickGUI's bind button sets, so either place shows
 * and changes the same thing. A middle or side mouse button is a key here too, as it was in the old client.</p>
 */
public final class JelloKeybindScreen extends Screen implements ModernBlurredBackdrop, TextEntryScreen {
    private static final int FRAME = 20;
    private static final int CARD_W = 250;
    private static final int CARD_H = 330;
    private static final int ENTRY_H = 55;
    private static final int PICK_W = 500;
    private static final int PICK_H = 600;
    private static final int PICK_ROW = 40;
    private static final int KEY_FACE = 0xFFF0F0F0;
    private static final int KEY_DEPTH = 0xFFD0D0D0;
    private static final int KEY_DEPTH_PRESSED = 0xFFDEDEDE;
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;
    private static final int CARD = 0xFFF4F4F4;

    private final Animation appear = new Animation(200, 200);
    private final Map<JelloKeys, Float> pressed = new java.util.EnumMap<>(JelloKeys.class);
    private final Animation cardAnimation = new Animation(250, 250);
    private final LegacyScroll entriesScroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private final LegacyScroll pickScroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private final LegacyTextField search = new LegacyTextField(
        LegacyTextField.Style.JELLO, 0, 0, PICK_W - 60, 60, Face.JELLO_LIGHT, 25, "Search...");
    private final Map<Module, Float> removing = new IdentityHashMap<>();
    private final Map<Module, Float> hover = new IdentityHashMap<>();
    private long lastFrame;

    private InputConstants.@Nullable Key openKey;
    private boolean picking;
    private boolean closingCard;
    private @Nullable JelloKeys held;
    private float scale = 1.0F;
    private int keyboardX;
    private int keyboardY;
    private int cardX;
    private int cardY;
    private boolean cardFlipped;
    private int pickX;
    private int pickY;

    public JelloKeybindScreen() {
        super(Component.literal("Keybind Manager"));
        this.cardAnimation.setProgress(0.0F);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** What was bound or unbound here is kept, as closing the ClickGUI keeps what was changed there. */
    @Override
    public void removed() {
        Client.getInstance().saveConfig();
        super.removed();
    }

    @Override
    public boolean isTypingText() {
        return this.picking && this.search.focused();
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        GuiVisuals.blurBackground(graphics);
    }

    // ------------------------------------------------------------------ what is bound

    private static List<Module> boundTo(final InputConstants.Key key) {
        List<Module> bound = new ArrayList<>();
        for (Module module : Client.getInstance().getModuleManager().all()) {
            Keybind keybind = module.getKeybind();
            if (keybind.isBound() && keybind.key().equals(key)) {
                bound.add(module);
            }
        }

        return bound;
    }

    private static boolean isBound(final InputConstants.Key key) {
        for (Module module : Client.getInstance().getModuleManager().all()) {
            if (module.getKeybind().isBound() && module.getKeybind().key().equals(key)) {
                return true;
            }
        }

        return false;
    }

    private static String nameOf(final InputConstants.Key key) {
        JelloKeys cap = JelloKeys.of(key);
        return cap != null ? cap.label : key.getDisplayName().getString();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            long now = System.nanoTime();
            float dt = this.lastFrame == 0L ? 0.0F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
            this.lastFrame = now;
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            float appear = this.appear.calcPercent();
            float pop = Easing.easeOutBack(appear, 0.0F, 1.0F, 1.0F);

            c.fill(0, 0, w, h, LegacyCanvas.alpha(INK, 0.25F * appear));

            this.scale = Math.min(1.0F, Math.min((w - 40) / (float) (JelloKeys.WIDTH + 2 * FRAME), (h - 140) / (float) (JelloKeys.HEIGHT + 2 * FRAME + 5)));
            this.keyboardX = Math.round((w - JelloKeys.WIDTH * this.scale) / 2.0F);
            this.keyboardY = Math.round((h - JelloKeys.HEIGHT * this.scale) / 2.0F);
            float zoom = this.scale * (0.8F + pop * 0.2F);

            c.push();
            try {
                c.scaleAbout(0.8F + pop * 0.2F, 0.8F + pop * 0.2F, w / 2.0F, h / 2.0F);
                c.text(Face.JELLO_MEDIUM, 40.0F, "Keybind Manager", this.keyboardX, this.keyboardY - 90 * this.scale, LegacyCanvas.alpha(WHITE, appear));
                this.keyboard(c, mx, my, appear, dt);
            } finally {
                c.pop();
            }

            if (this.openKey != null) {
                this.card(c, mx, my, dt);
            }
            if (this.picking) {
                this.picker(c, mx, my, dt);
            }
        }
    }

    private void keyboard(final LegacyCanvas c, final double mx, final double my, final float alpha, final float dt) {
        int frameX = this.keyboardX - Math.round(FRAME * this.scale);
        int frameY = this.keyboardY - Math.round(FRAME * this.scale);
        int frameW = Math.round((JelloKeys.WIDTH + 2 * FRAME) * this.scale);
        int frameH = Math.round((JelloKeys.HEIGHT + 5 + 2 * FRAME) * this.scale);
        c.outerGlow(frameX + 7, frameY + 7, frameW - 14, frameH - 14, 20.0F, 0.5F * alpha);
        c.rounded(frameX, frameY, frameW, frameH, Math.round(14 * this.scale), LegacyCanvas.alpha(WHITE, alpha));

        boolean covered = this.picking || this.openKey != null && this.cardVisible(mx, my);
        c.push();
        try {
            c.translate(this.keyboardX, this.keyboardY);
            c.scale(this.scale, this.scale);
            double lx = (mx - this.keyboardX) / this.scale;
            double ly = (my - this.keyboardY) / this.scale;
            for (JelloKeys cap : JelloKeys.values()) {
                boolean over = !covered && lx >= cap.x && lx < cap.x + cap.w && ly >= cap.y() && ly < cap.y() + JelloKeys.CAP_HEIGHT;
                float target = over || cap == this.held ? 1.0F : 0.0F;
                float press = this.pressed.getOrDefault(cap, 0.0F);
                press += (target - press) * Math.min(1.0F, dt * 14.0F);
                this.pressed.put(cap, press);
                this.cap(c, cap, press, isBound(cap.key()), alpha);
            }
        } finally {
            c.pop();
        }
    }

    private void cap(final LegacyCanvas c, final JelloKeys cap, final float press, final boolean bound, final float alpha) {
        int x = cap.x;
        int y = cap.y();
        int w = cap.w;
        int h = JelloKeys.CAP_HEIGHT;
        c.rounded(x, y + 5, w, h, 8, LegacyCanvas.fade(LegacyCanvas.shiftTowardsOther(KEY_DEPTH_PRESSED, KEY_DEPTH, press), alpha));
        c.rounded(x, y + Math.round(3.0F * press), w, h, 8, LegacyCanvas.fade(KEY_FACE, alpha));
        int ink = LegacyCanvas.alpha(INK, (0.3F + (bound ? 0.2F : 0.0F)) * alpha);
        int drop = Math.round(3.0F * press);
        switch (cap) {
            case CAPSLOCK -> {
                c.disc(x + 14, y + 11 + drop, 4.0F, LegacyCanvas.alpha(0xFF6DCE27, press * alpha));
                this.label(c, cap, bound, alpha, press);
            }
            case RETURN -> {
                int ax = x + 50;
                int ay = y + 33 + drop;
                this.arrow(c, ax, ay, ink);
                c.fill(ax + 25, ay - 8, ax + 27, ay - 1, ink);
            }
            case BACKSPACE -> this.arrow(c, x + 43, y + 33 + drop, ink);
            case LEFT_SUPER, RIGHT_SUPER -> c.disc(x + 32, y + 32 + drop, 14.0F, ink);
            case MENU -> {
                int mx = x + 25;
                int my = y + 25 + drop;
                for (int i = 0; i < 4; i++) {
                    c.fill(mx, my + i * 4, mx + 14, my + i * 4 + 3, ink);
                }
            }
            case SPACE -> { }
            default -> this.label(c, cap, bound, alpha, press);
        }
    }

    /** A left-pointing arrow: a head and a shaft, as Return and Back are drawn on their caps. */
    private void arrow(final LegacyCanvas c, final int x, final int y, final int ink) {
        c.pointerLeft(x, y, 6.0F, ink);
        c.fill(x + 6, y - 1, x + 27, y + 1, ink);
    }

    private void label(final LegacyCanvas c, final JelloKeys cap, final boolean bound, final float alpha, final float press) {
        Face face = bound ? Face.JELLO_MEDIUM : Face.JELLO_LIGHT;
        float width = c.textWidth(face, 20.0F, cap.label);
        c.text(face, 20.0F, cap.label, cap.x + (cap.w - width) / 2.0F, cap.y() + 19 + 3.0F * press,
            LegacyCanvas.alpha(INK, (0.4F + (bound ? 0.2F : 0.0F)) * alpha));
    }

    // ---- the card under a key

    private boolean cardVisible(final double mx, final double my) {
        return this.openKey != null && mx >= this.cardX && mx < this.cardX + CARD_W && my >= this.cardY && my < this.cardY + CARD_H;
    }

    private void card(final LegacyCanvas c, final double mx, final double my, final float dt) {
        InputConstants.Key key = this.openKey;
        this.cardAnimation.changeDirection(this.closingCard ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS);
        float p = this.cardAnimation.calcPercent();
        if (this.closingCard && p <= 0.0F) {
            this.openKey = null;
            this.closingCard = false;
            return;
        }

        float pop = Easing.easeOutBack(p, 0.0F, 1.0F, 1.0F);
        float fade = Easing.easeOutQuad(p, 0.0F, 1.0F, 1.0F);
        JelloKeys cap = JelloKeys.of(key);
        int anchorX;
        int anchorY;
        if (cap != null) {
            anchorX = this.keyboardX + Math.round((cap.x + cap.w / 2.0F) * this.scale);
            anchorY = this.keyboardY + Math.round((cap.y() + JelloKeys.CAP_HEIGHT) * this.scale);
        } else {
            anchorX = this.keyboardX + Math.round(JelloKeys.WIDTH * this.scale / 2.0F);
            anchorY = this.keyboardY + Math.round(20 * this.scale);
        }
        int screenW = c.width();
        int screenH = c.height();
        this.cardX = Math.max(10, Math.min(screenW - CARD_W - 10, anchorX - CARD_W / 2));
        this.cardFlipped = anchorY + 10 + CARD_H > screenH;
        this.cardY = this.cardFlipped
            ? anchorY - Math.round((cap == null ? 20 : JelloKeys.CAP_HEIGHT) * this.scale) - 10 - CARD_H
            : anchorY + 10;

        c.push();
        try {
            c.scaleAbout(0.8F + pop * 0.2F, 0.8F + pop * 0.2F, this.cardX + CARD_W / 2.0F, this.cardFlipped ? this.cardY + CARD_H : this.cardY);
            c.outerGlow(this.cardX + 5, this.cardY + 5, CARD_W - 10, CARD_H - 10, 35.0F, 0.6F * fade);
            c.rounded(this.cardX, this.cardY, CARD_W, CARD_H, 10, LegacyCanvas.alpha(CARD, fade));

            // The little point towards the key.
            int pointX = Math.max(this.cardX + 24, Math.min(this.cardX + CARD_W - 24, anchorX));
            int colour = LegacyCanvas.alpha(CARD, fade);
            for (int row = 0; row < 9; row++) {
                int y = this.cardFlipped ? this.cardY + CARD_H + 8 - row - 1 : this.cardY - 8 + row;
                c.fill(pointX - row, y, pointX + row + 1, y + 1, colour);
            }

            c.text(Face.JELLO_LIGHT, 25.0F, nameOf(key) + " Key", this.cardX + 25, this.cardY + 20, LegacyCanvas.alpha(INK, 0.8F * fade));
            c.fill(this.cardX + 25, this.cardY + 68, this.cardX + CARD_W - 25, this.cardY + 69, LegacyCanvas.alpha(INK, 0.05F * fade));

            List<Module> bound = boundTo(key);
            int top = this.cardY + 75;
            int bottom = this.cardY + CARD_H - 70;
            this.entriesScroll.clamp(bound.size() * ENTRY_H, bottom - top);
            c.scissor(this.cardX, top, this.cardX + CARD_W, bottom);
            try {
                int y = top - this.entriesScroll.offset();
                for (Module module : new ArrayList<>(bound)) {
                    float shrink = this.removing.getOrDefault(module, 0.0F);
                    float rowH = ENTRY_H * (1.0F - shrink);
                    if (this.removing.containsKey(module)) {
                        shrink = Math.min(1.0F, shrink + dt / 0.18F);
                        this.removing.put(module, shrink);
                        if (shrink >= 1.0F) {
                            this.removing.remove(module);
                            module.setKeybind(Keybind.UNBOUND);
                            continue;
                        }
                    }
                    this.entry(c, module, y, Math.max(1, Math.round(rowH)), mx, my, fade);
                    y += Math.round(rowH);
                }
            } finally {
                c.unscissor();
            }

            String add = "Add";
            float addW = c.textWidth(Face.JELLO_LIGHT, 25.0F, add);
            boolean addHover = mx >= this.cardX + CARD_W - 70 && mx < this.cardX + CARD_W - 70 + addW && my >= this.cardY + CARD_H - 70 && my < this.cardY + CARD_H;
            c.text(Face.JELLO_LIGHT, 25.0F, add, this.cardX + CARD_W - 70, this.cardY + CARD_H - 70 + 22,
                LegacyCanvas.alpha(INK, (addHover ? 0.8F : 0.5F) * fade));
        } finally {
            c.pop();
        }
    }

    private void entry(final LegacyCanvas c, final Module module, final int y, final int height, final double mx, final double my, final float fade) {
        c.scissor(this.cardX, y, this.cardX + CARD_W, y + height);
        try {
            float centre = y + height / 2.0F;
            c.text(Face.JELLO_MEDIUM, 20.0F, module.getName(), this.cardX + 25, centre - 17.5F, LegacyCanvas.alpha(INK, 0.6F * fade));
            c.text(Face.JELLO_LIGHT, 12.0F, module.getCategory().getDisplayName(), this.cardX + 25, centre + 7.5F, LegacyCanvas.alpha(INK, 0.6F * fade));
            int bx = this.cardX + 200;
            int by = Math.round(centre - 7.5F);
            boolean over = mx >= bx && mx < bx + 20 && my >= by - 5 && my < by + 25;
            c.image(LegacyTexture.TRASHCAN, bx + 2, by - 3, 16, 19, LegacyCanvas.alpha(INK, (over ? 0.6F : 0.3F) * fade));
        } finally {
            c.unscissor();
        }
    }

    // ---- the list of modules to add

    private List<Module> pickable() {
        String query = this.search.text().strip().toLowerCase(Locale.ROOT);
        List<Module> starts = new ArrayList<>();
        List<Module> contains = new ArrayList<>();
        for (Module module : Client.getInstance().getModuleManager().all()) {
            String name = module.getName().toLowerCase(Locale.ROOT);
            if (query.isEmpty() || name.startsWith(query)) {
                starts.add(module);
            } else if (name.contains(query)) {
                contains.add(module);
            }
        }

        starts.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        contains.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        starts.addAll(contains);
        return starts;
    }

    private void picker(final LegacyCanvas c, final double mx, final double my, final float dt) {
        int w = c.width();
        int h = c.height();
        float progress = this.pickAnimation.calcPercent();
        this.pickAnimation.changeDirection(this.pickClosing ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS);
        if (this.pickClosing && progress <= 0.0F) {
            this.picking = false;
            this.pickClosing = false;
            this.search.setFocused(false);
            return;
        }

        float scale = this.pickClosing ? Easing.easeOutQuad(progress, 0.0F, 1.0F, 1.0F) : Easing.easeOutBack(progress, 0.0F, 1.0F, 1.0F);
        c.fill(0, 0, w, h, LegacyCanvas.alpha(INK, 0.3F * progress));
        this.pickX = (w - PICK_W) / 2;
        this.pickY = Math.max(10, (h - PICK_H) / 2);
        int listH = Math.min(PICK_H - 60 - 120, h - 20 - 150);
        c.push();
        try {
            c.scaleAbout(0.8F + scale * 0.2F, 0.8F + scale * 0.2F, w / 2.0F, h / 2.0F);
            c.rounded(this.pickX, this.pickY, PICK_W, Math.min(PICK_H, h - 20), 10, LegacyCanvas.alpha(WHITE, progress));
            c.text(Face.JELLO_LIGHT, 36.0F, "Select mod to bind", this.pickX + 30, this.pickY + 30, LegacyCanvas.alpha(INK, 0.7F * progress));
            this.search.x = this.pickX + 30;
            this.search.y = this.pickY + 80;
            this.search.draw(c, progress);

            List<Module> modules = this.pickable();
            int top = this.pickY + 150;
            this.pickScroll.clamp(modules.size() * PICK_ROW, listH);
            c.scissor(this.pickX + 30, top, this.pickX + PICK_W - 30, top + listH);
            try {
                int y = top - this.pickScroll.offset();
                for (Module module : modules) {
                    if (y + PICK_ROW >= top && y < top + listH) {
                        boolean over = mx >= this.pickX + 30 && mx < this.pickX + PICK_W - 30 && my >= Math.max(y, top) && my < Math.min(y + PICK_ROW, top + listH);
                        float hv = this.hover.getOrDefault(module, 0.0F);
                        hv += ((over ? 1.0F : 0.0F) - hv) * Math.min(1.0F, dt * 14.0F);
                        this.hover.put(module, hv);
                        c.fill(this.pickX + 30, y, this.pickX + PICK_W - 30, y + PICK_ROW, LegacyCanvas.alpha(KEY_FACE, hv * progress));
                        c.text(Face.JELLO_LIGHT, 20.0F, module.getName(), this.pickX + 40, y + PICK_ROW / 2.0F - c.textHeight(Face.JELLO_LIGHT, 20.0F) / 2.0F,
                            LegacyCanvas.alpha(INK, (0.7F + 0.3F * hv) * progress));
                        String category = module.getCategory().getDisplayName();
                        c.text(Face.JELLO_LIGHT, 14.0F, category, this.pickX + PICK_W - 40 - c.textWidth(Face.JELLO_LIGHT, 14.0F, category),
                            y + PICK_ROW / 2.0F - c.textHeight(Face.JELLO_LIGHT, 14.0F) / 2.0F, LegacyCanvas.alpha(INK, 0.35F * progress));
                    }
                    y += PICK_ROW;
                }
            } finally {
                c.unscissor();
            }
        } finally {
            c.pop();
        }
    }

    private final Animation pickAnimation = new Animation(200, 120);
    private boolean pickClosing;

    // ------------------------------------------------------------------ input

    private void openCard(final InputConstants.Key key) {
        if (this.openKey != null && this.openKey.equals(key) && !this.closingCard) {
            this.closingCard = true;
            return;
        }

        this.openKey = key;
        this.closingCard = false;
        this.entriesScroll.setOffset(0);
        this.cardAnimation.setProgress(0.0F);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        int button = event.button();
        if (this.picking) {
            this.pickClicked(mx, my, button);
            return true;
        }

        if (this.openKey != null && !this.closingCard && this.cardVisible(mx, my)) {
            this.cardClicked(mx, my, button);
            return true;
        }

        if (button > 1) {
            // A mouse button is a key here too.
            this.openCard(InputConstants.Type.MOUSE.getOrCreate(button));
            return true;
        }

        double lx = (mx - this.keyboardX) / this.scale;
        double ly = (my - this.keyboardY) / this.scale;
        for (JelloKeys cap : JelloKeys.values()) {
            if (lx >= cap.x && lx < cap.x + cap.w && ly >= cap.y() && ly < cap.y() + JelloKeys.CAP_HEIGHT) {
                this.openCard(cap.key());
                return true;
            }
        }

        if (this.openKey != null) {
            this.closingCard = true;
        }

        return true;
    }

    private void cardClicked(final double mx, final double my, final int button) {
        if (button != 0) {
            return;
        }

        InputConstants.Key key = this.openKey;
        int top = this.cardY + 75;
        int bottom = this.cardY + CARD_H - 70;
        String add = "Add";
        float addW = LegacyFonts.width(Face.JELLO_LIGHT, add, 25.0F);
        if (mx >= this.cardX + CARD_W - 70 && mx < this.cardX + CARD_W - 70 + addW && my >= this.cardY + CARD_H - 70) {
            this.picking = true;
            this.pickClosing = false;
            this.pickAnimation.setProgress(0.0F);
            this.search.setText("");
            this.search.setFocused(true);
            this.pickScroll.setOffset(0);
            return;
        }

        if (my >= top && my < bottom) {
            int y = top - this.entriesScroll.offset();
            for (Module module : boundTo(key)) {
                if (!this.removing.containsKey(module) && my >= y && my < y + ENTRY_H && mx >= this.cardX + 195 && mx < this.cardX + 225) {
                    this.removing.put(module, 0.0F);
                    return;
                }
                y += ENTRY_H;
            }
        }
    }

    private void pickClicked(final double mx, final double my, final int button) {
        if (this.pickClosing) {
            return;
        }

        if (mx < this.pickX || mx >= this.pickX + PICK_W || my < this.pickY || my >= this.pickY + PICK_H) {
            this.pickClosing = true;
            return;
        }

        if (this.search.mouseClicked(mx, my)) {
            return;
        }

        int top = this.pickY + 150;
        int listH = Math.min(PICK_H - 60 - 120, this.height * this.minecraft.getWindow().getGuiScale() - 20 - 150);
        if (button == 0 && my >= top && my < top + listH && mx >= this.pickX + 30 && mx < this.pickX + PICK_W - 30) {
            int index = (int) ((my - top + this.pickScroll.offset()) / PICK_ROW);
            List<Module> modules = this.pickable();
            if (index >= 0 && index < modules.size() && this.openKey != null) {
                modules.get(index).setKeybind(Keybind.of(this.openKey));
                this.pickClosing = true;
            }
        }
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (this.picking) {
            this.search.mouseDragged(LegacyCanvas.toLegacy(event.x()));
        }

        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.held = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        double mx = LegacyCanvas.toLegacy(x);
        double my = LegacyCanvas.toLegacy(y);
        if (this.picking) {
            int listH = Math.min(PICK_H - 60 - 120, this.height * this.minecraft.getWindow().getGuiScale() - 20 - 150);
            this.pickScroll.wheel(scrollY, this.pickable().size() * PICK_ROW, listH);
        } else if (this.openKey != null && this.cardVisible(mx, my)) {
            this.entriesScroll.wheel(scrollY, boundTo(this.openKey).size() * ENTRY_H, CARD_H - 145);
        }

        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.picking) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                this.pickClosing = true;
            } else if (this.search.focused()) {
                this.search.keyPressed(event);
            }
            return true;
        }

        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (this.openKey != null && !this.closingCard) {
                this.closingCard = true;
            } else {
                this.onClose();
            }
            return true;
        }

        // Pressing the real key picks its cap, as touching it on screen does.
        InputConstants.Key key = InputConstants.getKey(event);
        this.held = JelloKeys.of(key);
        this.openCard(key);
        return true;
    }

    @Override
    public boolean keyReleased(final KeyEvent event) {
        this.held = null;
        return true;
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        return this.picking && this.search.charTyped(event);
    }
}
