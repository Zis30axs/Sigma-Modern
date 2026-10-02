package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.misc.FakePlayer;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The shared answer to "may a combat module target this entity?".
 *
 * <p>Range, priority and aim strategy still belong to the module doing the targeting. This module only owns the
 * categories that should be considered targets, so KillAura and future combat modules can agree on them.</p>
 *
 * <p>There is no server-side AntiBot classifier yet. For now {@link Kind#BOT} positively identifies Sigma's own
 * {@link FakePlayer}; a future AntiBot only needs to extend {@link #isKnownBot(LivingEntity)} instead of teaching
 * every combat module about bots separately.</p>
 */
public final class Target extends Module {

    enum Kind {
        PLAYER,
        BOT,
        MOB,
        ANIMAL
    }

    private final BooleanSetting players = this.register(new BooleanSetting(
            "Players", "Targets real players that are not known bots.", true));

    private final BooleanSetting bots = this.register(new BooleanSetting(
            "Bots", "Targets players known to be bots or fakes. Without AntiBot, this currently identifies FakePlayer.", true));

    private final BooleanSetting mobs = this.register(new BooleanSetting(
            "Mobs", "Targets hostile mobs.", true));

    private final BooleanSetting animals = this.register(new BooleanSetting(
            "Animals", "Targets every other creature: animals, villagers and golems.", false));

    private final BooleanSetting invisibles = this.register(new BooleanSetting(
            "Invisibles", "Also permits invisible entities from the enabled target categories.", false));

    public Target() {
        super(ModuleCategory.COMBAT, "Target", "Chooses which kinds of entities combat modules may target.");
    }

    /**
     * Target selection used to live inside KillAura, which was usable without enabling another module. Keep that
     * behaviour for configs written before Target existed.
     */
    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    /** The enabled Target module, or null before startup or while target selection is switched off. */
    public static @Nullable Target current() {
        return Modules.enabled(Target.class);
    }

    /** Whether {@code entity} is currently an allowed target for {@code player}. */
    public boolean accepts(final LocalPlayer player, final LivingEntity entity) {
        if (entity == player || !entity.isAlive() || entity.isRemoved() || entity.isSpectator() || entity instanceof ArmorStand) {
            return false;
        }

        return this.allows(this.kindOf(entity), entity.isInvisible());
    }

    /** Pure settings decision, kept separate so the category semantics are easy to regression-test. */
    boolean allows(final Kind kind, final boolean invisible) {
        if (invisible && !this.invisibles.get()) {
            return false;
        }

        return switch (kind) {
            case PLAYER -> this.players.get();
            case BOT -> this.bots.get();
            case MOB -> this.mobs.get();
            case ANIMAL -> this.animals.get();
        };
    }

    private Kind kindOf(final LivingEntity entity) {
        if (this.isKnownBot(entity)) {
            return Kind.BOT;
        }
        if (entity instanceof Player) {
            return Kind.PLAYER;
        }
        if (entity instanceof Enemy) {
            return Kind.MOB;
        }
        return Kind.ANIMAL;
    }

    /**
     * Bots we can prove today. AntiBot can extend this single point later; until then normal server players are never
     * guessed to be bots, while the client's own FakePlayer is independently selectable through the Bots setting.
     */
    boolean isKnownBot(final LivingEntity entity) {
        FakePlayer fakePlayer = Modules.enabled(FakePlayer.class);
        return fakePlayer != null && fakePlayer.getEntity() == entity;
    }
}
