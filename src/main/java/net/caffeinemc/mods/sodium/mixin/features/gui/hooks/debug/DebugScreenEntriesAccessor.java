package net.caffeinemc.mods.sodium.mixin.features.gui.hooks.debug;

import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;

/**
 * MODIFIED for porting: was a Mixin accessor interface for the static field {@code DebugScreenEntries.ENTRIES_BY_ID};
 * the vanilla class now exposes it through {@code DebugScreenEntries.sodium$getEntries()} and this interface forwards
 * to it so the call sites stay unchanged.
 */
public interface DebugScreenEntriesAccessor {
    static Map<Identifier, DebugScreenEntry> sodium$getEntries() {
        return DebugScreenEntries.sodium$getEntries();
    }
}
