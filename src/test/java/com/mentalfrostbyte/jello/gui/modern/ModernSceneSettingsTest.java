package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ModernSceneSettingsTest {

    @Test
    void defaultsToNightAndRoundTripsThroughConfig() {
        ModernSceneSettings settings = new ModernSceneSettings();
        assertEquals(ModernSceneTheme.NIGHT, settings.get());

        settings.set(ModernSceneTheme.SAKURA);
        JsonObject config = new JsonObject();
        settings.write(config);
        assertEquals("SAKURA", config.get("modernScene").getAsString());

        ModernSceneSettings reloaded = new ModernSceneSettings();
        reloaded.read(config);
        assertEquals(ModernSceneTheme.SAKURA, reloaded.get());
    }

    @Test
    void ignoresUnknownOrMalformedSavedValues() {
        ModernSceneSettings settings = new ModernSceneSettings();
        settings.set(ModernSceneTheme.DUSK);

        JsonObject unknown = new JsonObject();
        unknown.addProperty("modernScene", "NOT_A_THEME");
        settings.read(unknown);
        assertEquals(ModernSceneTheme.DUSK, settings.get());

        JsonObject malformed = new JsonObject();
        malformed.add("modernScene", new JsonObject());
        settings.read(malformed);
        assertEquals(ModernSceneTheme.DUSK, settings.get());
    }

    @Test
    void cycleWrapsBothWays() {
        ModernSceneSettings settings = new ModernSceneSettings();
        ModernSceneTheme[] all = ModernSceneTheme.values();
        settings.set(all[all.length - 1]);
        assertEquals(all[0], settings.cycle(1));
        assertEquals(all[all.length - 1], settings.cycle(-1));
    }

    @Test
    void byNameIsLenient() {
        assertEquals(ModernSceneTheme.ARCTIC_DAY, ModernSceneTheme.byName("arctic-day").orElseThrow());
        assertEquals(ModernSceneTheme.BLOOD_MOON, ModernSceneTheme.byName(" Blood Moon ").orElseThrow());
        assertTrue(ModernSceneTheme.byName("").isEmpty());
        assertTrue(ModernSceneTheme.byName(null).isEmpty());
    }
}
