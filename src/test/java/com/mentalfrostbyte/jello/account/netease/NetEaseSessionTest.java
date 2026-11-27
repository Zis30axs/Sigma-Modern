package com.mentalfrostbyte.jello.account.netease;

import static org.junit.jupiter.api.Assertions.*;
import com.mentalfrostbyte.jello.account.SigmaAccountManager;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Destination;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetEaseSessionTest {
    static final Entry SERVER = new Entry("001", "Example");
    static final Entry ROLE = new Entry("role-2", "Second role");
    static final UUID PLAYER = new UUID(0, 1);

    @Test
    void guestRequiresAnExplicitImportAndNeverBecomesSignedInOnARejection() {
        var guest = new NetEaseCatalog((query, body) -> { throw new NetEaseCatalog.LoginRequired(); });
        try (var session = new NetEaseSession(guest)) {
            assertFalse(session.enabled());
            session.enableDirectory();
            assertTrue(session.enabled());
            var failure = assertThrows(CompletionException.class, () -> session.page("", 0).join());
            assertInstanceOf(NetEaseCatalog.LoginRequired.class, failure.getCause());
            assertFalse(session.signedIn());
            session.forget().join();
            assertFalse(session.enabled());
        }
    }

    @Test
    void accountScreenAndMultiplayerShareOnlyAMemorySession(@TempDir Path dir) throws Exception {
        var file = dir.resolve("accounts.json");
        var manager = new SigmaAccountManager(file);
        var provider = new Provider();
        try (var session = manager.netEase()) {
            manager.addOffline("JavaPlayer");
            String selected = manager.selectedId();
            session.signIn(provider, NetEaseCredential.parse("uid=FAKE_TEST_SESSION"), PLAYER, "JavaPlayer").join();
            assertSame(session, manager.netEase());
            assertEquals(SERVER, manager.netEase().page("", 0).join().entries().getFirst());
            var roles = session.roles(SERVER).join();
            assertEquals(ROLE, roles.entries().getFirst());
            assertInstanceOf(NetEaseSession.PlayerChanged.class, assertThrows(CompletionException.class,
                () -> session.authorize(roles, ROLE, new UUID(0, 3), "Changed").join()).getCause());
            assertEquals(25570, session.authorize(roles, ROLE, PLAYER, "JavaPlayer").join().port());
            manager.save();
            assertFalse(Files.readString(file).contains("FAKE_TEST_SESSION"));
            assertEquals(selected, manager.selectedId());
            session.forget().join();
            assertFalse(session.signedIn());
            assertThrows(CompletionException.class, () -> session.authorize(roles, ROLE, new UUID(0, 1), "JavaPlayer").join());
            assertEquals(1, provider.closed);
        }
    }

    @Test
    void rejectedReplacementPreservesWorkingAccountAndServerSideExpiryClearsIt() {
        var provider = new Provider();
        try (var session = new NetEaseSession()) {
            var input = NetEaseCredential.parse("{\"session\":\"fixture\"}");
            session.signIn(provider, input, PLAYER, "JavaPlayer").join();
            long revision = session.revision();
            provider.reject = true;
            assertThrows(CompletionException.class, () -> session.signIn(provider, input, PLAYER, "JavaPlayer").join());
            assertTrue(session.signedIn());
            assertEquals(revision, session.revision());
            session.page("", 0).join();
            provider.expired = true;
            var failure = assertThrows(CompletionException.class, () -> session.roles(SERVER).join());
            assertInstanceOf(NetEaseCatalog.LoginRequired.class, failure.getCause());
            assertFalse(session.signedIn());
            assertEquals(1, provider.closed);
        }
    }

    private static final class Provider implements NetEaseProvider {
        boolean reject;
        boolean expired;
        int closed;
        public String displayName() { return "Fixture"; }
        public Account signIn(NetEaseCredential input) throws IOException {
            if (reject) throw new IOException("Rejected test login");
            return new Account() {
                public List<Entry> servers() { return List.of(SERVER); }
                public List<Entry> roles(String id) throws IOException {
                    if (expired) throw new NetEaseCatalog.LoginRequired();
                    assertEquals(SERVER.id(), id);
                    return List.of(ROLE);
                }
                public Destination authorize(String id, String role, UUID player, String name) {
                    assertEquals(SERVER.id(), id);
                    assertEquals(ROLE.id(), role);
                    return new Destination("example.test", 25570);
                }
                public void close() { closed++; }
            };
        }
    }
}
