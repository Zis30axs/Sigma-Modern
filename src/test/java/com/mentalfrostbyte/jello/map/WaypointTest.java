package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class WaypointTest {

    @Test
    void anEntryTheOldClientWroteReadsAsAnOverworldWaypoint() {
        JsonObject old = JsonParser.parseString("{\"name\":\"Home\",\"color\":-35477,\"x\":120,\"z\":-45}").getAsJsonObject();
        Waypoint waypoint = Waypoint.fromJson(old);
        assertEquals(new Waypoint("Home", 120, -45, WaypointColour.RED.argb, Waypoint.OVERWORLD), waypoint);
    }

    @Test
    void theOverworldIsLeftOutOfTheFileSoTheOldClientStillReadsIt() {
        JsonObject overworld = new Waypoint("Home", 1, 2, 3).toJson();
        assertFalse(overworld.has("dim"));
        assertEquals(4, overworld.size());

        Waypoint nether = new Waypoint("Gate", 5, 6, 7, "minecraft:the_nether");
        assertEquals(nether, Waypoint.fromJson(nether.toJson()));
        assertEquals("minecraft:the_nether", nether.toJson().get("dim").getAsString());
    }

    @Test
    void aMissingFieldTakesTheOldDefaultAndAnEntryThatIsNotAWaypointIsSkipped() {
        Waypoint bare = Waypoint.fromJson(new JsonObject());
        assertNotNull(bare);
        assertEquals(new Waypoint("", 0, 0, WaypointColour.GRAY.argb), bare);

        JsonObject wrong = JsonParser.parseString("{\"name\":\"x\",\"x\":\"not a number\"}").getAsJsonObject();
        assertNull(Waypoint.fromJson(wrong));
    }

    @Test
    void coordinatesAreTwoWholeNumbersOrThreeAsF3WritesThem() {
        assertArrayEquals(new int[]{120, -45}, Waypoint.parseCoordinates("120 -45"));
        assertArrayEquals(new int[]{120, -45}, Waypoint.parseCoordinates("  120,  -45 "));
        assertArrayEquals(new int[]{120, -45}, Waypoint.parseCoordinates("120 64 -45"));
        assertNull(Waypoint.parseCoordinates("120"));
        assertNull(Waypoint.parseCoordinates("120 north"));
        assertNull(Waypoint.parseCoordinates("12.5 3"));
        assertNull(Waypoint.parseCoordinates("1 2 3 4"));
        assertNull(Waypoint.parseCoordinates("99999999999 1"));
        assertNull(Waypoint.parseCoordinates(null));
    }
}
