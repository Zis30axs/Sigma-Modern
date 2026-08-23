package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.jtracy.Plot;
import com.mojang.jtracy.TracyClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.IntStream;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

@OnlyIn(Dist.CLIENT)
public class GlStateManager {
    private static final Plot PLOT_TEXTURES = TracyClient.createPlot("GPU Textures");
    private static int numTextures = 0;
    private static final Plot PLOT_BUFFERS = TracyClient.createPlot("GPU Buffers");
    private static int numBuffers = 0;
    // MODIFIED for porting: widened for iris's GlStateManagerAccessor @Accessor("BLEND")
    public static final GlStateManager.BlendState[] BLEND = new GlStateManager.BlendState[8];
    // MODIFIED for porting: widened for iris's GlStateManagerAccessor @Accessor("DEPTH")
    public static final GlStateManager.DepthState DEPTH = new GlStateManager.DepthState();
    private static final GlStateManager.CullState CULL = new GlStateManager.CullState();
    private static final GlStateManager.PolygonOffsetState POLY_OFFSET = new GlStateManager.PolygonOffsetState();
    private static final GlStateManager.ColorLogicState COLOR_LOGIC = new GlStateManager.ColorLogicState();
    private static final GlStateManager.ScissorState SCISSOR = new GlStateManager.ScissorState();
    // MODIFIED for porting: widened for iris's GlStateManagerAccessor @Accessor("activeTexture")
    public static int activeTexture;
    // MODIFIED for porting: was iris's MixinGlStateManager#iris$increaseMaximumAllowedTextureUnits
    // (@ModifyConstant on <clinit>, intValue 12 -> 128). Shader packs bind far more samplers than vanilla; OpenGL cannot be
    // queried for the real limit here because RenderSystem is initialized too late.
    private static final int TEXTURE_COUNT = 128;
    // MODIFIED for porting: widened for iris's GlStateManagerAccessor @Accessor("TEXTURES")
    public static final GlStateManager.TextureState[] TEXTURES = IntStream.range(0, TEXTURE_COUNT)
        .mapToObj(i -> new GlStateManager.TextureState())
        .toArray(GlStateManager.TextureState[]::new);
    // MODIFIED for porting: widened for iris's GlStateManagerAccessor @Accessor("COLOR_MASK")
    public static final @ColorTargetState.WriteMask int[] COLOR_MASK = new int[8];
    private static int readFbo;
    private static int writeFbo;

    public static void _disableScissorTest() {
        RenderSystem.assertOnRenderThread();
        SCISSOR.mode.disable();
    }

    public static void _enableScissorTest() {
        RenderSystem.assertOnRenderThread();
        SCISSOR.mode.enable();
    }

    public static void _scissorBox(final int x, final int y, final int width, final int height) {
        RenderSystem.assertOnRenderThread();
        GL33C.glScissor(x, y, width, height);
    }

    public static void _disableDepthTest() {
        RenderSystem.assertOnRenderThread();
        DEPTH.mode.disable();
    }

    public static void _enableDepthTest() {
        RenderSystem.assertOnRenderThread();
        DEPTH.mode.enable();
    }

    public static void _depthFunc(final int func) {
        RenderSystem.assertOnRenderThread();
        if (func != DEPTH.func) {
            DEPTH.func = func;
            GL33C.glDepthFunc(func);
        }
    }

    public static void _depthMask(final boolean mask) {
        // MODIFIED for porting: was iris's MixinGlStateManager_DepthColorOverride#iris$depthMaskLock (@Inject HEAD, cancellable)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.gl.blending.DepthColorStorage.isDepthColorLocked()) {
            net.irisshaders.iris.gl.blending.DepthColorStorage.deferDepthEnable(mask);
            return;
        }

        RenderSystem.assertOnRenderThread();
        if (mask != DEPTH.mask) {
            DEPTH.mask = mask;
            GL33C.glDepthMask(mask);
        }
    }

    // MODIFIED for porting: iris statelisteners MixinGlStateManager @Unique field plus its static initializer - iris
    // registers a notifier so its uniform holders can react to blend function changes.
    private static Runnable iris$blendFuncListener;

    static {
        net.irisshaders.iris.gl.state.StateUpdateNotifiers.blendFuncNotifier = listener -> iris$blendFuncListener = listener;
    }

    public static void _disableBlend(int index) {
        // MODIFIED for porting: was iris's MixinGlStateManager_BlendOverride#iris$blendDisableLock (@Inject HEAD, cancellable)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendLocked()) {
            net.irisshaders.iris.gl.blending.BlendModeStorage.deferBlendModeToggle(false);
            return;
        }

        RenderSystem.assertOnRenderThread();
        BLEND[index].mode.disable();
    }

    public static void _enableBlend(int index) {
        // MODIFIED for porting: was iris's MixinGlStateManager_BlendOverride#iris$blendEnableLock (@Inject HEAD, cancellable)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendLocked()) {
            net.irisshaders.iris.gl.blending.BlendModeStorage.deferBlendModeToggle(true);
            return;
        }

        RenderSystem.assertOnRenderThread();
        BLEND[index].mode.enable();
    }

    public static void _blendFuncSeparate(final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        // MODIFIED for porting: was iris's MixinGlStateManager_BlendOverride#iris$blendFuncSeparateLock
        // (@Inject HEAD, cancellable)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            if (net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendLocked()) {
                net.irisshaders.iris.gl.blending.BlendModeStorage.deferBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
                return;
            }

            if (net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendUnknown()) {
                BLEND[0].srcRgb = srcRgb;
                BLEND[0].dstRgb = dstRgb;
                BLEND[0].srcAlpha = srcAlpha;
                BLEND[0].dstAlpha = dstAlpha;
                glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                // MODIFIED for porting: iris statelisteners MixinGlStateManager#iris$onBlendFunc (@Inject RETURN)
                if (iris$blendFuncListener != null) {
                    iris$blendFuncListener.run();
                }

                return;
            }
        }

        RenderSystem.assertOnRenderThread();
        GlStateManager.BlendState firstBlend = BLEND[0];
        if (srcRgb != firstBlend.srcRgb || dstRgb != firstBlend.dstRgb || srcAlpha != firstBlend.srcAlpha || dstAlpha != firstBlend.dstAlpha) {
            firstBlend.srcRgb = srcRgb;
            firstBlend.dstRgb = dstRgb;
            firstBlend.srcAlpha = srcAlpha;
            firstBlend.dstAlpha = dstAlpha;
            glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    public static void _blendEquationSeparate(final int modeRgb, final int modeAlpha) {
        RenderSystem.assertOnRenderThread();
        GlStateManager.BlendState firstBlend = BLEND[0];
        if (modeRgb != firstBlend.modeRgb || modeAlpha != firstBlend.modeAlpha) {
            firstBlend.modeRgb = modeRgb;
            firstBlend.modeAlpha = modeAlpha;
            glBlendEquationSeparate(modeRgb, modeAlpha);
        }
    }

    public static int glGetProgrami(final int program, final int pname) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetProgrami(program, pname);
    }

    public static void glAttachShader(final int program, final int shader) {
        RenderSystem.assertOnRenderThread();
        GL33C.glAttachShader(program, shader);
    }

    public static void glDeleteShader(final int shader) {
        RenderSystem.assertOnRenderThread();
        GL33C.glDeleteShader(shader);
    }

    public static int glCreateShader(final int type) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glCreateShader(type);
    }

    public static void glShaderSource(final int shader, final String source) {
        RenderSystem.assertOnRenderThread();
        byte[] encoded = source.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = MemoryUtil.memAlloc(encoded.length + 1);
        buffer.put(encoded);
        buffer.put((byte)0);
        buffer.flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointers = stack.mallocPointer(1);
            pointers.put(buffer);
            GL33C.nglShaderSource(shader, 1, pointers.address0(), 0L);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public static void glCompileShader(final int shader) {
        RenderSystem.assertOnRenderThread();
        GL33C.glCompileShader(shader);
    }

    public static int glGetShaderi(final int shader, final int pname) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetShaderi(shader, pname);
    }

    // MODIFIED for porting: iris MixinGlStateManager_FramebufferBinding @Unique field
    private static int iris$program;

    public static void _glUseProgram(final int program) {
        // MODIFIED for porting: was iris's MixinGlStateManager_FramebufferBinding#iris$avoidRedundantBind2
        // (@Inject HEAD, cancellable) and MixinGlStateManager_DepthColorOverride#iris$resetTessellation (@Inject TAIL).
        // Note that upstream's HEAD injection cancels but still runs the two statements after the cancel (mixin callbacks keep
        // executing after ci.cancel()), so onProgramUse() and the field update happen either way.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            boolean irisRedundant = iris$program == 0 && program == 0;
            net.irisshaders.iris.gl.IrisRenderSystem.onProgramUse();
            iris$program = program;

            if (irisRedundant) {
                return;
            }
        }

        RenderSystem.assertOnRenderThread();
        GL33C.glUseProgram(program);
        // MODIFIED for porting: was iris's MixinGlStateManager_DepthColorOverride#iris$resetTessellation (@Inject TAIL)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.vertices.ImmediateState.usingTessellation = false;
        }
    }

    public static int glCreateProgram() {
        RenderSystem.assertOnRenderThread();
        return GL33C.glCreateProgram();
    }

    public static void glDeleteProgram(final int program) {
        RenderSystem.assertOnRenderThread();
        GL33C.glDeleteProgram(program);
    }

    public static void glLinkProgram(final int program) {
        RenderSystem.assertOnRenderThread();
        GL33C.glLinkProgram(program);
    }

    /**
     * MODIFIED for porting: was iris's MixinUniform#iris$glGetUniformLocation (@Inject RETURN, cancellable). It tries to make
     * texture unit 0 end up as the semantically default texture unit with iris's extended shaders. Upstream this lives in a
     * mixin named after {@code Uniform} to avoid a conflict with a sodium mixin, but it always targeted this method.
     */
    public static int _glGetUniformLocation(final int program, final CharSequence name) {
        RenderSystem.assertOnRenderThread();
        int returnValue = GL33C.glGetUniformLocation(program, name);
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return returnValue;
        }

        int location = returnValue;

        if (location == -1 && (name.equals("Sampler0") || name.equals("u_BlockTex"))) {
            location = _glGetUniformLocation(program, "tex");

            if (location == -1) {
                location = _glGetUniformLocation(program, "gtexture");

                if (location == -1) {
                    location = _glGetUniformLocation(program, "texture");

                    // TODO: If a shader samples from *any* sampler with a name that isn't known, then it should act like sampler 0.
                }
            }
        }

        if (location == -1 && name.equals("Sampler1")) {
            location = _glGetUniformLocation(program, "iris_overlay");
        }

        if (location == -1 && (name.equals("Sampler2") || name.equals("u_LightTex"))) {
            location = _glGetUniformLocation(program, "lightmap");
        }

        if (returnValue == -1 && location != -1) {
            return location;
        }

        return returnValue;
    }

    public static void _glUniform1i(final int location, final int v0) {
        RenderSystem.assertOnRenderThread();
        GL33C.glUniform1i(location, v0);
    }

    public static void _glBindAttribLocation(final int program, final int location, final CharSequence name) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBindAttribLocation(program, location, name);
    }

    static void incrementTrackedBuffers() {
        numBuffers++;
        PLOT_BUFFERS.setValue(numBuffers);
    }

    public static int _glGenBuffers() {
        RenderSystem.assertOnRenderThread();
        incrementTrackedBuffers();
        return GL33C.glGenBuffers();
    }

    public static int _glGenVertexArrays() {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGenVertexArrays();
    }

    public static void _glBindBuffer(final int target, final int buffer) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBindBuffer(target, buffer);
    }

    public static void _glBindVertexArray(final int arrayId) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBindVertexArray(arrayId);
    }

    public static void _glBufferData(final int target, final ByteBuffer data, final int usage) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBufferData(target, data, usage);
    }

    public static void _glBufferSubData(final int target, final long offset, final ByteBuffer data) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBufferSubData(target, offset, data);
    }

    public static void _glBufferData(final int target, final long size, final int usage) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBufferData(target, size, usage);
    }

    public static @Nullable ByteBuffer _glMapBufferRange(final int target, final long offset, final long length, final int access) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glMapBufferRange(target, offset, length, access);
    }

    public static void _glUnmapBuffer(final int target) {
        RenderSystem.assertOnRenderThread();
        GL33C.glUnmapBuffer(target);
    }

    public static void _glDeleteBuffers(final int buffer) {
        RenderSystem.assertOnRenderThread();
        numBuffers--;
        PLOT_BUFFERS.setValue(numBuffers);
        GL33C.glDeleteBuffers(buffer);
    }

    public static void _glBindFramebuffer(final int target, final int framebuffer) {
        if ((target == 36008 || target == 36160) && readFbo != framebuffer) {
            GL33C.glBindFramebuffer(36008, framebuffer);
            readFbo = framebuffer;
        }

        if ((target == 36009 || target == 36160) && writeFbo != framebuffer) {
            GL33C.glBindFramebuffer(36009, framebuffer);
            writeFbo = framebuffer;
        }
    }

    public static int getFrameBuffer(final int target) {
        if (target == 36008) {
            return readFbo;
        } else {
            return target == 36009 ? writeFbo : 0;
        }
    }

    public static void _glBlitFrameBuffer(
        final int srcX0,
        final int srcY0,
        final int srcX1,
        final int srcY1,
        final int dstX0,
        final int dstY0,
        final int dstX1,
        final int dstY1,
        final int mask,
        final int filter
    ) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public static void _glDeleteFramebuffers(final int framebuffer) {
        RenderSystem.assertOnRenderThread();
        GL33C.glDeleteFramebuffers(framebuffer);
        if (readFbo == framebuffer) {
            readFbo = 0;
        }

        if (writeFbo == framebuffer) {
            writeFbo = 0;
        }
    }

    public static int glGenFramebuffers() {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGenFramebuffers();
    }

    public static void _glFramebufferTexture2D(final int target, final int attachment, final int textarget, final int texture, final int level) {
        RenderSystem.assertOnRenderThread();
        GL33C.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static void glBlendFuncSeparate(final int srcColor, final int dstColor, final int srcAlpha, final int dstAlpha) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
    }

    public static void glBlendEquationSeparate(final int modeRgb, final int modeAlpha) {
        RenderSystem.assertOnRenderThread();
        GL33C.glBlendEquationSeparate(modeRgb, modeAlpha);
    }

    public static String glGetShaderInfoLog(final int shader, final int maxLength) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetShaderInfoLog(shader, maxLength);
    }

    public static String glGetProgramInfoLog(final int program, final int maxLength) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetProgramInfoLog(program, maxLength);
    }

    public static void _enableCull() {
        RenderSystem.assertOnRenderThread();
        CULL.enable.enable();
    }

    public static void _disableCull() {
        RenderSystem.assertOnRenderThread();
        CULL.enable.disable();
    }

    public static void _polygonMode(final int face, final int mode) {
        RenderSystem.assertOnRenderThread();
        GL33C.glPolygonMode(face, mode);
    }

    public static void _enablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        POLY_OFFSET.fill.enable();
    }

    public static void _disablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        POLY_OFFSET.fill.disable();
    }

    public static void _polygonOffset(final float factor, final float units) {
        RenderSystem.assertOnRenderThread();
        if (factor != POLY_OFFSET.factor || units != POLY_OFFSET.units) {
            POLY_OFFSET.factor = factor;
            POLY_OFFSET.units = units;
            GL33C.glPolygonOffset(factor, units);
        }
    }

    public static void _enableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        COLOR_LOGIC.enable.enable();
    }

    public static void _disableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        COLOR_LOGIC.enable.disable();
    }

    public static void _logicOp(final int op) {
        RenderSystem.assertOnRenderThread();
        if (op != COLOR_LOGIC.op) {
            COLOR_LOGIC.op = op;
            GL33C.glLogicOp(op);
        }
    }

    public static void _activeTexture(final int texture) {
        // MODIFIED for porting: was iris's MixinGlStateManager_FramebufferBinding#iris$checkActiveTexture (@Inject HEAD)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            int irisTex = texture - org.lwjgl.opengl.GL46C.GL_TEXTURE0;
            if (irisTex < 0 || irisTex > 128) {
                throw new IllegalArgumentException("Texture " + irisTex + " out of range");
            }
        }

        RenderSystem.assertOnRenderThread();
        if (activeTexture != texture - 33984) {
            activeTexture = texture - 33984;
            GL33C.glActiveTexture(texture);
        }
    }

    public static void _texParameter(final int target, final int name, final int value) {
        RenderSystem.assertOnRenderThread();
        GL33C.glTexParameteri(target, name, value);
    }

    public static int _getTexLevelParameter(final int target, final int level, final int name) {
        return GL33C.glGetTexLevelParameteri(target, level, name);
    }

    public static int _genTexture() {
        RenderSystem.assertOnRenderThread();
        numTextures++;
        PLOT_TEXTURES.setValue(numTextures);
        return GL33C.glGenTextures();
    }

    /**
     * MODIFIED for porting: was iris's texture MixinGlStateManager#iris$onDeleteTexture (@Inject TAIL) plus its @Unique helper.
     */
    public static void _deleteTexture(final int id) {
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.pbr.TextureTracker.INSTANCE.onDeleteTexture(id);
            net.irisshaders.iris.pbr.TextureInfoCache.INSTANCE.onDeleteTexture(id);
            net.irisshaders.iris.pbr.texture.PBRTextureManager.INSTANCE.onDeleteTexture(id);
        }

        RenderSystem.assertOnRenderThread();
        GL33C.glDeleteTextures(id);

        for (GlStateManager.TextureState state : TEXTURES) {
            if (state.binding == id) {
                state.binding = -1;
            }
        }

        numTextures--;
        PLOT_TEXTURES.setValue(numTextures);
    }

    public static void _bindTexture(final int id) {
        RenderSystem.assertOnRenderThread();
        if (id != TEXTURES[activeTexture].binding) {
            TEXTURES[activeTexture].binding = id;
            GL33C.glBindTexture(3553, id);
        }
    }

    public static void _texImage2D(
        final int target,
        final int level,
        final int internalformat,
        final int width,
        final int height,
        final int border,
        final int format,
        final int type,
        final @Nullable ByteBuffer pixels
    ) {
        RenderSystem.assertOnRenderThread();
        GL33C.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        // MODIFIED for porting: was iris's texture MixinGlStateManager#iris$onTexImage2D (@Inject TAIL) - iris caches the
        // dimensions/format of every texture so its PBR system can allocate matching normal/specular textures.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.pbr.TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
    }

    public static void _texSubImage2D(
        final int target,
        final int level,
        final int xoffset,
        final int yoffset,
        final int width,
        final int height,
        final int format,
        final int type,
        final long pixels
    ) {
        RenderSystem.assertOnRenderThread();
        GL33C.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    public static void _texSubImage2D(
        final int target,
        final int level,
        final int xoffset,
        final int yoffset,
        final int width,
        final int height,
        final int format,
        final int type,
        final ByteBuffer pixels
    ) {
        RenderSystem.assertOnRenderThread();
        GL33C.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    // MODIFIED for porting: sodium features.render.viewport GlStateManagerMixin @Unique fields
    private static int sodium$lastViewportX;
    private static int sodium$lastViewportY;
    private static int sodium$lastViewportWidth;
    private static int sodium$lastViewportHeight;

    public static void _viewport(final int x, final int y, final int width, final int height) {
        // MODIFIED for porting: sodium features.render.viewport GlStateManagerMixin#skipRedundantViewport
        // (@WrapWithCondition) - skip the driver call when the viewport did not change.
        if (x == sodium$lastViewportX && y == sodium$lastViewportY && width == sodium$lastViewportWidth && height == sodium$lastViewportHeight) {
            return;
        }

        sodium$lastViewportX = x;
        sodium$lastViewportY = y;
        sodium$lastViewportWidth = width;
        sodium$lastViewportHeight = height;
        GL33C.glViewport(x, y, width, height);
    }

    public static void _colorMask(final @ColorTargetState.WriteMask int writeMask) {
        // MODIFIED for porting: was iris's MixinGlStateManager_DepthColorOverride#iris$colorMaskLock(int)
        // (@Inject HEAD, cancellable)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.gl.blending.DepthColorStorage.isDepthColorLocked()) {
            net.irisshaders.iris.gl.blending.DepthColorStorage.deferColorMask(writeMask);
            return;
        }

        RenderSystem.assertOnRenderThread();

        for (int i = 0; i < COLOR_MASK.length; i++) {
            if (writeMask != COLOR_MASK[i]) {
                COLOR_MASK[i] = writeMask;
                GL33C.glColorMaski(i, (writeMask & 1) != 0, (writeMask & 2) != 0, (writeMask & 4) != 0, (writeMask & 8) != 0);
            }
        }
    }

    public static void _colorMask(final int index, final @ColorTargetState.WriteMask int writeMask) {
        // MODIFIED for porting: was iris's MixinGlStateManager_DepthColorOverride#iris$colorMaskLock(int,int)
        // (@Inject HEAD, cancellable)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.gl.blending.DepthColorStorage.isDepthColorLocked()) {
            net.irisshaders.iris.gl.blending.DepthColorStorage.deferColorMask(index, writeMask);
            return;
        }

        RenderSystem.assertOnRenderThread();
        if (writeMask != COLOR_MASK[index]) {
            COLOR_MASK[index] = writeMask;
            GL33C.glColorMaski(index, (writeMask & 1) != 0, (writeMask & 2) != 0, (writeMask & 4) != 0, (writeMask & 8) != 0);
        }
    }

    public static void _clear(final int mask) {
        RenderSystem.assertOnRenderThread();
        GL33C.glClear(mask);
        if (MacosUtil.IS_MACOS) {
            _getError();
        }
    }

    public static void _clearBuffer(final int index, final Vector4fc clearColor) {
        RenderSystem.assertOnRenderThread();
        GL33C.glClearBufferfv(6144, index, new float[]{clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w()});
        if (MacosUtil.IS_MACOS) {
            _getError();
        }
    }

    public static void _clearBuffer(final double clearDepth) {
        RenderSystem.assertOnRenderThread();
        GL33C.glClearBufferfv(6145, 0, new float[]{(float)clearDepth});
        if (MacosUtil.IS_MACOS) {
            _getError();
        }
    }

    public static void _vertexAttribPointer(final int index, final int size, final int type, final boolean normalized, final int stride, final long value) {
        RenderSystem.assertOnRenderThread();
        GL33C.glVertexAttribPointer(index, size, type, normalized, stride, value);
    }

    public static void _vertexAttribIPointer(final int index, final int size, final int type, final int stride, final long value) {
        RenderSystem.assertOnRenderThread();
        GL33C.glVertexAttribIPointer(index, size, type, stride, value);
    }

    public static void _enableVertexAttribArray(final int index) {
        RenderSystem.assertOnRenderThread();
        GL33C.glEnableVertexAttribArray(index);
    }

    public static void _drawElements(final int mode, final int count, final int type, final long indices) {
        RenderSystem.assertOnRenderThread();
        // MODIFIED for porting: was iris's MixinGlStateManager_DepthColorOverride#iris$modify (@Redirect on
        // GL33C#glDrawElements) - a shader pack's tessellation shaders need GL_PATCHES instead of GL_TRIANGLES.
        int effectiveMode = mode;
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && mode == org.lwjgl.opengl.GL43C.GL_TRIANGLES && net.irisshaders.iris.vertices.ImmediateState.usingTessellation) {
            effectiveMode = org.lwjgl.opengl.GL43C.GL_PATCHES;
        }

        GL33C.glDrawElements(effectiveMode, count, type, indices);
    }

    public static void _drawArrays(final int mode, final int first, final int count) {
        RenderSystem.assertOnRenderThread();
        GL33C.glDrawArrays(mode, first, count);
    }

    public static void _pixelStore(final int name, final int value) {
        RenderSystem.assertOnRenderThread();
        GL33C.glPixelStorei(name, value);
    }

    public static void _readPixels(final int x, final int y, final int width, final int height, final int format, final int type, final long pixels) {
        RenderSystem.assertOnRenderThread();
        GL33C.glReadPixels(x, y, width, height, format, type, pixels);
    }

    public static int _getError() {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetError();
    }

    public static void clearGlErrors() {
        RenderSystem.assertOnRenderThread();

        while (GL33C.glGetError() != 0) {
        }
    }

    public static String _getString(final int id) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetString(id);
    }

    public static int _getInteger(final int name) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glGetInteger(name);
    }

    public static long _glFenceSync(final int condition, final int flags) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glFenceSync(condition, flags);
    }

    public static int _glClientWaitSync(final long sync, final int flags, final long timeout) {
        RenderSystem.assertOnRenderThread();
        return GL33C.glClientWaitSync(sync, flags, timeout);
    }

    public static void _glDeleteSync(final long sync) {
        RenderSystem.assertOnRenderThread();
        GL33C.glDeleteSync(sync);
    }

    static {
        Arrays.setAll(COLOR_MASK, var0 -> 15);
        Arrays.setAll(BLEND, var0 -> new GlStateManager.BlendState());
    }

    @OnlyIn(Dist.CLIENT)
// MODIFIED for porting: iris.accesswidener makes GlStateManager$BlendState accessible
    public static class BlendState {
        public final GlStateManager.BooleanState mode = new GlStateManager.BooleanState(3042);
        public int srcRgb = 1;
        public int dstRgb = 0;
        public int modeRgb = 32774;
        public int srcAlpha = 1;
        public int dstAlpha = 0;
        public int modeAlpha = 32774;
    }

    @OnlyIn(Dist.CLIENT)
    // MODIFIED for porting: iris.accesswidener makes GlStateManager$BooleanState and its `enabled` field accessible, and
    // iris's statelisteners BooleanStateAccessor reads that field.
    public static class BooleanState implements net.irisshaders.iris.mixin.statelisteners.BooleanStateAccessor,
        net.irisshaders.iris.gl.BooleanStateExtended { // MODIFIED for porting: iris MixinBooleanState
        private final int state;
        private boolean enabled;

        // MODIFIED for porting: was iris's statelisteners BooleanStateAccessor @Accessor("enabled")
        @Override
        public boolean isEnabled() {
            return this.enabled;
        }

        public BooleanState(final int state) {
            this.state = state;
        }

        public void disable() {
            this.setEnabled(false);
        }

        public void enable() {
            this.setEnabled(true);
        }

        // MODIFIED for porting: iris MixinBooleanState @Unique field (its BooleanStateExtended implementation) - set when
        // iris changed the GL state behind GlStateManager's back, so the next setEnabled has to issue the call even though
        // the tracked value did not change.
        private boolean iris$stateUnknown;

        @Override
        public void setUnknownState() {
            this.iris$stateUnknown = true;
        }

        public void setEnabled(final boolean enabled) {
            // MODIFIED for porting: was iris's MixinBooleanState#iris$setUnknownState (@Inject HEAD, cancellable)
            if (this.iris$stateUnknown) {
                this.enabled = enabled;
                this.iris$stateUnknown = false;
                if (enabled) {
                    org.lwjgl.opengl.GL11.glEnable(this.state);
                } else {
                    org.lwjgl.opengl.GL11.glDisable(this.state);
                }

                return;
            }

            RenderSystem.assertOnRenderThread();
            if (enabled != this.enabled) {
                this.enabled = enabled;
                if (enabled) {
                    GL33C.glEnable(this.state);
                } else {
                    GL33C.glDisable(this.state);
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static class ColorLogicState {
        public final GlStateManager.BooleanState enable = new GlStateManager.BooleanState(3058);
        public int op = 5379;
    }

    @OnlyIn(Dist.CLIENT)
    private static class CullState {
        public final GlStateManager.BooleanState enable = new GlStateManager.BooleanState(2884);
    }

    @OnlyIn(Dist.CLIENT)
// MODIFIED for porting: iris.accesswidener makes GlStateManager$DepthState accessible
    public static class DepthState {
        public final GlStateManager.BooleanState mode = new GlStateManager.BooleanState(2929);
        public boolean mask = true;
        public int func = 513;
    }

    @OnlyIn(Dist.CLIENT)
    private static class PolygonOffsetState {
        public final GlStateManager.BooleanState fill = new GlStateManager.BooleanState(32823);
        public float factor;
        public float units;
    }

    @OnlyIn(Dist.CLIENT)
    private static class ScissorState {
        public final GlStateManager.BooleanState mode = new GlStateManager.BooleanState(3089);
    }

    @OnlyIn(Dist.CLIENT)
// MODIFIED for porting: iris.accesswidener makes GlStateManager$TextureState accessible
    public static class TextureState {
        public int binding;
    }
}