package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernStyle;
import com.mentalfrostbyte.jello.gui.modern.ModernTypography;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Paints shared module screens in the active presentation's coordinates, leaving layout and input to the screen. */
enum ModuleScreenStyle {
    JELLO(Face.JELLO_LIGHT, 0xFFFAFAFA, 0xFF181818, 0xFF575757, 0xFF166DB1,
            0xFFF0F0F0, 0xFFE2E6EA, 0xFFB9DFFF),
    CLASSIC(Face.CLASSIC, 0xFFD7D7D7, 0xFF161616, 0xFF424242, 0xFF244D82,
            0xFFC2C2C2, 0xFFB0B0B0, 0xFF9DB9D9),
    MODERN(null, ModernStyle.PANEL_SURFACE, ModernStyle.INK, ModernStyle.INK_MUTED, 0xFF166894,
            0xFFD2E3EB, 0xFFBFDFEE, 0xFF9AD9F5),
    VANILLA(null, 0xF2252525, 0xFFFFFFFF, 0xFFC6C6C6, 0xFFFFFFA0,
            0xFF424242, 0xFF565656, 0xFF656554);

    private final @Nullable Face face;
    private final int surface, idle, hover, selected;
    final int ink, muted, accent;

    ModuleScreenStyle(@Nullable Face face, int surface, int ink, int muted, int accent,
                      int idle, int hover, int selected) {
        this.face = face;
        this.surface = surface;
        this.ink = ink;
        this.muted = muted;
        this.accent = accent;
        this.idle = idle;
        this.hover = hover;
        this.selected = selected;
    }

    static String tr(String key, Object... args) {
        return Client.getInstance().getTranslations().get("module.editor." + key, args);
    }

    static ModuleScreenStyle current() {
        return switch (Client.getInstance().getClientModeManager().get()) {
            case JELLO -> JELLO;
            case CLASSIC -> CLASSIC;
            case SIGMA_MODERN -> MODERN;
            case NO_ADDONS -> VANILLA;
        };
    }

    void shape(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (this == CLASSIC || this == VANILLA) g.fill(x, y, x + w, y + h, color);
        else ModernStyle.rounded(g, x, y, w, h, this == JELLO ? Math.min(3, radius) : radius, color);
    }

    void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (this == MODERN) ModernStyle.dropShadow(g, x, y, w, h, 8, 0.7F);
        else g.fill(x + 1, y + 2, x + w + 1, y + h + 2, 0x50000000);
        shape(g, x, y, w, h, 8, surface);
        if (this == CLASSIC) g.outline(x, y, w, h, 0xFF646464);
    }

    void control(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean chosen, boolean over) {
        shape(g, x, y, w, h, 4, chosen ? selected : over ? hover : idle);
        if (this == CLASSIC || over && chosen) g.outline(x, y, w, h, over ? accent : 0xFF888888);
        if (chosen) g.fill(x + 1, y + 2, x + 3, y + h - 2, accent);
    }

    void label(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        if (face != null) LegacyFonts.draw(g, face, text, x, y, ModernTypography.size(), color);
        else if (this == MODERN) ModernTypography.draw(g, text, x, y, color, false);
        else g.text(Minecraft.getInstance().font, text, x, y, color, false);
    }

    private int width(String text) {
        if (face != null) return (int) Math.ceil(LegacyFonts.width(face, text, ModernTypography.size()));
        return this == MODERN ? ModernTypography.width(text) : Minecraft.getInstance().font.width(text);
    }

    private int lineHeight() {
        if (face != null) return (int) Math.ceil(LegacyFonts.height(face, ModernTypography.size()));
        return this == MODERN ? (int) ModernTypography.size() : Minecraft.getInstance().font.lineHeight;
    }

    private String fit(String text, int available) {
        if (available <= 0) return "";
        if (width(text) <= available) return text;
        int room = available - width("…");
        if (room < 0) return "";
        String prefix;
        if (face != null) prefix = LegacyFonts.fit(face, text, room);
        else if (this == MODERN) prefix = ModernTypography.fit(text, room);
        else prefix = Minecraft.getInstance().font.plainSubstrByWidth(text, room);
        return prefix + "…";
    }

    void label(GuiGraphicsExtractor g, String text, int x, int y, int available, int color) {
        label(g, fit(text, available), x, y, color);
    }

    void centered(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        String shown = fit(text, w - 8);
        label(g, shown, x + (w - width(shown)) / 2, y, color);
    }

    void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String text, boolean chosen, boolean over, boolean enabled) {
        int textColor = enabled ? ink : muted;
        if (this == CLASSIC) {
            g.fill(x, y, x + w, y + h, chosen ? accent : 0xFF333333);
            g.outline(x, y, w, h, enabled && over ? 0xFFFFFFFF : 0xFF171717);
            textColor = enabled ? 0xFFFFFFFF : 0xFFAAAAAA;
        } else {
            control(g, x, y, w, h, chosen, enabled && over);
        }
        centered(g, text, x, y + (h - lineHeight()) / 2, w, textColor);
    }

    Button button(int x, int y, int w, int h, Component text, Button.OnPress action) {
        if (this == VANILLA) return Button.builder(text, action).bounds(x, y, w, h).build();
        return new Button(x, y, w, h, text, action, supplier -> supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
                ModuleScreenStyle.this.button(g, getX(), getY(), getWidth(), getHeight(), getMessage().getString(),
                        false, isHoveredOrFocused(), active);
            }
        };
    }

    EditBox search(Font font, int x, int y, int w, int h, Component name) {
        if (face == null) return new EditBox(font, x, y, w, h, name);
        EditBox box = new EditBox(font, x, y, w, h, name) {
            @Override protected void extractBorder(GuiGraphicsExtractor g) {
                int left = getX(), top = getY(), width = getWidth(), height = getHeight();
                shape(g, left, top, width, height, 4, 0xFFFAFAFA);
                if (ModuleScreenStyle.this == CLASSIC) g.outline(left, top, width, height, isFocused() ? accent : muted);
                else g.fill(left + 1, top + height - 1, left + width - 1, top + height, isFocused() ? accent : muted);
            }
        };
        box.setTextColor(ink);
        box.setTextShadow(false);
        return box;
    }
}
