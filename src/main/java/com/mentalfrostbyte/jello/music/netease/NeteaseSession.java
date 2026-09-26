package com.mentalfrostbyte.jello.music.netease;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A NetEase Cloud Music session: the device fingerprint and cookie jar the client presents, and the weapi/eapi
 * POSTs built on {@link NeteaseCrypto}. Ported from SigmaClient, minus its debug logging (it printed cookies).
 *
 * <p>Both files live in the Sigma data directory: {@code netease_device.json} (generated once) and
 * {@code netease_cookie.dat} (same format as SigmaClient's, so a signed-in cookie file can simply be copied
 * over). Without a {@code MUSIC_U} login cookie requests are anonymous, which NetEase answers for free songs.
 * {@link #signIn} (after a QR login, {@link NeteaseAccount}) writes that file; {@link #signOut} deletes it. Only
 * the session cookies are ever kept, and none of them is ever logged. Thread-safe: requests come from the music
 * threads.</p>
 */
public final class NeteaseSession {
    static final String WEB = "https://music.163.com";
    static final String EAPI_HOST = "https://interface.music.163.com";
    private static final String PC_APP_VER = "3.1.28.205001";
    private static final String PC_OS_VER = "Microsoft-Windows-10-Professional-build-22631-64bit";
    private static final String UA_BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36 Edg/127.0.0.0";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/" + PC_APP_VER;
    private static final String RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Netease");
    static final int TIMEOUT_MS = 10_000;
    private static final String COOKIE_FILE = "netease_cookie.dat";
    /** The cookies worth keeping: the session identifiers. Anything else a reply sets is per-request noise. */
    private static final Set<String> KEPT = Set.of("NMTID", "__csrf", "MUSIC_U", "MUSIC_A_T", "MUSIC_R_T");
    /** The ones that make up a login; signing out drops them. */
    private static final Set<String> LOGIN = Set.of("__csrf", "MUSIC_U", "MUSIC_A_T", "MUSIC_R_T");

    private final Path directory;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String deviceId, clientSign;

    public NeteaseSession(Path directory) {
        this.directory = directory;
        loadDevice();
        loadCookies();
    }

    public synchronized boolean isLoggedIn() {
        String musicU = this.cookies.get("MUSIC_U");
        return musicU != null && !musicU.isEmpty();
    }

    /**
     * Takes the cookies a QR login handed back ({@code "MUSIC_U=...; Path=/; ...;;__csrf=..."}), on top of any the
     * reply's {@code Set-Cookie} headers already gave, and saves the login. True when that made a login.
     */
    public synchronized boolean signIn(@Nullable String cookieText) {
        if (cookieText != null) this.cookies.putAll(parseCookies(cookieText));
        if (!isLoggedIn()) return false;
        saveCookies();
        return true;
    }

    /** The names (never the values) of the cookies held - for the log after a login. */
    public synchronized java.util.List<String> cookieNames() {
        return java.util.List.copyOf(this.cookies.keySet());
    }

    /** Forgets the login and deletes the saved one; requests are anonymous again. */
    public synchronized void signOut() {
        LOGIN.forEach(this.cookies::remove);
        try {
            Files.deleteIfExists(this.directory.resolve(COOKIE_FILE));
        } catch (IOException e) {
            LOGGER.warn("Could not delete the saved NetEase login ({})", e.getClass().getSimpleName());
        }
    }

    // --- requests -------------------------------------------------------------------------------------

    /** POSTs {@code data} to a {@code /weapi/...} path of the web API and parses the JSON reply. */
    public JsonObject weapi(String path, JsonObject data) throws IOException {
        data.addProperty("csrf_token", cookie("__csrf"));
        String[] enc = NeteaseCrypto.weapi(data.toString());
        String body = "params=" + URLEncoder.encode(enc[0], StandardCharsets.UTF_8) + "&encSecKey=" + URLEncoder.encode(enc[1], StandardCharsets.UTF_8);
        return parse(post(WEB + path, body, weapiCookies(), UA_BROWSER));
    }

    /** POSTs {@code params} to an {@code /api/...} path through the desktop client's eapi and parses the reply. */
    public JsonObject eapi(String path, JsonObject params) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("clientSign", this.clientSign);
        header.addProperty("os", "pc");
        header.addProperty("appver", PC_APP_VER);
        header.addProperty("deviceId", this.deviceId);
        header.addProperty("requestId", 0);
        header.addProperty("osver", PC_OS_VER);
        JsonObject data = params.deepCopy();
        data.add("header", header);
        data.addProperty("e_r", false);
        String body = "params=" + NeteaseCrypto.eapi(path, new Gson().toJson(data));
        String reply = post(EAPI_HOST + path.replace("/api/", "/eapi/"), body, eapiCookies(path.contains("/login")), UA_DESKTOP).trim();
        // e_r=false asks for plain JSON; if an encrypted body comes back anyway, decrypt it.
        if (!reply.startsWith("{") && !reply.isEmpty()) reply = NeteaseCrypto.eapiDecrypt(reply);
        return parse(reply);
    }

    private static JsonObject parse(String text) throws IOException {
        try {
            JsonElement json = JsonParser.parseString(text);
            if (!json.isJsonObject()) throw new IOException("NetEase replied with non-object JSON");
            return json.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("NetEase replied with unreadable data", e);
        }
    }

    private String post(String url, String body, String cookie, String userAgent) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Referer", WEB + "/");
            conn.setRequestProperty("Origin", WEB);
            if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            try (InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                if (in == null) throw new IOException("HTTP " + status + " from " + URI.create(url).getPath());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                keepCookies(conn.getHeaderFields().get("Set-Cookie"));
                return bytes.toString(StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    // --- cookies ----------------------------------------------------------------------------------------

    private synchronized String cookie(String name) {
        String value = this.cookies.get(name);
        return value == null ? "" : value;
    }

    private synchronized void keepCookies(List<String> setCookies) {
        if (setCookies == null) return;
        // A Set-Cookie header is one cookie followed by its attributes.
        for (String header : setCookies) this.cookies.putAll(parseCookies(header.split(";", 2)[0]));
    }

    /**
     * The session cookies named in {@code text} - {@code "k=v; k=v"}, SigmaClient's {@code ";;"}-joined form, or
     * cookies with their attributes ({@code Path}, {@code Expires}, ...) - with everything else dropped.
     */
    static Map<String, String> parseCookies(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : text.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (KEPT.contains(name) && !value.isEmpty()) out.put(name, value);
        }
        return out;
    }

    private synchronized String weapiCookies() {
        Map<String, String> jar = new LinkedHashMap<>();
        jar.put("_ntes_nuid", random(16));
        jar.put("__remember_me", "true");
        copy(jar, "__csrf", "MUSIC_U", "NMTID");
        return join(jar);
    }

    /** @param login a login endpoint: no made-up NMTID (as the desktop client, and SigmaClient, do). */
    private synchronized String eapiCookies(boolean login) {
        Map<String, String> jar = new LinkedHashMap<>();
        jar.put("_ntes_nuid", random(16));
        if (!login) jar.put("NMTID", random(16));
        copy(jar, "__csrf", "MUSIC_U", "NMTID");
        jar.put("os", "pc");
        jar.put("appver", PC_APP_VER);
        jar.put("osver", PC_OS_VER);
        jar.put("WEVNSM", "1.0.0");
        jar.put("ntes_kaola_ad", "1");
        jar.put("channel", "netease");
        return join(jar);
    }

    private void copy(Map<String, String> jar, String... names) {
        for (String name : names) {
            String value = this.cookies.get(name);
            if (value != null && !value.isEmpty()) jar.put(name, value);
        }
    }

    private static String join(Map<String, String> jar) {
        StringBuilder out = new StringBuilder();
        jar.forEach((k, v) -> out.append(out.isEmpty() ? "" : "; ").append(k).append('=').append(v));
        return out.toString();
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(RANDOM_CHARS.charAt(RANDOM.nextInt(RANDOM_CHARS.length())));
        return out.toString();
    }

    /** Reads SigmaClient's {@code netease_cookie.dat}: {@code {"cookies": {...}}} or an older {@code {"cookie": "k=v; ..."}}. */
    private synchronized void loadCookies() {
        Path file = this.directory.resolve(COOKIE_FILE);
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("cookies") && json.get("cookies").isJsonObject()) {
                StringBuilder text = new StringBuilder();
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("cookies").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) text.append(e.getKey()).append('=').append(e.getValue().getAsString()).append(';');
                }
                this.cookies.putAll(parseCookies(text.toString()));
            } else if (json.has("cookie") && json.get("cookie").isJsonPrimitive()) {
                this.cookies.putAll(parseCookies(json.get("cookie").getAsString()));
            }
            LOGGER.info("NetEase session: {}", isLoggedIn() ? "signed-in cookie loaded" : "anonymous");
        } catch (Exception e) {
            LOGGER.warn("NetEase cookie file unreadable; continuing anonymously ({})", e.getClass().getSimpleName());
        }
    }

    /** Writes the login in the format {@link #loadCookies} (and SigmaClient) reads, replacing the file atomically. */
    private void saveCookies() {
        JsonObject jar = new JsonObject();
        this.cookies.forEach(jar::addProperty);
        JsonObject json = new JsonObject();
        json.add("cookies", jar);
        Path file = this.directory.resolve(COOKIE_FILE), temp = this.directory.resolve(COOKIE_FILE + ".tmp");
        try {
            Files.createDirectories(this.directory);
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not save the NetEase login; it lasts until the game closes ({})", e.getClass().getSimpleName());
        }
    }

    // --- device -----------------------------------------------------------------------------------------

    /** The desktop client's fingerprint, generated once and kept (same format as SigmaClient's). */
    private void loadDevice() {
        Path file = this.directory.resolve("netease_device.json");
        String macId = null, scrw = null, scrw1 = null;
        try {
            if (Files.isRegularFile(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                this.deviceId = json.get("deviceId").getAsString();
                macId = json.get("macId").getAsString();
                scrw = json.get("scrw").getAsString();
                scrw1 = json.get("scrw1").getAsString();
            }
        } catch (Exception ignored) {
            this.deviceId = null;
        }
        if (this.deviceId == null || this.deviceId.length() != 52 || macId == null || macId.length() != 17
            || scrw == null || scrw.length() != 15 || scrw1 == null || scrw1.length() != 62) {
            this.deviceId = "00" + hex(50);
            StringBuilder mac = new StringBuilder();
            for (int i = 0; i < 6; i++) mac.append(i == 0 ? "" : ":").append(hex(2));
            macId = mac.toString();
            scrw = "00" + hex(13);
            scrw1 = "00" + hex(60);
            try {
                Files.createDirectories(this.directory);
                JsonObject json = new JsonObject();
                json.addProperty("deviceId", this.deviceId);
                json.addProperty("macId", macId);
                json.addProperty("scrw", scrw);
                json.addProperty("scrw1", scrw1);
                Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Could not save the NetEase device fingerprint ({})", e.getMessage());
            }
        }
        this.clientSign = macId + "@@@SCRW" + scrw + "@@@@@@7" + scrw1;
    }

    private static String hex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes).substring(0, length);
    }
}
