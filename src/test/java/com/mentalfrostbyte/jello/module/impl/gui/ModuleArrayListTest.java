package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.gui.modern.ModernStyle;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
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

    /** Switched on untouched, the list must look like the one SigmaModern's HUD always drew. */
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
        assertEquals("Night Vision", ModuleArrayList.words("NIGHT_VISION"));
        assertEquals("Sigma", ModuleArrayList.words("SIGMA"));
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
