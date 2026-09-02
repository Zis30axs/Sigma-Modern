package net.minecraft.client.multiplayer;

import com.viaversion.viafabricplus.features.interaction.r1_18_2_block_ack_emulation.ClientPlayerInteractionManager1_18_2;
import com.viaversion.viafabricplus.features.interaction.replace_block_placement_logic.ActionResultException1_12_2;
import com.viaversion.viafabricplus.injection.access.interaction.container_clicking.IAbstractContainerMenu;
import com.viaversion.viafabricplus.injection.access.interaction.r1_18_2_block_ack_emulation.IMultiPlayerGameMode;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.protocoltranslator.impl.provider.viaversion.ViaFabricPlusHandItemProvider;
import com.viaversion.viafabricplus.protocoltranslator.translator.ItemTranslator;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_16_1to1_16_2.packet.ServerboundPackets1_16_2;
import com.viaversion.viaversion.protocols.v1_16_4to1_17.Protocol1_16_4To1_17;
import com.viaversion.viaversion.protocols.v1_21_2to1_21_4.packet.ServerboundPackets1_21_4;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.Protocol1_21_4To1_21_5;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;
import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class MultiPlayerGameMode implements IMultiPlayerGameMode { // MODIFIED for porting: was VFP r1_18_2_block_ack_emulation MixinMultiPlayerGameMode
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Minecraft minecraft;
    private final ClientPacketListener connection;
    // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#viaFabricPlus$oldCursorStack
    // (@Unique). The <= 1.16.4 container-click packet carries the slot item as it was BEFORE the click, so
    // the carried stack and the pre-click slot list have to be kept until the legacy writer consumed them.
    private ItemStack vfpOldCursorStack;
    // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#viaFabricPlus$oldItems (@Unique)
    private List<ItemStack> vfpOldItems;
    // MODIFIED for porting: was VFP r1_18_2_block_ack_emulation MixinMultiPlayerGameMode#viaFabricPlus$1_18_2InteractionManager
    // (@Unique). 1.14.4..1.18.2 acknowledge block actions with BLOCK_BREAK_ACK, so the unacked (pos, action)
    // pairs have to be remembered here to roll a mispredicted dig back.
    private final ClientPlayerInteractionManager1_18_2 vfp1_18_2InteractionManager = new ClientPlayerInteractionManager1_18_2();
    private BlockPos destroyBlockPos = new BlockPos(-1, -1, -1);
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float destroyProgress;
    private float destroyTicks;
    private int destroyDelay;
    private boolean isDestroying;
    private GameType localPlayerMode = GameType.DEFAULT_MODE;
    private @Nullable GameType previousLocalPlayerMode;
    private int carriedIndex;

    public MultiPlayerGameMode(final Minecraft minecraft, final ClientPacketListener connection) {
        this.minecraft = minecraft;
        this.connection = connection;
    }

    public void adjustPlayer(final Player player) {
        this.localPlayerMode.updatePlayerAbilities(player.getAbilities());
    }

    public void setLocalMode(final GameType mode, final @Nullable GameType previousMode) {
        this.localPlayerMode = mode;
        this.previousLocalPlayerMode = previousMode;
        this.localPlayerMode.updatePlayerAbilities(this.minecraft.player.getAbilities());
    }

    public void setLocalMode(final GameType mode) {
        if (mode != this.localPlayerMode) {
            this.previousLocalPlayerMode = this.localPlayerMode;
        }

        this.localPlayerMode = mode;
        this.localPlayerMode.updatePlayerAbilities(this.minecraft.player.getAbilities());
    }

    public boolean canHurtPlayer() {
        return this.localPlayerMode.isSurvival();
    }

    public boolean destroyBlock(final BlockPos pos) {
        if (this.minecraft.player.blockActionRestricted(this.minecraft.level, pos, this.localPlayerMode)) {
            return false;
        }

        Level level = this.minecraft.level;
        BlockState oldState = level.getBlockState(pos);
        if (!this.minecraft.player.getMainHandItem().canDestroyBlock(oldState, level, pos, this.minecraft.player)) {
            return false;
        }

        Block oldBlock = oldState.getBlock();
        if (oldBlock instanceof GameMasterBlock && !this.minecraft.player.canUseGameMasterBlocks()) {
            return false;
        }

        if (oldState.isAir()) {
            return false;
        }

        oldBlock.playerWillDestroy(level, pos, oldState, this.minecraft.player);
        FluidState fluidState = level.getFluidState(pos);
        boolean changed = level.setBlock(pos, fluidState.createLegacyBlock(), 11);
        if (changed) {
            oldBlock.destroy(level, pos, oldState);
        }

        if (SharedConstants.DEBUG_BLOCK_BREAK) {
            LOGGER.error("client broke {} {} -> {}", pos, oldState, level.getBlockState(pos));
        }

        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#resetBlockBreaking
        // (@Inject destroyBlock TAIL). <= 1.14.3 invalidated the mining target after a break so the next
        // start is never folded into the same target by sameDestroyTarget.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_14_3)) {
            this.destroyBlockPos = new BlockPos(this.destroyBlockPos.getX(), -1, this.destroyBlockPos.getZ());
        }

        return changed;
    }

    public boolean startDestroyBlock(final BlockPos pos, final Direction direction) {
        if (this.minecraft.player.blockActionRestricted(this.minecraft.level, pos, this.localPlayerMode)) {
            return false;
        }

        if (!this.minecraft.level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        if (this.minecraft.player.getAbilities().instabuild) {
            BlockState state = this.minecraft.level.getBlockState(pos);
            this.minecraft.getTutorial().onDestroyBlock(this.minecraft.level, pos, state, 1.0F);
            if (SharedConstants.DEBUG_BLOCK_BREAK) {
                LOGGER.info("Creative start {} {}", pos, state);
            }

            this.startPrediction(this.minecraft.level, sequence -> {
                // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#checkFireBlock
                // (@Redirect on destroyBlock inside lambda$startDestroyBlock$0, the instabuild branch).
                // <= 1.15.2 let a left click put out the fire in front of the block instead of breaking it,
                // and swallowed the break for that tick.
                if (!ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2) || !this.vfpExtinguishFire(pos, direction)) {
                    this.destroyBlock(pos);
                }

                return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
            });
            this.destroyDelay = 5;
        } else if (!this.isDestroying || !this.sameDestroyTarget(pos)) {
            if (this.isDestroying) {
                if (SharedConstants.DEBUG_BLOCK_BREAK) {
                    LOGGER.info("Abort old break {} {}", pos, this.minecraft.level.getBlockState(pos));
                }

                // MODIFIED for porting: was VFP r1_18_2_block_ack_emulation MixinMultiPlayerGameMode#trackPlayerAction
                // (@Redirect on the 3-arg ServerboundPlayerActionPacket constructor in startDestroyBlock).
                // 1.14.4..1.18.2 need the pre-action position so a rejected action can be rolled back.
                if (ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_14_4, ProtocolVersion.v1_18_2)) {
                    this.vfp1_18_2InteractionManager.trackPlayerAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos);
                }

                this.connection
                    .send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction));
            }

            BlockState state = this.minecraft.level.getBlockState(pos);
            this.minecraft.getTutorial().onDestroyBlock(this.minecraft.level, pos, state, 0.0F);
            if (SharedConstants.DEBUG_BLOCK_BREAK) {
                LOGGER.info("Start break {} {}", pos, state);
            }

            this.startPrediction(this.minecraft.level, sequence -> {
                boolean notAir = !state.isAir();
                if (notAir && this.destroyProgress == 0.0F) {
                    state.attack(this.minecraft.level, pos, this.minecraft.player);
                }

                if (notAir && state.getDestroyProgress(this.minecraft.player, this.minecraft.player.level(), pos) >= 1.0F) {
                    this.destroyBlock(pos);
                } else {
                    this.isDestroying = true;
                    this.destroyBlockPos = pos;
                    this.destroyingItem = this.minecraft.player.getMainHandItem();
                    this.destroyProgress = 0.0F;
                    this.destroyTicks = 0.0F;
                    this.minecraft.level.destroyBlockProgress(this.minecraft.player.getId(), this.destroyBlockPos, this.getDestroyStage());
                }

                return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
            });
        }

        return true;
    }

    public void stopDestroyBlock() {
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#fixMiningReset1_7
        // (@Redirect on the isDestroying GETFIELD in stopDestroyBlock). <= 1.7.6 always ran the reset body,
        // even when the client was not mining; the packet and the attack-strength reset are suppressed
        // separately below so only the local state is reset.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_7_6) || this.isDestroying) {
            BlockState state = this.minecraft.level.getBlockState(this.destroyBlockPos);
            this.minecraft.getTutorial().onDestroyBlock(this.minecraft.level, this.destroyBlockPos, state, -1.0F);
            if (SharedConstants.DEBUG_BLOCK_BREAK) {
                LOGGER.info("Stop dest {} {}", this.destroyBlockPos, state);
            }

            // MODIFIED for porting: was VFP r1_18_2_block_ack_emulation MixinMultiPlayerGameMode#trackPlayerAction
            // (@Redirect on the 3-arg ServerboundPlayerActionPacket constructor in stopDestroyBlock). The
            // constructor is an argument of the send below, so upstream tracks even when the send is skipped.
            if (ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_14_4, ProtocolVersion.v1_18_2)) {
                this.vfp1_18_2InteractionManager.trackPlayerAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos);
            }

            // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#preventPacketWhenNotMining1_7
            // (@WrapWithCondition on ClientPacketListener#send in stopDestroyBlock)
            if (ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_7_6) || this.isDestroying) {
                this.connection
                    .send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, Direction.DOWN));
            }

            this.isDestroying = false;
            this.destroyProgress = 0.0F;
            this.minecraft.level.destroyBlockProgress(this.minecraft.player.getId(), this.destroyBlockPos, -1);
            // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#preventAttackResetWhenNotMining1_7
            // (@WrapWithCondition on LocalPlayer#resetAttackStrengthTicker in stopDestroyBlock). isDestroying
            // was already cleared above, so on <= 1.7.6 this suppresses the reset outright, exactly as upstream.
            if (ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_7_6) || this.isDestroying) {
                this.minecraft.player.resetAttackStrengthTicker();
            }
        }
    }

    public boolean continueDestroyBlock(final BlockPos pos, final Direction direction) {
        this.ensureHasSentCarriedItem();
        if (this.destroyDelay > 0) {
            this.destroyDelay--;
            return true;
        }

        if (this.minecraft.player.getAbilities().instabuild && this.minecraft.level.getWorldBorder().isWithinBounds(pos)) {
            this.destroyDelay = 5;
            BlockState state = this.minecraft.level.getBlockState(pos);
            this.minecraft.getTutorial().onDestroyBlock(this.minecraft.level, pos, state, 1.0F);
            if (SharedConstants.DEBUG_BLOCK_BREAK) {
                LOGGER.info("Creative cont {} {}", pos, state);
            }

            this.startPrediction(this.minecraft.level, sequence -> {
                // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#checkFireBlock
                // (@Redirect on destroyBlock inside lambda$continueDestroyBlock$0, the instabuild branch).
                // <= 1.15.2 extinguished the fire in front of the block instead of breaking it. Upstream
                // deliberately leaves the survival-path lambdas alone.
                if (!ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2) || !this.vfpExtinguishFire(pos, direction)) {
                    this.destroyBlock(pos);
                }

                return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
            });
            return true;
        } else if (this.sameDestroyTarget(pos)) {
            BlockState state = this.minecraft.level.getBlockState(pos);
            if (state.isAir()) {
                this.isDestroying = false;
                return false;
            }

            this.destroyProgress = this.destroyProgress + state.getDestroyProgress(this.minecraft.player, this.minecraft.player.level(), pos);
            if (this.destroyTicks % 4.0F == 0.0F) {
                SoundType soundType = state.getSoundType();
                this.minecraft
                    .getSoundManager()
                    .play(
                        new SimpleSoundInstance(
                            soundType.getHitSound(),
                            SoundSource.BLOCKS,
                            (soundType.getVolume() + 1.0F) / 8.0F,
                            soundType.getPitch() * 0.5F,
                            SoundInstance.createUnseededRandom(),
                            pos
                        )
                    );
            }

            this.destroyTicks++;
            this.minecraft.getTutorial().onDestroyBlock(this.minecraft.level, pos, state, Mth.clamp(this.destroyProgress, 0.0F, 1.0F));
            if (this.destroyProgress >= 1.0F) {
                this.isDestroying = false;
                if (SharedConstants.DEBUG_BLOCK_BREAK) {
                    LOGGER.info("Finished breaking {} {}", pos, state);
                }

                this.startPrediction(this.minecraft.level, sequence -> {
                    this.destroyBlock(pos);
                    return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
                });
                this.destroyProgress = 0.0F;
                this.destroyTicks = 0.0F;
                this.destroyDelay = 5;
            }

            this.minecraft.level.destroyBlockProgress(this.minecraft.player.getId(), this.destroyBlockPos, this.getDestroyStage());
            return true;
        } else {
            return this.startDestroyBlock(pos, direction);
        }
    }

    private void startPrediction(final ClientLevel level, final PredictiveAction predictiveAction) {
        try (BlockStatePredictionHandler prediction = level.getBlockStatePredictionHandler().startPredicting()) {
            int sequence = prediction.currentSequence();
            Packet<ServerGamePacketListener> packetConcludingPrediction = predictiveAction.predict(sequence);
            // MODIFIED for porting: was VFP r1_18_2_block_ack_emulation MixinMultiPlayerGameMode#trackPlayerAction
            // (@Inject startPrediction HEAD). 1.14.4..1.18.2 acknowledge block actions with BLOCK_BREAK_ACK, so
            // the position (and rotation above 1.16.1) at send time has to be remembered to undo a rejected dig.
            // Upstream tests the PredictiveAction itself, which can never be a packet here because every caller
            // passes a lambda; the action's own packet is the value upstream meant.
            if (ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_14_4, ProtocolVersion.v1_18_2)
                && packetConcludingPrediction instanceof ServerboundPlayerActionPacket playerAction) {
                this.vfp1_18_2InteractionManager.trackPlayerAction(playerAction.getAction(), playerAction.getPos());
            }

            this.connection.send(packetConcludingPrediction);
        }
    }

    public void tick() {
        this.ensureHasSentCarriedItem();
        if (this.connection.getConnection().isConnected()) {
            this.connection.getConnection().tick();
        } else {
            this.connection.getConnection().handleDisconnection();
        }
    }

    private boolean sameDestroyTarget(final BlockPos pos) {
        ItemStack selected = this.minecraft.player.getMainHandItem();
        return pos.equals(this.destroyBlockPos) && ItemStack.isSameItemSameComponents(selected, this.destroyingItem);
    }

    private void ensureHasSentCarriedItem() {
        int index = this.minecraft.player.getInventory().getSelectedSlot();
        if (index != this.carriedIndex) {
            this.carriedIndex = index;
            this.connection.send(new ServerboundSetCarriedItemPacket(this.carriedIndex));
        }
    }

    public InteractionResult useItemOn(final LocalPlayer player, final InteractionHand hand, final BlockHitResult blockHit) {
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#cancelOffHandBlockPlace
        // (@Inject useItemOn HEAD). <= 1.8 has no off-hand, so only the main hand may interact with a block.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8) && !InteractionHand.MAIN_HAND.equals(hand)) {
            return InteractionResult.PASS;
        }

        this.ensureHasSentCarriedItem();
        if (!this.minecraft.level.getWorldBorder().isWithinBounds(blockHit.getBlockPos())) {
            return InteractionResult.FAIL;
        }

        MutableObject<InteractionResult> result = new MutableObject<>();
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#catchPacketCancelException
        // (@Redirect on startPrediction in useItemOn, ungated). The <= 1.12.2 path in performUseItemOn leaves by
        // throwing, which both skips the vanilla packet and unwinds the prediction handler.
        try {
            this.startPrediction(this.minecraft.level, sequence -> {
                // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#lambda$useItemOn$0
                // (@Overwrite of the useItemOn prediction lambda). <= 1.8 has to report the used item to Via's
                // HandItemProvider, and the 1.12.2 abort has to reach the caller as the interaction result.
                if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
                    ViaFabricPlusHandItemProvider.lastUsedItem = player.getItemInHand(hand).copy();
                }

                try {
                    result.setValue(this.performUseItemOn(player, hand, blockHit));
                    return new ServerboundUseItemOnPacket(hand, blockHit, sequence);
                } catch (ActionResultException1_12_2 e) {
                    result.setValue(e.getActionResult());
                    throw e;
                }
            });
        } catch (ActionResultException1_12_2 ignored) {
        }

        return result.get();
    }

    private InteractionResult performUseItemOn(final LocalPlayer player, final InteractionHand hand, final BlockHitResult blockHit) {
        BlockPos pos = blockHit.getBlockPos();
        ItemStack itemStack = player.getItemInHand(hand);
        if (this.localPlayerMode == GameType.SPECTATOR) {
            // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#changeSpectatorAction
            // (@Redirect on the InteractionResult.CONSUME GETSTATIC in performUseItemOn). <= 1.21 swung the arm
            // for a spectator interaction, so the result has to be SUCCESS rather than CONSUME.
            return ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21)
                ? InteractionResult.SUCCESS
                : InteractionResult.CONSUME;
        }

        boolean haveSomethingInOurHands = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        boolean suppressUsingBlock = player.isSecondaryUseActive() && haveSomethingInOurHands;
        if (!suppressUsingBlock) {
            BlockState blockState = this.minecraft.level.getBlockState(pos);
            if (!this.connection.isFeatureEnabled(blockState.getBlock().requiredFeatures())) {
                return InteractionResult.FAIL;
            }

            InteractionResult itemUse = blockState.useItemOn(player.getItemInHand(hand), this.minecraft.level, player, hand, blockHit);
            if (itemUse.consumesAction()) {
                return itemUse;
            }

            if (itemUse instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) {
                InteractionResult use = blockState.useWithoutItem(this.minecraft.level, player, blockHit);
                if (use.consumesAction()) {
                    return use;
                }
            }
        }

        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#interactBlock1_12_2
        // (@Inject performUseItemOn at the third ItemStack#isEmpty call). <= 1.12.2 validated the placement client
        // side, sent the interaction BEFORE running it, retargeted a one-layer snow click upwards, and knew no FAIL
        // result. The path leaves by throwing, which useItemOn turns back into the interaction result.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            final ItemStack heldItem = player.getItemInHand(hand);
            BlockHitResult checkHitResult = blockHit;
            if (heldItem.getItem() instanceof BlockItem) {
                final BlockState clickedBlock = this.minecraft.level.getBlockState(blockHit.getBlockPos());
                if (clickedBlock.getBlock().equals(Blocks.SNOW)) {
                    if (clickedBlock.getValue(SnowLayerBlock.LAYERS) == 1) {
                        checkHitResult = blockHit.withDirection(Direction.UP);
                    }
                }

                final UseOnContext placementUseContext = new UseOnContext(player, hand, checkHitResult);
                final BlockPlaceContext placementContext = new BlockPlaceContext(placementUseContext);
                if (!placementContext.canPlace()
                    || ((BlockItem)placementContext.getItemInHand().getItem()).getPlacementState(placementContext) == null) {
                    throw new ActionResultException1_12_2(InteractionResult.PASS);
                }
            }

            this.connection.send(new ServerboundUseItemOnPacket(hand, blockHit, 0));
            if (heldItem.isEmpty()) {
                throw new ActionResultException1_12_2(InteractionResult.PASS);
            }

            final UseOnContext useContext = new UseOnContext(player, hand, checkHitResult);
            InteractionResult actionResult;
            if (this.localPlayerMode.isCreative()) {
                final int count = heldItem.getCount();
                actionResult = heldItem.useOn(useContext);
                heldItem.setCount(count);
            } else {
                actionResult = heldItem.useOn(useContext);
            }

            if (!actionResult.consumesAction()) {
                actionResult = InteractionResult.PASS; // In <= 1.12.2 FAIL is the same as PASS
            }

            throw new ActionResultException1_12_2(actionResult);
        }

        if (!itemStack.isEmpty() && !player.getCooldowns().isOnCooldown(itemStack)) {
            UseOnContext context = new UseOnContext(player, hand, blockHit);
            InteractionResult success;
            if (player.hasInfiniteMaterials()) {
                int count = itemStack.getCount();
                success = itemStack.useOn(context);
                itemStack.setCount(count);
            } else {
                success = itemStack.useOn(context);
            }

            return success;
        } else {
            return InteractionResult.PASS;
        }
    }

    public InteractionResult useItem(final Player player, final InteractionHand hand) {
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#cancelOffHandItemInteract
        // (@Inject useItem HEAD). <= 1.8 has no off-hand, so only the main hand may use an item.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8) && !InteractionHand.MAIN_HAND.equals(hand)) {
            return InteractionResult.PASS;
        }

        if (this.localPlayerMode == GameType.SPECTATOR) {
            return InteractionResult.PASS;
        }

        this.ensureHasSentCarriedItem();
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#sendPlayerPosPacket
        // (@Inject useItem after ensureHasSentCarriedItem). 1.17..1.20.5 reported the position again right before
        // using an item, which those servers use for the interaction ray.
        if (ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_17, ProtocolVersion.v1_20_5)) {
            this.connection
                .send(
                    new ServerboundMovePlayerPacket.PosRot(
                        player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision
                    )
                );
        }

        MutableObject<InteractionResult> interactionResult = new MutableObject<>();
        final PredictiveAction useItemAction = sequence -> {
            // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#trackLastUsedItem
            // (@Inject lambda$useItem$0 HEAD). <= 1.8 reports the used item to Via's HandItemProvider.
            if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
                ViaFabricPlusHandItemProvider.lastUsedItem = player.getItemInHand(hand).copy();
            }

            ServerboundUseItemPacket packet = new ServerboundUseItemPacket(hand, sequence, player.getYRot(), player.getXRot());
            ItemStack itemStack = player.getItemInHand(hand);
            if (player.getCooldowns().isOnCooldown(itemStack)) {
                interactionResult.setValue(InteractionResult.PASS);
                return packet;
            }

            // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#eitherSuccessOrPass
            // (@Redirect on ItemStack#use in lambda$useItem$0). <= 1.8 had no action result: the use counted as
            // accepted only when the returned stack differed from the input one, so a vanilla result that
            // disagrees with that is folded back to SUCCESS or PASS.
            InteractionResult resultHolder;
            if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
                final int count = itemStack.getCount();
                final InteractionResult actionResult = itemStack.use(this.minecraft.level, player, hand);
                final ItemStack output;
                if (actionResult instanceof InteractionResult.Success success) {
                    output = Objects.requireNonNullElseGet(success.heldItemTransformedTo(), () -> player.getItemInHand(hand));
                } else {
                    output = player.getItemInHand(hand);
                }

                final boolean accepted = !output.isEmpty() && (output != itemStack || output.getCount() != count);
                if (actionResult.consumesAction() == accepted) {
                    resultHolder = actionResult;
                } else {
                    resultHolder = accepted ? InteractionResult.SUCCESS.heldItemTransformedTo(output) : InteractionResult.PASS;
                }
            } else {
                resultHolder = itemStack.use(this.minecraft.level, player, hand);
            }

            ItemStack result;
            if (resultHolder instanceof InteractionResult.Success success) {
                result = Objects.requireNonNullElseGet(success.heldItemTransformedTo(), () -> player.getItemInHand(hand));
            } else {
                result = player.getItemInHand(hand);
            }

            if (result != itemStack) {
                player.setItemInHand(hand, result);
            }

            interactionResult.setValue(resultHolder);
            return packet;
        };
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#fixPacketOrder
        // (@WrapWithCondition on startPrediction in useItem). <= 1.18.2 has no sequence to predict against and
        // sent the use-item packet BEFORE the item was used client side, so no prediction is started at all.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_18_2)) {
            this.connection.send(new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot()));
            useItemAction.predict(0);
        } else {
            this.startPrediction(this.minecraft.level, useItemAction);
        }

        return interactionResult.get();
    }

    public LocalPlayer createPlayer(final ClientLevel level, final StatsCounter stats, final ClientRecipeBook recipeBook) {
        return this.createPlayer(level, stats, recipeBook, Input.EMPTY, false);
    }

    public LocalPlayer createPlayer(
        final ClientLevel level, final StatsCounter stats, final ClientRecipeBook recipeBook, final Input lastSentInput, final boolean wasSprinting
    ) {
        return new LocalPlayer(this.minecraft, level, this.connection, stats, recipeBook, lastSentInput, wasSprinting, this.minecraft.computeChatAbilities());
    }

    public void attack(final Player player, final Entity entity) {
        this.ensureHasSentCarriedItem();
        this.connection.send(new ServerboundAttackPacket(entity.getId()));
        player.attack(entity);
        player.resetAttackStrengthTicker();
    }

    public void spectate(final Entity entity) {
        this.connection.send(new ServerboundSpectatorActionPacket(OptionalInt.of(entity.getId())));
    }

    public void spectatorNoAction() {
        this.connection.send(new ServerboundSpectatorActionPacket(OptionalInt.empty()));
    }

    public InteractionResult interact(final Player player, final Entity entity, final EntityHitResult hitResult, final InteractionHand hand) {
        this.ensureHasSentCarriedItem();
        Vec3 location = hitResult.getLocation().subtract(entity.getX(), entity.getY(), entity.getZ());
        this.connection.send(new ServerboundInteractPacket(entity.getId(), hand, location, player.isShiftKeyDown()));
        return this.localPlayerMode == GameType.SPECTATOR ? InteractionResult.PASS : player.interactOn(entity, hand, location);
    }

    public void handleContainerInput(final int containerId, final int slotNum, final int buttonNum, final ContainerInput containerInput, final Player player) {
        // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#removeClickActions
        // (@Inject handleContainerInput HEAD, cancellable). Old protocols cannot encode these click types at all.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.b1_5tob1_5_2) && !containerInput.equals(ContainerInput.PICKUP)) {
            return;
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.r1_4_6tor1_4_7)
            && !containerInput.equals(ContainerInput.PICKUP)
            && !containerInput.equals(ContainerInput.QUICK_MOVE)
            && !containerInput.equals(ContainerInput.SWAP)
            && !containerInput.equals(ContainerInput.CLONE)) {
            return;
        }

        // Pressing 'F' in the inventory: <= 1.15.2 has no offhand swap slot
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2) && containerInput == ContainerInput.SWAP && buttonNum == 40) {
            return;
        }

        AbstractContainerMenu containerMenu = player.containerMenu;
        if (containerId != containerMenu.containerId) {
            LOGGER.warn("Ignoring click in mismatching container. Click in {}, player has {}.", containerId, containerMenu.containerId);
        } else {
            NonNullList<Slot> slots = containerMenu.slots;
            int slotCount = slots.size();
            List<ItemStack> itemsBeforeClick = Lists.newArrayListWithCapacity(slotCount);
            // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#captureOldItems
            // (@ModifyVariable on the itemsBeforeClick STORE). The <= 1.16.4 writer below needs the carried stack
            // and the slot contents as they were before containerMenu.clicked runs; the list keeps filling through
            // the same reference.
            this.vfpOldCursorStack = this.minecraft.player.containerMenu.getCarried().copy();
            this.vfpOldItems = itemsBeforeClick;

            for (Slot slot : slots) {
                itemsBeforeClick.add(slot.getItem().copy());
            }

            containerMenu.clicked(slotNum, buttonNum, containerInput, player);
            Int2ObjectMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();

            for (int i = 0; i < slotCount; i++) {
                ItemStack before = itemsBeforeClick.get(i);
                ItemStack after = slots.get(i).getItem();
                if (!ItemStack.matches(before, after)) {
                    changedSlots.put(i, HashedStack.create(after, this.connection.decoratedHashOpsGenenerator()));
                }
            }

            HashedStack carriedItem = HashedStack.create(containerMenu.getCarried(), this.connection.decoratedHashOpsGenenerator());
            final ServerboundContainerClickPacket clickPacket = new ServerboundContainerClickPacket(
                containerId,
                containerMenu.getStateId(),
                Shorts.checkedCast(slotNum),
                SignedBytes.checkedCast(buttonNum),
                containerInput,
                changedSlots,
                carriedItem
            );
            // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#handleWindowClick
            // (@WrapWithCondition on ClientPacketListener#send in handleContainerInput). <= 1.16.4 sends the item
            // as it was before the click plus an action id, <= 1.21.4 sends the whole items instead of hashes, and
            // only 1.21.5 and newer take the vanilla hashed-stack packet.
            if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_16_4)) {
                this.vfpClickSlot1_16_5(clickPacket);
            } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21_4)) {
                this.vfpClickSlot1_21_4(clickPacket);
            } else {
                this.connection.send(clickPacket);
            }
        }
    }

    public void handlePlaceRecipe(final int containerId, final RecipeDisplayId recipe, final boolean useMaxItems) {
        this.connection.send(new ServerboundPlaceRecipePacket(containerId, recipe, useMaxItems));
    }

    public void handleInventoryButtonClick(final int containerId, final int buttonId) {
        this.connection.send(new ServerboundContainerButtonClickPacket(containerId, buttonId));
    }

    public void handleCreativeModeItemAdd(final ItemStack clicked, final int slot) {
        if (this.minecraft.player.hasInfiniteMaterials() && this.connection.isFeatureEnabled(clicked.getItem().requiredFeatures())) {
            this.connection.send(new ServerboundSetCreativeModeSlotPacket(slot, clicked));
        }
    }

    public void handleCreativeModeItemDrop(final ItemStack clicked) {
        boolean hasOtherInventoryOpen = this.minecraft.gui.screen() instanceof AbstractContainerScreen
            && !(this.minecraft.gui.screen() instanceof CreativeModeInventoryScreen);
        if (this.minecraft.player.hasInfiniteMaterials()
            && !hasOtherInventoryOpen
            && !clicked.isEmpty()
            && this.connection.isFeatureEnabled(clicked.getItem().requiredFeatures())) {
            this.connection.send(new ServerboundSetCreativeModeSlotPacket(-1, clicked));
            this.minecraft.player.getDropSpamThrottler().increment();
        }
    }

    public void releaseUsingItem(final Player player) {
        this.ensureHasSentCarriedItem();
        this.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        player.releaseUsingItem();
    }

    public void piercingAttack(final PiercingWeapon weapon) {
        this.ensureHasSentCarriedItem();
        this.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN));
        this.minecraft.player.onAttack();
        this.minecraft.player.postPiercingAttack();
        weapon.makeSound(this.minecraft.player);
    }

    public boolean hasExperience() {
        return this.localPlayerMode.isSurvival();
    }

    public boolean hasMissTime() {
        return !this.localPlayerMode.isCreative();
    }

    public boolean isServerControlledInventory() {
        return this.minecraft.player.isPassenger() && this.minecraft.player.getVehicle() instanceof HasCustomInventoryScreen;
    }

    public boolean isSpectator() {
        return this.localPlayerMode == GameType.SPECTATOR;
    }

    public @Nullable GameType getPreviousPlayerMode() {
        return this.previousLocalPlayerMode;
    }

    public GameType getPlayerMode() {
        return this.localPlayerMode;
    }

    public boolean isDestroying() {
        return this.isDestroying;
    }

    public int getDestroyStage() {
        // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#changeCalculation
        // (@Inject getDestroyStage HEAD, cancellable). <= 1.19.4 had no zero-progress sentinel: the stage was
        // simply progress * 10 - 1, so a fresh target reports -1 through the same formula.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_19_4)) {
            return (int)(this.destroyProgress * 10.0F) - 1;
        }

        return this.destroyProgress > 0.0F ? (int)(this.destroyProgress * 10.0F) : -1;
    }

    public void handlePickItemFromBlock(final BlockPos pos, final boolean includeData) {
        this.connection.send(new ServerboundPickItemFromBlockPacket(pos, includeData));
    }

    public void handlePickItemFromEntity(final Entity entity, final boolean includeData) {
        this.connection.send(new ServerboundPickItemFromEntityPacket(entity.getId(), includeData));
    }

    public void handleSlotStateChanged(final int slotId, final int containerId, final boolean newState) {
        this.connection.send(new ServerboundContainerSlotStateChangedPacket(slotId, containerId, newState));
    }

    // MODIFIED for porting: was VFP r1_18_2_block_ack_emulation MixinMultiPlayerGameMode's IMultiPlayerGameMode
    // implementation. The Via clientbound BLOCK_BREAK_ACK handler reaches the tracker through this.
    @Override
    public ClientPlayerInteractionManager1_18_2 viaFabricPlus$get1_18_2InteractionManager() {
        return this.vfp1_18_2InteractionManager;
    }

    // MODIFIED for porting: was VFP replace_block_item_use_logic MixinMultiPlayerGameMode#viaFabricPlus$extinguishFire (@Unique)
    private boolean vfpExtinguishFire(final BlockPos pos, final Direction direction) {
        final BlockPos firePos = pos.relative(direction);
        if (this.minecraft.level.getBlockState(firePos).getBlock() == Blocks.FIRE) {
            this.minecraft.level.levelEvent(this.minecraft.player, 1009, firePos, 0);
            this.minecraft.level.removeBlock(firePos, false);
            return true;
        }

        return false;
    }

    // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#viaFabricPlus$clickSlot1_21_4 (@Unique).
    // <= 1.21.4 carries the full item of every changed slot instead of the 1.21.5 hash, read back from the live menu.
    private void vfpClickSlot1_21_4(final ServerboundContainerClickPacket packet) {
        final PacketWrapper containerClick = PacketWrapper.create(ServerboundPackets1_21_4.CONTAINER_CLICK, ProtocolTranslator.getPlayNetworkUserConnection());
        containerClick.write(Types.VAR_INT, packet.containerId());
        containerClick.write(Types.VAR_INT, packet.stateId());
        containerClick.write(Types.SHORT, packet.slotNum());
        containerClick.write(Types.BYTE, packet.buttonNum());
        containerClick.write(Types.VAR_INT, packet.containerInput().id());

        final Int2ObjectMap<HashedStack> modifiedStacks = packet.changedSlots();
        containerClick.write(Types.VAR_INT, modifiedStacks.size());

        for (Int2ObjectMap.Entry<HashedStack> entry : modifiedStacks.int2ObjectEntrySet()) {
            final ItemStack itemStack = this.minecraft.player.containerMenu.slots.get(entry.getIntKey()).getItem();
            containerClick.write(Types.SHORT, (short)entry.getIntKey());
            containerClick.write(VersionedTypes.V1_21_4.item, ItemTranslator.mcToVia(itemStack, ProtocolVersion.v1_21_4));
        }

        final ItemStack cursorStack = this.minecraft.player.containerMenu.getCarried();
        containerClick.write(VersionedTypes.V1_21_4.item, ItemTranslator.mcToVia(cursorStack, ProtocolVersion.v1_21_4));
        containerClick.scheduleSendToServer(Protocol1_21_4To1_21_5.class);
    }

    // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#viaFabricPlus$clickSlot1_16_5 (@Unique).
    // <= 1.16.4 carries a client action id plus the ONE slot item as it was before the click, for verification.
    private void vfpClickSlot1_16_5(final ServerboundContainerClickPacket packet) {
        ItemStack slotItemBeforeModification;
        if (this.vfpShouldBeEmpty(packet.containerInput(), packet.slotNum())) {
            slotItemBeforeModification = ItemStack.EMPTY;
        } else if (packet.slotNum() < 0 || packet.slotNum() >= this.vfpOldItems.size()) {
            slotItemBeforeModification = this.vfpOldCursorStack;
        } else {
            slotItemBeforeModification = this.vfpOldItems.get(packet.slotNum());
        }

        final PacketWrapper containerClick = PacketWrapper.create(ServerboundPackets1_16_2.CONTAINER_CLICK, ProtocolTranslator.getPlayNetworkUserConnection());
        containerClick.write(Types.BYTE, (byte)packet.containerId());
        containerClick.write(Types.SHORT, packet.slotNum());
        containerClick.write(Types.BYTE, packet.buttonNum());
        containerClick.write(Types.SHORT, ((IAbstractContainerMenu)this.minecraft.player.containerMenu).viaFabricPlus$incrementAndGetActionId());
        containerClick.write(Types.VAR_INT, packet.containerInput().ordinal());
        containerClick.write(Types.ITEM1_13_2, ItemTranslator.mcToVia(slotItemBeforeModification, ProtocolVersion.v1_16_4));
        containerClick.scheduleSendToServer(Protocol1_16_4To1_17.class);

        this.vfpOldCursorStack = null;
        this.vfpOldItems = null;
    }

    // MODIFIED for porting: was VFP container_clicking MixinMultiPlayerGameMode#viaFabricPlus$shouldBeEmpty (@Unique)
    private boolean vfpShouldBeEmpty(final ContainerInput type, final int slot) {
        // quick craft always uses empty stack for verification
        if (type == ContainerInput.QUICK_CRAFT) {
            return true;
        }

        // Special case: throw always uses empty stack for verification
        if (type == ContainerInput.THROW) {
            return true;
        }

        // quick move always uses empty stack for verification since 1.12
        if (type == ContainerInput.QUICK_MOVE && ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_11_1)) {
            return true;
        }

        // pickup with slot -999 (outside window) to throw items always uses empty stack for verification
        return type == ContainerInput.PICKUP && slot == -999;
    }
}