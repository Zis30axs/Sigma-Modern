package com.mentalfrostbyte.jello.event.impl.game.network;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import net.minecraft.network.protocol.Packet;

/**
 * An outbound packet, fired before it is written to the channel. Cancelling drops it;
 * {@link #setPacket(Packet)} swaps in a different one.
 *
 * <p>May be fired off the game thread - see {@link EventReceivePacket}.</p>
 */
public class EventSendPacket extends CancellableEvent {

    private Packet<?> packet;

    public EventSendPacket(final Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return this.packet;
    }

    public void setPacket(final Packet<?> packet) {
        this.packet = packet;
    }
}
