package com.mentalfrostbyte.jello.gui.jello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Keybind Manager's keyboard: every cap inside the 1060 x 357 board, each key once. */
class JelloKeysTest {

    @Test
    void everyCapSitsInsideTheBoard() {
        for (JelloKeys cap : JelloKeys.values()) {
            assertTrue(cap.x >= 0 && cap.x + cap.w <= JelloKeys.WIDTH, cap + " is inside the width");
            // The body is 5 taller than the nominal 357: each cap's depth hangs under it.
            assertTrue(cap.y() + JelloKeys.CAP_HEIGHT <= JelloKeys.HEIGHT + 5, cap + " is inside the height");
            assertTrue(cap.row >= 1 && cap.row <= 5, cap + " is on a row");
        }
    }

    @Test
    void noKeyIsOnTheBoardTwice() {
        Set<Integer> codes = new HashSet<>();
        for (JelloKeys cap : JelloKeys.values()) {
            assertTrue(codes.add(cap.code), cap + " repeats a key code");
        }
    }

    @Test
    void capsOnARowDoNotOverlap() {
        for (JelloKeys a : JelloKeys.values()) {
            for (JelloKeys b : JelloKeys.values()) {
                if (a != b && a.row == b.row) {
                    assertTrue(a.x + a.w <= b.x || b.x + b.w <= a.x, a + " overlaps " + b);
                }
            }
        }
    }

    @Test
    void aKeyFindsItsCapAndAMouseButtonHasNone() {
        assertEquals(JelloKeys.Q, JelloKeys.of(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_Q)));
        assertEquals(JelloKeys.SPACE, JelloKeys.of(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_SPACE)));
        assertNull(JelloKeys.of(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_F5)), "no cap for the function keys");
        assertNull(JelloKeys.of(InputConstants.Type.MOUSE.getOrCreate(2)));
        assertNotNull(JelloKeys.RETURN.key());
        assertEquals(InputConstants.KEY_RETURN, JelloKeys.RETURN.code);
    }
}
