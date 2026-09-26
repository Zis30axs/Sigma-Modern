package com.mentalfrostbyte.jello.anticheat.alert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The players the detector has flagged, with their violation level per check: what the name tag and the
 * suspect list show. Kept per world and dropped when the world changes.
 *
 * <p>Read and written from the game thread only.</p>
 */
public final class Suspects {

    /** A level this low is indistinguishable from none. */
    private static final double VISIBLE_LEVEL = 0.05;

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** One flagged player. */
    public static final class Entry {
        private final UUID uuid;
        private String name;
        private final Map<String, Double> levels = new LinkedHashMap<>();
        private long lastAlertNanos = Long.MIN_VALUE;
        private String lastCheck = "";
        private String lastDetail = "";

        private Entry(final UUID uuid, final String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID uuid() {
            return this.uuid;
        }

        public String name() {
            return this.name;
        }

        /** Violation level per check, only those still above zero. */
        public Map<String, Double> levels() {
            Map<String, Double> visible = new LinkedHashMap<>();
            this.levels.forEach((check, level) -> {
                if (level > VISIBLE_LEVEL) {
                    visible.put(check, level);
                }
            });
            return visible;
        }

        public double total() {
            double total = 0.0;
            for (double level : this.levels.values()) {
                total += level > VISIBLE_LEVEL ? level : 0.0;
            }

            return total;
        }

        public long lastAlertNanos() {
            return this.lastAlertNanos;
        }

        public String lastCheck() {
            return this.lastCheck;
        }

        public String lastDetail() {
            return this.lastDetail;
        }
    }

    /** A check's level for a player changed. Players who were never flagged are not added. */
    public void update(final UUID uuid, final String name, final String check, final double level) {
        Entry entry = this.entries.get(uuid);
        if (entry == null) {
            if (level <= VISIBLE_LEVEL) {
                return;
            }

            entry = new Entry(uuid, name);
            this.entries.put(uuid, entry);
        }

        entry.name = name;
        entry.levels.put(check, level);
    }

    /** A player was announced; remembered so the list can say what for and when. */
    public void alerted(final UUID uuid, final String check, final String detail, final long nanos) {
        Entry entry = this.entries.get(uuid);
        if (entry != null) {
            entry.lastAlertNanos = nanos;
            entry.lastCheck = check;
            entry.lastDetail = detail;
        }
    }

    /** The total level across checks, or zero if the player is not a suspect. */
    public double totalLevel(final UUID uuid) {
        Entry entry = this.entries.get(uuid);
        return entry == null ? 0.0 : entry.total();
    }

    /** Suspects still above zero, highest total first. */
    public List<Entry> ranked() {
        List<Entry> ranked = new ArrayList<>();
        for (Entry entry : this.entries.values()) {
            if (entry.total() > 0.0) {
                ranked.add(entry);
            }
        }

        ranked.sort(Comparator.comparingDouble(Entry::total).reversed());
        return ranked;
    }

    public void remove(final UUID uuid) {
        this.entries.remove(uuid);
    }

    public void clear() {
        this.entries.clear();
    }
}
