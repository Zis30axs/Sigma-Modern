package me.flashyreese.mods.sodiumextra.mixin.panini_projection;

import java.util.List;
import net.minecraft.client.renderer.PostPass;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface AccessorPostChain {
    List<PostPass> sodiumExtra$getPasses();
}
