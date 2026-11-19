package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Keys are vanilla player menu slots. An absent rule leaves the slot available for storage. */
public final class InventoryLayoutSetting extends Setting<Map<Integer, String>> {
    private static final Map<Integer, String> DEFAULTS = Map.of(36, "WEAPON", 37, "BOW", 38, "PICKAXE",
            39, "AXE", 41, "POTION", 42, "FOOD", 43, "BLOCK", 44, "BLOCK", 45, "SHIELD");

    public InventoryLayoutSetting() {
        super("Slot Layout", "Assign an item or category to a backpack, hotbar or offhand slot.", DEFAULTS);
    }

    public void assign(int slot, String rule) {
        if (slot < 9 || slot > 45) throw new IllegalArgumentException("Invalid inventory slot " + slot);
        var next = new LinkedHashMap<>(get());
        if (rule.equals("NONE")) next.remove(slot);
        else next.put(slot, rule);
        set(next);
    }

    private static boolean valid(String rule) {
        return !rule.equals("NONE") && (InventoryRole.byKey(rule) != null || ItemListSetting.registeredItem(rule));
    }

    @Override protected Map<Integer, String> sanitise(Map<Integer, String> rules) {
        for (var entry : rules.entrySet()) {
            if (entry.getKey() < 9 || entry.getKey() > 45 || !valid(entry.getValue()))
                throw new IllegalArgumentException("Invalid inventory assignment " + entry);
        }
        return Map.copyOf(rules);
    }

    @Override public JsonElement toJson() {
        var json = new JsonObject();
        get().forEach((slot, rule) -> json.addProperty(slot.toString(), rule));
        return json;
    }

    @Override protected Optional<Map<Integer, String>> parse(JsonElement json) {
        if (json == null || !json.isJsonObject()) return Optional.empty();
        var result = new LinkedHashMap<Integer, String>();
        for (var entry : json.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) continue;
            try {
                int slot = Integer.parseInt(entry.getKey());
                String rule = entry.getValue().getAsString();
                if (slot >= 9 && slot <= 45 && valid(rule)) result.put(slot, rule);
            } catch (NumberFormatException ignored) { }
        }
        return Optional.of(result);
    }
}
