package net.minecraft.client.renderer.debug;

import com.google.common.collect.Maps;
import com.viaversion.viafabricplus.injection.access.networking.packet_handling.IGameTestBlockHighlightRenderer;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GameTestBlockHighlightRenderer implements IGameTestBlockHighlightRenderer {
    private static final int SHOW_POS_DURATION_MS = 10000;
    private static final float PADDING = 0.02F;
    private final Map<BlockPos, GameTestBlockHighlightRenderer.Marker> markers = Maps.newHashMap();

    public void highlightPos(final BlockPos absolutePos, final BlockPos relativePos) {
        String text = relativePos.toShortString();
        this.markers.put(absolutePos, new GameTestBlockHighlightRenderer.Marker(1610678016, text, Util.getMillis() + 10000L));
    }

    // MODIFIED for porting: was VFP networking/packet_handling MixinGameTestBlockHighlightRenderer, which implements
    // IGameTestBlockHighlightRenderer. No version gate here; the gate lives in the game-test debug payload handler.
    // 26.2 renamed the record component, so upstream's 'message' lands in Marker#text.
    @Override
    public void viaFabricPlus$addMarker(final BlockPos pos, final int color, final String message, final int duration) {
        this.markers.put(pos, new GameTestBlockHighlightRenderer.Marker(color, message, Util.getMillis() + duration));
    }

    public void clear() {
        this.markers.clear();
    }

    public void emitGizmos() {
        long time = Util.getMillis();
        this.markers.entrySet().removeIf(entry -> time > entry.getValue().removeAtTime);
        this.markers.forEach((pos, marker) -> this.renderMarker(pos, marker));
    }

    private void renderMarker(final BlockPos pos, final GameTestBlockHighlightRenderer.Marker marker) {
        Gizmos.cuboid(pos, 0.02F, GizmoStyle.fill(marker.color()));
        if (!marker.text.isEmpty()) {
            Gizmos.billboardText(marker.text, Vec3.atLowerCornerWithOffset(pos, 0.5, 1.2, 0.5), TextGizmo.Style.whiteAndCentered().withScale(0.16F))
                .setAlwaysOnTop();
        }
    }

    @OnlyIn(Dist.CLIENT)
    public record Marker(int color, String text, long removeAtTime) {
    }
}