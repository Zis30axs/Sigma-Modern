package com.mentalfrostbyte.jello.setting;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemListSettingTest {
    private static final String SNOWBALL = "minecraft:snowball", EGG = "minecraft:egg", PEARL = "minecraft:ender_pearl";

    private static ItemListSetting whitelist() {
        return new ItemListSetting("Whitelist", "test", List.of(SNOWBALL, EGG), List.of(SNOWBALL, EGG, PEARL));
    }

    @Test void persistedSelectionRoundTripsIncludingIntentionallyEmptyLists() {
        ItemListSetting original = whitelist();
        original.remove(SNOWBALL);
        original.add(PEARL);
        ItemListSetting restored = whitelist();
        assertTrue(restored.fromJson(original.toJson()));
        assertEquals(Set.of(EGG, PEARL), restored.get());
        assertEquals("2 selected", restored.displayLabel());
        assertTrue(restored.fromJson(JsonParser.parseString("[]")));
        assertTrue(restored.get().isEmpty());
        assertEquals("0 selected", restored.displayLabel());
        restored.reset();
        assertEquals(Set.of(SNOWBALL, EGG), restored.get());
    }

    @Test void malformedPersistedEntriesCannotExtendTheTrustedCatalogue() {
        ItemListSetting setting = whitelist();
        assertTrue(setting.fromJson(JsonParser.parseString("[\"minecraft:egg\",\"minecraft:egg\",null,2,{},[],true,\"egg\",\"other:egg\",\"../../secrets\",\"minecraft:diamond\"]")));
        assertEquals(Set.of(EGG), setting.get());
        for (String invalid : List.of("null", "{}", "42", "true", "\"minecraft:snowball\"")) {
            assertFalse(setting.fromJson(JsonParser.parseString(invalid)));
            assertEquals(Set.of(EGG), setting.get());
        }
        assertFalse(setting.fromJson(null));
    }

    @Test void callersCannotMutateStoredDefaultsValuesOrCatalogueWithoutChangeEvents() {
        ArrayList<String> defaults = new ArrayList<>(List.of(SNOWBALL));
        ArrayList<String> catalogue = new ArrayList<>(List.of(SNOWBALL, EGG));
        ItemListSetting setting = new ItemListSetting("test", "", defaults, catalogue);
        defaults.clear();
        catalogue.clear();
        assertEquals(Set.of(SNOWBALL), setting.get());
        assertEquals(List.of(SNOWBALL, EGG), setting.getAllowedIds());
        Set<String> replacement = new HashSet<>(Set.of(EGG));
        setting.set(replacement);
        replacement.add(SNOWBALL);
        assertEquals(Set.of(EGG), setting.get());
        assertThrows(UnsupportedOperationException.class, () -> setting.get().clear());
        assertThrows(UnsupportedOperationException.class, () -> setting.getDefaultValue().clear());
        assertThrows(UnsupportedOperationException.class, () -> setting.getAllowedIds().clear());
    }

    @Test void duplicateEditsDoNotNotifyAndSerializedOrderIsDeterministic() {
        ItemListSetting setting = whitelist();
        AtomicInteger changes = new AtomicInteger();
        setting.onChange(changes::incrementAndGet);
        setting.add(EGG);
        setting.remove(PEARL);
        assertEquals(0, changes.get());
        setting.toggle(SNOWBALL);
        setting.toggle(PEARL);
        setting.toggle(SNOWBALL);
        assertEquals(3, changes.get());
        assertEquals("[\"minecraft:snowball\",\"minecraft:egg\",\"minecraft:ender_pearl\"]", setting.toJson().toString());
    }

    @Test void invalidProgrammaticSelectionsFailFast() {
        ItemListSetting setting = whitelist();
        assertThrows(IllegalArgumentException.class, () -> setting.add("minecraft:diamond"));
        assertThrows(IllegalArgumentException.class, () -> setting.set(Set.of("egg")));
        assertThrows(IllegalArgumentException.class, () -> new ItemListSetting("bad", "", List.of(PEARL), List.of(EGG)));
        assertThrows(IllegalArgumentException.class, () -> new ItemListSetting("bad", "", List.of(), List.of("egg")));
        assertThrows(IllegalArgumentException.class, () -> new ItemListSetting("bad", "", List.of(), List.of()));
        assertEquals(Set.of(SNOWBALL, EGG), setting.get());
    }
}
