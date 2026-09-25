package com.mentalfrostbyte.jello.music.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Reading NetEase's QR-login and account replies (shapes as captured from the live API). */
class NeteaseAccountTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void readsEachStepOfTheLoginPoll() throws IOException {
        assertEquals(801, NeteaseAccount.Poll.parse(json("{\"code\":801,\"message\":\"等待扫码\"}")).code());
        NeteaseAccount.Poll scanned = NeteaseAccount.Poll.parse(json("{\"code\":802,\"nickname\":\"冰\",\"avatarUrl\":\"https://p1.music.126.net/a.jpg\"}"));
        assertEquals("冰", scanned.nickname());
        assertEquals("https://p1.music.126.net/a.jpg", scanned.avatarUrl());
        NeteaseAccount.Poll done = NeteaseAccount.Poll.parse(json("{\"code\":803,\"cookie\":\"MUSIC_U=x; Path=/\"}"));
        assertEquals("MUSIC_U=x; Path=/", done.cookie());
        assertThrows(IOException.class, () -> NeteaseAccount.Poll.parse(json("{\"message\":\"?\"}")));
    }

    @Test
    void nobodySignedInIsNetEasesExplicitEmptyAnswer() throws IOException {
        assertNull(NeteaseAccount.parseProfile(json("{\"code\":200,\"account\":null,\"profile\":null}")));
    }

    @Test
    void anythingElseUnexpectedIsAnErrorNotASignOut() {
        assertThrows(IOException.class, () -> NeteaseAccount.parseProfile(json("{\"code\":301}")));
        assertThrows(IOException.class, () -> NeteaseAccount.parseProfile(json("{\"code\":200,\"account\":{\"id\":1},\"profile\":null}")));
    }

    @Test
    void readsTheProfileAndMembership() throws IOException {
        NeteaseAccount.Profile vip = NeteaseAccount.parseProfile(json(
            "{\"code\":200,\"account\":{\"id\":7,\"vipType\":11},\"profile\":{\"userId\":7,\"nickname\":\"Jello\",\"avatarUrl\":\"https://a\"}}"));
        assertEquals(7L, vip.userId());
        assertEquals("Jello", vip.nickname());
        assertTrue(vip.vip());
        NeteaseAccount.Profile plain = NeteaseAccount.parseProfile(json(
            "{\"code\":200,\"account\":{\"id\":8,\"vipType\":0},\"profile\":{\"userId\":8,\"nickname\":\"n\",\"vipType\":0}}"));
        assertFalse(plain.vip());
    }
}
