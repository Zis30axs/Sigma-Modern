package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.gui.Bird;
import com.mentalfrostbyte.jello.module.impl.gui.BrainFreeze;
import com.mentalfrostbyte.jello.module.impl.gui.Compass;
import com.mentalfrostbyte.jello.module.impl.gui.Coords;
import com.mentalfrostbyte.jello.module.impl.gui.HudClock;
import com.mentalfrostbyte.jello.module.impl.gui.InfoHud;
import com.mentalfrostbyte.jello.module.impl.gui.KeyStrokes;
import com.mentalfrostbyte.jello.module.impl.gui.Keyboard;
import com.mentalfrostbyte.jello.module.impl.gui.Maps;
import com.mentalfrostbyte.jello.module.impl.gui.MiniMap;
import com.mentalfrostbyte.jello.module.impl.gui.PotionStatus;
import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import com.mentalfrostbyte.jello.module.impl.gui.Rwr;
import com.mentalfrostbyte.jello.module.impl.gui.Scoreboard;
import com.mentalfrostbyte.jello.module.impl.gui.Snake;
import com.mentalfrostbyte.jello.module.impl.gui.Spotlight;
import com.mentalfrostbyte.jello.module.impl.gui.SuspectList;
import com.mentalfrostbyte.jello.module.impl.gui.TargetHud;
import com.mentalfrostbyte.jello.module.impl.render.Esp;
import com.mentalfrostbyte.jello.module.impl.render.NameTags;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Presentation ownership for module browsers. The registry and saved module identities stay global. */
public enum ModuleStyleFilter {
    ALL, SHARED, JELLO, CLASSIC, MODERN;

    // Jello's own screens, ClickGUI effects and LegacyHud widgets.
    private static final Set<Class<? extends Module>> JELLO_MODULES = Set.of(
            Bird.class, BrainFreeze.class, Compass.class, Coords.class, InfoHud.class, KeyStrokes.class,
            Maps.class, MiniMap.class, Snake.class, Spotlight.class);

    // These modules' output is supplied by the Modern presentation. ModernChat has a mode-independent renderer.
    private static final Set<Class<? extends Module>> MODERN_MODULES = Set.of(
            Esp.class, HudClock.class, Keyboard.class, NameTags.class, PotionStatus.class, Radar.class,
            Rwr.class, Scoreboard.class, SuspectList.class, TargetHud.class);

    public static ModuleStyleFilter styleOf(Module module) {
        if (JELLO_MODULES.contains(module.getClass())) return JELLO;
        if (MODERN_MODULES.contains(module.getClass())) return MODERN;
        return SHARED;
    }

    public boolean includes(Module module) {
        ModuleStyleFilter owner = styleOf(module);
        return this == ALL || owner == SHARED || this == owner;
    }

    public List<Module> select(Collection<Module> modules, @Nullable ModuleCategory category) {
        return modules.stream().filter(module -> (category == null || module.getCategory() == category)
                && includes(module)).toList();
    }
}
