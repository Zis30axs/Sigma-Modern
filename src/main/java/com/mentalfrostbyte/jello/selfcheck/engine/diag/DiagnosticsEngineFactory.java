package com.mentalfrostbyte.jello.selfcheck.engine.diag;

import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;

/**
 * Makes a {@link DiagnosticsEngine}. Only runs with {@code -Dsigma.debug.selfcheck.diagnostics=true}. With
 * {@code sigma.debug.selfcheck.diagnosticsDelayMs} it takes that long to "start", to see how joining behaves behind a
 * slow engine.
 */
public final class DiagnosticsEngineFactory implements SelfCheckEngineFactory {

    @Override
    public String id() {
        return "Diagnostics";
    }

    @Override
    public boolean supports(final int protocolVersion) {
        return true;
    }

    @Override
    public SelfCheckEngine create(final EngineContext context) throws InterruptedException {
        Thread.sleep(Long.getLong("sigma.debug.selfcheck.diagnosticsDelayMs", 0L));
        return new DiagnosticsEngine(context);
    }
}
