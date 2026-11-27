package com.mentalfrostbyte.jello.account.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.account.netease.NetEaseCatalog.Query;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetEaseCatalogTest {
    private static JsonObject response(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void listHydratesOneBatchWithoutLosingIdPrecisionOrderOrNames() throws IOException {
        var calls = new ArrayList<Query>();
        var directory = new NetEaseCatalog((query, body) -> {
            calls.add(query);
            assertEquals("https", query.uri().getScheme());
            assertEquals("x19mclobt.nie.netease.com", query.uri().getHost());
            if (query == Query.AVAILABLE) {
                assertEquals("/item/query/available", query.uri().getPath());
                assertEquals(1, body.get("item_type").getAsInt());
                assertEquals("2", body.get("master_type_id").getAsString());
                assertEquals(0, body.get("offset").getAsInt());
                assertEquals(NetEaseCatalog.PAGE_SIZE, body.get("length").getAsInt());
                assertFalse(body.has("keyword"));
                return response("""
                    {"code":0,"total":40,"entities":[
                      {"entity_id":9007199254740993},{"entity_id":"b","name":"乙服"},
                      {"entity_id":"hidden","item_type":2},{"entity_id":"c"},{"entity_id":"c"}]}
                    """);
            }
            assertEquals(Query.DETAILS, query);
            assertEquals("/item/query/search-by-ids", query.uri().getPath());
            assertEquals("[9007199254740993,\"c\"]", body.getAsJsonArray("entity_ids").toString());
            return response("""
                {"code":0,"entities":[{"entity_id":"unrequested","name":"额外结果"},
                  {"entity_id":9007199254740993,"name":"甲服"}]}
                """);
        });
        var page = directory.page(" ", 0);
        assertEquals(List.of(Query.AVAILABLE, Query.DETAILS), calls);
        assertEquals(List.of(new Entry("9007199254740993", "甲服"), new Entry("b", "乙服"), new Entry("c", "c")), page.entries());
        assertTrue(page.hasNext());
        assertEquals(5, page.nextOffset());
    }

    @Test
    void searchUsesRawPageOffsetsEvenIfTheFirstPageHasNoGames() throws IOException {
        var calls = new ArrayList<Query>();
        var directory = new NetEaseCatalog((query, body) -> {
            calls.add(query);
            assertEquals(Query.SEARCH, query);
            assertEquals("/item/query/search-by-keyword", query.uri().getPath());
            assertEquals("城市", body.get("keyword").getAsString());
            if (body.get("offset").getAsInt() == 0) {
                JsonArray components = new JsonArray();
                for (int i = 0; i < NetEaseCatalog.PAGE_SIZE; i++) {
                    components.add(response("{\"item_type\":2}"));
                }
                JsonObject reply = response("{\"code\":0}");
                reply.add("entities", components);
                return reply;
            }
            assertEquals(NetEaseCatalog.PAGE_SIZE, body.get("offset").getAsInt());
            return response("""
                {"code":0,"entity":{"list":[{"entity_id":"x","item_type":1,"name":"城市"}]},"total":25}
                """);
        });
        var first = directory.page(" 城市 ", 0);
        assertTrue(first.entries().isEmpty());
        assertTrue(first.hasNext());
        var last = directory.page("城市", first.nextOffset());
        assertEquals(List.of(new Entry("x", "城市")), last.entries());
        assertFalse(last.hasNext());
        assertEquals(List.of(Query.SEARCH, Query.SEARCH), calls);
    }

    @Test
    void serverRejectionAtEitherStageIsNotAValidEmptyPage() {
        String rejection = "{\"code\":10,\"message\":\"请先登录\",\"details\":null,\"entity\":null}";
        var guest = new NetEaseCatalog((query, body) -> response(rejection));
        assertThrows(NetEaseCatalog.LoginRequired.class, () -> guest.page("", 0));
        var expired = new NetEaseCatalog((query, body) -> query == Query.AVAILABLE
            ? response("{\"code\":0,\"entities\":[{\"entity_id\":\"a\"}]}") : response(rejection));
        assertThrows(NetEaseCatalog.LoginRequired.class, () -> expired.page("", 0));
    }

    @Test
    void handlesEmptyPagesAndRejectsMalformedOrFailedReplies() throws IOException {
        var empty = new NetEaseCatalog((query, body) -> response("{\"code\":0,\"entities\":[],\"total\":90}"));
        assertFalse(empty.page("", 0).hasNext());
        assertThrows(IllegalArgumentException.class, () -> empty.page("", -1));
        for (String invalid : List.of("{}", "{\"code\":0}", "{\"code\":0,\"entities\":[{}]}",
                "{\"code\":0.5,\"entities\":[]}", "{\"code\":21,\"message\":\"private detail\"}")) {
            var directory = new NetEaseCatalog((query, body) -> response(invalid));
            IOException error = assertThrows(IOException.class, () -> directory.page("", 0));
            assertFalse(error.getMessage().contains("private detail"));
        }
    }
}
