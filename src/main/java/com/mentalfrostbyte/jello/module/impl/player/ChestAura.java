package com.mentalfrostbyte.jello.module.impl.player;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Optional ChestSteal opener. Track each double chest once and wait for its actual menu before moving on. */
final class ChestAura {
    private final Set<BlockPos> visited = new HashSet<>();
    private @Nullable BlockPos pending;
    private int wait, attempts;

    void reset() { visited.clear(); pending = null; wait = attempts = 0; }

    void opened(Minecraft mc) {
        BlockPos pos = pending;
        if (pos == null && mc.hitResult instanceof BlockHitResult hit) pos = hit.getBlockPos();
        if (pos != null) remember(mc, pos);
        pending = null;
        attempts = 0;
    }

    private void remember(Minecraft mc, BlockPos pos) {
        visited.add(pos.immutable());
        var state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            visited.add(pos.relative(ChestBlock.getConnectedDirection(state)));
    }

    void tick(Minecraft mc, double range, boolean shulkers) {
        var player = mc.player;
        if (player.isUsingItem() || player.isShiftKeyDown() || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) return;
        if (wait > 0) { wait--; return; }
        if (pending != null && attempts >= 3) { remember(mc, pending); pending = null; attempts = 0; }
        Vec3 eye = player.getEyePosition();
        range = Math.min(range, player.blockInteractionRange());
        int r = (int) Math.ceil(range);
        double nearest = range * range;
        BlockHitResult best = null;
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(eye).offset(-r, -r, -r), BlockPos.containing(eye).offset(r, r, r))) {
            Vec3 point = Vec3.atCenterOf(pos);
            double distance = eye.distanceToSqr(point);
            if (distance > nearest || visited.contains(pos) || !mc.level.hasChunkAt(pos)) continue;
            var state = mc.level.getBlockState(pos);
            var block = state.getBlock();
            if (!(block instanceof AbstractChestBlock<?> || block instanceof BarrelBlock || shulkers && block instanceof ShulkerBoxBlock)) continue;
            if (block instanceof ChestBlock && (ChestBlock.isChestBlockedAt(mc.level, pos)
                    || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && ChestBlock.isChestBlockedAt(mc.level, pos.relative(ChestBlock.getConnectedDirection(state))))) continue;
            var hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                nearest = distance;
                best = hit;
            }
        }
        wait = 5;
        if (best == null) { pending = null; attempts = 0; return; }
        if (!best.getBlockPos().equals(pending)) attempts = 0;
        pending = best.getBlockPos().immutable();
        attempts++;
        var result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, best);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT)
            player.swing(InteractionHand.MAIN_HAND);
        wait = 10;
    }
}
