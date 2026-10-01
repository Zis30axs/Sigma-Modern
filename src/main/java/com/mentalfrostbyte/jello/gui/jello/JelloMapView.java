package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.map.ExploredMap;
import com.mentalfrostbyte.jello.map.MapManager;
import com.mentalfrostbyte.jello.map.MapViewport;
import com.mentalfrostbyte.jello.map.Waypoint;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The map in the Maps page: what has been seen of the dimension as one picture, north up, which the mouse drags about and the
 * wheel or the little control in the corner zooms. The waypoints stand on it as markers and the player as an arrow.
 *
 * <p>The picture is one texture of the chunks that cover the frame (plus one, so a drag does not show an edge), laid out from
 * {@link ExploredMap} and rebuilt when the view moves to other chunks or something new has been seen - at most ten times a
 * second. It is drawn scaled with linear filtering, as the old client drew it.</p>
 *
 * <p>Everything is in {@code LegacyCanvas} pixels. The frame's right-hand corners are rounded like the panel's, which a
 * rectangular scissor cannot do, so the frame is drawn in a band across the middle and one-pixel rows near the top and
 * bottom, each cut a little shorter than the last.</p>
 */
final class JelloMapView {
    static final int ZOOM_W = 40;
    static final int ZOOM_H = 90;
    private static final int CORNER = 14;
    private static final Identifier ID = Identifier.withDefaultNamespace("sigma/maps");
    private static final long REBUILD_NANOS = 100_000_000L;
    private static final long REPEAT_AFTER_MS = 350L;
    private static final long REPEAT_EVERY_MS = 110L;
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;

    /** A texture the map is smoothed across when it is scaled. */
    private static final class MapTexture extends DynamicTexture {
        MapTexture(final int width, final int height) {
            super("SigmaModern maps", width, height, true);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    private static final class Ripple {
        final boolean zoomIn;
        float progress;

        Ripple(final boolean zoomIn) {
            this.zoomIn = zoomIn;
        }
    }

    /** Asks for the waypoint dialog: where the pointer is, and the spot of the world under it. */
    interface Popover {
        void open(double mx, double my, int blockX, int blockZ);
    }

    final MapViewport view = new MapViewport();
    final MapManager.Session session;
    // The frame, in canvas pixels: set by the page each frame.
    int x;
    int y;
    int w;
    int h;

    private final List<Ripple> ripples = new ArrayList<>();
    private @Nullable MapTexture texture;
    private int[] pixels = new int[0];
    private int texW;
    private int texH;
    private int builtChunkX = Integer.MIN_VALUE;
    private int builtChunkZ = Integer.MIN_VALUE;
    private int builtVersion = -1;
    private long lastBuild;
    private boolean dragging;
    private double lastX;
    private double lastY;
    private int held;
    private long heldSince;
    private long lastStep;

    JelloMapView(final MapManager.Session session) {
        this.session = session;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            this.view.centreOn(player.getX(), player.getZ());
        }
    }

    /** Gives the texture back; the page calls it when it closes. */
    void release() {
        if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(ID);
            this.texture = null;
        }
    }

    boolean contains(final double mx, final double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    private int zoomX() {
        return this.x + this.w - ZOOM_W - 10;
    }

    private int zoomY() {
        return this.y + this.h - ZOOM_H - 10;
    }

    // ------------------------------------------------------------------ drawing

    void draw(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        this.repeat();
        this.build();
        this.clipped(c, () -> this.body(c, alpha));
        this.zoomControl(c, alpha);
        if (!this.session.recordable()) {
            c.textCentered(Face.JELLO_LIGHT, 20.0F, "No map of this dimension", this.x + this.w / 2.0F, this.y + this.h / 2.0F,
                LegacyCanvas.alpha(INK, 0.4F * alpha));
        }
    }

    /**
     * Draws {@code body} inside the frame with its right-hand corners rounded: once for the band between the corners, and
     * once for each strip of the corners, each cut a little shorter than the one above it. A scissor is in whole GUI units,
     * so a strip is as tall as one GUI unit is in canvas pixels (and starts on one): anything thinner would be dropped.
     */
    private void clipped(final LegacyCanvas c, final Runnable body) {
        int r = CORNER;
        c.scissor(this.x, this.y + r, this.x + this.w, this.y + this.h - r);
        body.run();
        c.unscissor();
        int unit = Math.max(1, c.guiScale());
        for (int from = this.y; from < this.y + r; ) {
            int to = Math.min(this.y + r, (from / unit + 1) * unit);
            int inset = inset(r, (from + to) / 2.0 - this.y);
            c.scissor(this.x, from, this.x + this.w - inset, to);
            body.run();
            c.unscissor();
            from = to;
        }

        int bottom = this.y + this.h;
        for (int to = bottom; to > bottom - r; ) {
            int from = Math.max(bottom - r, (Math.ceilDiv(to, unit) - 1) * unit);
            int inset = inset(r, bottom - (from + to) / 2.0);
            c.scissor(this.x, from, this.x + this.w - inset, to);
            body.run();
            c.unscissor();
            to = from;
        }
    }

    /** How far in from the edge a rounded corner of radius {@code r} is {@code depth} pixels from the top (or bottom). */
    static int inset(final int r, final double depth) {
        // Past the radius the edge is straight again; above the edge the whole radius is cut.
        double dy = Math.max(0.0, Math.min(r, r - depth));
        return (int) Math.round(r - Math.sqrt(r * r - dy * dy));
    }

    private void body(final LegacyCanvas c, final float alpha) {
        if (this.texture != null) {
            float scale = (float) this.view.scale(this.w, this.h);
            float left = this.x + (float) this.view.pixelX(this.builtChunkX * 16.0, this.w, this.h);
            float top = this.y + (float) this.view.pixelZ(this.builtChunkZ * 16.0, this.w, this.h);
            c.image(ID, this.texW, this.texH, left, top, this.texW * scale, this.texH * scale, LegacyCanvas.alpha(WHITE, alpha));
        }

        for (Waypoint waypoint : this.session.waypoints().in(this.session.dimension())) {
            float px = this.x + (float) this.view.pixelX(waypoint.x() + 0.5, this.w, this.h);
            float py = this.y + (float) this.view.pixelZ(waypoint.z() + 0.5, this.w, this.h);
            if (px < this.x - 20 || px > this.x + this.w + 20 || py < this.y - 4 || py > this.y + this.h + 50) {
                continue;
            }

            c.image(LegacyTexture.WAYPOINT, Math.round(px) - 16, Math.round(py) - 44, 32, 46, LegacyCanvas.fade(waypoint.color(), alpha));
        }

        this.player(c, alpha);
    }

    /** The player: an arrow on the spot they stand, pointing the way they face. */
    private void player(final LegacyCanvas c, final float alpha) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        float px = this.x + (float) this.view.pixelX(player.getX(), this.w, this.h);
        float py = this.y + (float) this.view.pixelZ(player.getZ(), this.w, this.h);
        if (px < this.x - 10 || px > this.x + this.w + 10 || py < this.y - 10 || py > this.y + this.h + 10) {
            return;
        }

        c.push();
        try {
            c.translate(px, py);
            // Facing south is a yaw of 0 and the page has south down, so the arrow (which points up) turns half a circle.
            c.graphics().pose().rotate((float) Math.toRadians(player.getYRot() + 180.0F));
            float width = c.textWidth(Face.JELLO_MEDIUM, 20.0F, "^");
            c.text(Face.JELLO_MEDIUM, 20.0F, "^", -width / 2.0F + 1, -7.0F, LegacyCanvas.alpha(INK, 0.3F * alpha));
            c.text(Face.JELLO_MEDIUM, 20.0F, "^", -width / 2.0F, -8.0F, LegacyCanvas.alpha(WHITE, alpha));
        } finally {
            c.pop();
        }
    }

    /** Lays the chunks that cover the frame out on the texture, when the view or the map has changed since the last time. */
    private void build() {
        double scale = this.view.scale(this.w, this.h);
        int chunksWide = (int) Math.ceil(this.w / scale / 16.0) + 1;
        int chunksHigh = (int) Math.ceil(this.h / scale / 16.0) + 1;
        int chunkX = this.view.firstChunkX(this.w, this.h);
        int chunkZ = this.view.firstChunkZ(this.w, this.h);
        long now = System.nanoTime();
        boolean resized = this.texture == null || chunksWide * 16 != this.texW || chunksHigh * 16 != this.texH;
        boolean moved = chunkX != this.builtChunkX || chunkZ != this.builtChunkZ;
        boolean seen = this.session.explored().version() != this.builtVersion && now - this.lastBuild > REBUILD_NANOS;
        if (!resized && !moved && !seen) {
            return;
        }

        if (resized) {
            this.texW = chunksWide * 16;
            this.texH = chunksHigh * 16;
            this.pixels = new int[this.texW * this.texH];
            this.texture = new MapTexture(this.texW, this.texH);
            Minecraft.getInstance().getTextureManager().register(ID, this.texture);
        }

        this.session.explored().compose(chunkX, chunkZ, chunksWide, chunksHigh, this.pixels);
        for (int row = 0; row < this.texH; row++) {
            for (int column = 0; column < this.texW; column++) {
                this.texture.getPixels().setPixel(column, row, this.pixels[row * this.texW + column]);
            }
        }

        this.texture.upload();
        this.builtChunkX = chunkX;
        this.builtChunkZ = chunkZ;
        this.builtVersion = this.session.explored().version();
        this.lastBuild = now;
    }

    // ------------------------------------------------------------------ the zoom control

    /**
     * A dark glass pill in the corner: a plus over a minus, the top half zooming in and the bottom half out, each with a
     * ripple spreading from the middle of its half when pressed.
     */
    private void zoomControl(final LegacyCanvas c, final float alpha) {
        int zx = this.zoomX();
        int zy = this.zoomY();
        c.rounded(zx, zy, ZOOM_W, ZOOM_H, 10, LegacyCanvas.alpha(INK, 0.38F * alpha));
        c.fill(zx + 8, zy, zx + ZOOM_W - 8, zy + 1, LegacyCanvas.alpha(WHITE, 0.2F * alpha));

        int glyph = LegacyCanvas.alpha(WHITE, 0.85F * alpha);
        int plusY = zy + ZOOM_H / 4;
        int minusY = zy + ZOOM_H * 3 / 4;
        c.fill(zx + 15, plusY - 1, zx + 25, plusY + 1, glyph);
        c.fill(zx + 19, plusY - 5, zx + 21, plusY + 5, glyph);
        c.fill(zx + 15, minusY - 1, zx + 25, minusY + 1, glyph);

        for (Iterator<Ripple> it = this.ripples.iterator(); it.hasNext(); ) {
            Ripple ripple = it.next();
            int top = ripple.zoomIn ? zy : zy + ZOOM_H / 2;
            c.scissor(zx, top, zx + ZOOM_W, top + ZOOM_H / 2);
            float fade = (1.0F - ripple.progress * (0.5F + ripple.progress * 0.5F)) * 0.4F;
            c.disc(zx + ZOOM_W / 2.0F, top + ZOOM_H / 4.0F, 4.0F + 22.0F * ripple.progress, LegacyCanvas.alpha(WHITE, fade * alpha));
            c.unscissor();
            ripple.progress += 0.05F;
            if (ripple.progress >= 1.0F) {
                it.remove();
            }
        }
    }

    /** One step of zoom by the control (or its repeat while held). */
    private void step(final int direction) {
        this.view.zoomBy(direction);
        this.ripples.add(new Ripple(direction > 0));
    }

    /** Holding the control zooms on: a pause after the first step, then one every tenth of a second. */
    private void repeat() {
        if (this.held == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - this.heldSince > REPEAT_AFTER_MS && now - this.lastStep > REPEAT_EVERY_MS) {
            this.lastStep = now;
            this.step(this.held);
        }
    }

    // ------------------------------------------------------------------ input

    boolean mouseClicked(final double mx, final double my, final int button, final Popover popover) {
        if (!this.contains(mx, my)) {
            return false;
        }

        int zx = this.zoomX();
        int zy = this.zoomY();
        if (button == 0 && mx >= zx && mx < zx + ZOOM_W && my >= zy && my < zy + ZOOM_H) {
            this.held = my < zy + ZOOM_H / 2.0 ? 1 : -1;
            this.heldSince = System.currentTimeMillis();
            this.lastStep = this.heldSince;
            this.step(this.held);
            return true;
        }

        if (button == 1) {
            int blockX = (int) Math.round(this.view.worldX(mx - this.x, this.w, this.h));
            int blockZ = (int) Math.round(this.view.worldZ(my - this.y, this.w, this.h));
            popover.open(mx, my, blockX, blockZ);
            return true;
        }

        if (button == 0) {
            this.dragging = true;
            this.lastX = mx;
            this.lastY = my;
        }

        return true;
    }

    void mouseDragged(final double mx, final double my) {
        if (this.dragging) {
            this.view.pan(mx - this.lastX, my - this.lastY, this.w, this.h);
            this.lastX = mx;
            this.lastY = my;
        }
    }

    void mouseReleased() {
        this.dragging = false;
        this.held = 0;
    }

    /** The wheel zooms a step a notch (up is in). */
    void mouseScrolled(final double delta) {
        int steps = (int) Math.signum(delta);
        if (steps != 0) {
            this.view.zoomBy(steps);
        }
    }

    void centreOn(final Waypoint waypoint) {
        this.view.centreOn(waypoint.x() + 0.5, waypoint.z() + 0.5);
    }
}
