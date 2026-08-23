package net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline;

import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.caffeinemc.mods.sodium.client.services.PlatformModelAccess;
import net.caffeinemc.mods.sodium.client.services.PlatformModelEmitter;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.TriState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public class BlockRenderer extends AbstractBlockRenderContext implements net.irisshaders.iris.vertices.sodium.terrain.VertexEncoderInterface,
    net.irisshaders.iris.compat.sodium.mixin.BlockRendererAccessor { // MODIFIED for porting: iris compat.sodium MixinBlockRenderer + BlockRendererAccessor
    /**
     * MODIFIED for porting: iris compat.sodium MixinBlockRenderer @Unique fields (its VertexEncoderInterface implementation) - the block
     * currently being meshed, so the extended chunk vertex format can carry its id, render type and light emission.
     */
    private int iris$blockId;

    private byte iris$isFluid;

    private byte iris$lightEmission;

    private int iris$localX;

    private int iris$localY;

    private int iris$localZ;

    private int iris$lastBlockId;

    @Override
    public void beginBlock(final int blockId, final byte isFluid, final byte lightEmission, final int x, final int y, final int z) {
        this.iris$blockId = blockId;
        this.iris$isFluid = isFluid;
        this.iris$lightEmission = lightEmission;
        this.iris$localX = x;
        this.iris$localY = y;
        this.iris$localZ = z;
    }

    @Override
    public void overrideBlock(final int anInt) {
        if (this.iris$lastBlockId != -1) {
            this.iris$lastBlockId = this.iris$blockId;
        }

        this.iris$blockId = anInt;
    }

    @Override
    public void restoreBlock() {
        if (this.iris$lastBlockId != -1) {
            this.iris$blockId = this.iris$lastBlockId;
            this.iris$lastBlockId = -1;
        }
    }

    // MODIFIED for porting: iris compat.sodium MixinBlockRenderer @Unique field - the render type a shader pack forces
    private net.minecraft.client.renderer.chunk.ChunkSectionLayer iris$overrideRenderType;

    private final ColorProviderRegistry colorProviderRegistry;
    private final int[] vertexColors = new int[4];
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    private ChunkBuildBuffers buffers;

    // MODIFIED for porting: was iris's compat.sodium BlockRendererAccessor @Accessor("buffers"). Nothing in iris reads it, but
    // the accessor is declared in its mixin config, so it is implemented here rather than left dangling.
    @Override
    public ChunkBuildBuffers getBuffers() {
        return this.buffers;
    }

    private final Vector3f posOffset = new Vector3f();
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    @Nullable
    private ColorProvider<BlockState> colorProvider;
    private TranslucentGeometryCollector collector;
    private final boolean cutoutLeaves;

    private final ColorProvider<BlockState> mutableColorProvider = PlatformModelAccess.getInstance().createMutableColorProvider();

    public BlockRenderer(ColorProviderRegistry colorRegistry, LightPipelineProvider lighters) {
        this.colorProviderRegistry = colorRegistry;
        this.lighters = lighters;

        this.random = new SingleThreadedRandomSource(42L);
        this.cutoutLeaves = Minecraft.getInstance().options.cutoutLeaves().get();
    }

    public void prepare(ChunkBuildBuffers buffers, LevelSlice level, TranslucentGeometryCollector collector) {
        this.buffers = buffers;
        this.level = level;
        this.collector = collector;
        this.slice = level;
    }

    public void release() {
        this.buffers = null;
        this.level = null;
        this.collector = null;
        this.slice = null;
    }

    public void renderModel(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin) {
        // MODIFIED for porting: was iris's compat.sodium MixinBlockRenderer#handleShaderPackTransparency (@Inject at the INVOKE
        // of PlatformModelEmitter#emitModel) - a shader pack can move a block to a different terrain layer.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            this.iris$overrideRenderType = null;
            if (net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockTypeIds() != null && state != null) {
                net.irisshaders.iris.shaderpack.materialmap.BlockRenderType blockRenderType = net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE
                    .getBlockTypeIds()
                    .get(state.getBlock());
                if (blockRenderType != null) {
                    this.iris$overrideRenderType = switch (blockRenderType) {
                        case SOLID -> net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID;
                        case CUTOUT, CUTOUT_MIPPED -> net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT;
                        case TRANSLUCENT -> net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT;
                    };
                }
            }
        }

        this.state = state;
        this.pos = pos;

        this.prepareAoInfo(true);


        this.posOffset.set(origin.getX(), origin.getY(), origin.getZ());
        if (state.hasOffsetFunction()) {
            Vec3 modelOffset = state.getOffset(pos);
            this.posOffset.add((float) modelOffset.x, (float) modelOffset.y, (float) modelOffset.z);
        }

        this.colorProvider = this.colorProviderRegistry.getColorProvider(state.getBlock());

        this.prepareCulling(true);

        this.random.setSeed(state.getSeed(pos));

        this.forceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);

        PlatformModelEmitter.getInstance().emitModel(model, this::isFaceCulled, this.getForEmitting(), this.random, this.level, pos, state, this::bufferDefaultModel);

        this.forceOpaque = false;
    }

    /**
     * Process quad, after quad transforms and the culling check have been applied.
     */
    @Override
    protected void processQuad(MutableQuadViewImpl quad) {
        // MODIFIED for porting: was iris's compat.sodium MixinBlockRenderer#iris$overrideQuad (@Inject HEAD)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && this.iris$overrideRenderType != null) {
            quad.setRenderType(this.iris$overrideRenderType);
        }

        final TriState aoMode = quad.ambientOcclusion();
        final SodiumShadeMode shadeMode = quad.getShadeMode();
        final LightMode lightMode;
        if (aoMode == TriState.DEFAULT) {
            lightMode = this.defaultLightMode;
        } else {
            lightMode = this.useAmbientOcclusion && aoMode != TriState.FALSE ? LightMode.SMOOTH : LightMode.FLAT;
        }
        final boolean emissive = quad.emissive();

        final ChunkSectionLayer blendMode = quad.getRenderType();
        final Material material = DefaultMaterials.forChunkLayer(this.forceOpaque ? ChunkSectionLayer.SOLID : blendMode);

        this.tintQuad(quad);
        this.shadeQuad(quad, lightMode, emissive, shadeMode);
        this.bufferQuad(quad, this.quadLightData.br, material);
    }

    private void tintQuad(MutableQuadViewImpl quad) {
        int tintIndex = quad.getTintIndex();

        if (tintIndex != -1) {
            ColorProvider<BlockState> colorProvider = this.colorProvider;

            if (colorProvider == null && this.mutableColorProvider != null) colorProvider = this.mutableColorProvider;

            if (colorProvider != null) {
                int[] vertexColors = this.vertexColors;
                colorProvider.getColors(this.slice, this.pos, this.scratchPos, this.state, quad, vertexColors, this.slice.hasBiomeBlend());

                for (int i = 0; i < 4; i++) {
                    quad.setColor(i, ColorMixer.mulComponentWise(vertexColors[i], quad.baseColor(i)));
                }
            }
        }
    }

    private void bufferQuad(MutableQuadViewImpl quad, float[] brightnesses, Material material) {
        // TODO: Find a way to reimplement quad reorientation
        ModelQuadOrientation orientation = ModelQuadOrientation.NORMAL;
        ChunkVertexEncoder.Vertex[] vertices = this.vertices;
        Vector3f offset = this.posOffset;

        for (int dstIndex = 0; dstIndex < 4; dstIndex++) {
            int srcIndex = orientation.getVertexIndex(dstIndex);

            ChunkVertexEncoder.Vertex out = vertices[dstIndex];
            // MODIFIED for porting: was iris's compat.sodium MixinBlockRenderer#iris$writeVertex (@Inject at the FIELD write of
            // ChunkVertexEncoder$Vertex.x)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                ((net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension)out)
                    .iris$setData(this.iris$lightEmission, this.iris$isFluid, this.iris$blockId, this.iris$localX, this.iris$localY, this.iris$localZ);
            }

            out.x = quad.getX(srcIndex) + offset.x;
            out.y = quad.getY(srcIndex) + offset.y;
            out.z = quad.getZ(srcIndex) + offset.z;

            // FRAPI uses ARGB color format; convert to ABGR.
            out.color = ColorARGB.toABGR(quad.baseColor(srcIndex));
            out.ao = brightnesses[srcIndex];

            out.u = quad.getTexU(srcIndex);
            out.v = quad.getTexV(srcIndex);

            out.light = quad.getLight(srcIndex);
        }

        var atlasSprite = quad.sprite(SpriteFinderCache.forBlockAtlas());
        var materialBits = material.bits();
        ModelQuadFacing normalFace = quad.normalFace();

        var pass = material.pass;

        // collect all translucent quads into the translucency sorting system if enabled,
        // and discard the quad if it's invalid (i.e. not visible)
        if (pass.isTranslucent() && this.collector != null &&
                this.collector.appendQuad(vertices, normalFace, quad.getFaceNormal())) {
            return;
        }

        ChunkModelBuilder builder = this.buffers.get(pass);
        ChunkMeshBufferBuilder vertexBuffer = builder.getVertexBuffer(normalFace);
        vertexBuffer.push(vertices, materialBits);

        if (atlasSprite != null) {
            builder.addSprite(atlasSprite);
        }
    }
}