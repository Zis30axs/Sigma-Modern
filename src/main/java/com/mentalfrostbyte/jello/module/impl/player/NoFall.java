package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Takes no fall damage, by telling the server the player landed before they did.
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/player/nofall}: {@code ModuleNoFall},
 * {@code NoFallSpoofGround}, {@code NoFallPacket}), GPL-3.0, Copyright (c) 2015 - 2026 CCBlueX. For testing
 * {@code SelfDetection}'s ground checks with; both modes claim ground that isn't there, in the two ways a server can
 * be told so:</p>
 * <ul>
 *     <li>{@link Mode#SPOOF_GROUND}: past {@code Distance}, the movement packets the game sends anyway say "on the
 *     ground". A prediction anticheat compares that with where the player's feet are.</li>
 *     <li>{@link Mode#PACKET}: past {@code Distance}, an extra movement packet each tick says so on its own.</li>
 * </ul>
 *
 * <p>Like upstream it stands aside in creative and spectator mode and while flying or invulnerable. Left out of the
 * port: the other seventeen modes (server-specific, or block/item tricks), the {@code Always} filter and the
 * elytra/mace exceptions. Upstream's "Smart" distance is the player's safe fall distance, 3 unless something changes
 * it; {@code Distance} defaults to that.</p>
 */
public class NoFall extends Module {

    public enum Mode {
        SPOOF_GROUND,
        PACKET
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "SpoofGround marks the game's own movement packets as on the ground; Packet sends an extra one that says so.",
            Mode.SPOOF_GROUND));

    private final NumberSetting distance = this.register(new NumberSetting("Distance",
            "How far to fall before claiming ground. 3 is where fall damage starts.", 3.0F, 0.0F, 5.0F, 0.1F));

    private final BooleanSetting resetFallDistance = this.register(new BooleanSetting("Reset Fall Distance",
            "Forgets the fall so far once ground has been claimed, as the server does on landing.", true));

    public NoFall() {
        super(ModuleCategory.PLAYER, "NoFall", "Takes no fall damage by claiming to land early (SpoofGround, Packet).");
    }

    /** SpoofGround (LiquidBounce {@code NoFallSpoofGround.packetHandler}). */
    @EventTarget
    public void onMotion(final EventMotion event) {
        LocalPlayer player = mc.player;
        if (!event.isPre() || !this.mode.is(Mode.SPOOF_GROUND) || player == null || !active(player)) {
            return;
        }
        if (player.fallDistance >= this.distance.get()) {
            event.setOnGround(true);
            if (this.resetFallDistance.get()) {
                player.resetFallDistance();
            }
        }
    }

    /** Packet (LiquidBounce {@code NoFallPacket.repeatable}, {@code FallDistance} filter). */
    @EventTarget
    public void onTick(final EventTick event) {
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        if (!event.isPre() || !this.mode.is(Mode.PACKET) || player == null || connection == null || !active(player)) {
            return;
        }
        // The fall so far plus this tick's drop, so the claim goes out before the tick that would hurt.
        if (player.fallDistance - player.getDeltaMovement().y > this.distance.get() && player.tickCount > 20) {
            connection.send(new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(), true, player.horizontalCollision));
            if (this.resetFallDistance.get()) {
                player.resetFallDistance();
            }
        }
    }

    /** Upstream's {@code ModuleNoFall.running}: nothing to save where a fall can't hurt. */
    private static boolean active(final LocalPlayer player) {
        return !player.isCreative() && !player.isSpectator() && !player.getAbilities().invulnerable && !player.getAbilities().flying;
    }
}
