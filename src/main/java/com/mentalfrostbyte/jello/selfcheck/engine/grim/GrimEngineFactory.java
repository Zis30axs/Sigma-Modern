package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;

/**
 * The built-in GrimAC. Loaded afresh for every connection by {@code EngineLoader.Isolating}, together with its own copy
 * of Grim and PacketEvents, because both settle much of their behaviour from the server's version once, in static
 * fields. Its data folder is {@code plugins/GrimAC/}, laid out as on a server.
 */
public final class GrimEngineFactory implements SelfCheckEngineFactory {

    @Override
    public String id() {
        return SigmaGrimLoader.PLUGIN_NAME;
    }

    /** Grim judges 1.8 and newer; the protocol must be one PacketEvents knows. */
    @Override
    public boolean supports(final int protocolVersion) {
        ClientVersion version = ClientVersion.getById(protocolVersion);
        return version != null
                && version != ClientVersion.UNKNOWN
                && version.getProtocolVersion() == protocolVersion
                && version.isNewerThanOrEquals(ClientVersion.V_1_8);
    }

    @Override
    public SelfCheckEngine create(final EngineContext context) {
        return GrimEngine.start(context);
    }
}
