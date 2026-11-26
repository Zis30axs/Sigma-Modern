package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.impl.render.Freecam;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Tactical FPV drone OSD overlay for {@link Freecam} in {@link Freecam.ViewMode#FPV}:
 * scrolling compass azimuth tape with home bearing, tactical crosshair with dynamic pitch bars,
 * vertical altitude ladder with AGL / vertical speed, and airspeed / coordinate telemetry.
 */
public final class ModernFreecamFpv {
    private static final float SCALE_TEXT = 0.72F;
    private static final float SCALE_SMALL = 0.62F;
    private static final int SHADOW = 0xA8040A10;
    private static final int HOME_AMBER = 0xFFFFD879;

    private record Colors(int primary, int dim, int grid, int panel) {
        static Colors of(final Freecam.FpvTheme theme) {
            return switch (theme) {
                case TACTICAL -> new Colors(0xFF72FF84, 0xB85CD66D, 0x5539F24D, 0x94031108);
                case MONO -> new Colors(0xFFF2F8FC, 0xB8B8C7D4, 0x55B8C7D4, 0x940A0E14);
                case MODERN -> new Colors(0xFFAADEFF, 0xB88AB2CC, 0x558EBFD8, 0x9406131E);
            };
        }
    }

    private ModernFreecamFpv() {}

    public static void render(final GuiGraphicsExtractor g, final Freecam freecam, final float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (g == null || mc.player == null || mc.level == null || g.guiWidth() < 160 || g.guiHeight() < 120) {
            return;
        }

        Colors colors = Colors.of(freecam.getFpvTheme());
        Vec3 camPos = freecam.getPosition(partialTicks);
        float yaw = freecam.getYaw(partialTicks);
        float pitch = freecam.getPitch(partialTicks);
        float cx = g.guiWidth() * 0.5F;
        float cy = g.guiHeight() * 0.5F;
        float halfBracketW = Math.min(72.0F, g.guiWidth() * 0.16F);
        float halfBracketH = Math.min(46.0F, g.guiHeight() * 0.14F);

        g.nextStratum();
        g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
        try {
            renderAzimuthTape(g, colors, cx, cy, halfBracketH, yaw, camPos, mc.player.getPosition(partialTicks));
            renderReticleAndPitch(g, colors, cx, cy, halfBracketW, halfBracketH, pitch, mc.hitResult, camPos);
            renderAltitudeLadder(g, colors, cx + halfBracketW + 18.0F, cy, halfBracketH, camPos,
                    computeAboveGroundLevel(mc.level, camPos), freecam.getVerticalSpeedBlocksPerSecond());
            renderLeftTelemetry(g, colors, cx - halfBracketW - 18.0F, cy, halfBracketH, freecam, camPos,
                    mc.player.getEyePosition(partialTicks));
        } finally {
            g.disableScissor();
        }
        g.nextStratum();
    }

    private static void renderAzimuthTape(
        final GuiGraphicsExtractor g, final Colors c,
        final float cx, final float cy, final float halfBracketH,
        final float yaw, final Vec3 camPos, final Vec3 homePos
    ) {
        float topY = Math.min(cy - halfBracketH - 36.0F, Math.max(14.0F, ModernIsland.bottom() + 8.0F));
        topY = Math.max(8.0F, topY);
        float compassDeg = compassDegrees(yaw);
        int headingInt = Math.floorMod(Math.round(compassDeg), 360);
        String headingText = String.format(Locale.ROOT, "HDG %03d° %s", headingInt, cardinalLabel(headingInt));
        float pillW = Math.max(66.0F, measure(headingText, SCALE_TEXT) + 12.0F);
        drawBoxedPill(g, cx - pillW * 0.5F, topY, pillW, 13.0F, c);
        drawCentered(g, headingText, cx, topY + 2.5F, SCALE_TEXT, c.primary());

        float tapeW = Math.min(188.0F, Math.max(110.0F, cx * 1.4F));
        float tapeX0 = cx - tapeW * 0.5F;
        float tapeY = topY + 17.0F;
        float pxPerDeg = tapeW / 120.0F;

        ModernStyle.fill(g, Math.round(tapeX0), Math.round(tapeY), Math.round(tapeX0 + tapeW), Math.round(tapeY + 16.0F),
                (c.panel() & 0x00FFFFFF) | 0x58000000);
        RadarScopeDrawing.line(g, tapeX0, tapeY, tapeX0 + tapeW, tapeY, 0.8F, c.grid());
        // Centerlubber triangle lines pointing to the current heading.
        RadarScopeDrawing.line(g, cx - 3.0F, tapeY - 3.5F, cx, tapeY - 0.5F, 1.1F, c.primary());
        RadarScopeDrawing.line(g, cx + 3.0F, tapeY - 3.5F, cx, tapeY - 0.5F, 1.1F, c.primary());

        g.enableScissor(Math.round(tapeX0), Math.round(tapeY - 1.0F), Math.round(tapeX0 + tapeW), Math.round(tapeY + 18.0F));
        try {
            int startDeg = Math.floorDiv((int) Math.floor(compassDeg - 65.0F), 5) * 5;
            int endDeg = startDeg + 140;
            for (int deg = startDeg; deg <= endDeg; deg += 5) {
                float delta = shortestAngleDelta(compassDeg, deg);
                float tx = cx + delta * pxPerDeg;
                if (tx < tapeX0 - 12.0F || tx > tapeX0 + tapeW + 12.0F) continue;
                int normDeg = Math.floorMod(deg, 360);
                boolean major = normDeg % 15 == 0;
                float tickLen = major ? 5.5F : 3.0F;
                RadarScopeDrawing.line(g, tx, tapeY, tx, tapeY + tickLen, major ? 1.0F : 0.7F, major ? c.primary() : c.dim());
                if (major) {
                    String mark = normDeg % 45 == 0 ? cardinalLabel(normDeg) : String.format(Locale.ROOT, "%03d", normDeg);
                    drawCentered(g, mark, tx, tapeY + 7.2F, SCALE_SMALL, normDeg % 45 == 0 ? c.primary() : c.dim());
                }
            }

            double dxHome = homePos.x - camPos.x;
            double dzHome = homePos.z - camPos.z;
            if (dxHome * dxHome + dzHome * dzHome >= 2.25) {
                float homeBearing = bearingDegrees(camPos.x, camPos.z, homePos.x, homePos.z);
                float delta = shortestAngleDelta(compassDeg, homeBearing);
                float rawX = cx + delta * pxPerDeg;
                String marker = rawX < tapeX0 + 7.0F ? "<H" : rawX > tapeX0 + tapeW - 7.0F ? "H>" : "H";
                float hx = Math.clamp(rawX, tapeX0 + 8.0F, tapeX0 + tapeW - 8.0F);
                RadarScopeDrawing.line(g, hx, tapeY, hx, tapeY + 4.5F, 1.2F, HOME_AMBER);
                drawCentered(g, marker, hx, tapeY + 6.5F, SCALE_SMALL, HOME_AMBER);
            }
        } finally {
            g.disableScissor();
        }
    }

    private static void renderReticleAndPitch(
        final GuiGraphicsExtractor g, final Colors c,
        final float cx, final float cy, final float bw, final float bh,
        final float pitch, final HitResult hit, final Vec3 camPos
    ) {
        // Outer framing corner brackets matching ModernTrkFocus proportions.
        float arm = 10.0F;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float px = cx + sx * bw;
                float py = cy + sy * bh;
                RadarScopeDrawing.line(g, px, py, px - sx * arm, py, 1.0F, c.dim());
                RadarScopeDrawing.line(g, px, py, px, py - sy * arm, 1.0F, c.dim());
            }
        }

        // Central outlined reticle pip + open crosshair arms with mil subdivisions.
        ModernStyle.fill(g, Math.round(cx - 1.5F), Math.round(cy - 1.5F), Math.round(cx + 1.5F), Math.round(cy + 1.5F), SHADOW);
        ModernStyle.fill(g, Math.round(cx - 0.8F), Math.round(cy - 0.8F), Math.round(cx + 0.8F), Math.round(cy + 0.8F), c.primary());
        drawShadowedLine(g, cx - 15.0F, cy, cx - 5.0F, cy, c.primary());
        drawShadowedLine(g, cx + 5.0F, cy, cx + 15.0F, cy, c.primary());
        drawShadowedLine(g, cx, cy - 12.0F, cx, cy - 5.0F, c.primary());
        drawShadowedLine(g, cx, cy + 5.0F, cx, cy + 12.0F, c.primary());
        drawShadowedLine(g, cx - 10.0F, cy - 2.0F, cx - 10.0F, cy + 2.0F, c.dim());
        drawShadowedLine(g, cx + 10.0F, cy - 2.0F, cx + 10.0F, cy + 2.0F, c.dim());

        // Dynamic pitch horizon bars (-pitch is positive nose-up).
        float pitchOffset = Math.clamp(pitch * 0.65F, -bh + 12.0F, bh - 12.0F);
        float barY = cy + pitchOffset;
        for (int side = -1; side <= 1; side += 2) {
            float inner = cx + side * 24.0F;
            float outer = cx + side * 38.0F;
            drawShadowedLine(g, inner, barY, outer, barY, c.grid());
            drawShadowedLine(g, inner, barY, inner, barY + (pitch >= 0 ? -3.5F : 3.5F), c.grid());
        }
        drawText(g, String.format(Locale.ROOT, "%+.0f°", -pitch), cx + 41.0F, barY - 3.2F, SCALE_SMALL, c.dim());

        String rangeText = hit != null && hit.getType() != HitResult.Type.MISS
                ? String.format(Locale.ROOT, "RNG %.1fm", hit.getLocation().distanceTo(camPos))
                : "RNG ---";
        drawCentered(g, rangeText, cx, cy + 17.0F, SCALE_SMALL, c.dim());
    }

    private static void renderAltitudeLadder(
        final GuiGraphicsExtractor g, final Colors c,
        final float ladderX, final float cy, final float bh,
        final Vec3 camPos, final double agl, final double verticalSpeed
    ) {
        float ladderH = Math.max(60.0F, bh * 1.9F);
        float topY = cy - ladderH * 0.5F;
        float bottomY = cy + ladderH * 0.5F;
        float pxPerBlock = ladderH / 24.0F;

        RadarScopeDrawing.line(g, ladderX, topY, ladderX, bottomY, 0.8F, c.grid());
        g.enableScissor(Math.round(ladderX - 2.0F), Math.round(topY), Math.round(ladderX + 54.0F), Math.round(bottomY));
        try {
            int minBlock = (int) Math.floor(camPos.y - 13.0);
            int maxBlock = (int) Math.ceil(camPos.y + 13.0);
            for (int yBlock = minBlock; yBlock <= maxBlock; yBlock++) {
                float py = cy - (float) (yBlock - camPos.y) * pxPerBlock;
                if (py < topY - 6.0F || py > bottomY + 6.0F) continue;
                boolean major = Math.floorMod(yBlock, 5) == 0;
                RadarScopeDrawing.line(g, ladderX, py, ladderX + (major ? 6.0F : 3.0F), py, major ? 1.0F : 0.7F,
                        major ? c.primary() : c.dim());
                if (major) {
                    drawText(g, Integer.toString(yBlock), ladderX + 8.5F, py - 3.0F, SCALE_SMALL, c.dim());
                }
            }
        } finally {
            g.disableScissor();
        }

        // Center altitude digital readout box.
        String altText = String.format(Locale.ROOT, "Y %.1f", camPos.y);
        float boxW = Math.max(40.0F, measure(altText, SCALE_TEXT) + 10.0F);
        RadarScopeDrawing.line(g, ladderX, cy, ladderX + 6.0F, cy, 1.1F, c.primary());
        drawBoxedPill(g, ladderX + 7.0F, cy - 6.5F, boxW, 13.0F, c);
        drawText(g, altText, ladderX + 12.0F, cy - 4.0F, SCALE_TEXT, c.primary());

        String aglText = Double.isFinite(agl) ? String.format(Locale.ROOT, "AGL %.1fm", agl) : "AGL ---";
        String vsText = String.format(Locale.ROOT, "VS %+.1f", verticalSpeed);
        drawText(g, aglText, ladderX + 4.0F, bottomY + 5.0F, SCALE_SMALL, c.primary());
        drawText(g, vsText, ladderX + 4.0F, bottomY + 14.0F, SCALE_SMALL, c.dim());
    }

    private static void renderLeftTelemetry(
        final GuiGraphicsExtractor g, final Colors c,
        final float rightEdgeX, final float cy, final float bh,
        final Freecam freecam, final Vec3 camPos, final Vec3 playerPos
    ) {
        String speedText = String.format(Locale.ROOT, "%.1f m/s", freecam.getSpeedBlocksPerSecond());
        float boxW = Math.max(44.0F, measure(speedText, SCALE_TEXT) + 10.0F);
        RadarScopeDrawing.line(g, rightEdgeX - 6.0F, cy, rightEdgeX, cy, 1.1F, c.primary());
        drawBoxedPill(g, rightEdgeX - 7.0F - boxW, cy - 6.5F, boxW, 13.0F, c);
        drawRight(g, speedText, rightEdgeX - 12.0F, cy - 4.0F, SCALE_TEXT, c.primary());
        drawRight(g, String.format(Locale.ROOT, "GEAR ×%.1f", freecam.getConfiguredSpeed()),
                rightEdgeX - 7.0F, cy + 9.5F, SCALE_SMALL, c.dim());

        float bottomY = cy + Math.max(60.0F, bh * 1.9F) * 0.5F;
        String modeTag = freecam.isPlayerControl() ? "FPV · HOLD" : freecam.isNoClip() ? "FPV · NOCLIP" : "FPV · COLLIDE";
        double distHome = camPos.distanceTo(playerPos);
        drawRight(g, modeTag, rightEdgeX - 4.0F, bottomY + 5.0F, SCALE_SMALL, c.primary());
        drawRight(g, String.format(Locale.ROOT, "DIST %.1fm", distHome), rightEdgeX - 4.0F, bottomY + 14.0F, SCALE_SMALL, c.dim());
        drawRight(g, String.format(Locale.ROOT, "%d / %d / %d",
                        Mth.floor(camPos.x), Mth.floor(camPos.y), Mth.floor(camPos.z)),
                rightEdgeX - 4.0F, bottomY + 23.0F, SCALE_SMALL, c.dim());
    }

    private static void drawBoxedPill(
        final GuiGraphicsExtractor g, final float x, final float y, final float w, final float h, final Colors c
    ) {
        ModernStyle.fill(g, Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), c.panel());
        RadarScopeDrawing.box(g, x, y, w, h, 0.85F, c.grid());
    }

    private static void drawShadowedLine(
        final GuiGraphicsExtractor g, final float x1, final float y1, final float x2, final float y2, final int color
    ) {
        RadarScopeDrawing.line(g, x1, y1, x2, y2, 2.1F, SHADOW);
        RadarScopeDrawing.line(g, x1, y1, x2, y2, 1.0F, color);
    }

    private static void drawText(
        final GuiGraphicsExtractor g, final String value, final float x, final float y, final float scale, final int color
    ) {
        ModernTypography.draw(g, ModernTypography.Face.TEXT, value, x + 0.6F, y + 0.6F, scale, SHADOW);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, value, x, y, scale, color);
    }

    private static void drawCentered(
        final GuiGraphicsExtractor g, final String value, final float cx, final float y, final float scale, final int color
    ) {
        drawText(g, value, cx - measure(value, scale) * 0.5F, y, scale, color);
    }

    private static void drawRight(
        final GuiGraphicsExtractor g, final String value, final float rightX, final float y, final float scale, final int color
    ) {
        drawText(g, value, rightX - measure(value, scale), y, scale, color);
    }

    private static float measure(final String value, final float scale) {
        return ModernTypography.width(ModernTypography.Face.TEXT, value, scale);
    }

    /** Converts Minecraft yaw (0 = South +Z, 90 = West -X, -180 = North -Z) into compass degrees [0, 360). */
    static float compassDegrees(final float minecraftYaw) {
        float mod = (minecraftYaw + 180.0F) % 360.0F;
        return mod < 0.0F ? mod + 360.0F : mod;
    }

    /** Signed shortest angular offset in [-180, 180) moving clockwise from {@code fromDeg} to {@code toDeg}. */
    static float shortestAngleDelta(final float fromDeg, final float toDeg) {
        return Mth.wrapDegrees(toDeg - fromDeg);
    }

    /** Returns 8-wind cardinal abbreviation for a compass heading in degrees. */
    static String cardinalLabel(final int degrees) {
        int sector = Math.floorMod(Math.round(degrees / 45.0F), 8);
        return switch (sector) {
            case 0 -> "N";
            case 1 -> "NE";
            case 2 -> "E";
            case 3 -> "SE";
            case 4 -> "S";
            case 5 -> "SW";
            case 6 -> "W";
            default -> "NW";
        };
    }

    /** Computes compass bearing [0, 360) from {@code (fromX, fromZ)} toward {@code (toX, toZ)}. */
    static float bearingDegrees(final double fromX, final double fromZ, final double toX, final double toZ) {
        double deg = Math.toDegrees(Math.atan2(toX - fromX, -(toZ - fromZ)));
        float mod = (float) (deg % 360.0);
        return mod < 0.0F ? mod + 360.0F : mod;
    }

    /** Scans downward from camera position to find height above ground level (AGL), or {@code NaN} if void. */
    static double computeAboveGroundLevel(final ClientLevel level, final Vec3 camPos) {
        int bx = Mth.floor(camPos.x);
        int bz = Mth.floor(camPos.z);
        int minY = level.getMinY();
        int startY = Math.min(level.getMaxY(), Mth.floor(camPos.y));
        if (startY < minY) return Double.NaN;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int floorLimit = Math.max(minY, startY - 128);
        for (int y = startY; y >= floorLimit; y--) {
            cursor.set(bx, y, bz);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (!shape.isEmpty()) {
                return Math.max(0.0, camPos.y - (y + shape.max(Direction.Axis.Y)));
            }
        }
        return Double.NaN;
    }
}
