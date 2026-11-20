package com.mentalfrostbyte.jello.anticheat.client;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
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

    /** Swift Sneak III maximum sneaking speed factor (0.3 + 3 * 0.15). */
    private static final double SWIFT_SNEAK_MAX = 0.75;

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
                player.sigmaShowsEffectParticles(),
                player.getYHeadRot(),
                player.getXRot(),
                player.getAttributeValue(Attributes.STEP_HEIGHT),
                resolveSneakingSpeed(player));
    }

    /**
     * Resolves a remote player's sneaking speed across protocol versions:
     * <ul>
     *   <li><b>1.21+</b>: {@link Attributes#SNEAKING_SPEED} is syncable and broadcast by the server, including
     *       Swift Sneak modifiers.</li>
     *   <li><b>1.19–1.20.6</b>: {@code SNEAKING_SPEED} did not exist as a synced attribute and ViaFabricPlus
     *       only emulates it for the local player. Read Swift Sneak from leggings when visible, and conservatively
     *       assume Swift Sneak III (0.75) if the leggings show an enchantment glint whose NBT was stripped.</li>
     *   <li><b>1.18.2 and older</b>: Swift Sneak did not exist, so 0.3 is the vanilla ceiling.</li>
     * </ul>
     */
    private static double resolveSneakingSpeed(final RemotePlayer player) {
        ProtocolVersion target = ProtocolTranslator.getTargetVersion();
        if (target.newerThan(ProtocolVersion.v1_20_5)) {
            return player.getAttributeValue(Attributes.SNEAKING_SPEED);
        }

        if (target.olderThan(ProtocolVersion.v1_19)) {
            return Sample.DEFAULT_SNEAKING_SPEED;
        }

        ItemStack leggings = player.getItemBySlot(EquipmentSlot.LEGS);
        if (leggings.isEmpty()) {
            return Sample.DEFAULT_SNEAKING_SPEED;
        }

        int swiftSneak = player.level().registryAccess()
                .lookup(Enchantments.SWIFT_SNEAK.registryKey())
                .flatMap(reg -> reg.get(Enchantments.SWIFT_SNEAK))
                .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, leggings))
                .orElse(0);
        if (swiftSneak > 0) {
            return Math.min(1.0, Sample.DEFAULT_SNEAKING_SPEED + swiftSneak * 0.15);
        }

        // Many 1.19-1.20.6 servers obfuscate remote armor enchantment NBT while preserving the glint.
        if (leggings.hasFoil() || leggings.isEnchanted()) {
            return SWIFT_SNEAK_MAX;
        }

        return Sample.DEFAULT_SNEAKING_SPEED;
    }
}
