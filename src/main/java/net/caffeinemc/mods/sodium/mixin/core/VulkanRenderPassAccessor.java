package net.caffeinemc.mods.sodium.mixin.core;

import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface VulkanRenderPassAccessor {
    VulkanRenderPipeline sodium$getPipeline();

    VkCommandBuffer sodium$getCommandBuffer();
}
