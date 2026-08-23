package net.minecraft.client.resources.metadata.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.util.ExtraCodecs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
/**
 * MODIFIED for porting: iris's texture AnimationMetadataSectionAccessor exposes {@code frameWidth} / {@code frameHeight} as
 * {@code @Mutable @Accessor} setters, which a record cannot have (iris's PBR atlas loader rescales a PBR texture and then
 * corrects the frame size of the animation metadata in place - see
 * {@link net.irisshaders.iris.pbr.loader.AtlasPBRLoader}). This was therefore rewritten as a plain final class with the same
 * components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as the record's
 * generated ones.
 */
public final class AnimationMetadataSection implements net.irisshaders.iris.mixin.texture.AnimationMetadataSectionAccessor {
    private final Optional<List<AnimationFrame>> frames;
    private Optional<Integer> frameWidth;
    private Optional<Integer> frameHeight;
    private final int defaultFrameTime;
    private final boolean interpolatedFrames;

    public AnimationMetadataSection(
        final Optional<List<AnimationFrame>> frames,
        final Optional<Integer> frameWidth,
        final Optional<Integer> frameHeight,
        final int defaultFrameTime,
        final boolean interpolatedFrames
    ) {
        this.frames = frames;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.defaultFrameTime = defaultFrameTime;
        this.interpolatedFrames = interpolatedFrames;
    }

    public Optional<List<AnimationFrame>> frames() {
        return this.frames;
    }

    public Optional<Integer> frameWidth() {
        return this.frameWidth;
    }

    public Optional<Integer> frameHeight() {
        return this.frameHeight;
    }

    public int defaultFrameTime() {
        return this.defaultFrameTime;
    }

    public boolean interpolatedFrames() {
        return this.interpolatedFrames;
    }

    // MODIFIED for porting: was iris's texture AnimationMetadataSectionAccessor
    @Override
    public Optional<Integer> getFrameWidth() {
        return this.frameWidth;
    }

    @Override
    public void setFrameWidth(final Optional<Integer> frameWidth) {
        this.frameWidth = frameWidth;
    }

    @Override
    public Optional<Integer> getFrameHeight() {
        return this.frameHeight;
    }

    @Override
    public void setFrameHeight(final Optional<Integer> frameHeight) {
        this.frameHeight = frameHeight;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof AnimationMetadataSection other)) {
            return false;
        }

        return this.defaultFrameTime == other.defaultFrameTime
            && this.interpolatedFrames == other.interpolatedFrames
            && java.util.Objects.equals(this.frames, other.frames)
            && java.util.Objects.equals(this.frameWidth, other.frameWidth)
            && java.util.Objects.equals(this.frameHeight, other.frameHeight);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(this.frames, this.frameWidth, this.frameHeight, this.defaultFrameTime, this.interpolatedFrames);
    }

    @Override
    public String toString() {
        return "AnimationMetadataSection[frames="
            + this.frames
            + ", frameWidth="
            + this.frameWidth
            + ", frameHeight="
            + this.frameHeight
            + ", defaultFrameTime="
            + this.defaultFrameTime
            + ", interpolatedFrames="
            + this.interpolatedFrames
            + "]";
    }

    public static final Codec<AnimationMetadataSection> CODEC = RecordCodecBuilder.create(
        i -> i.group(
                AnimationFrame.CODEC.listOf().optionalFieldOf("frames").forGetter(AnimationMetadataSection::frames),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("width").forGetter(AnimationMetadataSection::frameWidth),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("height").forGetter(AnimationMetadataSection::frameHeight),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("frametime", 1).forGetter(AnimationMetadataSection::defaultFrameTime),
                Codec.BOOL.optionalFieldOf("interpolate", false).forGetter(AnimationMetadataSection::interpolatedFrames)
            )
            .apply(i, AnimationMetadataSection::new)
    );
    public static final MetadataSectionType<AnimationMetadataSection> TYPE = new MetadataSectionType<>("animation", CODEC);

    public FrameSize calculateFrameSize(final int spriteWidth, final int spriteHeight) {
        if (this.frameWidth.isPresent()) {
            return this.frameHeight.isPresent()
                ? new FrameSize(this.frameWidth.get(), this.frameHeight.get())
                : new FrameSize(this.frameWidth.get(), spriteHeight);
        }

        if (this.frameHeight.isPresent()) {
            return new FrameSize(spriteWidth, this.frameHeight.get());
        }

        int minDimension = Math.min(spriteWidth, spriteHeight);
        return new FrameSize(minDimension, minDimension);
    }
}