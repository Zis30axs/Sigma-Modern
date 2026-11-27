package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.jello.account.netease.NetEaseCatalog;
import com.mentalfrostbyte.jello.account.netease.NetEaseLogin;
import com.mentalfrostbyte.jello.account.netease.NetEaseProvider.Entry;
import com.mentalfrostbyte.jello.account.netease.NetEaseSession;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.CatalogEntry;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/** Contributes in-memory directory, paging and role rows to the regular multiplayer list. */
public final class NetEaseServers {
    private final JoinMultiplayerScreen screen;
    private final NetEaseSession session;
    private final Minecraft mc;
    private final Deque<Integer> history = new ArrayDeque<>();
    private long revision = -1;
    private long generation;
    private boolean refreshOnReturn;
    private String query = "";
    private int offset;
    private boolean busy;
    private Component status = Component.empty();
    private NetEaseCatalog.Page page;
    private NetEaseLogin.Roles roles;

    public NetEaseServers(JoinMultiplayerScreen screen, NetEaseSession session, Minecraft mc) {
        this.screen = screen;
        this.session = session;
        this.mc = mc;
    }

    public void tick() {
        long current = session.revision();
        if (revision == current && !refreshOnReturn) return;
        if (revision != current) {
            revision = current;
            query = "";
            offset = 0;
            history.clear();
        }
        refreshOnReturn = false;
        generation++;
        busy = false;
        if (session.enabled()) load(offset);
        else screen.setNetEaseRows(List.of());
    }

    public void leave() {
        generation++;
        refreshOnReturn = true;
        page = null;
        roles = null;
        busy = false;
    }

    public void search(String text) {
        if (!session.enabled() || busy) return;
        query = text.strip();
        history.clear();
        load(0);
    }

    public String query() { return query; }

    private void load(int start) {
        offset = start;
        roles = null;
        page = null;
        request("loading_servers", "directory_error", session.page(query, offset), result -> page = result);
    }

    private void chooseServer(Entry server) {
        request("loading_roles", "roles_error", session.roles(server), result -> roles = result);
    }

    private void join(NetEaseLogin.Roles selection, Entry role) {
        UUID id = mc.getUser().getProfileId();
        String name = mc.getUser().getName();
        if (!mc.allowsMultiplayer()) {
            status = NetEaseText.label("multiplayer_disabled");
            renderRows();
            return;
        }
        request("authorizing", "join_error", session.authorize(selection, role, id, name), target -> {
            if (!mc.allowsMultiplayer() || !id.equals(mc.getUser().getProfileId()) || !name.equals(mc.getUser().getName())) {
                status = NetEaseText.label("player_changed");
                return;
            }
            var data = new ServerData(selection.server().name(), target.address(), ServerData.Type.OTHER);
            data.requiresFreshAdmission = true;
            data.viaFabricPlus$passDirectConnectScreen(true);
            screen.join(data);
        });
    }

    private <T> void request(String progress, String error, CompletableFuture<T> future, Consumer<T> success) {
        long ticket = ++generation;
        busy = true;
        status = NetEaseText.label(progress);
        renderRows();
        future.whenComplete((value, failure) -> mc.execute(() -> {
            if (ticket != generation || revision != session.revision() || mc.gui.screen() != screen) return;
            busy = false;
            status = Component.empty();
            if (failure == null) {
                success.accept(value);
            } else {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof NetEaseCatalog.LoginRequired) {
                    roles = null;
                    page = null;
                    status = NetEaseText.label("catalog_requires_login");
                } else status = NetEaseText.label(cause instanceof NetEaseSession.PlayerChanged ? "player_changed" : error);
            }
            if (generation == ticket && mc.gui.screen() == screen) renderRows();
        }));
    }

    private void renderRows() {
        List<CatalogEntry> rows = new ArrayList<>();
        Component title = NetEaseText.label("network");
        Component serverTitle = roles == null ? title : Component.literal(roles.server().name());
        if (!status.getString().isEmpty()) {
            rows.add(new CatalogEntry("status", serverTitle, status, null));
        }
        if (busy) {
            screen.setNetEaseRows(rows);
            return;
        }
        if (roles != null) {
            var selection = roles;
            rows.add(new CatalogEntry("back", NetEaseText.label("back_servers"), serverTitle, () -> {
                roles = null;
                status = Component.empty();
                renderRows();
            }));
            for (Entry role : selection.entries()) {
                rows.add(new CatalogEntry("role/" + selection.server().id() + "/" + role.id(), Component.literal(role.name()),
                    NetEaseText.label("role_in", selection.server().name()), () -> join(selection, role)));
            }
            if (selection.entries().isEmpty()) {
                rows.add(new CatalogEntry("none", serverTitle, NetEaseText.label("no_roles"), null));
            }
        } else if (page != null) {
            for (Entry server : page.entries()) {
                rows.add(new CatalogEntry("server/" + server.id(), Component.literal(server.name()), title,
                    session.signedIn() ? () -> chooseServer(server) : null));
            }
            if (page.entries().isEmpty()) rows.add(new CatalogEntry("none", title, NetEaseText.label("no_matches"), null));
            if (!history.isEmpty()) rows.add(new CatalogEntry("prev", NetEaseText.label("prev_page"), title, () -> load(history.pop())));
            if (page.hasNext()) {
                int next = page.nextOffset();
                rows.add(new CatalogEntry("next", NetEaseText.label("next_page"), title, () -> {
                    history.push(offset);
                    load(next);
                }));
            }
        }
        screen.setNetEaseRows(rows);
    }
}
