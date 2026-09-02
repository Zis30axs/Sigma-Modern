package net.minecraft.world.item;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

public class LeadItem extends Item {
    public LeadItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        InteractionResult result = InteractionResult.PASS;
        if (state.is(BlockTags.FENCES)) {
            Player player = context.getPlayer();
            if (!level.isClientSide() && player != null) {
                result = bindPlayerMobs(player, level, pos);
            }
        }

        // MODIFIED for porting: was VFP item/interaction MixinLeadItem#swingHand (@Inject RETURN, cancellable) - RETURN
        // with no ordinal covers both vanilla exits, so they are folded into one result local here.
        // <= 1.21 swings the hand for any lead use against a fence, including the client-side PASS.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21) && state.is(BlockTags.FENCES)) {
            return InteractionResult.SUCCESS;
        }

        return result;
    }

    public static InteractionResult bindPlayerMobs(final Player player, final Level level, final BlockPos pos) {
        List<Leashable> entitiesToLeash = Leashable.leashableInArea(level, Vec3.atCenterOf(pos), l -> l.getLeashHolder() == player);
        if (entitiesToLeash.isEmpty()) {
            return InteractionResult.PASS;
        }

        Optional<LeashFenceKnotEntity> existingKnot = LeashFenceKnotEntity.getKnot(level, pos);
        LeashFenceKnotEntity activeKnot = existingKnot.orElseGet(() -> LeashFenceKnotEntity.createKnot(level, pos));
        boolean anyLeashed = false;

        for (Leashable leashable : entitiesToLeash) {
            if (leashable.canHaveALeashAttachedTo(activeKnot)) {
                leashable.setLeashedTo(activeKnot, true);
                anyLeashed = true;
            }
        }

        if (anyLeashed) {
            activeKnot.playPlacementSound();
            level.gameEvent(GameEvent.BLOCK_ATTACH, pos, GameEvent.Context.of(player));
            return InteractionResult.SUCCESS_SERVER;
        }

        if (existingKnot.isEmpty()) {
            activeKnot.discard();
        }

        return InteractionResult.PASS;
    }
}