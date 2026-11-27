package com.mentalfrostbyte.jello.gui;

import static com.mentalfrostbyte.jello.gui.ModuleStyleFilter.*;
import static com.mentalfrostbyte.jello.module.ModuleCategory.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.gui.Maps;
import com.mentalfrostbyte.jello.module.impl.gui.MiniMap;
import com.mentalfrostbyte.jello.module.impl.gui.ModernChat;
import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui;
import com.mentalfrostbyte.jello.module.impl.render.NameTags;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleStyleFilterTest {
    private final Module maps = new Maps();
    private final Module minimap = new MiniMap();
    private final Module chat = new ModernChat();
    private final Module radar = new Radar();
    private final Module tags = new NameTags();
    private final List<Module> modules = List.of(maps, minimap, chat, radar, tags);

    @Test
    void composesOwnershipAndCategoryInRegistrationOrder() {
        assertEquals(List.of(maps, minimap, chat), JELLO.select(modules, INTERFACE));
        assertEquals(List.of(chat, radar), MODERN.select(modules, INTERFACE));
        assertEquals(List.of(tags), MODERN.select(modules, RENDER));
        assertEquals(List.of(), CLASSIC.select(modules, RENDER));
        assertEquals(List.of(chat), CLASSIC.select(modules, null));
        assertEquals(List.of(chat), SHARED.select(modules, null));
        assertEquals(modules, ALL.select(modules, null));
    }

    @Test
    void changingTabGuiStyleOnlyChangesWhichModulesAreOffered() {
        ModuleManager manager = new ModuleManager();
        modules.forEach(manager::register);
        TabGui tab = new TabGui(manager);
        tab.showLayout(new TabGui.Layout(List.of(INTERFACE, RENDER), false, JELLO), false);
        assertEquals(List.of(INTERFACE), tab.categories());
        assertEquals(List.of(maps, minimap, chat), tab.modules(INTERFACE));
        tab.markShown();
        assertEquals(List.of(chat, radar), tab.modules(INTERFACE));
        assertSame(maps, manager.find("Maps").orElseThrow());
        assertFalse(maps.isEnabled());
    }
}
