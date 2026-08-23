package net.minecraft.world.level.block.entity;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.Optionull;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SculkCatalystBlock;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

// MODIFIED for porting: lithium world.block_entity_ticking.sleeping.sculk_catalyst SculkCatalystBlockEntityMixin
public class SculkCatalystBlockEntity extends BlockEntity
    implements GameEventListener.Provider<SculkCatalystBlockEntity.CatalystListener>, net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity {
    // MODIFIED for porting: lithium sculk_catalyst SculkCatalystBlockEntityMixin
    private net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor lithium$tickWrapper = null;
    private net.minecraft.world.level.block.entity.TickingBlockEntity lithium$sleepingTicker = null;

    @Override
    public net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor lithium$getTickWrapper() {
        return this.lithium$tickWrapper;
    }

    @Override
    public void lithium$setTickWrapper(final net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor tickWrapper) {
        this.lithium$tickWrapper = tickWrapper;
        this.lithium$setSleepingTicker(null);
    }

    @Override
    public net.minecraft.world.level.block.entity.TickingBlockEntity lithium$getSleepingTicker() {
        return this.lithium$sleepingTicker;
    }

    @Override
    public void lithium$setSleepingTicker(final net.minecraft.world.level.block.entity.TickingBlockEntity sleepingTicker) {
        this.lithium$sleepingTicker = sleepingTicker;
    }

    private final SculkCatalystBlockEntity.CatalystListener catalystListener;

    public SculkCatalystBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        super(BlockEntityTypes.SCULK_CATALYST, worldPosition, blockState);
        this.catalystListener = new SculkCatalystBlockEntity.CatalystListener(blockState, new BlockPositionSource(worldPosition));
        // MODIFIED for porting: lithium sculk_catalyst SculkCatalystBlockEntityMixin#addCatalystListenerCallback
        ((net.caffeinemc.mods.lithium.common.block.entity.sleeping_sculk.GameEventListenerWithCallback)this.catalystListener.getSculkSpreader()).lithium$setGameEventCallback(this::wakeUpNow);
    }

    public static void serverTick(final Level level, final BlockPos pos, final BlockState state, final SculkCatalystBlockEntity entity) {
        entity.catalystListener.getSculkSpreader().updateCursors(level, pos, level.getRandom(), true);
        // MODIFIED for porting: lithium sculk_catalyst SculkCatalystBlockEntityMixin#sleepIfNoCursors (RETURN)
        if (entity.getListener().getSculkSpreader().getCursors().isEmpty()) {
            entity.lithium$startSleeping();
        }
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.catalystListener.sculkSpreader.load(input);
            // MODIFIED for porting: lithium sculk_catalyst SculkCatalystBlockEntityMixin#wakeupIfLoadedWithData
        // (RETURN) - detects modification by commands
        if (!this.getListener().getSculkSpreader().getCursors().isEmpty()) {
            this.wakeUpNow();
        }
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        this.catalystListener.sculkSpreader.save(output);
        super.saveAdditional(output);
    }

    public SculkCatalystBlockEntity.CatalystListener getListener() {
        return this.catalystListener;
    }

    public static class CatalystListener implements GameEventListener {
        public static final int PULSE_TICKS = 8;
        private final SculkSpreader sculkSpreader;
        private final BlockState blockState;
        private final PositionSource positionSource;

        public CatalystListener(final BlockState blockState, final PositionSource positionSource) {
            this.blockState = blockState;
            this.positionSource = positionSource;
            this.sculkSpreader = SculkSpreader.createLevelSpreader();
        }

        @Override
        public PositionSource getListenerSource() {
            return this.positionSource;
        }

        @Override
        public int getListenerRadius() {
            return 8;
        }

        @Override
        public GameEventListener.DeliveryMode getDeliveryMode() {
            return GameEventListener.DeliveryMode.BY_DISTANCE;
        }

        @Override
        public boolean handleGameEvent(final ServerLevel level, final Holder<GameEvent> event, final GameEvent.Context context, final Vec3 sourcePosition) {
            if (event.is(GameEvent.ENTITY_DIE) && context.sourceEntity() instanceof LivingEntity mob) {
                if (!mob.wasExperienceConsumed()) {
                    DamageSource lastDamageSource = mob.getLastDamageSource();
                    int experienceWouldDrop = mob.getExperienceReward(level, Optionull.map(lastDamageSource, DamageSource::getEntity));
                    if (mob.shouldDropExperience() && experienceWouldDrop > 0) {
                        this.sculkSpreader.addCursors(BlockPos.containing(sourcePosition.relative(Direction.UP, 0.5)), experienceWouldDrop);
                        this.tryAwardItSpreadsAdvancement(level, mob);
                    }

                    mob.skipDropExperience();
                    this.positionSource.getPosition(level).ifPresent(vec3 -> this.bloom(level, BlockPos.containing(vec3), this.blockState, level.getRandom()));
                }

                return true;
            } else {
                return false;
            }
        }

        @VisibleForTesting
        public SculkSpreader getSculkSpreader() {
            return this.sculkSpreader;
        }

        private void bloom(final ServerLevel level, final BlockPos pos, final BlockState state, final RandomSource random) {
            level.setBlock(pos, state.setValue(SculkCatalystBlock.PULSE, true), 3);
            level.scheduleTick(pos, state.getBlock(), 8);
            level.sendParticles(ParticleTypes.SCULK_SOUL, pos.getX() + 0.5, pos.getY() + 1.15, pos.getZ() + 0.5, 2, 0.2, 0.0, 0.2, 0.0);
            level.playSound(null, pos, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 2.0F, 0.6F + random.nextFloat() * 0.4F);
        }

        private void tryAwardItSpreadsAdvancement(final Level level, final LivingEntity mob) {
            if (mob.getLastHurtByMob() instanceof ServerPlayer player) {
                DamageSource damageSource = mob.getLastDamageSource() == null ? level.damageSources().playerAttack(player) : mob.getLastDamageSource();
                CriteriaTriggers.KILL_MOB_NEAR_SCULK_CATALYST.trigger(player, mob, damageSource);
            }
        }
    }
}