package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/** Shared navigation for the two Sigma main-menu presentations. */
public abstract class SigmaMainMenuScreen extends Screen {

    protected SigmaMainMenuScreen(final Component title) {
        super(title);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    @Override
    public final boolean shouldCloseOnEsc() {
        return false;
    }

    protected final void openSingleplayer() {
        this.minecraft.gui.setScreen(new SelectWorldScreen(this));
    }

    protected final void openMultiplayer() {
        if (!this.minecraft.allowsMultiplayer()) {
            return;
        }

        Screen screen = this.minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this);
        this.minecraft.gui.setScreen(screen);
    }

    protected final void openRealms() {
        if (this.minecraft.allowsMultiplayer()) {
            this.minecraft.gui.setScreen(new RealmsMainScreen(this));
        }
    }

    protected final void openOptions() {
        this.minecraft.gui.setScreen(new OptionsScreen(this, this.minecraft.options, false));
    }

    protected final void openLanguage() {
        this.minecraft.gui.setScreen(new LanguageSelectScreen(this, this.minecraft.options, this.minecraft.getLanguageManager()));
    }

    protected final void openModeSelect() {
        this.minecraft.gui.setScreen(new ModeSelectScreen(this));
    }

    protected final void quitGame() {
        this.minecraft.stop();
    }

    protected static boolean inside(final int x, final int y, final int left, final int top, final int width, final int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }
}
