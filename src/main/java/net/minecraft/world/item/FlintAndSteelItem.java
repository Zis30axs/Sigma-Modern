package net.minecraft.world.item;

import com.viaversion.viafabricplus.settings.impl.DebugSettings;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;

public class FlintAndSteelItem extends Item {
    public FlintAndSteelItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!CampfireBlock.canLight(state) && !CandleBlock.canLight(state) && !CandleCakeBlock.canLight(state)) {
            BlockPos relativePos = pos.relative(context.getClickedFace());
            if (BaseFireBlock.canBePlacedAt(level, relativePos, context.getHorizontalDirection())) {
                // MODIFIED for porting: was VFP world/duplicated_sounds MixinItems#disableItemPlaceSounds
                // (@WrapWithCondition on Level#playSound). 1.8 and older send the use sound from the server, so
                // playing it client-side too would double it. The @At carries no ordinal, so both playSound calls in
                // this method are wrapped. The pitch is still rolled when the sound is skipped, because
                // @WrapWithCondition evaluates the wrapped call's arguments before testing the condition.
                final float pitch = level.getRandom().nextFloat() * 0.4F + 0.8F;
                if (!DebugSettings.INSTANCE.serversidePlaceSounds.isEnabled()) {
                    level.playSound(player, relativePos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, pitch);
                }

                BlockState fireState = BaseFireBlock.getState(level, relativePos);
                level.setBlock(relativePos, fireState, 11);
                level.gameEvent(player, GameEvent.BLOCK_PLACE, pos);
                ItemStack itemStack = context.getItemInHand();
                if (player instanceof ServerPlayer serverPlayer) {
                    CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, relativePos, itemStack);
                    itemStack.hurtAndBreak(1, player, context.getHand().asEquipmentSlot());
                }

                return InteractionResult.SUCCESS;
            } else {
                return InteractionResult.FAIL;
            }
        } else {
            // MODIFIED for porting: was VFP world/duplicated_sounds MixinItems#disableItemPlaceSounds
            // (@WrapWithCondition on Level#playSound) - the second wrapped call site of this method, lighting a
            // campfire, candle or candle cake.
            final float pitch = level.getRandom().nextFloat() * 0.4F + 0.8F;
            if (!DebugSettings.INSTANCE.serversidePlaceSounds.isEnabled()) {
                level.playSound(player, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, pitch);
            }

            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), 11);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            if (player != null) {
                context.getItemInHand().hurtAndBreak(1, player, context.getHand().asEquipmentSlot());
            }

            return InteractionResult.SUCCESS;
        }
    }
}