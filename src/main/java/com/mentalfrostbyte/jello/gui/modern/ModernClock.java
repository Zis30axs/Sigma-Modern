package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.HudClock;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** A local-time clock with a stable drag rectangle and shared, bounded clock-face textures. */
public final class ModernClock {
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter H24_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter H12_SECONDS = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);
    private static final TimeCache TIME = new TimeCache();
    private static final int[] FACE_SIZES = {64, 96, 128, 192, 256, 384, 512};
    // Extraction-thread only; seven immutable masks at most, with no eviction of queued GUI references.
    private static final Map<Integer, Identifier> FACES = new HashMap<>();
    private static float digitWidth;

    private ModernClock() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudClock options = Modules.enabled(HudClock.class);
        boolean editing = ModernHudEditor.isEditing();
        if (options == null || mc.player == null || mc.level == null || !ModernHud.isActive()
                || !ModernHudEditor.allowsHud() || mc.gui.hud.isHidden() && !editing) return;

        long now = System.currentTimeMillis();
        Display display = TIME.update(now, ZoneId.systemDefault(), options.isTwentyFourHour(),
                options.showsSeconds(), options.showsDate());
        Size size = size(options.getStyle(), options.showsDate());
        float scale = fittingScale(options.getScale(), size, g.guiWidth(), g.guiHeight());
        if (scale <= 0F) return;
        float width = size.width * scale, height = size.height * scale;
        float chatHeight = ChatComponent.getHeight(mc.options.chatHeightUnfocused().get())
                * mc.options.chatScale().get().floatValue();
        // Reserve the unfocused chat page, including ModernChat's padding, without following message animations.
        float defaultY = defaultY(g.guiHeight(), height, chatHeight, ModernHud.leftStack());
        var bounds = ModernHudEditor.place(g, "clock", "Clock", 12F, defaultY, width, height);
        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.x(), bounds.y());
            g.pose().scale(scale, scale);
            panel(g, size, options.getBackground());
            if (options.getStyle() == HudClock.Style.ANALOG) {
                analog(g, display, options.showsSeconds(), Math.floorMod(now, 1000L) / 1000.0);
            } else {
                digital(g, display, size);
            }
        } finally {
            g.pose().popMatrix();
        }
        g.nextStratum();
    }

    private static void panel(GuiGraphicsExtractor g, Size size, float opacity) {
        if (opacity <= 0F) return;
        ModernStyle.dropShadow(g, 0, 2, size.width, size.height, 9, opacity * 0.65F);
        ModernStyle.rounded(g, 0, 0, size.width, size.height, 9,
                ModernTypography.fade(0x668BBFD9, opacity));
        ModernStyle.rounded(g, 1, 1, size.width - 2, size.height - 2, 8,
                Math.round(opacity * 255F) << 24 | 0x0A1926);
        ModernStyle.fillGradient(g, 10, 1, size.width - 10, 15,
                ModernTypography.fade(0x25C3EFFF, opacity), 0x00C3EFFF);
    }

    private static void digital(GuiGraphicsExtractor g, Display display, Size size) {
        ModernTypography.draw(g, "LOCAL TIME", 11F, 8F, 0.6F, ModernStyle.MUTED, false);
        ModernStyle.rounded(g, size.width - 17, 10, 4, 4, 2, ModernStyle.ACCENT);
        drawTime(g, display.timeText, size.width, 23F, 1.5F);
        if (!display.dateText.isEmpty()) {
            ModernTypography.draw(g, display.dateText, 12F, 46F, 0.74F, ModernStyle.MUTED, false);
        }
    }

    private static void analog(GuiGraphicsExtractor g, Display display, boolean seconds, double fraction) {
        centered(g, "LOCAL TIME", 112, 8F, 0.6F, ModernStyle.MUTED);
        drawFace(g, 16, 22, 80);
        Angles angles = angles(display.time, fraction);
        hand(g, 56F, 62F, angles.hour, 21, 3, ModernStyle.TEXT);
        hand(g, 56F, 62F, angles.minute, 29, 2, ModernStyle.ACCENT);
        if (seconds) hand(g, 56F, 62F, angles.second, 32, 1, 0xFFA8F0F5);
        ModernStyle.rounded(g, 53, 59, 6, 6, 3, ModernStyle.ACCENT);
        ModernStyle.rounded(g, 55, 61, 2, 2, 1, 0xFF102735);
        drawTime(g, display.timeText, 112, 109F, 0.82F);
        if (!display.dateText.isEmpty()) centered(g, display.dateText, 112, 124F, 0.68F, ModernStyle.MUTED);
    }

    /** Each numeral reuses the shared font's ten glyph runs instead of rasterizing every possible second. */
    private static void drawTime(GuiGraphicsExtractor g, String value, int width, float y, float scale) {
        if (digitWidth == 0F) {
            for (int digit = 0; digit <= 9; digit++) digitWidth = Math.max(digitWidth, ModernTypography.width(Integer.toString(digit)));
        }
        float total = 0F;
        for (int i = 0; i < value.length(); i++) total += cellWidth(value.charAt(i));
        float x = (width - total * scale) / 2F;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            String glyph = Character.toString(ch);
            float cell = cellWidth(ch);
            ModernTypography.draw(g, glyph, x + (cell - ModernTypography.width(glyph)) * scale / 2F,
                    y, scale, ModernStyle.TEXT, false);
            x += cell * scale;
        }
    }

    private static float cellWidth(char ch) {
        return ch >= '0' && ch <= '9' ? digitWidth : ModernTypography.width(Character.toString(ch));
    }

    private static void centered(GuiGraphicsExtractor g, String value, int width, float y, float scale, int color) {
        ModernTypography.draw(g, value, (width - ModernTypography.width(value) * scale) / 2F, y, scale, color, false);
    }

    private static void hand(GuiGraphicsExtractor g, float x, float y, double turns, int length, int thickness, int color) {
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().rotate((float) (turns * Math.TAU));
            g.pose().translate(-thickness / 2F, 0F);
            ModernStyle.rounded(g, 0, -length, thickness, length + 5, 1, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static void drawFace(GuiGraphicsExtractor g, int x, int y, int size) {
        float transform = Math.max((float) Math.hypot(g.pose().m00(), g.pose().m01()),
                (float) Math.hypot(g.pose().m10(), g.pose().m11()));
        int pixels = faceSize(size * transform * Minecraft.getInstance().getWindow().getGuiScale());
        Identifier texture = FACES.computeIfAbsent(pixels, px -> {
            Identifier id = Identifier.withDefaultNamespace("sigma/clock/face_" + px);
            ModernSkiaRaster.upload(id, faceMask(px));
            return id;
        });
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, pixels, pixels,
                pixels, pixels, ModernStyle.a(ModernStyle.ACCENT));
    }

    static int faceSize(float pixels) {
        for (int size : FACE_SIZES) if (pixels <= size) return size;
        return FACE_SIZES[FACE_SIZES.length - 1];
    }

    static ModernSkiaRaster.Image faceMask(int pixels) {
        if (pixels < 1 || pixels > 512) throw new IllegalArgumentException("Clock face must be 1–512 pixels");
        return ModernSkiaRaster.render(pixels, pixels, canvas -> {
            canvas.scale(pixels / 80F, pixels / 80F);
            try (Paint paint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)) {
                paint.setColor(0x55FFFFFF).setStrokeWidth(0.8F);
                canvas.drawCircle(40F, 40F, 38F, paint);
                for (int tick = 0; tick < 60; tick++) {
                    boolean major = tick % 5 == 0;
                    double theta = tick * Math.TAU / 60.0;
                    float inner = major ? 29F : 33F;
                    paint.setColor(major ? 0xDDFFFFFF : 0x55FFFFFF).setStrokeWidth(major ? 1.8F : 0.7F);
                    canvas.drawLine(40F + (float) Math.sin(theta) * inner, 40F - (float) Math.cos(theta) * inner,
                            40F + (float) Math.sin(theta) * 35F, 40F - (float) Math.cos(theta) * 35F, paint);
                }
            }
        });
    }

    static String formatTime(LocalTime time, boolean twentyFourHour, boolean seconds) {
        return (twentyFourHour ? seconds ? H24_SECONDS : H24 : seconds ? H12_SECONDS : H12).format(time);
    }

    static String formatDate(LocalDate date) { return DateTimeFormatter.ISO_LOCAL_DATE.format(date); }

    /** Clockwise turns from twelve o'clock, including continuous minute/hour movement. */
    static Angles angles(LocalTime time, double secondFraction) {
        double fraction = Double.isFinite(secondFraction) ? Math.clamp(secondFraction, 0.0, 0.999999999) : 0.0;
        double second = time.getSecond() + fraction;
        double minute = time.getMinute() + second / 60.0;
        return new Angles((time.getHour() % 12 + minute / 60.0) / 12.0, minute / 60.0, second / 60.0);
    }

    static Size size(HudClock.Style style, boolean date) {
        return style == HudClock.Style.ANALOG ? new Size(112, date ? 140 : 124) : new Size(132, date ? 62 : 46);
    }

    static float fittingScale(float requested, Size size, int width, int height) {
        if (!Float.isFinite(requested) || requested <= 0F || width <= 12 || height <= 12) return 0F;
        return Math.min(requested, Math.min((width - 12F) / size.width, (height - 12F) / size.height));
    }

    static float defaultY(int screenHeight, float height, float chatHeight, float leftStack) {
        float bottom = Math.max(6F, screenHeight - height - 6F);
        float belowStack = Math.max(6F, leftStack + 6F);
        float aboveChat = screenHeight - height - 60F - Math.max(0F, chatHeight);
        // On short viewports the existing left stack takes precedence over reserving a full chat page.
        return belowStack <= bottom ? Math.clamp(aboveChat, belowStack, bottom) : bottom;
    }

    record Size(int width, int height) {}
    record Angles(double hour, double minute, double second) {}
    record Display(LocalTime time, String timeText, String dateText) {}

    /** Recompute only at a second, zone or setting change; wall-clock corrections are reflected immediately. */
    static final class TimeCache {
        private long second = Long.MIN_VALUE;
        private ZoneId zone;
        private boolean twentyFourHour, seconds, date;
        private Display display;

        Display update(long epochMillis, ZoneId zone, boolean twentyFourHour, boolean seconds, boolean date) {
            long second = Math.floorDiv(epochMillis, 1000L);
            if (this.display == null || this.second != second || !zone.equals(this.zone)
                    || this.twentyFourHour != twentyFourHour || this.seconds != seconds || this.date != date) {
                var local = Instant.ofEpochSecond(second).atZone(zone);
                this.display = new Display(local.toLocalTime(), formatTime(local.toLocalTime(), twentyFourHour, seconds),
                        date ? formatDate(local.toLocalDate()) : "");
                this.second = second;
                this.zone = zone;
                this.twentyFourHour = twentyFourHour;
                this.seconds = seconds;
                this.date = date;
            }
            return this.display;
        }
    }
}
