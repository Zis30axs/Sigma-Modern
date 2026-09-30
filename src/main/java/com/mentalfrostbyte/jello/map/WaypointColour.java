package com.mentalfrostbyte.jello.map;

/** The seven marker colours the old client's waypoint dialog offered, in its order. */
public enum WaypointColour {
    GRAY("Gray", -2565928),
    RED("Red", -35477),
    ORANGE("Orange", -17579),
    YELLOW("Yellow", -6310),
    GREEN("Green", -9240708),
    BLUE("Blue", -11491585),
    MAGENTA("Magenta", -2652417);

    public final String label;
    public final int argb;

    WaypointColour(final String label, final int argb) {
        this.label = label;
        this.argb = argb;
    }
}
