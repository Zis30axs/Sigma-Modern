package com.mentalfrostbyte.jello.event.impl.player;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import net.minecraft.world.entity.Entity;

/**
 * The local player is about to attack {@link #getTarget()}, fired from {@code MultiPlayerGameMode.attack} before the
 * attack packet is sent. Packets a listener sends from here reach the server ahead of the attack, which is where a
 * Criticals or a W-tap puts theirs. Cancelling skips the attack: no packet, no swing damage, no cooldown reset.
 */
public class EventAttack extends CancellableEvent {

    private final Entity target;

    public EventAttack(final Entity target) {
        this.target = target;
    }

    public Entity getTarget() {
        return this.target;
    }
}
