package com.mentalfrostbyte.jello.module.impl.world;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.jspecify.annotations.Nullable;

/** The four Grim options from the old SigmaClient's HeypixelDisabler, using the current packet API. */
public final class Disabler extends Module {
    private final BooleanSetting badPacketA = register(new BooleanSetting("DisBadPacketA",
            "Grim: filters repeated outgoing hotbar selections.", true));
    private final BooleanSetting badPacketF = register(new BooleanSetting("DisBadPacketF",
            "Grim: filters repeated start/stop sprint commands.", true));
    private final BooleanSetting aimModulo360 = register(new BooleanSetting("DisAimModulo360",
            "Legacy Grim option: adds cycling full turns to reported yaw without turning the camera.", true));
    private final BooleanSetting duplicateRotPlace = register(new BooleanSetting("DisDuplicateRotPlace",
            "Legacy Grim option: nudges yaw when a turn repeats the last placement's turn size.", true));

    private final GrimDisablerState state = new GrimDisablerState();
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private @Nullable LocalPlayer owner;
    private @Nullable ClientLevel level;
    private volatile @Nullable Connection connection;

    public Disabler() {
        super(ModuleCategory.WORLD, "Disabler", "Ports the old project's four Grim packet options; effectiveness depends on the server version.");
    }

    /**
     * Called by Connection after all cancellable listeners. A buffered packet is processed only when actually
     * resubmitted, and observers retaining the event see the final packet without depending on enable order.
     */
    public static void filterOutgoing(final Connection source, final EventSendPacket event) {
        if (event.isCancelled() || !relevant(event.getPacket())) return;
        Disabler module = Modules.enabled(Disabler.class);
        if (module != null) module.filter(source, event);
    }

    private void filter(final Connection source, final EventSendPacket event) {
        if (!mc.isSameThread()) {
            // No world reads or mutable history from a network-thread sender. The next game-thread packet starts fresh.
            if (connection == source) invalidated.set(true);
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.connection.getConnection() != source || !source.isConnected()) return;
        boolean externalChange = invalidated.getAndSet(false);
        if (owner != player || level != mc.level || connection != source || externalChange) {
            reset();
            owner = player;
            level = mc.level;
            connection = source;
        }
        state.apply(event, player.getId(), new GrimDisablerState.Options(badPacketA.get(), badPacketF.get(),
                aimModulo360.get(), duplicateRotPlace.get()));
    }

    /**
     * Vanilla invokes this only after applying the thread guard, immediately before a server correction or held-slot
     * change. Resetting here keeps correction replies unchanged even if other tasks ran since packet receipt.
     */
    public static void serverChangedState(final Connection source) {
        Disabler module = Modules.enabled(Disabler.class);
        if (module != null && mc.isSameThread() && mc.player != null && mc.player.connection.getConnection() == source) {
            module.reset();
        }
    }

    /** Accept only our own bounded wire transformation when an automation verifies that its look was reported. */
    public static boolean matchesTransformedRotation(final float yaw, final float pitch,
                                                     final float expectedYaw, final float expectedPitch) {
        Disabler module = Modules.enabled(Disabler.class);
        return module != null && mc.isSameThread() && module.owner != null && module.owner == mc.player
                && module.level == mc.level && module.connection != null && module.connection == mc.player.connection.getConnection()
                && !module.invalidated.get() && module.connection.isConnected()
                && module.state.matchesTransformedLook(yaw, pitch, expectedYaw, expectedPitch);
    }

    private static boolean relevant(final Packet<?> packet) {
        return packet instanceof ServerboundSetCarriedItemPacket || packet instanceof ServerboundPlayerCommandPacket
                || packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundUseItemPacket;
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (event.isPre() && (owner != mc.player || level != mc.level || connection != null && !connection.isConnected())) reset();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) { reset(); }

    @Override
    protected void onEnable() { reset(); }

    @Override
    protected void onDisable() { reset(); }

    private void reset() {
        state.reset();
        // Leave a concurrent network-thread invalidation intact; the next final filter consumes it.
        owner = null;
        level = null;
        connection = null;
    }
}
