package com.mentalfrostbyte.jello.selfcheck.host;

import net.minecraft.network.protocol.common.ClientboundPingPacket;

/**
 * A ping the self-check put into the stream of packets from the server. It is handled exactly like the server's
 * own pings - queued behind the packets before it, visible to modules, answered by the vanilla listener - except
 * that the answer comes back as a {@link SelfCheckPong}, which never leaves the client.
 */
public final class SelfCheckPing extends ClientboundPingPacket {

    public SelfCheckPing(final int id) {
        super(id);
    }
}
