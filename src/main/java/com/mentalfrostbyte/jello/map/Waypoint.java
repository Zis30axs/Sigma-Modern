package com.mentalfrostbyte.jello.map;

import com.google.gson.JsonObject;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A named place on the map: a spot (x and z - the old client's waypoints had no height), a colour for its marker, and the
 * dimension it was made in, which is the map it appears on.
 *
 * <p>Read from and written to the old client's {@code waypoints.json} entries ({@code name}, {@code color}, {@code x},
 * {@code z}); {@code dim} is new and left out for the overworld, so a file the old client wrote reads as it always did.</p>
 */
public record Waypoint(String name, int x, int z, int color, String dimension) {
    public static final String OVERWORLD = "minecraft:overworld";
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    public Waypoint {
        name = name == null ? "" : name;
        dimension = dimension == null || dimension.isBlank() ? OVERWORLD : dimension;
    }

    public Waypoint(final String name, final int x, final int z, final int color) {
        this(name, x, z, color, OVERWORLD);
    }

    /**
     * One entry of the file, or {@code null} for one that is not a waypoint at all. A missing field takes the old client's
     * default (a name of nothing, the spot 0, a grey marker) rather than dropping a place someone saved.
     */
    public static @Nullable Waypoint fromJson(final JsonObject json) {
        try {
            String name = json.has("name") ? json.get("name").getAsString() : "";
            int color = json.has("color") ? json.get("color").getAsInt() : WaypointColour.GRAY.argb;
            int x = json.has("x") ? json.get("x").getAsInt() : 0;
            int z = json.has("z") ? json.get("z").getAsInt() : 0;
            String dimension = json.has("dim") ? json.get("dim").getAsString() : OVERWORLD;
            return new Waypoint(name, x, z, color, dimension);
        } catch (RuntimeException notAWaypoint) {
            return null;
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", this.name);
        json.addProperty("color", this.color);
        json.addProperty("x", this.x);
        json.addProperty("z", this.z);
        if (!OVERWORLD.equals(this.dimension)) {
            json.addProperty("dim", this.dimension);
        }

        return json;
    }

    /**
     * The spot a coordinates box says: two whole numbers ("120 -45", or with a comma) or three, as F3 writes them
     * ("120 64 -45" - the height is dropped). Anything else is {@code null}, and the caller keeps the spot it had.
     */
    public static int @Nullable [] parseCoordinates(final @Nullable String text) {
        if (text == null) {
            return null;
        }

        String[] parts = text.strip().split("[\\s,]+");
        if (parts.length != 2 && parts.length != 3) {
            return null;
        }

        for (String part : parts) {
            if (!INTEGER.matcher(part).matches()) {
                return null;
            }
        }

        try {
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[parts.length - 1]);
            return new int[]{x, z};
        } catch (NumberFormatException tooBig) {
            return null;
        }
    }
}
