package me.flashyreese.mods.sodiumextra.mixin.fog;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface AccessorIntegratedServer {
    boolean sodiumExtra$commandsAllowedForOtherPlayers();
}
