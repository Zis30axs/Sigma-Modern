package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui.Nav;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TabGuiTest {

    private final ModuleManager modules = new ModuleManager();

    private final TabGui tab = new TabGui(this.modules);

    private final Module speed = new Speed();

    private final Module flight = new Flight();

    private final Module fullbright = new Fullbright();

    TabGuiTest() {
        this.modules.register(this.tab);
        this.modules.register(this.speed);
        this.modules.register(this.flight);
        this.modules.register(this.fullbright);
    }

    @AfterEach
    void switchEverythingOff() {
        this.modules.all().forEach(module -> module.setEnabled(false));
    }

    @Test
    void isAnInterfaceModuleThatStartsOff() {
        assertFalse(this.tab.isEnabled());
        assertFalse(this.tab.isEnabledByDefault(), "a new overlay is something to switch on, not something that appears");
        assertEquals(ModuleCategory.INTERFACE, this.tab.getCategory());
        assertEquals("TabGUI", this.tab.getName());
        assertEquals(0.6F, this.tab.getBackground());
        assertTrue(this.tab.showsKeybinds());
        assertTrue(this.tab.isAnimated());
    }

    @Test
    void offersOnlyCategoriesWithSomethingInThemAndNeverItself() {
        assertEquals(List.of(ModuleCategory.MOVEMENT, ModuleCategory.RENDER), this.tab.categories(),
                "Interface only holds the TabGUI, which isn't offered");
        assertEquals(List.of(this.speed, this.flight), this.tab.modules(ModuleCategory.MOVEMENT));
    }

    @Test
    void upAndDownWrapAroundTheCategories() {
        assertEquals(0, this.tab.selectedCategory());
        assertTrue(this.tab.press(Nav.UP));
        assertEquals(1, this.tab.selectedCategory());
        assertTrue(this.tab.press(Nav.DOWN));
        assertEquals(0, this.tab.selectedCategory());
        assertFalse(this.tab.press(Nav.LEFT), "nothing to go back to");
    }

    @Test
    void rightOpensACategoryAndEnterSwitchesTheHighlightedModule() {
        assertTrue(this.tab.press(Nav.RIGHT));
        assertTrue(this.tab.isOpen());
        assertEquals(0, this.tab.selectedModule());

        this.tab.press(Nav.DOWN);
        this.tab.press(Nav.ENTER);
        assertTrue(this.flight.isEnabled());
        assertFalse(this.speed.isEnabled());

        this.tab.press(Nav.RIGHT);
        assertFalse(this.flight.isEnabled(), "Right switches too, both ways");

        this.tab.press(Nav.DOWN);
        assertEquals(0, this.tab.selectedModule(), "wraps");
        this.tab.press(Nav.LEFT);
        assertFalse(this.tab.isOpen());
        assertEquals(0, this.tab.selectedCategory(), "back where it was");
    }

    @Test
    void anOpenCategoryStartsAtItsFirstModuleAndSwitchingTheMenuOffClosesIt() {
        this.tab.press(Nav.RIGHT);
        this.tab.press(Nav.DOWN);
        this.tab.press(Nav.LEFT);
        this.tab.press(Nav.ENTER);
        assertEquals(0, this.tab.selectedModule());

        this.tab.setEnabled(true);
        this.tab.setEnabled(false);
        assertFalse(this.tab.isOpen());
    }

    @Test
    void anEmptyClientHasNothingToNavigate() {
        TabGui alone = new TabGui(new ModuleManager());
        assertTrue(alone.categories().isEmpty());
        assertFalse(alone.press(Nav.DOWN));
    }

    @Test
    void theArrowsAndBothEntersAreTheKeys() {
        assertEquals(Nav.UP, TabGui.nav(InputConstants.KEY_UP));
        assertEquals(Nav.DOWN, TabGui.nav(InputConstants.KEY_DOWN));
        assertEquals(Nav.LEFT, TabGui.nav(InputConstants.KEY_LEFT));
        assertEquals(Nav.RIGHT, TabGui.nav(InputConstants.KEY_RIGHT));
        assertEquals(Nav.ENTER, TabGui.nav(InputConstants.KEY_RETURN));
        assertEquals(Nav.ENTER, TabGui.nav(InputConstants.KEY_NUMPADENTER));
        assertNull(TabGui.nav(InputConstants.KEY_W));
    }

    // One class each: the registry holds one module per class.
    private static final class Speed extends Module {
        Speed() {
            super(ModuleCategory.MOVEMENT, "Speed", "test");
        }
    }

    private static final class Flight extends Module {
        Flight() {
            super(ModuleCategory.MOVEMENT, "Flight", "test");
        }
    }

    private static final class Fullbright extends Module {
        Fullbright() {
            super(ModuleCategory.RENDER, "Fullbright", "test");
        }
    }
}
