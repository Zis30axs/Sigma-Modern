package net.minecraft.client.resources.model.geometry;

import com.mojang.blaze3d.platform.Transparency;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3fc;

@OnlyIn(Dist.CLIENT)
/**
 * MODIFIED for porting: sodium's core.model.quad BakedQuadMixin makes this type a
 * {@link net.caffeinemc.mods.sodium.client.model.quad.BakedQuadView} and adds three cached fields to it, which a record
 * cannot have. It was therefore rewritten as a plain final class with the same components and accessors; {@code equals},
 * {@code hashCode} and {@code toString} are implemented exactly as the record's generated ones (the cached fields are derived
 * from the components, so they are deliberately not part of them).
 */
public final class BakedQuad implements net.caffeinemc.mods.sodium.client.model.quad.BakedQuadView {
    private final Vector3fc position0;
    private final Vector3fc position1;
    private final Vector3fc position2;
    private final Vector3fc position3;
    private final long packedUV0;
    private final long packedUV1;
    private final long packedUV2;
    private final long packedUV3;
    private final Direction direction;
    private final BakedQuad.MaterialInfo materialInfo;
    // MODIFIED for porting: sodium core.model.quad BakedQuadMixin @Unique fields
    private final int sodium$flags;
    private final int sodium$normal;
    private final net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing sodium$normalFace;

    public BakedQuad(
        final Vector3fc position0,
        final Vector3fc position1,
        final Vector3fc position2,
        final Vector3fc position3,
        final long packedUV0,
        final long packedUV1,
        final long packedUV2,
        final long packedUV3,
        final Direction direction,
        final BakedQuad.MaterialInfo materialInfo
    ) {
        this.position0 = position0;
        this.position1 = position1;
        this.position2 = position2;
        this.position3 = position3;
        this.packedUV0 = packedUV0;
        this.packedUV1 = packedUV1;
        this.packedUV2 = packedUV2;
        this.packedUV3 = packedUV3;
        this.direction = direction;
        this.materialInfo = materialInfo;
        // MODIFIED for porting: sodium core.model.quad BakedQuadMixin#init (<init> RETURN)
        this.sodium$normal = this.calculateNormal();
        this.sodium$normalFace = net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing.fromPackedNormal(this.sodium$normal);
        this.sodium$flags = net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags.getQuadFlags(this, direction);
    }

    public Vector3fc position0() {
        return this.position0;
    }

    public Vector3fc position1() {
        return this.position1;
    }

    public Vector3fc position2() {
        return this.position2;
    }

    public Vector3fc position3() {
        return this.position3;
    }

    public long packedUV0() {
        return this.packedUV0;
    }

    public long packedUV1() {
        return this.packedUV1;
    }

    public long packedUV2() {
        return this.packedUV2;
    }

    public long packedUV3() {
        return this.packedUV3;
    }

    public Direction direction() {
        return this.direction;
    }

    public BakedQuad.MaterialInfo materialInfo() {
        return this.materialInfo;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof BakedQuad other)) {
            return false;
        }

        return this.packedUV0 == other.packedUV0
            && this.packedUV1 == other.packedUV1
            && this.packedUV2 == other.packedUV2
            && this.packedUV3 == other.packedUV3
            && java.util.Objects.equals(this.position0, other.position0)
            && java.util.Objects.equals(this.position1, other.position1)
            && java.util.Objects.equals(this.position2, other.position2)
            && java.util.Objects.equals(this.position3, other.position3)
            && java.util.Objects.equals(this.direction, other.direction)
            && java.util.Objects.equals(this.materialInfo, other.materialInfo);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
            this.position0,
            this.position1,
            this.position2,
            this.position3,
            this.packedUV0,
            this.packedUV1,
            this.packedUV2,
            this.packedUV3,
            this.direction,
            this.materialInfo
        );
    }

    @Override
    public String toString() {
        return "BakedQuad[position0="
            + this.position0
            + ", position1="
            + this.position1
            + ", position2="
            + this.position2
            + ", position3="
            + this.position3
            + ", packedUV0="
            + this.packedUV0
            + ", packedUV1="
            + this.packedUV1
            + ", packedUV2="
            + this.packedUV2
            + ", packedUV3="
            + this.packedUV3
            + ", direction="
            + this.direction
            + ", materialInfo="
            + this.materialInfo
            + "]";
    }

    // MODIFIED for porting: everything in this block was sodium's core.model.quad BakedQuadMixin
    @Override
    public float getX(final int idx) {
        return this.position(idx).x();
    }

    @Override
    public float getY(final int idx) {
        return this.position(idx).y();
    }

    @Override
    public float getZ(final int idx) {
        return this.position(idx).z();
    }

    @Override
    public int getColor(final int idx) {
        return 0xFFFFFFFF;
    }

    @Override
    public int getVertexNormal(final int idx) {
        return this.sodium$normal;
    }

    @Override
    public int getLight(final int idx) {
        return 0;
    }

    @Override
    public TextureAtlasSprite getSprite() {
        return this.materialInfo.sprite();
    }

    @Override
    public float getTexU(final int idx) {
        return net.minecraft.client.model.geom.builders.UVPair.unpackU(this.packedUV(idx));
    }

    @Override
    public float getTexV(final int idx) {
        return net.minecraft.client.model.geom.builders.UVPair.unpackV(this.packedUV(idx));
    }

    @Override
    public int getFlags() {
        return this.sodium$flags;
    }

    @Override
    public int getTintIndex() {
        return this.materialInfo.tintIndex();
    }

    @Override
    public net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing getNormalFace() {
        return this.sodium$normalFace;
    }

    @Override
    public int getFaceNormal() {
        return this.sodium$normal;
    }

    @Override
    public Direction getLightFace() {
        return this.direction;
    }

    @Override
    public int getMaxLightQuad(final int idx) {
        return net.minecraft.util.LightCoordsUtil.lightCoordsWithEmission(this.getLight(idx), this.materialInfo.lightEmission());
    }

    @Override
    public int getLightEmission() {
        return this.materialInfo.lightEmission();
    }

    @Override
    public boolean hasShade() {
        return this.materialInfo.shade();
    }

    @Override
    public boolean hasAO() {
        return true;
    }

    public static final int VERTEX_COUNT = 4;
    public static final int FLAG_TRANSLUCENT = 1;
    public static final int FLAG_ANIMATED = 2;

    public Vector3fc position(final int vertex) {
        return switch (vertex) {
            case 0 -> this.position0;
            case 1 -> this.position1;
            case 2 -> this.position2;
            case 3 -> this.position3;
            default -> throw new IndexOutOfBoundsException(vertex);
        };
    }

    public long packedUV(final int vertex) {
        return switch (vertex) {
            case 0 -> this.packedUV0;
            case 1 -> this.packedUV1;
            case 2 -> this.packedUV2;
            case 3 -> this.packedUV3;
            default -> throw new IndexOutOfBoundsException(vertex);
        };
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    @OnlyIn(Dist.CLIENT)
    public @interface MaterialFlags {
    }

    @OnlyIn(Dist.CLIENT)
    public record MaterialInfo(TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, int tintIndex, boolean shade, int lightEmission) {
        public static BakedQuad.MaterialInfo of(
            final Material.Baked material, final Transparency transparency, final int tintIndex, final boolean shade, final int lightEmission
        ) {
            ChunkSectionLayer layer = ChunkSectionLayer.byTransparency(transparency);
            RenderType itemRenderType;
            if (material.sprite().atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS)) {
                itemRenderType = transparency.hasTranslucent() ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet();
            } else {
                itemRenderType = transparency.hasTranslucent() ? Sheets.translucentItemSheet() : Sheets.cutoutItemSheet();
            }

            return new BakedQuad.MaterialInfo(material.sprite(), layer, itemRenderType, tintIndex, shade, lightEmission);
        }

        public boolean isTinted() {
            return this.tintIndex != -1;
        }

        public @BakedQuad.MaterialFlags int flags() {
            int flags = 0;
            flags |= this.layer.translucent() ? 1 : 0;
            return flags | (this.sprite.contents().isAnimated() ? 2 : 0);
        }
    }
}