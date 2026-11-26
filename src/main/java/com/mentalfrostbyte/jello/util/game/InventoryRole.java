package com.mentalfrostbyte.jello.util.game;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/** Slot choices and version-aware item evaluation expressed against the 26.2 item components and tags. */
public enum InventoryRole {
    NONE("Unassigned", "structure_void"), LOCK("Locked", "barrier"),
    HELMET("Helmet", "diamond_helmet"), CHESTPLATE("Chestplate", "diamond_chestplate"),
    LEGGINGS("Leggings", "diamond_leggings"), BOOTS("Boots", "diamond_boots"),
    WEAPON("Weapon", "diamond_sword"), SWORD("Sword", "iron_sword"), SPEAR("Spear", "iron_spear"),
    MACE("Mace", "mace"), KNOCKBACK("Knockback", "stick"), BOW("Bow", "bow"), CROSSBOW("Crossbow", "crossbow"),
    PICKAXE("Pickaxe", "diamond_pickaxe"), AXE("Axe", "diamond_axe"), SHOVEL("Shovel", "iron_shovel"),
    HOE("Hoe", "iron_hoe"), ROD("Fishing rod", "fishing_rod"), SHIELD("Shield", "shield"),
    WATER("Water bucket", "water_bucket"), LAVA("Lava bucket", "lava_bucket"), MILK("Milk bucket", "milk_bucket"),
    PEARL("Ender pearls", "ender_pearl"), GAPPLE("Golden apples", "golden_apple"), FOOD("Food", "bread"),
    POTION("Potions", "potion"), BLOCK("Blocks", "stone"), THROWABLE("Eggs and snowballs", "snowball"),
    ARROW("Arrows", "arrow"), TOTEM("Totems", "totem_of_undying");

    public static final List<InventoryRole> ARMOR_ROLES = List.of(HELMET, CHESTPLATE, LEGGINGS, BOOTS);
    private static final double MODERN_REFERENCE_HIT = 10.0;

    public final String label, icon;
    InventoryRole(String label, String icon) { this.label = label; this.icon = "minecraft:" + icon; }

    private static final Map<String, InventoryRole> RULES = new HashMap<>();
    static { for (var role : values()) RULES.put(role.name(), role); }

    public static @Nullable InventoryRole byKey(String key) { return RULES.get(key); }

    public static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

    public static boolean isLegacyCombat() {
        return ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8);
    }

    public static int enchantmentLevel(ItemStack s, ResourceKey<Enchantment> key) {
        for (var entry : s.getEnchantments().entrySet()) {
            if (entry.getKey().is(key)) return entry.getIntValue();
        }
        return 0;
    }

    public static boolean hasBindingCurse(ItemStack s) {
        return !s.isEmpty() && (enchantmentLevel(s, Enchantments.BINDING_CURSE) > 0
                || EnchantmentHelper.has(s, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE));
    }

    public static @Nullable InventoryRole armorRole(ItemStack s) {
        if (s.isEmpty() || s.has(DataComponents.GLIDER)) return null;
        for (var role : ARMOR_ROLES) {
            if (role.matches(s)) return role;
        }
        return null;
    }

    public int armorMenuSlot() {
        return switch (this) {
            case HELMET -> 5;
            case CHESTPLATE -> 6;
            case LEGGINGS -> 7;
            case BOOTS -> 8;
            default -> -1;
        };
    }

    public @Nullable EquipmentSlot armorEquipSlot() {
        return switch (this) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHESTPLATE -> EquipmentSlot.CHEST;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private double ironBaselineArmor() {
        return switch (this) {
            case HELMET -> 2.0;
            case CHESTPLATE -> 6.0;
            case LEGGINGS -> 5.0;
            case BOOTS -> 2.0;
            default -> 0.0;
        };
    }

    public boolean isMiningToolRole() {
        return this == PICKAXE || this == AXE || this == SHOVEL || this == HOE;
    }

    private static boolean isArmorForSlot(ItemStack s, TagKey<Item> tag, EquipmentSlot slot) {
        if (s.has(DataComponents.GLIDER)) return false;
        if (s.is(tag)) return true;
        var equip = s.get(DataComponents.EQUIPPABLE);
        return equip != null && equip.slot() == slot
                && s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                        .compute(Attributes.ARMOR, 0.0, slot) > 0.0;
    }

    private static boolean isSword(ItemStack s) {
        return s.is(ItemTags.SWORDS) || id(s).endsWith("_sword");
    }

    private static boolean isPickaxe(ItemStack s) {
        return s.is(ItemTags.PICKAXES) || id(s).endsWith("_pickaxe");
    }

    private static boolean isSpear(ItemStack s) {
        return s.is(ItemTags.SPEARS) || s.has(DataComponents.KINETIC_WEAPON);
    }

    private static boolean isAxe(ItemStack s) {
        return s.is(ItemTags.AXES) || s.getItem() instanceof AxeItem;
    }

    private static boolean isShovel(ItemStack s) {
        return s.is(ItemTags.SHOVELS) || s.getItem() instanceof ShovelItem;
    }

    private static boolean isHoe(ItemStack s) {
        return s.is(ItemTags.HOES) || s.getItem() instanceof HoeItem;
    }

    public boolean matches(ItemStack s) {
        if (s.isEmpty()) return false;
        return switch (this) {
            case NONE, LOCK -> false;
            case HELMET -> isArmorForSlot(s, ItemTags.HEAD_ARMOR, EquipmentSlot.HEAD);
            case CHESTPLATE -> isArmorForSlot(s, ItemTags.CHEST_ARMOR, EquipmentSlot.CHEST);
            case LEGGINGS -> isArmorForSlot(s, ItemTags.LEG_ARMOR, EquipmentSlot.LEGS);
            case BOOTS -> isArmorForSlot(s, ItemTags.FOOT_ARMOR, EquipmentSlot.FEET);
            case WEAPON -> isSword(s) || isAxe(s) || isSpear(s) || s.is(Items.MACE) || s.is(Items.TRIDENT);
            case SWORD -> isSword(s);
            case SPEAR -> isSpear(s);
            case MACE -> s.is(Items.MACE);
            case KNOCKBACK -> enchantmentLevel(s, Enchantments.KNOCKBACK) > 0;
            case AXE -> isAxe(s);
            case PICKAXE -> isPickaxe(s);
            case SHOVEL -> isShovel(s);
            case HOE -> isHoe(s);
            case BOW -> s.getItem() instanceof BowItem;
            case CROSSBOW -> s.getItem() instanceof CrossbowItem;
            case ROD -> s.getItem() instanceof FishingRodItem;
            case SHIELD -> s.getItem() instanceof ShieldItem;
            case WATER -> s.is(Items.WATER_BUCKET);
            case LAVA -> s.is(Items.LAVA_BUCKET);
            case MILK -> s.is(Items.MILK_BUCKET);
            case PEARL -> s.is(Items.ENDER_PEARL);
            case GAPPLE -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE);
            case FOOD -> s.has(DataComponents.FOOD);
            case POTION -> s.getItem() instanceof PotionItem;
            case BLOCK -> s.getItem() instanceof BlockItem;
            case THROWABLE -> s.getItem() instanceof EggItem || s.getItem() instanceof SnowballItem;
            case ARROW -> s.getItem() instanceof ArrowItem;
            case TOTEM -> s.is(Items.TOTEM_OF_UNDYING);
        };
    }

    /** A negative result means ineligible. Prefer the present slot on equal scores. */
    public static double score(String rule, ItemStack s) {
        return score(rule, s, null, isLegacyCombat());
    }

    public static double score(String rule, ItemStack s, @Nullable List<ItemStack> menuSlots, boolean legacyCombat) {
        if (s.isEmpty()) return -1;
        var role = byKey(rule);
        if (role == null) {
            if (!id(s).equals(rule) || hasBindingCurse(s)) return -1;
            var gear = primaryGearRole(s);
            return gear != null ? scoreRole(gear, s, menuSlots, legacyCombat) : s.getCount() + durabilityBonus(s, false);
        }
        if (!role.matches(s) || hasBindingCurse(s)) return -1;
        return scoreRole(role, s, menuSlots, legacyCombat);
    }

    public static double armorScore(InventoryRole role, ItemStack s, @Nullable List<ItemStack> menuSlots, boolean legacyCombat) {
        if (!role.matches(s) || hasBindingCurse(s)) return -1;
        return scoreArmorPiece(role, s, menuSlots, legacyCombat);
    }

    private static @Nullable InventoryRole primaryGearRole(ItemStack s) {
        var armor = armorRole(s);
        if (armor != null) return armor;
        if (isSword(s)) return SWORD;
        if (isPickaxe(s)) return PICKAXE;
        if (isAxe(s)) return AXE;
        if (isShovel(s)) return SHOVEL;
        if (isHoe(s)) return HOE;
        if (isSpear(s)) return SPEAR;
        if (s.is(Items.MACE)) return MACE;
        if (s.is(Items.TRIDENT)) return WEAPON;
        if (s.getItem() instanceof BowItem) return BOW;
        if (s.getItem() instanceof CrossbowItem) return CROSSBOW;
        if (s.getItem() instanceof FishingRodItem) return ROD;
        if (s.getItem() instanceof ShieldItem) return SHIELD;
        return null;
    }

    private static double scoreRole(InventoryRole role, ItemStack s, @Nullable List<ItemStack> menuSlots, boolean legacyCombat) {
        return switch (role) {
            case HELMET, CHESTPLATE, LEGGINGS, BOOTS -> scoreArmorPiece(role, s, menuSlots, legacyCombat);
            case WEAPON, SWORD, SPEAR -> scoreWeapon(s, legacyCombat);
            case MACE -> scoreMace(s, legacyCombat);
            case AXE, PICKAXE, HOE, SHOVEL -> scoreTool(role, s);
            case BOW -> scoreBow(s);
            case CROSSBOW -> scoreCrossbow(s);
            case ROD -> scoreRod(s);
            case SHIELD -> scoreShield(s);
            case KNOCKBACK -> enchantmentLevel(s, Enchantments.KNOCKBACK) * 2.0
                    + enchantmentLevel(s, Enchantments.FIRE_ASPECT) * 0.5 + durabilityBonus(s, false);
            case FOOD -> s.get(DataComponents.FOOD).nutrition() * s.getCount();
            case GAPPLE -> (s.is(Items.ENCHANTED_GOLDEN_APPLE) ? 100 : 0) + s.getCount();
            default -> s.getCount() + durabilityBonus(s, false);
        };
    }

    /**
     * Evaluates an armor piece's Effective HP (EHP) in a 4-piece suit context rather than naked isolation.
     *
     * <p>In {@code <= 1.8}, armor reduces damage by {@code 4%} per point without toughness, and Protection uses
     * the nonlinear {@code floor((6 + lvl^2) * 0.75 / 3)} EPF curve with {@code 0.75} expected roll.
     * In {@code >= 1.9}, {@code CombatRules} applies hit-damage armor penetration resisted by
     * {@code ARMOR_TOUGHNESS}, followed by linear {@code 4%} per Protection level. Other slots use the better of
     * currently equipped protective armor and an Iron baseline so early-game empty slots never let armor
     * penetration wipe out a single piece's base defense and toughness.</p>
     */
    private static double scoreArmorPiece(InventoryRole role, ItemStack s, @Nullable List<ItemStack> menuSlots, boolean legacyCombat) {
        EquipmentSlot slot = role.armorEquipSlot();
        var modifiers = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double armor = modifiers.compute(Attributes.ARMOR, 0.0, slot);
        double toughness = legacyCombat ? 0.0 : modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
        double kbRes = legacyCombat ? 0.0 : modifiers.compute(Attributes.KNOCKBACK_RESISTANCE, 0.0, slot);

        double otherArmor = 0.0;
        double otherToughness = 0.0;
        for (var other : ARMOR_ROLES) {
            if (other == role) continue;
            double slotArmor = other.ironBaselineArmor();
            double slotToughness = 0.0;
            int menuIdx = other.armorMenuSlot();
            if (menuSlots != null && menuIdx >= 0 && menuIdx < menuSlots.size()) {
                ItemStack worn = menuSlots.get(menuIdx);
                if (other.matches(worn) && !hasBindingCurse(worn)) {
                    EquipmentSlot otherSlot = other.armorEquipSlot();
                    var wornMods = worn.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                    slotArmor = Math.max(slotArmor, wornMods.compute(Attributes.ARMOR, 0.0, otherSlot));
                    if (!legacyCombat) {
                        slotToughness = Math.max(slotToughness, wornMods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, otherSlot));
                    }
                }
            }
            otherArmor += slotArmor;
            otherToughness += slotToughness;
        }

        int prot = enchantmentLevel(s, Enchantments.PROTECTION);
        double damageMultiplier;
        if (legacyCombat) {
            double totalArmor = Math.clamp(otherArmor + armor, 0.0, 20.0);
            double armorMult = 1.0 - totalArmor * 0.04;
            double epf = prot > 0 ? Math.floor((6.0 + (double) prot * prot) * 0.75 / 3.0) : 0.0;
            double protMult = 1.0 - Math.min(20.0, Math.min(25.0, epf) * 0.75) * 0.04;
            damageMultiplier = armorMult * protMult;
        } else {
            double totalArmor = otherArmor + armor;
            double totalToughness = otherToughness + toughness;
            double denom = 2.0 + totalToughness / 4.0;
            double realArmor = Math.clamp(totalArmor - MODERN_REFERENCE_HIT / denom, totalArmor * 0.2, 20.0);
            double armorMult = 1.0 - realArmor / 25.0;
            double protMult = 1.0 - Math.clamp(prot, 0.0, 20.0) / 25.0;
            damageMultiplier = armorMult * protMult;
        }

        double ehp = 20.0 / Math.max(0.05, damageMultiplier);
        double secondary = kbRes * 8.0
                + enchantmentLevel(s, Enchantments.BLAST_PROTECTION) * 0.35
                + enchantmentLevel(s, Enchantments.PROJECTILE_PROTECTION) * 0.30
                + enchantmentLevel(s, Enchantments.FIRE_PROTECTION) * 0.25
                + enchantmentLevel(s, Enchantments.FEATHER_FALLING) * 0.35
                + enchantmentLevel(s, Enchantments.DEPTH_STRIDER) * 0.25
                + enchantmentLevel(s, Enchantments.FROST_WALKER) * 0.10
                + enchantmentLevel(s, Enchantments.SWIFT_SNEAK) * 0.20
                + enchantmentLevel(s, Enchantments.RESPIRATION) * 0.15
                + enchantmentLevel(s, Enchantments.AQUA_AFFINITY) * 0.15
                + enchantmentLevel(s, Enchantments.SOUL_SPEED) * 0.15
                + enchantmentLevel(s, Enchantments.THORNS) * 0.15;
        if (id(s).startsWith("minecraft:iron_")) secondary += 0.002;
        return ehp + secondary + durabilityBonus(s, true);
    }

    private static double scoreWeapon(ItemStack s, boolean legacyCombat) {
        var modifiers = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        int sharp = enchantmentLevel(s, Enchantments.SHARPNESS);
        double primary;
        if (legacyCombat) {
            double baseDamage = legacyWeaponBaseDamage(s, modifiers);
            primary = baseDamage + sharp * 1.25;
        } else {
            double baseDamage = modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
            double sharpBonus = sharp > 0 ? 0.5 * sharp + 0.5 : 0.0;
            double speed = Math.max(0.25, modifiers.compute(
                    Attributes.ATTACK_SPEED, Attributes.DEFAULT_ATTACK_SPEED, EquipmentSlot.MAINHAND));
            primary = (baseDamage + sharpBonus) * speed;
        }
        double utility = enchantmentLevel(s, Enchantments.FIRE_ASPECT) * 1.25
                + enchantmentLevel(s, Enchantments.KNOCKBACK) * 0.35
                + (legacyCombat ? 0.0 : enchantmentLevel(s, Enchantments.SWEEPING_EDGE) * 0.45)
                + enchantmentLevel(s, Enchantments.SMITE) * 0.15
                + enchantmentLevel(s, Enchantments.BANE_OF_ARTHROPODS) * 0.10
                + enchantmentLevel(s, Enchantments.LOOTING) * 0.10
                + enchantmentLevel(s, Enchantments.LUNGE) * 0.50
                + enchantmentLevel(s, Enchantments.IMPALING) * 0.40
                + enchantmentLevel(s, Enchantments.LOYALTY) * 0.50
                + enchantmentLevel(s, Enchantments.RIPTIDE) * 0.50
                + enchantmentLevel(s, Enchantments.CHANNELING) * 0.25
                + enchantmentLevel(s, Enchantments.DENSITY) * 1.20
                + enchantmentLevel(s, Enchantments.BREACH) * 1.50
                + enchantmentLevel(s, Enchantments.WIND_BURST) * 1.00;
        return primary + utility + durabilityBonus(s, false);
    }

    private static double scoreMace(ItemStack s, boolean legacyCombat) {
        var modifiers = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double baseDamage = modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
        int sharp = enchantmentLevel(s, Enchantments.SHARPNESS);
        double sharpBonus = sharp > 0 ? (legacyCombat ? sharp * 1.25 : 0.5 * sharp + 0.5) : 0.0;
        return baseDamage + sharpBonus
                + enchantmentLevel(s, Enchantments.BREACH) * 3.0
                + enchantmentLevel(s, Enchantments.DENSITY) * 2.5
                + enchantmentLevel(s, Enchantments.WIND_BURST) * 2.0
                + enchantmentLevel(s, Enchantments.FIRE_ASPECT) * 1.25
                + durabilityBonus(s, false);
    }

    private static double legacyWeaponBaseDamage(ItemStack s, ItemAttributeModifiers modifiers) {
        if (s.hasNonDefault(DataComponents.ATTRIBUTE_MODIFIERS)) {
            return modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
        }
        if (isSword(s)) {
            return modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND) + 1.0;
        }
        if (isAxe(s)) {
            int max = s.getMaxDamage();
            if (max >= 2000) return 8.0;
            if (max >= 1000) return 7.0;
            if (max >= 220) return 6.0;
            if (max >= 100) return 5.0;
            return 4.0;
        }
        return modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
    }

    private static double scoreTool(InventoryRole role, ItemStack s) {
        var tool = s.get(DataComponents.TOOL);
        double rawSpeed = 1.0;
        if (tool != null) {
            rawSpeed = tool.rules().stream()
                    .filter(r -> r.correctForDrops().orElse(false))
                    .mapToDouble(r -> r.speed().orElse(1F)).max()
                    .orElseGet(() -> tool.rules().stream().mapToDouble(r -> r.speed().orElse(1F)).max().orElse(1.0));
        }
        boolean fragileGold = s.isDamageableItem() && s.getMaxDamage() <= 40;
        double effectiveBaseSpeed = fragileGold ? rawSpeed * 0.25 : rawSpeed;
        double tier = toolTierBonus(role == PICKAXE, s, tool);
        int eff = enchantmentLevel(s, Enchantments.EFFICIENCY);
        double effBonus = eff > 0 ? (eff * eff + 1) * 0.6 : 0.0;
        double utility = enchantmentLevel(s, Enchantments.FORTUNE) * 1.5
                + enchantmentLevel(s, Enchantments.SILK_TOUCH) * 1.5;
        return tier + effectiveBaseSpeed + effBonus + utility + durabilityBonus(s, false);
    }

    private static double toolTierBonus(boolean pickaxe, ItemStack s, @Nullable Tool tool) {
        int max = s.getMaxDamage();
        if (pickaxe && tool != null && tool.rules().stream().anyMatch(r -> r.blocks().size() > 0)) {
            if (tool.isCorrectForDrops(Blocks.OBSIDIAN.defaultBlockState())) return max >= 2000 ? 32.0 : 30.0;
            if (tool.isCorrectForDrops(Blocks.DIAMOND_ORE.defaultBlockState())) return 12.0;
            if (tool.isCorrectForDrops(Blocks.IRON_ORE.defaultBlockState())) return max >= 170 ? 6.0 : 5.0;
            return max <= 40 ? 1.0 : 0.0;
        }
        if (max >= 2000) return pickaxe ? 32.0 : 20.0;
        if (max >= 1000) return pickaxe ? 30.0 : 18.0;
        if (max >= 220) return pickaxe ? 12.0 : 10.0;
        if (max >= 170) return 6.0;
        if (max >= 100) return pickaxe ? 5.0 : 4.0;
        if (max <= 40) return 1.0;
        return 0.0;
    }

    private static double scoreBow(ItemStack s) {
        int power = enchantmentLevel(s, Enchantments.POWER);
        double powerScore = power > 0 ? (0.5 + 0.25 * power) * 4.0 : 0.0;
        return 1.0 + powerScore
                + enchantmentLevel(s, Enchantments.FLAME) * 1.8
                + enchantmentLevel(s, Enchantments.INFINITY) * 1.5
                + enchantmentLevel(s, Enchantments.PUNCH) * 0.8
                + durabilityBonus(s, false);
    }

    private static double scoreCrossbow(ItemStack s) {
        return 1.0 + enchantmentLevel(s, Enchantments.QUICK_CHARGE) * 1.5
                + enchantmentLevel(s, Enchantments.MULTISHOT) * 2.0
                + enchantmentLevel(s, Enchantments.PIERCING) * 0.6
                + durabilityBonus(s, false);
    }

    private static double scoreRod(ItemStack s) {
        return 1.0 + enchantmentLevel(s, Enchantments.KNOCKBACK) * 1.5
                + enchantmentLevel(s, Enchantments.LUCK_OF_THE_SEA) * 0.4
                + enchantmentLevel(s, Enchantments.LURE) * 0.4
                + durabilityBonus(s, false);
    }

    private static double scoreShield(ItemStack s) {
        return 1.0 + durabilityBonus(s, false);
    }

    private static double durabilityBonus(ItemStack s, boolean armor) {
        if (!s.isDamageableItem() || s.getMaxDamage() <= 0) return 0.0;
        int remaining = s.getMaxDamage() - s.getDamageValue();
        double ratio = Math.clamp((double) remaining / s.getMaxDamage(), 0.0, 1.0);
        int unbreaking = enchantmentLevel(s, Enchantments.UNBREAKING);
        int mending = enchantmentLevel(s, Enchantments.MENDING);
        double unbreakingBonus = unbreaking * (armor ? 0.12 : 0.20);
        double mendingBonus = mending > 0 ? (armor ? 0.30 : 0.50) : 0.0;
        double capacityBonus = Math.min(0.15, s.getMaxDamage() / 15000.0);
        double cursePenalty = enchantmentLevel(s, Enchantments.VANISHING_CURSE) > 0 ? 0.15 : 0.0;
        double fragilePenalty = remaining <= 3 && mending == 0 ? (armor ? 1.5 : 2.5) : 0.0;
        return 0.05 * ratio + capacityBonus + unbreakingBonus + mendingBonus - cursePenalty - fragilePenalty;
    }
}
