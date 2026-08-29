package com.mentalfrostbyte.jello.util.game;

import net.minecraft.client.Minecraft;

/**
 * Implement this to get {@code mc} in scope without repeating {@code Minecraft.getInstance()} on every
 * line. Client code that touches the game does this; nothing else belongs in here.
 *
 * <p>The constant is resolved when this interface is first initialised, which happens the first time
 * an implementing class is loaded. That is always after {@code Minecraft}'s constructor has published
 * the singleton, so the field is never null in practice - but it does mean no implementor may be
 * touched from static initialisation that runs earlier than that.</p>
 */
public interface MinecraftInstance {

    Minecraft mc = Minecraft.getInstance();
}
