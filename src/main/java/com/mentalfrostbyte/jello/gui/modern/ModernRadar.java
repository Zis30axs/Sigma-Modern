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
        float scale = RadarScopeDrawing.scale(options.getScale() * ModernHudEditor.scaleOf("radar"), WIDTH, HEIGHT, g);
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
            if (wt) {
                drawWt(g, options, frame, blips, preview, mc.level.getGameTime() + partial, yaw);
            } else {
                RadarScopeDrawing.panel(g, WIDTH, HEIGHT, false, options.getBackground());
                draw(g, false, options, frame, blips, preview, mc.level.getGameTime() + partial, yaw);
            }
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

    /** Authentic War Thunder B-scope radar skin with distinct SRC, TWS, ACQ (ACM), and TRK (STT) visuals. */
    private static void drawWt(GuiGraphicsExtractor g, Radar options, RadarContacts.Frame frame,
                               List<Blip> blips, boolean preview, double tick, float yaw) {
        int green = RadarScopeDrawing.WT_GREEN;
        int dim = ModernTypography.fade(green, 0.62F);
        int faint = ModernTypography.fade(green, 0.24F);
        int bg = options.getBackground() > 0F
                ? (Math.round(options.getBackground() * 115F) << 24) | 0x041208 : 0;

        boolean tracking = frame.tracking();
        Radar.Mode radarMode = options.getMode();
        boolean acq = !tracking && radarMode == Radar.Mode.ACQ;
        boolean tws = !tracking && radarMode == Radar.Mode.TWS;
        boolean flash = (int) (tick / 5) % 2 == 0;
        float scanAngle = tracking ? 180F : options.getScanAngle();

        // Scope backing strictly inside the B-scope plot (no bulky outer card or corner brackets).
        if (bg != 0) ModernStyle.fill(g, LEFT, TOP, LEFT + PLOT_W, TOP + PLOT_H, bg);

        // Top mode & range header (WT style: "SRC", "SRC HDN", "TWS", "ACQ", "TRK" on left; range & sector on right).
        String modeLabel = tracking ? "TRK" : radarMode.name() + (options.isHeadOn() ? " HDN" : "");
        ModernTypography.draw(g, ModernTypography.Face.TEXT, modeLabel, LEFT, 12F, 0.88F, green);
        String rightHeader = preview ? "PREVIEW" : Math.round(options.getRange()) + "m · " + Math.round(scanAngle) + "°";
        float rhW = ModernTypography.width(ModernTypography.Face.TEXT, rightHeader, 0.78F);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, rightHeader, LEFT + PLOT_W - rhW, 13F, 0.78F, dim);

        // Azimuth scale labels above the top rail.
        String leftAng = "-" + Math.round(scanAngle / 2F) + "°";
        String rightAng = "+" + Math.round(scanAngle / 2F) + "°";
        ModernTypography.draw(g, ModernTypography.Face.TEXT, leftAng, LEFT, 28F, 0.72F, dim);
        float zeroW = ModernTypography.width(ModernTypography.Face.TEXT, "0", 0.72F);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, "0", LEFT + PLOT_W * 0.5F - zeroW * 0.5F, 28F, 0.72F, dim);
        float rightAngW = ModernTypography.width(ModernTypography.Face.TEXT, rightAng, 0.72F);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, rightAng, LEFT + PLOT_W - rightAngW, 28F, 0.72F, dim);

        // Crisp B-scope frame (left, right, bottom, top rails + subtle range marks + inward azimuth ticks).
        RadarScopeDrawing.box(g, LEFT, TOP, PLOT_W, PLOT_H, 1.25F, green);
        for (int i = 1; i <= 3; i++) {
            float y = TOP + PLOT_H * i / 4F;
            RadarScopeDrawing.line(g, LEFT, y, LEFT + PLOT_W, y, 0.65F, faint);
            RadarScopeDrawing.line(g, LEFT, y, LEFT + 5F, y, 1.0F, green);
            RadarScopeDrawing.line(g, LEFT + PLOT_W - 5F, y, LEFT + PLOT_W, y, 1.0F, green);
            String dist = Integer.toString(Math.round(options.getRange() * (4 - i) / 4F));
            float dw = ModernTypography.width(ModernTypography.Face.TEXT, dist, 0.68F);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, dist, LEFT - dw - 4F, y - 4F, 0.68F, dim);
        }
        // Center boresight dashed line + inward ticks along top & bottom rails.
        float centerX = LEFT + PLOT_W * 0.5F;
        for (int i = 1; i <= 3; i++) {
            float x = LEFT + PLOT_W * i / 4F;
            float tickLen = i == 2 ? 6F : 4F;
            RadarScopeDrawing.line(g, x, TOP, x, TOP + tickLen, 1.0F, green);
            RadarScopeDrawing.line(g, x, TOP + PLOT_H - tickLen, x, TOP + PLOT_H, 1.0F, green);
        }
        for (float y = TOP + 8F; y < TOP + PLOT_H - 6F; y += 9F) {
            RadarScopeDrawing.line(g, centerX, y, centerX, Math.min(y + 4F, TOP + PLOT_H - 2F), 0.75F, faint);
        }

        // Left elevation bar indicator (classic WT vertical scan-elevation scale).
        float elevX = LEFT - 19F;
        RadarScopeDrawing.line(g, elevX, TOP + 4F, elevX, TOP + PLOT_H - 4F, 0.8F, faint);
        RadarScopeDrawing.line(g, elevX - 2.5F, TOP + PLOT_H * 0.5F, elevX + 2.5F, TOP + PLOT_H * 0.5F, 0.9F, dim);

        Blip focus = blips.stream().filter(b -> b.contact.entityId() == frame.selectedId() || preview).findFirst().orElse(null);

        g.enableScissor(LEFT - 6, TOP - 6, LEFT + PLOT_W + 6, TOP + PLOT_H + 6);
        try {
            double phase = (tick % (options.getScanPeriod() * 20)) / (options.getScanPeriod() * 20);
            float tri = (float) (1 - Math.abs(phase * 2 - 1));

            if (acq) {
                // ACQ / ACM mode: narrow central boresight acquisition corridor with fast inner scan bar.
                float gateHalf = Math.max(18F, PLOT_W * 0.22F);
                float gx0 = centerX - gateHalf, gx1 = centerX + gateHalf;
                for (float y = TOP + 3F; y < TOP + PLOT_H - 3F; y += 8F) {
                    float yEnd = Math.min(y + 4.5F, TOP + PLOT_H - 2F);
                    RadarScopeDrawing.line(g, gx0, y, gx0, yEnd, 1.1F, dim);
                    RadarScopeDrawing.line(g, gx1, y, gx1, yEnd, 1.1F, dim);
                }
                // Corner acquisition brackets inside the boresight gate.
                RadarScopeDrawing.line(g, gx0, TOP + 1F, gx0 + 7F, TOP + 1F, 1.3F, green);
                RadarScopeDrawing.line(g, gx1 - 7F, TOP + 1F, gx1, TOP + 1F, 1.3F, green);
                RadarScopeDrawing.line(g, gx0, TOP + PLOT_H - 1F, gx0 + 7F, TOP + PLOT_H - 1F, 1.3F, green);
                RadarScopeDrawing.line(g, gx1 - 7F, TOP + PLOT_H - 1F, gx1, TOP + PLOT_H - 1F, 1.3F, green);
                double fastPhase = ((tick * 1.8) % (options.getScanPeriod() * 20)) / (options.getScanPeriod() * 20);
                float fastTri = (float) (1 - Math.abs(fastPhase * 2 - 1));
                float sweep = gx0 + (gx1 - gx0) * fastTri;
                RadarScopeDrawing.line(g, sweep, TOP + 1F, sweep, TOP + PLOT_H - 1F, 1.15F, ModernTypography.fade(green, 0.85F));
            } else if (!tracking) {
                // SRC & TWS modes: full-sector sweeping beam with phosphor trail + top sweep caret.
                float sweep = LEFT + PLOT_W * tri;
                for (int i = 6; i >= 1; i--) {
                    float x = sweep + (phase < 0.5 ? -i : i) * 1.8F;
                    if (x >= LEFT && x <= LEFT + PLOT_W) {
                        RadarScopeDrawing.line(g, x, TOP + 1F, x, TOP + PLOT_H - 1F, 1.8F,
                                ModernTypography.fade(green, (7 - i) * 0.026F));
                    }
                }
                RadarScopeDrawing.line(g, sweep, TOP + 1F, sweep, TOP + PLOT_H - 1F, 1.15F, ModernTypography.fade(green, 0.85F));
            }

            // Blip rendering per mode.
            for (Blip blip : blips) {
                boolean selected = blip.contact.entityId() == frame.selectedId() || preview;
                int baseColor = blip.contact.ally() ? RadarScopeDrawing.AMBER : green;
                int color = ModernTypography.fade(baseColor, blip.alpha);

                if (tracking && selected) {
                    // STT / TRK mode: solid vertical lock line from bottom rail + double WT lock box + heading vector.
                    RadarScopeDrawing.line(g, blip.x, TOP + PLOT_H, blip.x, blip.y + 7F, 1.15F, ModernTypography.fade(color, 0.78F));
                    RadarScopeDrawing.box(g, blip.x - 6.5F, blip.y - 6.5F, 13F, 13F, 1.35F, color);
                    RadarScopeDrawing.line(g, blip.x - 3F, blip.y, blip.x + 3F, blip.y, 1.6F, color);
                    drawHeadingVector(g, blip, yaw, 10F, color);
                } else if (tws) {
                    // TWS mode: hollow tracked box + velocity vector + priority brackets on selected track.
                    RadarScopeDrawing.box(g, blip.x - 3.5F, blip.y - 3.5F, 7F, 7F, 1.1F, color);
                    if (blip.contact.ally()) {
                        RadarScopeDrawing.line(g, blip.x - 4F, blip.y - 6F, blip.x + 4F, blip.y - 6F, 1.0F, color);
                    }
                    drawHeadingVector(g, blip, yaw, 9F, ModernTypography.fade(color, 0.85F));
                    if (selected) {
                        RadarScopeDrawing.line(g, blip.x - 7.5F, blip.y - 6F, blip.x - 7.5F, blip.y + 6F, 1.35F, color);
                        RadarScopeDrawing.line(g, blip.x + 7.5F, blip.y - 6F, blip.x + 7.5F, blip.y + 6F, 1.35F, color);
                    }
                } else {
                    // SRC & ACQ modes: crisp WT phosphor horizontal bar (double bar for IFF ally) + cursor brackets.
                    RadarScopeDrawing.line(g, blip.x - 4.5F, blip.y, blip.x + 4.5F, blip.y, 1.8F, color);
                    if (blip.contact.ally()) {
                        RadarScopeDrawing.line(g, blip.x - 4.5F, blip.y - 3.2F, blip.x + 4.5F, blip.y - 3.2F, 1.1F, color);
                    }
                    if (selected) {
                        RadarScopeDrawing.line(g, blip.x - 7.5F, blip.y - 5.5F, blip.x - 7.5F, blip.y + 5.5F, 1.3F, color);
                        RadarScopeDrawing.line(g, blip.x + 7.5F, blip.y - 5.5F, blip.x + 7.5F, blip.y + 5.5F, 1.3F, color);
                    }
                }
            }
        } finally {
            g.disableScissor();
        }

        // Compact WT bottom readout right under the B-scope rail.
        float footerY = TOP + PLOT_H + 6F;
        if (focus != null) {
            String meas = String.format(Locale.ROOT, "%.0fm  %+.1fm/s", focus.distance, focus.closing);
            float measW = ModernTypography.width(ModernTypography.Face.TEXT, meas, 0.78F);
            int nameBudget = Math.max(0, (int) (PLOT_W - measW - 8F));
            String name = ModernTypography.ellipsize(focus.contact.name(), Math.round(nameBudget / 0.78F));
            ModernTypography.draw(g, ModernTypography.Face.TEXT, name, LEFT, footerY, 0.78F, green);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, meas, LEFT + PLOT_W - measW, footerY, 0.78F, green);
        } else {
            String status = tick < noContactUntil ? "NO CONTACT"
                    : acq ? (flash ? "ACQUIRING" : "ACM")
                    : blips.size() + (radarMode == Radar.Mode.SRC ? " RETURNS" : " TRACKS");
            ModernTypography.draw(g, ModernTypography.Face.TEXT, status, LEFT, footerY, 0.78F, dim);
            String shortcut = options.getLockKeyLabel();
            if (!shortcut.isEmpty()) {
                String hint = shortcut + " TRK";
                float hw = ModernTypography.width(ModernTypography.Face.TEXT, hint, 0.74F);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, hint, LEFT + PLOT_W - hw, footerY, 0.74F, dim);
            }
        }
    }

    private static void drawHeadingVector(GuiGraphicsExtractor g, Blip blip, float yaw, float length, int color) {
        RadarGeometry.Relative heading = RadarGeometry.relative(blip.contact.velocity(), yaw);
        if (heading == null || heading.distance() <= 0.01) return;
        double factor = length / heading.distance();
        RadarScopeDrawing.line(g, blip.x, blip.y, blip.x + (float) (heading.right() * factor),
                blip.y - (float) (heading.forward() * factor), 1.1F, color);
    }
}
