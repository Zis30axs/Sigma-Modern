package com.mentalfrostbyte.jello.gui.classic;

import com.mentalfrostbyte.jello.gui.ClickGuiInteractions;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * A module's settings, slid up over the Classic ClickGUI's list ({@code Class4345}).
 *
 * <p>A pale grey sheet wipes up from the bottom of the panel over 150 ms. It has "<Module> Settings" at the top
 * and the settings below in one column: the name in 20 px type, the control under or beside it, and the setting's
 * description in a grey strip to the right. A boolean is the slide-switch, a number a thin slider with a small
 * grip and its value in the name, a choice a dark box that opens into a list, text a dark input. Coordinates here
 * are the panel's own.</p>
 */
final class ClassicSettingsPage {
    private static final int X = 5;
    private static final int Y = 70;
    private static final int ROW_X = 35;
    private static final int FIRST_ROW = 35;

    private enum Kind {
        BIND, BOOLEAN, NUMBER, ENUM, TEXT, COLOR
    }

    private record Row(Kind kind, Setting<?> setting, int top, int height) {
    }

    private final Module module;
    private final ClickGuiInteractions interactions;
    private final Animation slide = new Animation(150, 150, Animation.Direction.FORWARDS);
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.CLASSIC);
    private final Map<Setting<?>, Animation> switches = new HashMap<>();
    private final Map<Setting<?>, LegacyTextField> fields = new HashMap<>();
    private boolean closing;
    private NumberSetting dragging;
    private EnumSetting<?> openChoice;
    private List<Row> rows = List.of();
    private int contentH;
    private int regionH;

    ClassicSettingsPage(final Module module, final ClickGuiInteractions interactions) {
        this.module = module;
        this.interactions = interactions;
    }

    void close() {
        this.closing = true;
        this.slide.changeDirection(Animation.Direction.BACKWARDS);
        this.openChoice = null;
        for (LegacyTextField field : this.fields.values()) {
            field.setFocused(false);
        }
    }

    boolean finished() {
        return this.closing && this.slide.calcPercent() <= 0.0F;
    }

    boolean typing() {
        for (LegacyTextField field : this.fields.values()) {
            if (field.focused()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ layout

    private void layout(final int pw, final int ph) {
        this.regionH = ph - 75;
        List<Row> rows = new ArrayList<>();
        int y = FIRST_ROW;
        rows.add(new Row(Kind.BIND, null, y, 18));
        y += 28;
        for (Setting<?> setting : this.module.settings()) {
            if (!setting.isVisible()) {
                continue;
            }
            Kind kind;
            int pitch;
            if (setting instanceof BooleanSetting) {
                kind = Kind.BOOLEAN;
                pitch = 28;
            } else if (setting instanceof NumberSetting) {
                kind = Kind.NUMBER;
                pitch = 44;
            } else if (setting instanceof EnumSetting<?>) {
                kind = Kind.ENUM;
                pitch = 55;
            } else if (setting instanceof ColorSetting) {
                kind = Kind.COLOR;
                pitch = 55;
            } else {
                kind = Kind.TEXT;
                pitch = 55;
            }
            rows.add(new Row(kind, setting, y, pitch));
            y += pitch;
        }
        this.rows = rows;
        this.contentH = y + 20;
    }

    // ------------------------------------------------------------------ drawing

    void draw(final LegacyCanvas c, final double mx, final double my, final float alpha, final int pw, final int ph) {
        this.layout(pw, ph);
        this.scroll.clamp(this.contentH, this.regionH);
        float t = this.closing ? Easing.easeInCubic(this.slide.calcPercent(), 0, 1, 1) : Easing.easeOutCubic(this.slide.calcPercent(), 0, 1, 1);
        int shown = Math.round(this.regionH * t);
        int top = Y + this.regionH - shown;
        int width = pw - 10;

        c.scissor(X, top, X + width, Y + this.regionH);
        c.fill(X, top, X + width, Y + this.regionH, LegacyCanvas.fade(0xFFD7D7D7, alpha));
        c.text(Face.CLASSIC, 20, this.module.getName() + " Settings", X + 12, Y + 2, LegacyCanvas.alpha(0xFF010101, alpha));

        for (Row row : this.rows) {
            int y = Y + row.top() - this.scroll.offset();
            if (y + row.height() < Y || y > Y + this.regionH) {
                continue;
            }
            this.drawRow(c, row, y, mx, my, alpha);
        }
        // An open choice folds out over the rows below it.
        if (this.openChoice != null) {
            for (Row row : this.rows) {
                if (row.setting() == this.openChoice) {
                    this.drawChoices(c, this.openChoice, Y + row.top() - this.scroll.offset() + 27, mx, my, alpha);
                }
            }
        }
        c.unscissor();
        this.scroll.draw(c, X + width, Y, this.regionH, this.contentH, this.regionH, mx >= X && mx < X + width && my >= Y && my < Y + this.regionH, alpha);
    }

    private void description(final LegacyCanvas c, final Setting<?> setting, final int x, final int y, final float alpha) {
        c.fill(x, y, x + 330, y + 18, LegacyCanvas.fade(0xFFC8C8C8, alpha));
        c.scissor(x, y, x + 330, y + 18);
        c.text(Face.CLASSIC, 17, setting.getDescription(), x + 5, y - 2, LegacyCanvas.alpha(0xFF222222, alpha));
        c.unscissor();
    }

    private void drawRow(final LegacyCanvas c, final Row row, final int y, final double mx, final double my, final float alpha) {
        int x = ROW_X;
        switch (row.kind()) {
            case BIND -> {
                c.text(Face.CLASSIC, 20, "Keybind", x, y, LegacyCanvas.alpha(0xFF010101, alpha));
                boolean binding = this.interactions.isBinding(this.module);
                String key = binding ? "Press a key..." : LegacyKeys.name(this.module.getKeybind());
                String mode = this.module.getKeybind().mode().name().charAt(0) + this.module.getKeybind().mode().name().substring(1).toLowerCase(Locale.ROOT);
                this.choiceBox(c, x + 100, y - 1, 120, 20, key + "  (" + mode + ")", mx, my, alpha, false);
            }
            case BOOLEAN -> {
                BooleanSetting setting = (BooleanSetting) row.setting();
                c.text(Face.CLASSIC, 20, setting.getName(), x, y, LegacyCanvas.alpha(0xFF010101, alpha));
                this.drawSwitch(c, setting, x + 135, y + 4, alpha);
                this.description(c, setting, x + 195, y + 4, alpha);
            }
            case NUMBER -> {
                NumberSetting setting = (NumberSetting) row.setting();
                c.text(Face.CLASSIC, 20, setting.getName() + ": " + this.shown(setting), x, y, LegacyCanvas.alpha(0xFF010101, alpha));
                float range = setting.getMax() - setting.getMin();
                float fraction = range <= 0 ? 0 : (setting.get() - setting.getMin()) / range;
                this.slider(c, x, y + 31, 240, 4, fraction, alpha);
                this.description(c, setting, x + 195, y + 4, alpha);
            }
            case ENUM -> {
                EnumSetting<?> setting = (EnumSetting<?>) row.setting();
                c.text(Face.CLASSIC, 20, setting.getName(), x, y, LegacyCanvas.alpha(0xFF010101, alpha));
                this.choiceBox(c, x, y + 27, 80, 20, EnumSetting.label(setting.get()), mx, my, alpha, this.openChoice == setting);
                this.description(c, setting, x + 195, y + 4, alpha);
            }
            case TEXT -> {
                TextSetting setting = (TextSetting) row.setting();
                c.text(Face.CLASSIC, 20, setting.getName(), x, y, LegacyCanvas.alpha(0xFF010101, alpha));
                LegacyTextField field = this.fields.computeIfAbsent(setting, s -> {
                    LegacyTextField f = new LegacyTextField(LegacyTextField.Style.CLASSIC, 0, 0, 114, 27, Face.CLASSIC, 17, "");
                    f.setText(setting.get());
                    f.onChange(text -> setting.set(text.text()));
                    return f;
                });
                field.x = x;
                field.y = y + 27;
                if (!field.focused() && !field.text().equals(setting.get())) {
                    field.setText(setting.get());
                }
                field.draw(c, alpha);
                this.description(c, setting, x + 195, y + 4, alpha);
            }
            case COLOR -> {
                ColorSetting setting = (ColorSetting) row.setting();
                c.text(Face.CLASSIC, 20, setting.getName(), x, y, LegacyCanvas.alpha(0xFF010101, alpha));
                String text = this.interactions.isEditing(setting) ? this.interactions.displayValue(setting) + "|"
                    : String.format(Locale.ROOT, "#%06X", setting.get() & 0xFFFFFF);
                c.fill(x, y + 27, x + 20, y + 47, LegacyCanvas.fade(setting.get() | 0xFF000000, alpha));
                ClassicButton.frame(c, x, y + 27, x + 20, y + 47, 1, LegacyCanvas.alpha(0xFF010101, alpha));
                this.choiceBox(c, x + 26, y + 27, 88, 20, text, mx, my, alpha, false);
                this.description(c, setting, x + 195, y + 4, alpha);
            }
        }
    }

    private String shown(final NumberSetting setting) {
        return this.interactions.isEditing(setting) ? this.interactions.displayValue(setting) + "|"
            : String.format(Locale.ROOT, "%." + setting.getDecimalPlaces() + "f", setting.get());
    }

    private void drawSwitch(final LegacyCanvas c, final Setting<?> setting, final int x, final int y, final float alpha) {
        BooleanSetting bool = (BooleanSetting) setting;
        Animation slide = this.switches.computeIfAbsent(setting, s -> {
            Animation a = new Animation(200, 200, bool.get() ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            a.setProgress(bool.get() ? 1.0F : 0.0F);
            return a;
        });
        slide.changeDirection(bool.get() ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        int frame = Math.round(19 * slide.calcPercent());
        c.region(LegacyTexture.GUI_CHECKBOX.id, LegacyTexture.GUI_CHECKBOX.width, LegacyTexture.GUI_CHECKBOX.height, frame * 40, 0, 40, 18,
            x, y, 40, 18, LegacyCanvas.alpha(0xFFFEFEFE, alpha));
    }

    /** The old slider: a dark track, a lighter filled part, and a small square-ended grip. */
    private void slider(final LegacyCanvas c, final int x, final int y, final int w, final int h, final float fraction, final float alpha) {
        int dark = LegacyCanvas.fade(0xFF787878, alpha);
        int light = LegacyCanvas.fade(0xFFA0A0A0, alpha);
        c.fill(x + w, y + 1, x + w + 1, y + h - 1, dark);
        c.fill(x + 1, y, x + w, y + h, dark);
        c.fill(x, y + 1, x + 1, y + h - 1, light);
        c.fill(x + 1, y, x + Math.max(1, Math.round(w * fraction)), y + h, light);
        int px = Math.round(x + w * fraction) - 2;
        int py = y - 3;
        int body = LegacyCanvas.fade(0xFFC8C8C8, alpha);
        int edge = LegacyCanvas.fade(0xFFB4B4B4, alpha);
        c.fill(px + 1, py + 1, px + 5, py + 9, body);
        c.fill(px, py + 1, px + 1, py + 9, edge);
        c.fill(px + 5, py + 1, px + 6, py + 9, edge);
        c.fill(px + 1, py, px + 5, py + 1, edge);
        c.fill(px + 1, py + 9, px + 5, py + 10, edge);
    }

    /** The dark box the old choices and fields used, with its little arrow. */
    private void choiceBox(final LegacyCanvas c, final int x, final int y, final int w, final int h, final String text, final double mx, final double my, final float alpha, final boolean open) {
        c.fill(x, y, x + w, y + h, LegacyCanvas.fade(0xFF222222, alpha));
        ClassicButton.frame(c, x, y, x + w, y + h, 1, LegacyCanvas.alpha(0xFF010101, alpha));
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            ClassicButton.frame(c, x + 1, y + 1, x + w - 1, y + h - 1, 1, LegacyCanvas.alpha(0xFFFEFEFE, 0.25F * alpha));
        }
        int ax = x + w - 11;
        int ay = y + h - 12;
        int grey = LegacyCanvas.fade(0xFF999999, alpha);
        for (int row = 0; row < 3; row++) {
            int inset = open ? 2 - row : row;
            c.fill(ax + inset, ay + row, ax + 6 - inset, ay + row + 1, grey);
        }
        c.scissor(x, y, x + w - 12, y + h);
        c.text(Face.CLASSIC, 15, text, x + 7, y + (h - c.textHeight(Face.CLASSIC, 15)) / 2.0F, LegacyCanvas.alpha(0xFFFEFEFE, 0.5F * alpha));
        c.unscissor();
    }

    private void drawChoices(final LegacyCanvas c, final EnumSetting<?> setting, final int top, final double mx, final double my, final float alpha) {
        List<? extends Enum<?>> options = setting.getOptions();
        for (int i = 0; i < options.size(); i++) {
            int y = top + 20 * i + 20;
            boolean hover = mx >= ROW_X && mx < ROW_X + 80 && my >= y && my < y + 20;
            c.fill(ROW_X, y, ROW_X + 80, y + 20, LegacyCanvas.fade(hover ? 0xFF3A3A3A : 0xFF222222, alpha));
            ClassicButton.frame(c, ROW_X, y, ROW_X + 80, y + 20, 1, LegacyCanvas.alpha(0xFF010101, alpha));
            c.scissor(ROW_X, y, ROW_X + 80, y + 20);
            c.text(Face.CLASSIC, 15, EnumSetting.label(options.get(i)), ROW_X + 7, y + (20 - c.textHeight(Face.CLASSIC, 15)) / 2.0F,
                LegacyCanvas.alpha(0xFFFEFEFE, (i == setting.index() ? 1.0F : 0.5F) * alpha));
            c.unscissor();
        }
    }

    // ------------------------------------------------------------------ input

    boolean mouseClicked(final double mx, final double my, final int button, final int pw, final int ph) {
        if (this.closing) {
            return false;
        }
        this.layout(pw, ph);
        if (mx < X || mx >= X + pw - 10 || my < Y || my >= Y + this.regionH) {
            return false;
        }
        for (LegacyTextField field : this.fields.values()) {
            field.setFocused(false);
        }
        if (button == 0 && this.scroll.press(mx, my, X + pw - 10, Y, this.regionH, this.contentH, this.regionH)) {
            return true;
        }

        if (this.openChoice != null) {
            for (Row row : this.rows) {
                if (row.setting() != this.openChoice) {
                    continue;
                }
                int top = Y + row.top() - this.scroll.offset() + 27;
                for (int i = 0; i < this.openChoice.getOptions().size(); i++) {
                    int y = top + 20 * i + 20;
                    if (mx >= ROW_X && mx < ROW_X + 80 && my >= y && my < y + 20) {
                        this.openChoice.setIndex(i);
                        this.openChoice = null;
                        return true;
                    }
                }
            }
            this.openChoice = null;
        }

        for (Row row : this.rows) {
            int y = Y + row.top() - this.scroll.offset();
            if (my < y - 4 || my >= y + row.height() + 4) {
                continue;
            }
            switch (row.kind()) {
                case BIND -> {
                    if (mx >= ROW_X + 100 && mx < ROW_X + 220) {
                        this.interactions.handleKeybindClick(this.module, button);
                    }
                }
                case BOOLEAN -> {
                    if (mx >= ROW_X + 135 && mx < ROW_X + 175 && my >= y + 4 && my < y + 22) {
                        ((BooleanSetting) row.setting()).toggle();
                    }
                }
                case NUMBER -> {
                    NumberSetting setting = (NumberSetting) row.setting();
                    if (mx >= ROW_X - 4 && mx < ROW_X + 244 && my >= y + 24 && my < y + 42) {
                        this.dragging = setting;
                        this.setFromPointer(setting, mx);
                    } else if (button == 1) {
                        this.interactions.startEditing(setting, String.format(Locale.ROOT, "%." + setting.getDecimalPlaces() + "f", setting.get()));
                    }
                }
                case ENUM -> {
                    if (mx >= ROW_X && mx < ROW_X + 80 && my >= y + 27 && my < y + 47) {
                        this.openChoice = (EnumSetting<?>) row.setting();
                    }
                }
                case TEXT -> {
                    LegacyTextField field = this.fields.get(row.setting());
                    if (field != null) {
                        field.mouseClicked(mx, my);
                    }
                }
                case COLOR -> {
                    if (mx >= ROW_X && mx < ROW_X + 114 && my >= y + 27 && my < y + 47) {
                        this.interactions.handleSettingClick(row.setting(), 0, 0, 1);
                    }
                }
            }
            return true;
        }
        return true;
    }

    private void setFromPointer(final NumberSetting setting, final double mx) {
        float fraction = (float) Math.max(0.0, Math.min(1.0, (mx - ROW_X) / 240.0));
        float raw = setting.getMin() + fraction * (setting.getMax() - setting.getMin());
        setting.set(Math.round(raw / setting.getStep()) * setting.getStep());
    }

    boolean mouseDragged(final double mx, final double my) {
        if (this.dragging != null) {
            this.setFromPointer(this.dragging, mx);
            return true;
        }
        this.scroll.drag(my, Y, this.regionH, this.contentH, this.regionH);
        for (LegacyTextField field : this.fields.values()) {
            field.mouseDragged(mx);
        }
        return this.scroll.dragging();
    }

    void mouseReleased() {
        this.dragging = null;
        this.scroll.release();
    }

    void wheel(final double delta) {
        this.scroll.wheel(delta, this.contentH, this.regionH);
    }

    boolean keyPressed(final KeyEvent event) {
        for (LegacyTextField field : this.fields.values()) {
            if (field.focused()) {
                if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                    field.setFocused(false);
                    return true;
                }
                return field.keyPressed(event) || true;
            }
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && this.openChoice != null) {
            this.openChoice = null;
            return true;
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
