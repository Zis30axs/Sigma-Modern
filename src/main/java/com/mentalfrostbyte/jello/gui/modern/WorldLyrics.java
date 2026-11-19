package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Each sung line gets a place in the local world. Only immutable text and poses cross into submission. */
public final class WorldLyrics {
    private static final int FADE_IN_MS = 230, FADE_OUT_MS = 360;
    private static final float SCALE = 0.014F;
    private @Nullable ClientLevel level;
    private @Nullable Track track;
    private long lastPosition, entered, retired;
    private int index = -1;
    private Vec3 origin = Vec3.ZERO, anchor = Vec3.ZERO;
    private @Nullable Caption current, previous;
    private Lyrics.@Nullable Line cachedLine;
    private @Nullable String cachedExtra;
    private int cachedLit;
    private List<Row> rows = List.of();

    private record Row(FormattedCharSequence text, float width, float y, float scale, int color) {}
    private record Caption(Vec3 anchor, List<Row> rows, float opacity, float rise) {
        void submit(PoseStack pose, SubmitNodeCollector output, CameraRenderState camera) {
            double distance = camera.pos.distanceTo(anchor);
            float proximity = (float)Math.clamp((distance - 0.30) / 0.65, 0.0, 1.0);
            int alpha = Math.round(255 * opacity * proximity);
            if (alpha < 5) return;
            pose.pushPose();
            pose.translate(anchor.x - camera.pos.x, anchor.y - camera.pos.y + rise, anchor.z - camera.pos.z);
            pose.mulPose(camera.orientation);
            float size = SCALE * (float)Math.clamp(distance / 2.7, 0.45, 1.0);
            pose.scale(size, -size, size);
            for (Row row : rows) {
                pose.pushPose();
                pose.translate(0, row.y, 0);
                pose.scale(row.scale, row.scale, row.scale);
                output.submitText(pose, -row.width / 2, 0, row.text, true, Font.DisplayMode.NORMAL,
                        LightCoordsUtil.FULL_BRIGHT, alpha << 24 | row.color, 0, 0);
                pose.popPose();
            }
            pose.popPose();
        }
    }

    public static final class Frame {
        private final @Nullable Caption old, active;
        private Frame(@Nullable Caption old, @Nullable Caption active) { this.old = old; this.active = active; }
        public void submit(PoseStack pose, SubmitNodeCollector output, CameraRenderState camera) {
            if (old != null) old.submit(pose, output, camera);
            if (active != null) active.submit(pose, output, camera);
        }
    }

    public void clear() {
        level = null;
        track = null;
        current = previous = null;
        index = -1;
        cachedLine = null;
        cachedExtra = null;
        rows = List.of();
    }

    public @Nullable Frame extract(Minecraft mc, CameraRenderState camera, float partialTick) {
        Client client = Client.getInstance();
        if (!client.isStarted() || mc.level == null || mc.player == null || camera.isPanoramicMode
                || mc.getCameraEntity() != mc.player || !client.getMusicLibrary().lyrics().worldLyrics()) {
            if (level != null) clear();
            return null;
        }
        var player = client.getMusicPlayer();
        if (!player.isPlaying() || player.isBuffering()) {
            if (level != null) clear();
            return null;
        }
        long pos = player.positionMs();
        Vec3 eye = mc.player.getEyePosition(partialTick);
        boolean discontinuity = level != mc.level || track != player.current() || pos < lastPosition
                || pos - lastPosition > 1200 || eye.distanceToSqr(origin) > 16;
        if (discontinuity) {
            clear();
            level = mc.level;
            track = player.current();
            origin = eye;
        }
        lastPosition = pos;
        var now = ModernLyricSweep.now(player, pos);
        int next = now == null ? -1 : now.index();
        if (next != index) {
            if (current != null) {
                previous = current;
                retired = pos;
            }
            current = null;
            index = next;
            entered = pos;
            if (now != null) {
                origin = eye;
                anchor = place(mc, eye, camera.yRot, index);
            }
        }
        if (now != null) {
            var line = now.line();
            String text = line.text();
            String extra = LyricsService.extra(line, client.getMusicLibrary().lyrics().language());
            int lit = boundary(text, now.progress());
            if (line != cachedLine || lit != cachedLit || !Objects.equals(extra, cachedExtra)) {
                rows = layout(mc.font, text, lit, extra);
                cachedLine = line;
                cachedLit = lit;
                cachedExtra = extra;
            }
            float shown = ease(pos - entered, FADE_IN_MS);
            current = new Caption(anchor, rows, shown, -0.12F * (1 - shown));
        }
        Caption ghost = null;
        if (previous != null) {
            float out = Math.clamp((pos - retired) / (float)FADE_OUT_MS, 0F, 1F);
            if (out >= 1F) previous = null;
            else ghost = new Caption(previous.anchor, previous.rows, previous.opacity * (1 - out), previous.rise + 0.26F * out);
        }
        return current == null && ghost == null ? null : new Frame(ghost, current);
    }

    static float ease(long elapsed, int duration) {
        float t = Math.clamp(elapsed / (float)duration, 0F, 1F);
        return t * (2 - t);
    }

    // The lyric service measures progress in UTF-16 units. Never split a supplementary glyph in half.
    static int boundary(String text, float progress) {
        int n = Math.clamp((int)(progress * text.length()), 0, text.length());
        return n > 0 && n < text.length() && Character.isLowSurrogate(text.charAt(n))
                && Character.isHighSurrogate(text.charAt(n - 1)) ? n - 1 : n;
    }

    static Vec3 target(Vec3 eye, float yaw, int index) {
        double a = Math.toRadians(yaw);
        double forward = 3.0, right = (index & 1) == 0 ? 0.85 : -0.85;
        return eye.add(-Math.sin(a) * forward - Math.cos(a) * right, -0.40,
                Math.cos(a) * forward - Math.sin(a) * right);
    }

    private static Vec3 place(Minecraft mc, Vec3 eye, float yaw, int index) {
        Vec3 target = target(eye, yaw, index);
        var hit = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return target;
        Vec3 other = target(eye, yaw, index ^ 1);
        var alternate = mc.level.clip(new ClipContext(eye, other, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (alternate.getType() == HitResult.Type.MISS) return other;
        Vec3 surface = hit.getLocation().distanceToSqr(eye) >= alternate.getLocation().distanceToSqr(eye)
                ? hit.getLocation() : alternate.getLocation();
        Vec3 delta = surface.subtract(eye);
        return eye.add(delta.normalize().scale(Math.max(0, delta.length() - 0.18)));
    }

    private static List<Row> layout(Font font, String text, int lit, @Nullable String extra) {
        var main = Component.empty().append(Component.literal(text.substring(0, lit)).withColor(0xE2FAFF))
                .append(Component.literal(text.substring(lit)).withColor(0x8395A7));
        List<Row> result = new ArrayList<>();
        float y = 0F;
        for (var row : font.split(main, 158)) {
            result.add(new Row(row, font.width(row), y, 1F, 0xFFFFFF));
            y += 11F;
        }
        if (extra != null && !extra.isBlank()) {
            y += 2F;
            for (var row : font.split(Component.literal(extra), 206)) {
                result.add(new Row(row, font.width(row), y, 0.76F, 0xABC5D6));
                y += 8.5F;
            }
        }
        return List.copyOf(result);
    }
}
