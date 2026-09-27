package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.EventAttack;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A player that exists only in this client, to try combat modules on: it walks, jumps, flies or fights back within a
 * circle round where it appeared.
 *
 * <p>The server never hears of it. Hitting it sends nothing - the attack is cancelled before its packet (a swing at
 * nothing is all anyone sees), the fake flashes red and is knocked back here, and the attack cooldown resets as after
 * a real hit, so a KillAura keeps its real rhythm. Whatever a module sends while aiming at it - rotations, blocking,
 * movement - does go out, which is what makes it useful with {@code SelfDetection}: aim and movement checks judge the
 * real packets, against a target that moves like a player.</p>
 *
 * <p>It cannot be pushed and pushes nobody: a shove from something the server does not know about would be movement a
 * server-side check cannot explain.</p>
 */
public class FakePlayer extends Module {

    public enum Mode {
        /** Walks from one random spot in the circle to the next. */
        MOVING("Moving"),
        /** Sprints about the circle, jumping as it goes. */
        JUMPING("Jumping"),
        /** Flies between random spots in the air above the circle. */
        FLYING("Flying"),
        /** Fights you: keeps at sword's reach, strafes round you, jumps, swings and backs off, as a player would. */
        COMBAT_SIMULATION("CombatSimulation");

        private final String label;

        Mode(final String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "Moving walks about, Jumping hops about, Flying flies about, CombatSimulation fights you.", Mode.MOVING));

    private final NumberSetting radius = this.register(new NumberSetting("Radius",
            "How far from where it appeared it may go, in blocks.", 6.0F, 2.0F, 16.0F, 0.5F));

    private final NumberSetting speed = this.register(new NumberSetting("Speed",
            "How fast it moves, against a player's own speeds.", 1.0F, 0.25F, 2.0F, 0.05F));

    private final TextSetting name = this.register(new TextSetting("Name",
            "The name over its head.", "FakePlayer", 16));

    private @Nullable FakePlayerEntity entity;

    public FakePlayer() {
        super(ModuleCategory.MISC, "FakePlayer", "A client-side player to try combat modules on: moves, jumps, flies or fights back.");
    }

    public Mode getMode() {
        return this.mode.get();
    }

    public double getSpeed() {
        return this.speed.get();
    }

    public double getRadius() {
        return this.radius.get();
    }

    public @Nullable Entity getEntity() {
        return this.entity;
    }

    @Override
    protected void onDisable() {
        ClientLevel level = mc.level;
        if (this.entity != null && level != null && this.entity.level() == level) {
            level.removeEntity(FakePlayerEntity.ID, Entity.RemovalReason.DISCARDED);
        }
        this.entity = null;
    }

    /**
     * (Re)appears three blocks ahead of the player once there is a world - after joining, after a dimension change - and
     * whenever the player ends up far from its circle, after a teleport, say.
     */
    @EventTarget
    public void onTick(final EventTick event) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!event.isPre() || player == null || level == null) {
            return;
        }
        if (this.entity == null || this.entity.isRemoved() || this.entity.level() != level) {
            this.spawn(player, level);
        } else if (FakePlayerBrain.horizontal(this.entity.anchor(), player.position()) > this.getRadius() + 24.0) {
            level.removeEntity(FakePlayerEntity.ID, Entity.RemovalReason.DISCARDED);
            this.spawn(player, level);
        }
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.entity = null;
    }

    /** Hitting it happens here only; see the class comment. First in line, so no module sends anything for the hit. */
    @EventTarget(EventPriority.HIGHEST)
    public void onAttack(final EventAttack event) {
        LocalPlayer player = mc.player;
        if (player != null && this.entity != null && event.getTarget() == this.entity) {
            event.cancel();
            this.entity.hitBy(player);
            player.resetAttackStrengthTicker();
        }
    }

    private void spawn(final LocalPlayer player, final ClientLevel level) {
        float yaw = (float) Math.toRadians(player.getYRot());
        double x = player.getX() - Math.sin(yaw) * 3.0;
        double z = player.getZ() + Math.cos(yaw) * 3.0;
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        String shown = this.name.get().isBlank() ? "FakePlayer" : this.name.get();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("FakePlayer:" + shown).getBytes(StandardCharsets.UTF_8)), shown);
        FakePlayerBrain brain = new FakePlayerBrain(new Vec3(x, y, z), System.nanoTime());
        this.entity = new FakePlayerEntity(level, profile, this, brain);
        level.addEntity(this.entity);
    }
}
