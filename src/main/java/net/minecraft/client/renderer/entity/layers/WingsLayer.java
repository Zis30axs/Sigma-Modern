package net.minecraft.client.renderer.entity.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class WingsLayer<S extends HumanoidRenderState, M extends EntityModel<S>> extends RenderLayer<S, M> {
    // MODIFIED for porting: iris entity_render_context MixinElytraLayer @Unique constant
    private static final net.irisshaders.iris.shaderpack.materialmap.NamespacedId IRIS_ELYTRA_CAPE_LOCATION = new net.irisshaders.iris.shaderpack.materialmap.NamespacedId("minecraft", "elytra_with_cape");

    private final ElytraModel elytraModel;
    private final ElytraModel elytraBabyModel;
    private final EquipmentLayerRenderer equipmentRenderer;

    public WingsLayer(final RenderLayerParent<S, M> renderer, final EntityModelSet modelSet, final EquipmentLayerRenderer equipmentRenderer) {
        super(renderer);
        this.elytraModel = new ElytraModel(modelSet.bakeLayer(ModelLayers.ELYTRA));
        this.elytraBabyModel = new ElytraModel(modelSet.bakeLayer(ModelLayers.ELYTRA_BABY));
        this.equipmentRenderer = equipmentRenderer;
    }

    public void submit(
        final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int lightCoords, final S state, final float yRot, final float xRot
    ) {
        ItemStack itemStack = state.chestEquipment;
        Equippable equippable = itemStack.get(DataComponents.EQUIPPABLE);
        if (equippable != null && !equippable.assetId().isEmpty()) {
            Identifier playerElytraTexture = getPlayerElytraTexture(state);
            ElytraModel model = state.isBaby ? this.elytraBabyModel : this.elytraModel;
            // MODIFIED for porting: was iris's entity_render_context MixinElytraLayer#changeId (@Inject at the INVOKE of
            // PoseStack#pushPose) - the elytra, or the cape drawn in its place, gets its own item id for the pack.
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds() != null) {
                if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState irisAvatarState
                    && irisAvatarState.skin.cape() != null
                    && irisAvatarState.showCape) {
                    net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE
                        .setCurrentRenderedItem(net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds().applyAsInt(IRIS_ELYTRA_CAPE_LOCATION));
                } else {
                    net.minecraft.resources.Identifier irisLocation = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(net.minecraft.world.item.Items.ELYTRA);
                    net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE
                        .setCurrentRenderedItem(
                            net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getItemIds().applyAsInt(new net.irisshaders.iris.shaderpack.materialmap.NamespacedId(irisLocation.getNamespace(), irisLocation.getPath()))
                        );
                }
            }

            poseStack.pushPose();
            poseStack.translate(0.0F, 0.0F, 0.125F);
            this.equipmentRenderer
                .renderLayers(
                    EquipmentClientInfo.LayerType.WINGS,
                    equippable.assetId().get(),
                    model,
                    state,
                    itemStack,
                    poseStack,
                    submitNodeCollector,
                    lightCoords,
                    playerElytraTexture,
                    state.outlineColor,
                    0
                );
            poseStack.popPose();
        }

        // MODIFIED for porting: was iris's entity_render_context MixinElytraLayer#changeId2 (@Inject RETURN)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
        }
    }

    private static @Nullable Identifier getPlayerElytraTexture(final HumanoidRenderState state) {
        if (state instanceof AvatarRenderState playerState) {
            PlayerSkin skin = playerState.skin;
            if (skin.elytra() != null) {
                return skin.elytra().texturePath();
            }

            if (skin.cape() != null && playerState.showCape) {
                return skin.cape().texturePath();
            }
        }

        return null;
    }
}