package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.Rwr;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/** Bearing-only threat scope. AIM is an observed look direction, never a claim of an enemy radar lock. */
public final class ModernRwr {
    private static final int WIDTH = 168, HEIGHT = 214, CX = WIDTH / 2, CY = 91;
    enum Vertical { NONE, UP, DOWN }
    record Plot(RwrThreats.Threat threat, float x, float y, Vertical vertical) {}
    record Footer(String left, String right, float leftBudget) {}

    private ModernRwr() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Rwr options = Modules.enabled(Rwr.class);
        boolean editing = ModernHudEditor.isEditing();
        if (options == null || mc.player == null || mc.level == null || !ModernHud.isActive()
                || !ModernHudEditor.allowsHud() || mc.gui.hud.isHidden() && !editing) return;
        float scale = RadarScopeDrawing.scale(options.getScale(), WIDTH, HEIGHT, g);
        if (scale <= 0F) return;
        var bounds = ModernHudEditor.place(g, "rwr", "RWR", 10, g.guiHeight() - HEIGHT * scale - 34,
                WIDTH * scale, HEIGHT * scale);
        List<RwrThreats.Threat> threats = RwrThreats.snapshots(mc, options);
        boolean preview = editing && threats.isEmpty();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float yaw = mc.player.getYRot(partial);
        if (preview) {
            // Already oriented to the scope so the editor preview remains stationary while dragging.
            yaw = 0;
            threats = List.of(new RwrThreats.Threat(-1, new Vec3(-12, 0, 20), 23, RwrThreats.Type.AIM, Double.POSITIVE_INFINITY),
                    new RwrThreats.Threat(-2, new Vec3(16, 0, -5), 17, RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY));
        }
        List<Plot> plots = plots(threats, yaw);
        boolean wt = options.getStyle() == Rwr.Style.WT;
        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.x(), bounds.y());
            g.pose().scale(scale, scale);
            RadarScopeDrawing.panel(g, WIDTH, HEIGHT, wt, options.getBackground());
            draw(g, wt, threats, plots, preview, mc.level.getGameTime() + partial, options.getRange());
        } finally {
            g.pose().popMatrix();
        }
        g.nextStratum();
    }

    /** Preserve exact bearings; vertical contacts get a labelled lane outside the bearing scope. */
    static List<Plot> plots(List<RwrThreats.Threat> threats, float yaw) {
        List<Plot> plots = new ArrayList<>();
        // Do not depend on caller ordering: a passive label must never suppress an incoming projectile.
        for (RwrThreats.Threat threat : RwrThreats.ordered(threats)) {
            RadarGeometry.Relative relative = RadarGeometry.relative(threat.offset(), yaw);
            if (relative == null) continue;
            if (relative.distance() < 0.001) {
                if (Math.abs(relative.elevation()) < 0.001) continue;
                Vertical vertical = relative.elevation() > 0 ? Vertical.UP : Vertical.DOWN;
                if (plots.stream().noneMatch(plot -> plot.vertical == vertical)) {
                    plots.add(new Plot(threat, vertical == Vertical.UP ? 44 : WIDTH - 44, 171, vertical));
                }
                continue;
            }
            float radius = switch (threat.type()) {
                case CONTACT -> 49;
                case AIM -> 39;
                case ARROW, TRIDENT -> 27;
            };
            double radians = Math.toRadians(relative.bearingDegrees());
            float x = CX + (float) Math.sin(radians) * radius;
            float y = CY - (float) Math.cos(radians) * radius;
            if (plots.stream().anyMatch(plot -> plot.vertical == Vertical.NONE
                    && Math.abs(plot.x - x) < 23 && Math.abs(plot.y - y) < 12)) continue;
            plots.add(new Plot(threat, x, y, Vertical.NONE));
            if (plots.size() == 12) break;
        }
        return List.copyOf(plots);
    }

    private static boolean inbound(RwrThreats.Threat threat) {
        return threat.type() == RwrThreats.Type.ARROW || threat.type() == RwrThreats.Type.TRIDENT;
    }

    /** Measure both skins with their actual font and reserve a gap before the right-aligned ETA/range. */
    static Footer footer(int total, int visible, boolean preview, double eta, float range,
                         ToDoubleFunction<String> width) {
        int hidden = Math.max(0, total - visible);
        String right = !preview && Double.isFinite(eta) ? String.format(Locale.ROOT, "%.1fs", eta)
                : Math.round(range) + " m";
        float budget = Math.max(0F, WIDTH - 22F - 10F - (float) width.applyAsDouble(right));
        String left = hidden > 0 ? visible + " VISIBLE / +" + hidden : total + " CONTACTS";
        if (width.applyAsDouble(left) > budget) left = hidden > 0 ? visible + " / +" + hidden : total + " CONTACTS";
        if (width.applyAsDouble(left) > budget) left = visible + "/" + total;
        return new Footer(left, right, budget);
    }

    private static void draw(GuiGraphicsExtractor g, boolean wt, List<RwrThreats.Threat> threats,
                             List<Plot> plots, boolean preview, double tick, float range) {
        int ink = RadarScopeDrawing.ink(wt), dim = RadarScopeDrawing.dim(wt);
        boolean incoming = threats.stream().anyMatch(ModernRwr::inbound);
        boolean aimed = threats.stream().anyMatch(t -> t.type() == RwrThreats.Type.AIM);
        int warning = incoming ? RadarScopeDrawing.RED : aimed ? RadarScopeDrawing.AMBER : ink;
        boolean flash = (int) (tick / 5) % 2 == 0;
        RadarScopeDrawing.text(g, wt, "RWR", 11, 10, ink);
        RadarScopeDrawing.right(g, wt, preview ? "PREVIEW" : incoming ? "INBOUND" : aimed ? "AIM" : "LISTEN",
                WIDTH - 11, 10, incoming && !flash ? ModernTypography.fade(warning, 0.55F) : warning);
        RadarScopeDrawing.rwrFace(g, CX - 64, CY - 64, 128, ink);
        RadarScopeDrawing.centered(g, wt, "12", CX, CY - 68, dim);
        RadarScopeDrawing.centered(g, wt, "6", CX, CY + 64, dim);
        RadarScopeDrawing.centered(g, wt, "9", CX - 70, CY - 3, dim);
        RadarScopeDrawing.centered(g, wt, "3", CX + 70, CY - 3, dim);
        // Heading reference, shared by both skins. The rings encode urgency, not estimated range.
        RadarScopeDrawing.line(g, CX, CY - 7, CX, CY + 7, 1.5F, ink);
        RadarScopeDrawing.line(g, CX - 7, CY + 2, CX, CY - 4, 1.2F, ink);
        RadarScopeDrawing.line(g, CX, CY - 4, CX + 7, CY + 2, 1.2F, ink);
        RadarScopeDrawing.line(g, CX - 3, CY + 6, CX + 3, CY + 6, 1, ink);
        for (Plot plot : plots) {
            RwrThreats.Threat threat = plot.threat;
            int color = inbound(threat) ? RadarScopeDrawing.RED : threat.type() == RwrThreats.Type.AIM ? RadarScopeDrawing.AMBER : ink;
            float dx = plot.x - CX, dy = plot.y - CY;
            if (plot.vertical == Vertical.NONE && threat.type() != RwrThreats.Type.CONTACT) {
                // A dashed bearing distinguishes look/inbound estimates from passive contact dots.
                for (float f = 0.35F; f < 0.83F; f += 0.18F) RadarScopeDrawing.line(g,
                        CX + dx * f, CY + dy * f, CX + dx * (f + 0.08F), CY + dy * (f + 0.08F), 1,
                        ModernTypography.fade(color, 0.65F));
            }
            String code = switch (threat.type()) {
                case CONTACT -> "P";
                case AIM -> "AIM";
                case ARROW -> "ARR";
                case TRIDENT -> "TRI";
            };
            if (plot.vertical != Vertical.NONE) {
                code = (plot.vertical == Vertical.UP ? "UP " : "DN ") + code;
                if (inbound(threat) && !flash) color = ModernTypography.fade(color, 0.65F);
            } else if (inbound(threat) && flash) {
                RadarScopeDrawing.bracket(g, plot.x, plot.y, color);
            }
            RadarScopeDrawing.centered(g, wt, code, plot.x, plot.y - 3, color);
        }
        RadarScopeDrawing.line(g, 11, 181, WIDTH - 11, 181, 0.7F, RadarScopeDrawing.grid(wt));
        String status = preview ? "PREVIEW" : incoming ? "PROJECTILE APPROACH" : aimed ? "ESTIMATED AIM" : "BEARING / THREAT";
        RadarScopeDrawing.text(g, wt, status, 11, 187, warning);
        double eta = threats.stream().filter(ModernRwr::inbound).mapToDouble(RwrThreats.Threat::etaSeconds)
                .min().orElse(Double.POSITIVE_INFINITY);
        Footer footer = footer(threats.size(), plots.size(), preview, eta, range,
                value -> RadarScopeDrawing.width(wt, value));
        RadarScopeDrawing.text(g, wt, RadarScopeDrawing.fit(wt, footer.left, (int) footer.leftBudget), 11, 200, dim);
        RadarScopeDrawing.right(g, wt, footer.right, WIDTH - 11, 200, incoming && !preview ? warning : dim);
    }
}
