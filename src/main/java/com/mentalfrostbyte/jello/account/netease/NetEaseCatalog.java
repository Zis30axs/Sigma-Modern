package com.mentalfrostbyte.jello.account.netease;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The read-only directory used by the NetEase PC Game Center. IDs are catalog item IDs. */
public final class NetEaseCatalog {
    public static final int PAGE_SIZE = 24;
    private static final String ORIGIN = "https://x19mclobt.nie.netease.com";
    private static final Gson JSON = new Gson();

    public enum Query {
        AVAILABLE("/item/query/available"),
        SEARCH("/item/query/search-by-keyword"),
        DETAILS("/item/query/search-by-ids");

        private final URI uri;

        Query(String path) {
            this.uri = URI.create(ORIGIN + path);
        }

        public URI uri() {
            return uri;
        }
    }

    /** An account with a platform-authorized game session can supply its own directory transport. */
    @FunctionalInterface
    public interface Transport {
        JsonObject post(Query query, JsonObject body) throws IOException;
    }

    /** A negative nextOffset marks the end of the result set. */
    public record Page(List<Entry> entries, int nextOffset) {
        public Page {
            entries = List.copyOf(entries);
        }

        public boolean hasNext() {
            return nextOffset >= 0;
        }
    }

    public static final class LoginRequired extends IOException {
        public LoginRequired() {
            super("The NetEase directory requires sign-in");
        }
    }

    private final Transport transport;

    public NetEaseCatalog(Transport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    /** Standard HTTPS with no credentials, cookies, redirects, or JVM-wide cookie handler. */
    public static NetEaseCatalog guest() {
        return Guest.INSTANCE;
    }

    private static final class Guest {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        static final NetEaseCatalog INSTANCE = new NetEaseCatalog((query, body) -> {
            HttpRequest request = HttpRequest.newBuilder(query.uri()).timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
            try {
                var response = HTTP.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 2L * 1024 * 1024));
                if (response.statusCode() == 401) throw new LoginRequired();
                if (response.statusCode() != 200) throw new IOException("Directory HTTP " + response.statusCode());
                return JSON.fromJson(response.body(), JsonObject.class);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Directory request interrupted");
            } catch (JsonParseException invalid) {
                throw malformed();
            }
        });
    }

    /** Offset is counted in raw search hits, including any other item types returned by search. */
    public Page page(String keyword, int offset) throws IOException {
        if (offset < 0) throw new IllegalArgumentException("Negative directory offset");
        String search = keyword.strip();
        JsonObject params = new JsonObject();
        params.addProperty("item_type", 1);           // GAME
        params.addProperty("master_type_id", "2");    // NET_GAME
        params.addProperty("secondary_type_id", "");
        params.addProperty("network_tag", false);
        params.addProperty("sort_type", 2);           // LAUNCH_TIME
        params.addProperty("order", 0);
        params.addProperty("offset", offset);
        params.addProperty("length", PAGE_SIZE);
        params.addProperty("is_has", false);
        params.addProperty("year", 0);
        params.addProperty("is_sync", 0);
        params.addProperty("price_type", 0);
        if (!search.isEmpty()) params.addProperty("keyword", search);

        JsonObject response = request(search.isEmpty() ? Query.AVAILABLE : Query.SEARCH, params);
        JsonArray rows = rows(response);
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, JsonElement> missing = new LinkedHashMap<>();
        for (JsonElement value : rows) {
            if (!value.isJsonObject()) throw malformed();
            JsonObject row = value.getAsJsonObject();
            if (row.has("item_type") && number(row.get("item_type")) != 1) continue;
            String id = id(row);
            String name = name(row);
            names.putIfAbsent(id, name);
            if (name != null) {
                names.put(id, name);
                missing.remove(id);
            } else if (names.get(id) == null) {
                missing.put(id, row.get("entity_id"));
            }
        }
        if (!missing.isEmpty()) {
            JsonArray ids = new JsonArray(missing.size());
            missing.values().forEach(ids::add);
            JsonObject details = new JsonObject();
            details.add("entity_ids", ids);
            details.addProperty("with_price", 0);
            details.addProperty("with_title_image", 0);
            details.addProperty("is_has", false);
            for (JsonElement value : rows(request(Query.DETAILS, details))) {
                if (!value.isJsonObject()) throw malformed();
                JsonObject row = value.getAsJsonObject();
                String id = id(row);
                String name = name(row);
                if (missing.containsKey(id) && name != null) names.put(id, name);
            }
        }
        List<Entry> entries = new ArrayList<>(names.size());
        // Delisted or unnamed entries may disappear between list and details. Keep their real IDs.
        names.forEach((id, name) -> entries.add(new Entry(id, name == null ? id : name)));
        long next = (long) offset + rows.size();
        boolean more = response.has("total") && !response.get("total").isJsonNull()
            ? next < number(response.get("total")) : rows.size() == PAGE_SIZE;
        return new Page(entries, !rows.isEmpty() && more && next <= Integer.MAX_VALUE ? (int) next : -1);
    }

    private JsonObject request(Query query, JsonObject params) throws IOException {
        JsonObject response = transport.post(query, params);
        if (response == null) throw malformed();
        long code = number(response.get("code"));
        if (code == 10) throw new LoginRequired();
        if (code != 0) throw new IOException("Directory returned code " + code);
        return response;
    }

    private static JsonArray rows(JsonObject response) throws IOException {
        JsonElement rows = response.get("entities");
        if ((rows == null || rows.isJsonNull()) && response.get("entity") instanceof JsonObject entity) {
            rows = entity.get("list");
        }
        if (rows == null || !rows.isJsonArray()) throw malformed();
        return rows.getAsJsonArray();
    }

    private static long number(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive()) throw malformed();
        try {
            return Long.parseLong(value.getAsString());
        } catch (NumberFormatException invalid) {
            throw malformed();
        }
    }

    private static String id(JsonObject row) throws IOException {
        JsonElement id = row.get("entity_id");
        if (id == null || !id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()
            || id.getAsString().isBlank()) throw malformed();
        return id.getAsString();
    }

    private static String name(JsonObject row) {
        JsonElement value = row.get("name");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String name = value.getAsString().strip();
        return name.isEmpty() ? null : name;
    }

    private static IOException malformed() {
        return new IOException("Invalid NetEase directory response");
    }
}
