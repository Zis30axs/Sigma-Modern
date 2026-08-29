package com.mentalfrostbyte.jello.util.text;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Fills {@code {placeholder}} markers in a string.
 *
 * <p>Deliberately not a language: there are no conditionals, no nesting and no escapes to learn. A marker
 * whose name the resolver does not know is left exactly as it was written, so a typo shows up in the
 * output as {@code {tiem}} instead of vanishing or throwing.</p>
 */
public final class TextTemplate {

    private TextTemplate() {
    }

    /**
     * @param template the text to fill in
     * @param resolver given a placeholder name, its value, or null if it has none
     */
    public static String render(final String template, final Function<String, @Nullable String> resolver) {
        int open = template.indexOf('{');
        if (open < 0) {
            return template;
        }

        StringBuilder result = new StringBuilder(template.length());
        int copiedUpTo = 0;
        while (open >= 0) {
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }

            String value = resolver.apply(template.substring(open + 1, close));
            if (value != null) {
                result.append(template, copiedUpTo, open).append(value);
                copiedUpTo = close + 1;
            }

            open = template.indexOf('{', close + 1);
        }

        return result.append(template, copiedUpTo, template.length()).toString();
    }
}
