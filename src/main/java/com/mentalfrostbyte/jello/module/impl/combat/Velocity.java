package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Takes less knockback than the server gives (also known as AntiKnockback).
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/combat/velocity}: {@code ModuleVelocity},
 * {@code VelocityModify}, {@code VelocityJumpReset}), GPL-3.0, Copyright (c) 2015 - 2026 CCBlueX. Here to test
 * {@code SelfDetection}'s knockback checks with, so again one mode on each side of the line:</p>
 * <ul>
 *     <li>{@link Mode#MODIFY}: scales the knockback the server sends before the game applies it - by default to
 *     nothing at all, by dropping the packet. The server expected the player to fly back; a velocity check sees that
 *     they did not.</li>
 *     <li>{@link Mode#JUMP_RESET}: jumps the tick after a sprinting player is hit, the timing trick players use by
 *     hand to cut knockback. The knockback is all taken, so a correct anticheat stays quiet.</li>
 * </ul>
 *
 * <p>Left out of the port: the server- and anticheat-specific modes, the delay and pause-on-setback switches, and
 * Modify's {@code MotionHorizontal}/{@code MotionVertical} (at upstream's default of 0 they zero the axis, which is what
 * a factor of 0 does here), {@code Filter}, {@code OnlyMove} and {@code TransactionBuffer} - the last holds back pongs,
 * which a module must never do by re-creating them (see {@code SELFCHECK_PORTING.md}). Jump Reset keeps the
 * delay-based cooldown (upstream's default) and drops the hit-count one.</p>
 *
 * <p>Knockback arrives on the network thread, so {@link #onReceive} only reads the player's id and ground/sprint
 * state and rewrites the packet; the game applies the result on its own thread as usual.</p>
 */
public class Velocity extends Module {

    public enum Mode {
        MODIFY,
        JUMP_RESET
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "Modify scales the knockback the server sends; JumpReset jumps right after a hit to cut it, as players do by hand.",
            Mode.MODIFY));

    private final NumberSetting horizontal = this.register(new NumberSetting("Horizontal",
            "Modify: share of the sideways knockback kept. 0 with Vertical 0 drops it entirely; negative pulls you in.",
            0.0F, -1.0F, 1.0F, 0.01F));

    private final NumberSetting vertical = this.register(new NumberSetting("Vertical",
            "Modify: share of the upward knockback kept.", 0.0F, -1.0F, 1.0F, 0.01F));

    private final BooleanSetting explosions = this.register(new BooleanSetting("Explosions",
            "Modify: scales explosion knockback too.", true));

    private final NumberSetting chance = this.register(new NumberSetting("Chance",
            "How often it acts on a hit, in percent.", 100.0F, 0.0F, 100.0F, 1.0F));

    private final NumberSetting untilJump = this.register(new NumberSetting("Until Jump",
            "JumpReset: ticks that must pass after one jump reset before the next.", 2.0F, 0.0F, 20.0F, 1.0F));

    // JumpReset, game thread only.
    private int ticksSinceJump;
    // JumpReset: whether the last knockback was fall damage's straight-down push, which jumping cannot reset. Written
    // on the network thread.
    private volatile boolean fallDamage;

    public Velocity() {
        super(ModuleCategory.COMBAT, "Velocity", "Takes less knockback than the server gives (Modify, JumpReset).");
        this.horizontal.visibleWhen(() -> this.mode.is(Mode.MODIFY));
        this.vertical.visibleWhen(() -> this.mode.is(Mode.MODIFY));
        this.explosions.visibleWhen(() -> this.mode.is(Mode.MODIFY));
        this.untilJump.visibleWhen(() -> this.mode.is(Mode.JUMP_RESET));
    }

    @Override
    protected void onEnable() {
        this.ticksSinceJump = 0;
        this.fallDamage = false;
    }

    @EventTarget
    public void onReceive(final EventReceivePacket event) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket motion && motion.id() == player.getId()) {
            Vec3 knockback = motion.movement();
            if (this.mode.is(Mode.JUMP_RESET)) {
                // LiquidBounce VelocityJumpReset.packetHandler: set on every push, since a later hit tells from its own.
                this.fallDamage = knockback.x == 0.0 && knockback.z == 0.0 && knockback.y < 0.0;
                return;
            }
            if (!this.roll()) {
                return;
            }
            Vec3 kept = scaled(knockback, this.horizontal.get(), this.vertical.get());
            if (kept == null) {
                event.cancel();
            } else {
                event.setPacket(new ClientboundSetEntityMotionPacket(motion.id(), kept));
            }
        } else if (event.getPacket() instanceof ClientboundExplodePacket explosion && this.mode.is(Mode.MODIFY)
                && this.explosions.get() && explosion.playerKnockback().isPresent() && this.roll()) {
            // Upstream scales explosion knockback without ever dropping it: the explosion itself still has to show.
            Vec3 knockback = explosion.playerKnockback().get();
            Vec3 kept = new Vec3(knockback.x * this.horizontal.get(), knockback.y * this.vertical.get(), knockback.z * this.horizontal.get());
            event.setPacket(new ClientboundExplodePacket(explosion.center(), explosion.radius(), explosion.blockCount(), Optional.of(kept),
                    explosion.explosionParticle(), explosion.explosionSound(), explosion.blockParticles()));
        }
    }

    /** JumpReset: jump on the first tick after a hit, sprinting on the ground (LiquidBounce {@code VelocityJumpReset}). */
    @EventTarget
    public void onMovementInput(final EventMovementInput event) {
        LocalPlayer player = mc.player;
        if (player == null || !this.mode.is(Mode.JUMP_RESET)) {
            return;
        }
        // Knockback only bends with a sprint jump, so it has to be sprinting to be worth it.
        if (player.hurtTime != 9 || !player.onGround() || !player.isSprinting() || this.fallDamage
                || this.ticksSinceJump < this.untilJump.getInt() || !this.roll()) {
            this.ticksSinceJump++;
            return;
        }
        event.setJump(true);
        this.ticksSinceJump = 0;
    }

    private boolean roll() {
        return ThreadLocalRandom.current().nextFloat() * 100.0F < this.chance.get();
    }

    /**
     * {@code knockback} scaled by {@code horizontal} sideways and {@code vertical} upwards, or null when both are 0 and
     * the push is to be dropped altogether - which leaves the player's own motion as it was, where a scaled push of 0
     * would stop them (LiquidBounce {@code VelocityModify.packetHandler}).
     */
    static @Nullable Vec3 scaled(final Vec3 knockback, final float horizontal, final float vertical) {
        if (horizontal == 0.0F && vertical == 0.0F) {
            return null;
        }
        return new Vec3(knockback.x * horizontal, knockback.y * vertical, knockback.z * horizontal);
    }
}
