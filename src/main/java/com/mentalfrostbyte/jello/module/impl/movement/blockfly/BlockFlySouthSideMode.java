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
    private final BooleanSetting smoothed, safeMode, testOnGround, fixRotation, randomSlow, blockFly, abuseRotation;
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
    private @Nullable Slot slot;
    private @Nullable Target target;
    private @Nullable Target lastFound;
    private @Nullable BlockPos lastPlaced;
    private @Nullable Rotation lastRotation;
    private Rotation sentRotation = new Rotation(0, 0);
    private Rotation previousSentRotation = new Rotation(0, 0);
    private volatile @Nullable ClientPacketListener session;
    private @Nullable ClientLevel level;
    private @Nullable LocalPlayer player;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private int onGroundTicks, offGroundTicks, rotateCount, placeCount, ups, eagleTicks, jumpDelay;
    private int startHotbarCount = 1;
    private double posY, pitchDifference, lastPlacePitchDifference;
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
        this.blockFly = bool("Block Fly", false);
        this.abuseRotation = bool("Abuse Rotation", true);
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
        this.lastPlaced = null;
        this.slot = null;
        this.onGroundTicks = this.offGroundTicks = this.rotateCount = this.placeCount = this.ups = this.eagleTicks = this.jumpDelay = 0;
        this.pitchDifference = this.lastPlacePitchDifference = 0;
        this.canPlace = true;
        this.waitingForEagle = this.cancelMove = false;
        release(false, true);
    }

    @Override
    public void disable() {
        release(sameSession(), true);
        if (sameSession() && this.originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() == this.selectedSlot) {
            mc.player.getInventory().setSelectedSlot(this.originalSlot);
            mc.gameMode.syncCarriedItem();
        }
        this.parent.clearRotation();
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
        this.session = null;
    }

    @Override
    public void tick(final EventTick event) {
        if (!sameSession()) { release(false, true); return; }
        if (event.isPost()) {
            if (!this.blockFly.get()) release(true, true);
            else if (this.holding && ++this.heldTicks > 16) release(true, false);
            if (this.mark.get() && this.lastPlaced != null) {
                try (Gizmos.TemporaryCollection ignored = mc.collectPerTickGizmos()) {
                    Gizmos.cuboid(this.lastPlaced, 0.002F, GizmoStyle.strokeAndFill(this.markLineColor.get(), 2, this.markSideColor.get()));
                }
            }
            return;
        }
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
        Target found = findTarget(BlockPos.containing(mc.player.getX(), this.posY, mc.player.getZ()), eyes);
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
        if (this.duplicateRotPlace.get()) {
            rotation = new Rotation(rotation.yaw() - random(0.0001F, 0.0003F),
                    Mth.clamp(rotation.pitch() - random(0.001F, 0.003F), -90, 90));
        }
        if (this.fixRotation.get()) rotation = Rotations.quantize(this.sentRotation, rotation, Rotations.mouseStep(mc.options.sensitivity().get()));
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
        if (!event.isPost() || event.isCancelled() || !sameSession() || this.target == null || this.slot == null || !this.canPlace
                || mc.gui.screen() != null) return;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + mc.player.getEyeHeight(), event.getZ());
        Rotation reported = new Rotation(event.getYaw(), event.getPitch());
        if (!BlockFlyPlacement.valid(mc.player, eyes, this.target, reported)) return;
        if (this.duplicateRotPlace.get() && this.pitchDifference > 2 && Math.abs(this.pitchDifference - this.lastPlacePitchDifference) < 0.0001) return;
        BlockHitResult hit = BlockFlyPlacement.raycast(mc.player, eyes, reported);
        if (hit == null || !validStack(stack(this.slot))) return;
        selectForUse();
        BlockPlaceContext context = new BlockPlaceContext(mc.player, this.slot.hand(), stack(this.slot), hit);
        if (!context.canPlace() || !context.getClickedPos().equals(this.target.placedPos())) return;
        if (this.blockFly.get()) {
            if (!this.holding) { this.holding = true; this.heldTicks = 0; }
            if (this.placeCount == 0 && ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_8)) {
                mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ZERO, Direction.DOWN));
            }
        }
        float cameraYaw = mc.player.getYRot(), cameraPitch = mc.player.getXRot();
        try {
            // Directional block prediction must see the rotation in the movement packet too.
            mc.player.setYRot(reported.yaw());
            mc.player.setXRot(reported.pitch());
            InteractionResult result = mc.gameMode.useItemOn(mc.player, this.slot.hand(), hit);
            if (result == InteractionResult.PASS && this.interactItem.get()) mc.gameMode.useItem(mc.player, this.slot.hand());
            if (result.consumesAction()) {
                this.placeCount++;
                this.lastPlaced = this.target.placedPos();
                if (this.pitchDifference > 0) this.lastPlacePitchDifference = this.pitchDifference;
                if (this.noSwing.get()) mc.player.connection.send(new ServerboundSwingPacket(this.slot.hand()));
                else mc.player.swing(this.slot.hand());
            }
            // Preserve the original ordering: a block-use is always emitted before these item uses.
            if (this.abuseRotation.get()) {
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

    private @Nullable Target findTarget(final BlockPos base, final Vec3 eyes) {
        for (BlockPos pos : BlockFlyPlacement.positionsAround(base, 3)) {
            if (pos.getY() >= Mth.floor(mc.player.getY())) continue;
            Target candidate = BlockFlyPlacement.find(mc.player, eyes, pos, this.sentRotation);
            if (candidate != null && isAnchor(candidate.hit().getBlockPos())) return candidate;
        }
        return null;
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
        int color = count < 16 ? 0xFFFF5050 : count < 32 ? 0xFFFFDC50 : 0xFFFFFFFF;
        String text = count + " Blocks";
        if (this.blockCountStyle.is(CounterStyle.OLD)) {
            g.text(mc.font, text, (event.getWidth() - mc.font.width(text)) / 2, y, color, true);
            return;
        }
        int width = mc.font.width(text) + 38;
        int x = (event.getWidth() - width) / 2;
        g.fill(x, y, x + width, y + 26, 0x5A000000);
        int filled = Math.round(width * Mth.clamp((float) count / this.startHotbarCount, 0, 1));
        g.fill(x, y, x + filled, y + 26, 0x5AFFFFFF);
        if (this.slot != null && !stack(this.slot).isEmpty()) g.item(stack(this.slot), x + 4, y + 5);
        g.text(mc.font, text, x + 28, y + 9, color, false);
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
