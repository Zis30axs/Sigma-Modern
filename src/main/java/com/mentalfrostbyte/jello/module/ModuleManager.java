package com.mentalfrostbyte.jello.module;

import com.mentalfrostbyte.jello.module.impl.gui.ModernChat;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.module.impl.gui.SuspectList;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui;
import com.mentalfrostbyte.jello.module.impl.misc.CustomTitle;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiExploit;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleSelfDetection;
import com.mentalfrostbyte.jello.module.impl.movement.Speed;
import com.mentalfrostbyte.jello.module.impl.render.BlockAnimation;
import com.mentalfrostbyte.jello.module.impl.render.CameraNoClip;
import com.mentalfrostbyte.jello.module.impl.render.Fullbright;
import com.mentalfrostbyte.jello.module.impl.render.LowFire;
import com.mentalfrostbyte.jello.module.impl.render.NoHurtCam;
import com.mentalfrostbyte.jello.module.impl.world.Weather;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The one registry of modules.
 *
 * <p>Every module exists exactly once and is registered exactly once, here. Nothing about how the client
 * looks decides whether a module exists: an interface may choose to show a module differently, or not to
 * show it at all, but that is a decision made where the drawing happens, over the single set of modules
 * this class holds.</p>
 */
public final class ModuleManager {

    private final Map<Class<? extends Module>, Module> byClass = new LinkedHashMap<>();

    /** Keyed by lower-cased name, so a command or a config can look a module up as the user typed it. */
    private final Map<String, Module> byName = new LinkedHashMap<>();

    /** Registers every module the client ships with. */
    public void registerAll() {
        ModuleAntiCheat antiCheat = new ModuleAntiCheat();
        this.register(antiCheat);
        this.register(new ModuleAntiExploit());
        this.register(new ModuleArrayList());
        this.register(new ModuleSelfDetection());
        this.register(new BlockAnimation());
        this.register(new CameraNoClip());
        this.register(new CustomTitle());
        this.register(new Fullbright());
        this.register(new LowFire());
        this.register(new ModernChat());
        this.register(new NoHurtCam());
        this.register(new Speed());
        this.register(new SuspectList(antiCheat));
        this.register(new TabGui(this));
        this.register(new Weather());
    }

    /**
     * Registration is all-or-nothing: both a class collision and a name collision are checked before
     * either map is touched, so a rejected registration never leaves {@code byClass} and {@code byName}
     * disagreeing about whether the module exists. The preflight check is safe without locking because
     * registration happens only during {@link #registerAll()}, on a single lifecycle thread.
     */
    public void register(final Module module) {
        Module sameClass = this.byClass.get(module.getClass());
        if (sameClass != null) {
            throw new IllegalStateException(module.getClass().getName() + " is already registered");
        }

        String key = module.getName().toLowerCase(Locale.ROOT);
        Module sameName = this.byName.get(key);
        if (sameName != null) {
            throw new IllegalStateException("Two modules are both named '" + module.getName()
                    + "': " + sameName.getClass().getName() + " and " + module.getClass().getName());
        }

        this.byClass.put(module.getClass(), module);
        this.byName.put(key, module);
    }

    /**
     * The single instance of a module. A module asking for another one by class is asking for something
     * that must exist, so an unregistered class is a programming error rather than a missing value.
     */
    public <T extends Module> T get(final Class<T> type) {
        Module module = this.byClass.get(type);
        if (module == null) {
            throw new IllegalStateException(type.getName() + " is not registered");
        }

        return type.cast(module);
    }

    /** Looks a module up by name, case insensitively - for commands, the config and search. */
    public Optional<Module> find(final String name) {
        return Optional.ofNullable(this.byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every module, in registration order. */
    public Collection<Module> all() {
        return Collections.unmodifiableCollection(this.byClass.values());
    }

    public List<Module> byCategory(final ModuleCategory category) {
        List<Module> found = new ArrayList<>();
        for (Module module : this.byClass.values()) {
            if (module.getCategory() == category) {
                found.add(module);
            }
        }

        return found;
    }
}
