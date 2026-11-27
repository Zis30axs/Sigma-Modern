package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.relay.RelayCoordinator;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;

/**
 * Native client-integrated session relay ({@code mc-relay}): hosts a local/LAN 26.2 proxy so another
 * client {@code B} can connect in offline mode, reconstruct Host {@code A}'s live world from memory,
 * and seamlessly take over {@code A}'s session across any ViaFabricPlus-supported server version.
 */
public class Relay extends Module {

    public static final int DEFAULT_PORT = 25577;

    private final TextSetting port = this.register(new TextSetting(
        "Port",
        "TCP port for the embedded Sigma Relay proxy (1024-65535).",
        Integer.toString(DEFAULT_PORT),
        5
    ));

    private final BooleanSetting allowLan = this.register(new BooleanSetting(
        "Allow LAN",
        "Bind on 0.0.0.0 and advertise over LAN discovery (224.0.2.60:4445) instead of localhost-only.",
        true
    ));

    private final BooleanSetting followController = this.register(new BooleanSetting(
        "Follow Controller",
        "Mirror the controller's live position, camera rotation, hotbar slot, and swings on this host client.",
        true
    ));

    private final BooleanSetting hudStatus = this.register(new BooleanSetting(
        "HUD Status",
        "Display a compact Relay status pill at the top of the HUD while enabled.",
        true
    ));

    private int activeConfiguredPort = -1;
    private boolean activeAllowLan = true;

    public Relay() {
        super(
            ModuleCategory.MISC,
            "Relay",
            "Hosts a local/LAN session relay so another client can take over this connection live"
        );
    }

    public int parsedPort() {
        return parsePortOrDefault(this.port.get(), DEFAULT_PORT);
    }

    public void setPortValue(final int newPort) {
        if (newPort < 1 || newPort > 65535) {
            throw new IllegalArgumentException("Port out of range: " + newPort);
        }
        this.port.set(Integer.toString(newPort));
    }

    public boolean isAllowLan() {
        return this.allowLan.get();
    }

    public boolean isFollowController() {
        return this.followController.get();
    }

    public boolean showsHudStatus() {
        return this.hudStatus.get();
    }

    public static int parsePortOrDefault(final String raw, final int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return (parsed >= 1 && parsed <= 65535) ? parsed : fallback;
        } catch (final NumberFormatException invalid) {
            return fallback;
        }
    }

    /** Returns the port configured on the registered {@link Relay} module, or {@link #DEFAULT_PORT}. */
    public static int configuredPort() {
        if (Minecraft.getInstance() == null) {
            return DEFAULT_PORT;
        }
        try {
            Client client = Client.getInstance();
            if (client != null && client.getModuleManager() != null) {
                return client.getModuleManager().get(Relay.class).parsedPort();
            }
        } catch (final RuntimeException ignored) {
        }
        return DEFAULT_PORT;
    }

    /** Hook called from {@code Connection} when Host A opens a play or singleplayer channel. */
    public static void onPlayChannelInit(final Channel channel) {
        Relay relay = Modules.enabled(Relay.class);
        if (relay != null) {
            RelayCoordinator.get().attachConnectionChannel(channel);
        }
    }

    @Override
    protected void onEnable() {
        int desiredPort = parsedPort();
        boolean lan = isAllowLan();
        this.activeConfiguredPort = desiredPort;
        this.activeAllowLan = lan;
        if (Minecraft.getInstance() != null) {
            RelayCoordinator.get().startProxy(desiredPort, lan);
        }
    }

    @Override
    protected void onDisable() {
        this.activeConfiguredPort = -1;
        if (RelayCoordinator.get().isProxyRunning()) {
            RelayCoordinator.get().stopProxy();
        }
    }

    @EventTarget
    public void onTick(final EventTick event) {
        RelayCoordinator coordinator = RelayCoordinator.get();
        int desiredPort = parsedPort();
        boolean lan = isAllowLan();
        if (!coordinator.isProxyRunning()
            || desiredPort != this.activeConfiguredPort
            || lan != this.activeAllowLan) {
            this.activeConfiguredPort = desiredPort;
            this.activeAllowLan = lan;
            coordinator.startProxy(desiredPort, lan);
        } else {
            coordinator.ensureAttachedToCurrentConnection();
        }

        if (isFollowController() && coordinator.isControllerActivelyControlling() && mc.player != null) {
            coordinator.mirror().applyToHostPlayer(mc.player);
        }
    }

    @EventTarget(EventPriority.LOWEST)
    public void onMovementInput(final EventMovementInput event) {
        if (RelayCoordinator.get().isControllerActivelyControlling()) {
            event.setInput(new Input(false, false, false, false, false, false, false));
        }
    }
}
