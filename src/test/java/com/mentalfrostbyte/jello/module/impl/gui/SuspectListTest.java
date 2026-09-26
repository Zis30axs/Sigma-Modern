package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SuspectListTest {

    @Test
    void startsOffAsAnInterfaceModuleThatShowsOnTheHud() {
        SuspectList list = new SuspectList(new ModuleAntiCheat());
        assertFalse(list.isEnabled());
        assertEquals(ModuleCategory.INTERFACE, list.getCategory());
        assertEquals("SuspectList", list.getName());
        assertTrue(list.showOnHud());
    }

    @Test
    void readsAndForgetsWhatAntiCheatHasFlagged() {
        ModuleAntiCheat antiCheat = new ModuleAntiCheat();
        SuspectList list = new SuspectList(antiCheat);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        antiCheat.suspects().update(a, "A", "Speed", 2.0);
        antiCheat.suspects().update(b, "B", "Flight", 5.0);

        assertEquals(2, list.ranked().size());
        assertEquals("B", list.ranked().get(0).name(), "highest total level first");

        list.forget(b);
        assertEquals(1, list.ranked().size());
        list.forgetAll();
        assertTrue(list.ranked().isEmpty());
    }

    @Test
    void saysWhetherAnythingIsBeingWatched() {
        ModuleAntiCheat antiCheat = new ModuleAntiCheat();
        SuspectList list = new SuspectList(antiCheat);
        assertFalse(list.sourceEnabled(), "AntiCheat starts off: an empty list then means nobody is watched");
        antiCheat.setEnabled(true);
        assertTrue(list.sourceEnabled());
        antiCheat.setEnabled(false);
    }
}
