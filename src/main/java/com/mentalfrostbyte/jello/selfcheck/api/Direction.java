package com.mentalfrostbyte.jello.selfcheck.api;

/** Which way a packet crossed the wire. */
public enum Direction {
    /**
     * Server to client, as the server sent it: taken before ViaFabricPlus translates it and before any module
     * could cancel it, so an engine knows what the player was sent even when the client chose to ignore it.
     */
    CLIENTBOUND,
    /**
     * Client to server, as it actually left: taken after every module changed, held back or dropped what it
     * wanted and after ViaFabricPlus translated it, so it is exactly what the server receives.
     */
    SERVERBOUND
}
