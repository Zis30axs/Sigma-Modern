package com.mentalfrostbyte.jello.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Proves ModuleManager.register(Module) is all-or-nothing (F5). */
class ModuleManagerTest {

    @Test
    void duplicateClassRegistrationLeavesBothIndexesUnchanged() {
        ModuleManager manager = new ModuleManager();
        AlphaModule first = new AlphaModule("Alpha");
        manager.register(first);

        AlphaModule second = new AlphaModule("SomeOtherName");
        assertThrows(IllegalStateException.class, () -> manager.register(second));

        assertEquals(1, manager.all().size());
        assertSame(first, manager.get(AlphaModule.class));
        assertTrue(manager.find("someothername").isEmpty());
        assertSame(first, manager.find("alpha").orElseThrow());
    }

    @Test
    void duplicateNameRegistrationLeavesBothIndexesUnchanged() {
        ModuleManager manager = new ModuleManager();
        AlphaModule first = new AlphaModule("Shared");
        manager.register(first);

        BetaModule second = new BetaModule("Shared");
        assertThrows(IllegalStateException.class, () -> manager.register(second));

        assertEquals(1, manager.all().size());
        assertSame(first, manager.find("shared").orElseThrow());
        assertThrows(IllegalStateException.class, () -> manager.get(BetaModule.class));
    }

    private static final class AlphaModule extends Module {
        AlphaModule(final String name) {
            super(ModuleCategory.MISC, name, "test");
        }
    }

    private static final class BetaModule extends Module {
        BetaModule(final String name) {
            super(ModuleCategory.MISC, name, "test");
        }
    }
}
