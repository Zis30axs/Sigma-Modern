package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** An immutable selection from a fixed, trusted catalogue of item or block identifiers. */
public final class ItemListSetting extends Setting<Set<String>> {
    private enum Source { ITEMS, BLOCKS, FIXED }

    private final Source source;
    // Non-null only for Source.FIXED; registry sources resolve lazily after Minecraft bootstraps.
    private final List<String> allowedIds;

    public ItemListSetting(final String name, final String description) {
        this(name, description, Source.ITEMS);
    }

    private ItemListSetting(final String name, final String description, final Source source) {
        super(name, description, Set.of());
        this.source = source;
        this.allowedIds = null;
    }

    public static ItemListSetting blocks(final String name, final String description) {
        return new ItemListSetting(name, description, Source.BLOCKS);
    }

    private static final class RegistryCatalogue {
        static final List<String> IDS = BuiltInRegistries.ITEM.keySet().stream()
                .map(Identifier::toString).filter(id -> !id.equals("minecraft:air")).sorted().toList();
        static final Set<String> LOOKUP = Set.copyOf(IDS);
    }

    private static final class BlockRegistryCatalogue {
        private static final Set<String> AIR_IDS = Set.of(
                "minecraft:air", "minecraft:cave_air", "minecraft:void_air");
        static final List<String> IDS = BuiltInRegistries.BLOCK.keySet().stream()
                .map(Identifier::toString).filter(id -> !AIR_IDS.contains(id)).sorted().toList();
        static final Set<String> LOOKUP = Set.copyOf(IDS);
    }

    public static List<String> registeredItems() { return RegistryCatalogue.IDS; }
    public static boolean registeredItem(String id) { return RegistryCatalogue.LOOKUP.contains(id); }

    public static List<String> registeredBlocks() { return BlockRegistryCatalogue.IDS; }
    public static boolean registeredBlock(String id) { return BlockRegistryCatalogue.LOOKUP.contains(id); }

    public ItemListSetting(final String name, final String description, final Collection<String> defaultIds,
                           final Collection<String> allowedIds) {
        super(name, description, checkedSelection(defaultIds, checkedCatalogue(allowedIds)));
        this.source = Source.FIXED;
        this.allowedIds = checkedCatalogue(allowedIds);
    }

    public List<String> getAllowedIds() {
        return switch (this.source) {
            case ITEMS -> registeredItems();
            case BLOCKS -> registeredBlocks();
            case FIXED -> this.allowedIds;
        };
    }

    private boolean isAllowed(String id) {
        return switch (this.source) {
            case ITEMS -> registeredItem(id);
            case BLOCKS -> registeredBlock(id);
            case FIXED -> this.allowedIds.contains(id);
        };
    }

    public boolean contains(final String id) { return this.get().contains(id); }
    public String displayLabel() { return this.get().size() + " selected"; }

    public void add(final String id) {
        if (!this.isAllowed(id)) throw new IllegalArgumentException("Item is outside this setting's catalogue: " + id);
        Set<String> next = new LinkedHashSet<>(this.get());
        next.add(id);
        this.set(next);
    }

    public void remove(final String id) {
        Set<String> next = new LinkedHashSet<>(this.get());
        next.remove(id);
        this.set(next);
    }

    public void toggle(final String id) {
        if (this.contains(id)) this.remove(id);
        else this.add(id);
    }

    @Override protected Set<String> sanitise(final Set<String> value) {
        return checkedSelection(value, this.getAllowedIds());
    }

    @Override public JsonElement toJson() {
        JsonArray values = new JsonArray();
        this.get().forEach(values::add);
        return values;
    }

    @Override protected Optional<Set<String>> parse(final JsonElement element) {
        if (element == null || !element.isJsonArray()) return Optional.empty();
        Set<String> selection = new LinkedHashSet<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()
                    && this.isAllowed(entry.getAsString())) selection.add(entry.getAsString());
        }
        return Optional.of(selection);
    }

    private static List<String> checkedCatalogue(final Collection<String> ids) {
        Objects.requireNonNull(ids, "allowedIds");
        Set<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            Objects.requireNonNull(id, "item identifier");
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || !id.equals(parsed.toString())) throw new IllegalArgumentException("Expected a namespaced item identifier: " + id);
            unique.add(id);
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("An item catalogue cannot be empty");
        return List.copyOf(unique);
    }

    private static Set<String> checkedSelection(final Collection<String> selected, final List<String> catalogue) {
        Objects.requireNonNull(selected, "selected");
        for (String id : selected) {
            if (!catalogue.contains(Objects.requireNonNull(id, "selected item"))) throw new IllegalArgumentException("Item is outside this setting's catalogue: " + id);
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : catalogue) if (selected.contains(id)) ordered.add(id);
        return Collections.unmodifiableSet(ordered);
    }
}
