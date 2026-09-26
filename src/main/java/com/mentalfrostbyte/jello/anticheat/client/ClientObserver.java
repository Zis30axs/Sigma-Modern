package com.mentalfrostbyte.jello.anticheat.client;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Where the game's packet handlers meet the detector. Each method is called from a handler on the game
 * thread, after vanilla has applied the packet, and turns what the handler has on hand into the plain values
 * {@link ObservedPlayers} works with.
 *
 * <p>Only {@link RemotePlayer}s that are on the tab list are watched: the local player is the vanilla
 * simulation itself, and an entity with a player's shape but no tab entry is a server-side NPC whose
 * "movement" is scripted.</p>
 */
public final class ClientObserver {

    private final ObservedPlayers players;
    private final Supplier<CheckSettings> settings;

    public ClientObserver(final ObservedPlayers players, final Supplier<CheckSettings> settings) {
        this.players = players;
        this.settings = settings;
    }

    public ObservedPlayers players() {
        return this.players;
    }

    public CheckSettings settings() {
        return this.settings.get();
    }

    /** The server reported where {@code entity} is. {@code position} is the decoded absolute position. */
    public void onPosition(final Entity entity, final Vec3 position, final boolean onGround) {
        if (!(entity instanceof RemotePlayer player)) {
            return;
        }

        long now = System.nanoTime();
        PlayerInfo info = playerInfo(player);
        if (info == null) {
            return;
        }

        TrackedPlayer tracked = this.players.track(player.getId(), player.getUUID(), player.getGameProfile().name(), now);
        this.players.onPosition(tracked.entityId(), position.x, position.y, position.z, onGround, snapshot(player, info),
                now, this.settings.get());
    }

    public void onTeleport(final Entity entity) {
        this.players.onTeleport(entity.getId(), System.nanoTime());
    }

    /** The server pushed {@code entity} - knockback, an explosion, a launch. */
    public void onImpulse(final Entity entity) {
        this.players.onImpulse(entity.getId(), System.nanoTime());
    }

    public void onRemoved(final int entityId) {
        this.players.untrack(entityId);
    }

    public void onBlockChanged(final BlockPos pos) {
        this.players.onBlocksChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                System.nanoTime());
    }

    public void onSectionChanged(final SectionPos section) {
        this.players.onBlocksChanged(section.minBlockX(), section.minBlockY(), section.minBlockZ(),
                section.maxBlockX() + 1, section.maxBlockY() + 1, section.maxBlockZ() + 1, System.nanoTime());
    }

    public void clear() {
        this.players.clear();
    }

    private static PlayerInfo playerInfo(final RemotePlayer player) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection == null ? null : connection.getPlayerInfo(player.getUUID());
    }

    private static ObservedPlayers.Snapshot snapshot(final RemotePlayer player, final PlayerInfo info) {
        int flags = 0;
        flags |= player.isSprinting() ? PlayerFlags.SPRINTING : 0;
        flags |= player.isCrouching() ? PlayerFlags.SNEAKING : 0;
        flags |= player.isUsingItem() ? PlayerFlags.USING_ITEM : 0;
        flags |= player.isSwimming() ? PlayerFlags.SWIMMING : 0;
        flags |= player.isFallFlying() ? PlayerFlags.GLIDING : 0;
        flags |= player.isAutoSpinAttack() ? PlayerFlags.SPIN_ATTACK : 0;
        flags |= player.isPassenger() ? PlayerFlags.PASSENGER : 0;
        flags |= player.isSleeping() ? PlayerFlags.SLEEPING : 0;
        flags |= player.isDeadOrDying() ? PlayerFlags.DEAD : 0;
        GameType mode = info.getGameMode();
        flags |= mode == GameType.CREATIVE || mode == GameType.SPECTATOR ? PlayerFlags.EXEMPT_MODE : 0;

        return new ObservedPlayers.Snapshot(flags,
                player.getAttributeValue(Attributes.MOVEMENT_SPEED),
                player.getAttributeValue(Attributes.JUMP_STRENGTH),
                player.getAttributeValue(Attributes.GRAVITY),
                player.sigmaShowsEffectParticles());
    }
}
