package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.gui.classic.ClassicClickGuiScreen;
import com.mentalfrostbyte.jello.gui.click.ClickGuiScreen;
import com.mentalfrostbyte.jello.gui.jello.JelloClickGuiScreen;
import com.mentalfrostbyte.jello.gui.noaddons.NoAddonsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screen-level click smoke test.
 *
 * <p>This sends synthetic mouse clicks through the real {@link Screen#mouseClicked} path so the actual
 * hit-testing and navigation code is exercised, not just the shared interaction helper.</p>
 */
public final class GuiScreenInteractionSmoke {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/GuiScreenSmoke");

    private GuiScreenInteractionSmoke() {
    }

    public static void run(final Screen screen) {
        if (screen instanceof NoAddonsScreen) {
            // NoAddons has no module browser; its Switch button is the only interaction.
            int x = screen.width / 2;
            int y = 110;
            screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
            screen.resize(Math.max(100, screen.width - 2), Math.max(100, screen.height - 2));
            screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            LOGGER.info("Sigma debug: NoAddons screen click/resize/escape smoke passed");
            return;
        }

        if (screen instanceof JelloClickGuiScreen || screen instanceof ClickGuiScreen) {
            runThreePanelClickSmoke(screen);
        } else if (screen instanceof ClassicClickGuiScreen) {
            runClassicClickSmoke(screen);
        } else {
            LOGGER.warn("Sigma debug: unknown screen for click smoke: {}", screen.getClass().getName());
        }
    }

    private static void runThreePanelClickSmoke(final Screen screen) {
        boolean jello = screen instanceof JelloClickGuiScreen;
        int panelTop = jello ? 36 : 26;
        int rowHeight = jello ? 24 : 18;
        int categoryX = jello ? 8 : 8;
        int moduleX = jello ? 146 : 126;
        int settingsX = jello ? 324 : 274;

        int firstListY = panelTop + 16;
        screen.mouseClicked(new MouseButtonEvent(categoryX + 12, firstListY + 6, new MouseButtonInfo(0, 0)), false);
        screen.mouseClicked(new MouseButtonEvent(moduleX + 12, firstListY + 6, new MouseButtonInfo(0, 0)), false);

        int keybindY = panelTop + 20;
        int settingsListTop = keybindY + rowHeight + 2;
        screen.mouseClicked(new MouseButtonEvent(settingsX + 12, settingsListTop + 6, new MouseButtonInfo(0, 0)), false);

        screen.resize(Math.max(100, screen.width - 2), Math.max(100, screen.height - 2));
        screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
        LOGGER.info("Sigma debug: three-panel screen click/resize/escape smoke passed");
    }

    private static void runClassicClickSmoke(final Screen screen) {
        int cardWidth = 170;
        int cardHeight = 52;
        int gap = 8;
        int startX = (screen.width - 2 * cardWidth - gap) / 2;

        // Category grid first card.
        screen.mouseClicked(new MouseButtonEvent(startX + 12, 60 + 12, new MouseButtonInfo(0, 0)), false);

        // Module grid first card.
        screen.mouseClicked(new MouseButtonEvent(startX + 12, 50 + 12, new MouseButtonInfo(0, 0)), false);

        // Settings first row.
        int settingsListTop = 28 + 18 + 4;
        screen.mouseClicked(new MouseButtonEvent(20, settingsListTop + 6, new MouseButtonInfo(0, 0)), false);

        screen.resize(Math.max(100, screen.width - 2), Math.max(100, screen.height - 2));
        screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
        LOGGER.info("Sigma debug: classic screen click/resize/escape smoke passed");
    }
}
