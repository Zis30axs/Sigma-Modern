package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.player.EventAttack;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;

/**
 * Makes every hit a sprint hit, for the extra knockback (also known as W-Tap).
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/combat/ModuleSuperKnockback.kt}, its
 * {@code Packet} mode), GPL-3.0, Copyright (c) 2015 - 2026 CCBlueX. Before each attack it tells the server the player
 * stopped and started sprinting again, twice over, so the server counts the hit as the first of a fresh sprint. No
 * vanilla client sends sprint commands like that - several in one tick, some undoing each other, none from a key - so
 * this is here to test {@code SelfDetection}'s packet-order and duplicate-state checks with.</p>
 *
 * <p>Left out of the port: the {@code SprintTap} and {@code WTap} modes, which let go of sprint or forward for a tick;
 * they need the movement direction rewritten, which the movement-input hook here does not offer. Of the
 * {@code Conditions}, only upstream's default ({@code NotInWater}) is kept.</p>
 */
public class SuperKnockback extends Module {

    private final NumberSetting hurtTime = this.register(new NumberSetting("Hurt Time",
            "Only when the target has at most this many ticks left of its last hit's red flash; 10 is any time.",
            10.0F, 0.0F, 10.0F, 1.0F));

    private final NumberSetting chance = this.register(new NumberSetting("Chance",
            "How often it acts on an attack, in percent.", 100.0F, 0.0F, 100.0F, 1.0F));

    private final BooleanSetting onlyOnMove = this.register(new BooleanSetting("Only On Move",
            "Only while walking, when a sprint hit is plausible at all.", true));

    private final BooleanSetting onlyForward = this.register(new BooleanSetting("Only Forward",
            "With Only On Move: not while strafing sideways.", true));

    private final BooleanSetting notInWater = this.register(new BooleanSetting("Not In Water",
            "Not while in water, where sprinting means swimming.", true));

    public SuperKnockback() {
        super(ModuleCategory.COMBAT, "SuperKnockback", "Resets sprint in packets before each hit for extra knockback.");
        this.onlyForward.visibleWhen(this.onlyOnMove::get);
    }

    /** LiquidBounce {@code ModuleSuperKnockback.Packet.attackHandler}. */
    @EventTarget
    public void onAttack(final EventAttack event) {
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        if (player == null || connection == null || !(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        if (!this.shouldOperate(player) || target.hurtTime > this.hurtTime.getInt()
                || ThreadLocalRandom.current().nextFloat() * 100.0F >= this.chance.get()) {
            return;
        }
        // A critical needs the player not sprinting; don't trade one away for knockback.
        if (Criticals.wouldDoCriticalHit(player)) {
            return;
        }

        if (player.isSprinting()) {
            connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
        connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));

        // The server now thinks the player sprints; so must the client, or its next tick "corrects" it.
        player.setSprinting(true);
        player.setWasSprinting(true);
    }

    private boolean shouldOperate(final LocalPlayer player) {
        if (this.onlyOnMove.get()) {
            Vec2 move = player.input.getMoveVector();
            boolean sideways = move.x != 0.0F;
            if (move.y == 0.0F && !sideways || this.onlyForward.get() && sideways) {
                return false;
            }
        }
        return !this.notInWater.get() || !player.isInWater();
    }
}
