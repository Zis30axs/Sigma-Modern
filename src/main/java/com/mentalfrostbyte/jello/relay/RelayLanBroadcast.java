package com.mentalfrostbyte.jello.relay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Broadcasts Sigma Relay sessions over Minecraft's standard LAN discovery multicast group
 * ({@code 224.0.2.60:4445}) so Client B sees Host A's relay session automatically in Multiplayer.
 */
public final class RelayLanBroadcast {

    public static final String MULTICAST_GROUP = "224.0.2.60";
    public static final int MULTICAST_PORT = 4445;
    public static final String SIGMA_RELAY_TAG = "[Sigma Relay]";

    private final int port;
    private final Supplier<String> motdSupplier;
    private volatile boolean running;
    private @Nullable DatagramSocket socket;
    private @Nullable Thread thread;

    public RelayLanBroadcast(final int port, final Supplier<String> motdSupplier) {
        this.port = port;
        this.motdSupplier = motdSupplier;
    }

    public void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        Thread worker = new Thread(this::runLoop, "Sigma-Relay-LAN-" + this.port);
        worker.setDaemon(true);
        this.thread = worker;
        worker.start();
    }

    public void stop() {
        this.running = false;
        DatagramSocket s = this.socket;
        this.socket = null;
        if (s != null) {
            s.close();
        }
        Thread t = this.thread;
        this.thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void runLoop() {
        try (DatagramSocket udp = new DatagramSocket()) {
            this.socket = udp;
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            while (this.running && !Thread.currentThread().isInterrupted()) {
                try {
                    String payload = formatAnnouncement(this.motdSupplier.get(), this.port);
                    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                    udp.send(new DatagramPacket(bytes, bytes.length, group, MULTICAST_PORT));
                } catch (final IOException ignored) {
                    if (!this.running) {
                        break;
                    }
                }
                Thread.sleep(1500L);
            }
        } catch (final IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            this.socket = null;
        }
    }

    public static String buildMotd(final String hostName, final String targetLabel, final boolean controlled) {
        String state = controlled ? " §e(Busy)" : " §a(Ready)";
        return "§b" + SIGMA_RELAY_TAG + " §f" + hostName + " §7-> §b" + targetLabel + state;
    }

    public static String formatAnnouncement(final String motd, final int port) {
        return "[MOTD]" + (motd == null ? SIGMA_RELAY_TAG : motd) + "[/MOTD][AD]" + port + "[/AD]";
    }

    /** Returns {@code true} when a LAN server's MOTD came from Sigma Relay (ignoring {@code §} formatting). */
    public static boolean isSigmaRelayMotd(final @Nullable String motd) {
        if (motd == null || motd.isEmpty()) {
            return false;
        }
        StringBuilder stripped = new StringBuilder(motd.length());
        for (int i = 0; i < motd.length(); i++) {
            char ch = motd.charAt(i);
            if (ch == '§' && i + 1 < motd.length()) {
                i++;
                continue;
            }
            stripped.append(ch);
        }
        return stripped.toString().contains(SIGMA_RELAY_TAG);
    }

    /** Returns {@code true} when a LAN server's MOTD came from a hot-injected external {@code mc-relay} agent. */
    public static boolean isInjectedAgentRelayMotd(final @Nullable String motd) {
        if (motd == null || motd.isEmpty()) {
            return false;
        }
        return motd.contains("MC-Relay Proxy") || motd.contains("[MC-Relay]");
    }
}
