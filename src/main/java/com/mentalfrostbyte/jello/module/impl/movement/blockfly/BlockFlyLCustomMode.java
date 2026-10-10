package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Naven/HeyPixel's LCustom: right-click gating and the predictive six-layer support search. */
public final class BlockFlyLCustomMode extends BlockFlyCustomBase {
    private final BooleanSetting whileRightClick;

    public BlockFlyLCustomMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.L_CUSTOM, "LCustom");
        this.whileRightClick = parent.modeSetting(BlockFly.Mode.L_CUSTOM, new BooleanSetting("LCustom While Right Click", "Only rotate, jump, sneak and place while use is held.", true));
    }

    @Override protected boolean activationHeld() {
        return !this.whileRightClick.get() || GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }
    @Override protected float turnSpeed() { return 180.0F; }

    /** Naven's mode owns its jump/sneak input and never calls the legacy parent tower/sprint routine. */
    @Override public boolean usesSharedMovement() { return false; }

    @Override
    protected BlockFlyPlacement.Target search(final LocalPlayer player, final Vec3 eyes) {
        Vec3 prediction = player.position().add(player.getDeltaMovement().scale(2.0));
        BlockPos base = BlockPos.containing(prediction.x, this.baseY, prediction.z);
        for (BlockPos placed : pyramid(base)) {
            // Search order uses the prediction; reach and ray endpoints share the real current eye.
            BlockFlyPlacement.Target candidate = BlockFlyPlacement.find(player, eyes, placed, this.rotation);
            if (candidate != null && eyes.distanceToSqr(candidate.hit().getLocation()) <= 20.25) return candidate;
        }
        return null;
    }

    @Override
    protected Rotation desiredRotation(final LocalPlayer player, final Vec3 eyes, final BlockFlyPlacement.Target target) {
        Rotation quantized = this.fixed(target.rotation());
        return this.hitsTarget(player, eyes, quantized, target) ? quantized : target.rotation();
    }

    @Override
    public void click(final EventClick event) {
        // Automatic mode leaves ordinary use available, as in the source.
        if (this.whileRightClick.get()) super.click(event);
    }
}
