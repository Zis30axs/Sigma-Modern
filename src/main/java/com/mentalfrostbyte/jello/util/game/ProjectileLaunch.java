package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.AddValue;
import net.minecraft.world.item.enchantment.effects.MultiplyValue;
import net.minecraft.world.item.enchantment.effects.SetValue;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Read-only, deterministic launch geometry for the currently held projectile item. */
public final class ProjectileLaunch {
    private static final int MAX_PATHS = 3;
    private static final int MAX_TICKS = 160;

    public enum Physics { ARROW, THROWABLE, WIND_CHARGE, FIREWORK }

    /** Physics describes the vanilla tick order; velocity excludes random launch inaccuracy. */
    public record Shot(Vec3 origin, Vec3 velocity, Physics physics, double gravity,
                       double airDrag, double waterDrag, float width, float height,
                       int maxTicks, boolean preview) { }

    /** The deterministic center shot of a candidate hotbar item, without selecting or using it. */
    public record Template(float speed, float pitchOffset, Physics physics, double gravity,
                           double airDrag, double waterDrag, double originY, float width,
                           float height, int maxTicks, boolean inheritsMovement) {
        public Shot launch(Vec3 eye, Vec3 inherited, float yaw, float pitch) {
            Vec3 velocity = inheritsMovement
                    ? initialVelocity(yaw, pitch, pitchOffset, speed, inherited)
                    : crossbowVelocity(yaw, pitch, 0, speed);
            return new Shot(eye.add(0, originY, 0), velocity, physics, gravity,
                    airDrag, waterDrag, width, height, maxTicks, false);
        }
    }

    private ProjectileLaunch() { }

    public static List<Shot> held(LocalPlayer player, float partialTicks, float yaw, float pitch) {
        if (player == null || !player.isAlive() || player.isSpectator()
                || !Float.isFinite(partialTicks) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return List.of();
        }
        Vec3 eye = player.getEyePosition(Mth.clamp(partialTicks, 0F, 1F));
        Vec3 movement = player.getKnownMovement();
        if (!finite(eye) || !finite(movement)) return List.of();
        Vec3 inherited = inheritedMovement(movement, player.onGround());
        // An active use takes precedence: do not preview an offhand throw while eating or shielding.
        if (player.isUsingItem()) {
            return fromHand(player, player.getUsedItemHand(), eye, inherited, yaw, pitch);
        }
        InteractionHand hand = supported(player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        return fromHand(player, hand, eye, inherited, yaw, pitch);
    }

    private static List<Shot> fromHand(LocalPlayer player, InteractionHand hand, Vec3 eye,
                                       Vec3 inherited, float yaw, float pitch) {
        ItemStack item = player.getItemInHand(hand);
        if (item.isEmpty()) return List.of();
        boolean using = player.isUsingItem() && player.getUsedItemHand() == hand;
        int useTicks = using ? player.getTicksUsingItem() : 0;
        if (item.getItem() instanceof BowItem && !player.hasInfiniteMaterials()
                && player.getProjectile(item).isEmpty()) return List.of();
        return fromItem(item, eye, inherited, yaw, pitch, using, useTicks);
    }

    /** Charge is the actual/planned held-use age, not the idle full-charge preview. Ammo is checked by the caller. */
    public static @Nullable Template template(ItemStack item, int useTicks) {
        if (item == null || item.isEmpty()) return null;
        List<Shot> shots = fromItem(item, Vec3.ZERO, Vec3.ZERO, 0, 0, true, Math.max(0, useTicks));
        if (shots.isEmpty()) return null;
        Shot center = shots.getFirst();
        // Vanilla multishot is odd-sized. Custom even spreads have no forward center projectile.
        if (Math.abs(center.velocity().x) > 1.0E-6 || center.velocity().z <= 0) return null;
        float pitchOffset = item.getItem() instanceof ThrowablePotionItem
                || item.getItem() instanceof ExperienceBottleItem ? -20F : 0F;
        return new Template((float) center.velocity().length(), pitchOffset, center.physics(),
                center.gravity(), center.airDrag(), center.waterDrag(), center.origin().y,
                center.width(), center.height(), center.maxTicks(), !(item.getItem() instanceof CrossbowItem));
    }

    private static List<Shot> fromItem(ItemStack item, Vec3 eye, Vec3 inherited,
                                       float yaw, float pitch, boolean using, int useTicks) {
        if (item.getItem() instanceof BowItem) {
            return bow(eye, inherited, yaw, pitch, using, useTicks);
        }
        if (item.getItem() instanceof CrossbowItem) return crossbow(item, eye, yaw, pitch);
        if (item.getItem() instanceof TridentItem) {
            // Inspect the effect without evaluating it: enchantment helpers may consume the player's RNG.
            boolean riptide = EnchantmentHelper.has(item, EnchantmentEffectComponents.TRIDENT_SPIN_ATTACK_STRENGTH);
            if (!canThrowTrident(using, useTicks, riptide, item.nextDamageWillBreak())) return List.of();
            return List.of(arrow(eye, initialVelocity(yaw, pitch, 0F, 2.5F, inherited), 0.99F, !using));
        }
        if (item.getItem() instanceof SnowballItem || item.getItem() instanceof EggItem
                || item.getItem() instanceof EnderpearlItem) {
            return List.of(throwable(eye, initialVelocity(yaw, pitch, 0F, 1.5F, inherited), 0.03));
        }
        if (item.getItem() instanceof ThrowablePotionItem) {
            return List.of(throwable(eye, initialVelocity(yaw, pitch, -20F, 0.5F, inherited), 0.05));
        }
        if (item.getItem() instanceof ExperienceBottleItem) {
            return List.of(throwable(eye, initialVelocity(yaw, pitch, -20F, 0.7F, inherited), 0.07));
        }
        if (item.getItem() instanceof WindChargeItem) {
            return List.of(new Shot(eye, initialVelocity(yaw, pitch, 0F, 1.5F, inherited),
                    Physics.WIND_CHARGE, 0, 1, 1, 0.3125F, 0.3125F, MAX_TICKS, false));
        }
        return List.of();
    }

    static List<Shot> bow(Vec3 eye, Vec3 inherited, float yaw, float pitch, boolean using, int useTicks) {
        float power = bowPower(using, useTicks);
        if (power < 0.1F) return List.of();
        return List.of(arrow(eye, initialVelocity(yaw, pitch, 0F, power * 3F, inherited), 0.6F, !using));
    }

    static float bowPower(boolean using, int useTicks) {
        if (!using) return 1F;
        float time = Math.max(0, useTicks) / 20F;
        return Math.min(1F, (time * time + 2F * time) / 3F);
    }

    static boolean canThrowTrident(boolean using, int useTicks, boolean riptide, boolean wouldBreak) {
        return !riptide && !wouldBreak && (!using || useTicks >= 10);
    }

    private static List<Shot> crossbow(ItemStack item, Vec3 eye, float yaw, float pitch) {
        ChargedProjectiles charged = item.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        if (charged.isEmpty()) return List.of();
        float spread = crossbowSpread(item);
        // Conditional or random custom spread cannot be determined in a client-side loot context.
        if (!Float.isFinite(spread)) return List.of();
        float speed = charged.contains(Items.FIREWORK_ROCKET) ? 1.6F : 3.15F;
        List<Shot> shots = new ArrayList<>(MAX_PATHS);
        int count = charged.items().size();
        for (int i = 0; i < Math.min(count, MAX_PATHS); i++) {
            var projectile = charged.items().get(i);
            Vec3 velocity = crossbowVelocity(yaw, pitch, spreadAngle(i, count, spread), speed);
            if (projectile.is(Items.FIREWORK_ROCKET)) {
                Fireworks fireworks = projectile.get(DataComponents.FIREWORKS);
                int lifetime = fireworkLifetime(fireworks == null ? 0 : fireworks.flightDuration());
                // Shot-at-angle rockets fly straight. Only their guaranteed lifetime is shown; the
                // additional 0..11 random lifetime ticks cannot be predicted before firing.
                shots.add(new Shot(eye.add(0, -0.15F, 0), velocity, Physics.FIREWORK,
                        0, 1, 1, 0.25F, 0.25F, lifetime, true));
            } else shots.add(arrow(eye, velocity, 0.6F, false));
        }
        return List.copyOf(shots);
    }

    private static float crossbowSpread(ItemStack item) {
        float spread = 0F;
        ItemEnchantments enchantments = item.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : enchantments.entrySet()) {
            for (var effect : entry.getKey().value().getEffects(EnchantmentEffectComponents.PROJECTILE_SPREAD)) {
                if (effect.requirements().isPresent()) return Float.NaN;
                int level = entry.getIntValue();
                if (effect.effect() instanceof AddValue add) spread += add.value().calculate(level);
                else if (effect.effect() instanceof MultiplyValue multiply) spread *= multiply.factor().calculate(level);
                else if (effect.effect() instanceof SetValue set) spread = set.value().calculate(level);
                else return Float.NaN;
            }
        }
        return Math.max(0F, spread);
    }

    static float spreadAngle(int index, int count, float maxAngle) {
        if (count <= 1) return 0F;
        float step = 2F * maxAngle / (count - 1);
        float offset = (count - 1) % 2 * step / 2F;
        return offset + ((index & 1) == 0 ? 1F : -1F) * ((index + 1) / 2) * step;
    }

    static Vec3 crossbowVelocity(float yaw, float pitch, float angle, float speed) {
        Vec3 up = direction(yaw, pitch - 90F, 0F);
        Quaternionf rotation = new Quaternionf().setAngleAxis(angle * Mth.DEG_TO_RAD, up.x, up.y, up.z);
        Vector3f shot = direction(yaw, pitch, 0F).toVector3f().rotate(rotation);
        return new Vec3(shot.x, shot.y, shot.z).normalize().scale(speed);
    }

    static Vec3 initialVelocity(float yaw, float pitch, float pitchOffset, float speed, Vec3 inherited) {
        return direction(yaw, pitch, pitchOffset).normalize().scale(speed).add(inherited);
    }

    private static Vec3 direction(float yaw, float pitch, float pitchOffset) {
        // Projectile.shootFromRotation offsets only Y for potions/XP, not the entire direction.
        float yawRadians = yaw * Mth.DEG_TO_RAD;
        float pitchRadians = pitch * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yawRadians) * Mth.cos(pitchRadians),
                -Mth.sin((pitch + pitchOffset) * Mth.DEG_TO_RAD),
                Mth.cos(yawRadians) * Mth.cos(pitchRadians));
    }

    public static Vec3 inheritedMovement(Vec3 movement, boolean onGround) {
        return new Vec3(movement.x, onGround ? 0 : movement.y, movement.z);
    }

    static int fireworkLifetime(int flightDuration) {
        return Math.min(MAX_TICKS, 10 * (1 + Mth.clamp(flightDuration, 0, 255)));
    }

    private static Shot arrow(Vec3 eye, Vec3 velocity, float waterDrag, boolean preview) {
        return new Shot(eye.add(0, -0.1F, 0), velocity, Physics.ARROW,
                0.05, 0.99F, waterDrag, 0.5F, 0.5F, MAX_TICKS, preview);
    }

    private static Shot throwable(Vec3 eye, Vec3 velocity, double gravity) {
        return new Shot(eye.add(0, -0.1F, 0), velocity, Physics.THROWABLE,
                gravity, 0.99F, 0.8F, 0.25F, 0.25F, MAX_TICKS, false);
    }

    private static boolean supported(ItemStack item) {
        return !item.isEmpty() && (item.getItem() instanceof BowItem || item.getItem() instanceof CrossbowItem
                || item.getItem() instanceof TridentItem || item.getItem() instanceof SnowballItem
                || item.getItem() instanceof EggItem || item.getItem() instanceof EnderpearlItem
                || item.getItem() instanceof ThrowablePotionItem || item.getItem() instanceof ExperienceBottleItem
                || item.getItem() instanceof WindChargeItem);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
