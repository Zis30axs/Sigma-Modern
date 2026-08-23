package com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.GpuFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's VertexFormatExtensions (core.render VertexFormatMixin), which assigns every
// vertex format a dense global id used by sodium's vertex serializer registry.
public class VertexFormat implements net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatExtensions,
    net.irisshaders.iris.pipeline.programs.VertexFormatExtension { // MODIFIED for porting: iris vertices MixinVertexFormat
    /**
     * MODIFIED for porting: was iris's vertices MixinVertexFormat (its VertexFormatExtension implementation) - makes sure the
     * correct attribute binding state for the extended vertex format is set up when needed.
     */
    private static final com.google.common.collect.ImmutableSet<String> IRIS_ATTRIBUTE_LIST = com.google.common.collect.ImmutableSet
        .of("Position", "Color", "Normal", "UV0", "UV1", "UV2", "LineWidth");

    @Override
    public void bindAttributesIris(final boolean isFallback, final int i) {
        int j = 0;

        for (VertexFormatElement x : this.getElements()) {
            String string = x.name();
            com.mojang.blaze3d.opengl.GlStateManager
                ._glBindAttribLocation(i, j, IRIS_ATTRIBUTE_LIST.contains(string) && !isFallback ? "iris_" + string : string);
            j++;
        }
    }

    // MODIFIED for porting: sodium core.render VertexFormatMixin @Unique field
    private int sodium$globalId;

    @Override
    public int sodium$getGlobalId() {
        return this.sodium$globalId;
    }

    private static final int VERTEX_ALIGNMENT = 4;
    public static final int MAX_VERTEX_ELEMENTS = 16;
    private final Map<String, VertexFormatElement> elements = new Object2ObjectArrayMap<>(16);
    private final int vertexSize;
    private final int stepRate;
    private final List<VertexFormatElement> elementValues;

    private VertexFormat(final List<VertexFormatElement> elements, final int vertexSize, final int stepRate) {
        // MODIFIED for porting: sodium core.render VertexFormatMixin#afterInit (<init> RETURN). Assigned first so that the
        // remainder of the constructor keeps its original order; the registry only stores the reference.
        this.sodium$globalId = net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatRegistry.instance().allocateGlobalId(this);
        this.vertexSize = vertexSize;
        this.stepRate = stepRate;

        for (VertexFormatElement element : elements) {
            this.elements.putIfAbsent(element.name(), element);
        }

        this.elementValues = elements;
    }

    public static VertexFormat.Builder builder(final int stepRate) {
        return new VertexFormat.Builder(stepRate);
    }

    @Override
    public String toString() {
        return "VertexFormat" + this.elementValues.stream().map(VertexFormatElement::name).collect(Collectors.joining(", ", "[", "]"));
    }

    public int getVertexSize() {
        return this.vertexSize;
    }

    public int getStepRate() {
        return this.stepRate;
    }

    public List<VertexFormatElement> getElements() {
        return this.elementValues;
    }

    public @Nullable VertexFormatElement getElement(final String attributeName) {
        return this.elements.get(attributeName);
    }

    public boolean contains(final String attributeName) {
        return this.elements.containsKey(attributeName);
    }

    @Override
    public boolean equals(final Object o) {
        return this == o ? true : o instanceof VertexFormat format && this.elements.equals(format.elements) && this.vertexSize == format.vertexSize;
    }

    @Override
    public int hashCode() {
        return this.elementValues.hashCode();
    }

    @OnlyIn(Dist.CLIENT)
    public static class Builder {
        private final List<VertexFormatElement> elements = new ArrayList<>(16);
        private int offset = 0;
        private final int stepRate;

        private Builder(final int stepRate) {
            this.stepRate = stepRate;
        }

        private void createAttribute(final String name, final int offset, final GpuFormat elementFormat) {
            if (this.elements.size() >= 16) {
                throw new IllegalArgumentException("Having more than 16 attributes are not supported");
            }

            if (!Mth.isMultipleOf(offset, elementFormat.byteAlignment())) {
                throw new IllegalArgumentException(name + " is not aligned to " + elementFormat.byteAlignment() + " as required by " + elementFormat);
            }

            VertexFormatElement element = new VertexFormatElement(name, offset, elementFormat);
            this.elements.add(element);
        }

        private void validateUniqueName(final String name) {
            for (VertexFormatElement element : this.elements) {
                if (element.name().equals(name)) {
                    throw new IllegalArgumentException("Another vertex attribute exists with the name " + name);
                }
            }
        }

        public VertexFormat.Builder addAttribute(final String name, final GpuFormat elementFormat) {
            this.validateUniqueName(name);
            this.createAttribute(name, this.offset, elementFormat);
            this.offset = this.offset + elementFormat.blockSize();
            return this;
        }

        public VertexFormat.Builder addAttribute(final String name, final int stride, final GpuFormat elementFormat) {
            this.validateUniqueName(name);
            this.createAttribute(name, this.offset, elementFormat);
            this.offset += stride;
            return this;
        }

        public VertexFormat.Builder addAttribute(final String name, final GpuFormat elementFormat, final int columnCount) {
            this.validateUniqueName(name);

            for (int i = 0; i < columnCount; i++) {
                this.createAttribute(name, this.offset, elementFormat);
                this.offset = this.offset + elementFormat.blockSize();
            }

            return this;
        }

        public VertexFormat.Builder addAttribute(final String name, final int offset, final int stride, final GpuFormat elementFormat, final int columnCount) {
            this.validateUniqueName(name);
            int offsetTracker = offset;

            for (int i = 0; i < columnCount; i++) {
                this.createAttribute(name, offsetTracker, elementFormat);
                offsetTracker += stride;
            }

            this.offset = Math.max(this.offset, offsetTracker);
            return this;
        }

        public VertexFormat build() {
            int vertexSize = this.offset;
            if (!Mth.isMultipleOf(vertexSize, 4)) {
                throw new IllegalStateException("Vertex size must be a multiple of 4, was " + vertexSize);
            } else {
                return new VertexFormat(this.elements, vertexSize, this.stepRate);
            }
        }
    }
}