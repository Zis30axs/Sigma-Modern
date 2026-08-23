package net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import java.io.IOException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public abstract class ReloadableTexture extends AbstractTexture
    implements net.irisshaders.iris.mixin.texture.ReloadableTextureAccessor { // MODIFIED for porting: iris ReloadableTextureAccessor
    private final Identifier resourceId;

    // MODIFIED for porting: was iris's texture ReloadableTextureAccessor @Accessor("resourceId")
    @Override
    public Identifier getLocation() {
        return this.resourceId;
    }

    public ReloadableTexture(final Identifier resourceId) {
        this.resourceId = resourceId;
    }

    public Identifier resourceId() {
        return this.resourceId;
    }

    public void apply(final TextureContents contents) {
        boolean clamp = contents.clamp();
        boolean blur = contents.blur();
        AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
        FilterMode minMag = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
        this.sampler = RenderSystem.getSamplerCache().getSampler(addressMode, addressMode, minMag, minMag, false);

        try (NativeImage image = contents.image()) {
            this.doLoad(image);
        }
    }

    protected void doLoad(final NativeImage image) {
        this.iris$doLoad(image);
        // MODIFIED for porting: was iris's texture pbr MixinReloadableTexture#iris$onDoLoad (@Inject RETURN)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.pbr.TextureTracker.INSTANCE.trackTexture(this.texture.iris$getGlId(), this);
        }
    }

    // MODIFIED for porting: original vanilla body of doLoad
    private void iris$doLoad(final NativeImage image) {
        GpuDevice device = RenderSystem.getDevice();
        this.close();
        this.texture = device.createTexture(this.resourceId::toString, 5, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
        this.textureView = device.createTextureView(this.texture);
        device.createCommandEncoder().writeToTexture(this.texture, image);
    }

    public abstract TextureContents loadContents(ResourceManager resourceManager) throws IOException;
}