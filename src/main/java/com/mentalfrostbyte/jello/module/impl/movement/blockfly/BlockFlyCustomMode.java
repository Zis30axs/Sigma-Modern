package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** SigmaClient's Custom: back-facing pitch search, optional wider search, and gradual telly turns. */
public final class BlockFlyCustomMode extends BlockFlyCustomBase {
    private final BooleanSetting advanced;
    private final BooleanSetting grim;
    private final BooleanSetting duplicate;

    public BlockFlyCustomMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.CUSTOM, "Custom");
        this.advanced = parent.modeSetting(BlockFly.Mode.CUSTOM, new BooleanSetting("Custom Advanced Block Search", "Search neighboring faces when the backward ray misses.", true));
        this.grim = parent.modeSetting(BlockFly.Mode.CUSTOM, new BooleanSetting("Custom Grim1.17Rot", "Send a placement rotation followed by a camera rotation report.", false));
        this.duplicate = parent.modeSetting(BlockFly.Mode.CUSTOM, new BooleanSetting("Custom DuplicateRotPlace", "Vary the turn step and tower aim slightly.", true));
    }

    @Override protected boolean activationHeld() { return true; }
    @Override protected boolean grimRotation() { return this.grim.get(); }
    @Override protected boolean rejectUseName() { return true; }
    @Override protected float turnSpeed() { return this.duplicate.get() ? 72.0F + jitter(3.0F) : 75.0F; }

    @Override
    protected BlockFlyPlacement.Target search(final LocalPlayer player, final Vec3 eyes) {
        BlockPos base = BlockPos.containing(player.getX(), this.baseY, player.getZ());
        return BlockFlyLegacyMode.findOnLayer(player, eyes, base, this.advanced.get() ? 2 : 1, this.rotation);
    }

    @Override
    protected Rotation desiredRotation(final LocalPlayer player, final Vec3 eyes, final BlockFlyPlacement.Target target) {
        float yaw = (this.advanced.get()
                ? BlockFlyLegacyMode.movementYaw(player.getYRot(), BlockFlyLegacyMode.physicalInput()) : player.getYRot()) - 180.0F;
        yaw += jitter(0.25F);
        Rotation backwards = this.fixed(new Rotation(yaw, 82.0F));
        if (this.hitsTarget(player, eyes, backwards, target)) return backwards;
        Rotation best = null;
        float bestDelta = Float.POSITIVE_INFINITY;
        for (float pitch = Math.max(-90.0F, this.rotation.pitch() - 30.0F);
             pitch < Math.min(90.0F, this.rotation.pitch() + 20.0F); pitch += 0.3F) {
            Rotation trial = this.fixed(new Rotation(yaw, pitch));
            if (this.hitsTarget(player, eyes, trial, target)) {
                float delta = Math.abs(trial.pitch() - this.rotation.pitch());
                if (delta < bestDelta) { best = trial; bestDelta = delta; }
            }
        }
        if (best != null) return best;
        if (mc.options.keyJump.isDown() && !BlockFlyLegacyMode.isMoving(BlockFlyLegacyMode.physicalInput())) {
            Rotation tower = new Rotation(yaw + (this.duplicate.get() ? jitter(0.25F) : 0.0F), 90.0F);
            if (this.hitsTarget(player, eyes, tower, target)) return tower;
        }
        if (this.advanced.get()) {
            // The shared shape-face sampler replaces the old 18,000-ray brute-force yaw/pitch loop.
            Rotation precise = this.fixed(target.rotation());
            return this.hitsTarget(player, eyes, precise, target) ? precise : target.rotation();
        }
        return new Rotation(backwards.yaw(), Mth.clamp(backwards.pitch(), -90.0F, 90.0F));
    }
}
