package net.minecraft.client.renderer.entity.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class EquipmentLayerRenderer {
    private static final int NO_LAYER_COLOR = 0;
    private final EquipmentAssetManager equipmentAssets;
    private final Function<EquipmentLayerRenderer.LayerTextureKey, Identifier> layerTextureLookup;
    private final Function<EquipmentLayerRenderer.TrimSpriteKey, TextureAtlasSprite> trimSpriteLookup;

    public EquipmentLayerRenderer(final EquipmentAssetManager equipmentAssets, final TextureAtlas armorTrimAtlas) {
        this.equipmentAssets = equipmentAssets;
        this.layerTextureLookup = Util.memoize(key -> key.layer.getTextureLocation(key.layerType));
        this.trimSpriteLookup = Util.memoize(key -> armorTrimAtlas.getSprite(key.spriteId()));
    }

    public <S> void renderLayers(
        final EquipmentClientInfo.LayerType layerType,
        final ResourceKey<EquipmentAsset> equipmentAssetId,
        final Model<? super S> model,
        final S state,
        final ItemStack itemStack,
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector,
        final int lightCoords,
        final int outlineColor
    ) {
        this.renderLayers(layerType, equipmentAssetId, model, state, itemStack, poseStack, submitNodeCollector, lightCoords, null, outlineColor, 1);
    }

    public <S> void renderLayers(
        final EquipmentClientInfo.LayerType layerType,
        final ResourceKey<EquipmentAsset> equipmentAssetId,
        final Model<? super S> model,
        final S state,
        final ItemStack itemStack,
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector,
        final int lightCoords,
        final @Nullable Identifier playerTextureOverride,
        final int outlineColor,
        final int order
    ) {
        List<EquipmentClientInfo.Layer> layers = this.equipmentAssets.get(equipmentAssetId).getLayers(layerType);
        if (!layers.isEmpty()) {
            int dyeColor = DyedItemColor.getOrDefault(itemStack, 0);
            boolean renderFoil = itemStack.hasFoil();
            int nextOrder = order;

            for (EquipmentClientInfo.Layer layer : layers) {
                int color = getColorForLayer(layer, dyeColor);
                if (color != 0) {
                    // MODIFIED for porting: was iris's entity_render_context MixinEquipmentLayerRenderer#changeId (@Inject at
                    // the INVOKE of EquipmentClientInfo$Layer#usePlayerTexture, with @Local(argsOnly) ItemStack)
                    if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds() != null) {
                        Identifier irisLocation = itemStack.get(DataComponents.ITEM_MODEL);
                        if (irisLocation == null) {
                            irisLocation = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(itemStack.getItem());
                        }

                        net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE
                            .setCurrentRenderedItem(
                                net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds().applyAsInt(new net.irisshaders.iris.shaderpack.materialmap.NamespacedId(irisLocation.getNamespace(), irisLocation.getPath()))
                            );
                    }

                    Identifier layerTexture = layer.usePlayerTexture() && playerTextureOverride != null
                        ? playerTextureOverride
                        : this.layerTextureLookup.apply(new EquipmentLayerRenderer.LayerTextureKey(layerType, layer));
                    submitNodeCollector.order(nextOrder++)
                        .submitModel(
                            model,
                            state,
                            poseStack,
                            RenderTypes.armorCutoutNoCull(layerTexture),
                            lightCoords,
                            OverlayTexture.NO_OVERLAY,
                            color,
                            null,
                            outlineColor,
                            null
                        );
                    if (renderFoil) {
                        submitNodeCollector.order(nextOrder++)
                            .submitModel(
                                model,
                                state,
                                poseStack,
                                RenderTypes.armorEntityGlint(),
                                lightCoords,
                                OverlayTexture.NO_OVERLAY,
                                color,
                                null,
                                outlineColor,
                                null
                            );
                    }

                    renderFoil = false;
                }
            }

            ArmorTrim trim = itemStack.get(DataComponents.TRIM);
            if (trim != null && layerType != EquipmentClientInfo.LayerType.HUMANOID_BABY) {
                // MODIFIED for porting: was iris's entity_render_context MixinEquipmentLayerRenderer#changeTrimTemp (@Inject at
                // the FIELD read of trimSpriteLookup, with @Local ArmorTrim) - the trim material gets its own temporary item id.
                // Upstream's "// TODO 1.21.5 check" note is carried over.
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds() != null) {
                    // TODO 1.21.5 check
                    net.irisshaders.iris.helpers.EntityState
                        .interposeItemId(
                            net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE
                                .getItemIds()
                                .applyAsInt(new net.irisshaders.iris.shaderpack.materialmap.NamespacedId("minecraft", "trim_" + trim.material().value().assets().base().suffix()))
                        );
                }

                TextureAtlasSprite sprite = this.trimSpriteLookup.apply(new EquipmentLayerRenderer.TrimSpriteKey(trim, layerType, equipmentAssetId));
                RenderType renderType = Sheets.armorTrimsSheet(trim.pattern().value().decal());
                submitNodeCollector.order(nextOrder++)
                    .submitModel(model, state, poseStack, renderType, lightCoords, OverlayTexture.NO_OVERLAY, -1, sprite, outlineColor, null);
                // MODIFIED for porting: was iris's entity_render_context MixinEquipmentLayerRenderer#changeTrimTemp2 (@Inject at
                // the third INVOKE of OrderedSubmitNodeCollector#submitModel, shift AFTER) - that third call is exactly the trim
                // one above.
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds() != null) {
                    net.irisshaders.iris.helpers.EntityState.restoreItemId();
                }
            }
        }

        // MODIFIED for porting: was iris's entity_render_context MixinEquipmentLayerRenderer#changeId2 (@Inject TAIL)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
        }
    }

    private static int getColorForLayer(final EquipmentClientInfo.Layer layer, final int dyeColor) {
        Optional<EquipmentClientInfo.Dyeable> dyeable = layer.dyeable();
        if (dyeable.isPresent()) {
            int colorWhenUndyed = dyeable.get().colorWhenUndyed().map(ARGB::opaque).orElse(0);
            return dyeColor != 0 ? dyeColor : colorWhenUndyed;
        } else {
            return -1;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private record LayerTextureKey(EquipmentClientInfo.LayerType layerType, EquipmentClientInfo.Layer layer) {
    }

    @OnlyIn(Dist.CLIENT)
    private record TrimSpriteKey(ArmorTrim trim, EquipmentClientInfo.LayerType layerType, ResourceKey<EquipmentAsset> equipmentAssetId) {
        public Identifier spriteId() {
            return this.trim.layerAssetId(this.layerType.trimAssetPrefix(), this.equipmentAssetId);
        }
    }
}