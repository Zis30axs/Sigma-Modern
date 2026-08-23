package net.minecraft.world.entity.ai.sensing;

import com.google.common.collect.ImmutableSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class TemptingSensor extends Sensor<PathfinderMob> {
    private static final TargetingConditions TEMPT_TARGETING = TargetingConditions.forNonCombat().ignoreLineOfSight();
    private final BiPredicate<PathfinderMob, ItemStack> temptations;

    public TemptingSensor(final Predicate<ItemStack> tt) {
        this((m, i) -> tt.test(i));
    }

    public static TemptingSensor forAnimal() {
        return new TemptingSensor((m, i) -> m instanceof Animal animal ? animal.isFood(i) : false);
    }

    private TemptingSensor(final BiPredicate<PathfinderMob, ItemStack> temptations) {
        this.temptations = temptations;
    }

    protected void doTick(final ServerLevel level, final PathfinderMob body) {
        // MODIFIED for porting: lithium ai.sensor.replace_streams.tempting TemptingSensorMixin#doTick (@Overwrite) -
        // the stream pipeline (including the full sort) is replaced by a single pass that keeps the closest match.
        Brain<?> brain = body.getBrain();
        TargetingConditions targeting = TEMPT_TARGETING.copy().range((float)body.getAttributeValue(Attributes.TEMPT_RANGE));
        Player closestPlayer = null;
        double minDist = Double.MAX_VALUE;

        for (net.minecraft.server.level.ServerPlayer serverPlayer : level.players()) {
            if (EntitySelector.NO_SPECTATORS.test(serverPlayer)
                && targeting.test(level, body, serverPlayer)
                && this.playerHoldingTemptation(body, serverPlayer)
                && !body.hasPassenger(serverPlayer)) {
                double dist = body.distanceToSqr(serverPlayer);
                if (dist < minDist) {
                    minDist = dist;
                    closestPlayer = serverPlayer;
                }
            }
        }

        if (closestPlayer != null) {
            brain.setMemory(MemoryModuleType.TEMPTING_PLAYER, closestPlayer);
        } else {
            brain.eraseMemory(MemoryModuleType.TEMPTING_PLAYER);
        }
    }

    private boolean playerHoldingTemptation(final PathfinderMob mob, final Player player) {
        return this.isTemptation(mob, player.getMainHandItem()) || this.isTemptation(mob, player.getOffhandItem());
    }

    private boolean isTemptation(final PathfinderMob mob, final ItemStack itemStack) {
        return this.temptations.test(mob, itemStack);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(MemoryModuleType.TEMPTING_PLAYER);
    }
}