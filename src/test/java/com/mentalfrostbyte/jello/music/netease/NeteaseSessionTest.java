package com.mentalfrostbyte.jello.music.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The login cookie's life on disk, without the network: sign in, survive a restart, sign out. */
class NeteaseSessionTest {
    @TempDir
    Path directory;

    @Test
    void keepsOnlyTheSessionCookiesAndDropsAttributes() {
        Map<String, String> cookies = NeteaseSession.parseCookies("MUSIC_U=abc; Max-Age=1296000; Expires=Sat, 10 Oct 2026; Path=/;;"
            + "__csrf=tok; HTTPOnly;;NMTID=n1; ntes_kaola_ad=1; MUSIC_R_T=r; empty=");
        assertEquals(Map.of("MUSIC_U", "abc", "__csrf", "tok", "NMTID", "n1", "MUSIC_R_T", "r"), cookies);
    }

    @Test
    void aLoginIsSavedAndSurvivesARestartUntilSignedOut() throws Exception {
        NeteaseSession session = new NeteaseSession(this.directory);
        assertFalse(session.isLoggedIn());
        assertTrue(session.signIn("MUSIC_U=abc; Path=/;;__csrf=tok; Expires=never;;os=pc"));

        Path file = this.directory.resolve("netease_cookie.dat");
        JsonObject saved = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("cookies");
        assertEquals("abc", saved.get("MUSIC_U").getAsString());
        assertEquals("tok", saved.get("__csrf").getAsString());
        assertFalse(saved.has("Path") || saved.has("Expires") || saved.has("os"));
        assertFalse(Files.exists(this.directory.resolve("netease_cookie.dat.tmp")));

        assertTrue(new NeteaseSession(this.directory).isLoggedIn(), "a new session reads the saved login");

        session.signOut();
        assertFalse(session.isLoggedIn());
        assertFalse(Files.exists(file));
        assertFalse(new NeteaseSession(this.directory).isLoggedIn());
    }

    @Test
    void aNewLoginReplacesASavedOne() throws Exception {
        Files.writeString(this.directory.resolve("netease_cookie.dat"), "{\"cookies\":{\"MUSIC_U\":\"old\",\"__csrf\":\"c\"}}");
        NeteaseSession session = new NeteaseSession(this.directory);
        assertTrue(session.signIn("MUSIC_U=new; Path=/"));
        JsonObject saved = JsonParser.parseString(Files.readString(this.directory.resolve("netease_cookie.dat"), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("cookies");
        assertEquals("new", saved.get("MUSIC_U").getAsString());
        assertEquals("c", saved.get("__csrf").getAsString());
    }

    @Test
    void aReplyWithoutALoginSavesNothing() {
        NeteaseSession session = new NeteaseSession(this.directory);
        assertFalse(session.signIn(null));
        assertFalse(session.signIn("__csrf=tok; Path=/"));
        assertFalse(Files.exists(this.directory.resolve("netease_cookie.dat")));
    }

    @Test
    void readsSigmaClientsOlderSingleStringFormat() throws Exception {
        Files.writeString(this.directory.resolve("netease_cookie.dat"), "{\"cookie\":\"MUSIC_U=old;; __csrf=c; Path=/\"}");
        assertTrue(new NeteaseSession(this.directory).isLoggedIn());
    }
}
