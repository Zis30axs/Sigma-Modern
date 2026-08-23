package net.caffeinemc.mods.sodium.client.services;

import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;
import java.util.function.Predicate;

public interface PlatformModelEmitter {
    // MODIFIED for porting: the ServiceLoader indirection is gone - this project has no mod loader, so the vanilla
    // implementation is referenced directly.
    PlatformModelEmitter INSTANCE = new DefaultModelEmitter();

    static PlatformModelEmitter getInstance() {
        return INSTANCE;
    }

    void emitModel(BlockStateModel model, Predicate<Direction> cullTest, MutableQuadViewImpl quad, RandomSource random, BlockAndTintGetter blockView, BlockPos pos, BlockState state, Bufferer defaultBuffer);

    @FunctionalInterface
    interface Bufferer {
        void emit(BlockStateModelPart part, Predicate<Direction> cullTest, Consumer<MutableQuadViewImpl> emitter);
    }
}
