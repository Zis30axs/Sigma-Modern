package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class InventoryLayoutScreenTest {
    @Test void allThirtySevenTargetsAndThePaletteFitAtSupportedGuiScales() {
        for (int[] size : new int[][] {{240, 180}, {320, 180}, {426, 240}, {854, 480}}) {
            var g = InventoryLayoutScreen.Geometry.of(size[0], size[1]);
            var coordinates = new HashSet<String>();
            for (int slot = 9; slot <= 45; slot++) {
                int x = g.slotX(slot), y = g.slotY(slot);
                assertTrue(x >= 0 && x + g.step() <= size[0]);
                assertTrue(y >= 0 && y + g.step() <= g.searchY());
                assertTrue(coordinates.add(x + "," + y));
            }
            assertTrue(g.paletteY() + g.rows() * g.step() < size[1] - 24);
            assertTrue(g.columns() * g.step() <= g.width());
        }
    }
}
