package me.flashyreese.mods.sodiumextra.mixin.fog;

import net.minecraft.client.server.IntegratedServer;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface AccessorMinecraft {
    IntegratedServer sodiumExtra$getSingleplayerServer();
}
