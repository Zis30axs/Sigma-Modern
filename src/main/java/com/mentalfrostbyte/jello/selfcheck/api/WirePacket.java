package com.mentalfrostbyte.jello.selfcheck.api;

import io.netty.buffer.ByteBuf;

/**
 * One packet as it crossed the wire: decrypted, decompressed and framed, in the protocol of the server being
 * played on rather than the client's own. The buffer starts at the packet id varint.
 *
 * <p>The buffer is a read-only view that is only valid while the callback runs. An engine that keeps it, or
 * hands it to something that writes to it (PacketEvents rewrites a buffer it had to re-encode), copies it.</p>
 *
 * @param nanoTime {@link System#nanoTime()} when the packet reached the tap
 */
public record WirePacket(Direction direction, long nanoTime, ByteBuf buffer) {
}
