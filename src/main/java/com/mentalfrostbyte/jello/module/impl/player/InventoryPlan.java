package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.jello.util.game.InventoryRole;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** A single action from a fresh player-menu snapshot. Never address crafting or armor slots. */
final class InventoryPlan {
    enum Kind { SWAP, MERGE, DROP }
    record Move(Kind kind, int from, int to) { }
    record Policy(Map<Integer, String> layout, Set<String> keep, Set<String> trash, Map<InventoryRole, Integer> limits) {
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
        int end = Math.min(slots.size(), 46);
        boolean[] reserved = new boolean[end];
        var targets = policy.layout().keySet().stream().filter(i -> i >= 9 && i < end)
                .sorted(Comparator.<Integer>comparingInt(i -> priority(policy.layout().get(i)))
                        .thenComparingInt(i -> i >= 36 ? i - 36 : i + 1)).toList();
        for (int i : targets) reserved[i] = "LOCK".equals(policy.layout().get(i));
        for (int target : targets) {
            if (reserved[target]) continue;
            String rule = policy.layout().get(target);
            ItemStack present = slots.get(target);
            int best = target;
            double highest = policy.rejected(present) ? -1 : InventoryRole.score(rule, present);
            for (int i = 9; i < end; i++) {
                ItemStack candidate = slots.get(i);
                if (i == target || reserved[i] || candidate.isEmpty() || policy.rejected(candidate)) continue;
                // Identical stacks can be consolidated after assignment. Trading two of them repeatedly
                // would starve other targets and pickup would merge them instead of exchanging them.
                if (highest >= 0 && ItemStack.isSameItemSameComponents(present, candidate)) continue;
                double score = InventoryRole.score(rule, candidate);
                if (score > highest && (!needsSwapBuffer(i, target, candidate, present) || bufferHotbar(policy.layout()) >= 0)) {
                    highest = score;
                    best = i;
                }
            }
            if (best != target) return new Move(Kind.SWAP, best, target);
            if (highest >= 0) reserved[target] = true;
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
                if (discardSpareGear && stack.isDamageableItem() && !stack.has(DataComponents.CUSTOM_NAME)) {
                    for (int target : targets) {
                        if (!reserved[target]) continue;
                        String rule = policy.layout().get(target);
                        double score = InventoryRole.score(rule, stack);
                        if (score >= 0 && score < InventoryRole.score(rule, slots.get(target)))
                            return new Move(Kind.DROP, i, -1);
                    }
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
}
