package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.setting.EnumSetting;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockFlyTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void allEightModesAndTheirSettingsAreRegisteredWithoutNameCollisions() {
        BlockFly module = new BlockFly();
        EnumSetting<?> mode = (EnumSetting<?>) module.setting("Mode").orElseThrow();
        assertEquals(8, mode.getOptions().size());
        assertEquals(BlockFly.Mode.NCP, mode.get());
        assertFalse(module.isEnabled());
        for (int i = 0; i < mode.getOptions().size(); i++) {
            mode.setIndex(i);
            BlockFly.Mode selected = BlockFly.Mode.values()[i];
            assertTrue(module.isMode(selected));
            assertTrue(module.settings().stream().anyMatch(setting -> setting.isVisible()
                    && setting.getName().startsWith(selected.toString() + " ")));
        }
    }
}
