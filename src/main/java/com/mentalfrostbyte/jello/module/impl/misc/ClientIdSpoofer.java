package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.TextSetting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.ClientIdModCheckPayload;
import net.minecraft.network.protocol.common.custom.ClientIdModListPayload;
import net.minecraft.network.protocol.common.custom.ClientIdPackListPayload;
import net.minecraft.network.protocol.common.custom.ClientIdVersionPayload;
import net.minecraft.server.packs.repository.Pack;
import org.jspecify.annotations.Nullable;

/**
 * Emulates Novinity's ClientID client-side mod, so servers that check it see a spoofed install.
 *
 * <p>ClientID sends four UTF-8 custom payloads on play join: {@code clientid:modcheck} (profile
 * UUID), {@code clientid:modlist} (comma-separated mod IDs), {@code clientid:packlist}
 * (comma-separated enabled resource-pack names) and {@code clientid:clientversion} (ClientID
 * version). This module sends the same four, with a configurable mod list and version, and the
 * real selected resource packs.</p>
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, PR #8536 "Add ClientID spoofer" by Razzyk,
 * {@code features/spoofer/SpooferClientId.kt} and {@code features/spoofer/clientid/payload/}),
 * GPL-3.0, Copyright (c) 2015 - 2026 CCBlueX. Differences from upstream, all forced by Sigma having
 * no Fabric loader:</p>
 * <ul>
 *     <li>Fabric's {@code PayloadTypeRegistry} is gone: the four payload types are registered in
 *     {@code ServerboundCustomPayloadPacket}'s codec instead (direct source edit, as in every other
 *     port here). Without that, unknown types would fall back to {@code DiscardedPayload} and lose
 *     their content on encode.</li>
 *     <li>The login-packet trigger is replaced by {@link EventLoadWorld}, and the per-connection
 *     once-only guard is kept by remembering which connection the payloads went to, since Sigma has
 *     no disconnect event.</li>
 *     <li>Upstream refuses to enable when the real ClientID mod is installed; there is no mod loader
 *     here, so that check is dropped.</li>
 *     <li>Upstream hardcodes the mod list and version; they are settings here, defaulting to
 *     upstream's values.</li>
 * </ul>
 */
public final class ClientIdSpoofer extends Module {

    private static final String DEFAULT_VERSION = "1.1.3";
    private static final String DEFAULT_MODS = "minecraft,java,fabricloader,fabric-api,clientid";

    private final TextSetting modList = this.register(new TextSetting("ModList",
            "Comma-separated mod IDs sent as the ClientID mod list.", DEFAULT_MODS));
    private final TextSetting version = this.register(new TextSetting("Version",
            "ClientID version sent to the server.", DEFAULT_VERSION));

    private boolean sentInitialPayloads;
    private @Nullable ClientPacketListener sentTo;

    public ClientIdSpoofer() {
        super(ModuleCategory.MISC, "ClientIdSpoofer",
                "Emulates the ClientID mod: reports a spoofed mod/resource-pack list to servers that check it.");
    }

    @Override
    protected void onEnable() {
        this.sendInitialPayloads();
    }

    @Override
    protected void onDisable() {
        this.sentInitialPayloads = false;
        this.sentTo = null;
    }

    /** Upstream's login-packet handler: send once per connection, on joining a world. */
    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.sendInitialPayloads();
    }

    private void sendInitialPayloads() {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        if (connection != this.sentTo) {
            this.sentTo = connection;
            this.sentInitialPayloads = false;
        }
        if (this.sentInitialPayloads) {
            return;
        }

        this.sentInitialPayloads = true;
        connection.send(new ServerboundCustomPayloadPacket(
                new ClientIdModCheckPayload(mc.getUser().getProfileId().toString())));
        connection.send(new ServerboundCustomPayloadPacket(
                new ClientIdModListPayload(normalized(this.modList.get()))));
        connection.send(new ServerboundCustomPayloadPacket(
                new ClientIdPackListPayload(this.packList())));
        connection.send(new ServerboundCustomPayloadPacket(
                new ClientIdVersionPayload(this.version.get())));
    }

    /** Upstream sends the real selected packs, falling back to "Default" when there are none. */
    private String packList() {
        List<String> packs = new ArrayList<>();
        for (Pack pack : mc.getResourcePackRepository().getSelectedPacks()) {
            packs.add(pack.getTitle().getString());
        }
        if (packs.isEmpty()) {
            packs.add("Default");
        }
        return normalized(String.join(",", packs));
    }

    /** Upstream's normalizedEntries: split on commas, trim, drop empties, de-duplicate. */
    private static String normalized(final String value) {
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty() && !entries.contains(trimmed)) {
                entries.add(trimmed);
            }
        }
        return String.join(",", entries);
    }
}
