package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventJump;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Moves faster than walking by hopping.
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/movement/speed}: {@code ModuleSpeed},
 * {@code SpeedGeneric.kt}; {@code utils/entity/EntityExtensions.kt} for the strafe maths), GPL-3.0, Copyright (c)
 * 2015 - 2026 CCBlueX. Two modes, chosen because they sit on either side of the line an anticheat draws, which is
 * what {@code SelfDetection} needs to be tested against:</p>
 * <ul>
 *     <li>{@link Mode#LEGIT_HOP}: holds jump for you while you move on the ground. Nothing a player could not do by
 *     pressing space, so a correct anticheat stays quiet.</li>
 *     <li>{@link Mode#YPORT}: on every jump sets the horizontal speed to {@code Speed}, and while airborne slams the
 *     vertical speed to -1 so the player is back on the ground at once. Neither is reachable by vanilla movement.</li>
 * </ul>
 *
 * <p>Left out of the port: LiquidBounce's other modes (most target specific anticheats), its "don't jump into a
 * corner" and Criticals coordination, and the only-in-combat / only-with-potion switches.</p>
 */
public class Speed extends Module {

    public enum Mode {
        LEGIT_HOP,
        YPORT
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "LegitHop jumps for you while you move; YPort forces speed on every jump and pulls you straight back down.",
            Mode.LEGIT_HOP));

    private final NumberSetting speed = this.register(new NumberSetting("Speed",
            "YPort: the horizontal speed set on every jump, in blocks per tick. Sprint-jumping gives about 0.3.",
            0.4F, 0.1F, 1.0F, 0.01F));

    public Speed() {
        super(ModuleCategory.MOVEMENT, "Speed", "Moves faster than walking by hopping (LegitHop, YPort).");
    }

    /** Both modes: hold jump for the player whenever they walk on the ground (LiquidBounce {@code SpeedBHopBase}). */
    @EventTarget
    public void onMovementInput(final EventMovementInput event) {
        LocalPlayer player = mc.player;
        if (player != null && player.onGround() && event.isMoving()) {
            event.setJump(true);
        }
    }

    /** YPort: straight back down while airborne and moving (LiquidBounce {@code SpeedSpeedYPort.tickHandler}). */
    @EventTarget
    public void onTick(final EventTick event) {
        LocalPlayer player = mc.player;
        if (!event.isPre() || !this.mode.is(Mode.YPORT) || player == null) {
            return;
        }
        if (!player.onGround() && isMoving(player.input.keyPresses)) {
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(velocity.x, -1.0, velocity.z);
        }
    }

    /** YPort: set the horizontal speed once the jump impulse is in (LiquidBounce {@code SpeedSpeedYPort.afterJumpHandler}). */
    @EventTarget
    public void onJump(final EventJump event) {
        LocalPlayer player = mc.player;
        if (!event.isPost() || !this.mode.is(Mode.YPORT) || player == null) {
            return;
        }
        player.setDeltaMovement(withStrafe(player.getDeltaMovement(), this.speed.get(), player.getYRot(), player.input.keyPresses));
    }

    static boolean isMoving(final Input input) {
        return input.forward() != input.backward() || input.left() != input.right();
    }

    /**
     * {@code velocity} with its horizontal part replaced by {@code speed} in the direction the keys point, or with no
     * horizontal part if they point nowhere (LiquidBounce {@code Vec3.withStrafe} at full strength).
     */
    static Vec3 withStrafe(final Vec3 velocity, final double speed, final float facingYaw, final Input input) {
        if (!isMoving(input)) {
            return new Vec3(0.0, velocity.y, 0.0);
        }
        double angle = Math.toRadians(movementYaw(facingYaw, input));
        return new Vec3(-Math.sin(angle) * speed, velocity.y, Math.cos(angle) * speed);
    }

    /** The yaw the held keys move the player towards (LiquidBounce {@code getMovementDirectionOfInput}). */
    static float movementYaw(final float facingYaw, final Input input) {
        float yaw = facingYaw;
        float forwardMultiplier;
        if (input.backward() && !input.forward()) {
            yaw += 180.0F;
            forwardMultiplier = -0.5F;
        } else if (input.forward() && !input.backward()) {
            forwardMultiplier = 0.5F;
        } else {
            forwardMultiplier = 1.0F;
        }
        if (input.left() && !input.right()) {
            yaw -= 90.0F * forwardMultiplier;
        }
        if (input.right() && !input.left()) {
            yaw += 90.0F * forwardMultiplier;
        }
        return yaw;
    }
}
