package com.mentalfrostbyte.jello.selfcheck.api;

/**
 * Makes an engine for a connection. Found with {@link java.util.ServiceLoader}: a jar in {@code plugins/}
 * lists its factory in {@code META-INF/services/com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory}.
 *
 * <p>Each connection gets a fresh class loader for every engine, so an engine may keep whatever static state
 * it likes - including state that depends on the server's version - without it leaking into the next server.</p>
 */
public interface SelfCheckEngineFactory {

    /** The engine's name and the name of its folder under {@code plugins/}, such as {@code GrimAC}. */
    String id();

    /** Whether the engine can judge a connection speaking {@code protocolVersion}. */
    boolean supports(int protocolVersion);

    /**
     * Called on a background thread while the client is still connecting, so slow start-up is fine. Packets
     * that arrive meanwhile are held back and delivered in order once this returns.
     */
    SelfCheckEngine create(EngineContext context) throws Exception;
}
