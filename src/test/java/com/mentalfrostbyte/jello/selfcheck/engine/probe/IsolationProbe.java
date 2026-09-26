package com.mentalfrostbyte.jello.selfcheck.engine.probe;

import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import com.mentalfrostbyte.jello.selfcheck.api.WirePacket;

/**
 * Lives in an isolated package so {@code EngineLoaderTest} can see whether each connection's loader really gets its
 * own statics - the property Grim's version-dependent {@code static final} fields rely on.
 */
public final class IsolationProbe implements SelfCheckEngineFactory {

    public static int instances;

    public IsolationProbe() {
        instances++;
    }

    @Override
    public String id() {
        return "Probe" + instances;
    }

    @Override
    public boolean supports(final int protocolVersion) {
        return true;
    }

    @Override
    public SelfCheckEngine create(final EngineContext context) {
        return new SelfCheckEngine() {
            @Override
            public void onClientbound(final WirePacket packet) {
            }

            @Override
            public void onServerbound(final WirePacket packet) {
            }
        };
    }
}
