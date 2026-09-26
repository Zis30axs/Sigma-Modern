package com.mentalfrostbyte.jello.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** How {@link ModuleConfig#read} decides a module's on/off state, in particular for modules that start on. */
class ModuleConfigTest {

    private final ModuleManager modules = new ModuleManager();

    private final StartsOn startsOn = new StartsOn();

    private final StartsOff startsOff = new StartsOff();

    ModuleConfigTest() {
        this.modules.register(this.startsOn);
        this.modules.register(this.startsOff);
    }

    @AfterEach
    void switchEverythingOff() {
        this.modules.all().forEach(module -> module.setEnabled(false));
    }

    @Test
    void aFreshConfigPutsEveryModuleInItsDefaultState() {
        ModuleConfig.read(new JsonObject(), this.modules);
        assertTrue(this.startsOn.isEnabled());
        assertFalse(this.startsOff.isEnabled());
    }

    @Test
    void aConfigWrittenBeforeAModuleExistedStillSwitchesItOn() {
        ModuleConfig.read(json("{\"modules\": {\"StartsOff\": {\"enabled\": true}}}"), this.modules);
        assertTrue(this.startsOn.isEnabled());
        assertTrue(this.startsOff.isEnabled());
    }

    @Test
    void aSavedStateWinsOverTheDefault() {
        ModuleConfig.read(json("{\"modules\": {\"startson\": {\"enabled\": false}}}"), this.modules);
        assertFalse(this.startsOn.isEnabled(), "looked up case-insensitively, like any module name");
    }

    @Test
    void anUnusableSavedStateFallsBackToTheDefault() {
        ModuleConfig.read(json("{\"modules\": {\"StartsOn\": {\"enabled\": \"no\"}, \"StartsOff\": 3}}"), this.modules);
        assertTrue(this.startsOn.isEnabled());
        assertFalse(this.startsOff.isEnabled());

        ModuleConfig.read(json("{\"modules\": []}"), this.modules);
        assertTrue(this.startsOn.isEnabled());
    }

    @Test
    void settingsAreInPlaceBeforeAModuleIsSwitchedOnEvenByDefault() {
        ModuleConfig.read(json("{\"modules\": {\"StartsOn\": {\"settings\": {\"Level\": 7}}}}"), this.modules);
        assertTrue(this.startsOn.isEnabled());
        assertEquals(7F, this.startsOn.levelAtEnable);
    }

    @Test
    void whatIsWrittenReadsBackTheSame() {
        this.startsOn.setEnabled(false);
        this.startsOff.setEnabled(true);
        JsonObject root = new JsonObject();
        ModuleConfig.write(root, this.modules);
        this.switchEverythingOff();

        ModuleConfig.read(root, this.modules);
        assertFalse(this.startsOn.isEnabled());
        assertTrue(this.startsOff.isEnabled());
    }

    private static JsonObject json(final String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final class StartsOn extends Module {
        private final NumberSetting level = this.register(new NumberSetting("Level", "test", 1F, 0F, 10F, 1F));

        float levelAtEnable = Float.NaN;

        StartsOn() {
            super(ModuleCategory.INTERFACE, "StartsOn", "test");
        }

        @Override
        public boolean isEnabledByDefault() {
            return true;
        }

        @Override
        protected void onEnable() {
            this.levelAtEnable = this.level.get();
        }
    }

    private static final class StartsOff extends Module {
        StartsOff() {
            super(ModuleCategory.MISC, "StartsOff", "test");
        }
    }
}
