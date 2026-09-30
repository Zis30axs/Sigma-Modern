package com.mentalfrostbyte.jello.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.util.io.JsonFileUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One world's waypoints: their order (which is the order of the list beside the map) and the file they live in.
 *
 * <p>Every change is written straight away - the list is a handful of entries - so nothing is lost if the game is closed
 * without leaving the world. A file that cannot be read is treated as empty rather than failing, like the client's other
 * saved state; the next change then replaces it.</p>
 */
public final class WaypointStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Waypoints");

    private final Path file;
    private final List<Waypoint> waypoints = new ArrayList<>();
    private int version;

    public WaypointStore(final Path file) {
        this.file = file;
    }

    /** Reads the file: a missing or unreadable one leaves the list empty, an entry that is not a waypoint is skipped. */
    public WaypointStore load() {
        this.waypoints.clear();
        JsonObject root = JsonFileUtil.read(this.file);
        if (root.has("waypoints") && root.get("waypoints").isJsonArray()) {
            for (JsonElement entry : root.getAsJsonArray("waypoints")) {
                Waypoint waypoint = entry.isJsonObject() ? Waypoint.fromJson(entry.getAsJsonObject()) : null;
                if (waypoint != null && !this.waypoints.contains(waypoint)) {
                    this.waypoints.add(waypoint);
                }
            }
        }

        this.version++;
        return this;
    }

    /** Counts changes, so a view can tell its copy of the list has gone stale. */
    public int version() {
        return this.version;
    }

    /** All of them, in order. */
    public List<Waypoint> all() {
        return List.copyOf(this.waypoints);
    }

    /** The ones on {@code dimension}'s map, in order. */
    public List<Waypoint> in(final String dimension) {
        List<Waypoint> here = new ArrayList<>();
        for (Waypoint waypoint : this.waypoints) {
            if (waypoint.dimension().equals(dimension)) {
                here.add(waypoint);
            }
        }

        return here;
    }

    /** Adds one at the end of the list; {@code false} when that very waypoint is already there. */
    public boolean add(final Waypoint waypoint) {
        if (this.waypoints.contains(waypoint)) {
            return false;
        }

        this.waypoints.add(waypoint);
        this.changed();
        return true;
    }

    public boolean remove(final Waypoint waypoint) {
        if (!this.waypoints.remove(waypoint)) {
            return false;
        }

        this.changed();
        return true;
    }

    /**
     * Puts {@code dimension}'s waypoints in {@code ordered}'s order. The others keep their places: the waypoints of this
     * dimension take back the slots they held, in the new order. Refused - and nothing changed - unless {@code ordered} is
     * exactly this dimension's waypoints.
     */
    public boolean reorder(final String dimension, final List<Waypoint> ordered) {
        List<Waypoint> here = this.in(dimension);
        Set<Waypoint> given = new HashSet<>(ordered);
        if (ordered.size() != here.size() || given.size() != ordered.size() || !given.containsAll(here)) {
            return false;
        }

        if (here.equals(ordered)) {
            return true;
        }

        int next = 0;
        for (int i = 0; i < this.waypoints.size(); i++) {
            if (this.waypoints.get(i).dimension().equals(dimension)) {
                this.waypoints.set(i, ordered.get(next++));
            }
        }

        this.changed();
        return true;
    }

    private void changed() {
        this.version++;
        this.save();
    }

    private void save() {
        JsonArray array = new JsonArray();
        for (Waypoint waypoint : this.waypoints) {
            array.add(waypoint.toJson());
        }

        JsonObject root = new JsonObject();
        root.add("waypoints", array);
        try {
            JsonFileUtil.write(this.file, root);
        } catch (IOException failure) {
            LOGGER.warn("Could not save the waypoints to {}", this.file, failure);
        }
    }
}
