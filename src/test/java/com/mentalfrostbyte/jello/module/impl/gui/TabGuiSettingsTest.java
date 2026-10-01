package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui.Layout;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui.Nav;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What a presentation can ask of the TabGUI: its own categories and, for Classic, a level of settings. */
class TabGuiSettingsTest {

    private static final Layout WITH_SETTINGS = new Layout(List.of(ModuleCategory.MOVEMENT, ModuleCategory.RENDER), true);

    private final ModuleManager modules = new ModuleManager();

    private final TabGui tab = new TabGui(this.modules);

    private final Tunable tunable = new Tunable();

    private final Plain plain = new Plain();

    TabGuiSettingsTest() {
        this.modules.register(this.tab);
        this.modules.register(this.tunable);
        this.modules.register(this.plain);
    }

    @AfterEach
    void switchEverythingOff() {
        this.modules.all().forEach(module -> module.setEnabled(false));
    }

    @Test
    void aLayoutChoosesTheCategoriesAndTheirOrder() {
        this.tab.markShown(new Layout(List.of(ModuleCategory.RENDER, ModuleCategory.MOVEMENT, ModuleCategory.COMBAT), false));
        assertEquals(List.of(ModuleCategory.RENDER, ModuleCategory.MOVEMENT), this.tab.categories(),
                "Combat has nothing to offer, so it is left out");

        this.tab.markShown();
        assertEquals(List.of(ModuleCategory.MOVEMENT, ModuleCategory.RENDER), this.tab.categories(), "back to the usual order");
    }

    @Test
    void withoutASettingsLevelRightSwitchesTheModule() {
        this.tab.markShown();
        this.tab.press(Nav.RIGHT);
        this.tab.press(Nav.RIGHT);

        assertTrue(this.tunable.isEnabled());
        assertFalse(this.tab.inSettings());
    }

    @Test
    void withOneRightStepsIntoTheSettingsAndEnterStillSwitchesTheModule() {
        this.tab.markShown(WITH_SETTINGS);
        this.tab.press(Nav.RIGHT);
        this.tab.press(Nav.RIGHT);
        assertTrue(this.tab.inSettings());
        assertFalse(this.tunable.isEnabled(), "Right only stepped in");

        this.tab.press(Nav.LEFT);
        assertFalse(this.tab.inSettings());
        assertTrue(this.tab.isOpen(), "Left goes back one level at a time");

        this.tab.press(Nav.ENTER);
        assertTrue(this.tunable.isEnabled());
    }

    @Test
    void aModuleWithNoSettingsHasNothingToStepInto() {
        this.tab.markShown(WITH_SETTINGS);
        this.tab.press(Nav.DOWN);
        this.tab.press(Nav.RIGHT);
        assertTrue(this.tab.isOpen());
        assertEquals(List.of(this.plain), this.tab.modules(ModuleCategory.RENDER));

        this.tab.press(Nav.RIGHT);
        assertFalse(this.tab.inSettings());
        assertFalse(this.plain.isEnabled(), "and Right does not switch it either");
    }

    @Test
    void upAndDownPickASettingUntilRightStartsEditingIt() {
        this.enterSettings();
        assertEquals(0, this.tab.selectedSetting());

        this.tab.press(Nav.DOWN);
        assertEquals(1, this.tab.selectedSetting());
        this.tab.press(Nav.UP);
        this.tab.press(Nav.UP);
        assertEquals(3, this.tab.selectedSetting(), "wraps through the four settings");
        assertFalse(this.tab.isEditing());
    }

    @Test
    void editingANumberUpRaisesItAndDownLowersItWithinItsRange() {
        this.enterSettings();
        this.tab.press(Nav.RIGHT);
        assertTrue(this.tab.isEditing());

        this.tab.press(Nav.UP);
        assertEquals(3.5F, this.tunable.reach.get());
        this.tab.press(Nav.DOWN);
        this.tab.press(Nav.DOWN);
        assertEquals(2.5F, this.tunable.reach.get());
        for (int i = 0; i < 20; i++) {
            this.tab.press(Nav.DOWN);
        }
        assertEquals(0.0F, this.tunable.reach.get(), "stops at the bottom of the range");

        this.tab.press(Nav.LEFT);
        assertFalse(this.tab.isEditing());
        assertTrue(this.tab.inSettings(), "the first Left only stops editing");
        assertTrue(this.tab.press(Nav.DOWN));
        assertEquals(1, this.tab.selectedSetting());
    }

    @Test
    void editingASwitchFlipsItAndEditingAChoiceMovesThroughItsOptions() {
        this.enterSettings();
        this.tab.press(Nav.DOWN);
        this.tab.press(Nav.RIGHT);
        this.tab.press(Nav.DOWN);
        assertTrue(this.tunable.sprint.get());

        this.tab.press(Nav.LEFT);
        this.tab.press(Nav.DOWN);
        this.tab.press(Nav.RIGHT);
        this.tab.press(Nav.DOWN);
        assertEquals(Style.FAST, this.tunable.style.get(), "Down moves on to the next option");
        this.tab.press(Nav.UP);
        assertEquals(Style.SLOW, this.tunable.style.get(), "Up moves back");
        this.tab.press(Nav.UP);
        assertEquals(Style.FAST, this.tunable.style.get(), "and wraps round the end");
    }

    @Test
    void aColourIsShownButLeftToTheClickGui() {
        this.enterSettings();
        this.tab.press(Nav.UP);
        assertFalse(TabGui.isEditable(this.tunable.tint));
        this.tab.press(Nav.RIGHT);
        assertFalse(this.tab.isEditing());
    }

    @Test
    void enterMeansNothingInsideTheSettings() {
        this.enterSettings();
        assertFalse(this.tab.press(Nav.ENTER));
        assertFalse(this.tunable.isEnabled());
    }

    @Test
    void aPresentationWithoutTheSettingsLevelClosesItAgain() {
        this.enterSettings();
        this.tab.press(Nav.RIGHT);
        assertTrue(this.tab.isEditing());

        this.tab.markShown();
        assertFalse(this.tab.inSettings());
        assertFalse(this.tab.isEditing());
    }

    @Test
    void onlyAKeyThatMovedTheMenuCountsAsAPress() {
        long before = this.tab.lastPress();
        assertFalse(this.tab.press(Nav.LEFT), "nothing to go back to");
        assertEquals(before, this.tab.lastPress());

        assertTrue(this.tab.press(Nav.DOWN));
        assertTrue(this.tab.lastPress() > before);
    }

    private void enterSettings() {
        this.tab.markShown(WITH_SETTINGS);
        this.tab.press(Nav.RIGHT);
        this.tab.press(Nav.RIGHT);
        assertTrue(this.tab.inSettings());
    }

    private enum Style { SLOW, FAST }

    private static final class Tunable extends Module {
        final NumberSetting reach = this.register(new NumberSetting("Reach", "test", 3.0F, 0.0F, 6.0F, 0.5F));
        final BooleanSetting sprint = this.register(new BooleanSetting("Sprint", "test", false));
        final EnumSetting<Style> style = this.register(new EnumSetting<>("Style", "test", Style.SLOW));
        final ColorSetting tint = this.register(new ColorSetting("Tint", "test", 0xFFFFFFFF));

        Tunable() {
            super(ModuleCategory.MOVEMENT, "Tunable", "test");
        }
    }

    private static final class Plain extends Module {
        Plain() {
            super(ModuleCategory.RENDER, "Plain", "test");
        }
    }
}
