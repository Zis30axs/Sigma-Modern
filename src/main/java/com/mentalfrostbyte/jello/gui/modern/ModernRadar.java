package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Azimuth/range B-scope with separate search, acquisition and tracking behavior. */
public final class ModernRadar {
    private static final int WIDTH = 218, HEIGHT = 212;
    private static final int LEFT = 27, TOP = 42, PLOT_W = 164, PLOT_H = 127;
    private static InputConstants.@Nullable Key consumedKey;
    private static long noContactUntil;
    private static WeakReference<Object> feedbackWorld = new WeakReference<>(null);
    private static long feedbackSession = Long.MIN_VALUE;
    private record Blip(float x, float y, RadarContacts.Contact contact, double distance, double closing, float alpha) {}

    private ModernRadar() {}

    /** Called at the raw input boundary so Alt+F can close the Alt editor and cannot swap the off-hand item. */
    public static boolean handleKey(KeyEvent event, int action) {
        InputConstants.Key key = InputConstants.getKey(event);
        if (key.equals(consumedKey)) {
            if (action == GLFW.GLFW_RELEASE) { consumedKey = null; return true; }
            if (action == GLFW.GLFW_REPEAT) return true;
            consumedKey = null;
        }
        Minecraft mc = Minecraft.getInstance();
        Radar radar = Modules.enabled(Radar.class);
        if (action != GLFW.GLFW_PRESS || radar == null || !radar.matchesLockKey(event)
                || mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()
                || !mc.isWindowActive() || mc.gui.overlay() != null || !ModernHud.isActive()
                || !ModernHudEditor.allowsHud() || !ModernHudEditor.isEditing() && !mc.mouseHandler.isMouseGrabbed()) return false;
        ModernHudEditor.finishForShortcut();
        updateFeedback(mc, radar);
        boolean changed = RadarContacts.toggleTrack(mc, radar);
        noContactUntil = changed ? 0 : mc.level.getGameTime() + 30;
        consumedKey = key;
        return true;
    }

    private static void updateFeedback(Minecraft mc, Radar radar) {
        if (feedbackWorld.get() != mc.level || feedbackSession != radar.getSession()) {
            feedbackWorld = new WeakReference<>(mc.level);
            feedbackSession = radar.getSession();
            noContactUntil = 0;
        }
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Radar options = Modules.enabled(Radar.class);
        boolean editing = ModernHudEditor.isEditing();
        if (options == null || mc.player == null || mc.level == null || !ModernHud.isActive()
                || !ModernHudEditor.allowsHud() || mc.gui.hud.isHidden() && !editing) return;
        float scale = RadarScopeDrawing.scale(options.getScale(), WIDTH, HEIGHT, g);
        if (scale <= 0F) return;
        updateFeedback(mc, options);
        var bounds = ModernHudEditor.place(g, "radar", "Radar", g.guiWidth() - WIDTH * scale - 10, g.guiHeight() - HEIGHT * scale - 34,
                WIDTH * scale, HEIGHT * scale);
        RadarContacts.Frame frame = RadarContacts.snapshots(mc, options);
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 origin = mc.player.getPosition(partial);
        Vec3 velocity = mc.player.position().subtract(new Vec3(mc.player.xo, mc.player.yo, mc.player.zo));
        float yaw = mc.player.getYRot(partial);
        List<Blip> blips = new ArrayList<>();
        for (RadarContacts.Contact contact : frame.contacts()) {
            // Stored SRC echoes stay where the beam saw them. Tracked contacts interpolate the latest tick.
            Vec3 position = options.getMode() == Radar.Mode.SRC && !frame.tracking() ? contact.position()
                    : contact.position().subtract(contact.velocity().scale(1 - partial));
            Vec3 offset = position.subtract(origin);
            RadarGeometry.Relative relative = RadarGeometry.relative(offset, yaw);
            float scanAngle = frame.tracking() ? 180F : options.getScanAngle();
            double distance = offset.length();
            if (relative == null || !Double.isFinite(distance) || distance > options.getRange()
                    || !(frame.tracking() && relative.distance() < 1.0E-8)
                    && !RadarGeometry.withinScan(relative, options.getRange(), scanAngle)) continue;
            float x = LEFT + (float) (0.5 + relative.bearingDegrees() / scanAngle) * PLOT_W;
            float y = TOP + PLOT_H * (1F - (float) (distance / options.getRange()));
            float age = (mc.level.getGameTime() - contact.seenTick() + partial) / (options.getScanPeriod() * 20F);
            float alpha = options.getMode() == Radar.Mode.SRC && !frame.tracking() ? Math.clamp(1.1F - age, 0.18F, 1F) : 1F;
            blips.add(new Blip(x, y, contact, distance, RadarGeometry.closure(offset, contact.velocity().subtract(velocity)) * 20, alpha));
        }
        boolean preview = editing && blips.isEmpty();
        if (preview) {
            blips.add(new Blip(LEFT + PLOT_W * 0.43F, TOP + PLOT_H * 0.56F,
                    new RadarContacts.Contact(-1, "PREVIEW", Vec3.ZERO, Vec3.ZERO, false, 0), 28, 2.4, 1));
        }
        boolean wt = options.getStyle() == Radar.Style.WT;
        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.x(), bounds.y());
            g.pose().scale(scale, scale);
            RadarScopeDrawing.panel(g, WIDTH, HEIGHT, wt, options.getBackground());
            draw(g, wt, options, frame, blips, preview, mc.level.getGameTime() + partial, yaw);
        } finally {
            g.pose().popMatrix();
        }
        g.nextStratum();
    }

    private static void draw(GuiGraphicsExtractor g, boolean wt, Radar options, RadarContacts.Frame frame,
                             List<Blip> blips, boolean preview, double tick, float yaw) {
        int ink = RadarScopeDrawing.ink(wt), grid = RadarScopeDrawing.grid(wt), dim = RadarScopeDrawing.dim(wt);
        String mode = frame.tracking() ? "TRK" : options.getMode().name() + (options.isHeadOn() ? " HDN" : "");
        float scanAngle = frame.tracking() ? 180F : options.getScanAngle();
        RadarScopeDrawing.text(g, wt, mode, 11, 10, ink);
        RadarScopeDrawing.right(g, wt, preview ? "PREVIEW" : "RADAR", WIDTH - 11, 10, dim);
        RadarScopeDrawing.text(g, wt, "-" + Math.round(scanAngle / 2F) + "°", LEFT, 29, dim);
        RadarScopeDrawing.centered(g, wt, "0", LEFT + PLOT_W / 2F, 29, dim);
        RadarScopeDrawing.right(g, wt, "+" + Math.round(scanAngle / 2F) + "°", LEFT + PLOT_W, 29, dim);
        for (int i = 0; i <= 4; i++) {
            float y = TOP + PLOT_H * i / 4F;
            RadarScopeDrawing.line(g, LEFT, y, LEFT + PLOT_W, y, 0.7F, grid);
            RadarScopeDrawing.right(g, wt, Integer.toString(Math.round(options.getRange() * (4 - i) / 4F)), LEFT - 5, y - 3, dim);
        }
        for (int i = 0; i <= 4; i++) {
            float x = LEFT + PLOT_W * i / 4F;
            RadarScopeDrawing.line(g, x, TOP, x, TOP + PLOT_H, i == 2 ? 1 : 0.7F, grid);
        }
        g.enableScissor(LEFT - 8, TOP - 6, LEFT + PLOT_W + 8, TOP + PLOT_H + 6);
        try {
            double phase = (tick % (options.getScanPeriod() * 20)) / (options.getScanPeriod() * 20);
            float sweep = LEFT + PLOT_W * (float) (1 - Math.abs(phase * 2 - 1));
            // The scan trail stays inside the plot even at the reversal points.
            for (int i = frame.tracking() ? 0 : 5; i >= 1; i--) {
                float x = sweep + (phase < 0.5 ? -i : i) * 1.7F;
                if (x >= LEFT && x <= LEFT + PLOT_W) RadarScopeDrawing.line(g, x, TOP, x, TOP + PLOT_H,
                        1.8F, ModernTypography.fade(ink, (6 - i) * 0.018F));
            }
            if (!frame.tracking()) RadarScopeDrawing.line(g, sweep, TOP, sweep, TOP + PLOT_H, 1, ModernTypography.fade(ink, 0.7F));
            for (Blip blip : blips) {
                int color = ModernTypography.fade(blip.contact.ally() ? RadarScopeDrawing.AMBER : ink, blip.alpha);
                if (!wt) ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, blip.x - 8, blip.y - 8, 16,
                        ModernTypography.fade(color, 0.3F));
                RadarScopeDrawing.line(g, blip.x - 3, blip.y, blip.x + 3, blip.y, 1.7F, color);
                if (blip.contact.ally()) RadarScopeDrawing.line(g, blip.x - 3, blip.y - 3, blip.x + 3, blip.y - 3, 1, color);
                if (options.getMode() == Radar.Mode.TWS) {
                    RadarGeometry.Relative heading = RadarGeometry.relative(blip.contact.velocity(), yaw);
                    if (heading != null && heading.distance() > 0.01) {
                        double factor = 7 / heading.distance();
                        RadarScopeDrawing.line(g, blip.x, blip.y, blip.x + (float) (heading.right() * factor),
                                blip.y - (float) (heading.forward() * factor), 1, ModernTypography.fade(color, 0.65F));
                    }
                }
                if (blip.contact.entityId() == frame.selectedId() || preview) RadarScopeDrawing.bracket(g, blip.x, blip.y, color);
            }
        } finally {
            g.disableScissor();
        }
        Blip focus = blips.stream().filter(b -> b.contact.entityId() == frame.selectedId() || preview).findFirst().orElse(null);
        RadarScopeDrawing.line(g, 11, 180, WIDTH - 11, 180, 0.7F, grid);
        String status = preview ? "PREVIEW" : frame.tracking() ? "SINGLE TARGET TRACK" : tick < noContactUntil ? "NO CONTACT" : options.getMode() == Radar.Mode.ACQ
                ? (focus == null ? "ACQUIRING" : "TRACK") : blips.size() + (options.getMode() == Radar.Mode.SRC ? " RETURNS" : " TRACKS");
        RadarScopeDrawing.text(g, wt, status, 11, 186, ink);
        RadarScopeDrawing.right(g, wt, Math.round(options.getRange()) + " m", WIDTH - 11, 186, dim);
        if (focus != null) {
            String measurements = String.format(Locale.ROOT, "%.0fm  %+.1fm/s", focus.distance, focus.closing);
            int nameWidth = Math.max(0, (int) (WIDTH - 32 - RadarScopeDrawing.width(wt, measurements)));
            String name = RadarScopeDrawing.fit(wt, focus.contact.name(), nameWidth);
            RadarScopeDrawing.text(g, wt, name, 11, 199, dim);
            RadarScopeDrawing.right(g, wt, measurements, WIDTH - 11, 199, dim);
        } else {
            String shortcut = options.getLockKeyLabel();
            RadarScopeDrawing.text(g, wt, RadarScopeDrawing.fit(wt, shortcut.isEmpty()
                    ? (options.isHeadOn() ? "HEAD-ON FILTER" : "AZIMUTH / RANGE") : shortcut + " / TRK", WIDTH - 22), 11, 199, dim);
        }
    }
}
