package com.mentalfrostbyte.jello.util.game;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jspecify.annotations.Nullable;

/** LiquidBounce slot choices expressed against the 26.2 item components and tags. */
public enum InventoryRole {
    NONE("Unassigned", "structure_void"), LOCK("Locked", "barrier"), WEAPON("Weapon", "diamond_sword"),
    SWORD("Sword", "iron_sword"), SPEAR("Spear", "iron_spear"), MACE("Mace", "mace"),
    KNOCKBACK("Knockback", "stick"), BOW("Bow", "bow"), CROSSBOW("Crossbow", "crossbow"),
    PICKAXE("Pickaxe", "diamond_pickaxe"), AXE("Axe", "diamond_axe"), SHOVEL("Shovel", "iron_shovel"),
    HOE("Hoe", "iron_hoe"), ROD("Fishing rod", "fishing_rod"), SHIELD("Shield", "shield"),
    WATER("Water bucket", "water_bucket"), LAVA("Lava bucket", "lava_bucket"), MILK("Milk bucket", "milk_bucket"),
    PEARL("Ender pearls", "ender_pearl"), GAPPLE("Golden apples", "golden_apple"), FOOD("Food", "bread"),
    POTION("Potions", "potion"), BLOCK("Blocks", "stone"), THROWABLE("Eggs and snowballs", "snowball"),
    ARROW("Arrows", "arrow"), TOTEM("Totems", "totem_of_undying");

    public final String label, icon;
    InventoryRole(String label, String icon) { this.label = label; this.icon = "minecraft:" + icon; }
    private static final Map<String, InventoryRole> RULES = new HashMap<>();
    static { for (var role : values()) RULES.put(role.name(), role); }
    public static @Nullable InventoryRole byKey(String key) { return RULES.get(key); }

    public static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

    private static int level(ItemStack s, ResourceKey<Enchantment> key) {
        for (var entry : s.getEnchantments().entrySet()) {
            if (entry.getKey().is(key)) return entry.getIntValue();
        }
        return 0;
    }

    public boolean matches(ItemStack s) {
        if (s.isEmpty()) return false;
        return switch (this) {
            case NONE, LOCK -> false;
            case WEAPON -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(ItemTags.SPEARS)
                    || s.is(Items.MACE) || s.is(Items.TRIDENT);
            case SWORD -> s.is(ItemTags.SWORDS);
            case SPEAR -> s.is(ItemTags.SPEARS);
            case MACE -> s.is(Items.MACE);
            case KNOCKBACK -> level(s, Enchantments.KNOCKBACK) > 0;
            case AXE -> s.is(ItemTags.AXES);
            case PICKAXE -> s.is(ItemTags.PICKAXES);
            case SHOVEL -> s.is(ItemTags.SHOVELS);
            case HOE -> s.is(ItemTags.HOES);
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
        if (s.isEmpty()) return -1;
        var role = byKey(rule);
        if (role == null) return id(s).equals(rule) ? s.getCount() + durability(s) : -1;
        if (!role.matches(s)) return -1;
        double value = switch (role) {
            case WEAPON, SWORD, SPEAR, MACE -> s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND) + level(s, Enchantments.SHARPNESS) * .6;
            case AXE, PICKAXE, HOE, SHOVEL -> {
                var tool = s.get(DataComponents.TOOL);
                double speed = tool == null ? 1 : tool.rules().stream().mapToDouble(r -> r.speed().orElse(1F)).max().orElse(1);
                yield speed + level(s, Enchantments.EFFICIENCY);
            }
            case BOW -> 1 + level(s, Enchantments.POWER);
            case CROSSBOW -> 1 + level(s, Enchantments.QUICK_CHARGE);
            case KNOCKBACK -> level(s, Enchantments.KNOCKBACK);
            case FOOD -> s.get(DataComponents.FOOD).nutrition() * s.getCount();
            case GAPPLE -> (s.is(Items.ENCHANTED_GOLDEN_APPLE) ? 100 : 0) + s.getCount();
            default -> s.getCount();
        };
        return value + durability(s);
    }

    private static double durability(ItemStack s) {
        return s.isDamageableItem() ? .01 * (1.0 - (double) s.getDamageValue() / s.getMaxDamage()) : 0;
    }
}
