package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.gui.classic.ClassicClickGuiScreen;
import com.mentalfrostbyte.jello.gui.click.ClickGuiScreen;
import com.mentalfrostbyte.jello.gui.jello.JelloClickGuiScreen;
import com.mentalfrostbyte.jello.gui.noaddons.NoAddonsScreen;
import com.mentalfrostbyte.jello.gui.modern.ModernClickGuiScreen;
import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
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
        if (screen instanceof ModernClickGuiScreen) {
            runModernClickSmoke(screen);
            return;
        }
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

    private static void runModernClickSmoke(final Screen screenBase) {
        ModernClickGuiScreen screen = (ModernClickGuiScreen) screenBase;
        ModuleManager modules = Client.getInstance().getModuleManager();
        Module module = modules.all().stream().findFirst().orElseThrow();
        ModuleCategory category = module.getCategory();

        int panelX = screen.panelX(), panelY = screen.panelY();
        int sidebarX = panelX + ModernClickGuiScreen.PAD + ModernClickGuiScreen.SIDEBAR_W / 2;
        int sidebarY = panelY + ModernClickGuiScreen.HEADER_H
            + category.ordinal() * screen.sidebarItemHeight() + screen.sidebarItemHeight() / 2;

        boolean original = module.isEnabled();
        try {
            // Switch to the module's own sidebar category, then toggle it from its tile.
            screen.mouseClicked(new MouseButtonEvent(sidebarX, sidebarY, new MouseButtonInfo(0, 0)), false);
            int[] tile = screen.moduleBounds(module);
            if (tile == null) throw new IllegalStateException("Modern category view has no tile for " + module.getName());
            int rowX = tile[0], rowY = tile[1];
            screen.mouseClicked(new MouseButtonEvent(rowX + 12, rowY + 8, new MouseButtonInfo(0, 0)), false);
            if (module.isEnabled() == original) throw new IllegalStateException("Modern row click did not toggle module");
            boolean afterToggle = module.isEnabled();

            // Right click drills into the detail view without changing the module's state.
            screen.mouseClicked(new MouseButtonEvent(rowX + 12, rowY + 8, new MouseButtonInfo(1, 0)), false);
            if (module.isEnabled() != afterToggle) throw new IllegalStateException("Modern detail click changed module state");

            // A module with many visible settings can push its keybind row past the panel's clickable
            // strip, so ask the screen to scroll it into view rather than assuming it starts on-screen.
            int[] keybindBounds = screen.scrollToKeybindRow();
            if (keybindBounds == null) throw new IllegalStateException("Modern detail view has no keybind row");
            screen.mouseClicked(new MouseButtonEvent(keybindBounds[0] + 12, keybindBounds[1] + 8, new MouseButtonInfo(0, 0)), false);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            if (net.minecraft.client.Minecraft.getInstance().gui.screen() != screen) {
                throw new IllegalStateException("Modern bind cancellation closed screen");
            }

            // A second Escape (no longer binding) pops the detail view back to the category list rather
            // than closing the screen.
            screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            if (net.minecraft.client.Minecraft.getInstance().gui.screen() != screen) {
                throw new IllegalStateException("Modern back-navigation Escape closed screen");
            }

            int width = screen.width, height = screen.height;
            screen.resize(320, 240);
            screen.mouseScrolled(100, 100, 0, -50);
            screen.resize(width, height);
            screen.mouseScrolled(100, 100, 0, 50);
        } finally {
            module.setEnabled(original);
        }

        // The music window is pulled out from its tab on the right edge; its controls drive the shared player.
        com.mentalfrostbyte.jello.music.MusicPlayer music = Client.getInstance().getMusicPlayer();
        int originalTrack = music.index();
        boolean wasPlaying = music.isPlaying();
        try {
            int[] tab = screen.musicTabBounds();
            int grabX = tab[0] + tab[2] / 2, grabY = tab[1] + tab[3] / 2;
            screen.mouseClicked(new MouseButtonEvent(grabX, grabY, new MouseButtonInfo(0, 0)), false);
            screen.mouseReleased(new MouseButtonEvent(grabX, grabY, new MouseButtonInfo(0, 0)));
            if (screen.isMusicOpen()) throw new IllegalStateException("A click on the music tab opened the window (it must be dragged)");

            // Drag it all the way out, in steps, and let go.
            screen.mouseClicked(new MouseButtonEvent(grabX, grabY, new MouseButtonInfo(0, 0)), false);
            for (int step = 1; step <= 10; step++) {
                screen.mouseDragged(new MouseButtonEvent(grabX - step * 30, grabY, new MouseButtonInfo(0, 0)), -30, 0);
            }
            screen.mouseReleased(new MouseButtonEvent(grabX - 300, grabY, new MouseButtonInfo(0, 0)));
            if (!screen.isMusicOpen()) throw new IllegalStateException("Dragging the music tab out did not open the window");

            if (music.queue().isEmpty()) {
                // Online, the chart may not have arrived yet; -Dsigma.debug.musicOffline makes this deterministic.
                LOGGER.info("Sigma debug: music transport steps skipped (queue still empty)");
            } else {
                int[] play = screen.musicControl("play");
                screen.mouseClicked(new MouseButtonEvent(play[0] + play[2] / 2, play[1] + play[3] / 2, new MouseButtonInfo(0, 0)), false);
                screen.mouseReleased(new MouseButtonEvent(play[0] + play[2] / 2, play[1] + play[3] / 2, new MouseButtonInfo(0, 0)));
                if (music.isPlaying() == wasPlaying) throw new IllegalStateException("Music play button did not toggle playback");
                int[] next = screen.musicControl("next");
                screen.mouseClicked(new MouseButtonEvent(next[0] + next[2] / 2, next[1] + next[3] / 2, new MouseButtonInfo(0, 0)), false);
                screen.mouseReleased(new MouseButtonEvent(next[0] + next[2] / 2, next[1] + next[3] / 2, new MouseButtonInfo(0, 0)));
                if (music.index() != Math.floorMod(originalTrack + 1, music.queue().size())) throw new IllegalStateException("Music next did not advance");
            }

            if (Client.getInstance().getMusicLibrary().isOnline()) {
                // The search field: while focused it owns every key - Right Shift and '/' must not reach the ClickGUI,
                // Space must not toggle playback - and Escape only lets go of the field.
                // The account page opens from the title bar (a click, not a window drag); the rail's player entry returns.
                int[] account = screen.musicControl("account");
                screen.mouseClicked(new MouseButtonEvent(account[0] + account[2] / 2, account[1] + account[3] / 2, new MouseButtonInfo(0, 0)), false);
                screen.mouseReleased(new MouseButtonEvent(account[0] + account[2] / 2, account[1] + account[3] / 2, new MouseButtonInfo(0, 0)));
                if (!"ACCOUNT".equals(screen.musicPage())) throw new IllegalStateException("Music account button did not open the account page");
                int[] back = screen.musicControl("rail:player");
                screen.mouseClicked(new MouseButtonEvent(back[0] + back[2] / 2, back[1] + back[3] / 2, new MouseButtonInfo(0, 0)), false);
                screen.mouseReleased(new MouseButtonEvent(back[0] + back[2] / 2, back[1] + back[3] / 2, new MouseButtonInfo(0, 0)));
                if (!"PLAYER".equals(screen.musicPage())) throw new IllegalStateException("Music rail's player entry did not return from the account page");

                // A category on the rail opens the browse page on it.
                int[] hot = screen.musicControl("rail:hot");
                screen.mouseClicked(new MouseButtonEvent(hot[0] + hot[2] / 2, hot[1] + hot[3] / 2, new MouseButtonInfo(0, 0)), false);
                screen.mouseReleased(new MouseButtonEvent(hot[0] + hot[2] / 2, hot[1] + hot[3] / 2, new MouseButtonInfo(0, 0)));
                if (!"BROWSE".equals(screen.musicPage())) throw new IllegalStateException("Music rail's chart entry did not open the browse page");

                int[] search = screen.musicControl("search");
                screen.mouseClicked(new MouseButtonEvent(search[0] + search[2] / 2, search[1] + search[3] / 2, new MouseButtonInfo(0, 0)), false);
                screen.mouseReleased(new MouseButtonEvent(search[0] + search[2] / 2, search[1] + search[3] / 2, new MouseButtonInfo(0, 0)));
                if (!screen.isMusicTyping()) throw new IllegalStateException("Music search button did not focus the search field");
                boolean playingBefore = music.isPlaying();
                screen.keyPressed(new KeyEvent(InputConstants.KEY_SPACE, 0, 0));
                screen.charTyped(new net.minecraft.client.input.CharacterEvent(' '));
                // Right Shift first meets the global ClickGUI hotkey, ahead of the screen; it must pass it by.
                if (com.mentalfrostbyte.jello.gui.click.ClickGuiHandler.handleKey(
                    com.mentalfrostbyte.jello.gui.click.ClickGuiHandler.OPEN_KEY, com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress.Action.PRESS)) {
                    throw new IllegalStateException("The ClickGUI hotkey took Right Shift from the music search field");
                }
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RSHIFT, 0, 0));
                screen.keyPressed(new KeyEvent(InputConstants.KEY_SLASH, 0, 0));
                screen.charTyped(new net.minecraft.client.input.CharacterEvent('/'));
                if (net.minecraft.client.Minecraft.getInstance().gui.screen() != screen) throw new IllegalStateException("A key typed into music search closed the ClickGUI");
                if (!screen.isOnCategoryView()) throw new IllegalStateException("'/' typed into music search opened the ClickGUI's own search");
                if (music.isPlaying() != playingBefore) throw new IllegalStateException("Space typed into music search toggled playback");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
                if (screen.isMusicTyping()) throw new IllegalStateException("Escape did not release the music search field");
                if (net.minecraft.client.Minecraft.getInstance().gui.screen() != screen) throw new IllegalStateException("Escape in music search closed the ClickGUI");
                ModernClickGuiScreen.closeMusicNow();
                pullMusicOut(screen);
            }

            // And push it back in by its tab.
            int[] openTab = screen.musicTabBounds();
            int backX = openTab[0] + openTab[2] / 2, backY = openTab[1] + openTab[3] / 2;
            screen.mouseClicked(new MouseButtonEvent(backX, backY, new MouseButtonInfo(0, 0)), false);
            for (int step = 1; step <= 10; step++) {
                screen.mouseDragged(new MouseButtonEvent(backX + step * 30, backY, new MouseButtonInfo(0, 0)), 30, 0);
            }
            screen.mouseReleased(new MouseButtonEvent(backX + 300, backY, new MouseButtonInfo(0, 0)));
            if (screen.isMusicOpen()) throw new IllegalStateException("Dragging the music tab back did not close the window");
            if (net.minecraft.client.Minecraft.getInstance().gui.screen() != screen) throw new IllegalStateException("Music window closed the screen");
        } finally {
            ModernClickGuiScreen.closeMusicNow();
            music.select(originalTrack, false);
            if (wasPlaying) music.play();
        }
        LOGGER.info("Sigma debug: Modern toggle/detail/bind-cancel/back-nav/resize/scroll/music smoke passed");
    }

    /** Pulls the music window back out after a reset, the way the steps above leave it. */
    private static void pullMusicOut(final ModernClickGuiScreen screen) {
        int[] tab = screen.musicTabBounds();
        int grabX = tab[0] + tab[2] / 2, grabY = tab[1] + tab[3] / 2;
        screen.mouseClicked(new MouseButtonEvent(grabX, grabY, new MouseButtonInfo(0, 0)), false);
        for (int step = 1; step <= 10; step++) {
            screen.mouseDragged(new MouseButtonEvent(grabX - step * 30, grabY, new MouseButtonInfo(0, 0)), -30, 0);
        }
        screen.mouseReleased(new MouseButtonEvent(grabX - 300, grabY, new MouseButtonInfo(0, 0)));
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
