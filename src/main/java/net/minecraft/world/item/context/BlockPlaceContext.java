package net.minecraft.world.item.context;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.protocoltranslator.impl.ViaFabricPlusMappingDataLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class BlockPlaceContext extends UseOnContext {
    private final BlockPos relativePos;
    protected boolean replaceClicked = true;

    public BlockPlaceContext(final Player player, final InteractionHand hand, final ItemStack itemInHand, final BlockHitResult hitResult) {
        this(player.level(), player, hand, itemInHand, hitResult);
    }

    public BlockPlaceContext(final UseOnContext context) {
        this(context.getLevel(), context.getPlayer(), context.getHand(), context.getItemInHand(), context.getHitResult());
    }

    protected BlockPlaceContext(
        final Level level, final @Nullable Player player, final InteractionHand hand, final ItemStack itemStackInHand, final BlockHitResult hitResult
    ) {
        super(level, player, hand, itemStackInHand, hitResult);
        this.relativePos = hitResult.getBlockPos().relative(hitResult.getDirection());
        this.replaceClicked = level.getBlockState(hitResult.getBlockPos()).canBeReplaced(this);
    }

    public static BlockPlaceContext at(final BlockPlaceContext context, final BlockPos pos, final Direction direction) {
        return new BlockPlaceContext(
            context.getLevel(),
            context.getPlayer(),
            context.getHand(),
            context.getItemInHand(),
            new BlockHitResult(
                new Vec3(
                    pos.getX() + 0.5 + direction.getStepX() * 0.5, pos.getY() + 0.5 + direction.getStepY() * 0.5, pos.getZ() + 0.5 + direction.getStepZ() * 0.5
                ),
                direction,
                pos,
                false
            )
        );
    }

    @Override
    public BlockPos getClickedPos() {
        return this.replaceClicked ? super.getClickedPos() : this.relativePos;
    }

    public boolean canPlace() {
        final boolean canPlace = this.replaceClicked || this.getLevel().getBlockState(this.getClickedPos()).canBeReplaced(this);
        // MODIFIED for porting: was VFP interaction/replace_block_item_use_logic
        // MixinBlockPlaceContext#canPlace1_12_2 (@Inject RETURN, cancellable)
        // <=1.12.2 allowed an anvil to be placed into a "decoration" material block, which modern canBeReplaced
        // rejects. Only reached when vanilla already said no, exactly as upstream.
        if (!canPlace && ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return ViaFabricPlusMappingDataLoader.getBlockMaterial(this.getLevel().getBlockState(this.getClickedPos()).getBlock()).equals("decoration")
                && Block.byItem(this.getItemInHand().getItem()).equals(Blocks.ANVIL);
        }

        return canPlace;
    }

    public boolean replacingClickedOnBlock() {
        return this.replaceClicked;
    }

    public Direction getNearestLookingDirection() {
        // MODIFIED for porting: was VFP interaction/replace_block_item_use_logic
        // MixinBlockPlaceContext#getPlayerLookDirection1_12_2 (@Inject HEAD, cancellable)
        // <=1.12.2 picked the placement facing from the player's own position/eye height rather than from the
        // look vector; the 0.5 block-centre offset only exists from 1.11 on.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            final Player player = this.getPlayer();
            final BlockPos placementPos = this.getClickedPos();
            final double blockPosCenterFactor = ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_10) ? 0.5 : 0;

            if (Math.abs(player.getX() - (placementPos.getX() + blockPosCenterFactor)) < 2
                && Math.abs(player.getZ() - (placementPos.getZ() + blockPosCenterFactor)) < 2) {
                final double eyeY = player.getY() + player.getEyeHeight(player.getPose());

                if (eyeY - placementPos.getY() > 2) {
                    return Direction.DOWN;
                }

                if (placementPos.getY() - eyeY > 0) {
                    return Direction.UP;
                }
            }

            return player.getDirection();
        }

        return Direction.orderedByNearest(this.getPlayer())[0];
    }

    public Direction getNearestLookingVerticalDirection() {
        return Direction.getFacingAxis(this.getPlayer(), Direction.Axis.Y);
    }

    public Direction[] getNearestLookingDirections() {
        Direction[] directions = Direction.orderedByNearest(this.getPlayer());
        if (this.replaceClicked) {
            return directions;
        }

        Direction clickedFace = this.getClickedFace();
        int index = 0;

        while (index < directions.length && directions[index] != clickedFace.getOpposite()) {
            index++;
        }

        if (index > 0) {
            System.arraycopy(directions, 0, directions, 1, index);
            directions[0] = clickedFace.getOpposite();
        }

        return directions;
    }
}