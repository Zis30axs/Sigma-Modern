package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.module.BindMode;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Shared interaction state for Sigma ClickGUI screens.
 *
 * <p>This deliberately owns only transient UI interaction state: keybind capture, text/color editing,
 * and number-slider dragging. It never stores a second copy of module or setting business state.</p>
 */
public final class ClickGuiInteractions {

    private @Nullable Module bindingModule;
    private @Nullable Setting<?> editingSetting;
    private String editingBuffer = "";

    private @Nullable NumberSetting draggingNumber;
    private int draggingTrackLeft;
    private int draggingTrackRight;

    public boolean mouseClickedBinding(final MouseButtonEvent event) {
        if (this.bindingModule == null) {
            return false;
        }

        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(event.button());
        this.bindingModule.setKeybind(Keybind.of(key));
        this.bindingModule = null;
        return true;
    }

    public void startBind(final Module module) {
        this.bindingModule = module;
    }

    public boolean isBinding(final Module module) {
        return this.bindingModule == module;
    }

    public boolean keyPressed(final KeyEvent event) {
        if (this.bindingModule != null) {
            if (event.isEscape()) {
                this.bindingModule = null;
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                this.bindingModule.setKeybind(Keybind.UNBOUND);
                this.bindingModule = null;
                return true;
            }

            this.bindingModule.setKeybind(Keybind.of(InputConstants.getKey(event)));
            this.bindingModule = null;
            return true;
        }

        if (this.editingSetting != null) {
            if (event.isEscape()) {
                this.editingSetting = null;
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                this.commitEdit();
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!this.editingBuffer.isEmpty()) {
                    this.editingBuffer = this.editingBuffer.substring(0, this.editingBuffer.length() - 1);
                }
                return true;
            }

            return true;
        }

        return false;
    }

    public boolean charTyped(final CharacterEvent event) {
        if (this.editingSetting == null) {
            return false;
        }

        if (event.isAllowedChatCharacter()) {
            this.editingBuffer = this.editingBuffer + event.codepointAsString();
        }
        return true;
    }

    public boolean mouseDragged(final MouseButtonEvent event) {
        if (this.draggingNumber == null) {
            return false;
        }

        this.updateDraggedNumber((int) event.x());
        return true;
    }

    public void mouseReleased() {
        this.draggingNumber = null;
    }

    public void handleKeybindClick(final Module module, final int button) {
        if (button == 0) {
            this.startBind(module);
        } else if (button == 1) {
            this.cycleKeybindMode(module);
        }
    }

    public void handleSettingClick(final Setting<?> setting, final int mouseX, final int trackLeft, final int trackRight) {
        if (setting instanceof BooleanSetting bool) {
            bool.toggle();
            return;
        }

        if (setting instanceof NumberSetting number) {
            this.draggingNumber = number;
            this.draggingTrackLeft = trackLeft;
            this.draggingTrackRight = trackRight;
            this.updateDraggedNumber(mouseX);
            return;
        }

        if (setting instanceof EnumSetting<?> enumSetting) {
            enumSetting.cycle();
            return;
        }

        if (setting instanceof ColorSetting color) {
            this.editingSetting = setting;
            this.editingBuffer = this.colorDisplay(color.get());
            return;
        }

        if (setting instanceof TextSetting) {
            this.editingSetting = setting;
            this.editingBuffer = setting.get().toString();
        }
    }

    public boolean isEditing(final Setting<?> setting) {
        return this.editingSetting == setting;
    }

    public String displayValue(final Setting<?> setting) {
        if (this.editingSetting == setting) {
            return this.editingBuffer;
        }
        if (setting instanceof ColorSetting color) {
            return this.colorDisplay(color.get());
        }
        return setting.get().toString();
    }

    public String keybindDisplay(final Keybind keybind) {
        if (!keybind.isBound()) {
            return "UNBOUND";
        }
        return keybind.key().getName();
    }

    private void updateDraggedNumber(final int mouseX) {
        NumberSetting number = this.draggingNumber;
        if (number == null) {
            return;
        }

        float range = number.getMax() - number.getMin();
        if (range <= 0.0F) {
            return;
        }

        float fraction = (mouseX - this.draggingTrackLeft) / (float) (this.draggingTrackRight - this.draggingTrackLeft);
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        float raw = number.getMin() + fraction * range;
        float stepped = Math.round(raw / number.getStep()) * number.getStep();
        number.set(stepped);
    }

    private void commitEdit() {
        Setting<?> setting = this.editingSetting;
        if (setting == null) {
            return;
        }

        if (setting instanceof TextSetting text) {
            text.set(this.editingBuffer);
        } else if (setting instanceof ColorSetting color) {
            this.parseColor(this.editingBuffer).ifPresent(color::set);
        }

        this.editingSetting = null;
    }

    private Optional<Integer> parseColor(final String input) {
        String text = input.trim();
        try {
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 6) {
                    return Optional.of(0xFF000000 | Integer.parseInt(hex, 16));
                }
                if (hex.length() == 8) {
                    return Optional.of((int) Long.parseLong(hex, 16));
                }
                return Optional.empty();
            }
            return Optional.of(Long.decode(text).intValue());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private String colorDisplay(final int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    private void cycleKeybindMode(final Module module) {
        BindMode[] modes = BindMode.values();
        BindMode current = module.getKeybind().mode();
        module.setKeybind(new Keybind(module.getKeybind().key(), modes[(current.ordinal() + 1) % modes.length]));
    }
}
