package com.mentalfrostbyte.jello.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The files bundled with the client: every language has its strings and its flag, and English has every key. */
class LanguageFilesTest {

    private static Map<String, String> read(final ClientLanguage language) throws IOException {
        try (InputStream stream = LanguageFilesTest.class.getResourceAsStream(language.langResource())) {
            assertNotNull(stream, language.langResource() + " is missing");
            return Translations.parse(new InputStreamReader(stream, StandardCharsets.UTF_8), language.langResource());
        }
    }

    @Test
    void englishNamesEveryCategoryAndEveryLanguage() throws IOException {
        Map<String, String> english = read(ClientLanguage.SOURCE);
        for (ModuleCategory category : ModuleCategory.values()) {
            assertTrue(english.containsKey("category." + category.name().toLowerCase(Locale.ROOT)), category.name());
        }
        for (ClientLanguage language : ClientLanguage.values()) {
            assertTrue(english.containsKey(language.nameKey()), language.nameKey());
        }
    }

    @Test
    void noLanguageHasAKeyEnglishDoesNot() throws IOException {
        Set<String> english = read(ClientLanguage.SOURCE).keySet();
        for (ClientLanguage language : ClientLanguage.values()) {
            Set<String> extra = new HashSet<>(read(language).keySet());
            extra.removeAll(english);
            assertTrue(extra.isEmpty(), language.code() + " has keys English doesn't (renamed or mistyped?): " + extra);
        }
    }

    @Test
    void everyLanguageHasAFlagAndEveryCategoryAnIcon() throws IOException {
        for (ClientLanguage language : ClientLanguage.values()) {
            assertSvg(language.flagResource());
        }
        for (ModuleCategory category : ModuleCategory.values()) {
            assertSvg("/assets/minecraft/sigma/icons/category/" + category.name().toLowerCase(Locale.ROOT) + ".svg");
        }
    }

    @Test
    void codesAreUniqueAndFindTheirLanguage() {
        Set<String> codes = new HashSet<>();
        for (ClientLanguage language : ClientLanguage.values()) {
            assertTrue(codes.add(language.code()), language.code());
            assertTrue(ClientLanguage.byCode(language.code()) == language);
            assertFalse(language.nativeName().isBlank());
        }
    }

    private static void assertSvg(final String resource) throws IOException {
        try (InputStream stream = LanguageFilesTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " is missing");
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("<svg") && text.contains("viewBox="), resource + " should be an SVG with a viewBox");
        }
    }
}
