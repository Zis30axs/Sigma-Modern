package com.mentalfrostbyte.jello.util.game.render;

import com.mentalfrostbyte.jello.gui.modern.SuspectNameTagTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

/** A warning SVG in the vanilla name-tag pass, with that pass's light, alpha, depth and Iris entity context. */
public final class SuspectNameTagIcon {
    public static final float SIZE = 9.0F;
    public static final float ADVANCE = SIZE + 2.0F;
    private static final int COLOR = 0x00FF5555;

    private SuspectNameTagIcon() {}

    public static void render(Matrix4fc pose, float nameX, float y, int nameColor, int light,
                              Font.DisplayMode displayMode, Function<RenderType, VertexConsumer> buffers) {
        Identifier texture = SuspectNameTagTexture.texture();
        if (texture == null) return;
        RenderType renderType = displayMode == Font.DisplayMode.SEE_THROUGH
                ? RenderTypes.textSeeThrough(texture) : RenderTypes.text(texture);
        VertexConsumer buffer = buffers.apply(renderType);
        int color = (nameColor & 0xFF000000) | COLOR;
        float x = nameX - ADVANCE;
        // Same winding and z plane as BakedSheetGlyph; the negative billboard y scale is already in pose.
        buffer.addVertex(pose, x, y, 0.0F).setColor(color).setUv(0.0F, 0.0F).setLight(light);
        buffer.addVertex(pose, x, y + SIZE, 0.0F).setColor(color).setUv(0.0F, 1.0F).setLight(light);
        buffer.addVertex(pose, x + SIZE, y + SIZE, 0.0F).setColor(color).setUv(1.0F, 1.0F).setLight(light);
        buffer.addVertex(pose, x + SIZE, y, 0.0F).setColor(color).setUv(1.0F, 0.0F).setLight(light);
    }
}
