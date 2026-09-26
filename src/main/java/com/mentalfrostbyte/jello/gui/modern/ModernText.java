package com.mentalfrostbyte.jello.gui.modern;

import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * SigmaModern's own copy in two languages. The design labels (spaced capitals like "IN THIS SESSION") stay
 * English on purpose; sentences and states follow the game's language - Chinese when it is Chinese.
 */
final class ModernText {
    private ModernText() {}

    static boolean chinese() {
        String selected = Minecraft.getInstance().getLanguageManager().getSelected();
        return selected != null && selected.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    static String t(String en, String zh) {
        return chinese() ? zh : en;
    }
}
