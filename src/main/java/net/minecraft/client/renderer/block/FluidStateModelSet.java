package net.minecraft.client.renderer.block;

import java.util.Map;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class FluidStateModelSet {
    private static final FluidModel.Unbaked WATER_MODEL = new FluidModel.Unbaked(
        new Material(Identifier.withDefaultNamespace("block/water_still")),
        new Material(Identifier.withDefaultNamespace("block/water_flow")),
        new Material(Identifier.withDefaultNamespace("block/water_overlay")),
        BlockTintSources.water()
    );
    private static final FluidModel.Unbaked LAVA_MODEL = new FluidModel.Unbaked(
        new Material(Identifier.withDefaultNamespace("block/lava_still")), new Material(Identifier.withDefaultNamespace("block/lava_flow")), null, null
    );
    private final Map<Fluid, FluidModel> modelByFluid;
    private final FluidModel missingModel;

    public FluidStateModelSet(final Map<Fluid, FluidModel> modelByFluid, final FluidModel missingModel) {
        this.modelByFluid = modelByFluid;
        this.missingModel = missingModel;
    }

    public static Map<Fluid, FluidModel> bake(final MaterialBaker materials) {
        FluidModel waterModel = WATER_MODEL.bake(materials, () -> "Water");
        FluidModel lavaModel = LAVA_MODEL.bake(materials, () -> "Lava");
        return Map.of(Fluids.WATER, waterModel, Fluids.FLOWING_WATER, waterModel, Fluids.LAVA, lavaModel, Fluids.FLOWING_LAVA, lavaModel);
    }

    public FluidModel get(final FluidState state) {
        // MODIFIED for porting: sodium features.textures.animations.tracking FluidStateModelSetMixin#sodium$catchUsedSprites
        // (RETURN) - catches fluid sprites accessed outside the chunk fluid rendering path.
        FluidModel model = this.modelByFluid.getOrDefault(state.getType(), this.missingModel);
        if (model != null) {
            net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(model.stillMaterial().sprite());
            net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(model.flowingMaterial().sprite());
            net.minecraft.client.resources.model.sprite.Material.Baked overlay = model.overlayMaterial();
            if (overlay != null) {
                net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(overlay.sprite());
            }
        }

        return model;
    }
}