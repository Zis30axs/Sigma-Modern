package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.player.EventAttack;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventJump;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.WebBlock;

/**
 * Makes attacks land as critical hits.
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/combat/criticals}:
 * {@code ModuleCriticals}, {@code CriticalsPacket}, {@code CriticalsNoGround}, {@code CriticalsJump}), GPL-3.0,
 * Copyright (c) 2015 - 2026 CCBlueX. Like {@code Speed}, it is here to test {@code SelfDetection} with, so the modes
 * cover both sides of the line:</p>
 * <ul>
 *     <li>{@link Mode#JUMP}: jumps for you while an enemy is in reach, so the hit lands on the way down. A player
 *     can do this by pressing space; a correct anticheat stays quiet.</li>
 *     <li>{@link Mode#PACKET}: on each attack, tells the server the player hopped by a fraction of a block, in extra
 *     movement packets sent ahead of the attack. The player never moved, so a prediction anticheat sees positions no
 *     movement explains, and the {@link PacketMode#LOW LOW} and {@link PacketMode#GRIM GRIM} offsets are small enough
 *     to fall under the "moved less than the client ever reports" line.</li>
 *     <li>{@link Mode#NO_GROUND}: reports every movement as off the ground.</li>
 * </ul>
 *
 * <p>Left out of the port: the {@code WhenSprinting} group (so, as upstream by default, no critical is attempted while
 * sprinting, where vanilla doesn't crit either), the particle {@code Visuals}, the {@code Blink} and {@code Timer}
 * modes, and Jump's KillAura/AutoClicker gating and its landing simulation. {@link #shouldWaitForJump} keeps
 * upstream's cooldown timing with the flat-ground estimate upstream falls back to when the simulation finds no
 * landing.</p>
 */
public class Criticals extends Module {

    /** Vanilla's jump impulse; Jump only reshapes jumps of exactly this power, not honey-block or boosted ones. */
    static final float BASE_JUMP_POWER = 0.42F;
    private static final float GRAVITY = 0.08F;

    public enum Mode {
        PACKET,
        NO_GROUND,
        JUMP
    }

    /** The hops {@link Mode#PACKET} reports, named as upstream names them. */
    public enum PacketMode {
        VANILLA("Vanilla"),
        NO_CHEAT_PLUS("NoCheatPlus"),
        FALLING("Falling"),
        LOW("Low"),
        DOWN("Down"),
        GRIM("Grim"),
        BLOCKS_MC("BlocksMC");

        private final String label;

        PacketMode(final String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    /** Which movement packet carries a hop: position and rotation (upstream's default), or position only. */
    public enum PacketType {
        FULL,
        POSITION
    }

    /** One reported hop: this far above the player's real position, claiming to be on the ground or not. */
    record Offset(double y, boolean onGround) {
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "Packet reports a small hop before each attack; NoGround reports never touching the ground; Jump jumps for you near enemies.",
            Mode.PACKET));

    private final EnumSetting<PacketMode> packetMode = this.register(new EnumSetting<>("Packet Mode",
            "The hop Packet reports, by the anticheat it was tuned against.", PacketMode.NO_CHEAT_PLUS));

    private final EnumSetting<PacketType> packetType = this.register(new EnumSetting<>("Packet Type",
            "Full sends position and rotation, Position only the position.", PacketType.FULL));

    private final NumberSetting height = this.register(new NumberSetting("Height",
            "Jump: how high the jump goes. 0.42 is a normal jump; lower hops come down sooner.",
            BASE_JUMP_POWER, 0.1F, BASE_JUMP_POWER, 0.01F));

    private final NumberSetting range = this.register(new NumberSetting("Range",
            "Jump: how close an enemy has to be before it starts jumping.", 4.0F, 1.0F, 6.0F, 0.1F));

    private final BooleanSetting waitForCooldown = this.register(new BooleanSetting("Wait For Cooldown",
            "Jump: holds a jump back until it would come down with the attack cooldown ready.", true));

    private final BooleanSetting lineOfSight = this.register(new BooleanSetting("Line Of Sight",
            "Jump: only for enemies the player can see.", true));

    /** Set when Jump presses jump, so only that jump - not one the player makes - gets {@code Height}. */
    private boolean adjustNextJump;

    public Criticals() {
        super(ModuleCategory.COMBAT, "Criticals", "Makes attacks land as critical hits (Packet, NoGround, Jump).");
        this.packetMode.visibleWhen(() -> this.mode.is(Mode.PACKET));
        this.packetType.visibleWhen(() -> this.mode.is(Mode.PACKET));
        this.height.visibleWhen(() -> this.mode.is(Mode.JUMP));
        this.range.visibleWhen(() -> this.mode.is(Mode.JUMP));
        this.waitForCooldown.visibleWhen(() -> this.mode.is(Mode.JUMP));
        this.lineOfSight.visibleWhen(() -> this.mode.is(Mode.JUMP));
    }

    @Override
    protected void onDisable() {
        this.adjustNextJump = false;
    }

    /** Packet: the hop goes out ahead of the attack packet (LiquidBounce {@code CriticalsPacket.attackHandler}). */
    @EventTarget
    public void onAttack(final EventAttack event) {
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        if (!this.mode.is(Mode.PACKET) || player == null || connection == null || !(event.getTarget() instanceof LivingEntity)) {
            return;
        }
        if (!canDoCriticalHit(player, true)) {
            return;
        }

        for (Offset offset : offsets(this.packetMode.get(), player.onGround(), player.tickCount)) {
            connection.send(this.packet(player, offset));
        }
    }

    /** NoGround: every movement packet says off the ground (LiquidBounce {@code CriticalsNoGround}). */
    @EventTarget
    public void onMotion(final EventMotion event) {
        if (event.isPre() && this.mode.is(Mode.NO_GROUND)) {
            event.setOnGround(false);
        }
    }

    /** Jump: press jump on the ground while an enemy is in reach (LiquidBounce {@code CriticalsJump.movementInputEvent}). */
    @EventTarget
    public void onMovementInput(final EventMovementInput event) {
        LocalPlayer player = mc.player;
        if (!this.mode.is(Mode.JUMP) || player == null || !allowsCriticalHit(player, true)) {
            return;
        }
        if (this.waitForCooldown.get() && shouldWaitForJump(this.ticksUntilNextCrit(player))) {
            return;
        }

        if (player.onGround() && this.enemyInRange(player)) {
            event.setJump(true);
            this.adjustNextJump = true;
        }
    }

    /** Jump: that jump gets {@code Height} (LiquidBounce {@code CriticalsJump.jumpHandler}). */
    @EventTarget
    public void onJump(final EventJump event) {
        if (!event.isPre() || !this.mode.is(Mode.JUMP)) {
            return;
        }
        if (this.adjustNextJump && event.getJumpPower() == BASE_JUMP_POWER) {
            event.setJumpPower(this.height.get());
        }
        this.adjustNextJump = false;
    }

    /**
     * The hops {@link Mode#PACKET} reports, in order, as upstream sends them. {@link PacketMode#GRIM} only reports from
     * the air (a player already falling who dips a hair lower still crits in vanilla); {@link PacketMode#BLOCKS_MC}
     * only every fourth tick.
     */
    static List<Offset> offsets(final PacketMode mode, final boolean onGround, final int tickCount) {
        return switch (mode) {
            case VANILLA -> List.of(new Offset(0.2, false), new Offset(0.01, false));
            case NO_CHEAT_PLUS -> List.of(new Offset(0.11, false), new Offset(0.1100013579, false), new Offset(0.0000013579, false));
            case FALLING -> List.of(new Offset(0.0625, false), new Offset(0.0625013579, false), new Offset(0.0000013579, false));
            case LOW -> List.of(new Offset(1.0E-9, false), new Offset(0.0, false));
            case DOWN -> List.of(new Offset(-1.0E-9, false));
            case GRIM -> onGround ? List.of() : List.of(new Offset(-0.000001, false));
            case BLOCKS_MC -> tickCount % 4 == 0 ? List.of(new Offset(0.0011, true), new Offset(0.0, false)) : List.of();
        };
    }

    private ServerboundMovePlayerPacket packet(final LocalPlayer player, final Offset offset) {
        double y = player.getY() + offset.y();
        return switch (this.packetType.get()) {
            case FULL -> new ServerboundMovePlayerPacket.PosRot(player.getX(), y, player.getZ(), player.getYRot(), player.getXRot(),
                    offset.onGround(), player.horizontalCollision);
            case POSITION -> new ServerboundMovePlayerPacket.Pos(player.getX(), y, player.getZ(), offset.onGround(), player.horizontalCollision);
        };
    }

    private boolean enemyInRange(final LocalPlayer player) {
        ClientLevel level = mc.level;
        if (level == null) {
            return false;
        }
        double reach = this.range.get();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player || !(entity instanceof LivingEntity living) || living instanceof ArmorStand
                    || !living.isAlive() || !living.isAttackable() || living.isSpectator()) {
                continue;
            }
            if (entity.getBoundingBox().distanceToSqr(player.getEyePosition()) > reach * reach) {
                continue;
            }
            if (!this.lineOfSight.get() || player.hasLineOfSight(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Ticks until the attack cooldown is far enough along for a critical (LiquidBounce {@code calculateTicksUntilNextCrit}). */
    private float ticksUntilNextCrit(final LocalPlayer player) {
        float delay = player.getCurrentItemAttackStrengthDelay();
        float waited = player.getAttackStrengthScale(0.0F) * delay;
        return Math.max(0.0F, delay * 0.9F - 0.5F - waited);
    }

    /**
     * Whether Jump should hold a jump back (LiquidBounce {@code CriticalsJump.shouldWaitForJump}): yes while jumping now
     * would reach the top of the jump before the cooldown recovers, unless the cooldown is so far off that even the
     * whole jump would not cover it. Landing is taken as twice the time to the top, upstream's estimate when its
     * simulation finds no ground to land on.
     */
    static boolean shouldWaitForJump(final float ticksUntilNextCrit) {
        float ticksTillFall = BASE_JUMP_POWER / GRAVITY;
        float ticksTillNextOnGround = (int) ticksTillFall * 2;
        if (ticksTillNextOnGround + ticksTillFall < ticksUntilNextCrit) {
            return false;
        }
        return ticksTillFall + 1.0F < ticksUntilNextCrit;
    }

    /** No critical can land at all: liquid, a ladder, a cobweb, flying, and so on (LiquidBounce {@code allowsCriticalHit}). */
    public static boolean allowsCriticalHit(final LocalPlayer player, final boolean ignoreOnGround) {
        if (player.isInWater() || player.isInLava() || player.isPassenger()) {
            return false;
        }
        if (player.level().getBlockStates(player.getBoundingBox()).anyMatch(state -> state.getBlock() instanceof WebBlock)) {
            return false;
        }
        if (player.hasEffect(MobEffects.LEVITATION) || player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.SLOW_FALLING)) {
            return false;
        }
        if (player.onClimbable() || player.isNoGravity() || player.isHandsBusy() || player.getAbilities().flying) {
            return false;
        }
        return ignoreOnGround || !player.onGround();
    }

    /** A critical could land if the player were falling: the cooldown is ready and they are not sprinting. */
    public static boolean canDoCriticalHit(final LocalPlayer player, final boolean ignoreOnGround) {
        return allowsCriticalHit(player, ignoreOnGround) && player.getAttackStrengthScale(0.5F) > 0.9F && !player.isSprinting();
    }

    /** An attack right now would be a critical hit in vanilla (LiquidBounce {@code wouldDoCriticalHit}). */
    public static boolean wouldDoCriticalHit(final LocalPlayer player) {
        return canDoCriticalHit(player, false) && player.fallDistance > 0.0;
    }
}
