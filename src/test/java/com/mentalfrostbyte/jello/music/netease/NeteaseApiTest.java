package com.mentalfrostbyte.jello.music.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class NeteaseApiTest {
    /** A /user/playlist reply, with the field names captured from the live API (trimmed to what's read). */
    @Test
    void readsAUsersPlaylists() {
        List<NeteaseApi.PlaylistInfo> lists = NeteaseApi.playlists(JsonParser.parseString("""
            {"code":200,"playlist":[
              {"id":24381616,"name":"我喜欢的音乐","coverImgUrl":"http://p1.music.126.net/x.jpg","trackCount":312,"specialType":5},
              {"id":7,"name":"Snow","coverImgUrl":null,"trackCount":0},
              {"name":"no id"}
            ]}""").getAsJsonObject());
        assertEquals(2, lists.size());
        assertEquals(24381616L, lists.get(0).id());
        assertEquals("我喜欢的音乐", lists.get(0).name());
        assertEquals(312, lists.get(0).trackCount());
        assertTrue(lists.get(0).cover().startsWith("https://"), "covers are fetched over https at a fitting size");
        assertNull(lists.get(1).cover());
    }

    @Test
    void aReplyWithoutPlaylistsIsEmpty() {
        assertTrue(NeteaseApi.playlists(JsonParser.parseString("{\"code\":200}").getAsJsonObject()).isEmpty());
    }
}
