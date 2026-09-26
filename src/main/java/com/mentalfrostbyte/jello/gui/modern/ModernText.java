package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * SigmaModern's own copy. {@link #t} is the older two-language form: the design labels (spaced capitals like
 * "IN THIS SESSION") stay English on purpose; sentences and states follow the game's language - Chinese when it is
 * Chinese. {@link #tr} is text keyed in the client's language files, which follow the language picked in the ClickGUI
 * instead; text moves over to it as it gets translated.
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

    /**
     * Sigma's own text for {@code key} in the client language picked on the ClickGUI's Language page (see
     * {@link com.mentalfrostbyte.jello.lang.Translations}): English until a language's file translates it.
     */
    static String tr(String key) {
        return Client.getInstance().getTranslations().get(key);
    }

    /** {@link #tr(String)} with its {@code %s} placeholders filled in. */
    static String tr(String key, Object... args) {
        return Client.getInstance().getTranslations().get(key, args);
    }

    /** A category's name in the client language. */
    static String category(ModuleCategory category) {
        return tr("category." + category.name().toLowerCase(Locale.ROOT));
    }
}
