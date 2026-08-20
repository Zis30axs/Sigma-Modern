package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

public enum ShaderPackBackend {
    OPENGL("OpenGL"),
    VULKAN("Vulkan"),
    UNKNOWN("Unavailable");

    private final String displayName;

    ShaderPackBackend(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }

    public boolean supportsCustomShaderPipelines() {
        return this == OPENGL || this == VULKAN;
    }

    public static ShaderPackBackend current() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return UNKNOWN;
        }

        return switch (device.getDeviceInfo().backendName()) {
            case "OpenGL" -> OPENGL;
            case "Vulkan" -> VULKAN;
            default -> UNKNOWN;
        };
    }
}
