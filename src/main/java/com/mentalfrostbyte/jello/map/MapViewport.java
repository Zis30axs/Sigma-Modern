package com.mentalfrostbyte.jello.map;

/**
 * Where the Maps page is looking: a point of the world in the middle and a zoom, which is how many chunks fit across the
 * longer side of the frame (twice the zoom less one, as in the old client, which counted 3 to 33). The arithmetic of the
 * frame is here so it can be checked without a game; the page only draws.
 *
 * <p>North is up: +X goes right and +Z down the screen.</p>
 */
public final class MapViewport {
    public static final int MIN_ZOOM = 3;
    public static final int MAX_ZOOM = 33;
    public static final int DEFAULT_ZOOM = 8;

    private double centreX;
    private double centreZ;
    private int zoom = DEFAULT_ZOOM;

    public double centreX() {
        return this.centreX;
    }

    public double centreZ() {
        return this.centreZ;
    }

    public int zoom() {
        return this.zoom;
    }

    public void centreOn(final double x, final double z) {
        this.centreX = x;
        this.centreZ = z;
    }

    /** Zooms in (a positive {@code steps}) or out; the zoom stays between {@link #MIN_ZOOM} and {@link #MAX_ZOOM}. */
    public void zoomBy(final int steps) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, this.zoom - steps));
    }

    /** How many chunks fit across the frame's longer side. */
    public int chunksAcross() {
        return (this.zoom - 1) * 2;
    }

    /** Pixels of frame to a block of world. */
    public double scale(final int width, final int height) {
        return Math.max(width, height) / (this.chunksAcross() * 16.0);
    }

    /** The frame moved by the mouse: the world follows the pointer, so the centre goes the other way. */
    public void pan(final double pixelsX, final double pixelsY, final int width, final int height) {
        double scale = this.scale(width, height);
        this.centreX -= pixelsX / scale;
        this.centreZ -= pixelsY / scale;
    }

    public double worldX(final double pixelX, final int width, final int height) {
        return this.centreX + (pixelX - width / 2.0) / this.scale(width, height);
    }

    public double worldZ(final double pixelY, final int width, final int height) {
        return this.centreZ + (pixelY - height / 2.0) / this.scale(width, height);
    }

    public double pixelX(final double worldX, final int width, final int height) {
        return width / 2.0 + (worldX - this.centreX) * this.scale(width, height);
    }

    public double pixelZ(final double worldZ, final int width, final int height) {
        return height / 2.0 + (worldZ - this.centreZ) * this.scale(width, height);
    }

    /** The chunk column at the frame's left edge. */
    public int firstChunkX(final int width, final int height) {
        return Math.floorDiv((int) Math.floor(this.worldX(0, width, height)), 16);
    }

    /** The chunk row at the frame's top edge. */
    public int firstChunkZ(final int width, final int height) {
        return Math.floorDiv((int) Math.floor(this.worldZ(0, width, height)), 16);
    }

    /** How many chunk columns reach from the left edge to the right one. */
    public int chunksWide(final int width, final int height) {
        return Math.floorDiv((int) Math.floor(this.worldX(width, width, height)), 16) - this.firstChunkX(width, height) + 1;
    }

    /** How many chunk rows reach from the top edge to the bottom one. */
    public int chunksHigh(final int width, final int height) {
        return Math.floorDiv((int) Math.floor(this.worldZ(height, width, height)), 16) - this.firstChunkZ(width, height) + 1;
    }
}
