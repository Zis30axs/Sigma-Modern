package com.mentalfrostbyte.jello.selfcheck.host;

import net.minecraft.network.protocol.common.ServerboundPongPacket;

/**
 * The client's answer to a {@link SelfCheckPing}. It goes through {@code Connection.send} like any other pong, so
 * modules that hold packets back delay it just as they would delay the server's transactions, and it is taken out
 * of the pipeline before the encoder: the server never sent that ping and must never see this answer.
 */
public final class SelfCheckPong extends ServerboundPongPacket {

    public SelfCheckPong(final int id) {
        super(id);
    }
}
