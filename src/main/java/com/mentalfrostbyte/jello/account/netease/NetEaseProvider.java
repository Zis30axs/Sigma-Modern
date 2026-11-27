package com.mentalfrostbyte.jello.account.netease;

import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.IDN;
import java.util.List;
import java.util.UUID;

/**
 * Client integration point for a platform or server operator that authorizes NetEase accounts.
 * Bound network calls so a cancelled login can release the session when the player leaves.
 */
public interface NetEaseProvider {
    String displayName();

    Account signIn(NetEaseCredential credential) throws IOException;

    /** All methods on one account run serially off the render thread. */
    interface Account extends AutoCloseable {
        List<Entry> servers() throws IOException;

        /** Supply the PC directory for this session when roles/authorize accept its catalog IDs. */
        default NetEaseCatalog catalog() {
            return null;
        }

        List<Entry> roles(String serverId) throws IOException;

        /**
         * Authorize this role for the current Java player. The returned game entry must accept an
         * ordinary Minecraft connection and remain usable after this account browser closes.
         */
        Destination authorize(String serverId, String roleId, UUID playerId, String playerName) throws IOException;

        @Override
        void close();
    }

    /** IDs are opaque and scoped to their parent account or server. */
    record Entry(String id, String name) {
        public Entry {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Missing server or role");
            }
        }
    }

    /** An address only. Account credentials never enter Minecraft's server data or session service. */
    record Destination(String host, int port) {
        public Destination {
            if (host == null || host.isBlank() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid game address");
            }
            if (!InetAddresses.isInetAddress(host)) {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
                if (host.isEmpty() || host.equals(".") || host.length() > (host.endsWith(".") ? 254 : 253)) {
                    throw new IllegalArgumentException("Invalid game host");
                }
            }
        }

        public String address() {
            return HostAndPort.fromParts(host, port).toString();
        }
    }
}
