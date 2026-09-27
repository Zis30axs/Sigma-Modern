package com.mentalfrostbyte.jello.event.impl.player;

import com.mentalfrostbyte.jello.event.CancellableEvent;

/**
 * The local player is using an item but the use key is up, so vanilla is about to stop - fired from
 * {@code Minecraft.handleKeybinds}, the one place a key release ends an item's use. Cancelling keeps the item in use
 * this tick, as if the key were still held: a module that raised a block itself (a KillAura's AutoBlock) holds it
 * this way, where it would otherwise be lowered again in the very tick it went up.
 */
public class EventStopUsingItem extends CancellableEvent {
}
