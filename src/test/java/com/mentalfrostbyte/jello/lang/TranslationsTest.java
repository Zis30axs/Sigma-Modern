package com.mentalfrostbyte.jello.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranslationsTest {

    private final Translations translations = new Translations(language -> switch (language) {
        case EN_US -> Map.of("greeting", "Hello", "count", "%s enabled", "only.english", "English only");
        case JA_JP -> Map.of("greeting", "こんにちは", "count", "%s %s broken placeholders");
        default -> Map.of();
    });

    @Test
    void startsInEnglish() {
        assertEquals(ClientLanguage.EN_US, this.translations.selected());
        assertEquals("Hello", this.translations.get("greeting"));
    }

    @Test
    void aPickedLanguageFallsBackToEnglishThenToTheKey() {
        this.translations.select(ClientLanguage.JA_JP);
        assertEquals("こんにちは", this.translations.get("greeting"));
        assertEquals("English only", this.translations.get("only.english"), "not translated yet reads as English");
        assertEquals("nobody.wrote.this", this.translations.get("nobody.wrote.this"));

        this.translations.select(ClientLanguage.KO_KR);
        assertEquals("Hello", this.translations.get("greeting"), "a language with no file is all English");
    }

    @Test
    void placeholdersAreFilledAndABrokenTranslationFallsBackToEnglish() {
        assertEquals("3 enabled", this.translations.get("count", 3));
        this.translations.select(ClientLanguage.JA_JP);
        assertEquals("3 enabled", this.translations.get("count", 3), "two placeholders for one value can't be filled");
    }

    @Test
    void theChoiceIsStoredByCodeAndAnUnknownCodeIsIgnored() {
        this.translations.select(ClientLanguage.RU_RU);
        JsonObject config = new JsonObject();
        this.translations.write(config);
        assertEquals("ru_ru", config.get("language").getAsString());

        Translations fresh = new Translations(language -> Map.of());
        fresh.read(config);
        assertEquals(ClientLanguage.RU_RU, fresh.selected());

        fresh.read(JsonParser.parseString("{\"language\": \"xx_yy\"}").getAsJsonObject());
        assertEquals(ClientLanguage.RU_RU, fresh.selected(), "kept");
        fresh.read(new JsonObject());
        assertEquals(ClientLanguage.RU_RU, fresh.selected());
    }

    @Test
    void aFileKeepsItsStringsAndSkipsAnythingElse() {
        Map<String, String> strings = Translations.parse(
                new StringReader("{\"a\": \"text\", \"b\": 3, \"c\": {\"nested\": \"x\"}, \"d\": \"more\"}"), "test.json");
        assertEquals(Map.of("a", "text", "d", "more"), strings);
        assertEquals(Map.of(), Translations.parse(new StringReader("[\"not\", \"an\", \"object\"]"), "test.json"));
    }
}
