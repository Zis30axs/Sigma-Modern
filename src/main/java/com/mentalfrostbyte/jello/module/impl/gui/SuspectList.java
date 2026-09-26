package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.anticheat.alert.Suspects;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import java.util.List;
import java.util.UUID;

/**
 * The list of players AntiCheat has flagged, as something that stays on screen: a switch, and the data behind
 * it. Switched off, nothing is drawn; switched on, a presentation shows it.
 *
 * <p>SigmaModern shows it as a drawer pulled out from the left edge (see {@code gui.modern.ModernSuspectDrawer}),
 * over every screen while a world is loaded and over the game while it is left open. Another presentation
 * draws it its own way by asking this module - {@link #ranked()} for who, {@link #sourceEnabled()} for whether
 * anything is being watched, {@link #forget} and {@link #forgetAll} for the two things a list can do - and
 * needs nothing else from AntiCheat. The module itself is the same under every presentation, the way
 * {@code ModernChat} is.</p>
 */
public class SuspectList extends Module {

    private final ModuleAntiCheat source;

    private final BooleanSetting showOnHud = this.register(new BooleanSetting("ShowOnHud",
            "Keeps the list on the game screen while it is pulled out, so flags can be watched during play. Off, "
            + "it only shows while a screen is open.", true));

    public SuspectList(final ModuleAntiCheat source) {
        super(ModuleCategory.INTERFACE, "SuspectList", "Keeps AntiCheat's flagged players on screen, in a drawer you pull out");
        this.source = source;
    }

    /** Whether AntiCheat is on. Off, the list is empty because nobody is being watched, not because nobody cheats. */
    public boolean sourceEnabled() {
        return this.source.isEnabled();
    }

    /** Flagged players, highest total violation level first. */
    public List<Suspects.Entry> ranked() {
        return this.source.suspects().ranked();
    }

    public void forget(final UUID uuid) {
        this.source.forget(uuid);
    }

    public void forgetAll() {
        this.source.forgetAll();
    }

    /** Whether a presentation should also draw the list over the game while no screen is open. */
    public boolean showOnHud() {
        return this.showOnHud.get();
    }
}
