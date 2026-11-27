package com.mentalfrostbyte.jello.account.netease;

import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Account;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Destination;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** The authenticated account and its current role list, owned by the account browser's worker. */
public final class NetEaseLogin implements AutoCloseable {
    private Account account;
    private List<Entry> snapshot;
    private List<Entry> servers = List.of();
    private Roles currentRoles;

    public record Roles(Entry server, List<Entry> entries) {
        public Roles {
            entries = List.copyOf(entries);
        }
    }

    public List<Entry> signIn(NetEaseProvider provider, NetEaseCredential credential) throws IOException {
        openAccount(provider, credential);
        try {
            this.servers = allServers();
            return this.servers;
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public void openAccount(NetEaseProvider provider, NetEaseCredential credential) throws IOException {
        close();
        this.account = Objects.requireNonNull(provider.signIn(credential));
    }

    public NetEaseCatalog.Page serverPage(String keyword, int offset) throws IOException {
        if (account == null) throw new NetEaseCatalog.LoginRequired();
        if (offset < 0) throw new IllegalArgumentException("Negative directory offset");
        currentRoles = null;
        servers = List.of();
        NetEaseCatalog catalog = account.catalog();
        if (catalog != null) {
            try {
                NetEaseCatalog.Page page = catalog.page(keyword, offset);
                servers = page.entries();
                return page;
            } catch (NetEaseCatalog.LoginRequired expired) {
                close();
                throw expired;
            }
        }
        // Existing providers return one account-scoped snapshot. Never re-fetch it per keystroke or page.
        String needle = keyword.strip().toLowerCase(Locale.ROOT);
        List<Entry> matches = allServers().stream().filter(e -> e.name().toLowerCase(Locale.ROOT).contains(needle)
            || e.id().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int from = Math.min(offset, matches.size());
        int to = from + Math.min(NetEaseCatalog.PAGE_SIZE, matches.size() - from);
        servers = List.copyOf(matches.subList(from, to));
        return new NetEaseCatalog.Page(servers, to < matches.size() ? to : -1);
    }

    private List<Entry> allServers() throws IOException {
        if (snapshot == null) snapshot = options(account.servers());
        return snapshot;
    }

    public Roles listRoles(Entry server) throws IOException {
        this.currentRoles = null;
        if (this.account == null || !this.servers.contains(server)) {
            throw new IllegalStateException("Select a server in the current account");
        }
        this.currentRoles = new Roles(server, options(this.account.roles(server.id())));
        return this.currentRoles;
    }

    public Destination authorize(Roles roles, Entry role, UUID playerId, String playerName) throws IOException {
        if (this.account == null || roles == null || this.currentRoles != roles || !roles.entries().contains(role)) {
            throw new IllegalStateException("Select a role in the current server");
        }
        return Objects.requireNonNull(this.account.authorize(roles.server().id(), role.id(), playerId, playerName));
    }

    private static List<Entry> options(List<Entry> entries) throws IOException {
        List<Entry> snapshot = List.copyOf(entries);
        var ids = new HashSet<String>();
        for (Entry entry : snapshot) {
            if (!ids.add(entry.id())) {
                throw new IOException("Duplicate entry ID in the provider response");
            }
        }
        return snapshot;
    }

    @Override
    public void close() {
        Account previous = this.account;
        this.account = null;
        this.snapshot = null;
        this.servers = List.of();
        this.currentRoles = null;
        if (previous != null) {
            previous.close();
        }
    }
}
