package com.mentalfrostbyte.jello.music.netease;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing in to NetEase Cloud Music by QR code, and who is signed in.
 *
 * <p>The flow is the desktop client's (eapi, {@code type=3}, which NetEase doesn't answer with its 8821 security
 * refusal): fetch a key, show {@link #QR_PREFIX}{@code + key} as a QR code, and poll until the NetEase app has
 * scanned it (802) and the user confirmed (803) - the reply then carries the login cookies, which the
 * {@link NeteaseSession} saves. A key expires by itself (800) after a few minutes.</p>
 *
 * <p>Interfaces read {@link #state()}, an immutable snapshot, and never wait on the network. Each login attempt
 * has an id; starting another (a refreshed code) or signing out stops the one before. Nothing from the replies
 * is logged: the confirming one carries the cookie.</p>
 */
public final class NeteaseAccount {
    public static final String QR_PREFIX = "https://music.163.com/login?codekey=";
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Netease");
    private static final long POLL_MS = 2_000L, GIVE_UP_MS = 5 * 60_000L;
    private static final int MAX_POLL_ERRORS = 5;

    public enum Phase {
        /** Not signed in, no code on show. */
        IDLE,
        /** Asking NetEase for a code. */
        FETCHING,
        /** A code is on show, waiting for the NetEase app to scan it. */
        WAITING,
        /** Scanned; waiting for the user to confirm on the phone. */
        SCANNED,
        /** The code ran out; a new one is needed. */
        EXPIRED,
        /** NetEase refused the login (its 8821 security check). */
        DENIED,
        /** No code could be fetched, or the confirming reply carried no login. */
        FAILED,
        SIGNED_IN
    }

    /**
     * @param qrText   what the QR code encodes (while there is a code)
     * @param scanner  who scanned it (once scanned), with their avatar
     * @param lapsed   the saved login turned out to have expired: the interface says so above a fresh code
     */
    public record State(Phase phase, @Nullable String qrText, @Nullable String scanner, @Nullable String scannerAvatar, boolean lapsed) {
        static State of(Phase phase, boolean lapsed) {
            return new State(phase, null, null, null, lapsed);
        }
    }

    /** @param vip whether the account has any NetEase membership (full VIP songs) */
    public record Profile(long userId, String nickname, @Nullable String avatarUrl, boolean vip) {}

    /** One reply of the login poll. */
    record Poll(int code, @Nullable String nickname, @Nullable String avatarUrl, @Nullable String cookie) {
        static Poll parse(JsonObject reply) throws IOException {
            JsonElement code = reply.get("code");
            if (code == null || !code.isJsonPrimitive()) throw new IOException("login poll reply without a code");
            return new Poll(code.getAsInt(), string(reply, "nickname"), string(reply, "avatarUrl"), string(reply, "cookie"));
        }
    }

    private final NeteaseSession session;
    private final ScheduledExecutorService threads = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Sigma NetEase login");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger attempt = new AtomicInteger();
    // Bumped whenever who is signed in changes, so per-account lists (daily picks, playlists) are fetched afresh.
    private final AtomicInteger generation = new AtomicInteger();
    private volatile State state;
    private volatile @Nullable CompletableFuture<Profile> profile;
    private volatile Runnable onSignedIn = () -> {};
    // Signed in by QR code in this session (not loaded from disk): such a login is never taken for a lapsed one.
    private volatile boolean freshLogin;

    public NeteaseAccount(NeteaseSession session) {
        this.session = session;
        this.state = State.of(session.isLoggedIn() ? Phase.SIGNED_IN : Phase.IDLE, false);
    }

    public State state() {
        return this.state;
    }

    /** Changes whenever who is signed in changes (a login, a sign-out, a lapsed login). */
    public int generation() {
        return this.generation.get();
    }

    /** Runs (on the login thread) after a QR login succeeds - e.g. to reload a track that was only a preview. */
    public void onSignedIn(Runnable action) {
        this.onSignedIn = action;
    }

    // --- QR login -------------------------------------------------------------------------------------

    /** Shows a new code: fetches a key, then polls it every two seconds. Stops any attempt before it. */
    public void startLogin() {
        int id = this.attempt.incrementAndGet();
        boolean lapsed = this.state.lapsed();
        this.state = State.of(Phase.FETCHING, lapsed);
        this.threads.execute(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("type", 3);
                JsonObject reply = this.session.eapi("/api/login/qrcode/unikey", params);
                if (id != this.attempt.get()) return;
                String key = string(reply, "unikey");
                if (!"200".equals(string(reply, "code")) || key == null || key.isEmpty()) {
                    this.state = State.of(Phase.FAILED, lapsed);
                    return;
                }
                String qr = QR_PREFIX + key;
                this.state = new State(Phase.WAITING, qr, null, null, lapsed);
                long deadline = System.currentTimeMillis() + GIVE_UP_MS;
                this.threads.schedule(() -> poll(id, key, qr, deadline, 0), POLL_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (id == this.attempt.get()) this.state = State.of(Phase.FAILED, lapsed);
            }
        });
    }

    private void poll(int id, String key, String qr, long deadline, int errors) {
        if (id != this.attempt.get()) return;
        boolean lapsed = this.state.lapsed();
        if (System.currentTimeMillis() > deadline) {
            this.state = new State(Phase.EXPIRED, qr, null, null, lapsed);
            return;
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("key", key);
            params.addProperty("type", 3);
            Poll poll = Poll.parse(this.session.eapi("/api/login/qrcode/client/login", params));
            if (id != this.attempt.get()) return;
            switch (poll.code()) {
                case 801 -> this.state = new State(Phase.WAITING, qr, null, null, lapsed);
                case 802 -> this.state = new State(Phase.SCANNED, qr, poll.nickname(), poll.avatarUrl(), lapsed);
                case 800 -> {
                    this.state = new State(Phase.EXPIRED, qr, null, null, lapsed);
                    return;
                }
                case 803 -> {
                    if (this.session.signIn(poll.cookie())) {
                        this.profile = null;
                        this.freshLogin = true;
                        this.generation.incrementAndGet();
                        this.state = State.of(Phase.SIGNED_IN, false);
                        LOGGER.info("NetEase: signed in by QR code (cookies kept: {})", String.join(", ", this.session.cookieNames()));
                        this.onSignedIn.run();
                    } else {
                        LOGGER.info("NetEase: the QR login was confirmed but carried no login cookie (cookies: {})",
                            String.join(", ", this.session.cookieNames()));
                        this.state = State.of(Phase.FAILED, lapsed);
                    }
                    return;
                }
                case 8821 -> {
                    this.state = State.of(Phase.DENIED, lapsed);
                    return;
                }
                default -> {
                    this.state = State.of(Phase.FAILED, lapsed);
                    return;
                }
            }
            errors = 0;
        } catch (Exception e) {
            // A dropped poll isn't the end of the code; a run of them is.
            if (++errors >= MAX_POLL_ERRORS) {
                if (id == this.attempt.get()) this.state = State.of(Phase.FAILED, lapsed);
                return;
            }
        }
        int errorsSoFar = errors;
        this.threads.schedule(() -> poll(id, key, qr, deadline, errorsSoFar), POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops showing a code (the page was left); a signed-in account stays signed in. */
    public void cancelLogin() {
        this.attempt.incrementAndGet();
        State current = this.state;
        if (current.phase() != Phase.SIGNED_IN) this.state = State.of(Phase.IDLE, current.lapsed());
    }

    public void signOut() {
        this.attempt.incrementAndGet();
        this.session.signOut();
        this.profile = null;
        this.freshLogin = false;
        this.generation.incrementAndGet();
        this.state = State.of(Phase.IDLE, false);
        LOGGER.info("NetEase: signed out");
    }

    // --- profile --------------------------------------------------------------------------------------

    /**
     * Who is signed in, fetched once per login. If NetEase answers that nobody is, a login loaded from disk has
     * lapsed: it is dropped and the state says so. A login just made by QR code is never dropped that way (the
     * answer would be a misreading, not a lapse), and neither is anything on a network failure - ask again with
     * {@link #refreshProfile()}.
     */
    public CompletableFuture<Profile> profile() {
        CompletableFuture<Profile> current = this.profile;
        if (current != null) return current;
        CompletableFuture<Profile> fetch = CompletableFuture.supplyAsync(() -> {
            try {
                Profile found = parseProfile(this.session.weapi("/weapi/w/nuser/account/get", new JsonObject()));
                if (found != null) {
                    LOGGER.info("NetEase: account check - signed in ({})", found.vip() ? "VIP" : "no VIP");
                    return found;
                }
                if (this.freshLogin) {
                    LOGGER.info("NetEase: account check - answered 'nobody' right after a QR login; keeping the login");
                    throw new IllegalStateException("profile unavailable");
                }
                LOGGER.info("NetEase: account check - the saved login has expired; signing out");
                this.attempt.incrementAndGet();
                this.session.signOut();
                this.generation.incrementAndGet();
                this.state = State.of(Phase.IDLE, true);
                throw new IllegalStateException("signed out");
            } catch (IOException e) {
                LOGGER.info("NetEase: account check - unavailable ({})", e.getClass().getSimpleName());
                throw new IllegalStateException("profile unavailable", e);
            }
        }, this.threads);
        this.profile = fetch;
        return fetch;
    }

    public void refreshProfile() {
        this.profile = null;
    }

    /**
     * {@code /w/nuser/account/get}: a profile when signed in; {@code null} for NetEase's "nobody" answer
     * ({@code {"code":200,"account":null,"profile":null}}); an exception for anything else, which must not be taken
     * for a lapsed login.
     */
    static @Nullable Profile parseProfile(JsonObject reply) throws IOException {
        JsonElement code = reply.get("code");
        if (code == null || !code.isJsonPrimitive() || code.getAsInt() != 200) throw new IOException("unexpected account reply");
        JsonElement profile = reply.get("profile");
        if (profile == null || profile.isJsonNull()) {
            JsonElement account = reply.get("account");
            if (account == null || account.isJsonNull()) return null;
            throw new IOException("account without a profile");
        }
        JsonObject p = profile.getAsJsonObject();
        JsonElement account = reply.get("account");
        int vipType = Math.max(intOf(p, "vipType"), account != null && account.isJsonObject() ? intOf(account.getAsJsonObject(), "vipType") : 0);
        String nickname = string(p, "nickname");
        return new Profile(p.has("userId") ? p.get("userId").getAsLong() : 0L, nickname == null ? "" : nickname, string(p, "avatarUrl"), vipType > 0);
    }

    private static int intOf(JsonObject json, String name) {
        JsonElement e = json.get(name);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
    }

    private static @Nullable String string(JsonObject json, String name) {
        JsonElement e = json.get(name);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    public void close() {
        this.attempt.incrementAndGet();
        this.threads.shutdownNow();
    }
}
