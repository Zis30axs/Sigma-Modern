/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.protocoltranslator.util;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixin on {@link UserConnectionImpl}, which is a ViaVersion
 * jar class. Sigma-Modern has no Mixin runtime, but the class is public and non-final and both send entry
 * points are public, so a subclass installed at the single dummy-connection call site
 * ({@code ProtocolTranslator#createDummyUserConnection}) is equivalent.
 */
public final class NoPacketSendUserConnection extends UserConnectionImpl {

    public NoPacketSendUserConnection(final Channel channel) {
        super(channel, true);
    }

    // was VFP core/integration MixinUserConnectionImpl#handleNoPacketSendChannel
    // (@Inject sendRawPacket(Lio/netty/buffer/ByteBuf;Z)V HEAD, cancellable). All versions: a protocol running
    // on a dummy translator connection must never write, because NoPacketSendChannel is an unregistered
    // LocalChannel - clientSide sendRawPacketNow would NPE on pipeline().context(decoderName) and the
    // scheduled path on the null eventLoop. The private sendRawPacket(ByteBuf, boolean) the mixin cancels is
    // reached only from these two public methods, so overriding both covers exactly the same call paths.
    // sendRawPacketFuture is deliberately left alone - upstream does not cancel it either.
    // Only difference to upstream: the buffer is released instead of leaked (ci.cancel() drops it), which
    // cannot change protocol behaviour - every caller either hands over ownership or passes retain().
    @Override
    public void sendRawPacket(final ByteBuf packet) {
        if (this.getChannel() instanceof NoPacketSendChannel) {
            packet.release();
            return;
        }
        super.sendRawPacket(packet);
    }

    @Override
    public void scheduleSendRawPacket(final ByteBuf packet) {
        if (this.getChannel() instanceof NoPacketSendChannel) {
            packet.release();
            return;
        }
        super.scheduleSendRawPacket(packet);
    }

}
