package com.mentalfrostbyte.jello.selfcheck.api;

/**
 * What an engine concludes. None of it is ever carried out: the player is not teleported, kicked or punished,
 * and nothing reaches the server. Everything is written to the self-check log and shown as the player asked.
 */
public interface Verdicts {

    /** A check failed. Always logged; shown in chat only while the player has verbose output on. */
    void flag(String check, double violations, String verbose);

    /**
     * A chat line for the player, as a Minecraft JSON text component: an alert the engine decided to show, a
     * reply to a command, a notice.
     */
    void message(String componentJson);

    /** A punishment command a server would have run, such as {@code kick Steve Cheating}. */
    void punishment(String command);

    /** A server would have teleported the player back here. */
    void setback(String reason);

    /** A server would have disconnected the player. */
    void disconnect(String reason);
}
