package com.mentalfrostbyte.jello.relay;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reconstructs Host A's live 26.2 client memory state into native 26.2 {@code Configuration} and {@code Play}
 * packets for Controller B.
 *
 * <p>All reads of {@link ClientLevel}, {@link ClientChunkCache}, {@link LocalPlayer}, {@link Scoreboard}, and
 * {@link Entity} instances execute on {@link Minecraft}'s main thread to avoid data races with client ticks.</p>
 */
public final class RelayWorldSnapshot {

    private static final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> TAB_INIT_ACTIONS = EnumSet.of(
        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
        ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER
    );

    private RelayWorldSnapshot() {
    }

    /** Executes {@code supplier} on {@link Minecraft}'s main thread with a bounded timeout. */
    private static <T> T onMainThread(final Minecraft mc, final java.util.function.Supplier<T> supplier) {
        if (mc.isSameThread()) {
            return supplier.get();
        }
        return CompletableFuture.supplyAsync(supplier, mc).orTimeout(10L, TimeUnit.SECONDS).join();
    }

    public static GameProfile readHostProfile(final Minecraft mc) {
        return onMainThread(mc, () -> {
            ClientPacketListener listener = mc.getConnection();
            if (listener != null) {
                return listener.getLocalGameProfile();
            }
            return mc.getGameProfile();
        });
    }

    public static boolean isHostReadyInPlay(final Minecraft mc) {
        return onMainThread(mc, () -> mc.player != null && mc.level != null && mc.getConnection() != null);
    }

    /**
     * Builds the full 26.2 {@code Configuration} packet sequence (inline-NBT synchronized registries,
     * enabled feature flags, and network tags) from Host A's active {@link ClientPacketListener}.
     */
    public static List<ByteBuf> buildConfigurationPackets(final Minecraft mc) {
        return onMainThread(mc, () -> {
            ClientPacketListener listener = mc.getConnection();
            if (listener == null) {
                return List.of();
            }
            RegistryAccess.Frozen registries = listener.registryAccess();
            List<ByteBuf> packets = new ArrayList<>();

            // Pass Set.of() for clientKnownPacks so every entry embeds its full inline NBT Tag,
            // guaranteeing exact element parity even on ViaFabricPlus-translated legacy servers.
            RegistrySynchronization.packRegistries(
                registries.createSerializationContext(NbtOps.INSTANCE),
                registries,
                Set.of(),
                (registryKey, entries) -> packets.add(
                    encodeConfig(new ClientboundRegistryDataPacket(registryKey, entries))
                )
            );

            packets.add(encodeConfig(new ClientboundUpdateEnabledFeaturesPacket(
                FeatureFlags.REGISTRY.toNames(listener.enabledFeatures())
            )));
            packets.add(encodeConfig(new ClientboundUpdateTagsPacket(
                TagNetworkSerialization.serializeTagsToNetwork(registries)
            )));
            return packets;
        });
    }

    /** Builds the 26.2 {@link ClientboundLoginPacket} ({@code JoinGame}) with {@code onlineMode=false}. */
    public static ByteBuf buildJoinGamePacket(final Minecraft mc) {
        return onMainThread(mc, () -> {
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            ClientPacketListener listener = mc.getConnection();
            MultiPlayerGameMode gameMode = mc.gameMode;
            if (player == null || level == null || listener == null || gameMode == null) {
                throw new IllegalStateException("Host client is not currently in a world");
            }

            ProtocolInfo<ClientGamePacketListener> playProtocol = bindPlayClientbound(listener.registryAccess());
            Set<ResourceKey<Level>> levelKeys = listener.levels();
            if (levelKeys == null || levelKeys.isEmpty()) {
                levelKeys = Set.of(level.dimension());
            } else {
                levelKeys = Set.copyOf(levelKeys);
            }

            GameType currentMode = gameMode.getPlayerMode();
            GameType previousMode = gameMode.getPreviousPlayerMode();
            CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
                level.dimensionTypeRegistration(),
                level.dimension(),
                0L,
                currentMode,
                previousMode,
                level.isDebug(),
                level.getLevelData().voidDarknessOnsetRange() <= 1.0F,
                player.getLastDeathLocation(),
                player.getPortalCooldown(),
                level.getSeaLevel()
            );

            int chunkRadius = Math.max(2, listener.serverChunkRadius());
            int simDistance = Math.max(2, listener.serverSimulationDistance());
            ClientboundLoginPacket loginPacket = new ClientboundLoginPacket(
                player.getId(),
                level.getLevelData().isHardcore(),
                levelKeys,
                20,
                chunkRadius,
                simDistance,
                player.isReducedDebugInfo(),
                player.shouldShowDeathScreen(),
                player.getDoLimitedCrafting(),
                spawnInfo,
                false, // onlineMode = false so B never enables strict chat-signing enforcement locally
                false  // enforcesSecureChat = false
            );
            return RelayPacketIds.encodePacket(playProtocol, loginPacket);
        });
    }

    /**
     * Snapshots Host A's live world state on the main thread and streams native 26.2 {@code PLAY}
     * packets into {@code sink}.
     */
    public static void streamWorldState(final Minecraft mc, final Consumer<ByteBuf> sink) {
        List<ByteBuf> encoded = onMainThread(mc, () -> collectWorldPacketsOnMainThread(mc));
        for (ByteBuf buf : encoded) {
            sink.accept(buf);
        }
    }

    private static List<ByteBuf> collectWorldPacketsOnMainThread(final Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        ClientPacketListener listener = mc.getConnection();
        if (player == null || level == null || listener == null) {
            return List.of();
        }

        ProtocolInfo<ClientGamePacketListener> protocol = bindPlayClientbound(listener.registryAccess());
        List<ByteBuf> out = new ArrayList<>(512);

        // 1. Difficulty, abilities, selected hotbar slot, permission status
        out.add(encodePlay(protocol, new ClientboundChangeDifficultyPacket(
            level.getDifficulty(),
            level.getLevelData().isDifficultyLocked()
        )));
        out.add(encodePlay(protocol, new ClientboundPlayerAbilitiesPacket(player.getAbilities())));
        out.add(encodePlay(protocol, new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot())));
        out.add(encodePlay(protocol, new ClientboundEntityEventPacket(player, (byte) 28)));

        // 2. Tab list (all PlayerInfo entries, preserving listed vs unlisted so NPC skins resolve cleanly)
        Set<PlayerInfo> listedSet = new HashSet<>(listener.getListedOnlinePlayers());
        Set<UUID> knownProfileIds = new HashSet<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> tabEntries = new ArrayList<>();
        for (PlayerInfo info : listener.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            knownProfileIds.add(profile.id());
            tabEntries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
                profile.id(),
                profile,
                listedSet.contains(info),
                info.getLatency(),
                info.getGameMode(),
                info.getTabListDisplayName(),
                info.showHat(),
                info.getTabListOrder(),
                null // chatSession omitted so B validates unsigned relay messages cleanly
            ));
        }
        if (!tabEntries.isEmpty()) {
            out.add(encodePlay(protocol, ClientboundPlayerInfoUpdatePacket.fromEntries(TAB_INIT_ACTIONS, tabEntries)));
        }

        // 3. Default spawn & initial player position/rotation
        out.add(encodePlay(protocol, new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData())));
        ClientboundPlayerPositionPacket initialPos = new ClientboundPlayerPositionPacket(
            0,
            new PositionMoveRotation(player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot()),
            Set.of()
        );
        out.add(encodePlay(protocol, initialPos));

        // 4. World time & weather
        out.add(encodePlay(protocol, new ClientboundSetTimePacket(level.getGameTime(), Map.of())));
        float rainLevel = level.getRainLevel(1.0F);
        float thunderLevel = level.getThunderLevel(1.0F);
        if (level.isRaining() || rainLevel > 0.0F) {
            out.add(encodePlay(protocol, new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F)));
            out.add(encodePlay(protocol, new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel)));
            out.add(encodePlay(protocol, new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel)));
        }

        // 5. Chunk cache bounds + LEVEL_CHUNKS_LOAD_START (required in modern protocols to dismiss loading screen)
        ClientChunkCache chunkCache = level.getChunkSource();
        int playerChunkX = SectionPosCoord(player.getBlockX());
        int playerChunkZ = SectionPosCoord(player.getBlockZ());
        int centerX = chunkCache.viewCenterX() != 0 || chunkCache.viewCenterZ() != 0 ? chunkCache.viewCenterX() : playerChunkX;
        int centerZ = chunkCache.viewCenterX() != 0 || chunkCache.viewCenterZ() != 0 ? chunkCache.viewCenterZ() : playerChunkZ;
        out.add(encodePlay(protocol, new ClientboundSetChunkCacheRadiusPacket(Math.max(2, listener.serverChunkRadius()))));
        out.add(encodePlay(protocol, new ClientboundSetSimulationDistancePacket(Math.max(2, listener.serverSimulationDistance()))));
        out.add(encodePlay(protocol, new ClientboundSetChunkCacheCenterPacket(centerX, centerZ)));
        out.add(encodePlay(protocol, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F)));

        // 6. Loaded chunks with full sky + block light sections, sorted radially from player
        List<LevelChunk> chunks = chunkCache.loadedChunks();
        chunks.sort(Comparator.comparingInt(c -> {
            ChunkPos pos = c.getPos();
            int dx = pos.x() - playerChunkX;
            int dz = pos.z() - playerChunkZ;
            return dx * dx + dz * dz;
        }));
        LevelLightEngine lightEngine = chunkCache.getLightEngine();
        out.add(encodePlay(protocol, ClientboundChunkBatchStartPacket.INSTANCE));
        int sentChunks = 0;
        for (LevelChunk chunk : chunks) {
            try {
                out.add(encodePlay(protocol, new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)));
                sentChunks++;
            } catch (final RuntimeException ignored) {
                // Skip any concurrently unloading chunk rather than aborting the snapshot.
            }
        }
        out.add(encodePlay(protocol, new ClientboundChunkBatchFinishedPacket(sentChunks)));

        // 7. Teams & Scoreboard (teams first so nametag colors/prefixes immediately apply)
        Scoreboard scoreboard = level.getScoreboard();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            out.add(encodePlay(protocol, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true)));
        }
        for (Objective objective : scoreboard.getObjectives()) {
            out.add(encodePlay(protocol, new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD)));
            for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
                out.add(encodePlay(protocol, new ClientboundSetScorePacket(
                    score.owner(),
                    objective.getName(),
                    score.value(),
                    Optional.ofNullable(score.display()),
                    Optional.ofNullable(score.numberFormatOverride())
                )));
            }
        }
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective displayed = scoreboard.getDisplayObjective(slot);
            if (displayed != null) {
                out.add(encodePlay(protocol, new ClientboundSetDisplayObjectivePacket(slot, displayed)));
            }
        }

        // 8. Inventory, open container, health, food, XP, player attributes/effects/metadata
        sendMenuSnapshot(protocol, player.inventoryMenu, out);
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            sendMenuSnapshot(protocol, player.containerMenu, out);
        }
        out.add(encodePlay(protocol, new ClientboundSetHealthPacket(
            player.getHealth(),
            player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel()
        )));
        out.add(encodePlay(protocol, new ClientboundSetExperiencePacket(
            player.experienceProgress,
            player.totalExperience,
            player.experienceLevel
        )));
        out.add(encodePlay(protocol, new ClientboundUpdateAttributesPacket(
            player.getId(),
            player.getAttributes().getSyncableAttributes()
        )));
        for (MobEffectInstance effect : player.getActiveEffects()) {
            out.add(encodePlay(protocol, new ClientboundUpdateMobEffectPacket(player.getId(), effect, false)));
        }
        List<SynchedEntityData.DataValue<?>> localPlayerData = player.getEntityData().packAll();
        if (!localPlayerData.isEmpty()) {
            out.add(encodePlay(protocol, new ClientboundSetEntityDataPacket(player.getId(), localPlayerData)));
        }

        // 9. World entities (skip local player & skip negative-ID client-only FakePlayer entities)
        List<Entity> vehiclesWithPassengers = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || entity == player || entity.isRemoved() || entity.getId() < 0) {
                continue;
            }
            try {
                if (entity instanceof RemotePlayer remotePlayer && !knownProfileIds.contains(remotePlayer.getUUID())) {
                    knownProfileIds.add(remotePlayer.getUUID());
                    ClientboundPlayerInfoUpdatePacket.Entry npcEntry = new ClientboundPlayerInfoUpdatePacket.Entry(
                        remotePlayer.getUUID(),
                        remotePlayer.getGameProfile(),
                        false,
                        0,
                        GameType.SURVIVAL,
                        null,
                        remotePlayer.isModelPartShown(PlayerModelPart.HAT),
                        0,
                        null
                    );
                    out.add(encodePlay(protocol, ClientboundPlayerInfoUpdatePacket.fromEntries(TAB_INIT_ACTIONS, List.of(npcEntry))));
                }

                out.add(encodePlay(protocol, buildAddEntityPacket(entity)));

                List<SynchedEntityData.DataValue<?>> entityData = entity.getEntityData().packAll();
                if (!entityData.isEmpty()) {
                    out.add(encodePlay(protocol, new ClientboundSetEntityDataPacket(entity.getId(), entityData)));
                }
                out.add(encodePlay(protocol, new ClientboundRotateHeadPacket(entity, Mth.packDegrees(entity.getYHeadRot()))));

                Vec3 motion = entity.getDeltaMovement();
                if (motion.lengthSqr() > 1.0E-6) {
                    out.add(encodePlay(protocol, new ClientboundSetEntityMotionPacket(entity)));
                }

                if (entity instanceof LivingEntity living) {
                    List<Pair<EquipmentSlot, ItemStack>> equipment = collectEquipment(living);
                    if (!equipment.isEmpty()) {
                        out.add(encodePlay(protocol, new ClientboundSetEquipmentPacket(living.getId(), equipment)));
                    }
                    Collection<net.minecraft.world.entity.ai.attributes.AttributeInstance> attrs =
                        living.getAttributes().getSyncableAttributes();
                    if (!attrs.isEmpty()) {
                        out.add(encodePlay(protocol, new ClientboundUpdateAttributesPacket(living.getId(), attrs)));
                    }
                }

                if (!entity.getPassengers().isEmpty()) {
                    vehiclesWithPassengers.add(entity);
                }
            } catch (final RuntimeException ignored) {
                // Skip individual problematic entities without aborting world reconstruction.
            }
        }

        // Attach passengers after all vehicle & passenger entities have been spawned on B
        for (Entity vehicle : vehiclesWithPassengers) {
            out.add(encodePlay(protocol, new ClientboundSetPassengersPacket(vehicle)));
        }
        if (player.getVehicle() != null && player.getVehicle().getId() >= 0) {
            out.add(encodePlay(protocol, new ClientboundSetPassengersPacket(player.getVehicle())));
        }

        // 10. Final position & spawn confirmation after chunks are loaded
        out.add(encodePlay(protocol, new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData())));
        out.add(encodePlay(protocol, initialPos));
        return out;
    }

    private static int SectionPosCoord(final int blockCoord) {
        return blockCoord >> 4;
    }

    private static void sendMenuSnapshot(
        final ProtocolInfo<ClientGamePacketListener> protocol,
        final AbstractContainerMenu menu,
        final List<ByteBuf> out
    ) {
        List<ItemStack> items = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            items.add(menu.slots.get(i).getItem().copy());
        }
        ItemStack carried = menu.getCarried().copy();
        out.add(encodePlay(protocol, new ClientboundContainerSetContentPacket(
            menu.containerId,
            menu.getStateId(),
            items,
            carried
        )));
    }

    private static ClientboundAddEntityPacket buildAddEntityPacket(final Entity entity) {
        int extraData = 0;
        if (entity instanceof FallingBlockEntity fallingBlock) {
            extraData = Block.getId(fallingBlock.getBlockState());
        } else if (entity instanceof HangingEntity hanging) {
            extraData = hanging.getDirection().get3DDataValue();
            BlockPos pos = hanging.getPos();
            return new ClientboundAddEntityPacket(entity, extraData, pos);
        }
        return new ClientboundAddEntityPacket(
            entity.getId(),
            entity.getUUID(),
            entity.getX(),
            entity.getY(),
            entity.getZ(),
            entity.getXRot(),
            entity.getYRot(),
            entity.getType(),
            extraData,
            entity.getDeltaMovement(),
            entity.getYHeadRot()
        );
    }

    private static List<Pair<EquipmentSlot, ItemStack>> collectEquipment(final LivingEntity living) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = living.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                list.add(Pair.of(slot, stack.copy()));
            }
        }
        return list;
    }

    private static ProtocolInfo<ClientGamePacketListener> bindPlayClientbound(final RegistryAccess registries) {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
    }

    private static ByteBuf encodeConfig(final Packet<? super ClientConfigurationPacketListener> packet) {
        return RelayPacketIds.encodePacket(ConfigurationProtocols.CLIENTBOUND, packet);
    }

    private static ByteBuf encodePlay(
        final ProtocolInfo<ClientGamePacketListener> protocol,
        final Packet<? super ClientGamePacketListener> packet
    ) {
        return RelayPacketIds.encodePacket(protocol, packet);
    }
}
