package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.ClickGuiInteractions;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyDropdown;
import com.mentalfrostbyte.jello.gui.legacy.LegacyKeys;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/**
 * A module's settings, opened over the Jello ClickGUI ({@code SettingGroup} + {@code SettingPanel}).
 *
 * <p>The screen dims to 45 %, and a white card 500 px wide (70 % of the height, at most 600) springs up from
 * 0.8 scale. Above it the module's name in big medium-weight white type; inside, its description in light grey
 * and then the settings, one row each - the name on the left in 25 px light type, the control on the right:
 * a round check for a switch, a slim slider with its value beside it, a dropdown for a choice, an underlined field
 * for text, a hue/brightness picker for a colour. Hovering a name puts its description under the card. The
 * page leaves on a press outside the card or on Escape.</p>
 *
 * <p>What the controls do to a setting is {@link ClickGuiInteractions}' and the setting's own; this class only
 * draws them and turns pointer positions into calls.</p>
 */
final class JelloSettingsPage {
    private static final int CARD_W = 500;
    private static final int GAP = 20;
    private static final int LEFT = 20;

    private static final int GREY = 0xFF999999;
    private static final int LIGHT_BLUE = 0xFF29A6FF;

    private enum Kind {
        BIND, BOOLEAN, NUMBER, ENUM, TEXT, COLOR
    }

    private record Row(Kind kind, Setting<?> setting, int top, int height) {
    }

    private final JelloClickGuiScreen owner;
    private final Module module;
    private final ClickGuiInteractions interactions;
    private final Animation appear = new Animation(200, 120, Animation.Direction.FORWARDS);
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private final Map<Setting<?>, Animation> checks = new HashMap<>();
    private final Map<Setting<?>, LegacyDropdown> dropdowns = new HashMap<>();
    private final Map<Setting<?>, LegacyTextField> fields = new HashMap<>();
    private final Map<Setting<?>, float[]> hsv = new HashMap<>();
    private boolean closing;
    private Setting<?> pressedColor;
    private int pressedColorPart;
    private Setting<?> hovered;
    private float hint;
    private String hintName = "";
    private String hintText = "";

    // Geometry of the last frame, for hit testing.
    private int cardX;
    private int cardY;
    private int cardH;
    private int listX;
    private int listY;
    private int listH;
    private List<Row> rows = List.of();
    private int contentH;

    JelloSettingsPage(final JelloClickGuiScreen owner, final Module module, final ClickGuiInteractions interactions) {
        this.owner = owner;
        this.module = module;
        this.interactions = interactions;
    }

    float visibility() {
        return this.appear.calcPercent();
    }

    boolean visible() {
        return this.appear.calcPercent() > 0.0F;
    }

    boolean finished() {
        return this.closing && this.appear.calcPercent() <= 0.0F;
    }

    void close() {
        this.closing = true;
        this.appear.changeDirection(Animation.Direction.BACKWARDS);
        for (LegacyTextField field : this.fields.values()) {
            field.setFocused(false);
        }
    }

    boolean typing() {
        for (LegacyTextField field : this.fields.values()) {
            if (field.focused()) {
                return true;
            }
        }
        return this.interactions.isBinding(this.module);
    }

    // ------------------------------------------------------------------ layout

    private void layout(final int w, final int h) {
        int cardH = Math.min(600, (int) (h * 0.7F));
        this.cardH = cardH;
        this.cardX = (w - CARD_W) / 2;
        this.cardY = (h - cardH) / 2 + 20;
        this.listX = this.cardX + 10;
        this.listY = this.cardY + 59;
        this.listH = cardH - 59 - 10;

        List<Row> rows = new ArrayList<>();
        int top = LEFT;
        rows.add(new Row(Kind.BIND, null, top, 27));
        top += 27 + 10;
        for (Setting<?> setting : this.module.settings()) {
            if (!setting.isVisible()) {
                continue;
            }
            Kind kind;
            int height;
            if (setting instanceof BooleanSetting) {
                kind = Kind.BOOLEAN;
                height = 24;
            } else if (setting instanceof NumberSetting) {
                kind = Kind.NUMBER;
                height = 24;
            } else if (setting instanceof EnumSetting<?>) {
                kind = Kind.ENUM;
                height = 27;
            } else if (setting instanceof ColorSetting) {
                kind = Kind.COLOR;
                height = 114;
            } else {
                kind = Kind.TEXT;
                height = 27;
            }
            rows.add(new Row(kind, setting, top, height));
            // The old panel stepped a control's height plus 10; a colour picker's own margin made it exactly its height.
            top += kind == Kind.COLOR ? height : height + 10;
        }
        this.rows = rows;
        this.contentH = top;
    }

    // ------------------------------------------------------------------ drawing

    void draw(final LegacyCanvas c, final double mx, final double my, final float screenAlpha) {
        int w = c.width();
        int h = c.height();
        this.layout(w, h);
        this.scroll.clamp(this.contentH, this.listH);

        float p = this.appear.calcPercent();
        float scale = 0.8F + (this.closing ? Easing.easeOutQuad(p, 0, 1, 1) : Easing.easeOutBack(p, 0, 1, 1)) * 0.2F;

        c.fill(0, 0, w, h, LegacyCanvas.alpha(0xFF010101, 0.45F * p));
        c.push();
        c.scaleAbout(scale, scale, w / 2.0F, h / 2.0F);

        c.rounded(this.cardX, this.cardY, CARD_W, this.cardH, 10, LegacyCanvas.alpha(0xFFFEFEFE, p));
        c.text(Face.JELLO_MEDIUM, 40, this.module.getName(), this.cardX, this.cardY - 60, LegacyCanvas.alpha(0xFFFEFEFE, p));
        c.scissor(this.cardX, this.cardY, this.cardX + CARD_W - 30, this.cardY + this.cardH);
        c.text(Face.JELLO_LIGHT, 20, this.module.getDescription(), this.cardX + 30, this.cardY + 30, LegacyCanvas.alpha(0xFF010101, p * 0.7F));
        c.unscissor();

        this.hovered = null;
        List<LegacyDropdown> open = new ArrayList<>();
        c.scissor(this.listX, this.listY, this.listX + CARD_W - 20, this.listY + this.listH);
        for (Row row : this.rows) {
            int top = this.listY + row.top() - this.scroll.offset();
            if (top + row.height() < this.listY - 10 || top > this.listY + this.listH) {
                continue;
            }
            this.drawRow(c, row, top, mx, my, p, open);
        }
        c.unscissor();
        this.scroll.draw(c, this.listX + CARD_W - 20, this.listY, this.listH, this.contentH, this.listH,
            mx >= this.listX && mx < this.listX + CARD_W - 20 && my >= this.listY && my < this.listY + this.listH, p);

        // Open dropdowns fold out over the rows below them, so they draw last and are not clipped by the list.
        for (LegacyDropdown dropdown : open) {
            dropdown.draw(c, mx, my, p);
        }

        // The hovered setting's own description, under the card.
        this.hint = Math.max(0.0F, Math.min(1.0F, this.hint + (this.hovered != null ? 1 : -1) * 0.12F));
        if (this.hovered != null) {
            this.hintName = this.hovered.getName();
            this.hintText = this.hovered.getDescription();
        }
        if (this.hint > 0.0F) {
            int white = 0xFFFEFEFE;
            int y = this.cardY + this.cardH + 24;
            c.text(Face.JELLO_LIGHT, 14, this.hintName, this.cardX + 10, y, LegacyCanvas.alpha(white, 0.5F * this.hint * p));
            c.text(Face.JELLO_LIGHT, 14, this.hintName, this.cardX + 11, y, LegacyCanvas.alpha(white, 0.5F * this.hint * p));
            c.text(Face.JELLO_LIGHT, 14, this.hintText, this.cardX + 14 + c.textWidth(Face.JELLO_LIGHT, 14, this.hintName) + 2, y,
                LegacyCanvas.alpha(white, 0.5F * this.hint * p));
        }
        c.pop();
    }

    private int right() {
        return this.listX + CARD_W - 20 - GAP;
    }

    /** A setting's name: 25 px light type hung from the row's top edge, where the old panel put it. */
    private void label(final LegacyCanvas c, final String text, final int top, final float p) {
        c.text(Face.JELLO_LIGHT, 25, text, this.listX + LEFT, top, LegacyCanvas.alpha(0xFF010101, p));
    }

    private void drawRow(final LegacyCanvas c, final Row row, final int top, final double mx, final double my, final float p, final List<LegacyDropdown> open) {
        boolean over = mx >= this.listX && mx < this.listX + CARD_W - 20 && my >= top && my < top + row.height() + GAP - 6
            && my >= this.listY && my < this.listY + this.listH;
        if (over && row.setting() != null) {
            this.hovered = row.setting();
        }

        switch (row.kind()) {
            case BIND -> {
                this.label(c, "Keybind", top, p);
                boolean binding = this.interactions.isBinding(this.module);
                String key = binding ? "Press a key..." : LegacyKeys.name(this.module.getKeybind());
                String mode = this.module.getKeybind().mode().name().charAt(0) + this.module.getKeybind().mode().name().substring(1).toLowerCase(Locale.ROOT);
                int pillW = 150;
                int pillX = this.right() - pillW;
                boolean hover = mx >= pillX && mx < pillX + pillW && my >= top && my < top + row.height();
                c.rounded(pillX, top, pillW, row.height(), 4, LegacyCanvas.alpha(hover || binding ? 0xFFE6E6E6 : 0xFFF2F2F2, p));
                c.textCentered(Face.JELLO_LIGHT, 18, key, pillX + pillW / 2.0F, top + row.height() / 2.0F, LegacyCanvas.alpha(0xFF010101, p * (binding ? 1.0F : 0.7F)));
                float modeW = c.textWidth(Face.JELLO_LIGHT, 14, mode);
                c.text(Face.JELLO_LIGHT, 14, mode, pillX - modeW - 12, top + row.height() / 2.0F - c.textHeight(Face.JELLO_LIGHT, 14) / 2.0F,
                    LegacyCanvas.alpha(GREY, p));
            }
            case BOOLEAN -> {
                BooleanSetting setting = (BooleanSetting) row.setting();
                this.label(c, setting.getName(), top, p);
                Animation check = this.checks.computeIfAbsent(setting, s -> {
                    Animation a = new Animation(70, 90, setting.get() ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS);
                    a.setProgress(setting.get() ? 0.0F : 1.0F);
                    return a;
                });
                check.changeDirection(setting.get() ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS);
                float off = check.calcPercent();
                int x = this.right() - 24;
                int y = top + 6;
                boolean pressedBox = mx >= x && mx < x + 24 && my >= y && my < y + 24;
                c.rounded(x, y, 24, 24, 10, LegacyCanvas.alpha(0xFFC0C0C0, (pressedBox ? 0.6F : 0.43F) * off * p));
                float on = 1.0F - off;
                c.rounded(x, y, 24, 24, 10, LegacyCanvas.alpha(LegacyCanvas.shiftTowardsOther(LIGHT_BLUE, 0xFF010101, pressedBox ? 0.9F : 1.0F), on * p));
                float s = 1.5F - 0.5F * on;
                c.push();
                c.scaleAbout(s, s, x + 12, y + 12);
                c.image(LegacyTexture.CHECK, x, y, 24, 24, LegacyCanvas.alpha(0xFFFEFEFE, on * p));
                c.pop();
            }
            case NUMBER -> {
                NumberSetting setting = (NumberSetting) row.setting();
                this.label(c, setting.getName(), top, p);
                int x = this.right() - 126;
                int y = top + 6;
                float range = setting.getMax() - setting.getMin();
                float fraction = range <= 0 ? 0 : (setting.get() - setting.getMin()) / range;
                int trackL = x + 12;
                int trackR = x + 126 - 12;
                int trackY = y + 12 - 3;
                int knob = trackL + Math.round((trackR - trackL) * fraction);
                c.rounded(trackL, trackY, Math.max(1, knob - trackL), 6, 3, LegacyCanvas.alpha(LIGHT_BLUE, p));
                c.rounded(knob, trackY, Math.max(1, trackR - knob), 6, 3, LegacyCanvas.alpha(lighten(LIGHT_BLUE, 0.8F), p));
                c.outerGlow(knob - 10, y + 2, 20, 20, 10, p * 0.8F * 0.35F);
                c.disc(knob, y + 12, 10, LegacyCanvas.alpha(0xFFFEFEFE, p));
                String value = this.interactions.isEditing(setting) ? this.interactions.displayValue(setting) + "|"
                    : String.format(Locale.ROOT, "%." + setting.getDecimalPlaces() + "f", setting.get());
                float valueW = c.textWidth(Face.JELLO_LIGHT, 14, value);
                c.text(Face.JELLO_LIGHT, 14, value, trackL - valueW - 10, trackY - 5, LegacyCanvas.alpha(0xFF010101, 0.5F * p));
            }
            case ENUM -> {
                EnumSetting<?> setting = (EnumSetting<?>) row.setting();
                this.label(c, setting.getName(), top + 2, p);
                LegacyDropdown dropdown = this.dropdowns.computeIfAbsent(setting, s -> {
                    List<String> labels = new ArrayList<>();
                    for (Enum<?> option : setting.getOptions()) {
                        labels.add(EnumSetting.label(option));
                    }
                    LegacyDropdown d = new LegacyDropdown(0, 0, 123, 27, labels, setting.index());
                    d.onSelect(setting::setIndex);
                    return d;
                });
                dropdown.x = this.right() - 123 + GAP;
                dropdown.y = top + 5;
                if (!dropdown.isOpen()) {
                    dropdown.draw(c, mx, my, p);
                } else {
                    open.add(dropdown);
                }
            }
            case TEXT -> {
                TextSetting setting = (TextSetting) row.setting();
                this.label(c, setting.getName(), top, p);
                LegacyTextField field = this.fields.computeIfAbsent(setting, s -> {
                    LegacyTextField f = new LegacyTextField(LegacyTextField.Style.JELLO, 0, 0, 114, 27, Face.JELLO_LIGHT, 18, "");
                    f.setText(setting.get());
                    f.onChange(x -> setting.set(x.text()));
                    return f;
                });
                field.x = this.right() - 114 + GAP;
                field.y = top + 5;
                if (!field.focused() && !field.text().equals(setting.get())) {
                    field.setText(setting.get());
                }
                field.draw(c, p);
            }
            case COLOR -> {
                ColorSetting setting = (ColorSetting) row.setting();
                this.label(c, setting.getName(), top, p);
                this.drawPicker(c, setting, this.right() - 160 + GAP + 10 - GAP, top, p);
            }
        }
    }

    // ------------------------------------------------------------------ colour picker

    private static final int PICK_W = 160;
    private static final int PICK_H = 114;
    private static final int BLOCK_W = 124;
    private static final int BAR_X = 134;
    private static final int BAR_W = 20;

    private float[] hsvOf(final ColorSetting setting) {
        float[] cached = this.hsv.get(setting);
        int argb = setting.get();
        Color color = new Color(argb, true);
        float[] fresh = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        if (cached == null || (Color.HSBtoRGB(cached[0], cached[1], cached[2]) & 0xFFFFFF) != (argb & 0xFFFFFF)) {
            cached = fresh;
            this.hsv.put(setting, cached);
        }
        return cached;
    }

    private void drawPicker(final LegacyCanvas c, final ColorSetting setting, final int x, final int top, final float p) {
        float[] hsv = this.hsvOf(setting);
        // Saturation runs left to right and brightness bottom to top over the block, in a few vertical bands.
        int bands = 31;
        int y0 = top;
        int height = PICK_H - 14;
        for (int i = 0; i < bands; i++) {
            int x0 = x + i * BLOCK_W / bands;
            int x1 = x + (i + 1) * BLOCK_W / bands;
            float s = (i + 0.5F) / bands;
            int topColor = Color.HSBtoRGB(hsv[0], s, 1.0F) | 0xFF000000;
            c.graphics().fillGradient(x0, y0, x1, y0 + height, LegacyCanvas.fade(topColor, p), LegacyCanvas.fade(0xFF010101, p));
        }
        c.graphics().fill(x - 1, y0 - 1, x + BLOCK_W + 1, y0, LegacyCanvas.alpha(0xFFC0C0C0, 0.6F * p));
        c.graphics().fill(x - 1, y0 + height, x + BLOCK_W + 1, y0 + height + 1, LegacyCanvas.alpha(0xFFC0C0C0, 0.6F * p));
        int cx = x + Math.round(hsv[1] * BLOCK_W);
        int cy = y0 + Math.round((1.0F - hsv[2]) * height);
        c.disc(cx, cy, 7, LegacyCanvas.alpha(0xFF010101, 0.35F * p));
        c.disc(cx, cy, 6, LegacyCanvas.alpha(0xFFFEFEFE, p));
        c.disc(cx, cy, 4, LegacyCanvas.alpha(Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]) | 0xFF000000, p));

        // The hue bar.
        int barBands = 24;
        for (int i = 0; i < barBands; i++) {
            int a = y0 + i * height / barBands;
            int b = y0 + (i + 1) * height / barBands;
            c.graphics().fill(x + BAR_X, a, x + BAR_X + BAR_W, b, LegacyCanvas.fade(Color.HSBtoRGB((i + 0.5F) / barBands, 1.0F, 1.0F) | 0xFF000000, p));
        }
        int hy = y0 + Math.round(hsv[0] * height);
        c.graphics().fill(x + BAR_X - 3, hy - 1, x + BAR_X + BAR_W + 3, hy + 2, LegacyCanvas.alpha(0xFF010101, p));
        c.graphics().fill(x + BAR_X - 2, hy, x + BAR_X + BAR_W + 2, hy + 1, LegacyCanvas.alpha(0xFFFEFEFE, p));

        // The chosen colour and its value, under the block.
        c.rounded(x, y0 + height + 4, 14, 10, 3, LegacyCanvas.fade(setting.get() | 0xFF000000, p));
        String hex = this.interactions.isEditing(setting) ? this.interactions.displayValue(setting) + "|"
            : String.format(Locale.ROOT, "#%06X", setting.get() & 0xFFFFFF);
        c.text(Face.JELLO_LIGHT, 14, hex, x + 20, y0 + height + 2, LegacyCanvas.alpha(0xFF010101, 0.5F * p));
    }

    private boolean pickColor(final ColorSetting setting, final int x, final int top, final double mx, final double my, final int part) {
        int height = PICK_H - 14;
        float[] hsv = this.hsvOf(setting);
        if (part == 1) {
            hsv[1] = (float) Math.max(0, Math.min(1, (mx - x) / BLOCK_W));
            hsv[2] = (float) Math.max(0, Math.min(1, 1.0 - (my - top) / height));
        } else if (part == 2) {
            hsv[0] = (float) Math.max(0, Math.min(0.9999, (my - top) / height));
        } else {
            return false;
        }
        int alpha = setting.get() >>> 24;
        setting.set((alpha == 0 ? 0xFF : alpha) << 24 | Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]) & 0xFFFFFF);
        this.hsv.put(setting, hsv);
        return true;
    }

    private int pickerX() {
        return this.right() - 160 + 10;
    }

    private static int lighten(final int color, final float shift) {
        int a = color >>> 24;
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        return a << 24 | (int) (r + (255 - r) * shift) << 16 | (int) (g + (255 - g) * shift) << 8 | (int) (b + (255 - b) * shift);
    }

    // ------------------------------------------------------------------ input

    void mouseClicked(final double mx, final double my, final MouseButtonEvent event, final int w, final int h) {
        this.layout(w, h);
        if (mx < this.cardX || mx >= this.cardX + CARD_W || my < this.cardY || my >= this.cardY + this.cardH) {
            this.close();
            return;
        }
        for (LegacyTextField field : this.fields.values()) {
            field.setFocused(false);
        }
        if (event.button() == 0 && this.scroll.press(mx, my, this.listX + CARD_W - 20, this.listY, this.listH, this.contentH, this.listH)) {
            return;
        }
        if (mx < this.listX || mx >= this.listX + CARD_W - 20 || my < this.listY || my >= this.listY + this.listH) {
            return;
        }
        int gs = Minecraft.getInstance().getWindow().getGuiScale();
        for (LegacyDropdown dropdown : this.dropdowns.values()) {
            if (dropdown.isOpen() && dropdown.contains(mx, my)) {
                dropdown.mouseClicked(mx, my);
                return;
            }
        }
        for (Row row : this.rows) {
            int top = this.listY + row.top() - this.scroll.offset();
            if (my < top - 6 || my >= top + row.height() + 6) {
                continue;
            }
            switch (row.kind()) {
                case BIND -> {
                    int pillX = this.right() - 150;
                    if (mx >= pillX && mx < pillX + 150) {
                        this.interactions.handleKeybindClick(this.module, event.button());
                    }
                }
                case BOOLEAN -> {
                    int x = this.right() - 24;
                    if (event.button() == 0 && mx >= x - 6 && mx < x + 30) {
                        ((BooleanSetting) row.setting()).toggle();
                    }
                }
                case NUMBER -> {
                    int x = this.right() - 126;
                    if (mx >= x - 4 && mx < x + 130) {
                        if (event.button() == 0) {
                            this.interactions.handleSettingClick(row.setting(), (int) (mx / gs), (x + 12) / gs, (x + 126 - 12) / gs);
                        } else if (event.button() == 1) {
                            NumberSetting number = (NumberSetting) row.setting();
                            this.interactions.startEditing(number, String.format(Locale.ROOT, "%." + number.getDecimalPlaces() + "f", number.get()));
                        }
                    }
                }
                case ENUM -> {
                    LegacyDropdown dropdown = this.dropdowns.get(row.setting());
                    if (dropdown != null && dropdown.contains(mx, my)) {
                        for (LegacyDropdown other : this.dropdowns.values()) {
                            if (other != dropdown) {
                                other.close();
                            }
                        }
                        dropdown.mouseClicked(mx, my);
                    }
                }
                case TEXT -> {
                    LegacyTextField field = this.fields.get(row.setting());
                    if (field != null) {
                        field.mouseClicked(mx, my);
                    }
                }
                case COLOR -> {
                    ColorSetting setting = (ColorSetting) row.setting();
                    int x = this.pickerX();
                    int height = PICK_H - 14;
                    if (event.button() == 1 || (mx >= x + 20 && mx < x + BLOCK_W && my >= top + height + 2 && my < top + height + 16)) {
                        this.interactions.handleSettingClick(setting, (int) (mx / gs), 0, 1);
                    } else if (mx >= x && mx < x + BLOCK_W && my >= top && my < top + height) {
                        this.pressedColor = setting;
                        this.pressedColorPart = 1;
                        this.pickColor(setting, x, top, mx, my, 1);
                    } else if (mx >= x + BAR_X && mx < x + BAR_X + BAR_W && my >= top && my < top + height) {
                        this.pressedColor = setting;
                        this.pressedColorPart = 2;
                        this.pickColor(setting, x, top, mx, my, 2);
                    }
                }
            }
            return;
        }
    }

    void mouseDragged(final double mx, final double my) {
        this.scroll.drag(my, this.listY, this.listH, this.contentH, this.listH);
        for (LegacyTextField field : this.fields.values()) {
            field.mouseDragged(mx);
        }
        if (this.pressedColor instanceof ColorSetting color) {
            for (Row row : this.rows) {
                if (row.setting() == color) {
                    this.pickColor(color, this.pickerX(), this.listY + row.top() - this.scroll.offset(), mx, my, this.pressedColorPart);
                }
            }
        }
    }

    void mouseReleased() {
        this.scroll.release();
        this.pressedColor = null;
    }

    void wheel(final double mx, final double my, final double delta) {
        for (LegacyDropdown dropdown : this.dropdowns.values()) {
            if (dropdown.isOpen()) {
                return;
            }
        }
        this.scroll.wheel(delta, this.contentH, this.listH);
    }

    boolean keyPressed(final KeyEvent event) {
        for (LegacyTextField field : this.fields.values()) {
            if (field.focused()) {
                if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                    field.setFocused(false);
                    return true;
                }
                return field.keyPressed(event) || event.key() != GLFW.GLFW_KEY_ESCAPE;
            }
        }
        return false;
    }

    boolean charTyped(final CharacterEvent event) {
        for (LegacyTextField field : this.fields.values()) {
            if (field.focused()) {
                return field.charTyped(event);
            }
        }
        return false;
    }
}
