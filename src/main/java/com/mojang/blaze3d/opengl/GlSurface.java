package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class GlSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    private final long windowHandle;
    private int swapchainWidth;
    private int swapchainHeight;

    public GlSurface(final long windowHandle) {
        this.windowHandle = windowHandle;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        // MODIFIED for porting: was sodium-extra's adaptive_sync MixinGlSurface#setSwapInterval
        // (@Redirect on GLFW#glfwSwapInterval) - FIFO_RELAXED maps to GLFW's adaptive vsync (-1).
        if (config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED && sodiumExtra$usesAdaptiveSync()) {
            GLFW.glfwSwapInterval(-1);
        } else {
            GLFW.glfwSwapInterval(config.presentMode() == GpuSurface.PresentMode.FIFO ? 1 : 0);
        }

        this.swapchainWidth = config.width();
        this.swapchainHeight = config.height();
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
        ((GlCommandEncoder)commandEncoder).presentTexture(textureView, this.swapchainWidth, this.swapchainHeight);
    }

    @Override
    public void present() {
        GLFW.glfwSwapBuffers(this.windowHandle);
        // MODIFIED for porting: sodium workarounds.context_creation GlSurfaceMixin#preSwapBuffers (RETURN)
        sodium$preSwapBuffers();
    }

    // MODIFIED for porting: everything below was sodium's workarounds.context_creation GlSurfaceMixin
    private static final org.slf4j.Logger SODIUM_LOGGER = org.slf4j.LoggerFactory.getLogger("Sodium-GlSurface");

    private static long sodium$wglPrevContext;

    private static boolean sodium$hasDonePostLaunchChecks = false;

    private static void sodium$doChecksOnce() {
        if (sodium$hasDonePostLaunchChecks) {
            return;
        }

        // note the position of this assignment is here to prevent checkModules from running twice when the game renders the
        // last frame before shutting down after checkModules throws an exception and aborts control flow
        sodium$hasDonePostLaunchChecks = true;
        SODIUM_LOGGER.info(String.valueOf(Thread.currentThread()));
        net.caffeinemc.mods.sodium.client.platform.NativeWindowHandle handle = () -> org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(
            net.minecraft.client.Minecraft.getInstance().getWindow().handle()
        );
        if (com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName().contains("OpenGL")) {
            net.caffeinemc.mods.sodium.client.compatibility.environment.GlContextInfo context =
                net.caffeinemc.mods.sodium.client.compatibility.environment.GlContextInfo.create();
            SODIUM_LOGGER.info("OpenGL Vendor: {}", context.vendor());
            SODIUM_LOGGER.info("OpenGL Renderer: {}", context.renderer());
            SODIUM_LOGGER.info("OpenGL Version: {}", context.version());
            net.caffeinemc.mods.sodium.client.compatibility.checks.PostLaunchChecks.onContextInitialized(handle, context);
        }

        net.caffeinemc.mods.sodium.client.compatibility.checks.ModuleScanner.checkModules(handle);
    }

    private static void sodium$preSwapBuffers() {
        sodium$doChecksOnce();
        // wglGetCurrentContext is only applicable on Windows
        if (net.minecraft.util.Util.getPlatform() != net.minecraft.util.Util.OS.WINDOWS
            || !com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName().contains("OpenGL")) {
            return;
        }

        if (sodium$wglPrevContext == org.lwjgl.system.MemoryUtil.NULL) {
            // There is no prior recorded context. Record it.
            sodium$wglPrevContext = org.lwjgl.opengl.WGL.wglGetCurrentContext(null);
            return;
        }

        long currentWglContext = org.lwjgl.opengl.WGL.wglGetCurrentContext(null);
        if (sodium$wglPrevContext == currentWglContext) {
            // The context has not changed.
            return;
        }

        // record the current context for the next check, we do this here to prevent a duplicate call to checkModules when
        // the game renders on last frame before shutting down after checkModules throws an exception
        sodium$wglPrevContext = currentWglContext;
        // Something has decided to replace the OpenGL context, which is not a good sign
        SODIUM_LOGGER.warn("The OpenGL context appears to have been suddenly replaced! Something has likely just injected into the game process.");
        // Likely, this indicates a module was injected into the current process. We should check that nothing problematic
        // was just installed.
        net.caffeinemc.mods.sodium.client.compatibility.checks.ModuleScanner.checkModules(
            () -> org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(net.minecraft.client.Minecraft.getInstance().getWindow().handle())
        );
    }

    @Override
    public void close() {
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        // MODIFIED for porting: was sodium-extra's adaptive_sync MixinGlSurface#addFifoRelaxedPresentMode
        // (@Inject RETURN, cancellable)
        if (sodiumExtra$usesAdaptiveSync()) {
            java.util.EnumSet<GpuSurface.PresentMode> modes = java.util.EnumSet.copyOf(SUPPORTED_PRESENT_MODES);
            modes.add(GpuSurface.PresentMode.FIFO_RELAXED);
            return modes;
        }

        return SUPPORTED_PRESENT_MODES;
    }

    // MODIFIED for porting: was sodium-extra's adaptive_sync MixinGlSurface#sodiumExtra$usesAdaptiveSync (@Unique)
    private static boolean sodiumExtra$usesAdaptiveSync() {
        return me.flashyreese.mods.sodiumextra.client.config.SodiumExtraFeatures.ADAPTIVE_SYNC
            && me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod.options().extraSettings.useAdaptiveSync
            && me.flashyreese.mods.sodiumextra.client.config.SodiumExtraGameOptions.VerticalSyncOption.isAdaptiveSyncSupported();
    }
}