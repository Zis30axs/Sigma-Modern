package com.mentalfrostbyte.jello.selfcheck.host;

/**
 * Where a session's verdicts go - in practice the module, which decides what reaches the chat. Calls arrive on
 * the connection's network thread; an implementation hops to the game thread itself.
 */
public interface SelfCheckOutput {

    void flag(String engine, String check, double violations, String verbose);

    /** A chat line the engine wants the player to see, as a JSON text component. */
    void message(String engine, String componentJson);

    void punishment(String engine, String command);

    void setback(String engine, String reason);

    void disconnect(String engine, String reason);

    /** News about the self-check itself: an engine failed to start or was switched off, a version is unsupported. */
    void notice(String text);
}
