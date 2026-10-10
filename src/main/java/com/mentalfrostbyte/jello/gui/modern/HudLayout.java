package com.mentalfrostbyte.jello.gui.modern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Persistent HUD geometry, independent of Minecraft, windows, textures and input. */
public final class HudLayout {
    private static final String CONFIG_KEY = "modernHudLayout";
    private static final int MAX_POSITIONS = 128;
    private final Map<String, Position> positions = new LinkedHashMap<>();

    private record Position(boolean following, float x, float y) {}

    /** Final GUI rectangle and translation from the widget's original coordinates. */
    public record Rect(float x, float y, float width, float height, float dx, float dy) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        }
    }

    /** Current-frame reservations only; automatic placement never changes a saved position. */
    public static final class Frame {
        private static final float GAP = 6F;
        private static final double EDGE_EPSILON = 0.001;
        private final Map<String, Rect> occupied = new LinkedHashMap<>();

        public void clear() { occupied.clear(); }

        public void reserve(String id, Rect bounds) {
            Objects.requireNonNull(id, "HUD id");
            requireBounds(bounds);
            if (bounds.width == 0F || bounds.height == 0F) occupied.remove(id);
            else occupied.put(id, bounds);
        }

        public Rect place(String id, Rect preferred, float viewportWidth, float viewportHeight, boolean automatic) {
            Objects.requireNonNull(id, "HUD id");
            requireBounds(preferred);
            requireGeometry(preferred.x, preferred.y, preferred.width, preferred.height, viewportWidth, viewportHeight);
            if (!automatic) {
                reserve(id, preferred);
                return preferred;
            }
            float maxX = Math.max(0F, viewportWidth - preferred.width);
            float maxY = Math.max(0F, viewportHeight - preferred.height);
            Rect origin = translated(preferred, clamp(preferred.x, 0F, maxX), clamp(preferred.y, 0F, maxY));
            if (overlap(id, origin) == 0.0) {
                reserve(id, origin);
                return origin;
            }

            // Every free interval begins at a viewport edge or beside a blocker. Enumerating those boundaries
            // finds a stable answer without repeatedly pushing a panel between opposing obstacles.
            TreeSet<Float> xs = new TreeSet<>(), ys = new TreeSet<>();
            candidates(xs, origin.x, maxX);
            candidates(ys, origin.y, maxY);
            for (Map.Entry<String, Rect> entry : occupied.entrySet()) {
                if (id.equals(entry.getKey())) continue;
                Rect obstacle = entry.getValue();
                xs.add(bounded((double) obstacle.x - preferred.width - GAP, maxX));
                xs.add(bounded((double) obstacle.x + obstacle.width + GAP, maxX));
                ys.add(bounded((double) obstacle.y - preferred.height - GAP, maxY));
                ys.add(bounded((double) obstacle.y + obstacle.height + GAP, maxY));
            }
            boolean upward = (double) origin.y + preferred.height * 0.5 > viewportHeight * 0.5;
            Rect best = null;
            for (float y : ys) {
                Rect candidate = translated(preferred, origin.x, y);
                if (overlap(id, candidate) == 0.0 && closer(candidate, best, origin, upward)) best = candidate;
            }
            if (best == null) {
                for (float x : xs) {
                    for (float y : ys) {
                        Rect candidate = translated(preferred, x, y);
                        if (overlap(id, candidate) == 0.0 && closer(candidate, best, origin, upward)) best = candidate;
                    }
                }
            }
            if (best == null) {
                // A panel larger than the available space still gets a finite, repeatable placement. Prefer the
                // least obstructed candidate; equally crowded candidates stay nearest the original anchor.
                xs.add(0F);
                xs.add(maxX);
                ys.add(0F);
                ys.add(maxY);
                for (Map.Entry<String, Rect> entry : occupied.entrySet()) {
                    if (id.equals(entry.getKey())) continue;
                    Rect obstacle = entry.getValue();
                    overlapEdges(xs, obstacle.x, obstacle.width, preferred.width, maxX);
                    overlapEdges(ys, obstacle.y, obstacle.height, preferred.height, maxY);
                }
                best = origin;
                double leastOverlap = overlap(id, origin, 0F);
                for (float x : xs) {
                    for (float y : ys) {
                        Rect candidate = translated(preferred, x, y);
                        double area = overlap(id, candidate, 0F);
                        if (area < leastOverlap || area == leastOverlap && closer(candidate, best, origin, upward)) {
                            best = candidate;
                            leastOverlap = area;
                        }
                    }
                }
            }
            reserve(id, best);
            return best;
        }

        private static void candidates(TreeSet<Float> values, float preferred, float maximum) {
            values.add(preferred);
            float margin = Math.min(GAP, maximum * 0.5F);
            values.add(margin);
            values.add(maximum - margin);
        }

        private static void overlapEdges(TreeSet<Float> values, float start, float length, float size, float maximum) {
            values.add(bounded(start, maximum));
            values.add(bounded((double) start - size, maximum));
            values.add(bounded((double) start + length, maximum));
            values.add(bounded((double) start + length - size, maximum));
        }

        private double overlap(String id, Rect candidate) { return overlap(id, candidate, GAP); }

        private double overlap(String id, Rect candidate, float gap) {
            if (candidate.width == 0F || candidate.height == 0F) return 0.0;
            double total = 0.0;
            for (Map.Entry<String, Rect> entry : occupied.entrySet()) {
                if (id.equals(entry.getKey())) continue;
                Rect obstacle = entry.getValue();
                double width = Math.min((double) candidate.x + candidate.width, (double) obstacle.x + obstacle.width + gap)
                        - Math.max(candidate.x, (double) obstacle.x - gap);
                double height = Math.min((double) candidate.y + candidate.height, (double) obstacle.y + obstacle.height + gap)
                        - Math.max(candidate.y, (double) obstacle.y - gap);
                // Boundary candidates are floats too: scaled dimensions can land a few ULPs inside the gap.
                // A sub-thousandth-pixel sliver must not reject an otherwise exact adjacent slot.
                if (width > EDGE_EPSILON && height > EDGE_EPSILON) total += width * height;
            }
            return total;
        }

        private static boolean closer(Rect candidate, Rect current, Rect origin, boolean upward) {
            if (current == null) return true;
            // Stay in the nearest column, then choose the nearest free vertical slot in that column.
            int horizontal = Double.compare(Math.abs((double) candidate.x - origin.x), Math.abs((double) current.x - origin.x));
            if (horizontal != 0) return horizontal < 0;
            int vertical = Double.compare(Math.abs((double) candidate.y - origin.y), Math.abs((double) current.y - origin.y));
            if (vertical != 0) return vertical < 0;
            if (candidate.y != current.y) return upward ? candidate.y < current.y : candidate.y > current.y;
            return candidate.x < current.x;
        }

        private static Rect translated(Rect preferred, float x, float y) {
            if (x == preferred.x && y == preferred.y) return preferred;
            return new Rect(x, y, preferred.width, preferred.height,
                    finite((double) preferred.dx + x - preferred.x), finite((double) preferred.dy + y - preferred.y));
        }

        private static float bounded(double value, float maximum) { return (float) Math.clamp(value, 0.0, maximum); }
        private static float finite(double value) { return (float) Math.clamp(value, -Float.MAX_VALUE, Float.MAX_VALUE); }

        private static void requireBounds(Rect bounds) {
            Objects.requireNonNull(bounds, "HUD bounds");
            requireGeometry(bounds.x, bounds.y, bounds.width, bounds.height, 0F, 0F);
            if (!Float.isFinite(bounds.dx) || !Float.isFinite(bounds.dy)) throw new IllegalArgumentException("Invalid HUD translation");
        }
    }

    public boolean hasPosition(String id) { return positions.containsKey(id); }
    public boolean reset(String id) { return positions.remove(id) != null; }

    public Rect resolve(String id, float defaultX, float defaultY, float width, float height,
                        float viewportWidth, float viewportHeight, boolean following) {
        requireGeometry(defaultX, defaultY, width, height, viewportWidth, viewportHeight);
        Position position = positions.get(id);
        float x = defaultX, y = defaultY;
        if (position != null && position.following == following) {
            x = following ? defaultX + position.x * viewportWidth : position.x * Math.max(0, viewportWidth - width);
            y = following ? defaultY + position.y * viewportHeight : position.y * Math.max(0, viewportHeight - height);
        }
        x = clamp(x, 0, Math.max(0, viewportWidth - width));
        y = clamp(y, 0, Math.max(0, viewportHeight - height));
        return new Rect(x, y, width, height, x - defaultX, y - defaultY);
    }

    /** Saves the dragged top-left; following widgets retain an offset from their moving anchor. */
    public void move(String id, float x, float y, float defaultX, float defaultY, float width, float height,
                     float viewportWidth, float viewportHeight, boolean following) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid HUD id: " + id);
        requireGeometry(defaultX, defaultY, width, height, viewportWidth, viewportHeight);
        if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Non-finite HUD position");
        x = clamp(x, 0, Math.max(0, viewportWidth - width));
        y = clamp(y, 0, Math.max(0, viewportHeight - height));
        float normalizedX = following ? (x - defaultX) / Math.max(1, viewportWidth)
                : x / Math.max(1, viewportWidth - width);
        float normalizedY = following ? (y - defaultY) / Math.max(1, viewportHeight)
                : y / Math.max(1, viewportHeight - height);
        positions.put(id, new Position(following, clamp(normalizedX, following ? -1 : 0, 1),
                clamp(normalizedY, following ? -1 : 0, 1)));
    }

    public void read(JsonObject config) {
        positions.clear();
        JsonElement element = config.get(CONFIG_KEY);
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (positions.size() >= MAX_POSITIONS) break;
            if (!validId(entry.getKey()) || !entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            try {
                if (!value.has("mode") || !value.get("mode").isJsonPrimitive()
                        || !value.getAsJsonPrimitive("mode").isString()) continue;
                String mode = value.get("mode").getAsString();
                if (!mode.equals("absolute") && !mode.equals("follow")) continue;
                if (!number(value.get("x")) || !number(value.get("y"))) continue;
                float x = value.get("x").getAsFloat(), y = value.get("y").getAsFloat();
                if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
                boolean following = mode.equals("follow");
                positions.put(entry.getKey(), new Position(following,
                        clamp(x, following ? -1 : 0, 1), clamp(y, following ? -1 : 0, 1)));
            } catch (NumberFormatException ignored) {
                // Malformed persisted coordinates leave this widget at its default.
            }
        }
    }

    public void write(JsonObject config) {
        JsonObject saved = new JsonObject();
        positions.forEach((id, position) -> {
            JsonObject value = new JsonObject();
            value.addProperty("mode", position.following ? "follow" : "absolute");
            value.addProperty("x", position.x);
            value.addProperty("y", position.y);
            saved.add(id, value);
        });
        config.add(CONFIG_KEY, saved);
    }

    private static boolean validId(String id) { return id != null && id.matches("[a-z0-9_.-]{1,80}"); }
    private static boolean number(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static void requireGeometry(float x, float y, float w, float h, float sw, float sh) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(w) || !Float.isFinite(h)
                || !Float.isFinite(sw) || !Float.isFinite(sh) || w < 0 || h < 0 || sw < 0 || sh < 0) {
            throw new IllegalArgumentException("Invalid HUD rectangle");
        }
    }
}
