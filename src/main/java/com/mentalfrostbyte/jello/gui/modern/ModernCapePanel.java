package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.cape.CapeLibrary;
import com.mentalfrostbyte.jello.module.impl.render.Cape;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Util;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Cape module's wardrobe: a glass window beside the ClickGUI card while the Cape page is open, with your
 * player turning on a stand - drag it to look around - and every cape below it to try on.
 *
 * <p>The preview always wears the cape being looked at, whether or not the module is on; picking one switches
 * the module on, since picking a cape and then not wearing it is never what was meant. Off the main menu there
 * is no player to dress, so the preview shows the cape flat instead.</p>
 *
 * <p>The host screen moves its card left by {@link #reserve()} and hands the window its position every frame
 * (the same position for drawing and for input), and routes mouse events to it first.</p>
 */
final class ModernCapePanel {
    static final int GAP = 12;
    private static final int PAD = 12, COLS = 4, CELL_GAP = 6, BUTTON_H = 22;
    private static final float SPIN_DEG_PER_S = 16F, IDLE_BEFORE_SPIN_S = 2.5F;

    private float reserve;
    private int x, y, w, h;
    private float yaw = 160F, pitch = -6F;
    private boolean dragging;
    private double lastX, lastY;
    private float idle = IDLE_BEFORE_SPIN_S;
    private float scroll;
    private int scrollTarget;
    private String hoveredName;
    private final java.util.Map<Object, Float> anim = new java.util.HashMap<>();
    private float dt;

    private record Box(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return ModernStyle.inside(mx, my, this.x, this.y, this.w, this.h);
        }
    }

    static int width(int screenW) {
        return Math.max(190, Math.min(250, Math.round(screenW * 0.27F)));
    }

    // --- geometry -----------------------------------------------------------------------------------

    /** Eases the window in or out; call once a frame, before the host lays out its card. */
    void update(boolean shown, int screenW, float dt) {
        this.dt = dt;
        float target = shown ? width(screenW) + GAP : 0F;
        this.reserve = ModernStyle.smooth(this.reserve, target, dt, 12F);
        if (Math.abs(this.reserve - target) < 0.5F) this.reserve = target;
        if (!shown) this.dragging = false;
    }

    /** Pixels the window takes from the right of the card's area right now. */
    float reserve() {
        return this.reserve;
    }

    boolean visible() {
        return this.reserve > 1F;
    }

    /** How far in the window has eased, 0..1. */
    float openness() {
        return this.w <= 0 ? 0F : Math.min(1F, this.reserve / (this.w + GAP));
    }

    /** Input only reaches the window once it is mostly in place. */
    private boolean interactive() {
        return openness() > 0.6F;
    }

    void place(int x, int y, int h, int screenW) {
        this.x = x;
        this.y = y;
        this.w = width(screenW);
        this.h = Math.max(220, h);
    }

    boolean contains(double mx, double my) {
        return visible() && ModernStyle.inside(mx, my, this.x, this.y, this.w, this.h);
    }

    private Box preview() {
        int top = this.y + 34;
        int ph = Math.max(110, Math.min(220, Math.round(this.h * 0.44F)));
        return new Box(this.x + PAD, top, this.w - PAD * 2, ph);
    }

    private Box buttons() {
        return new Box(this.x + PAD, this.y + this.h - PAD - BUTTON_H, this.w - PAD * 2, BUTTON_H);
    }

    private Box folderButton() {
        Box b = buttons();
        int half = (b.w() - 6) / 2;
        return new Box(b.x(), b.y(), half, b.h());
    }

    private Box reloadButton() {
        Box b = buttons();
        int half = (b.w() - 6) / 2;
        return new Box(b.x() + half + 6, b.y(), b.w() - half - 6, b.h());
    }

    private Box grid() {
        Box p = preview();
        int top = p.y() + p.h() + 26;
        int bottom = buttons().y() - 20;
        return new Box(this.x + PAD, top, this.w - PAD * 2, Math.max(0, bottom - top));
    }

    private int cellW() {
        return (grid().w() - CELL_GAP * (COLS - 1)) / COLS;
    }

    private int cellH() {
        return Math.round(cellW() * 1.3F);
    }

    private Box cell(int index) {
        Box grid = grid();
        int cw = cellW(), ch = cellH();
        int col = index % COLS, row = index / COLS;
        return new Box(grid.x() + col * (cw + CELL_GAP), grid.y() + row * (ch + CELL_GAP) - Math.round(this.scroll), cw, ch);
    }

    private int maxScroll(int count) {
        int rows = (count + COLS - 1) / COLS;
        int content = rows * (cellH() + CELL_GAP) - CELL_GAP;
        return Math.max(0, content - grid().h());
    }

    // --- rendering ------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor g, int mx, int my, Cape cape) {
        if (!interactive()) {
            mx = -10000;
            my = -10000;
        }
        List<CapeLibrary.Entry> capes = CapeLibrary.all();
        CapeLibrary.Entry selected = cape.selected();
        this.scrollTarget = Math.max(0, Math.min(maxScroll(capes.size()), this.scrollTarget));
        this.scroll = ModernStyle.smooth(this.scroll, this.scrollTarget, this.dt, 16F);

        ModernStyle.glassCard(g, this.x, this.y, this.w, this.h, 12);
        this.hoveredName = null;
        drawGrid(g, capes, selected, mx, my);
        drawHeader(g, selected);
        drawPreview(g, selected, mx, my);
        drawFooter(g, mx, my);
    }

    private void drawHeader(GuiGraphicsExtractor g, CapeLibrary.Entry selected) {
        ModernStyle.fill(g, this.x + PAD, this.y + 12, this.x + PAD + 3, this.y + 24, ModernStyle.GLOW);
        String title = ModernStyle.spaced(ModernText.tr("cape.title"));
        ModernTypography.draw(g, title, this.x + PAD + 11, this.y + 14, ModernStyle.INK_MUTED, false);
        String name = this.hoveredName != null ? this.hoveredName : selected == null ? "" : nameOf(selected);
        int budget = this.w - PAD * 2 - 22 - ModernTypography.width(title);
        String shown = ModernTypography.fit(name, Math.max(20, budget));
        ModernTypography.draw(g, shown, this.x + this.w - PAD - ModernTypography.width(shown), this.y + 14,
            this.hoveredName != null ? ModernStyle.INK : ModernStyle.BLUE, false);
    }

    private void drawPreview(GuiGraphicsExtractor g, CapeLibrary.Entry selected, int mx, int my) {
        Box p = preview();
        boolean over = p.contains(mx, my);
        if (over || this.dragging) g.requestCursor(CursorTypes.RESIZE_ALL);
        // A soft stand: a faint well with a lit floor for the player to stand on.
        ModernStyle.rounded(g, p.x(), p.y(), p.w(), p.h(), 10, 0x1A243746);
        ModernStyle.roundedGradient(g, p.x(), p.y(), p.w(), p.h(), 10, p.y(), p.y() + p.h(), 0x00FFFFFF, 0x24BFE8FF);
        int floorW = Math.round(p.w() * 0.46F);
        ModernStyle.rounded(g, p.x() + (p.w() - floorW) / 2, p.y() + p.h() - 18, floorW, 6, 3, 0x2615303E);

        if (!this.dragging) {
            this.idle += this.dt;
            if (this.idle > IDLE_BEFORE_SPIN_S) this.yaw += SPIN_DEG_PER_S * this.dt;
        }
        this.yaw %= 360F;

        LocalPlayer player = Minecraft.getInstance().player;
        if (selected == null) {
            String text = ModernText.tr("cape.none");
            ModernTypography.draw(g, text, p.x() + (p.w() - ModernTypography.width(text)) / 2, p.y() + p.h() / 2 - 4, ModernStyle.INK_MUTED, false);
        } else if (player != null) {
            // The entity is drawn off to the side and composited back; it can't fade, so it waits for the window.
            if (openness() > 0.85F) drawPlayer(g, player, selected, p);
        } else {
            int th = p.h() - 30, tw = Math.round(th * 10F / 16F);
            blitFace(g, selected, p.x() + (p.w() - tw) / 2, p.y() + 10, tw, th);
            String text = ModernTypography.fit(ModernText.tr("cape.no_world"), p.w() - 12);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, text,
                p.x() + (p.w() - ModernTypography.width(ModernTypography.Face.TEXT, text, 0.78F)) / 2F, p.y() + p.h() - 14, 0.78F, ModernStyle.INK_MUTED);
        }
        if (player != null && selected != null) {
            float hint = animate("hint", over && !this.dragging ? 1F : 0F);
            if (hint > 0.02F) {
                try (var fade = ModernStyle.alphaScope(hint)) {
                    String text = ModernText.tr("cape.drag");
                    float tw = ModernTypography.width(ModernTypography.Face.TEXT, text, 0.78F);
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, text, p.x() + (p.w() - tw) / 2F, p.y() + 6, 0.78F, ModernStyle.INK_MUTED);
                }
            }
        }
    }

    private void drawPlayer(GuiGraphicsExtractor g, LocalPlayer player, CapeLibrary.Entry entry, Box p) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        EntityRenderer<? super LocalPlayer, ?> renderer = dispatcher.getRenderer(player);
        EntityRenderState state = renderer.createRenderState(player, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof AvatarRenderState avatar) {
            avatar.skin = Cape.withCape(avatar.skin, entry);
            avatar.showCape = true;
        }
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180F + this.yaw;
            living.yRot = 0F;
            living.xRot = 0F;
            living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
            living.boundingBoxHeight = living.boundingBoxHeight / living.scale;
            living.scale = 1.0F;
        }
        float size = (p.h() - 22) / Math.max(1.8F, state.boundingBoxHeight) * 0.92F;
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(this.pitch * (float) (Math.PI / 180.0));
        rotation.mul(tilt);
        Vector3f translation = new Vector3f(0.0F, state.boundingBoxHeight / 2.0F + 0.0625F, 0.0F);
        g.entity(state, size, translation, rotation, tilt, p.x(), p.y() + 4, p.x() + p.w(), p.y() + p.h() - 8);
    }

    private void drawGrid(GuiGraphicsExtractor g, List<CapeLibrary.Entry> capes, CapeLibrary.Entry selected, int mx, int my) {
        Box grid = grid();
        Box p = preview();
        String label = ModernText.tr("cape.count", capes.size());
        ModernTypography.draw(g, ModernTypography.Face.TEXT, label, grid.x(), p.y() + p.h() + 10, 0.78F, ModernStyle.INK_MUTED);
        if (grid.h() <= 0) return;
        boolean overGrid = grid.contains(mx, my);
        g.enableScissor(grid.x() - 4, grid.y() - 2, grid.x() + grid.w() + 4, grid.y() + grid.h());
        for (int i = 0; i < capes.size(); i++) {
            Box c = cell(i);
            if (c.y() + c.h() < grid.y() || c.y() > grid.y() + grid.h()) continue;
            CapeLibrary.Entry entry = capes.get(i);
            boolean current = selected != null && entry.id().equals(selected.id());
            boolean over = overGrid && c.contains(mx, my);
            if (over) {
                this.hoveredName = nameOf(entry);
                g.requestCursor(CursorTypes.POINTING_HAND);
            }
            float hover = animate(entry.id(), over ? 1F : 0F);
            float on = animate("on-" + entry.id(), current ? 1F : 0F);
            if (on > 0.01F) ModernStyle.halo(g, c.x(), c.y(), c.w(), c.h(), 8, ModernStyle.GLOW, 0.45F * on);
            ModernStyle.rounded(g, c.x(), c.y(), c.w(), c.h(), 8,
                ModernStyle.mix(ModernStyle.mix(0x1A243746, 0x2E3D9DDC, hover), 0x553D9DDC, on));
            if (on > 0.01F) {
                ModernStyle.rounded(g, c.x(), c.y(), c.w(), 2, 1, ModernTypography.fade(ModernStyle.BLUE, on));
            }
            int th = c.h() - 10, tw = Math.round(th * 10F / 16F);
            int lift = Math.round(hover * 1.5F);
            blitFace(g, entry, c.x() + (c.w() - tw) / 2, c.y() + 5 - lift, tw, th);
            if (entry.custom()) {
                ModernStyle.rounded(g, c.x() + c.w() - 9, c.y() + 4, 5, 5, 2, ModernStyle.mix(0xFFFFB86B, 0xFFFFFFFF, hover * 0.3F));
            }
        }
        g.disableScissor();
        int max = maxScroll(capes.size());
        if (max > 0) {
            int trackH = grid.h();
            int thumbH = Math.max(18, Math.round(trackH * (trackH / (float) (trackH + max))));
            int thumbY = grid.y() + Math.round((trackH - thumbH) * (this.scroll / max));
            ModernStyle.rounded(g, this.x + this.w - 6, thumbY, 2, thumbH, 1, 0x4415303E);
        }
    }

    private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
        Box b = buttons();
        String note = ModernTypography.fit(ModernText.tr("cape.client_side"), b.w());
        ModernTypography.draw(g, ModernTypography.Face.TEXT, note, b.x(), b.y() - 14, 0.78F, ModernStyle.INK_MUTED);
        drawButton(g, folderButton(), ModernIcons.Icon.LAYERS, ModernText.tr("cape.open_folder"), "folder", mx, my);
        drawButton(g, reloadButton(), ModernIcons.Icon.REFRESH, ModernText.tr("cape.reload"), "reload", mx, my);
    }

    private void drawButton(GuiGraphicsExtractor g, Box b, ModernIcons.Icon icon, String label, String key, int mx, int my) {
        boolean over = b.contains(mx, my);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        float hover = animate(key, over ? 1F : 0F);
        ModernStyle.rounded(g, b.x(), b.y(), b.w(), b.h(), 7, ModernStyle.mix(0x24243746, 0x403D9DDC, hover));
        String text = ModernTypography.fit(label, b.w() - 28);
        float tw = ModernTypography.width(ModernTypography.Face.TEXT, text, 0.82F);
        float start = b.x() + (b.w() - tw - 16) / 2F;
        int color = ModernStyle.mix(ModernStyle.INK_MUTED, ModernStyle.BLUE, hover);
        ModernIcons.draw(g, icon, start, b.y() + (b.h() - 11) / 2F, 11, color);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, text, start + 16, b.y() + (b.h() - 7) / 2F, 0.82F, color);
    }

    /** The cape's outer face - u 1..11, v 1..17 of the 64x32 layout - drawn flat. Works at any 2:1 resolution. */
    private static void blitFace(GuiGraphicsExtractor g, CapeLibrary.Entry entry, int x, int y, int w, int h) {
        ModernStyle.rounded(g, x - 1, y - 1, w + 2, h + 2, 2, 0x3315303E);
        int color = ModernTypography.fade(0xFFFFFFFF, ModernStyle.groupAlpha());
        g.blit(RenderPipelines.GUI_TEXTURED, entry.texture(), x, y, 1F, 1F, w, h, 10, 16, 64, 32, color);
    }

    private static String nameOf(CapeLibrary.Entry entry) {
        if (entry.custom()) return entry.name();
        String key = "cape.name." + entry.id();
        String name = ModernText.tr(key);
        return name.equals(key) ? entry.name() : name;
    }

    private float animate(Object key, float target) {
        float value = ModernStyle.smooth(this.anim.getOrDefault(key, target), target, this.dt, 14F);
        this.anim.put(key, value);
        return value;
    }

    // --- input ------------------------------------------------------------------------------------

    /** True when the click landed on the window (it then belongs to the window, whatever it hit). */
    boolean mouseClicked(double mx, double my, int button, Cape cape) {
        if (!contains(mx, my)) return false;
        if (!interactive()) return true;
        if (button == 0 && preview().contains(mx, my)) {
            this.dragging = true;
            this.lastX = mx;
            this.lastY = my;
            this.idle = 0F;
            return true;
        }
        if (button != 0) return true;
        if (folderButton().contains(mx, my)) {
            try {
                Files.createDirectories(CapeLibrary.folder());
            } catch (IOException ignored) {
                // Opening the parent is still more useful than nothing; the OS will say if it's missing.
            }
            Util.getPlatform().openPath(CapeLibrary.folder());
            return true;
        }
        if (reloadButton().contains(mx, my)) {
            CapeLibrary.reload();
            this.scrollTarget = 0;
            return true;
        }
        Box grid = grid();
        if (grid.contains(mx, my)) {
            List<CapeLibrary.Entry> capes = CapeLibrary.all();
            for (int i = 0; i < capes.size(); i++) {
                if (cell(i).contains(mx, my)) {
                    cape.select(capes.get(i).id());
                    if (!cape.isEnabled()) cape.setEnabled(true);
                    return true;
                }
            }
        }
        return true;
    }

    boolean mouseDragged(double mx, double my) {
        if (!this.dragging) return false;
        this.yaw += (float) (mx - this.lastX) * 0.9F;
        this.pitch = Math.max(-30F, Math.min(30F, this.pitch - (float) (my - this.lastY) * 0.5F));
        this.lastX = mx;
        this.lastY = my;
        this.idle = 0F;
        return true;
    }

    boolean mouseReleased() {
        if (!this.dragging) return false;
        this.dragging = false;
        this.idle = 0F;
        return true;
    }

    boolean mouseScrolled(double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        if (grid().contains(mx, my)) {
            this.scrollTarget = Math.max(0, Math.min(maxScroll(CapeLibrary.all().size()), this.scrollTarget - (int) Math.round(amount * (cellH() + CELL_GAP))));
        } else if (preview().contains(mx, my)) {
            this.yaw += (float) amount * 15F;
            this.idle = 0F;
        }
        return true;
    }
}
