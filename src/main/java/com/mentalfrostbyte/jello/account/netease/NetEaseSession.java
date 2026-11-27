package com.mentalfrostbyte.jello.account.netease;

import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Destination;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** One in-memory platform account for multiplayer. All provider operations run on the same worker. */
public final class NetEaseSession implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Sigma-NetEase").factory());
    private final NetEaseCatalog guest;
    private NetEaseLogin login = new NetEaseLogin();
    private UUID accountPlayer;
    private String accountName;
    private volatile boolean signedIn;
    private volatile boolean enabled;
    private final AtomicLong revision = new AtomicLong();

    public NetEaseSession() {
        this(null);
    }

    NetEaseSession(NetEaseCatalog guest) {
        this.guest = guest;
    }

    /** Discovery does not expose a pasted credential to any provider. The user chooses one first. */
    public static List<NetEaseProvider> providers() {
        return ServiceLoader.load(NetEaseProvider.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    public boolean signedIn() { return signedIn; }
    public boolean enabled() { return enabled; }
    public long revision() { return revision.get(); }

    /** An explicit import also enables the ordinary server list to check the directory without a session. */
    public void enableDirectory() {
        enabled = true;
        revision.incrementAndGet();
    }

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception | LinkageError failure) {
                throw new CompletionException(failure);
            }
        }, worker);
    }

    public CompletableFuture<Void> signIn(NetEaseProvider provider, NetEaseCredential credential, UUID player, String name) {
        return submit(() -> {
            NetEaseLogin next = new NetEaseLogin();
            next.openAccount(provider, credential);
            NetEaseLogin previous = login;
            login = next;
            accountPlayer = player;
            accountName = name;
            signedIn = true;
            enabled = true;
            revision.incrementAndGet();
            previous.close();
            return null;
        });
    }

    public CompletableFuture<NetEaseCatalog.Page> page(String search, int offset) {
        return submit(() -> {
            if (!signedIn) return (guest == null ? NetEaseCatalog.guest() : guest).page(search, offset);
            return withAccount(() -> login.serverPage(search, offset));
        });
    }

    public CompletableFuture<NetEaseLogin.Roles> roles(Entry server) {
        return submit(() -> withAccount(() -> login.listRoles(server)));
    }

    public CompletableFuture<Destination> authorize(NetEaseLogin.Roles roles, Entry role, UUID player, String name) {
        return submit(() -> withAccount(() -> {
            if (!player.equals(accountPlayer) || !name.equals(accountName)) throw new PlayerChanged();
            return login.authorize(roles, role, player, name);
        }));
    }

    public static final class PlayerChanged extends IOException {}

    private <T> T withAccount(Callable<T> request) throws Exception {
        if (!signedIn) throw new NetEaseCatalog.LoginRequired();
        try {
            return request.call();
        } catch (NetEaseCatalog.LoginRequired expired) {
            signedIn = false;
            accountPlayer = null;
            accountName = null;
            login.close();
            throw expired;
        }
    }

    public CompletableFuture<Void> forget() {
        return submit(() -> {
            enabled = false;
            signedIn = false;
            accountPlayer = null;
            accountName = null;
            revision.incrementAndGet();
            login.close();
            return null;
        });
    }

    @Override
    public void close() {
        if (!worker.isShutdown()) {
            forget();
            worker.shutdown();
        }
    }
}
