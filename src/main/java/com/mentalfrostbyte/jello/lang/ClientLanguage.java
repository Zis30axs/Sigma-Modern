package com.mentalfrostbyte.jello.lang;

import org.jspecify.annotations.Nullable;

/**
 * The languages the client's own interface can be shown in - Sigma's text, not the game's, which keeps following
 * the game's language setting.
 *
 * <p>Each one is a file of strings, {@code assets/minecraft/sigma/lang/<code>.json}, and a flag,
 * {@code assets/minecraft/sigma/flags/<flag>.svg}. English is the source: every key exists in {@code en_us.json},
 * and a key another language's file leaves out, or doesn't translate yet, reads as its English text. Adding a
 * language is a constant here, a JSON file and a flag.</p>
 */
public enum ClientLanguage {
    EN_US("en_us", "English", "us"),
    ZH_CN("zh_cn", "简体中文", "cn"),
    JA_JP("ja_jp", "日本語", "jp"),
    KO_KR("ko_kr", "한국어", "kr"),
    RU_RU("ru_ru", "Русский", "ru"),
    ES_ES("es_es", "Español", "es");

    /** The one every other language falls back to. */
    public static final ClientLanguage SOURCE = EN_US;

    private final String code;

    private final String nativeName;

    private final String flag;

    ClientLanguage(final String code, final String nativeName, final String flag) {
        this.code = code;
        this.nativeName = nativeName;
        this.flag = flag;
    }

    /** The file's name, and the key it is stored under in the config. */
    public String code() {
        return this.code;
    }

    /** The language's name in itself ("日本語"), which is how a list of languages names them whatever it is shown in. */
    public String nativeName() {
        return this.nativeName;
    }

    /** The key of the language's name in the language being shown ("Japanese" in English). */
    public String nameKey() {
        return "language." + this.code;
    }

    public String langResource() {
        return "/assets/minecraft/sigma/lang/" + this.code + ".json";
    }

    public String flagResource() {
        return "/assets/minecraft/sigma/flags/" + this.flag + ".svg";
    }

    /** The language stored under {@code code}, or null for one this client doesn't have. */
    public static @Nullable ClientLanguage byCode(final String code) {
        for (ClientLanguage language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }

        return null;
    }
}
