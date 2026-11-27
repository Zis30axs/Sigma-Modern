package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.impl.combat.AutoProjectile;
import com.mentalfrostbyte.jello.module.impl.player.ChestSteal;
import com.mentalfrostbyte.jello.module.impl.player.InvManager;
import com.mentalfrostbyte.jello.module.impl.render.BlockEsp;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SettingEditorTest {
    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void itemAndBlockCataloguesUseTheSameEditorAcrossStyles() {
        var inventory = new InvManager();
        var chest = new ChestSteal();
        var projectile = new AutoProjectile();
        var blocks = new BlockEsp();
        for (Module module : List.of(inventory, chest, projectile, blocks)) {
            for (ModuleStyleFilter style : List.of(ModuleStyleFilter.JELLO, ModuleStyleFilter.CLASSIC, ModuleStyleFilter.MODERN)) {
                assertEquals(List.of(module), style.select(List.of(module), module.getCategory()));
            }
        }
        assertEquals(SettingEditor.SLOT_LAYOUT, editor(inventory, "Slot Layout"));
        for (var name : List.of("Whitelist", "Blacklist")) {
            assertEquals(SettingEditor.ITEM_LIST, editor(inventory, name));
            assertEquals(SettingEditor.ITEM_LIST, editor(chest, name));
        }
        assertEquals(SettingEditor.ITEM_LIST, editor(projectile, "Projectile Whitelist"));
        assertEquals(SettingEditor.ITEM_LIST, editor(blocks, "Custom Blocks"));
    }

    @Test void ordinaryInputsKeepTheirOwnControlsEvenWithAnEditorName() {
        assertNull(SettingEditor.forSetting(new TextSetting("Training", "", "")));
        assertNull(SettingEditor.forSetting(new BooleanSetting("Whitelist", "", true)));
    }

    private static SettingEditor editor(Module module, String name) {
        return SettingEditor.forSetting(module.setting(name).orElseThrow());
    }
}
