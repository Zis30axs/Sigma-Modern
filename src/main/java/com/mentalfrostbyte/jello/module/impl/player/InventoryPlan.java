package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jspecify.annotations.Nullable;

/** A single action from a fresh player-menu snapshot. Never address crafting grid slots (0..4). */
final class InventoryPlan {
    enum Kind { SWAP, MERGE, DROP }
    record Move(Kind kind, int from, int to) { }

    record Policy(Map<Integer, String> layout, Set<String> keep, Set<String> trash,
                  Map<InventoryRole, Integer> limits, boolean legacyCombat, boolean discardSpareGear) {
        Policy(Map<Integer, String> layout, Set<String> keep, Set<String> trash, Map<InventoryRole, Integer> limits) {
            this(layout, keep, trash, limits, InventoryRole.isLegacyCombat(), false);
        }

        boolean rejected(ItemStack stack) {
            if (trash.isEmpty() || stack.isEmpty()) return false;
            String id = InventoryRole.id(stack);
            return trash.contains(id) && !keep.contains(id);
        }

        boolean protectedItem(ItemStack stack) {
            return !keep.isEmpty() && keep.contains(InventoryRole.id(stack));
        }

        boolean allowsLoot(ItemStack stack, List<ItemStack> inventory) {
            if (rejected(stack)) return false;
            if (protectedItem(stack)) return true;
            var armorRole = InventoryRole.armorRole(stack);
            if (armorRole != null && InventoryRole.hasBindingCurse(stack)) return false;
            if (discardSpareGear && stack.isDamageableItem() && !stack.has(DataComponents.CUSTOM_NAME) && armorRole != null) {
                int needed = 1;
                String itemId = InventoryRole.id(stack);
                for (String rule : layout.values()) {
                    if (armorRole.name().equals(rule) || itemId.equals(rule)) needed++;
                }
                double candidateScore = InventoryRole.armorScore(armorRole, stack, null, legacyCombat);
                int betterOrEqual = 0;
                for (var other : inventory) {
                    if (other.isEmpty() || rejected(other) || !armorRole.matches(other) || InventoryRole.hasBindingCurse(other)) continue;
                    if (InventoryRole.armorScore(armorRole, other, null, legacyCombat) >= candidateScore) betterOrEqual++;
                }
                if (betterOrEqual >= needed) return false;
            }
            for (var limit : limits.entrySet()) {
                if (!limit.getKey().matches(stack)) continue;
                int owned = 0;
                for (var other : inventory) owned += amount(limit.getKey(), other);
                if (owned >= limit.getValue()) return false;
            }
            return true;
        }
    }

    static int amount(InventoryRole role, ItemStack s) {
        if (!role.matches(s)) return 0;
        return role == InventoryRole.FOOD ? s.get(DataComponents.FOOD).nutrition() * s.getCount() : s.getCount();
    }

    private static int priority(String rule) {
        InventoryRole role = InventoryRole.byKey(rule);
        if (role == null) return 0;
        return role == InventoryRole.WEAPON || role == InventoryRole.FOOD || role == InventoryRole.BLOCK ? 2 : 1;
    }

    static int bufferHotbar(Map<Integer, String> rules) {
        for (int i = 36; i <= 44; i++) if (!"LOCK".equals(rules.get(i))) return i;
        return -1;
    }

    // Bundles intercept a normal left click on another occupied slot and pack its contents.
    static boolean needsSwapBuffer(int from, int to, ItemStack a, ItemStack b) {
        return !(from >= 36 && from <= 44 || to >= 36 && to <= 44)
                && !a.isEmpty() && !b.isEmpty() && (a.has(DataComponents.BUNDLE_CONTENTS) || b.has(DataComponents.BUNDLE_CONTENTS));
    }

    static @Nullable Move next(List<ItemStack> slots, Policy policy, boolean merge, boolean clean, boolean discardSpareGear) {
        return next(slots, policy, merge, clean, discardSpareGear, true);
    }

    static @Nullable Move next(List<ItemStack> slots, Policy policy, boolean merge, boolean clean,
                               boolean discardSpareGear, boolean autoArmor) {
        return plan(slots, policy, true, merge, clean, discardSpareGear, autoArmor);
    }

    static int disposableSlot(List<ItemStack> slots, Policy policy, boolean discardSpareGear, boolean autoArmor) {
        for (int i = 9; i < Math.min(slots.size(), 45); i++) {
            if (!"LOCK".equals(policy.layout().get(i)) && policy.rejected(slots.get(i))) return i;
        }
        var planned = plan(slots, policy, false, false, true, discardSpareGear, autoArmor);
        return planned != null && planned.kind() == Kind.DROP && planned.from() < 45 ? planned.from() : -1;
    }

    private static @Nullable Move plan(List<ItemStack> slots, Policy policy, boolean sort, boolean merge,
                                       boolean clean, boolean discardSpareGear, boolean autoArmor) {
        int end = Math.min(slots.size(), 46);
        boolean[] reserved = new boolean[end];
        var targets = policy.layout().keySet().stream().filter(i -> i >= 9 && i < end)
                .sorted(Comparator.<Integer>comparingInt(i -> priority(policy.layout().get(i)))
                        .thenComparingInt(i -> i >= 36 ? i - 36 : i + 1)).toList();
        for (int i : targets) reserved[i] = "LOCK".equals(policy.layout().get(i));

        if (autoArmor && end >= 9) {
            for (var role : InventoryRole.ARMOR_ROLES) {
                int armorSlot = role.armorMenuSlot();
                ItemStack worn = slots.get(armorSlot);
                // Preserve non-armor wearables (such as Elytra or Carved Pumpkin) and immovable cursed armor.
                if (!worn.isEmpty() && (!role.matches(worn) || InventoryRole.hasBindingCurse(worn))) continue;
                int best = armorSlot;
                double highest = worn.isEmpty() || policy.rejected(worn)
                        ? -1 : InventoryRole.armorScore(role, worn, slots, policy.legacyCombat());
                for (int i = 9; i < end; i++) {
                    ItemStack candidate = slots.get(i);
                    if (reserved[i] || candidate.isEmpty() || policy.rejected(candidate)
                            || InventoryRole.hasBindingCurse(candidate)) continue;
                    double score = InventoryRole.armorScore(role, candidate, slots, policy.legacyCombat());
                    if (score > highest) {
                        highest = score;
                        best = i;
                    }
                }
                if (best != armorSlot) {
                    if (sort) return new Move(Kind.SWAP, best, armorSlot);
                    reserved[best] = true;
                }
            }
        }

        for (int target : targets) {
            if (reserved[target]) continue;
            String rule = policy.layout().get(target);
            ItemStack present = slots.get(target);
            int best = target;
            double highest = policy.rejected(present) ? -1 : InventoryRole.score(rule, present, slots, policy.legacyCombat());
            for (int i = 9; i < end; i++) {
                ItemStack candidate = slots.get(i);
                if (i == target || reserved[i] || candidate.isEmpty() || policy.rejected(candidate)) continue;
                // Identical stacks can be consolidated after assignment. Trading two of them repeatedly
                // would starve other targets and pickup would merge them instead of exchanging them.
                if (highest >= 0 && ItemStack.isSameItemSameComponents(present, candidate)) continue;
                double score = InventoryRole.score(rule, candidate, slots, policy.legacyCombat());
                if (score > highest && (!needsSwapBuffer(i, target, candidate, present) || bufferHotbar(policy.layout()) >= 0)) {
                    highest = score;
                    best = i;
                }
            }
            if (best != target) {
                if (sort) return new Move(Kind.SWAP, best, target);
                reserved[best] = true;
            } else if (highest >= 0) {
                reserved[target] = true;
            }
        }

        if (merge) {
            for (int source = 9; source < end; source++) {
                ItemStack stack = slots.get(source);
                if (reserved[source] || stack.isEmpty() || !stack.isStackable()) continue;
                for (int target = 9; target < end; target++) {
                    ItemStack other = slots.get(target);
                    if (target == source || "LOCK".equals(policy.layout().get(target))
                            || other.isEmpty() || other.getCount() >= other.getMaxStackSize()) continue;
                    // Unassigned stacks only merge toward earlier slots, so partial leftovers stay put.
                    if ((reserved[target] || target < source) && ItemStack.isSameItemSameComponents(stack, other))
                        return new Move(Kind.MERGE, source, target);
                }
            }
        }

        if (clean) {
            for (int i = 9; i < end; i++) {
                var stack = slots.get(i);
                if (reserved[i] || stack.isEmpty() || policy.protectedItem(stack)) continue;
                if (policy.rejected(stack)) return new Move(Kind.DROP, i, -1);
                if (discardSpareGear && isRedundantSpareGear(slots, reserved, targets, policy, i, end)) {
                    return new Move(Kind.DROP, i, -1);
                }
                for (var limit : policy.limits().entrySet()) {
                    if (!limit.getKey().matches(stack)) continue;
                    int remaining = 0;
                    for (int j = 9; j < end; j++) {
                        if (j != i) remaining += amount(limit.getKey(), slots.get(j));
                    }
                    if (remaining >= limit.getValue()) return new Move(Kind.DROP, i, -1);
                }
            }
        }
        return null;
    }

    private static boolean isRedundantSpareGear(List<ItemStack> slots, boolean[] reserved, List<Integer> targets,
                                                Policy policy, int index, int end) {
        ItemStack stack = slots.get(index);
        if (!stack.isDamageableItem() || stack.has(DataComponents.CUSTOM_NAME)) return false;

        var armorRole = InventoryRole.armorRole(stack);
        if (armorRole != null) {
            if (InventoryRole.hasBindingCurse(stack)) return true;
            double myScore = InventoryRole.armorScore(armorRole, stack, slots, policy.legacyCombat());
            int armorSlot = armorRole.armorMenuSlot();
            if (armorSlot >= 0 && armorSlot < slots.size()) {
                ItemStack worn = slots.get(armorSlot);
                if (armorRole.matches(worn) && !policy.rejected(worn) && !InventoryRole.hasBindingCurse(worn)
                        && InventoryRole.armorScore(armorRole, worn, slots, policy.legacyCombat()) >= myScore) {
                    return true;
                }
            }
            for (int j = 9; j < end; j++) {
                if (j == index) continue;
                ItemStack other = slots.get(j);
                if (other.isEmpty() || policy.rejected(other) || !armorRole.matches(other)
                        || InventoryRole.hasBindingCurse(other)) continue;
                double otherScore = InventoryRole.armorScore(armorRole, other, slots, policy.legacyCombat());
                if (otherScore > myScore || (otherScore == myScore && (reserved[j] || j < index))) return true;
            }
            return false;
        }

        for (int target : targets) {
            String rule = policy.layout().get(target);
            if ("LOCK".equals(rule)) continue;
            double myScore = InventoryRole.score(rule, stack, slots, policy.legacyCombat());
            if (myScore < 0) continue;
            if (preservesSpecialistTool(slots, reserved, policy, index, rule, stack, myScore, end)) continue;

            int needed = 0;
            for (int t : targets) {
                if (rule.equals(policy.layout().get(t))) needed++;
            }
            int dominating = 0;
            for (int j = 9; j < end; j++) {
                if (j == index) continue;
                ItemStack other = slots.get(j);
                if (other.isEmpty() || policy.rejected(other)) continue;
                double otherScore = InventoryRole.score(rule, other, slots, policy.legacyCombat());
                if (otherScore > myScore || (otherScore == myScore && (reserved[j] || j < index))) {
                    dominating++;
                }
            }
            if (dominating >= needed) return true;
        }
        return false;
    }

    private static boolean preservesSpecialistTool(List<ItemStack> slots, boolean[] reserved, Policy policy,
                                                   int index, String rule, ItemStack stack, double myScore, int end) {
        var role = InventoryRole.byKey(rule);
        boolean miningTool = role != null ? role.isMiningToolRole()
                : (InventoryRole.PICKAXE.matches(stack) || InventoryRole.AXE.matches(stack)
                || InventoryRole.SHOVEL.matches(stack) || InventoryRole.HOE.matches(stack));
        if (!miningTool) return false;
        if (InventoryRole.enchantmentLevel(stack, Enchantments.SILK_TOUCH) > 0
                && !hasDominatingEnchantedTool(slots, reserved, policy, index, rule, Enchantments.SILK_TOUCH, myScore, end)) {
            return true;
        }
        return InventoryRole.enchantmentLevel(stack, Enchantments.FORTUNE) > 0
                && !hasDominatingEnchantedTool(slots, reserved, policy, index, rule, Enchantments.FORTUNE, myScore, end);
    }

    private static boolean hasDominatingEnchantedTool(List<ItemStack> slots, boolean[] reserved, Policy policy,
                                                      int index, String rule, ResourceKey<Enchantment> enchant,
                                                      double myScore, int end) {
        for (int j = 9; j < end; j++) {
            if (j == index) continue;
            ItemStack other = slots.get(j);
            if (other.isEmpty() || policy.rejected(other) || InventoryRole.enchantmentLevel(other, enchant) <= 0) continue;
            double otherScore = InventoryRole.score(rule, other, slots, policy.legacyCombat());
            if (otherScore > myScore || (otherScore == myScore && (reserved[j] || j < index))) return true;
        }
        return false;
    }
}
