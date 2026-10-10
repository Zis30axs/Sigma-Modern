package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.config.ModuleConfig;
import com.mentalfrostbyte.jello.gui.modern.ModernStyle;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModuleArrayListTest {

    private final List<Module> switchedOn = new ArrayList<>();

    @AfterEach
    void switchEverythingOff() {
        this.switchedOn.forEach(module -> module.setEnabled(false));
    }

    @Test
    void isAnInterfaceModuleThatTheConfigSwitchesOnByDefault() {
        ModuleArrayList list = new ModuleArrayList();
        assertFalse(list.isEnabled(), "on by default is the config's doing, not the constructor's");
        assertTrue(list.isEnabledByDefault());
        assertEquals(ModuleCategory.INTERFACE, list.getCategory());
        assertEquals("ArrayList", list.getName());
    }

    /** Adding new effects must not change the defaults of the existing text and layout choices. */
    @Test
    void defaultsReproduceTheOldList() {
        ModuleArrayList list = new ModuleArrayList();
        assertEquals(ModuleArrayList.Corner.TOP_RIGHT, list.getPosition());
        assertEquals(ModuleArrayList.DEFAULT_FONT_SIZE, list.getFontSize());
        assertEquals(0, list.getSpacing());
        assertEquals(0F, list.getBackground());
        assertFalse(list.hasAccentBar());
        assertEquals(ModuleArrayList.ColorMode.STATIC, list.getColorMode());
        assertEquals(ModernStyle.TEXT, list.getColor());
        assertTrue(list.hasTextShadow());
        assertNull(list.suffixOf(new Probe("Speed", ModuleCategory.MOVEMENT, true)), "no suffix until asked for");
    }

    @Test
    void listsEveryOtherSwitchedOnModuleByNameIgnoringCase() {
        ModuleArrayList list = this.on(new ModuleArrayList());
        Module zeta = this.on(new Probe("Zeta", ModuleCategory.MISC, false));
        Module alpha = this.on(new Probe("alpha", ModuleCategory.MOVEMENT, false));
        Module beta = this.on(new Probe("Beta", ModuleCategory.RENDER, false));
        Module off = new Probe("Off", ModuleCategory.MISC, false);

        assertEquals(List.of(alpha, beta, zeta), list.listed(List.of(zeta, list, off, beta, alpha), module -> 0),
                "switched-off modules and the list itself are left out");
    }

    @Test
    void lengthPutsTheWidestLineFirstAndBreaksTiesByName() {
        ModuleArrayList list = new ModuleArrayList();
        this.choose(list, "Sort", ModuleArrayList.Order.LENGTH);
        Module shortName = this.on(new Probe("Ab", ModuleCategory.MISC, false));
        Module longName = this.on(new Probe("Abcdef", ModuleCategory.MISC, false));
        Module tieB = this.on(new Probe("Bcd", ModuleCategory.MISC, false));
        Module tieA = this.on(new Probe("Acd", ModuleCategory.MISC, false));

        assertEquals(List.of(longName, tieA, tieB, shortName),
                list.listed(List.of(shortName, tieB, longName, tieA), module -> module.getName().length()));
    }

    @Test
    void hideVisualsLeavesOutRenderAndInterfaceModules() {
        ModuleArrayList list = new ModuleArrayList();
        Module render = this.on(new Probe("Render", ModuleCategory.RENDER, false));
        Module chat = this.on(new Probe("Chat", ModuleCategory.INTERFACE, false));
        Module speed = this.on(new Probe("Speed", ModuleCategory.MOVEMENT, false));
        assertEquals(3, list.listed(List.of(render, chat, speed), module -> 0).size());

        ((BooleanSetting) list.setting("Hide Visuals").orElseThrow()).set(true);
        assertEquals(List.of(speed), list.listed(List.of(render, chat, speed), module -> 0));
    }

    @Test
    void theSuffixIsTheFirstChoiceWordedForReading() {
        ModuleArrayList list = new ModuleArrayList();
        ((BooleanSetting) list.setting("Suffix").orElseThrow()).set(true);
        assertEquals("Legit Hop", list.suffixOf(new Probe("Speed", ModuleCategory.MOVEMENT, true)));
        assertNull(list.suffixOf(new Probe("NoHurtCam", ModuleCategory.RENDER, false)), "nothing to name");
    }

    @Test
    void colorIsOnlyOfferedWhileItIsUsed() {
        ModuleArrayList list = new ModuleArrayList();
        var color = list.setting("Color").orElseThrow();
        assertTrue(color.isVisible());
        this.choose(list, "Color Mode", ModuleArrayList.ColorMode.WAVE);
        assertTrue(color.isVisible());
        this.choose(list, "Color Mode", ModuleArrayList.ColorMode.RAINBOW);
        assertFalse(color.isVisible());
    }

    /** Jello and Classic draw every module, on or off, and fade a line out as its module is switched off. */
    @Test
    void theOldListsNameEveryModuleButTheInterfaceOnesWhetherOnOrOff() {
        ModuleArrayList list = new ModuleArrayList();
        Module speed = new Probe("Speed", ModuleCategory.MOVEMENT, true);
        Module render = new Probe("Render", ModuleCategory.RENDER, false);
        Module chat = new Probe("Chat", ModuleCategory.INTERFACE, false);

        assertTrue(list.lists(speed), "off is still listed: its line is on its way out");
        assertTrue(list.lists(render));
        assertFalse(list.lists(chat), "the old GUI category was never listed");
        assertFalse(list.lists(list), "nor is the list itself");

        ((BooleanSetting) list.setting("Hide Visuals").orElseThrow()).set(true);
        assertFalse(list.lists(render));
        assertTrue(list.lists(speed));
    }

    @Test
    void classicShowsTheModeWhateverTheSuffixSettingSays() {
        ModuleArrayList list = new ModuleArrayList();
        Module speed = new Probe("Speed", ModuleCategory.MOVEMENT, true);
        assertNull(list.suffixOf(speed));
        assertEquals("Legit Hop", ModuleArrayList.modeOf(speed));
        assertNull(ModuleArrayList.modeOf(new Probe("NoHurtCam", ModuleCategory.RENDER, false)));
    }

    @Test
    void theOldStylesStartWhereTheOldClientHadThem() {
        ModuleArrayList list = new ModuleArrayList();
        assertEquals(ModuleArrayList.Size.NORMAL, list.getSize());
        assertEquals(20F, list.getSize().points());
        assertEquals(18F, ModuleArrayList.Size.SMALL.points());
        assertEquals(14F, ModuleArrayList.Size.TINY.points());
        assertEquals(ModuleArrayList.Outline.ALL, list.getOutline());
        assertEquals(ModuleArrayList.Transition.SMOOTH, list.getTransition());
    }

    @Test
    void olderProfilesKeepTheirExistingChoicesAndGainOnlyTheNewEffectDefaults() {
        ModuleArrayList list = new ModuleArrayList();
        ModuleManager modules = this.registry(list);
        ModuleConfig.read(JsonParser.parseString("""
                {"modules":{"ArrayList":{"enabled":false,"settings":{
                  "Position":"BOTTOM_RIGHT","Font Size":14,"Spacing":3,"Background":0.45,
                  "Accent Bar":true,"Color Mode":"WAVE","Text Shadow":false,"Animations":false
                }}}}
                """).getAsJsonObject(), modules);

        assertFalse(list.isEnabled(), "the saved module switch still wins over the default");
        assertEquals(ModuleArrayList.Corner.BOTTOM_RIGHT, list.getPosition());
        assertEquals(14F, list.getFontSize());
        assertEquals(3, list.getSpacing());
        assertEquals(0.45F, list.getBackground());
        assertTrue(list.hasAccentBar());
        assertEquals(ModuleArrayList.ColorMode.WAVE, list.getColorMode());
        assertFalse(list.hasTextShadow());
        assertFalse(list.isAnimated());
        assertTrue(list.hasBlur());
        assertEquals(0.8F, list.getBlurStrength());
        assertTrue(list.hasBloom());
        assertEquals(0.35F, list.getBloomStrength());
        assertTrue(list.setting("Blur Strength").orElseThrow().isVisible());
        assertTrue(list.setting("Bloom Strength").orElseThrow().isVisible());
    }

    @Test
    void switchedOffEffectsKeepTheirStrengthsThroughARealConfigRoundTrip() {
        ModuleArrayList original = new ModuleArrayList();
        ModuleManager originalModules = this.registry(original);
        ModuleConfig.read(JsonParser.parseString("""
                {"modules":{"ArrayList":{"enabled":false,"settings":{
                  "Blur":false,"Blur Strength":0.2,"Bloom":false,"Bloom Strength":0.65,
                  "Background":0,"Font Size":13
                }}}}
                """).getAsJsonObject(), originalModules);
        assertFalse(original.setting("Blur Strength").orElseThrow().isVisible());
        assertFalse(original.setting("Bloom Strength").orElseThrow().isVisible());
        JsonObject written = new JsonObject();
        ModuleConfig.write(written, originalModules);

        ModuleArrayList restored = new ModuleArrayList();
        ModuleConfig.read(JsonParser.parseString(written.toString()).getAsJsonObject(), this.registry(restored));
        assertFalse(restored.isEnabled());
        assertFalse(restored.hasBlur());
        assertFalse(restored.hasBloom());
        assertEquals(0.2F, restored.getBlurStrength(), "hidden settings are still persisted");
        assertEquals(0.65F, restored.getBloomStrength());
        assertEquals(0F, restored.getBackground());
        assertEquals(13F, restored.getFontSize());
        assertFalse(restored.setting("Blur Strength").orElseThrow().isVisible());
        assertFalse(restored.setting("Bloom Strength").orElseThrow().isVisible());

        ((BooleanSetting) restored.setting("Blur").orElseThrow()).set(true);
        ((BooleanSetting) restored.setting("Bloom").orElseThrow()).set(true);
        assertTrue(restored.setting("Blur Strength").orElseThrow().isVisible());
        assertTrue(restored.setting("Bloom Strength").orElseThrow().isVisible());
        assertEquals(0.2F, restored.getBlurStrength());
        assertEquals(0.65F, restored.getBloomStrength());
    }

    @Test
    void handEditedEffectStrengthsStayInsideTheirSupportedRange() {
        ModuleArrayList list = new ModuleArrayList();
        ModuleManager modules = this.registry(list);
        ModuleConfig.read(JsonParser.parseString("""
                {"modules":{"ArrayList":{"enabled":false,"settings":{
                  "Blur Strength":9,"Bloom Strength":-2
                }}}}
                """).getAsJsonObject(), modules);
        assertEquals(1F, list.getBlurStrength());
        assertEquals(0F, list.getBloomStrength());
        assertEquals(0F, list.getBackground(), "new effect settings must not create an opaque background by default");
    }

    private ModuleManager registry(final ModuleArrayList list) {
        ModuleManager modules = new ModuleManager();
        modules.register(list);
        this.switchedOn.add(list);
        return modules;
    }

    private <M extends Module> M on(final M module) {
        module.setEnabled(true);
        this.switchedOn.add(module);
        return module;
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> void choose(final ModuleArrayList list, final String name, final E value) {
        ((EnumSetting<E>) list.setting(name).orElseThrow()).set(value);
    }

    private enum Mode { LEGIT_HOP, YPORT }

    private static final class Probe extends Module {
        Probe(final String name, final ModuleCategory category, final boolean withMode) {
            super(category, name, "test");
            this.register(new BooleanSetting("Flag", "a setting that is not a choice", false));
            if (withMode) {
                this.register(new EnumSetting<>("Mode", "test", Mode.LEGIT_HOP));
            }
        }
    }
}
