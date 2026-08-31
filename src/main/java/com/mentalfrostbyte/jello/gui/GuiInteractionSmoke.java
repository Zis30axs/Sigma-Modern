package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small in-client smoke test for the shared ClickGUI interaction layer.
 *
 * <p>It is only active when {@code -Dsigma.debug.smoke=true} is passed. It exercises the same
 * {@link ClickGuiInteractions} code that Jello / Classic / Minimal all use, without requiring a human
 * to click through the UI.</p>
 */
public final class GuiInteractionSmoke {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/GuiSmoke");

    private GuiInteractionSmoke() {
    }

    public static void run(final ModuleManager modules) {
        ClickGuiInteractions interactions = new ClickGuiInteractions();
        Module module = modules.all().stream().findFirst().orElse(null);
        if (module == null) {
            LOGGER.warn("No modules available for GUI interaction smoke test");
            return;
        }

        // Toggle business state directly to prove the GUI never owns a second copy.
        boolean original = module.isEnabled();
        module.setEnabled(true);
        module.setEnabled(false);
        module.setEnabled(original);

        // Keybind capture through the shared interaction helper.
        interactions.startBind(module);
        interactions.keyPressed(new KeyEvent(InputConstants.KEY_R, 0, 0));
        if (!module.getKeybind().isBound()) {
            throw new IllegalStateException("Keybind capture smoke failed");
        }
        module.setKeybind(Keybind.UNBOUND);

        // Switching ClientMode must never change Module.enabled.
        ClientMode originalMode = Client.getInstance().getClientModeManager().get();
        module.setEnabled(true);
        for (ClientMode mode : ClientMode.values()) {
            Client.getInstance().getClientModeManager().set(mode);
            if (!module.isEnabled()) {
                throw new IllegalStateException("ClientMode " + mode + " changed Module.enabled");
            }
        }
        Client.getInstance().getClientModeManager().set(originalMode);
        module.setEnabled(original);

        // Exercise every current Setting type through the shared helper.
        for (Setting<?> setting : module.settings()) {
            if (setting instanceof BooleanSetting bool) {
                interactions.handleSettingClick(bool, 0, 0, 100);
                interactions.handleSettingClick(bool, 0, 0, 100);
            } else if (setting instanceof NumberSetting number) {
                interactions.handleSettingClick(number, 80, 0, 100);
                interactions.mouseReleased();
            } else if (setting instanceof EnumSetting<?> enumSetting) {
                interactions.handleSettingClick(enumSetting, 0, 0, 100);
            } else if (setting instanceof ColorSetting color) {
                interactions.handleSettingClick(color, 0, 0, 100);
                interactions.charTyped(new CharacterEvent('A'));
                interactions.charTyped(new CharacterEvent('B'));
                interactions.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            } else if (setting instanceof TextSetting text) {
                interactions.handleSettingClick(text, 0, 0, 100);
                interactions.charTyped(new CharacterEvent('S'));
                interactions.charTyped(new CharacterEvent('M'));
                interactions.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            }
        }

        // Mouse drag/release through the shared helper.
        NumberSetting number = findNumber(module);
        if (number != null) {
            interactions.handleSettingClick(number, 50, 0, 100);
            interactions.mouseDragged(new MouseButtonEvent(70, 0, new MouseButtonInfo(0, 0)));
            interactions.mouseReleased();
        }

        LOGGER.info("Sigma debug: GUI interaction smoke passed");
    }

    private static NumberSetting findNumber(final Module module) {
        for (Setting<?> setting : module.settings()) {
            if (setting instanceof NumberSetting number) {
                return number;
            }
        }
        return null;
    }
}
