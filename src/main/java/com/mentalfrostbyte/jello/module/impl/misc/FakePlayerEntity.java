package com.mentalfrostbyte.jello.module.impl.misc;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The {@link FakePlayer}'s body: a remote player that exists only in this client, moved by its
 * {@link FakePlayerBrain} during its own tick - after the level has kept last tick's position, so it renders as
 * smoothly as a real player. The server never hears of it.
 */
final class FakePlayerEntity extends RemotePlayer {

    /** Far from any id a server hands out. */
    static final int ID = -0x5160;

    private final FakePlayer module;
    private final FakePlayerBrain brain;

    FakePlayerEntity(final ClientLevel level, final GameProfile profile, final FakePlayer module, final FakePlayerBrain brain) {
        super(level, profile);
        this.module = module;
        this.brain = brain;
        this.setId(ID);
        this.snapTo(brain.position().x, brain.position().y, brain.position().z);
    }

    /**
     * Never pushed and never pushing: the local player would be shoved by something the server does not know about,
     * which a movement check sees as movement nothing explains.
     */
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void aiStep() {
        LocalPlayer opponent = Minecraft.getInstance().player;
        this.brain.tick(this.module.getMode(), this.module.getSpeed(), this.module.getRadius(),
                opponent == null ? null : opponent.getEyePosition().subtract(0.0, this.getEyeHeight(), 0.0), this::groundAt);
        this.setPos(this.brain.position());
        this.setYRot(this.brain.yaw());
        this.setXRot(this.brain.pitch());
        this.yHeadRot = this.brain.yaw();
        this.yBodyRot = this.brain.yaw();
        this.setOnGround(this.brain.onGround());
        if (this.brain.swung()) {
            this.swing(InteractionHand.MAIN_HAND);
        }
        this.updateSwingTime();
        if (this.getHealth() < this.getMaxHealth()) {
            this.setHealth(Math.min(this.getMaxHealth(), this.getHealth() + 0.05F));
        }
    }

    /** Hit by {@code attacker}: the red flash, knockback away from them, and a little health. */
    void hitBy(final LocalPlayer attacker) {
        this.animateHurt(Mth.wrapDegrees(attacker.getYRot() - this.getYRot()));
        this.brain.knockback(attacker.getYRot());
        float health = this.getHealth() - 2.0F;
        this.setHealth(health <= 1.0F ? this.getMaxHealth() : health);
    }

    Vec3 anchor() {
        return this.brain.anchor();
    }

    private double groundAt(final double x, final double z) {
        return this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }
}
