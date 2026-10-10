package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** HeyPixel's shared jump, Eagle, snap and held-item presentation policy. */
abstract class BlockFlyCustomBase extends BlockFlyMode {
    public enum BridgeMode { NORMAL, TELLY_BRIDGE, KEEP_Y }

    private static final Set<Block> EXTRA_EXCLUDED = Set.of(Blocks.SNOW, Blocks.COAL_ORE, Blocks.DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.GOLD_ORE, Blocks.IRON_ORE, Blocks.LAPIS_ORE, Blocks.REDSTONE_TORCH, Blocks.FLOWER_POT);
    protected final EnumSetting<BridgeMode> bridgeMode;
    protected final BooleanSetting eagle;
    protected final BooleanSetting sneak;
    protected final BooleanSetting snap;
    protected final BooleanSetting hideSnap;
    protected final BooleanSetting renderItemSpoof;
    protected Rotation rotation;
    protected Rotation correctRotation;
    protected BlockFlyPlacement.Target target;
    protected int baseY;
    protected int offGroundTicks;
    protected boolean active;
    protected boolean wantsJump;
    private int previousSlot = -1;
    private int ownedSlot = -1;
    private int sneakTicks;
    private boolean forceSneak;
    private boolean queued;
    private BlockFlyPlacement.Target queuedTarget;
    private LocalPlayer sessionPlayer;

    BlockFlyCustomBase(final BlockFly parent, final BlockFly.Mode mode, final String prefix) {
        super(parent);
        this.bridgeMode = parent.modeSetting(mode, new EnumSetting<>(prefix + " Mode", "Bridge style.", BridgeMode.NORMAL));
        this.eagle = parent.modeSetting(mode, new BooleanSetting(prefix + " Eagle", "Sneak on block edges in Normal mode.", true), () -> this.bridgeMode.is(BridgeMode.NORMAL));
        this.sneak = parent.modeSetting(mode, new BooleanSetting(prefix + " Sneak", "Pulse sneak on ticks 18 through 20 of each cycle.", true));
        this.snap = parent.modeSetting(mode, new BooleanSetting(prefix + " Snap", "Snap to a placement face in Normal mode.", true), () -> this.bridgeMode.is(BridgeMode.NORMAL));
        this.hideSnap = parent.modeSetting(mode, new BooleanSetting(prefix + " Hide Snap Rotation", "Keep snap corrections out of the camera.", true), () -> this.bridgeMode.is(BridgeMode.NORMAL) && this.snap.get());
        this.renderItemSpoof = parent.modeSetting(mode, new BooleanSetting(prefix + " Render Item Spoof", "Show the original held item while scaffolding.", true));
    }

    protected abstract boolean activationHeld();
    protected abstract BlockFlyPlacement.Target search(LocalPlayer player, Vec3 eyes);
    protected abstract Rotation desiredRotation(LocalPlayer player, Vec3 eyes, BlockFlyPlacement.Target target);
    protected abstract float turnSpeed();
    protected boolean grimRotation() { return false; }
    protected boolean rejectUseName() { return false; }

    @Override
    public boolean usesSharedMovement() { return this.active && this.activationHeld(); }

    @Override
    public void enable() {
        this.sessionPlayer = mc.player;
        this.previousSlot = mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
        this.ownedSlot = -1;
        this.rotation = mc.player == null ? new Rotation(0.0F, 0.0F)
                : new Rotation(mc.player.getYRot() - 180.0F, mc.player.getXRot());
        this.correctRotation = this.rotation;
        this.baseY = Integer.MIN_VALUE;
        this.offGroundTicks = 0;
        this.sneakTicks = 0;
        this.active = false;
        this.target = null;
        this.forceSneak = false;
        this.wantsJump = false;
        this.queued = false;
        this.queuedTarget = null;
    }

    @Override
    public void disable() {
        this.restoreOwnedSlot();
        this.previousSlot = this.ownedSlot = -1;
        this.sessionPlayer = null;
        this.releaseInput();
        this.parent.clearRotation();
    }

    private void restoreOwnedSlot() {
        if (mc.player == this.sessionPlayer && mc.player != null && this.previousSlot >= 0
                && this.ownedSlot >= 0 && mc.player.getInventory().getSelectedSlot() == this.ownedSlot) {
            mc.player.getInventory().setSelectedSlot(this.previousSlot);
            if (mc.gameMode != null && mc.getConnection() != null) mc.gameMode.syncCarriedItem();
        }
        this.ownedSlot = -1;
    }

    private void releaseInput() {
        this.restoreOwnedSlot();
        // Only EventMovementInput is modified, so physical and toggle key states never need unwinding.
        this.active = this.forceSneak = this.wantsJump = this.queued = false;
        this.sneakTicks = 0;
        this.baseY = Integer.MIN_VALUE;
        this.target = null;
        this.queuedTarget = null;
        if (mc.player != null) this.rotation = new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    @Override
    public void tick(final EventTick event) {
        if (!event.isPre()) return;
        if (mc.player != this.sessionPlayer) this.enable();
        this.queued = false;
        this.queuedTarget = null;
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !this.activationHeld()
                || !mc.player.isAlive() || mc.player.isSpectator()) {
            this.releaseInput();
            this.parent.clearRotation();
            return;
        }
        LocalPlayer player = mc.player;
        if (!this.active) this.previousSlot = player.getInventory().getSelectedSlot();
        this.active = this.selectBlock(player);
        if (!this.active) {
            this.releaseInput();
            this.parent.clearRotation();
            return;
        }
        boolean moving = BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput());
        boolean holdingJump = mc.options.keyJump.isDown();
        if (player.onGround()) this.offGroundTicks = 0;
        else this.offGroundTicks++;
        int belowY = Mth.floor(player.getY()) - 1;
        if (this.baseY == Integer.MIN_VALUE || this.baseY > belowY || player.onGround() || !moving
                || holdingJump || this.bridgeMode.is(BridgeMode.NORMAL)) this.baseY = belowY;

        this.target = this.search(player, player.getEyePosition());
        this.forceSneak = false;
        if (this.sneak.get()) {
            this.sneakTicks++;
            if (this.sneakTicks >= 21) this.sneakTicks = 0;
            this.forceSneak = this.sneakTicks >= 18;
            if (this.sneakTicks == 18) player.setSprinting(false);
        } else this.sneakTicks = 0;
        this.wantsJump = !this.bridgeMode.is(BridgeMode.NORMAL) && moving;
        if (this.bridgeMode.is(BridgeMode.NORMAL) && this.eagle.get()) {
            this.forceSneak |= player.onGround() && this.atEdge(player);
        }
        if (this.target != null) {
            this.correctRotation = this.desiredRotation(player, player.getEyePosition(), this.target);
            this.rotation = this.turnTowards(this.correctRotation);
            if (this.bridgeMode.is(BridgeMode.TELLY_BRIDGE) && this.offGroundTicks < 1 && moving) {
                this.rotation = new Rotation(Rotations.continuous(this.rotation.yaw(), player.getYRot()), this.rotation.pitch());
            } else if (this.bridgeMode.is(BridgeMode.NORMAL) && this.snap.get() && !holdingJump
                    && !this.hitsTarget(player, player.getEyePosition(), this.rotation, this.target)) {
                this.rotation = new Rotation(player.getYRot() + jitter(0.25F), this.rotation.pitch());
            }
            this.parent.requestRotation(this.rotation, true, MovementCorrection.SILENT);
        } else this.parent.clearRotation();
    }

    @Override
    public void input(final EventMovementInput event) {
        if (!this.active || !this.activationHeld() || mc.gui.screen() != null) return;
        if (this.forceSneak) event.setShift(true);
        if (!this.bridgeMode.is(BridgeMode.NORMAL) && event.isMoving()) event.setJump(true);
        if (this.forceSneak) event.setSprint(false);
    }

    @Override
    public void motion(final EventMotion event) {
        if (!this.active || !this.activationHeld() || mc.player == null || mc.level == null || mc.gui.screen() != null) {
            this.parent.queuePlacement(null);
            this.parent.clearRotation();
            if (event.isPre() && mc.player != null) {
                event.setYaw(mc.player.getYRot());
                event.setPitch(mc.player.getXRot());
            }
            return;
        }
        if (!event.isPre()) {
            // Explicit rotation mode restores the camera direction only after the queued use-on packet.
            if (this.queued && this.grimRotation()) {
                this.parent.queuePlacement(null);
                this.parent.place(this.queuedTarget);
                mc.player.sendRotation(mc.player.getYRot(), mc.player.getXRot());
            }
            return;
        }
        if (this.target == null) return;
        LocalPlayer player = mc.player;
        Vec3 eyes = new Vec3(event.getX(), event.getY() + player.getEyeHeight(), event.getZ());
        this.parent.aim(event, this.rotation, true, MovementCorrection.SILENT);
        if (this.grimRotation()) event.forceRotation();
        if (this.bridgeMode.is(BridgeMode.TELLY_BRIDGE) && player.onGround()
                && BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput())) return;

        // The predicted eye origin can change during movement. Recast using the exact outgoing look.
        BlockFlyPlacement.Target current = BlockFlyPlacement.fromHit(player, eyes,
                BlockFlyPlacement.raycast(player, eyes, this.rotation), this.rotation);
        if (current == null || !current.placedPos().equals(this.target.placedPos())) return;
        if (current.hit().getDirection() == Direction.DOWN) return;
        if (current.hit().getDirection() == Direction.UP && !player.onGround()
                && BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput()) && !mc.options.keyJump.isDown()
                && !this.bridgeMode.is(BridgeMode.NORMAL)) return;
        if (!this.isValidStack(player.getMainHandItem())) return;
        this.parent.queuePlacement(current);
        this.queued = true;
        this.queuedTarget = current;
    }

    @Override
    public void click(final EventClick event) {
        if (event.getButton() == EventClick.Button.RIGHT && this.active && this.activationHeld()) event.cancel();
    }

    @Override
    public ItemStack renderedItem(final InteractionHand hand, final ItemStack original) {
        if (hand == InteractionHand.MAIN_HAND && this.renderItemSpoof.get() && this.active
                && mc.player == this.sessionPlayer && mc.player != null && this.previousSlot >= 0) {
            return mc.player.getInventory().getItem(this.previousSlot);
        }
        return original;
    }

    /** A render-only look; it must never be written back to LocalPlayer's camera angles. */
    @Override
    public Rotation cameraRotation() {
        return this.active && this.target != null && !this.hideSnap.get() ? this.rotation : null;
    }

    protected final Rotation turnTowards(final Rotation desired) {
        Rotation continuous = Rotations.continuous(this.rotation, desired);
        if (this.bridgeMode.is(BridgeMode.NORMAL) && this.snap.get()) return continuous;
        return new Rotation(Rotations.limit(this.rotation, continuous, this.turnSpeed(), 180.0F).yaw(), continuous.pitch());
    }

    protected final boolean hitsTarget(final LocalPlayer player, final Vec3 eyes, final Rotation look,
                                       final BlockFlyPlacement.Target expected) {
        BlockHitResult hit = BlockFlyPlacement.raycast(player, eyes, look);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(expected.hit().getBlockPos())
                && hit.getDirection() == expected.hit().getDirection();
    }

    protected final Rotation fixed(final Rotation desired) {
        return Rotations.quantize(this.rotation, Rotations.continuous(this.rotation, desired),
                Rotations.mouseStep(mc.options.sensitivity().get()));
    }

    private boolean selectBlock(final LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (this.isValidStack(player.getInventory().getItem(slot))) {
                player.getInventory().setSelectedSlot(slot);
                this.ownedSlot = slot;
                return true;
            }
        }
        return false;
    }

    private boolean isValidStack(final ItemStack stack) {
        if (!BlockFlyPlacement.isPlaceable(stack) || stack.getCount() <= 1
                || !(stack.getItem() instanceof BlockItem item) || item.getBlock() instanceof SlabBlock
                || EXTRA_EXCLUDED.contains(item.getBlock())) return false;
        String name = stack.getHoverName().getString();
        return !name.contains("Click") && !name.contains("Right") && !name.contains("Teleport")
                && (!this.rejectUseName() || !name.contains("Use"))
                && !name.contains("点击") && !name.contains("使用") && !name.contains("传送") && !name.contains("再来");
    }

    private boolean atEdge(final LocalPlayer player) {
        AABB box = player.getBoundingBox();
        double inset = Math.min(0.3, Math.min(box.getXsize(), box.getZsize()) / 2.0 - 0.001);
        return mc.level.noCollision(player, box.deflate(inset, 0.0, inset).move(0.0, -0.5, 0.0));
    }

    protected static float jitter(final float amount) {
        return (float) ThreadLocalRandom.current().nextDouble(-amount, amount);
    }

    /** Naven's six-layer diamond, deterministic and de-duplicated at the symmetry axes. */
    protected static Set<BlockPos> pyramid(final BlockPos base) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        positions.add(base);
        for (int distance = 1; distance <= 6; distance++) {
            positions.add(base.below(distance));
            for (int x = 0; x <= distance; x++) {
                for (int z = 0; z <= distance - x; z++) {
                    if (x == 0 && z == 0) continue;
                    int y = distance - x - z;
                    positions.add(base.offset(x, -y, z));
                    positions.add(base.offset(-x, -y, z));
                    positions.add(base.offset(x, -y, -z));
                    positions.add(base.offset(-x, -y, -z));
                }
            }
        }
        return positions;
    }
}
