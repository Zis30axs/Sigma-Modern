package com.mentalfrostbyte.jello.account.netease;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Destination;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetEaseLoginTest {
    private static final Entry A = new Entry("a", "Server A");
    private static final Entry B = new Entry("b", "Server B");
    private static final Entry ONE = new Entry("1", "One");
    private static final Entry TWO = new Entry("2", "Two");
    private static final Entry THREE = new Entry("3", "Three");
    private static final UUID PLAYER = new UUID(0, 42);
    private static final String JSON = "{\"sdkuid\":\"fixture\",\"sessionid\":\"test-value\"}";

    @Test
    void acceptsHeaderOrValueAndPreservesCookieContents() {
        String value = "uid=fake; session=abc==; empty=;";
        for (String input : List.of(value, "cOoKiE: " + value)) {
            var parsed = NetEaseCredential.parse(input);
            assertEquals(NetEaseCredential.Kind.COOKIE, parsed.kind());
            assertEquals(value, parsed.value());
            assertFalse(parsed.toString().contains("abc"));
            assertFalse(new Gson().toJson(parsed).contains("abc"));
        }
    }

    @Test
    void normalizesBareObjectAndBothEnvelopeForms() {
        for (String input : List.of(JSON, "{\"sauth_json\":" + JSON + "}",
            "{\"sauth_json\":" + new JsonPrimitive(JSON) + "}")) {
            var parsed = NetEaseCredential.parse(input);
            assertEquals(NetEaseCredential.Kind.SAUTH, parsed.kind());
            assertEquals(JSON, parsed.value());
            assertFalse(parsed.toString().contains("test-value"));
            assertFalse(new Gson().toJson(parsed).contains("test-value"));
        }
    }

    @Test
    void rejectsMalformedCredentialsWithoutReturningTheirContentsInErrors() {
        for (String input : List.of("", "Cookie:", ";", "uid=foo;;x=y", "uid=foo\r\nOther: value",
            "{bad:test-value}", "{}", "{\"sauth_json\":12}", "{\"sauth_json\":\"[]\"}", JSON + " extra",
            "a=".concat("x".repeat(65536)))) {
            var error = assertThrows(IllegalArgumentException.class, () -> NetEaseCredential.parse(input));
            assertEquals("Invalid Cookie or SAuth format", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void bothInputsReachTheProviderAndAllThreeRolesAreScopedToTheServer() throws IOException {
        for (String input : List.of("uid=fixture", JSON)) {
            var provider = new Provider();
            try (var login = new NetEaseLogin()) {
                assertEquals(List.of(A, B), login.signIn(provider, NetEaseCredential.parse(input)));
                assertEquals(input, provider.received);
                var roles = login.listRoles(A);
                assertEquals(List.of(ONE, TWO, THREE), roles.entries());
                assertEquals("example.test:25565", login.authorize(roles, THREE, PLAYER, "Player").address());
                assertEquals(List.of("a", "3", PLAYER.toString(), "Player"), provider.lastJoin);
                var other = login.listRoles(B);
                assertEquals(List.of(ONE), other.entries());
                assertThrows(IllegalStateException.class, () -> login.authorize(roles, ONE, PLAYER, "Player"));
                assertThrows(IllegalStateException.class, () -> login.authorize(other, THREE, PLAYER, "Player"));
                login.authorize(other, ONE, PLAYER, "Player");
                assertEquals("b", provider.lastJoin.getFirst());
            }
            assertEquals(1, provider.closed);
        }
    }

    @Test
    void replacingOrClosingAnAccountInvalidatesItsRoleList() throws IOException {
        var first = new Provider();
        var second = new Provider();
        var login = new NetEaseLogin();
        login.signIn(first, NetEaseCredential.parse(JSON));
        var stale = login.listRoles(A);
        login.signIn(second, NetEaseCredential.parse(JSON));
        assertEquals(1, first.closed);
        var current = login.listRoles(A);
        assertThrows(IllegalStateException.class, () -> login.authorize(stale, ONE, PLAYER, "Player"));
        login.close();
        login.close();
        assertThrows(IllegalStateException.class, () -> login.authorize(current, ONE, PLAYER, "Player"));
        assertEquals(1, second.closed);
    }

    @Test
    void clearsThePreviousRolesOnFetchFailureOrEmptyList() throws IOException {
        var provider = new Provider();
        try (var login = new NetEaseLogin()) {
            login.signIn(provider, NetEaseCredential.parse(JSON));
            var stale = login.listRoles(A);
            provider.roles = Map.of("a", List.of(ONE, ONE), "b", List.of());
            assertThrows(IOException.class, () -> login.listRoles(A));
            assertThrows(IllegalStateException.class, () -> login.authorize(stale, ONE, PLAYER, "Player"));
            var empty = login.listRoles(B);
            assertTrue(empty.entries().isEmpty());
            assertThrows(IllegalStateException.class, () -> login.authorize(empty, ONE, PLAYER, "Player"));
        }
    }

    @Test
    void failedSignInClosesAcquiredSessionsAndCopiesTheReturnedCatalog() throws IOException {
        var provider = new Provider();
        var source = new ArrayList<>(List.of(A, B));
        provider.servers = source;
        try (var login = new NetEaseLogin()) {
            var servers = login.signIn(provider, NetEaseCredential.parse(JSON));
            source.clear();
            assertEquals(List.of(A, B), servers);
            source.addAll(List.of(A, A));
            assertThrows(IOException.class, () -> login.signIn(provider, NetEaseCredential.parse(JSON)));
            assertEquals(2, provider.closed);
            assertThrows(IllegalStateException.class, () -> login.listRoles(A));
        }
    }

    @Test
    void destinationContainsOnlyHostAndPort() {
        assertEquals("[::1]:25565", new Destination("::1", 25565).address());
        assertEquals("127.0.0.1:20000", new Destination("127.0.0.1", 20000).address());
        for (String host : List.of("", ".", "name@example.test", "example.test/a", "example.test?ticket=x", "host\r\nother")) {
            assertThrows(IllegalArgumentException.class, () -> new Destination(host, 25565));
        }
        assertThrows(IllegalArgumentException.class, () -> new Destination("example.test", 0));
        assertThrows(IllegalArgumentException.class, () -> new Destination("example.test", 65536));
    }

    @Test
    void legacyAccountUsesOneSnapshotAndRejectsRolesFromPreviousPages() throws IOException {
        Provider provider = new Provider();
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < NetEaseCatalog.PAGE_SIZE; i++) entries.add(new Entry("id-" + i, "Server " + i));
        entries.add(B);
        provider.servers = entries;
        Entry first = entries.getFirst();
        provider.roles = Map.of(first.id(), List.of(ONE), B.id(), List.of(THREE));
        try (var login = new NetEaseLogin()) {
            login.openAccount(provider, NetEaseCredential.parse(JSON));
            var page = login.serverPage("", 0);
            assertEquals(NetEaseCatalog.PAGE_SIZE, page.entries().size());
            var stale = login.listRoles(first);
            assertEquals(List.of(B), login.serverPage("", page.nextOffset()).entries());
            assertThrows(IllegalStateException.class, () -> login.listRoles(first));
            assertThrows(IllegalStateException.class, () -> login.authorize(stale, ONE, PLAYER, "Player"));
            assertEquals(List.of(THREE), login.listRoles(B).entries());
            assertEquals(List.of(first), login.serverPage("  SeRVeR 0  ", 0).entries());
            assertEquals(1, provider.catalogReads);
        }
    }

    @Test
    void gameCenterAccountRetriesDirectoryWithoutReimportAndExpiresCleanly() throws IOException {
        Provider provider = new Provider();
        var calls = new ArrayList<NetEaseCatalog.Query>();
        provider.catalog = new NetEaseCatalog((query, body) -> {
            calls.add(query);
            if (calls.size() == 1) throw new IOException("temporary catalog error");
            String json = calls.size() == 2
                ? "{\"code\":0,\"entities\":[{\"entity_id\":\"a\",\"name\":\"A\"}]}"
                : "{\"code\":10,\"message\":\"请先登录\"}";
            return JsonParser.parseString(json).getAsJsonObject();
        });
        try (var login = new NetEaseLogin()) {
            login.openAccount(provider, NetEaseCredential.parse(JSON));
            assertThrows(IOException.class, () -> login.serverPage("", 0));
            var selected = login.serverPage("游戏", 0).entries().getFirst();
            var roles = login.listRoles(selected);
            assertThrows(NetEaseCatalog.LoginRequired.class, () -> login.serverPage("", 0));
            assertThrows(IllegalStateException.class, () -> login.authorize(roles, ONE, PLAYER, "Player"));
            assertThrows(NetEaseCatalog.LoginRequired.class, () -> login.serverPage("", 0));
            assertEquals(0, provider.catalogReads);
            assertEquals(List.of(NetEaseCatalog.Query.AVAILABLE, NetEaseCatalog.Query.SEARCH,
                NetEaseCatalog.Query.AVAILABLE), calls);
        }
        assertEquals(1, provider.closed);
    }

    /** Test-only account authority. No credentials or endpoints from a real service are used. */
    private static final class Provider implements NetEaseProvider {
        NetEaseCatalog catalog;
        int catalogReads;
        List<Entry> servers = List.of(A, B);
        Map<String, List<Entry>> roles = Map.of("a", List.of(ONE, TWO, THREE), "b", List.of(ONE));
        List<String> lastJoin;
        String received;
        int closed;

        public String displayName() { return "Fixture"; }

        public Account signIn(NetEaseCredential credential) {
            received = credential.value();
            return new Account() {
                public List<Entry> servers() { catalogReads++; return servers; }
                public NetEaseCatalog catalog() { return catalog; }
                public List<Entry> roles(String serverId) { return roles.get(serverId); }
                public Destination authorize(String serverId, String roleId, UUID playerId, String playerName) {
                    lastJoin = List.of(serverId, roleId, playerId.toString(), playerName);
                    return new Destination("example.test", 25565);
                }
                public void close() { closed++; }
            };
        }
    }
}
