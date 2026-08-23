package net.caffeinemc.mods.sodium.client.services.vanilla;

import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import java.util.ArrayList;
import java.util.List;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.helper.ListStorage;
import net.caffeinemc.mods.sodium.client.services.PlatformModelAccess;
import net.caffeinemc.mods.sodium.client.services.SodiumModelData;
import net.caffeinemc.mods.sodium.client.services.SodiumModelDataContainer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * MODIFIED for porting: replaces the loader specific {@code FabricModelAccess} / {@code NeoForgeModelAccess}. Identical to
 * the Fabric implementation except for {@code createMutableColorProvider}, which returns null: that provider only exists to
 * expose a loader's dynamic per-block-state tint factories (Fabric's {@code BlockColorRegistry}, NeoForge's dynamic color
 * provider). Vanilla block tints all come from {@code BlockColors}, which
 * {@link net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry} already reads, and
 * {@link net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer} treats a null provider as
 * "no platform fallback".
 */
public class VanillaModelAccess implements PlatformModelAccess {
    private static final SodiumModelDataContainer EMPTY_CONTAINER = new SodiumModelDataContainer(Long2ObjectMaps.emptyMap());

    @Override
    public List<BakedQuad> getQuads(
        final BlockAndTintGetter level,
        final BlockPos pos,
        final BlockStateModelPart model,
        final BlockState state,
        final Direction face,
        final RandomSource random
    ) {
        return model.getQuads(face);
    }

    @Override
    public SodiumModelDataContainer getModelDataContainer(final Level level, final SectionPos sectionPos) {
        return EMPTY_CONTAINER;
    }

    @Override
    public SodiumModelData getEmptyModelData() {
        return null;
    }

    @Override
    public List<BlockStateModelPart> collectPartsOf(
        final BlockStateModel blockStateModel,
        final BlockAndTintGetter blockView,
        final BlockPos pos,
        final BlockState state,
        final RandomSource random,
        final @Nullable ListStorage emitter
    ) {
        List<BlockStateModelPart> parts = emitter == null ? new ArrayList<>() : emitter.clearAndGet();
        blockStateModel.collectParts(random, parts);
        return parts;
    }

    @Override
    public @Nullable ColorProvider<BlockState> createMutableColorProvider() {
        return null;
    }
}
