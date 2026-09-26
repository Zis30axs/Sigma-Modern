package com.mentalfrostbyte.jello.module.impl.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.selfcheck.host.LocalCommands;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheck;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckOutput;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckSession;
import com.mentalfrostbyte.jello.selfcheck.host.ServerRoot;
import com.mentalfrostbyte.jello.selfcheck.host.Target;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mentalfrostbyte.jello.util.text.ChatUtil;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs a real anticheat - the built-in GrimAC, and any engine in {@code sigma5/selfcheck/plugins/} - against the
 * player's own traffic, and says what it would have done: which checks fail, which punishments a server would run,
 * when it would set the player back or kick them. Nothing is ever carried out and nothing extra reaches the server.
 *
 * <p>The module holds the switches. The work is in {@code com.mentalfrostbyte.jello.selfcheck}: the connection's
 * pipeline gets taps on the wire side of ViaFabricPlus, so an engine reads exactly the bytes a server-side
 * anticheat would, and a session feeds them to the engines. How that works, and what it cannot see, is in
 * {@code SELFCHECK_PORTING.md}.</p>
 *
 * <p>A session starts when a connection to a server is opened with the module on, and ends with the connection or
 * when the module is switched off. Switching it on while connected takes effect on the next server.</p>
 */
public class ModuleSelfDetection extends Module {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Punishment commands that end in a ban rather than a kick, a mute or a message. */
    private static final Set<String> BANS = Set.of("ban", "tempban", "ipban", "banip", "ban-ip", "tempbanip", "litebans:ban");

    private static final long SETBACK_NOTICE_INTERVAL = 2_000_000_000L;

    private final BooleanSetting grim = this.register(new BooleanSetting("Grim",
            "Runs the built-in GrimAC. Its config is sigma5/selfcheck/plugins/GrimAC/, laid out like a server's.", true));

    private final BooleanSetting plugins = this.register(new BooleanSetting("Plugins",
            "Also runs the engines in sigma5/selfcheck/plugins/*.jar.", false));

    private final TextSetting prefix = this.register(new TextSetting("Command Prefix",
            "Local commands start with this, as in .grim alerts. Such lines are answered here and never sent to the server.", ".", 4));

    private final BooleanSetting record = this.register(new BooleanSetting("Record",
            "Writes each connection's traffic to sigma5/selfcheck/recordings/ for replay tests. The files are large.", false));

    private @Nullable ServerRoot root;
    private final Map<String, Long> lastSetbackNotice = new ConcurrentHashMap<>();
    // Lines said while the player is not in a world yet - a session starts on the connecting screen - wait here and
    // are shown on arrival, in order. Parsed only then, when the connection's registries exist.
    private final Queue<Supplier<Component>> waiting = new ConcurrentLinkedQueue<>();

    public ModuleSelfDetection() {
        super(ModuleCategory.MISC, "SelfDetection",
                "Runs a real anticheat (GrimAC) on your own traffic and tells you what it would flag, kick or ban you for.");
    }

    @Override
    protected void onEnable() {
        if (mc.getConnection() != null && SelfCheck.current() == null) {
            ChatUtil.print("§b[SelfCheck] §7Starts checking the next time you join a server.");
        }
    }

    @Override
    protected void onDisable() {
        this.waiting.clear();
        SelfCheckSession session = SelfCheck.current();
        if (session != null) {
            session.close("module switched off");
        }
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (event.isPre() && !this.waiting.isEmpty() && inWorld()) {
            this.flush();
        }
    }

    /**
     * Sigma hook from {@code Connection.connect}: a connection to a server is being opened. Runs on the connection's
     * network thread while its pipeline is being set up.
     */
    public void attach(final Channel channel, final ProtocolVersion version, final InetSocketAddress address) {
        this.waiting.clear(); // anything still waiting was about a connection that never reached a world
        String where = address.getHostString() + ":" + address.getPort();
        if (version.getVersionType() != VersionType.RELEASE || version.olderThan(ProtocolVersion.v1_8)) {
            this.notice("Not checking " + where + ": " + version.getName() + " is not supported (1.8 and newer releases only).");
            return;
        }

        try {
            ServerRoot serverRoot = this.root();
            Target target = new Target(version.getVersion(), version.getName(), where,
                    mc.getUser().getProfileId(), mc.getUser().getName());
            SelfCheck.Settings settings = new SelfCheck.Settings(this.grim.get(), this.plugins.get(), this.record.get(),
                    Boolean.getBoolean("sigma.debug.selfcheck.diagnostics"), this.prefix.get());
            SelfCheck.start(channel, target, serverRoot, new Announcer(), settings);
        } catch (RuntimeException e) {
            // never let the self-check stand between the player and the server
            LOGGER.warn("[Sigma/SelfCheck] could not start for {}", where, e);
            this.notice("Could not start for " + where + ": " + e);
        }
    }

    /**
     * Sigma hook from {@code ClientPacketListener.sendChat}: returns true when {@code message} is a self-check command,
     * which has then been answered and must not be sent.
     */
    public boolean interceptChat(final String message) {
        return LocalCommands.handle(message, this.prefix.get(), SelfCheck.current(), reply -> ChatUtil.print("§b[SelfCheck] §7" + reply));
    }

    private ServerRoot root() {
        ServerRoot current = this.root;
        if (current == null) {
            current = new ServerRoot(Client.getInstance().getDirectory().resolve("selfcheck"));
            this.root = current;
        }
        return current;
    }

    private void notice(final String text) {
        this.print(Component.literal("§b[SelfCheck] §7" + text));
    }

    private void print(final Component line) {
        this.print(() -> line);
    }

    /** Shows {@code line} in the chat now if the player is in a world, else on arrival. Any thread. */
    private void print(final Supplier<Component> line) {
        Minecraft client = mc;
        if (!client.isSameThread()) {
            client.execute(() -> this.print(line));
            return;
        }
        this.waiting.add(line);
        if (inWorld()) {
            this.flush();
        }
    }

    private void flush() {
        Supplier<Component> line;
        while ((line = this.waiting.poll()) != null) {
            ChatUtil.print(line.get());
        }
    }

    /** In a world with its terrain on screen, rather than connecting or loading it. */
    private static boolean inWorld() {
        return mc.player != null && !(mc.gui.screen() instanceof LevelLoadingScreen);
    }

    /** Parses a JSON text component with the connection's registries when there is one (item hovers need them). */
    private static Component parse(final String json) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        DynamicOps<JsonElement> ops = connection != null
                ? RegistryOps.create(JsonOps.INSTANCE, connection.registryAccess())
                : JsonOps.INSTANCE;
        return ComponentSerialization.CODEC.parse(ops, JsonParser.parseString(json))
                .result()
                .orElseGet(() -> Component.literal(json));
    }

    /** Where a session's verdicts go: the chat, as the engine asked or as a one-line summary. */
    private final class Announcer implements SelfCheckOutput {

        @Override
        public void flag(final String engine, final String check, final double violations, final String verbose) {
            // Logged by the session. An engine decides for itself which flags become chat lines (Grim's alerts and
            // verbose output arrive as messages).
        }

        @Override
        public void message(final String engine, final String componentJson) {
            ModuleSelfDetection.this.print(() -> parse(componentJson));
        }

        @Override
        public void punishment(final String engine, final String command) {
            String verb = command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String label = BANS.contains(verb) ? "§4§lBAN RISK §c" : "§c";
            ModuleSelfDetection.this.print(Component.literal("§b[SelfCheck] " + label + engine + " would run: §f" + command));
        }

        @Override
        public void setback(final String engine, final String reason) {
            long now = System.nanoTime();
            Long last = ModuleSelfDetection.this.lastSetbackNotice.get(engine);
            if (last != null && now - last < SETBACK_NOTICE_INTERVAL) {
                return; // logged every time, announced at most every two seconds
            }
            ModuleSelfDetection.this.lastSetbackNotice.put(engine, now);
            ModuleSelfDetection.this.print(Component.literal("§b[SelfCheck] §e" + engine + " would set you back: §f" + reason));
        }

        @Override
        public void disconnect(final String engine, final String reason) {
            ModuleSelfDetection.this.print(Component.literal("§b[SelfCheck] §c" + engine + " would disconnect you: §f" + reason));
        }

        @Override
        public void notice(final String text) {
            ModuleSelfDetection.this.notice(text);
        }
    }
}
