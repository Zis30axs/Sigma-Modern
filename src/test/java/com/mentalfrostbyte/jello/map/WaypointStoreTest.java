package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointStoreTest {
    private static final Waypoint HOME = new Waypoint("Home", 10, 20, 1);
    private static final Waypoint MINE = new Waypoint("Mine", -300, 45, 2);
    private static final Waypoint FARM = new Waypoint("Farm", 7, 8, 3);
    private static final Waypoint GATE = new Waypoint("Gate", 1, 1, 4, "minecraft:the_nether");

    @TempDir
    Path dir;

    private WaypointStore store() {
        return new WaypointStore(this.dir.resolve("deep/er/waypoints.json")).load();
    }

    @Test
    void whatWasAddedIsThereAfterReloadingInTheSameOrder() {
        WaypointStore store = this.store();
        assertTrue(store.add(HOME));
        assertTrue(store.add(MINE));
        assertTrue(store.add(GATE));

        assertEquals(List.of(HOME, MINE, GATE), this.store().all());
        assertEquals(List.of(HOME, MINE), this.store().in(Waypoint.OVERWORLD));
        assertEquals(List.of(GATE), this.store().in("minecraft:the_nether"));
    }

    @Test
    void theSameWaypointIsNotAddedTwice() {
        WaypointStore store = this.store();
        assertTrue(store.add(HOME));
        assertFalse(store.add(new Waypoint("Home", 10, 20, 1)));
        assertEquals(1, store.all().size());
        // The same spot with another name or colour is another waypoint.
        assertTrue(store.add(new Waypoint("Home 2", 10, 20, 1)));
    }

    @Test
    void removingTakesOneOutAndSavesIt() {
        WaypointStore store = this.store();
        store.add(HOME);
        store.add(MINE);
        assertTrue(store.remove(HOME));
        assertFalse(store.remove(HOME));
        assertEquals(List.of(MINE), this.store().all());
    }

    @Test
    void reorderingOneDimensionLeavesTheOthersWhereTheyWere() {
        WaypointStore store = this.store();
        store.add(HOME);
        store.add(GATE);
        store.add(MINE);
        store.add(FARM);

        assertTrue(store.reorder(Waypoint.OVERWORLD, List.of(FARM, HOME, MINE)));
        // The nether's waypoint kept its slot between the overworld's; the overworld's took theirs back in the new order.
        assertEquals(List.of(FARM, GATE, HOME, MINE), this.store().all());
    }

    @Test
    void reorderingWithAnythingButTheSameWaypointsIsRefusedAndChangesNothing() {
        WaypointStore store = this.store();
        store.add(HOME);
        store.add(MINE);
        int version = store.version();

        assertFalse(store.reorder(Waypoint.OVERWORLD, List.of(HOME)));
        assertFalse(store.reorder(Waypoint.OVERWORLD, List.of(HOME, FARM)));
        assertFalse(store.reorder(Waypoint.OVERWORLD, List.of(HOME, HOME)));
        assertEquals(List.of(HOME, MINE), store.all());
        assertEquals(version, store.version());

        // The order it already has is accepted and is not a change.
        assertTrue(store.reorder(Waypoint.OVERWORLD, List.of(HOME, MINE)));
        assertEquals(version, store.version());
    }

    @Test
    void aFileThatIsNotJsonIsTreatedAsEmptyAndTheNextChangeReplacesIt() throws IOException {
        Path file = this.dir.resolve("waypoints.json");
        Files.writeString(file, "this is { not json");
        WaypointStore store = new WaypointStore(file).load();
        assertTrue(store.all().isEmpty());

        store.add(HOME);
        assertEquals(List.of(HOME), new WaypointStore(file).load().all());
    }

    @Test
    void entriesThatAreNotWaypointsAreSkippedAndDuplicatesCollapse() throws IOException {
        Path file = this.dir.resolve("waypoints.json");
        Files.writeString(file, "{\"waypoints\":[{\"name\":\"A\",\"x\":1,\"z\":2,\"color\":3}, 5, {\"name\":\"B\",\"x\":\"bad\"},"
            + "{\"name\":\"A\",\"x\":1,\"z\":2,\"color\":3}]}");
        assertEquals(List.of(new Waypoint("A", 1, 2, 3)), new WaypointStore(file).load().all());
    }
}
