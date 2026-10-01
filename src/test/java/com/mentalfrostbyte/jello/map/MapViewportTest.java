package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MapViewportTest {
    private static final int W = 590;
    private static final int H = 550;

    @Test
    void itStartsWhereTheOldClientDidAndZoomStaysInItsRange() {
        MapViewport view = new MapViewport();
        assertEquals(8, view.zoom());
        assertEquals(14, view.chunksAcross());

        view.zoomBy(1000);
        assertEquals(MapViewport.MIN_ZOOM, view.zoom());
        assertEquals(4, view.chunksAcross());
        view.zoomBy(-1000);
        assertEquals(MapViewport.MAX_ZOOM, view.zoom());
        assertEquals(64, view.chunksAcross());
    }

    @Test
    void theLongerSideHoldsTheChunksAcross() {
        MapViewport view = new MapViewport();
        // 14 chunks = 224 blocks across 590 pixels.
        assertEquals(590 / 224.0, view.scale(W, H), 1.0E-9);
        assertEquals(590 / 224.0, view.scale(H, W), 1.0E-9);
    }

    @Test
    void theCentreOfTheFrameIsTheCentreOfTheView() {
        MapViewport view = new MapViewport();
        view.centreOn(1000.5, -250.25);
        assertEquals(1000.5, view.worldX(W / 2.0, W, H), 1.0E-9);
        assertEquals(-250.25, view.worldZ(H / 2.0, W, H), 1.0E-9);
        assertEquals(W / 2.0, view.pixelX(1000.5, W, H), 1.0E-9);
        assertEquals(H / 2.0, view.pixelZ(-250.25, W, H), 1.0E-9);
    }

    @Test
    void worldAndPixelAreInversesAndSouthIsDown() {
        MapViewport view = new MapViewport();
        view.centreOn(40, 80);
        for (double px : new double[]{0, 13.5, 295, 589.9}) {
            assertEquals(px, view.pixelX(view.worldX(px, W, H), W, H), 1.0E-9);
        }
        // A larger z is further down; a larger x further right.
        assertEquals(true, view.pixelZ(90, W, H) > view.pixelZ(80, W, H));
        assertEquals(true, view.pixelX(50, W, H) > view.pixelX(40, W, H));
    }

    @Test
    void draggingMovesTheWorldWithThePointer() {
        MapViewport view = new MapViewport();
        view.centreOn(0, 0);
        double blockUnderPointer = view.worldX(200, W, H);
        view.pan(30, -12, W, H);
        // The block that was under the pointer is now 30 pixels to the right of where it was.
        assertEquals(230, view.pixelX(blockUnderPointer, W, H), 1.0E-9);
        assertEquals(true, view.centreX() < 0);
        assertEquals(true, view.centreZ() > 0);
    }

    @Test
    void theChunksDrawnCoverEveryPixelOfTheFrame() {
        MapViewport view = new MapViewport();
        for (double[] at : new double[][]{{0, 0}, {-7.3, 15.9}, {100.2, -333.3}, {16, 16}, {-16, -16}}) {
            for (int zoom = MapViewport.MIN_ZOOM; zoom <= MapViewport.MAX_ZOOM; zoom += 5) {
                view.centreOn(at[0], at[1]);
                view.zoomBy(view.zoom() - zoom);
                int cx = view.firstChunkX(W, H);
                int cz = view.firstChunkZ(W, H);
                int wide = view.chunksWide(W, H);
                int high = view.chunksHigh(W, H);
                String where = at[0] + "," + at[1] + " zoom " + zoom;
                assertEquals(true, cx * 16 <= view.worldX(0, W, H), where);
                assertEquals(true, (cx + wide) * 16 > view.worldX(W, W, H), where);
                assertEquals(true, cz * 16 <= view.worldZ(0, W, H), where);
                assertEquals(true, (cz + high) * 16 > view.worldZ(H, W, H), where);
                // And no more than one spare chunk each side.
                assertEquals(true, wide <= view.chunksAcross() + 2, where);
            }
        }
    }
}
