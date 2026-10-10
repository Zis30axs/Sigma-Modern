package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HudLayoutTest {
    @Test
    void untouchedWidgetUsesItsDefaultAndHasNoSavedPosition() {
        HudLayout layout = new HudLayout();
        assertEquals(new HudLayout.Rect(12, 25, 80, 30, 0, 0), layout.resolve("array-list", 12, 25, 80, 30, 800, 600, false));
        assertFalse(layout.hasPosition("array-list"));
    }

    @Test
    void absolutePositionsKeepTheirScreenAnchorAcrossResizeAndGuiScale() {
        HudLayout layout = new HudLayout();
        layout.move("target_hud", 700, 550, 20, 20, 100, 50, 800, 600, false);
        HudLayout.Rect resized = layout.resolve("target_hud", 20, 20, 100, 50, 400, 300, false);
        assertEquals(300, resized.x());
        assertEquals(250, resized.y());
        assertEquals(280, resized.dx());
        assertEquals(230, resized.dy());
    }

    @Test
    void temporaryClampingForExpandedPanelsDoesNotMoveTheSavedAnchor() {
        HudLayout layout = new HudLayout();
        layout.move("tab-gui", 600, 400, 10, 38, 100, 100, 800, 600, false);
        JsonObject before = new JsonObject();
        layout.write(before);
        HudLayout.Rect expanded = layout.resolve("tab-gui", 10, 38, 900, 700, 800, 600, false);
        assertEquals(0, expanded.x());
        assertEquals(0, expanded.y());
        HudLayout.Rect collapsed = layout.resolve("tab-gui", 10, 38, 100, 100, 800, 600, false);
        assertEquals(600, collapsed.x(), 0.0001);
        assertEquals(400, collapsed.y(), 0.0001);
        JsonObject after = new JsonObject();
        layout.write(after);
        assertEquals(before, after);
    }

    @Test
    void followPositionTracksItsMovingAnchorAndScalesOnlyTheOffset() {
        HudLayout layout = new HudLayout();
        layout.move("target_hud_follow", 250, 180, 200, 150, 100, 50, 800, 600, true);
        HudLayout.Rect movedTarget = layout.resolve("target_hud_follow", 350, 250, 100, 50, 800, 600, true);
        assertEquals(400, movedTarget.x());
        assertEquals(280, movedTarget.y());
        HudLayout.Rect resized = layout.resolve("target_hud_follow", 100, 75, 100, 50, 400, 300, true);
        assertEquals(125, resized.x());
        assertEquals(90, resized.y());
    }

    @Test
    void absoluteAndFollowCoordinateModesAreNotInterchangeable() {
        HudLayout layout = new HudLayout();
        layout.move("target", 300, 250, 30, 40, 100, 50, 800, 600, false);
        assertEquals(30, layout.resolve("target", 30, 40, 100, 50, 800, 600, true).x());
        layout.move("target", 300, 250, 30, 40, 100, 50, 800, 600, true);
        assertEquals(30, layout.resolve("target", 30, 40, 100, 50, 800, 600, false).x());
    }

    @Test
    void persistedLayoutRoundTripsAndSingleResetKeepsOtherWidgets() {
        HudLayout original = new HudLayout();
        original.move("array-list", 30, 20, 10, 10, 120, 80, 800, 600, false);
        original.move("target_hud_follow", 250, 180, 200, 150, 100, 50, 800, 600, true);
        JsonObject config = new JsonObject();
        config.addProperty("unrelated", "keep");
        original.write(config);
        HudLayout loaded = new HudLayout();
        loaded.read(JsonParser.parseString(config.toString()).getAsJsonObject());
        assertEquals(original.resolve("target_hud_follow", 200, 150, 100, 50, 800, 600, true),
                loaded.resolve("target_hud_follow", 200, 150, 100, 50, 800, 600, true));
        assertTrue(loaded.reset("array-list"));
        assertFalse(loaded.reset("array-list"));
        assertTrue(loaded.hasPosition("target_hud_follow"));
        assertEquals("keep", config.get("unrelated").getAsString());
    }

    @Test
    void corruptPersistedEntriesAreIgnoredAndFiniteOutliersAreClamped() {
        HudLayout layout = new HudLayout();
        layout.read(JsonParser.parseString("""
                {"modernHudLayout": {
                  "valid": {"mode":"absolute","x":2,"y":-3},
                  "unknown": {"mode":"pixels","x":0.5,"y":0.5},
                  "string": {"mode":"absolute","x":"0.5","y":0.5},
                  "infinite": {"mode":"absolute","x":1e999,"y":0.5},
                  "../bad": {"mode":"absolute","x":0.5,"y":0.5},
                  "shape": []
                }}
                """).getAsJsonObject());
        assertEquals(700, layout.resolve("valid", 10, 10, 100, 50, 800, 600, false).x());
        assertEquals(0, layout.resolve("valid", 10, 10, 100, 50, 800, 600, false).y());
        for (String id : new String[]{"unknown", "string", "infinite", "../bad", "shape"}) assertFalse(layout.hasPosition(id));
        layout.read(JsonParser.parseString("{\"modernHudLayout\":false}").getAsJsonObject());
        assertFalse(layout.hasPosition("valid"));
    }

    @Test
    void zeroViewportIsFiniteAndInvalidCallerGeometryFailsFast() {
        HudLayout layout = new HudLayout();
        HudLayout.Rect zero = layout.resolve("target", 20, 30, 100, 50, 0, 0, false);
        assertEquals(0, zero.x());
        assertEquals(0, zero.y());
        assertFalse(zero.contains(-1, -1));
        assertThrows(IllegalArgumentException.class, () -> layout.resolve("target", Float.NaN, 0, 100, 50, 800, 600, false));
    }

    @Test
    void frameMovesScoreboardBelowRightListAndPreservesItsRenderTranslation() {
        HudLayout.Frame frame = new HudLayout.Frame();
        frame.reserve("array-list", new HudLayout.Rect(600, 10, 190, 220, 0, 0));
        HudLayout.Rect placed = frame.place("scoreboard", new HudLayout.Rect(646, 120, 144, 140, 7, -4), 800, 600, true);
        assertEquals(646F, placed.x());
        assertEquals(236F, placed.y());
        assertEquals(7F, placed.dx());
        assertEquals(112F, placed.dy());
    }

    @Test
    void bottomStackMovesUpAndEqualDistanceTiesFollowTheScreenAnchor() {
        HudLayout.Frame frame = new HudLayout.Frame();
        frame.reserve("array-list", new HudLayout.Rect(600, 400, 190, 190, 0, 0));
        assertEquals(314F, frame.place("potions", new HudLayout.Rect(600, 450, 190, 80, 0, 0), 800, 600, true).y());

        frame.clear();
        frame.reserve("upper-blocker", new HudLayout.Rect(100, 90, 80, 40, 0, 0));
        assertEquals(136F, frame.place("upper", new HudLayout.Rect(100, 100, 80, 20, 0, 0), 500, 400, true).y());
        frame.clear();
        frame.reserve("lower-blocker", new HudLayout.Rect(100, 290, 80, 40, 0, 0));
        assertEquals(264F, frame.place("lower", new HudLayout.Rect(100, 300, 80, 20, 0, 0), 500, 400, true).y());
    }

    @Test
    void fullColumnFallsBackToTheNearestFreeColumn() {
        HudLayout.Frame frame = new HudLayout.Frame();
        frame.reserve("right-column", new HudLayout.Rect(200, 0, 100, 200, 0, 0));
        HudLayout.Rect placed = frame.place("scoreboard", new HudLayout.Rect(220, 60, 60, 70, 0, 0), 300, 200, true);
        assertEquals(134F, placed.x());
        assertEquals(60F, placed.y());
        assertEquals(-86F, placed.dx());
    }

    @Test
    void manualPositionsRemainFixedAndBlockOtherAutomaticWidgets() {
        HudLayout.Frame frame = new HudLayout.Frame();
        frame.reserve("other-pin", new HudLayout.Rect(10, 10, 100, 100, 0, 0));
        HudLayout.Rect pin = new HudLayout.Rect(50, 50, 120, 100, 30, 40);
        assertSame(pin, frame.place("clock", pin, 800, 600, false));
        HudLayout.Rect automatic = frame.place("keyboard", new HudLayout.Rect(60, 70, 50, 40, 0, 0), 800, 600, true);
        assertEquals(60F, automatic.x());
        assertEquals(156F, automatic.y());
    }

    @Test
    void exactDefaultEdgeAnchorsAndClearanceStayUnchanged() {
        HudLayout.Frame frame = new HudLayout.Frame();
        HudLayout.Rect edge = new HudLayout.Rect(0, 0, 80, 30, 4, -5);
        assertSame(edge, frame.place("edge", edge, 800, 600, true));
        HudLayout.Rect beside = new HudLayout.Rect(86, 0, 80, 30, 0, 0);
        assertSame(beside, frame.place("beside", beside, 800, 600, true));
    }

    @Test
    void clearingFrameRemovesDisabledWidgetsAndOwnReservationsDoNotCauseDrift() {
        HudLayout.Frame frame = new HudLayout.Frame();
        HudLayout.Rect preferred = new HudLayout.Rect(600, 120, 190, 140, 0, 0);
        frame.reserve("array-list", new HudLayout.Rect(600, 10, 190, 220, 0, 0));
        HudLayout.Rect first = frame.place("scoreboard", preferred, 800, 600, true);
        assertEquals(first, frame.place("scoreboard", preferred, 800, 600, true));
        frame.clear();
        assertSame(preferred, frame.place("scoreboard", preferred, 800, 600, true));
        assertSame(preferred, frame.place("scoreboard", preferred, 800, 600, true));
    }

    @Test
    void reservationsReplacePreviousBoundsAndZeroSizeDoesNotBlock() {
        HudLayout.Frame frame = new HudLayout.Frame();
        HudLayout.Rect preferred = new HudLayout.Rect(10, 10, 80, 40, 0, 0);
        frame.reserve("clock", preferred);
        frame.reserve("clock", new HudLayout.Rect(400, 400, 80, 40, 0, 0));
        assertSame(preferred, frame.place("keyboard", preferred, 800, 600, true));
        frame.reserve("clock", preferred);
        frame.reserve("clock", new HudLayout.Rect(10, 10, 0, 40, 0, 0));
        frame.reserve("empty", new HudLayout.Rect(10, 10, 80, 0, 0, 0));
        assertSame(preferred, frame.place("keyboard", preferred, 800, 600, true));
    }

    @Test
    void crowdedFallbackMinimizesVisibleOverlapAndIsDeterministic() {
        HudLayout.Frame frame = new HudLayout.Frame();
        HudLayout.Rect expected = null;
        for (int iteration = 0; iteration < 8; iteration++) {
            frame.clear();
            frame.reserve("left", new HudLayout.Rect(0, 0, 140, 100, 0, 0));
            frame.reserve("right", new HudLayout.Rect(150, 0, 50, 100, 0, 0));
            HudLayout.Rect result = frame.place("scoreboard", new HudLayout.Rect(0, 20, 100, 60, 0, 0), 200, 100, true);
            // Only 90 pixels of its width overlap here, versus all 100 at the original left anchor.
            assertEquals(50F, result.x());
            assertEquals(20F, result.y());
            if (expected == null) expected = result;
            else assertEquals(expected, result);
        }
    }

    @Test
    void resizedAndOversizedPanelsRemainFiniteAndClampedWhenNothingFits() {
        HudLayout.Frame frame = new HudLayout.Frame();
        for (int[] viewport : new int[][]{{800, 600}, {320, 200}, {0, 0}}) {
            frame.clear();
            frame.reserve("full-screen", new HudLayout.Rect(0, 0, 800, 600, 0, 0));
            HudLayout.Rect result = frame.place("expanded-tab", new HudLayout.Rect(20, 30, 900, 700, 5, 6),
                    viewport[0], viewport[1], true);
            assertEquals(0F, result.x());
            assertEquals(0F, result.y());
            assertEquals(-15F, result.dx());
            assertEquals(-24F, result.dy());
            assertTrue(Float.isFinite(result.width()));
            assertTrue(Float.isFinite(result.height()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> frame.place("bad", new HudLayout.Rect(Float.NaN, 0, 10, 10, 0, 0), 800, 600, true));
        assertThrows(IllegalArgumentException.class,
                () -> frame.reserve("bad", new HudLayout.Rect(0, 0, 10, 10, Float.POSITIVE_INFINITY, 0)));
    }

    @Test
    void enabledModernPanelsShareTheFrameWithoutOverlap() {
        HudLayout.Frame frame = new HudLayout.Frame();
        List<HudLayout.Rect> panels = new ArrayList<>();
        panels.add(frame.place("music_island", new HudLayout.Rect(362, 8, 236, 52, 0, 0), 960, 540, true));
        panels.add(frame.place("watermark", new HudLayout.Rect(10, 10, 128, 22, 0, 0), 960, 540, true));
        panels.add(frame.place("tab-gui", new HudLayout.Rect(10, 38, 280, 230, 0, 0), 960, 540, true));
        panels.add(frame.place("keystrokes", new HudLayout.Rect(10, 140, 66, 76, 0, 0), 960, 540, true));
        HudLayout.Rect list = frame.place("array-list", new HudLayout.Rect(790, 10, 160, 190, 0, 0), 960, 540, true);
        panels.add(list);
        panels.add(frame.place("potion-status", new HudLayout.Rect(770, 408, 180, 122, 0, 0), 960, 540, true));
        HudLayout.Rect scoreboard = frame.place("scoreboard", new HudLayout.Rect(750, 70, 200, 230, 0, 0), 960, 540, true);
        panels.add(scoreboard);
        panels.add(frame.place("clock", new HudLayout.Rect(12, 250, 160, 62, 0, 0), 960, 540, true));
        panels.add(frame.place("target_hud", new HudLayout.Rect(504, 335, 216, 122, 0, 0), 960, 540, true));

        assertTrue(scoreboard.y() >= list.y() + list.height() + 6
                || scoreboard.x() + scoreboard.width() + 6 <= list.x());
        for (int i = 0; i < panels.size(); i++) {
            HudLayout.Rect a = panels.get(i);
            assertTrue(a.x() >= 0 && a.y() >= 0 && a.x() + a.width() <= 960 && a.y() + a.height() <= 540);
            for (int j = i + 1; j < panels.size(); j++) {
                HudLayout.Rect b = panels.get(j);
                assertTrue(a.x() + a.width() + 6 <= b.x() || b.x() + b.width() + 6 <= a.x()
                        || a.y() + a.height() + 6 <= b.y() || b.y() + b.height() + 6 <= a.y(),
                        "Panels " + i + " and " + j + " overlap: " + a + " / " + b);
            }
        }
    }

    @Test
    void scaledFractionalEdgesDoNotSendAdjacentPanelsAcrossTheScreen() {
        HudLayout.Frame frame = new HudLayout.Frame();
        frame.reserve("fractional-row", new HudLayout.Rect(100, 10, 80, 22.6F, 0, 0));
        HudLayout.Rect below = frame.place("scoreboard", new HudLayout.Rect(100, 20, 80, 20, 0, 0), 500, 400, true);
        assertEquals(100F, below.x());
        assertEquals(38.6F, below.y(), 0.001F);
        assertEquals(18.6F, below.dy(), 0.001F);

        frame.clear();
        frame.reserve("fractional-column", new HudLayout.Rect(100, 0, 22.6F, 400, 0, 0));
        HudLayout.Rect beside = frame.place("clock", new HudLayout.Rect(110, 20, 20, 20, 0, 0), 500, 400, true);
        assertEquals(128.6F, beside.x(), 0.001F);
        assertEquals(20F, beside.y());
    }
}
