package com.mentalfrostbyte.jello.gui.modern;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One blurred world snapshot per frame, sampled only inside the requested HUD silhouettes. */
public final class ModernHudBlur implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/HudBlur");
    private static final Identifier EFFECT = Identifier.withDefaultNamespace("sigma_hud_blur");
    private static final int POINTS = 36;
    private static final float[] CORNER_COS = new float[POINTS], CORNER_SIN = new float[POINTS];
    static {
        for (int i = 0; i < POINTS; i++) {
            double angle = -Math.PI * 0.5 * (i / 9 + 1) - (i % 9) * Math.PI / 16;
            CORNER_COS[i] = (float)Math.cos(angle);
            CORNER_SIN[i] = (float)Math.sin(angle);
        }
    }
    private @Nullable TextureTarget target;
    private @Nullable PostChain failedChain;

    public record Size(int width, int height) {}
    public record Vertex(float x, float y, float u, float v, float alpha) {}
    @FunctionalInterface interface VertexSink { void accept(float x, float y, float u, float v, float alpha); }

    /** Scalars make the extracted pose immutable, even when the caller subsequently pops or changes its stack. */
    public record Transform(float m00, float m01, float m10, float m11, float m20, float m21) {
        static Transform copy(Matrix3x2fc pose) {
            return new Transform(pose.m00(), pose.m01(), pose.m10(), pose.m11(), pose.m20(), pose.m21());
        }
        float x(float x, float y) { return m00 * x + m10 * y + m20; }
        float y(float x, float y) { return m01 * x + m11 * y + m21; }
        boolean finite() {
            return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m10)
                    && Float.isFinite(m11) && Float.isFinite(m20) && Float.isFinite(m21);
        }
    }

    /** Geometry and clipping only. GPU bindings are assigned later, after world rendering and resize. */
    public record Request(int x, int y, int width, int height, float radius, float opacity,
                          Transform pose, ScreenRectangle scissor, ScreenRectangle bounds) {}

    public static @Nullable Element extract(Matrix3x2fc pose, @Nullable ScreenRectangle scissor, int x, int y,
                                             int width, int height, int radius, float opacity, int viewportWidth, int viewportHeight) {
        if (width <= 0 || height <= 0 || viewportWidth <= 0 || viewportHeight <= 0 || !Float.isFinite(opacity) || opacity <= 0) return null;
        Transform transform = Transform.copy(pose);
        if (!transform.finite()) return null;
        ScreenRectangle viewport = new ScreenRectangle(0, 0, viewportWidth, viewportHeight);
        ScreenRectangle clip = scissor == null ? viewport : scissor.intersection(viewport);
        if (clip == null) return null;
        ScreenRectangle bounds = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose).intersection(clip);
        if (bounds == null) return null;
        return new Element(new Request(x, y, width, height, Math.clamp(radius, 0, Math.min(width, height) / 2),
                Math.min(1, opacity), transform, clip, bounds));
    }

    /** Half-resolution at most, capped for 4K windows; neither dimension may collapse to zero. */
    static Size targetSize(int width, int height) {
        if (width <= 0 || height <= 0) return new Size(0, 0);
        double scale = Math.min(0.5, Math.min(1280.0 / width, 720.0 / height));
        return new Size(Math.max(1, (int)Math.ceil(width * scale)), Math.max(1, (int)Math.ceil(height * scale)));
    }

    /** Runs before GUI preparation: the source already contains the completed world, Iris effects and held item. */
    public void prepare(List<Element> elements, RenderTarget source, int guiScale, GraphicsResourceAllocator allocator) {
        if (elements.isEmpty()) {
            close();
            return;
        }
        if (source.width <= 0 || source.height <= 0 || guiScale <= 0 || source.getColorTextureView() == null) return;
        PostChain chain = Minecraft.getInstance().getShaderManager().getPostChain(EFFECT, java.util.Set.of(PostChain.MAIN_TARGET_ID));
        if (chain == null || chain == failedChain) return;
        GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();
        try {
            Size size = targetSize(source.width, source.height);
            if (target == null || target.width != size.width || target.height != size.height) {
                if (target != null) target.destroyBuffers();
                target = new TextureTarget("Sigma HUD blur", size.width, size.height, false, GpuFormat.RGBA8_UNORM);
            }
            // TRACY_BLIT is the existing unblended screenquad copy pipeline. Linear sampling downsizes entirely on the GPU.
            // Neither this pass nor the subsequent chain ever writes to the game's main target or depth texture.
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Sigma HUD world snapshot", target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderPipelines.TRACY_BLIT);
                RenderSystem.bindDefaultUniforms(pass);
                pass.bindTexture("InSampler", source.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(3, 1, 0, 0);
            }
            chain.process(target, allocator);
            TextureSetup texture = TextureSetup.singleTexture(target.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (Element element : elements) element.bind(texture, guiScale / (float)source.width, guiScale / (float)source.height, guiScale);
        } catch (RuntimeException failure) {
            // A reloaded chain is a different instance and can be tried again; a broken one does not flood every frame.
            failedChain = chain;
            LOGGER.warn("HUD backdrop blur unavailable; keeping the normal HUD surface", failure);
            if (target != null) { target.destroyBuffers(); target = null; }
        } finally {
            // PostPass restores this normally; restore it here as well if a pass failed midway.
            if (oldProjection != null) RenderSystem.setProjectionMatrix(oldProjection, oldProjectionType);
        }
    }

    @Override public void close() {
        if (target != null) { target.destroyBuffers(); target = null; }
        failedChain = null;
    }

    public static final class Element implements GuiElementRenderState {
        private final Request request;
        private TextureSetup texture = TextureSetup.noTexture();
        private float uScale, vScale;
        private int guiScale;
        private boolean ready;

        private Element(Request request) { this.request = request; }
        public Request request() { return request; }
        private void bind(TextureSetup texture, float uScale, float vScale, int guiScale) {
            this.texture = texture;
            this.uScale = uScale;
            this.vScale = vScale;
            this.guiScale = guiScale;
            this.ready = true;
        }
        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }
        @Override public TextureSetup textureSetup() { return texture; }
        @Override public ScreenRectangle scissorArea() { return request.scissor; }
        @Override public ScreenRectangle bounds() { return request.bounds; }
        @Override public void buildVertices(VertexConsumer buffer) {
            if (!ready) return;
            vertices(request, uScale, vScale, guiScale, (x, y, u, v, alpha) -> buffer.addVertex(x, y, 0)
                    .setUv(u, v).setColor(Math.clamp(Math.round(alpha * 255), 0, 255) << 24 | 0xFFFFFF));
        }
    }

    /** Rounded center fan plus a narrow alpha fringe, using the same quad winding as BlitRenderState. */
    static void vertices(Request request, float uScale, float vScale, int guiScale, VertexSink output) {
        Transform pose = request.pose;
        float scale = Math.max((float)Math.hypot(pose.m00, pose.m01), (float)Math.hypot(pose.m10, pose.m11));
        float fringe = Math.min(Math.min(request.width, request.height) * 0.5F, 0.75F / Math.max(0.001F, scale * guiScale));
        float cx = request.x + request.width * 0.5F, cy = request.y + request.height * 0.5F;
        for (int i = 0; i < POINTS; i++) {
            int next = (i + 1) % POINTS;
            float ix = pointX(request, i, fringe), iy = pointY(request, i, fringe);
            float nx = pointX(request, next, fringe), ny = pointY(request, next, fringe);
            emit(request, cx, cy, request.opacity, uScale, vScale, output);
            emit(request, ix, iy, request.opacity, uScale, vScale, output);
            emit(request, nx, ny, request.opacity, uScale, vScale, output);
            emit(request, cx, cy, request.opacity, uScale, vScale, output);
            emit(request, ix, iy, request.opacity, uScale, vScale, output);
            emit(request, pointX(request, i, 0), pointY(request, i, 0), 0, uScale, vScale, output);
            emit(request, pointX(request, next, 0), pointY(request, next, 0), 0, uScale, vScale, output);
            emit(request, nx, ny, request.opacity, uScale, vScale, output);
        }
    }

    private static float pointX(Request r, int index, float inset) {
        float radius = Math.max(0, r.radius - inset);
        float cx = index / 9 < 2 ? r.x + inset + radius : r.x + r.width - inset - radius;
        return cx + CORNER_COS[index] * radius;
    }

    private static float pointY(Request r, int index, float inset) {
        int corner = index / 9;
        float radius = Math.max(0, r.radius - inset);
        float cy = corner == 0 || corner == 3 ? r.y + inset + radius : r.y + r.height - inset - radius;
        return cy + CORNER_SIN[index] * radius;
    }

    private static void emit(Request r, float x, float y, float alpha, float uScale, float vScale, VertexSink output) {
        float screenX = r.pose.x(x, y), screenY = r.pose.y(x, y);
        // Render-target textures have bottom-up V, like vanilla PictureInPictureRenderer.blitTexture.
        output.accept(screenX, screenY, screenX * uScale, 1 - screenY * vScale, alpha);
    }
}
