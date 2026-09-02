package net.minecraft.client.multiplayer;

import com.google.common.collect.Lists;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.viaversion.viafabricplus.injection.access.core.IConnection; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viafabricplus.settings.impl.BedrockSettings; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viafabricplus.settings.impl.DebugSettings; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion; // MODIFIED for porting: ViaFabricPlus
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants; // MODIFIED for porting: ViaFabricPlus core/integration MixinServerStatusPinger_1
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class ServerStatusPinger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component CANT_CONNECT_MESSAGE = Component.translatable("multiplayer.status.cannot_connect").withColor(-65536);
    private static final ResolutionContext DESCRIPTION_SANITIZE_CONTEXT = ResolutionContext.builder()
        .withObjectInfoValidator(description -> !(description instanceof PlayerSprite))
        .setDepthLimit(16)
        .setDepthLimitBehavior(ResolutionContext.LimitBehavior.DISCARD_REMAINING)
        .build();
    private final List<Connection> connections = Collections.synchronizedList(Lists.newArrayList());

    public void pingServer(
        final ServerData data, final Runnable onPersistentDataChange, final Runnable onPongResponse, final EventLoopGroupHolder eventLoopGroupHolder
    ) throws UnknownHostException {
        // MODIFIED for porting: was VFP core/integration/bedrock MixinServerStatusPinger#replaceDefaultPort
        // (@WrapOperation around ServerAddress#parseString). An entry forced to Bedrock and given no explicit port
        // has to be pinged on 19132; unlike the join path this reads the forced version directly, with no
        // passedDirectConnectScreen branch.
        final ServerAddress rawAddress = ServerAddress.parseString(BedrockSettings.replaceDefaultPort(data.ip, data.viaFabricPlus$forcedVersion()));
        Optional<InetSocketAddress> resolvedAddress = ServerNameResolver.DEFAULT.resolveAddress(rawAddress).map(ResolvedServerAddress::asInetSocketAddress);
        if (resolvedAddress.isEmpty()) {
            this.onPingFailed(ConnectScreen.UNKNOWN_HOST_MESSAGE, data);
        } else {
            final InetSocketAddress address = resolvedAddress.get();
            // MODIFIED for porting: was VFP MixinServerStatusPinger#setForcedVersion (@WrapOperation around connectToServer)
            LocalSampleLogger localSampleLogger = null;
            final com.viaversion.viafabricplus.injection.access.core.IServerData serverDataAccess = (com.viaversion.viafabricplus.injection.access.core.IServerData) data;
            if (serverDataAccess.viaFabricPlus$forcedVersion() != null && !serverDataAccess.viaFabricPlus$passedDirectConnectScreen()) {
                localSampleLogger = new LocalSampleLogger(1);
                localSampleLogger.viaFabricPlus$setForcedVersion(serverDataAccess.viaFabricPlus$forcedVersion());
                serverDataAccess.viaFabricPlus$passDirectConnectScreen(false);
            }
            final Connection connection = Connection.connectToServer(address, eventLoopGroupHolder, localSampleLogger);
            this.connections.add(connection);
            data.motd = Component.translatable("multiplayer.status.pinging");
            data.playerList = Collections.emptyList();
            ClientStatusPacketListener listener = new ClientStatusPacketListener() {
                private boolean success;
                private boolean receivedPing;
                private long pingStart;

                @Override
                public void handleStatusResponse(final ClientboundStatusResponsePacket packet) {
                    // MODIFIED for porting: was VFP core/integration MixinServerStatusPinger_1#trackTranslatingState
                    // (@Inject handleStatusResponse HEAD). Remembers which version this entry is being translated to,
                    // so the server list can show it when hovering the ping bar. All versions.
                    if (connection instanceof final IConnection viaFabricPlus$connection) {
                        data.viaFabricPlus$setTranslatingVersion(viaFabricPlus$connection.viaFabricPlus$getTargetVersion());
                    }

                    if (this.receivedPing) {
                        connection.disconnect(Component.translatable("multiplayer.status.unrequested"));
                    } else {
                        this.receivedPing = true;
                        ServerStatus status = packet.status();
                        // MODIFIED for porting: was VFP features/networking/server_pinging
                        // MixinServerStatusPinger_1#removeSanitizeDescription (@Redirect on sanitizeDescription).
                        // <= 1.21.11 MOTDs get stripped or emptied by the modern sanitizer, so they are taken raw.
                        // The gate is keyed on this entry's translating version, not on the global target version.
                        final Component description = status.description();
                        data.motd = DebugSettings.INSTANCE.removeServerDescriptionSanitize.isEnabled(data.viaFabricPlus$translatingVersion())
                            ? description
                            : sanitizeDescription(description);
                        status.version().ifPresentOrElse(version -> {
                            data.version = Component.literal(version.name());
                            data.protocol = version.protocol();
                        }, () -> {
                            data.version = Component.translatable("multiplayer.status.old");
                            data.protocol = 0;
                        });
                        status.players().ifPresentOrElse(players -> {
                            data.status = ServerStatusPinger.formatPlayerCount(players.online(), players.max());
                            data.players = players;
                            if (!players.sample().isEmpty()) {
                                List<Component> playerNames = new ArrayList<>(players.sample().size());

                                for (NameAndId profile : players.sample()) {
                                    Component playerName;
                                    if (profile.equals(MinecraftServer.ANONYMOUS_PLAYER_PROFILE)) {
                                        playerName = Component.translatable("multiplayer.status.anonymous_player");
                                    } else {
                                        playerName = Component.literal(profile.name());
                                    }

                                    playerNames.add(playerName);
                                }

                                if (players.sample().size() < players.online()) {
                                    playerNames.add(Component.translatable("multiplayer.status.and_more", players.online() - players.sample().size()));
                                }

                                data.playerList = playerNames;
                            } else {
                                data.playerList = List.of();
                            }
                        }, () -> data.status = Component.translatable("multiplayer.status.unknown").withStyle(ChatFormatting.DARK_GRAY));
                        status.favicon().ifPresent(newIcon -> {
                            if (!Arrays.equals(newIcon.iconBytes(), data.getIconBytes())) {
                                data.setIconBytes(ServerData.validateIcon(newIcon.iconBytes()));
                                onPersistentDataChange.run();
                            }
                        });
                        this.pingStart = Util.getMillis();
                        connection.send(new ServerboundPingRequestPacket(this.pingStart));
                        // MODIFIED for porting: was VFP core/integration MixinServerStatusPinger_1#fixVersionComparison
                        // (@Inject on Connection#send in handleStatusResponse, shift AFTER). A translated server
                        // advertises its own protocol, which would render the entry as incompatible, so the client
                        // protocol is reported instead. All versions.
                        final ProtocolVersion viaFabricPlus$version = ((IConnection) connection).viaFabricPlus$getTargetVersion();
                        if (viaFabricPlus$version != null && viaFabricPlus$version.getVersion() == data.protocol) {
                            data.protocol = SharedConstants.getProtocolVersion();
                        }

                        this.success = true;
                    }
                }

                private static Component sanitizeDescription(final Component original) {
                    try {
                        return ComponentUtils.resolve(ServerStatusPinger.DESCRIPTION_SANITIZE_CONTEXT, original);
                    } catch (CommandSyntaxException e) {
                        ServerStatusPinger.LOGGER.warn("Failed to sanitize status {}", original, e);
                        return Component.empty();
                    }
                }

                @Override
                public void handlePongResponse(final ClientboundPongResponsePacket packet) {
                    long then = this.pingStart;
                    long now = Util.getMillis();
                    data.ping = now - then;
                    connection.disconnect(Component.translatable("multiplayer.status.finished"));
                    onPongResponse.run();
                }

                @Override
                public void onDisconnect(final DisconnectionDetails details) {
                    if (!this.success) {
                        ServerStatusPinger.this.onPingFailed(details.reason(), data);
                        ServerStatusPinger.this.pingLegacyServer(address, rawAddress, data, eventLoopGroupHolder);
                    }
                }

                @Override
                public boolean isAcceptingMessages() {
                    return connection.isConnected();
                }
            };

            try {
                connection.initiateServerboundStatusConnection(rawAddress.getHost(), rawAddress.getPort(), listener);
                connection.send(ServerboundStatusRequestPacket.INSTANCE);
            } catch (Throwable t) {
                LOGGER.error("Failed to ping server {}", rawAddress, t);
            }
        }
    }

    private void onPingFailed(final Component reason, final ServerData data) {
        LOGGER.error("Can't ping {}: {}", data.ip, reason.getString());
        data.motd = CANT_CONNECT_MESSAGE;
        data.status = CommonComponents.EMPTY;
    }

    private void pingLegacyServer(
        final InetSocketAddress resolvedAddress, final ServerAddress rawAddress, final ServerData data, final EventLoopGroupHolder eventLoopGroupHolder
    ) {
        // MODIFIED for porting: emptied - was VFP remove_legacy_pinger MixinServerStatusPinger @Overwrite
        // Legacy pinging is handled by ViaFabricPlus itself.
    }

    public static Component formatPlayerCount(final int curPlayers, final int maxPlayers) {
        Component current = Component.literal(Integer.toString(curPlayers)).withStyle(ChatFormatting.GRAY);
        Component max = Component.literal(Integer.toString(maxPlayers)).withStyle(ChatFormatting.GRAY);
        return Component.translatable("multiplayer.status.player_count", current, max).withStyle(ChatFormatting.DARK_GRAY);
    }

    public void tick() {
        synchronized (this.connections) {
            Iterator<Connection> iterator = this.connections.iterator();

            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                if (connection.isConnected()) {
                    connection.tick();
                } else {
                    iterator.remove();
                    connection.handleDisconnection();
                }
            }
        }
    }

    public void removeAll() {
        synchronized (this.connections) {
            Iterator<Connection> iterator = this.connections.iterator();

            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                if (connection.isConnected()) {
                    iterator.remove();
                    connection.disconnect(Component.translatable("multiplayer.status.cancelled"));
                }
            }
        }
    }
}
