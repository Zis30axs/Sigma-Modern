package com.mentalfrostbyte.jello.event.impl.game.network;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import net.minecraft.network.protocol.Packet;

/**
 * An inbound packet, fired before it is handed to the packet listener. Cancelling drops it;
 * {@link #setPacket(Packet)} swaps in a different one.
 *
 * <p>Fired on the Netty event loop, not the game thread. Listeners must not touch the level or the
 * player directly - schedule that onto the client instead.</p>
 */
public class EventReceivePacket extends CancellableEvent {

    private Packet<?> packet;

    public EventReceivePacket(final Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return this.packet;
    }

    public void setPacket(final Packet<?> packet) {
        this.packet = packet;
    }
}
