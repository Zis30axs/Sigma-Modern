package com.mentalfrostbyte.jello.selfcheck.host;

import java.util.UUID;

/**
 * The connection being judged.
 *
 * @param protocol     the protocol number the wire speaks, which is the server's, not the client's own
 * @param protocolName a readable name for it, such as {@code 1.8.x}
 * @param address      what the player typed, host and port
 */
public record Target(int protocol, String protocolName, String address, UUID playerId, String playerName) {
}
