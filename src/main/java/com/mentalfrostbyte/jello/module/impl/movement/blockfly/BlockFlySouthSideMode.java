package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.game.render.EventRender2D;
import com.mentalfrostbyte.jello.event.impl.player.EventGetFovModifier;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.gui.modern.ModernBlockFlyCounter;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement.Target;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.mentalfrostbyte.jello.util.text.ChatUtil;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * SigmaClient's SouthSide mode, originally OpenSSNGScaffoldAndClutch's Scaffold.
 * Its Telly/Snap/Normal rotation policy, air prediction, inventory policy and packet hold are local
 * to this mode; the shared parent supplies the modern event and rotation integration.
 */
public final class BlockFlySouthSideMode extends BlockFlyMode {

    public enum Style { TELLY, SNAP, NORMAL }
    public enum SlotMode { FARTHEST, MOST_BLOCKS }
    public enum JumpMode { PARKOUR, NORMAL, NONE }
    public enum CounterStyle { RETRO, OLD }

    private final EnumSetting<Style> mode;
    private final BooleanSetting alwaysUpdateRot, spoofItem, noSwing, eagle, snap, noUptelly, godBridge;
    private final BooleanSetting smoothed, safeMode, testOnGround, fixRotation, randomSlow, blinkHold, abuseRotation;
    private final BooleanSetting debug, keepFoV, mark, duplicateRotPlace, interactItem, blockCount;
    private final NumberSetting placeTick, rotTick, safeDistance, tellyEagleTick, keepEagleSneakTick, fovValue, blockCountOffset;
    private final EnumSetting<SlotMode> blockSlotMode;
    private final EnumSetting<JumpMode> jumpMode;
    private final EnumSetting<CounterStyle> blockCountStyle;
    private final ColorSetting markSideColor, markLineColor;

    private static final Set<Block> INVALID = Set.of(
            Blocks.ENCHANTING_TABLE, Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TRAPPED_CHEST,
            Blocks.ANVIL, Blocks.SAND, Blocks.COBWEB, Blocks.TORCH, Blocks.CRAFTING_TABLE, Blocks.FURNACE,
            Blocks.CAULDRON, Blocks.DISPENSER, Blocks.STONE_PRESSURE_PLATE, Blocks.NOTE_BLOCK,
            Blocks.DROPPER, Blocks.TNT, Blocks.REDSTONE_TORCH, Blocks.DAYLIGHT_DETECTOR
    );

    private record Slot(int index, InteractionHand hand) { }
    private record DeferredPlacement(Target target, Vec3 eyes, Rotation rotation, double pitchDiff, float yawDiff) { }
    private @Nullable Slot slot;
    private @Nullable Target target;
    private @Nullable Target lastFound;
    private @Nullable DeferredPlacement deferredPlacement;
    private @Nullable BlockPos lastPlaced;
    private @Nullable Rotation lastRotation;
    private Rotation sentRotation = new Rotation(0, 0);
    private Rotation previousSentRotation = new Rotation(0, 0);
    private volatile @Nullable ClientPacketListener session;
    private @Nullable ClientLevel level;
    private @Nullable LocalPlayer player;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private int lastPlaceTick = Integer.MIN_VALUE;
    private int onGroundTicks, offGroundTicks, rotateCount, placeCount, ups, eagleTicks, jumpDelay;
    private int startHotbarCount = 1;
    private double posY, pitchDifference, lastPlacePitchDifference;
    private float lastPlacedDeltaYaw = Float.NaN;
    private boolean canPlace, waitingForEagle, cancelMove;

    // Game-thread only. Keep connection/control packets flowing; only ordered gameplay is held.
    private final ArrayDeque<Packet<?>> held = new ArrayDeque<>();
    private boolean holding, flushing;
    private int heldTicks;
    private static final int MAX_HELD_PACKETS = 2048;

    public BlockFlySouthSideMode(final BlockFly parent) {
        super(parent);
        this.mode = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE, new EnumSetting<>("SouthSide Mode", "Scaffold rotation strategy.", Style.TELLY));
        this.alwaysUpdateRot = bool("Always Update Rotation", false);
        this.placeTick = number("PlaceTick", 1, 1, 5, 1);
        this.rotTick = number("RotationTick", 1, 1, 5, 1);
        this.spoofItem = bool("Spoof Item", true);
        this.noSwing = bool("No Swing", false);
        this.eagle = bool("Eagle", false);
        this.snap = bool("Snap", false);
        this.noUptelly = bool("No Uptelly", true);
        this.godBridge = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new BooleanSetting("SouthSide GodBridge", "Compatibility option retained from SigmaClient; its source declares no additional behavior.", false));
        this.smoothed = bool("Heypixel UpTelly", true);
        this.safeMode = bool("Safe Mode", false);
        this.testOnGround = bool("Test OnGround", false);
        this.fixRotation = bool("Fix Rotation", true);
        this.randomSlow = bool("SlowUpTelly", false);
        this.blinkHold = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new BooleanSetting("SouthSide Blink Hold", "Blink-queues outgoing packets for up to 16 ticks before flushing (legacy).", false));
        this.abuseRotation = bool("Abuse Rotation", false);
        this.blockSlotMode = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new EnumSetting<>("SouthSide Block Slot Mode", "Last valid hotbar slot, or largest stack.", SlotMode.FARTHEST));
        this.jumpMode = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new EnumSetting<>("SouthSide Jump Mode", "Jump continuously, at an edge, or manually.", JumpMode.NORMAL));
        this.safeDistance = number("Clutch Safe Distance", 4.5F, 1, 5, 0.25F);
        this.tellyEagleTick = number("EagleTick", 1, 1, 5, 1);
        this.keepEagleSneakTick = number("KeepEagleTick", 1, 1, 5, 1);
        this.debug = bool("Debug", false);
        this.keepFoV = bool("Keep FoV", true);
        this.fovValue = number("Fov", 1.1F, 1, 2.1F, 0.05F);
        this.mark = bool("Mark", true);
        this.duplicateRotPlace = bool("DuplicateRotPlace", true);
        this.interactItem = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new BooleanSetting("SouthSide Use Item On Pass", "Use an item only after block placement returns PASS.", false));
        this.markSideColor = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new ColorSetting("SouthSide Mark Side Color", "Placed-block fill.", 0x46FFFFFF, true));
        this.markLineColor = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new ColorSetting("SouthSide Mark Line Color", "Placed-block outline.", 0x96FFFFFF, true));
        this.blockCount = bool("BlockCount", true);
        this.blockCountStyle = parent.modeSetting(BlockFly.Mode.SOUTH_SIDE,
                new EnumSetting<>("SouthSide BlockCount Style", "Progress card or plain count.", CounterStyle.RETRO));
        this.blockCountOffset = number("BlockCount Y Offset", 0, 0, 200, 1);
        this.jumpMode.visibleWhen(() -> visible() && this.mode.is(Style.TELLY));
        this.placeTick.visibleWhen(() -> visible() && this.mode.is(Style.TELLY));
        this.smoothed.visibleWhen(() -> visible() && this.mode.is(Style.TELLY));
        this.safeMode.visibleWhen(() -> visible() && this.smoothed.get());
        this.godBridge.visibleWhen(() -> visible() && this.mode.is(Style.NORMAL));
        this.tellyEagleTick.visibleWhen(() -> visible() && this.eagle.get());
        this.keepEagleSneakTick.visibleWhen(() -> visible() && this.eagle.get());
        this.fovValue.visibleWhen(() -> visible() && this.keepFoV.get());
        this.markLineColor.visibleWhen(() -> visible() && this.mark.get());
        this.markSideColor.visibleWhen(() -> visible() && this.mark.get());
        this.blockCountStyle.visibleWhen(() -> visible() && this.blockCount.get());
        this.blockCountOffset.visibleWhen(() -> visible() && this.blockCount.get());
    }

    private boolean visible() { return this.parent.isMode(BlockFly.Mode.SOUTH_SIDE); }
    private BooleanSetting bool(final String name, final boolean initial) {
        return this.parent.modeSetting(BlockFly.Mode.SOUTH_SIDE, new BooleanSetting("SouthSide " + name, name, initial));
    }
    private NumberSetting number(final String name, final float initial, final float min, final float max, final float step) {
        return this.parent.modeSetting(BlockFly.Mode.SOUTH_SIDE, new NumberSetting("SouthSide " + name, name, initial, min, max, step));
    }

    static boolean shouldDeferPlacementForTickEnd(final ProtocolVersion targetVersion) {
        return targetVersion.newerThanOrEqualTo(ProtocolVersion.v1_21_2);
    }

    static boolean shouldRunAbuseRotation(final ProtocolVersion targetVersion) {
        return targetVersion.betweenInclusive(ProtocolVersion.v1_17, ProtocolVersion.v1_20_5);
    }

    @Override
    public boolean hasDeferredWork() {
        return this.deferredPlacement != null || this.holding;
    }

    @Override
    public void enable() {
        this.session = mc.getConnection();
        this.level = mc.level;
        this.player = mc.player;
        this.originalSlot = this.selectedSlot = mc.player.getInventory().getSelectedSlot();
        this.lastRotation = this.sentRotation = this.previousSentRotation = new Rotation(mc.player.getYRot(), mc.player.getXRot());
        this.startHotbarCount = Math.max(1, countBlocks());
        this.posY = Mth.floor(mc.player.getY() - 1);
        this.target = this.lastFound = null;
        this.deferredPlacement = null;
        this.lastPlaced = null;
        this.slot = null;
        this.lastPlaceTick = Integer.MIN_VALUE;
        this.onGroundTicks = this.offGroundTicks = this.rotateCount = this.placeCount = this.ups = this.eagleTicks = this.jumpDelay = 0;
        this.pitchDifference = this.lastPlacePitchDifference = 0;
        this.lastPlacedDeltaYaw = Float.NaN;
        this.canPlace = true;
        this.waitingForEagle = this.cancelMove = false;
        release(false, true);
    }

    @Override
    public void disable() {
        release(sameSession(), true);
        if (sameSession() && this.originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() == this.selectedSlot) {
            mc.player.getInventory().setSelectedSlot(this.originalSlot);
            if (mc.isInPreMovementTick()) {
                mc.gameMode.syncCarriedItem();
            } else {
                mc.gameMode.markCarriedItemDirty();
            }
        }
        this.parent.clearRotation();
        this.deferredPlacement = null;
        this.lastPlaceTick = Integer.MIN_VALUE;
        this.slot = null;
        this.target = null;
        this.cancelMove = false;
        this.session = null;
        this.level = null;
        this.player = null;
    }

    private boolean sameSession() {
        return this.session != null && this.session == mc.getConnection() && this.level == mc.level && this.player == mc.player;
    }

    @Override
    public void worldLoad(final EventLoadWorld event) {
        release(false, true);
        this.deferredPlacement = null;
        this.lastPlaceTick = Integer.MIN_VALUE;
        this.session = null;
    }

    @Override
    public void tick(final EventTick event) {
        if (!sameSession()) { release(false, true); this.deferredPlacement = null; return; }
        if (event.isPost()) {
            if (!this.blinkHold.get()) release(true, true);
            else if (this.holding && ++this.heldTicks > 16) release(true, false);
            if (this.mark.get() && this.lastPlaced != null) {
                try (Gizmos.TemporaryCollection ignored = mc.collectPerTickGizmos()) {
                    Gizmos.cuboid(this.lastPlaced, 0.002F, GizmoStyle.strokeAndFill(this.markLineColor.get(), 2, this.markSideColor.get()));
                }
            }
            return;
        }
        flushDeferredPlacement();
        if (mc.player.onGround()) { this.onGroundTicks++; this.offGroundTicks = 0; }
        else { this.offGroundTicks++; this.onGroundTicks = 0; }
        if (this.jumpDelay > 0) this.jumpDelay--;
        if (this.waitingForEagle) {
            if (++this.eagleTicks >= this.tellyEagleTick.getInt() + this.keepEagleSneakTick.getInt()) {
                this.waitingForEagle = false;
                this.eagleTicks = 0;
            }
        }
        this.slot = selectSlot();
        if (this.slot == null || mc.gui.screen() != null) {
            this.target = null;
            this.cancelMove = false;
            this.canPlace = false;
            this.waitingForEagle = false;
            this.parent.clearRotation();
            return;
        }
        if (mc.player.onGround() || mc.options.keyJump.isDown()) this.posY = Mth.floor(mc.player.getY()) - 1;
        Vec3 eyes = mc.player.getEyePosition();
        Target found = mc.level.getBlockState(mc.player.blockPosition()).canBeReplaced()
                ? findTarget(BlockPos.containing(mc.player.getX(), this.posY, mc.player.getZ()), eyes)
                : null;
        if (found != null) this.target = found;
        this.lastFound = found;
        this.canPlace = switch (this.mode.get()) {
            case NORMAL -> true;
            case SNAP -> mc.level.getBlockState(mc.player.blockPosition().below()).canBeReplaced();
            case TELLY -> this.offGroundTicks >= this.placeTick.getInt()
                    || this.safeMode.get() && this.testOnGround.get() && mc.options.keyJump.isDown() && this.onGroundTicks == 1;
        };
        selectForUse();

        Prediction prediction = predict(mc.player.position(), mc.player.getDeltaMovement(), mc.player.input.moveVector.x,
                mc.player.input.moveVector.y, this.sentRotation.yaw(), mc.player.isSprinting(), mc.player.getEyeHeight());
        Target lower = findTarget(mc.player.blockPosition().below(), eyes);
        boolean force = this.safeMode.get() && this.testOnGround.get() && this.onGroundTicks == 1 && mc.options.keyJump.isDown();
        boolean reachable = true;
        if (lower != null && (prediction.nextEyes().distanceTo(Vec3.atCenterOf(lower.hit().getBlockPos())) >= this.safeDistance.get()
                || lower.hit().getBlockPos().getY() > prediction.afterTwo().y)) {
            this.target = this.lastFound = lower;
            this.canPlace = true;
            reachable = false;
        }
        if (this.target != null && this.target.hit().getBlockPos().getY() > prediction.afterTwo().y) {
            BlockPos anchor = this.target.hit().getBlockPos();
            AABB column = new AABB(anchor.getX(), anchor.getY() - 1, anchor.getZ(), anchor.getX() + 1, anchor.getY() + 1, anchor.getZ() + 1);
            if (!column.contains(mc.player.position())) {
                this.canPlace = true;
                reachable = false;
                this.posY = Mth.floor(mc.player.getY()) - 1;
                this.target = this.lastFound = lower;
            }
        }
        if (this.target == null) {
            this.cancelMove = false;
            this.parent.clearRotation();
            return;
        }
        if (!reachable && this.rotateCount < 8) {
            this.cancelMove = true;
            if (++this.rotateCount == 1 && this.debug.get()) ChatUtil.print("SouthSide: clutch prediction active");
        } else {
            this.cancelMove = false;
            this.rotateCount = 0;
        }
        Rotation rotation = chooseRotation(eyes, force);
        rotation = finalizeRotation(eyes, this.target, rotation);
        if (BlockFlyPlacement.valid(mc.player, eyes, this.target, rotation)) {
            this.cancelMove = false;
            this.rotateCount = 0;
        }
        this.parent.requestRotation(rotation, true, MovementCorrection.SILENT);
    }

    private Rotation chooseRotation(final Vec3 eyes, final boolean force) {
        Rotation wanted = Rotations.continuous(this.sentRotation, this.target.rotation());
        if (this.cancelMove) return wanted;
        float diff = Mth.wrapDegrees(wanted.yaw() - this.sentRotation.yaw());
        if (this.mode.is(Style.TELLY)) {
            boolean jump = mc.options.keyJump.isDown();
            if (jump && this.noUptelly.get()) return wanted;
            if (jump && this.randomSlow.get() && ++this.ups % 2 == 0) return wanted;
            if (this.smoothed.get() && (this.offGroundTicks < this.rotTick.getInt() || this.safeMode.get())) {
                if (this.onGroundTicks > 0) {
                    if (this.safeMode.get() && (!this.testOnGround.get() || jump)) {
                        if (this.onGroundTicks == 1) {
                            this.jumpDelay = 2;
                            wanted = force ? wanted : new Rotation(this.sentRotation.yaw() + diff * 0.5F, 75.5F);
                        } else if (this.onGroundTicks == 2) return new Rotation(mc.player.getYRot(), 75.5F);
                    } else return new Rotation(mc.player.getYRot(), 75.5F);
                } else {
                    float limit = (this.offGroundTicks == 1 ? 80 : 50) - random(0.001F, 0.005F);
                    wanted = new Rotation(this.sentRotation.yaw() + Mth.clamp(diff, -limit, limit), wanted.pitch());
                }
            } else if ((!this.snap.get() || !jump || this.lastFound == null) && this.offGroundTicks < this.rotTick.getInt()) {
                return new Rotation(mc.player.getYRot(), 85 + random(0, 1));
            }
        }
        if (this.lastRotation != null && BlockFlyPlacement.valid(mc.player, eyes, this.target, this.lastRotation)) return this.lastRotation;
        if (!this.alwaysUpdateRot.get() && this.offGroundTicks >= this.rotTick.getInt()
                && !BlockFlyPlacement.valid(mc.player, eyes, this.target, wanted) && this.lastRotation != null) {
            this.lastRotation = new Rotation(this.lastRotation.yaw() + random(0, 1), this.lastRotation.pitch());
            return this.lastRotation;
        }
        return this.lastRotation = wanted;
    }

    @Override
    public void input(final EventMovementInput event) {
        if (this.slot == null || !sameSession() || mc.gui.screen() != null) return;
        boolean physicalJump = mc.options.keyJump.isDown();
        if (this.cancelMove) { event.setForward(0); event.setStrafe(0); }
        if (this.mode.is(Style.TELLY) && this.onGroundTicks > (this.smoothed.get() && this.safeMode.get() && !this.testOnGround.get() ? 1 : 0)
                && !physicalJump && event.isMoving() && this.jumpDelay == 0) {
            boolean jump = this.jumpMode.is(JumpMode.NORMAL) || this.jumpMode.is(JumpMode.PARKOUR) && edgeAhead();
            if (jump) {
                event.setJump(true);
                if (this.eagle.get()) { this.waitingForEagle = true; this.eagleTicks = 0; }
            }
        }
        if (this.jumpDelay > 0) event.setJump(false);
        if (this.eagle.get()) {
            if (this.mode.is(Style.TELLY)) event.setShift(event.getInput().shift() || this.placeCount % 4 == 0);
            else if (this.waitingForEagle && this.eagleTicks >= this.tellyEagleTick.getInt()) event.setShift(true);
        }
        if (this.onGroundTicks == 1 && this.testOnGround.get() && this.smoothed.get() && !this.noUptelly.get()
                && this.safeMode.get() && physicalJump) {
            event.setForward(event.getForward() * 0.2F);
            event.setStrafe(event.getStrafe() * 0.2F);
        }
    }

    private boolean edgeAhead() {
        Vec3 forward = Vec3.directionFromRotation(0, mc.player.getYRot());
        for (int i = 1; i <= 2; i++) {
            BlockPos pos = BlockPos.containing(mc.player.getX() + forward.x * i, mc.player.getY() - 0.1, mc.player.getZ() + forward.z * i);
            if (mc.level.getBlockState(pos).canBeReplaced()) return true;
        }
        return false;
    }

    @Override
    public void motion(final EventMotion event) {
        if (event.isCancelled() || !sameSession() || this.slot == null || mc.gui.screen() != null) return;
        if (event.isPre()) {
            if (!this.canPlace) return;
            Vec3 postEyes = new Vec3(event.getX(), event.getY() + mc.player.getEyeHeight(), event.getZ());
            BlockPos base = BlockPos.containing(event.getX(), this.posY, event.getZ());
            if (mc.level.getBlockState(BlockPos.containing(event.getX(), event.getY(), event.getZ())).canBeReplaced()) {
                Target refreshed = findTarget(base, postEyes);
                if (refreshed != null) {
                    this.target = refreshed;
                }
            }
            if (this.target == null) return;
            Rotation current = new Rotation(event.getYaw(), event.getPitch());
            if (!BlockFlyPlacement.valid(mc.player, postEyes, this.target, current)) {
                Target reaim = BlockFlyPlacement.find(mc.player, postEyes, this.target.placedPos(), this.sentRotation);
                if (reaim != null && isAnchor(reaim.hit().getBlockPos())) {
                    this.target = reaim;
                    Rotation adjusted = finalizeRotation(postEyes, reaim, Rotations.continuous(this.sentRotation, reaim.rotation()));
                    if (BlockFlyPlacement.valid(mc.player, postEyes, reaim, adjusted)) {
                        this.lastRotation = adjusted;
                        this.parent.aim(event, adjusted, true, MovementCorrection.SILENT);
                    }
                }
            }
            return;
        }
        if (!event.isPost() || this.target == null || !this.canPlace) return;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + mc.player.getEyeHeight(), event.getZ());
        Rotation reported = new Rotation(event.getYaw(), event.getPitch());
        if (!BlockFlyPlacement.valid(mc.player, eyes, this.target, reported)) return;
        if (this.duplicateRotPlace.get() && this.pitchDifference > 2 && Math.abs(this.pitchDifference - this.lastPlacePitchDifference) < 0.0001) return;
        float yawDiff = Math.abs(reported.yaw() - this.previousSentRotation.yaw());
        if (shouldDeferPlacementForTickEnd(ProtocolTranslator.getTargetVersion())) {
            // 1.21.2+ / 26.2 forbids sending slot or block-place packets between MovePlayer and CLIENT_TICK_END.
            this.deferredPlacement = new DeferredPlacement(this.target, eyes, reported, this.pitchDifference, yawDiff);
            return;
        }
        executePlacement(this.target, eyes, reported, this.pitchDifference, yawDiff);
    }

    private void flushDeferredPlacement() {
        DeferredPlacement pending = this.deferredPlacement;
        this.deferredPlacement = null;
        if (pending == null || mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) {
            return;
        }
        Vec3 currentEyes = mc.player.getEyePosition();
        if (currentEyes.distanceToSqr(pending.eyes()) > 1.0E-4) {
            return;
        }
        this.slot = selectSlot();
        if (this.slot == null) {
            return;
        }
        executePlacement(pending.target(), currentEyes, pending.rotation(), pending.pitchDiff(), pending.yawDiff());
    }

    private void executePlacement(final Target placeTarget, final Vec3 eyes, final Rotation reported,
                                  final double pitchDiff, final float yawDiff) {
        if (this.slot == null || this.lastPlaceTick == mc.player.tickCount) return;
        if (!BlockFlyPlacement.valid(mc.player, eyes, placeTarget, reported)) return;
        BlockHitResult hit = BlockFlyPlacement.raycast(mc.player, eyes, reported);
        if (hit == null || !validStack(stack(this.slot))) return;
        selectForUse();
        float cameraYaw = mc.player.getYRot(), cameraPitch = mc.player.getXRot();
        try {
            // Directional block prediction must see the rotation in the movement packet too.
            mc.player.setYRot(reported.yaw());
            mc.player.setXRot(reported.pitch());
            BlockPlaceContext context = new BlockPlaceContext(mc.player, this.slot.hand(), stack(this.slot), hit);
            if (!context.canPlace() || !context.getClickedPos().equals(placeTarget.placedPos())) return;
            if (this.blinkHold.get() && !this.holding) {
                this.holding = true;
                this.heldTicks = 0;
            }
            this.lastPlaceTick = mc.player.tickCount;
            InteractionResult result = mc.gameMode.useItemOn(mc.player, this.slot.hand(), hit);
            if (result == InteractionResult.PASS && this.interactItem.get()
                    && ProtocolTranslator.getTargetVersion().olderThan(ProtocolVersion.v1_21)) {
                mc.gameMode.useItem(mc.player, this.slot.hand());
            }
            if (result.consumesAction()) {
                this.placeCount++;
                this.lastPlaced = placeTarget.placedPos();
                if (pitchDiff > 0) this.lastPlacePitchDifference = pitchDiff;
                if (yawDiff > 0.0F) this.lastPlacedDeltaYaw = yawDiff;
                if (this.noSwing.get()) mc.player.connection.send(new ServerboundSwingPacket(this.slot.hand()));
                else mc.player.swing(this.slot.hand());
            }
            // Preserve the original ordering: a block-use is always emitted before these item uses.
            if (this.abuseRotation.get() && shouldRunAbuseRotation(ProtocolTranslator.getTargetVersion())) {
                float change = Mth.wrapDegrees(reported.yaw() - this.previousSentRotation.yaw());
                int steps = Math.min(6, (int) (Math.abs(change) / 30));
                float yaw = this.previousSentRotation.yaw();
                for (int i = 0; i < steps; i++) {
                    yaw += Mth.clamp(change, -30, 30);
                    mc.player.setYRot(yaw);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }
            }
        } finally {
            mc.player.setYRot(cameraYaw);
            mc.player.setXRot(cameraPitch);
        }
    }

    /**
     * Restored from original SigmaClient/OpenSSNGScaffoldAndClutch getBlockData -> getPos / getBlockPos + getPlaceSide:
     * only targets {@code base} itself when directly supported, returning null when {@code base} is already solid
     * (preventing cross-shaped filling around the player's feet), and falling back to the closest lower solid anchor's
     * nearest open side only when suspended over air.
     */
    private @Nullable Target findTarget(final BlockPos base, final Vec3 eyes) {
        if (mc.level == null || mc.player == null) return null;
        // Step 1 (getPos): check the 5 direct supporting neighbors of base.
        BlockPos directAnchor = directSupportAnchor(base);
        if (directAnchor != null) {
            if (!mc.level.getBlockState(base).canBeReplaced()) return null;
            Target direct = BlockFlyPlacement.find(mc.player, eyes, base, this.sentRotation);
            return direct != null && isAnchor(direct.hit().getBlockPos()) ? direct : null;
        }

        // Step 2 (getBlockPos + getPlaceSide): only when base has no direct support, find the nearest solid anchor below feet.
        if (!mc.level.getBlockState(base).canBeReplaced()) return null;
        BlockPos nearestAnchor = findNearestLowerAnchor();
        if (nearestAnchor == null) return null;
        Direction side = bestPlaceSide(nearestAnchor);
        if (side == null) return null;
        BlockPos placedPos = nearestAnchor.relative(side);
        if (!mc.level.getBlockState(placedPos).canBeReplaced()) return null;
        Target fallback = BlockFlyPlacement.find(mc.player, eyes, placedPos, this.sentRotation);
        return fallback != null && isAnchor(fallback.hit().getBlockPos()) ? fallback : null;
    }

    private @Nullable BlockPos directSupportAnchor(final BlockPos pos) {
        BlockPos[] neighbors = {
                pos.west(), pos.east(), pos.south(), pos.north(), pos.below()
        };
        for (BlockPos anchor : neighbors) {
            if (isSolidAnchor(anchor)) return anchor;
        }
        return null;
    }

    private @Nullable BlockPos findNearestLowerAnchor() {
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 5; dx++) {
            for (int dy = -4; dy < 0; dy++) {
                for (int dz = -4; dz <= 5; dz++) {
                    cursor.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    if (!isSolidAnchor(cursor)) continue;
                    double d = cursor.distSqr(playerPos);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private @Nullable Direction bestPlaceSide(final BlockPos anchor) {
        BlockPos playerPos = mc.player.blockPosition();
        Direction best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        Direction[] horizontals = {Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST};
        for (Direction dir : horizontals) {
            BlockPos sidePos = anchor.relative(dir);
            if (sidePos.equals(playerPos) || !mc.level.getBlockState(sidePos).canBeReplaced()) continue;
            double d = sidePos.distSqr(playerPos);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = dir;
            }
        }
        return best;
    }

    private boolean isSolidAnchor(final BlockPos pos) {
        return !mc.level.getBlockState(pos).canBeReplaced() && isAnchor(pos);
    }

    /** Applies mouse-step GCD quantization first, then nudges by whole GCD steps if DuplicateRotPlace would trigger. */
    private Rotation finalizeRotation(final Vec3 eyes, final Target target, final Rotation raw) {
        double mouseStep = Rotations.mouseStep(mc.options.sensitivity().get());
        Rotation result = this.fixRotation.get() ? Rotations.quantize(this.sentRotation, raw, mouseStep) : raw;
        if (!this.duplicateRotPlace.get() || Float.isNaN(this.lastPlacedDeltaYaw)) {
            return result;
        }
        float deltaYaw = Math.abs(result.yaw() - this.sentRotation.yaw());
        if (deltaYaw > 2.0F && Math.abs(deltaYaw - this.lastPlacedDeltaYaw) < 0.002F) {
            int[] steps = {1, -1, 2, -2};
            for (int s : steps) {
                Rotation nudged = new Rotation((float) (result.yaw() + s * mouseStep), result.pitch());
                float nudgedDelta = Math.abs(nudged.yaw() - this.sentRotation.yaw());
                if (Math.abs(nudgedDelta - this.lastPlacedDeltaYaw) >= 0.002F
                        && BlockFlyPlacement.valid(mc.player, eyes, target, nudged)) {
                    return nudged;
                }
            }
        }
        return result;
    }

    private boolean isAnchor(final BlockPos pos) {
        Block block = mc.level.getBlockState(pos).getBlock();
        return !INVALID.contains(block) && !(block instanceof SignBlock) && !(block instanceof DoorBlock)
                && !(block instanceof TrapDoorBlock) && !(block instanceof FenceGateBlock);
    }

    private @Nullable Slot selectSlot() {
        if (ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_8) && validStack(mc.player.getOffhandItem())) return new Slot(40, InteractionHand.OFF_HAND);
        int selected = mc.player.getInventory().getSelectedSlot();
        if (this.blockSlotMode.is(SlotMode.FARTHEST) && validStack(mc.player.getMainHandItem())) return new Slot(selected, InteractionHand.MAIN_HAND);
        int best = validStack(mc.player.getMainHandItem()) ? selected : -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (validStack(stack) && (this.blockSlotMode.is(SlotMode.FARTHEST) || best < 0
                    || stack.getCount() > mc.player.getInventory().getItem(best).getCount())) best = i;
        }
        return best < 0 ? null : new Slot(best, InteractionHand.MAIN_HAND);
    }

    private void selectForUse() {
        if (this.slot != null && this.slot.hand() == InteractionHand.MAIN_HAND) {
            mc.player.getInventory().setSelectedSlot(this.slot.index());
            this.selectedSlot = this.slot.index();
            mc.gameMode.syncCarriedItem();
        }
    }

    private ItemStack stack(final Slot slot) {
        return slot.hand() == InteractionHand.OFF_HAND ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot.index());
    }

    private static boolean validStack(final ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        Block block = item.getBlock();
        return !INVALID.contains(block) && !(block instanceof SignBlock)
                && block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private int countBlocks() {
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (validStack(stack)) count += stack.getCount();
        }
        if (validStack(mc.player.getOffhandItem())) count += mc.player.getOffhandItem().getCount();
        return count;
    }

    @Override
    public ItemStack renderedItem(final InteractionHand hand, final ItemStack original) {
        return this.spoofItem.get() && hand == InteractionHand.MAIN_HAND && sameSession() && this.originalSlot >= 0
                ? mc.player.getInventory().getItem(this.originalSlot) : original;
    }

    @Override
    public void click(final EventClick event) {
        if (event.getButton() == EventClick.Button.RIGHT) event.cancel();
    }

    @Override
    public void fov(final EventGetFovModifier event) {
        if (this.keepFoV.get() && mc.player.input.moveVector.lengthSquared() > 0) {
            MobEffectInstance speed = mc.player.getEffect(MobEffects.SPEED);
            event.setFovModifier(this.fovValue.get() + (speed == null ? 0 : speed.getAmplifier() + 1) * 0.13F);
        }
    }

    @Override
    public void render(final EventRender2D event) {
        if (!event.isPost() || !this.blockCount.get() || mc.gui.hud.isHidden()) return;
        int count = countBlocks();
        this.startHotbarCount = Math.max(this.startHotbarCount, count);
        GuiGraphicsExtractor g = event.getGraphics();
        int y = event.getHeight() / 2 + 15 + this.blockCountOffset.getInt();
        if (this.blockCountStyle.is(CounterStyle.OLD)) {
            int color = count < 16 ? 0xFFFF5050 : count < 32 ? 0xFFFFDC50 : 0xFFFFFFFF;
            String text = count + " Blocks";
            g.text(mc.font, text, (event.getWidth() - mc.font.width(text)) / 2, y, color, true);
            return;
        }
        Slot shown = this.slot != null && validStack(stack(this.slot)) ? this.slot : selectSlot();
        ModernBlockFlyCounter.render(
            g,
            count,
            shown != null ? stack(shown) : ItemStack.EMPTY,
            event.getWidth() / 2.0F,
            y
        );
    }

    @Override
    public void sendPacket(final EventSendPacket event) {
        if (!sameSession() || event.isCancelled()) return;
        Packet<?> packet = event.getPacket();
        if (!this.flushing && packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            this.previousSentRotation = this.sentRotation;
            this.sentRotation = new Rotation(move.getYRot(this.sentRotation.yaw()), move.getXRot(this.sentRotation.pitch()));
            this.pitchDifference = Math.abs(this.sentRotation.pitch() - this.previousSentRotation.pitch());
        }
        if (!this.holding || this.flushing || !holdable(packet)) return;
        if (this.held.size() >= MAX_HELD_PACKETS) release(true, false);
        this.held.addLast(packet);
        event.cancel();
    }

    @Override
    public void receivePacket(final EventReceivePacket event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ClientPacketListener captured = this.session;
            mc.execute(() -> {
                if (captured != null && captured == this.session && sameSession()) {
                    release(false, true);
                    this.deferredPlacement = null;
                    this.target = null;
                    this.cancelMove = false;
                }
            });
        }
    }

    private static boolean holdable(final Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundPlayerInputPacket
                || packet instanceof ServerboundPlayerCommandPacket || packet instanceof ServerboundSetCarriedItemPacket
                || packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundSwingPacket || packet instanceof ServerboundPlayerActionPacket
                || packet instanceof ServerboundInteractPacket || packet instanceof ServerboundContainerClickPacket
                || packet instanceof ServerboundClientTickEndPacket;
    }

    private void release(final boolean send, final boolean stop) {
        this.flushing = true;
        try {
            if (send && sameSession() && this.session.getConnection().isConnected()) {
                Packet<?> packet;
                while ((packet = this.held.pollFirst()) != null) this.session.send(packet);
            } else this.held.clear();
        } finally {
            this.flushing = false;
            this.heldTicks = 0;
            if (stop) this.holding = false;
        }
    }

    private static float random(final float min, final float max) {
        return (float) ThreadLocalRandom.current().nextDouble(min, max);
    }

    record Prediction(Vec3 nextEyes, Vec3 afterTwo) { }

    /** The source's two air-only steps, kept independent of mutable player state. */
    static Prediction predict(Vec3 position, Vec3 motion, final float strafe, final float forward, final float yaw,
                              final boolean sprinting, final float eyeHeight) {
        Vec3 input = new Vec3(strafe, 0, forward);
        if (input.lengthSqr() > 1) input = input.normalize();
        input = input.scale(sprinting ? 0.026 : 0.02);
        double angle = Math.toRadians(yaw), sine = Math.sin(angle), cosine = Math.cos(angle);
        Vec3 acceleration = new Vec3(input.x * cosine - input.z * sine, 0, input.z * cosine + input.x * sine);
        Vec3 nextEyes = position.add(0, eyeHeight, 0);
        for (int i = 0; i < 2; i++) {
            motion = motion.add(acceleration);
            position = position.add(motion);
            if (i == 0) nextEyes = position.add(0, eyeHeight, 0);
            motion = motion.add(0, -0.08, 0).multiply(0.91, 0.98, 0.91);
        }
        return new Prediction(nextEyes, position);
    }
}
