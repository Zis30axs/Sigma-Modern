package com.mentalfrostbyte.jello.module.impl.combat.crystalaura;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-side end-crystal explosion damage, in the shape of LiquidBounce's
 * {@code CrystalAuraDamageOptions} / {@code DamageProvider} plus the shared
 * {@code LivingEntity.getDamageFromExplosion} math.
 *
 * <p>Vanilla has no client-side damage service, so the pipeline is assembled from the pieces that
 * are client-safe: the raw explosion formula (a copy of
 * {@code ExplosionDamageCalculator.getEntityDamageAmount}, which cannot be used directly because it
 * takes a server-only {@code Explosion}), exposure from
 * {@link ServerExplosion#getSeenPercent(Vec3, Entity)}, armor from
 * {@link CombatRules#getDamageAfterAbsorb}, and blast-protection levels summed by hand from the
 * victim's armor, because {@code EnchantmentHelper.getDamageProtection} needs a {@code ServerLevel}.
 * Absorption is never subtracted from the damage itself; it only counts towards the health the
 * anti-suicide check compares against, exactly like upstream.</p>
 */
public final class CrystalDamage {

    /** An end crystal's explosion radius (see {@code EndCrystal.hurtServer}). */
    public static final float CRYSTAL_RADIUS = 6.0F;
    /** Twice the radius: the range over which explosion damage falls to zero. */
    public static final float EXPLOSION_RANGE = CRYSTAL_RADIUS * 2.0F;
    /** With Terrain on, blocks at or below this blast resistance are treated as destroyed by the
     * explosion, so they do not block the exposure ray (upstream's {@code maxBlastResistance = 9f}). */
    public static final float MAX_BLAST_RESISTANCE = 9.0F;

    /** How predicted damage is combined with current damage (upstream {@code CalculationMode}). */
    public enum CalcMode {
        BOTH,
        PREDICT_ONLY
    }

    /** How the two damage values are combined in {@link CalcMode#BOTH} (upstream {@code Logic}). */
    public enum Logic {
        AND,
        OR
    }

    /** The damage thresholds and switches of one evaluation, mirroring upstream's Damage settings. */
    public record DamageOptions(
            float minEnemyDamage,
            float maxSelfDamage,
            float maxFriendDamage,
            boolean antiSuicide,
            boolean efficient,
            boolean terrainAware,
            boolean predictSelf,
            boolean predictTarget,
            int selfTicks,
            int targetTicks,
            CalcMode calcMode,
            Logic logic) {
    }

    /** The (self, enemy) damage of a spot that passed every gate; the values are the predicted ones
     * when prediction is on (upstream's {@code getFixed()} returns the predicted damage). */
    public record DamagePair(float selfDamage, float enemyDamage) {
    }

    private CrystalDamage() {
    }

    // --------------------------------------------------------------------------- raw explosion math

    /**
     * The vanilla explosion damage before armor and enchantments, for a target whose squared
     * distance to the blast centre is {@code distSqr} and whose exposure is {@code exposure}
     * (fraction of unblocked rays, 0..1). Pure maths, so it is unit-testable without a game.
     */
    public static float rawExplosionDamage(final double distSqr, final float exposure) {
        double distanceDecay = 1.0 - Math.sqrt(distSqr) / EXPLOSION_RANGE;
        double pre = exposure * distanceDecay;
        return (float) ((pre * pre + pre) / 2.0 * 7.0 * EXPLOSION_RANGE + 1.0);
    }

    /** Vanilla exposure: the fraction of sample rays from the entity's box that reach the centre. */
    public static float exposure(final Vec3 center, final LivingEntity entity) {
        return ServerExplosion.getSeenPercent(center, entity);
    }

    /**
     * Terrain-aware exposure: like {@link #exposure(Vec3, LivingEntity)} but blocks with a blast
     * resistance at or below {@link #MAX_BLAST_RESISTANCE} do not block the ray, because the
     * explosion would destroy them first. Fluids never block (vanilla's clip context ignores them),
     * and the voxel a ray starts in never blocks it (a ray starting inside a block is unblocked in
     * vanilla's shape clip). Slightly more pessimistic than vanilla's shape clip where a ray only
     * grazes a voxel corner, which errs towards underestimating damage.
     */
    public static float exposureTerrainAware(final ClientLevel level, final Vec3 center, final AABB box) {
        double xs = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) {
            return 0.0F;
        }
        int hits = 0;
        int count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = Mth.lerp(xx, box.minX, box.maxX);
                    double y = Mth.lerp(yy, box.minY, box.maxY);
                    double z = Mth.lerp(zz, box.minZ, box.maxZ);
                    Vec3 from = new Vec3(x + xOffset, y, z + zOffset);
                    Boolean blocked = BlockGetter.traverseBlocks(from, center, null, (context, pos) -> {
                        if (pos.equals(BlockPos.containing(from))) {
                            return null;
                        }
                        var state = level.getBlockState(pos);
                        if (!state.getFluidState().isEmpty()) {
                            return null;
                        }
                        return state.getBlock().getExplosionResistance() > MAX_BLAST_RESISTANCE
                                ? Boolean.TRUE : null;
                    }, context -> Boolean.FALSE);
                    if (!blocked) {
                        hits++;
                    }
                    count++;
                }
            }
        }
        return count == 0 ? 0.0F : (float) hits / count;
    }

    // ------------------------------------------------------------------------- per-entity damage

    /** Full pipeline for one entity at its current position: raw, armor, enchantments, resistance, difficulty. */
    public static float damageToEntity(final Vec3 center, final LivingEntity entity, final ClientLevel level,
                                       final boolean terrainAware) {
        return damageToEntity(center, entity, entity.getBoundingBox(), level, terrainAware);
    }

    /** Same, but against a (possibly predicted) bounding box instead of the entity's current one. */
    public static float damageToEntity(final Vec3 center, final LivingEntity entity, final AABB box,
                                       final ClientLevel level, final boolean terrainAware) {
        double distSqr = box.distanceToSqr(center);
        if (distSqr > EXPLOSION_RANGE * EXPLOSION_RANGE) {
            return 0.0F;
        }
        float exposure = terrainAware
                ? exposureTerrainAware(level, center, box)
                : ServerExplosion.getSeenPercent(center, entity);
        float raw = rawExplosionDamage(distSqr, exposure);
        return effectiveDamage(entity, level, raw);
    }

    /**
     * Armor, enchantments, Resistance and difficulty, in vanilla's order. Invulnerable and creative
     * victims take no damage; the Resistance and difficulty steps match
     * {@code LivingEntity.getDamageAfterMagicAbsorb} / {@code LivingEntity.hurt}.
     */
    static float effectiveDamage(final LivingEntity entity, final ClientLevel level, final float damage) {
        // LivingEntity.isInvulnerableTo needs a ServerLevel, which the client does not have; the
        // plain invulnerable flag covers the cases that matter client-side (markers, spectators).
        if (entity.isInvulnerable() || !entity.isAlive()) {
            return 0.0F;
        }
        if (entity instanceof Player player && player.getAbilities().invulnerable) {
            return 0.0F;
        }
        float amount = damage;
        if (entity instanceof Player) {
            amount = switch (level.getDifficulty()) {
                case PEACEFUL -> 0.0F;
                case EASY -> Math.min(amount / 2.0F + 1.0F, amount);
                case HARD -> amount * 3.0F / 2.0F;
                default -> amount;
            };
            if (amount <= 0.0F) {
                return 0.0F;
            }
        }
        DamageSource source = level.damageSources().explosion(null, null);
        amount = CombatRules.getDamageAfterAbsorb(entity, amount, source, entity.getArmorValue(),
                (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        amount = CombatRules.getDamageAfterMagicAbsorb(amount, blastProtectionTotal(entity, level));
        var resistance = entity.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            amount = amount * (25.0F - (resistance.getAmplifier() + 1) * 5.0F) / 25.0F;
        }
        return Math.max(0.0F, amount);
    }

    /**
     * Total magic armor against an explosion, summed piece by piece: Protection gives 1 per level,
     * Blast Protection 2 per level (vanilla's data-driven values, which the client cannot read
     * because {@code Enchantment.modifyDamageProtection} needs a {@code ServerLevel}).
     * {@link CombatRules#getDamageAfterMagicAbsorb} clamps the total to 20.
     */
    static float blastProtectionTotal(final LivingEntity entity, final ClientLevel level) {
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> blast = registry.getOrThrow(Enchantments.BLAST_PROTECTION);
        Holder<Enchantment> protection = registry.getOrThrow(Enchantments.PROTECTION);
        int total = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var enchantments = stack.getEnchantments();
            total += enchantments.getLevel(blast) * 2 + enchantments.getLevel(protection);
        }
        return total;
    }

    // ------------------------------------------------------------------------------ evaluation

    /**
     * Upstream's {@code CrystalAuraDamageOptions.approximateExplosionDamage}, in the same rejection
     * order: minimum enemy damage, anti-suicide, maximum self damage, friend damage, then the
     * damage-balance ("Efficient") rule. Returns null when the spot must be rejected, otherwise the
     * (self, enemy) pair used for scoring. There is no faceplace rule upstream, so none is added.
     *
     * <p>Prediction: with {@link CalcMode#PREDICT_ONLY} only the predicted values are used; with
     * {@link CalcMode#BOTH} and {@link Logic#AND} both the current and predicted values must pass
     * every gate, with {@link Logic#OR} either one passing is enough. Anti-suicide always uses the
     * worse of the two self-damages (upstream's {@code isAnyGreaterThanOrEqual} is OR in both
     * {@code BiDamageProvider} classes). The returned pair always carries the predicted values when
     * prediction is on (upstream's {@code getFixed()}).</p>
     */
    public static @Nullable DamagePair evaluate(final Vec3 crystalPos, final LivingEntity target,
                                                final LocalPlayer self, final ClientLevel level,
                                                final List<? extends LivingEntity> friends,
                                                final DamageOptions options) {
        float targetCurrent = damageToEntity(crystalPos, target, level, options.terrainAware());
        float selfCurrent = damageToEntity(crystalPos, self, level, options.terrainAware());
        float targetFixed = targetCurrent;
        float selfFixed = selfCurrent;
        if (options.predictTarget() || options.predictSelf()) {
            float targetPredicted = targetCurrent;
            float selfPredicted = selfCurrent;
            if (options.predictTarget() && target instanceof Player) {
                AABB predicted = predictedBox(target.getBoundingBox(),
                        predictPosition(target.position(), target.getDeltaMovement(), options.targetTicks()));
                targetPredicted = damageToEntity(crystalPos, target, predicted, level, options.terrainAware());
            }
            if (options.predictSelf()) {
                AABB predicted = predictedBox(self.getBoundingBox(),
                        predictPosition(self.position(), self.getDeltaMovement(), options.selfTicks()));
                selfPredicted = damageToEntity(crystalPos, self, predicted, level, options.terrainAware());
            }
            targetFixed = targetPredicted;
            selfFixed = selfPredicted;
            if (options.calcMode() == CalcMode.BOTH) {
                boolean currentPasses = passesGates(selfCurrent, targetCurrent, self, options);
                boolean predictedPasses = passesGates(selfPredicted, targetPredicted, self, options);
                boolean passes = options.logic() == Logic.AND
                        ? currentPasses && predictedPasses : currentPasses || predictedPasses;
                if (!passes) {
                    return null;
                }
            } else if (!passesGates(selfPredicted, targetPredicted, self, options)) {
                return null;
            }
        } else if (!passesGates(selfFixed, targetFixed, self, options)) {
            return null;
        }
        if (options.maxFriendDamage() < 10.0F) {
            for (LivingEntity friend : friends) {
                if (!(friend instanceof Player) || !friend.isAlive()) {
                    continue;
                }
                if (friend.getBoundingBox().maxY <= crystalPos.y
                        || friend.getBoundingBox().distanceToSqr(crystalPos) > EXPLOSION_RANGE * EXPLOSION_RANGE) {
                    continue;
                }
                if (damageToEntity(crystalPos, friend, level, options.terrainAware()) > options.maxFriendDamage()) {
                    return null;
                }
            }
        }
        return new DamagePair(selfFixed, targetFixed);
    }

    /**
     * The gate set of one (self, enemy) damage pair, in upstream's order. Pure floats, so it is
     * unit-testable without a game. Anti-suicide compares against health + absorption.
     */
    public static boolean passesGates(final float selfDamage, final float enemyDamage, final float selfHealth,
                                      final float selfAbsorption, final DamageOptions options) {
        if (enemyDamage < options.minEnemyDamage()) {
            return false;
        }
        if (options.antiSuicide() && selfDamage >= selfHealth + selfAbsorption) {
            return false;
        }
        if (selfDamage > options.maxSelfDamage()) {
            return false;
        }
        if (options.efficient() && enemyDamage <= selfDamage) {
            return false;
        }
        return true;
    }

    private static boolean passesGates(final float selfDamage, final float enemyDamage, final LocalPlayer self,
                                       final DamageOptions options) {
        return passesGates(selfDamage, enemyDamage, self.getHealth(), self.getAbsorptionAmount(), options);
    }

    // --------------------------------------------------------------------------- pure helpers

    /** Linear extrapolation: where an entity will be after {@code ticks} at its current velocity. */
    public static Vec3 predictPosition(final Vec3 position, final Vec3 deltaMovement, final int ticks) {
        return position.add(deltaMovement.scale(ticks));
    }

    /** The entity's box moved so its feet sit at {@code predictedPosition}. */
    public static AABB predictedBox(final AABB box, final Vec3 predictedPosition) {
        double halfWidth = (box.maxX - box.minX) / 2.0;
        double height = box.maxY - box.minY;
        double halfDepth = (box.maxZ - box.minZ) / 2.0;
        return new AABB(predictedPosition.x - halfWidth, predictedPosition.y,
                predictedPosition.z - halfDepth, predictedPosition.x + halfWidth,
                predictedPosition.y + height, predictedPosition.z + halfDepth);
    }

    /**
     * Placement candidate order (upstream's {@code PlacementPositionCandidate} compare): higher
     * enemy damage wins, then lower self damage, then closer to the player.
     */
    public static int compareCandidates(final float enemyA, final float selfA, final double distanceSqA,
                                        final float enemyB, final float selfB, final double distanceSqB) {
        int byEnemy = Float.compare(enemyB, enemyA);
        if (byEnemy != 0) {
            return byEnemy;
        }
        int bySelf = Float.compare(selfA, selfB);
        if (bySelf != 0) {
            return bySelf;
        }
        return Double.compare(distanceSqA, distanceSqB);
    }

    /** Whether a predicted box would block a crystal spot (upstream's {@code willBeBlocked}). */
    public static boolean willBeBlocked(final AABB crystalBox, final @Nullable AABB predictedBox) {
        return predictedBox != null && crystalBox.intersects(predictedBox);
    }

    /** A crystal's explosion centre for a base block: the centre of its top face. */
    public static Vec3 crystalCenter(final BlockPos base) {
        return new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    /** The 1x2 space a crystal occupies above a base block. */
    public static AABB crystalBox(final BlockPos base) {
        return new AABB(base.getX(), base.getY() + 1.0, base.getZ(),
                base.getX() + 1.0, base.getY() + 3.0, base.getZ() + 1.0);
    }
}
