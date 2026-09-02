/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.screen.impl.classic4j;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * MODIFIED for porting: replaces ViaFabricPlus' {@code compat/classic4j/MixinCCAuthenticationResponse}, whose
 * {@code @Mixin} target is {@code CCAuthenticationResponse} in the classic4j jar - a normal Maven dependency here,
 * with no source file to inline into.
 *
 * <p>That mixin redirects the {@code CCError.description} field read inside {@code getErrorDisplay()} to a
 * translation, because classic4j itself has no translations. The redirect cannot be reproduced, but its only
 * observable effect can be: classic4j wraps {@code getErrorDisplay()} in a {@code LoginException} and hands it to
 * {@code LoginProcessHandler#handleException}, whose two implementations - {@link ClassiCubeLoginScreen} and
 * {@link ClassiCubeMFAScreen} - show {@code Throwable#getMessage()} as the screen subtitle. So the five hardcoded
 * descriptions are mapped back to their translation keys on the way into that subtitle, which produces exactly the
 * string the redirect would have built, still evaluated per display so a language change is picked up.
 *
 * <p>The keys are the five {@code classic4j_library.viafabricplus.error.*} entries in the mod's lang files; four of
 * them are unreachable without this. The descriptions are the literals in {@code CCError}'s static initialiser
 * (classic4j-2.3.0.jar), and {@code getErrorDisplay()} joins one per reported error with {@code \n} and trims.
 */
public final class ClassiCubeErrorTranslations {

    private static final Map<String, String> TRANSLATION_KEYS = new HashMap<>();

    static {
        TRANSLATION_KEYS.put("Incorrect token. Is your ViaFabricPlus out of date?", "classic4j_library.viafabricplus.error.token");
        TRANSLATION_KEYS.put("Invalid username.", "classic4j_library.viafabricplus.error.username");
        TRANSLATION_KEYS.put("Invalid password.", "classic4j_library.viafabricplus.error.password");
        TRANSLATION_KEYS.put("User hasn't verified their E-mail address yet.", "classic4j_library.viafabricplus.error.verification");
        TRANSLATION_KEYS.put("Multi-factor authentication requested. Please check your E-mail.", "classic4j_library.viafabricplus.error.logincode");
    }

    private ClassiCubeErrorTranslations() {
    }

    /**
     * Translates every classic4j {@code CCError} description in a login error message. Lines that are not one of
     * those descriptions - a network error, say - are left exactly as they are.
     *
     * @param message the message of the throwable passed to {@code LoginProcessHandler#handleException}
     * @return the same message with every known description replaced by its translation, or null if it was null
     */
    public static @Nullable String translate(final @Nullable String message) {
        if (message == null) {
            return null;
        }

        final String[] lines = message.split("\n", -1);
        final StringBuilder translated = new StringBuilder(message.length());
        for (int i = 0; i < lines.length; i++) {
            if (i != 0) {
                translated.append('\n');
            }

            final String key = TRANSLATION_KEYS.get(lines[i]);
            translated.append(key != null ? Component.translatable(key).getString() : lines[i]);
        }
        return translated.toString();
    }

}
