package com.mentalfrostbyte.jello.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.viaversion.viafabricplus.injection.access.core.IServerData;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;

/**
 * Native in-client JVM hot-injector and session-takeover helper using JDK's {@link VirtualMachine}
 * Attach API: enumerates running vanilla / modded Minecraft client processes, hot-loads the cached
 * Java-8 {@code mc-relay-agent.jar}, probes the resulting proxy's exact protocol version, configures
 * {@code ViaFabricPlus} automatically, and connects {@code Sigma-Modern} to take over control.
 */
public final class RelayJvmInjector {

    private static final Pattern VERSION_ARG_PATTERN = Pattern.compile(
        "(?:--version(?:=|\s+)|-Dminecraft\\.version=|versions[/\\\\])(\\d+\\.\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern GENERIC_MC_VERSION_PATTERN = Pattern.compile(
        "\\b(1\\.(?:8|9|1[0-9]|2[01])(?:\\.\\d+)?|26\\.[12](?:\\.\\d+)?)\\b"
    );

    public record JvmTarget(
        String pid,
        String title,
        String commandLine,
        @Nullable String guessedVersion,
        boolean likelyMinecraft
    ) {
    }

    public record ProbeStatus(
        String versionName,
        int protocol,
        String description,
        ProtocolVersion resolvedProtocolVersion
    ) {
    }

    private RelayJvmInjector() {
    }

    /** Lists attachable local Java processes (excluding this {@code Sigma-Modern} JVM itself). */
    public static List<JvmTarget> listProcesses(final boolean includeAllJavaProcesses) {
        String selfPid = Long.toString(ProcessHandle.current().pid());
        List<JvmTarget> out = new ArrayList<>();

        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            String pid = descriptor.id();
            if (pid == null || pid.equals(selfPid)) {
                continue;
            }
            String display = descriptor.displayName() == null ? "" : descriptor.displayName().trim();
            String fullCmd = readProcessCommandLine(pid, display);
            boolean likelyMc = classifyMinecraftProcess(display, fullCmd);
            if (!includeAllJavaProcesses && !likelyMc) {
                continue;
            }
            String title = summarizeTitle(display, fullCmd);
            String guessedVersion = guessMinecraftVersion(display + " " + fullCmd);
            out.add(new JvmTarget(pid, title, fullCmd, guessedVersion, likelyMc));
        }

        out.sort(Comparator
            .comparing((JvmTarget t) -> !t.likelyMinecraft())
            .thenComparing(t -> {
                try {
                    return Long.parseLong(t.pid());
                } catch (final NumberFormatException ignored) {
                    return Long.MAX_VALUE;
                }
            }));
        return out;
    }

    public static boolean classifyMinecraftProcess(final String displayName, final String commandLine) {
        String combined = (displayName + " " + commandLine).toLowerCase(Locale.ROOT);
        if (combined.contains("org.codehaus.plexus")
            || combined.contains("surefirebooter")
            || combined.contains("gradledaemon")
            || combined.contains("jetbrains")
            || combined.contains("intellij")
            || combined.contains("eclipse.equinox")
            || combined.contains("javac")) {
            return false;
        }
        return combined.contains("net.minecraft")
            || combined.contains("minecraft")
            || combined.contains("launchwrapper")
            || combined.contains("modlauncher")
            || combined.contains("cpw.mods")
            || combined.contains("fabricloader")
            || combined.contains("knotclient")
            || combined.contains("quiltloader")
            || combined.contains("neoforge")
            || combined.contains("forge")
            || combined.contains("lwjgl")
            || combined.contains("lunarclient")
            || combined.contains("hmcl")
            || combined.contains("pcl")
            || combined.contains("--accessToken".toLowerCase(Locale.ROOT))
            || combined.contains("--assetIndex".toLowerCase(Locale.ROOT));
    }

    public static @Nullable String guessMinecraftVersion(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher argMatcher = VERSION_ARG_PATTERN.matcher(text);
        if (argMatcher.find()) {
            return argMatcher.group(1);
        }
        Matcher generic = GENERIC_MC_VERSION_PATTERN.matcher(text);
        if (generic.find()) {
            return generic.group(1);
        }
        return null;
    }

    private static String readProcessCommandLine(final String pid, final String fallbackDisplay) {
        try {
            long numericPid = Long.parseLong(pid.trim());
            Optional<ProcessHandle> handle = ProcessHandle.of(numericPid);
            if (handle.isPresent()) {
                Optional<String> cmdLine = handle.get().info().commandLine();
                if (cmdLine.isPresent() && !cmdLine.get().isBlank()) {
                    return cmdLine.get();
                }
            }
        } catch (final RuntimeException ignored) {
        }
        return fallbackDisplay;
    }

    private static String summarizeTitle(final String display, final String fullCmd) {
        String base = !display.isBlank() ? display : fullCmd;
        if (base.isBlank()) {
            return "Java Process";
        }
        int space = base.indexOf(' ');
        String mainToken = space > 0 ? base.substring(0, space) : base;
        int slash = Math.max(mainToken.lastIndexOf('/'), mainToken.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < mainToken.length()) {
            mainToken = mainToken.substring(slash + 1);
        }
        if (mainToken.contains("KnotClient")) {
            return "Fabric Client (" + mainToken + ")";
        }
        if (mainToken.contains("BootstrapLauncher") || base.contains("neoforge") || base.contains("forge")) {
            return "Forge / NeoForge Client (" + mainToken + ")";
        }
        if (mainToken.contains("Launch") && base.contains("tweakClass")) {
            return "Legacy LaunchWrapper (" + mainToken + ")";
        }
        if (mainToken.endsWith("Main") || base.contains("net.minecraft.client.main.Main")) {
            return "Vanilla Minecraft (" + mainToken + ")";
        }
        return mainToken;
    }

    /**
     * Compiles/loads the cached Java-8 {@code mc-relay-agent.jar} and hot-attaches it into {@code pid}.
     */
    public static void inject(final String pid, final int port, final Consumer<String> log) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid relay port: " + port);
        }
        Path agentJar = RelayAgentBuilder.ensureAgentJar();
        log.accept("Agent JAR ready: " + agentJar.getFileName());

        VirtualMachine vm = null;
        try {
            log.accept("Attaching to JVM PID " + pid + " ...");
            vm = VirtualMachine.attach(pid.trim());
            log.accept("Loading agent with port=" + port + " ...");
            vm.loadAgent(agentJar.toAbsolutePath().toString(), "port=" + port);
            log.accept("Hot-injection succeeded on PID " + pid + " (port " + port + ").");
        } catch (final AttachNotSupportedException | AgentLoadException | AgentInitializationException attachErr) {
            throw new IOException("Hot-attach failed for PID " + pid + ": " + attachErr.getMessage(), attachErr);
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (final IOException ignored) {
                }
            }
        }
    }

    /**
     * Sends a lightweight Minecraft Status Ping to {@code host:port} to confirm the relay proxy is
     * listening and to resolve Host A's exact {@link ProtocolVersion} for {@code ViaFabricPlus}.
     */
    public static @Nullable ProbeStatus probeRelayStatus(final String host, final int port, final int timeoutMs) {
        SharedConstants.tryDetectVersion();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1. Handshake packet (ID 0x00, nextState = 1 STATUS)
            ByteArrayOutputStream handshakePayload = new ByteArrayOutputStream();
            writeVarInt(handshakePayload, 0x00);
            writeVarInt(handshakePayload, SharedConstants.getProtocolVersion());
            writeUtf(handshakePayload, host);
            handshakePayload.write((port >>> 8) & 0xFF);
            handshakePayload.write(port & 0xFF);
            writeVarInt(handshakePayload, 1);
            byte[] hsBytes = handshakePayload.toByteArray();
            writeVarInt(out, hsBytes.length);
            out.write(hsBytes);

            // 2. Status Request packet (ID 0x00)
            writeVarInt(out, 1);
            writeVarInt(out, 0x00);
            out.flush();

            // 3. Read Status Response frame
            int frameLen = readVarInt(in);
            if (frameLen <= 0 || frameLen > 262_144) {
                return null;
            }
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return null;
            }
            String json = readUtf(in);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            String versionName = "unknown";
            int protocol = -1;
            if (root.has("version") && root.get("version").isJsonObject()) {
                JsonObject ver = root.getAsJsonObject("version");
                if (ver.has("name")) {
                    versionName = ver.get("name").getAsString();
                }
                if (ver.has("protocol")) {
                    protocol = ver.get("protocol").getAsInt();
                }
            }
            String desc = "";
            if (root.has("description")) {
                if (root.get("description").isJsonObject() && root.getAsJsonObject("description").has("text")) {
                    desc = root.getAsJsonObject("description").get("text").getAsString();
                } else if (root.get("description").isJsonPrimitive()) {
                    desc = root.get("description").getAsString();
                }
            }

            ProtocolVersion resolved = resolveViaProtocol(protocol, versionName);
            return new ProbeStatus(versionName, protocol, desc, resolved);
        } catch (final Exception unreachable) {
            return null;
        }
    }

    /** Polls {@code host:port} until the injected proxy responds or {@code maxWaitMs} elapses. */
    public static @Nullable ProbeStatus waitForRelayReady(final String host, final int port, final long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            ProbeStatus status = probeRelayStatus(host, port, 500);
            if (status != null) {
                return status;
            }
            try {
                Thread.sleep(200L);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public static ProtocolVersion resolveViaProtocol(final int protocolId, final @Nullable String versionName) {
        if (protocolId > 0 && ProtocolVersion.isRegistered(protocolId)) {
            return ProtocolVersion.getProtocol(protocolId);
        }
        if (versionName != null && !versionName.isBlank()) {
            ProtocolVersion closest = ProtocolVersion.getClosest(versionName.trim());
            if (closest != null) {
                return closest;
            }
        }
        return ProtocolTranslator.AUTO_DETECT_PROTOCOL;
    }

    /**
     * Connects {@code Sigma-Modern} to {@code host:port} on the main client thread, automatically
     * forcing {@code ViaFabricPlus} to Host A's detected {@link ProtocolVersion}.
     */
    public static void connectAndTakeover(
        final @Nullable Screen parentScreen,
        final String host,
        final int port,
        final @Nullable ProbeStatus probe
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        ProtocolVersion targetVersion = probe != null
            ? probe.resolvedProtocolVersion()
            : ProtocolTranslator.AUTO_DETECT_PROTOCOL;
        String endpoint = host + ":" + port;
        String label = probe != null && !"unknown".equals(probe.versionName())
            ? "MC-Relay (" + probe.versionName() + ")"
            : "MC-Relay (" + endpoint + ")";

        mc.execute(() -> {
            ServerData data = new ServerData(label, endpoint, ServerData.Type.LAN);
            data.sigma$setRelayTakeover(true);
            ((IServerData) data).viaFabricPlus$passDirectConnectScreen(false);
            ((IServerData) data).viaFabricPlus$forceVersion(targetVersion);
            Screen back = parentScreen != null ? parentScreen : mc.gui.screen();
            ConnectScreen.startConnecting(
                back,
                mc,
                ServerAddress.parseString(endpoint),
                data,
                false,
                null
            );
        });
    }

    private static void writeVarInt(final ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeVarInt(final DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static void writeUtf(final ByteArrayOutputStream out, final String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(final DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            result |= (read & 0x7F) << (7 * numRead);
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    private static String readUtf(final DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 262_144) {
            throw new IOException("Invalid UTF length: " + len);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new IOException("Truncated UTF string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
