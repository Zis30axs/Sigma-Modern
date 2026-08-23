package net.minecraft.client.renderer.texture.atlas.sources;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record DirectoryLister(String sourcePath, String idPrefix) implements SpriteSource {
    public static final MapCodec<DirectoryLister> MAP_CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(Codec.STRING.fieldOf("source").forGetter(DirectoryLister::sourcePath), Codec.STRING.fieldOf("prefix").forGetter(DirectoryLister::idPrefix))
            .apply(i, DirectoryLister::new)
    );

    @Override
    public void run(final ResourceManager resourceManager, final SpriteSource.Output output) {
        FileToIdConverter converter = new FileToIdConverter("textures/" + this.sourcePath, ".png");
        converter.listMatchingResources(resourceManager).forEach((identifier, resource) -> {
            // MODIFIED for porting: was iris's texture pbr MixinDirectoryLister#iris$modifyForEachAction (@ModifyArgs wrapping
            // the BiConsumer passed to Map#forEach) - a PBR texture (foo_n.png / foo_s.png) must not be listed as a sprite of
            // its own when the base texture exists.
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                String irisBasePath = net.irisshaders.iris.pbr.texture.PBRType.removeSuffix(identifier.getPath());
                if (irisBasePath != null && resourceManager.getResource(identifier.withPath(irisBasePath)).isPresent()) {
                    return;
                }
            }

            Identifier spriteLocation = converter.fileToId(identifier).withPrefix(this.idPrefix);
            output.add(spriteLocation, resource);
        });
    }

    @Override
    public MapCodec<DirectoryLister> codec() {
        return MAP_CODEC;
    }
}