package com.mentalfrostbyte.jello.account.netease;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Pattern;

/** An explicitly pasted credential. Parsing checks its format. The account provider verifies it. */
public final class NetEaseCredential {
    public enum Kind { COOKIE, SAUTH }

    private static final int MAX_LENGTH = 65536;
    private static final Pattern COOKIE_NAME = Pattern.compile("[!#$%&'*+.^_`|~A-Za-z0-9-]+");
    private final Kind kind;
    private final transient String value;

    private NetEaseCredential(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    public static NetEaseCredential parse(String pasted) {
        if (pasted == null || pasted.length() > MAX_LENGTH || pasted.isBlank()) {
            throw invalid();
        }
        String text = pasted.strip();
        if (text.startsWith("{")) {
            JsonObject outer = object(text);
            JsonElement inner = outer.get("sauth_json");
            if (inner == null) {
                return new NetEaseCredential(Kind.SAUTH, text);
            }
            if (inner.isJsonPrimitive() && inner.getAsJsonPrimitive().isString()) {
                String raw = inner.getAsString();
                object(raw);
                return new NetEaseCredential(Kind.SAUTH, raw);
            }
            if (inner.isJsonObject() && !inner.getAsJsonObject().isEmpty()) {
                return new NetEaseCredential(Kind.SAUTH, inner.toString());
            }
            throw invalid();
        }

        if (text.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        if (text.regionMatches(true, 0, "Cookie:", 0, 7)) {
            text = text.substring(7).strip();
        }
        String pairs = text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
        for (String part : pairs.split(";", -1)) {
            String pair = part.strip();
            int equals = pair.indexOf('=');
            if (equals < 1 || !COOKIE_NAME.matcher(pair.substring(0, equals)).matches()) {
                throw invalid();
            }
        }
        return new NetEaseCredential(Kind.COOKIE, text);
    }

    private static JsonObject object(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = JsonParser.parseReader(reader);
            if (result.isJsonObject() && !result.getAsJsonObject().isEmpty() && reader.peek() == JsonToken.END_DOCUMENT) {
                return result.getAsJsonObject();
            }
        } catch (RuntimeException | IOException ignored) {
            // Parser diagnostics may quote input. Expose only the fixed error below.
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Cookie or SAuth format");
    }

    public Kind kind() {
        return this.kind;
    }

    /** Cookie request-header value or bare SAuth JSON for the provider the player selected. */
    public String value() {
        return this.value;
    }

    @Override
    public String toString() {
        return this.kind.name();
    }
}
