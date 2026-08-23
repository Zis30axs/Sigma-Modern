package net.minecraft.client.color.block;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's BlockColorsExtension (core.model.colors BlockColorsMixin)
public class BlockColors implements net.caffeinemc.mods.sodium.client.model.color.interop.BlockColorsExtension {
    // MODIFIED for porting: sodium core.model.colors BlockColorsMixin @Unique fields. Sodium keeps a copy because it needs
    // to iterate over the entry pairs, rather than just the values.
    private final it.unimi.dsi.fastutil.objects.Reference2ReferenceMap<Block, BlockTintSource[]> sodium$blocksToColor =
        new it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap<>();

    private final it.unimi.dsi.fastutil.objects.ReferenceSet<Block> sodium$overridenBlocks =
        new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

    @Override
    public it.unimi.dsi.fastutil.objects.Reference2ReferenceMap<Block, BlockTintSource[]> sodium$getProviders() {
        return it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps.unmodifiable(this.sodium$blocksToColor);
    }

    @Override
    public it.unimi.dsi.fastutil.objects.ReferenceSet<Block> sodium$getOverridenVanillaBlocks() {
        return it.unimi.dsi.fastutil.objects.ReferenceSets.unmodifiable(this.sodium$overridenBlocks);
    }

    public static final int LILY_PAD_IN_WORLD = -14647248;
    public static final int LILY_PAD_DEFAULT = -9321636;
    private static final BlockTintSource BLANK_LAYER = BlockTintSources.constant(-1);
    private final Map<Block, List<BlockTintSource>> sources = new IdentityHashMap<>();

    public static BlockColors createDefault() {
        BlockColors colors = new BlockColors();
        colors.register(List.of(BlockTintSources.doubleTallGrass()), Blocks.LARGE_FERN, Blocks.TALL_GRASS);
        colors.register(List.of(BlockTintSources.grass()), Blocks.FERN, Blocks.SHORT_GRASS, Blocks.POTTED_FERN, Blocks.BUSH);
        colors.register(List.of(BlockTintSources.grassBlock()), Blocks.GRASS_BLOCK);
        colors.register(List.of(BLANK_LAYER, BlockTintSources.grass()), Blocks.PINK_PETALS, Blocks.WILDFLOWERS);
        colors.register(List.of(BlockTintSources.constant(-10380959)), Blocks.SPRUCE_LEAVES);
        colors.register(List.of(BlockTintSources.constant(-8345771)), Blocks.BIRCH_LEAVES);
        colors.register(
            List.of(BlockTintSources.foliage()),
            Blocks.OAK_LEAVES,
            Blocks.JUNGLE_LEAVES,
            Blocks.ACACIA_LEAVES,
            Blocks.DARK_OAK_LEAVES,
            Blocks.VINE,
            Blocks.MANGROVE_LEAVES
        );
        colors.register(List.of(BlockTintSources.dryFoliage()), Blocks.LEAF_LITTER);
        colors.register(List.of(BlockTintSources.water()), Blocks.WATER_CAULDRON);
        colors.register(List.of(BlockTintSources.waterParticles()), Blocks.WATER, Blocks.BUBBLE_COLUMN);
        colors.register(List.of(BlockTintSources.redstone()), Blocks.REDSTONE_WIRE);
        colors.register(List.of(BlockTintSources.sugarCane()), Blocks.SUGAR_CANE);
        colors.register(List.of(BlockTintSources.constant(-2046180)), Blocks.ATTACHED_MELON_STEM, Blocks.ATTACHED_PUMPKIN_STEM);
        colors.register(List.of(BlockTintSources.stem()), Blocks.MELON_STEM, Blocks.PUMPKIN_STEM);
        colors.register(List.of(BlockTintSources.constant(-9321636, -14647248)), Blocks.LILY_PAD);
        return colors;
    }

    public List<BlockTintSource> getTintSources(final BlockState state) {
        return this.sources.getOrDefault(state.getBlock(), List.of());
    }

    public @Nullable BlockTintSource getTintSource(final BlockState state, final int layer) {
        List<BlockTintSource> layers = this.getTintSources(state);
        return layer >= layers.size() ? null : layers.get(layer);
    }

    public void register(final List<BlockTintSource> layers, final Block... blocks) {
        // MODIFIED for porting: sodium core.model.colors BlockColorsMixin#preRegisterColorProvider (HEAD)
        for (Block block : blocks) {
            // There will be one provider already registered for vanilla blocks, if we are replacing it, it means a mod is
            // using custom logic, and we need to disable per-vertex coloring
            if (this.sodium$blocksToColor.put(block, layers.toArray(BlockTintSource[]::new)) != null) {
                this.sodium$overridenBlocks.add(block);
                net.caffeinemc.mods.sodium.client.SodiumClientMod.logger()
                    .info(
                        "Block {} had its color provider replaced with {} and will not use per-vertex coloring",
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block),
                        layers
                    );
            }
        }

        for (Block block : blocks) {
            this.sources.put(block, layers);
        }
    }

    public Set<Property<?>> getColoringProperties(final Block block) {
        List<BlockTintSource> sources = this.sources.getOrDefault(block, List.of());
        if (sources.isEmpty()) {
            return Set.of();
        }

        if (sources.size() == 1) {
            return sources.getFirst().relevantProperties();
        }

        Set<Property<?>> result = new HashSet<>();

        for (BlockTintSource source : sources) {
            result.addAll(source.relevantProperties());
        }

        return result;
    }
}