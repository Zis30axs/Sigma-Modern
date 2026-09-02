package net.minecraft.client.gui.screens;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import java.util.UUID;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viafabricplus.injection.access.core.IConnection;
import com.viaversion.viafabricplus.injection.access.core.IServerData;
import com.viaversion.viafabricplus.injection.access.networking.legacy_chat_signature.IProfilePublicKey_Data;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.protocoltranslator.impl.provider.vialegacy.ViaFabricPlusClassicMPPassProvider;
import com.viaversion.viafabricplus.protocoltranslator.util.ProtocolVersionDetector;
import com.viaversion.viafabricplus.save.SaveManager;
import com.viaversion.viafabricplus.settings.impl.AuthenticationSettings;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.ProfileKey;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_0;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_1;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import de.florianreuth.classic4j.model.classicube.account.CCAccount;
import java.net.ConnectException;
import java.security.KeyPair;
import java.security.PrivateKey;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.bedrock.model.MinecraftMultiplayerToken;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.raphimc.viabedrock.protocol.storage.AuthData;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.quickplay.QuickPlay;
import net.minecraft.client.quickplay.QuickPlayLog;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class ConnectScreen extends Screen {
    private static final AtomicInteger UNIQUE_THREAD_ID = new AtomicInteger(0);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long NARRATION_DELAY_MS = 2000L;
    public static final Component ABORT_CONNECTION = Component.translatable("connect.aborted");
    public static final Component UNKNOWN_HOST_MESSAGE = Component.translatable("disconnect.genericReason", Component.translatable("disconnect.unknownHost"));
    private volatile @Nullable Connection connection;
    private @Nullable ChannelFuture channelFuture;
    private volatile boolean aborted;
    private final Screen parent;
    private Component status = Component.translatable("connect.connecting");
    private long lastNarration = -1L;
    private final Component connectFailedTitle;

    private ConnectScreen(final Screen parent, final Component connectFailedTitle) {
        super(GameNarrator.NO_TITLE);
        this.parent = parent;
        this.connectFailedTitle = connectFailedTitle;
    }

    public static void startConnecting(
        final Screen parent,
        final Minecraft minecraft,
        final ServerAddress hostAndPort,
        final ServerData data,
        final boolean isQuickPlay,
        final @Nullable TransferState transferState
    ) {
        if (minecraft.gui.screen() instanceof ConnectScreen) {
            LOGGER.error("Attempt to connect while already connecting");
        } else {
            Component connectFailedTitle;
            if (transferState != null) {
                connectFailedTitle = CommonComponents.TRANSFER_CONNECT_FAILED;
            } else if (isQuickPlay) {
                connectFailedTitle = QuickPlay.ERROR_TITLE;
            } else {
                connectFailedTitle = CommonComponents.CONNECT_FAILED;
            }

            ConnectScreen screen = new ConnectScreen(parent, connectFailedTitle);
            if (transferState != null) {
                screen.updateStatus(Component.translatable("connect.transferring"));
            }

            minecraft.disconnectWithProgressScreen(false);
            minecraft.prepareForMultiplayer();
            minecraft.updateReportEnvironment(ReportEnvironment.thirdParty(data.ip));
            minecraft.quickPlayLog().setWorldData(QuickPlayLog.Type.MULTIPLAYER, data.ip, data.name);
            minecraft.gui.setScreen(screen);
            screen.connect(minecraft, hostAndPort, data, transferState);
        }
    }

    private void connect(final Minecraft minecraft, final ServerAddress hostAndPort, final ServerData server, final @Nullable TransferState transferState) {
        LOGGER.info("Connecting to {}, {}", hostAndPort.getHost(), hostAndPort.getPort());
        Thread thread = new Thread("Server Connector #" + UNIQUE_THREAD_ID.incrementAndGet()) {
            @Override
            public void run() {
                InetSocketAddress address = null;

                try {
                    if (ConnectScreen.this.aborted) {
                        return;
                    }

                    Optional<InetSocketAddress> resolvedAddress = ServerNameResolver.DEFAULT
                        .resolveAddress(hostAndPort)
                        .map(ResolvedServerAddress::asInetSocketAddress);
                    if (ConnectScreen.this.aborted) {
                        return;
                    }

                    if (resolvedAddress.isEmpty()) {
                        minecraft.execute(
                            () -> minecraft.gui
                                .setScreen(
                                    new DisconnectedScreen(ConnectScreen.this.parent, ConnectScreen.this.connectFailedTitle, ConnectScreen.UNKNOWN_HOST_MESSAGE)
                                )
                        );
                        return;
                    }

                    address = resolvedAddress.get();
                    // MODIFIED for porting: was VFP integration MixinConnectScreen_1#setServerInfoAndProtocolVersion
                    // (@WrapOperation on Optional#get). Picks the version this join translates to: a per-server
                    // forced version wins once (then the direct-connect latch is cleared), otherwise the global
                    // target; AUTO_DETECT reuses the version from a successful ping and otherwise probes the
                    // server. The global setter is used because every inlined gate reads getTargetVersion().
                    ProtocolVersion vfp$targetVersion = ProtocolTranslator.getTargetVersion();
                    if (((IServerData) server).viaFabricPlus$forcedVersion() != null && !((IServerData) server).viaFabricPlus$passedDirectConnectScreen()) {
                        vfp$targetVersion = ((IServerData) server).viaFabricPlus$forcedVersion();
                        ((IServerData) server).viaFabricPlus$passDirectConnectScreen(false); // reset state
                    }
                    if (vfp$targetVersion == ProtocolTranslator.AUTO_DETECT_PROTOCOL) {
                        // If the server got already pinged, try to use that version if it's valid. Otherwise, perform auto-detect
                        final boolean vfp$serverPinged = server.state() == ServerData.State.SUCCESSFUL || server.state() == ServerData.State.INCOMPATIBLE;
                        if (vfp$serverPinged) {
                            vfp$targetVersion = ProtocolVersion.getProtocol(server.protocol);
                        }
                        if (!vfp$serverPinged || !vfp$targetVersion.isKnown()) {
                            ConnectScreen.this.updateStatus(Component.translatable("base.viafabricplus.detecting_server_version"));
                            try {
                                vfp$targetVersion = ProtocolVersionDetector.get(hostAndPort, address, ProtocolTranslator.NATIVE_VERSION);
                            } catch (final ConnectException ignored) {
                                // Don't let this one through as not relevant
                            }
                        }
                    }
                    ProtocolTranslator.setTargetVersion(vfp$targetVersion, true);
                    // MODIFIED for porting: was VFP integration MixinConnectScreen_1 @Unique viaFabricPlus$useClassiCubeAccount
                    // - latched here, read by the useClassiCubeUsername hook below.
                    final boolean vfp$useClassiCubeAccount = AuthenticationSettings.INSTANCE.setSessionNameToClassiCubeNameInServerList.getValue()
                        && ViaFabricPlusClassicMPPassProvider.classicubeMPPass != null;
                    // MODIFIED for porting: was VFP srv_resolving MixinConnectScreen_1 getRealAddress/getRealPort (@Redirect <=1_17 uses raw host/port)
                    // Both un-ordinaled redirects also cover the error-message stripping in the catch block below.
                    final boolean vfp$useRawAddress = ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_17);
                    final String vfp$connectHost = vfp$useRawAddress ? hostAndPort.getHost() : address.getHostName();
                    final int vfp$connectPort = vfp$useRawAddress ? hostAndPort.getPort() : address.getPort();
                    Connection pendingConnection;
                    synchronized (ConnectScreen.this) {
                        if (ConnectScreen.this.aborted) {
                            return;
                        }

                        pendingConnection = new Connection(PacketFlow.CLIENTBOUND);
                        pendingConnection.setBandwidthLogger(minecraft.getDebugOverlay().getBandwidthLogger());
                        // MODIFIED for porting: was VFP bedrock MixinConnectScreen_1#markAsConnecting (@Redirect
                        // EventLoopGroupHolder#remote) - flags the holder as connecting so the bedrock transport
                        // hooks in Connection#connect take the connect path instead of the RakNet ping path.
                        final EventLoopGroupHolder vfp$eventLoopGroupHolder = EventLoopGroupHolder.remote(minecraft.options.useNativeTransport());
                        vfp$eventLoopGroupHolder.viaFabricPlus$setConnecting(true);
                        ConnectScreen.this.channelFuture = Connection.connect(
                            address, vfp$eventLoopGroupHolder, pendingConnection
                        );
                        // MODIFIED for porting: was VFP integration MixinConnectScreen_1#resetProtocolVersionAfterDisconnect
                        // (@WrapOperation on Connection#connect) - a version set with revertOnDisconnect=true is
                        // reverted when this channel closes.
                        ProtocolTranslator.injectPreviousVersionReset(ConnectScreen.this.channelFuture.channel());
                    }

                    ConnectScreen.this.channelFuture.syncUninterruptibly();
                    // MODIFIED for porting: was VFP legacy_chat_signature MixinConnectScreen_1#setupChatSessions (@Inject AFTER syncUninterruptibly)
                    final UserConnection vfp$viaUser = ((com.viaversion.viafabricplus.injection.access.core.IConnection) pendingConnection).viaFabricPlus$getUserConnection();
                    if (ProtocolTranslator.getTargetVersion().betweenInclusive(ProtocolVersion.v1_19, ProtocolVersion.v1_19_1)) {
                        final ProfileKeyPair keyPair = minecraft.getProfileKeyPairManager().prepareKeyPair().join().orElse(null);
                        if (keyPair != null) {
                            final ProfilePublicKey.Data publicKeyData = keyPair.publicKey().data();
                            final PrivateKey privateKey = keyPair.privateKey();
                            final long expiresAt = publicKeyData.expiresAt().toEpochMilli();
                            final byte[] publicKey = publicKeyData.key().getEncoded();
                            final UUID uuid = minecraft.getUser().getProfileId();
                            vfp$viaUser.put(new ChatSession1_19_1(uuid, privateKey, new ProfileKey(expiresAt, publicKey, publicKeyData.keySignature())));
                            if (ProtocolTranslator.getTargetVersion() == ProtocolVersion.v1_19) {
                                final byte[] legacyKeySignature = publicKeyData.viafabricplus$getLegacyPublicKeySignature();
                                if (legacyKeySignature != null) {
                                    vfp$viaUser.put(new ChatSession1_19_0(uuid, privateKey, new ProfileKey(expiresAt, publicKey, legacyKeySignature)));
                                }
                            }
                        } else {
                            com.viaversion.viafabricplus.ViaFabricPlusImpl.INSTANCE.getLogger().error("Could not get public key signature. Joining servers with enforce-secure-profiles enabled will not work!");
                        }
                    }
                    // MODIFIED for porting: was VFP integration/bedrock MixinConnectScreen_1#setupBedrockAccount
                    // (@Inject AFTER syncUninterruptibly). Bedrock target only: the refreshed multiplayer token has to
                    // reach the UserConnection before the handshake is initiated, hence the shared injection point.
                    if (ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
                        final BedrockAuthManager bedrockSession = SaveManager.INSTANCE.getAccountsSave().getBedrockAccount();
                        if (bedrockSession != null) {
                            final MinecraftMultiplayerToken multiplayerToken = bedrockSession.getMinecraftMultiplayerToken().refresh();
                            final KeyPair sessionKeyPair = bedrockSession.getSessionKeyPair();
                            final UUID deviceId = bedrockSession.getDeviceId();
                            vfp$viaUser.put(new AuthData(multiplayerToken.getToken(), sessionKeyPair, deviceId));
                        } else {
                            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Could not get Bedrock account. Joining online mode servers will not work!");
                        }
                    }
                    synchronized (ConnectScreen.this) {
                        if (ConnectScreen.this.aborted) {
                            pendingConnection.disconnect(ConnectScreen.ABORT_CONNECTION);
                            return;
                        }

                        ConnectScreen.this.connection = pendingConnection;
                        minecraft.getDownloadedPackSource().configureForServerControl(pendingConnection, convertPackStatus(server.getResourcePackStatus()));
                    }

                    ConnectScreen.this.connection
                        .initiateServerboundPlayConnection(
                            vfp$connectHost,
                            vfp$connectPort,
                            LoginProtocols.SERVERBOUND,
                            LoginProtocols.CLIENTBOUND,
                            new ClientHandshakePacketListenerImpl(
                                ConnectScreen.this.connection,
                                minecraft,
                                server,
                                ConnectScreen.this.parent,
                                false,
                                null,
                                ConnectScreen.this::updateStatus,
                                new LevelLoadTracker(),
                                transferState
                            ),
                            transferState != null
                        );
                    // MODIFIED for porting: was VFP integration MixinConnectScreen_1#useClassiCubeUsername
                    // (@Redirect on User#getName) - ClassiCube servers expect the stored ClassiCube name instead of
                    // the Mojang session name whenever an MPPass was issued for this join.
                    String vfp$loginName = minecraft.getUser().getName();
                    if (vfp$useClassiCubeAccount) {
                        final CCAccount vfp$classiCubeAccount = SaveManager.INSTANCE.getAccountsSave().getClassicubeAccount();
                        if (vfp$classiCubeAccount != null) {
                            vfp$loginName = vfp$classiCubeAccount.username();
                        }
                    }
                    ConnectScreen.this.connection.send(new ServerboundHelloPacket(vfp$loginName, minecraft.getUser().getProfileId()));
                } catch (Exception exception) {
                    if (ConnectScreen.this.aborted) {
                        return;
                    }

                    Exception cause;
                    if (exception.getCause() instanceof Exception originalCause) {
                        cause = originalCause;
                    } else {
                        cause = exception;
                    }

                    ConnectScreen.LOGGER.error("Couldn't connect to server", exception);
                    // MODIFIED for porting: was VFP bedrock MixinConnectScreen_1#handleNullExceptionMessage
                    // (@WrapOperation on Exception#getMessage, un-ordinaled so both reads below). Vanilla never sees a
                    // null message here, but RakNet/Via pipeline exceptions do, which would NPE the replaceAll chain.
                    final String vfp$causeMessage = cause.getMessage() == null ? "" : cause.getMessage();
                    String message;
                    if (address == null) {
                        message = vfp$causeMessage;
                    } else {
                        // MODIFIED for porting: was VFP srv_resolving MixinConnectScreen_1 getRealAddress/getRealPort
                        // (@Redirect, second un-ordinaled call site) - <=1.17 handshakes with the raw host/port, so that
                        // is the pair to strip here; on newer targets the resolved endpoint is stripped as in vanilla.
                        final boolean vfp$useRawInMessage = ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_17);
                        final String vfp$messageHost = vfp$useRawInMessage ? hostAndPort.getHost() : address.getHostName();
                        final int vfp$messagePort = vfp$useRawInMessage ? hostAndPort.getPort() : address.getPort();
                        message = vfp$causeMessage.replaceAll(vfp$messageHost + ":" + vfp$messagePort, "").replaceAll(address.toString(), "");
                    }
                    minecraft.execute(
                        () -> minecraft.gui
                            .setScreen(
                                new DisconnectedScreen(
                                    ConnectScreen.this.parent,
                                    ConnectScreen.this.connectFailedTitle,
                                    Component.translatable("disconnect.genericReason", message)
                                )
                            )
                    );
                }
            }

            private static ServerPackManager.PackPromptStatus convertPackStatus(final ServerData.ServerPackStatus resourcePackStatus) {
                return switch (resourcePackStatus) {
                    case ENABLED -> ServerPackManager.PackPromptStatus.ALLOWED;
                    case DISABLED -> ServerPackManager.PackPromptStatus.DECLINED;
                    case PROMPT -> ServerPackManager.PackPromptStatus.PENDING;
                };
            }
        };
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    private void updateStatus(final Component status) {
        this.status = status;
    }

    @Override
    public void tick() {
        if (this.connection != null) {
            if (this.connection.isConnected()) {
                this.connection.tick();
            } else {
                this.connection.handleDisconnection();
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> {
            synchronized (this) {
                this.aborted = true;
                if (this.channelFuture != null) {
                    this.channelFuture.cancel(true);
                    this.channelFuture = null;
                }

                if (this.connection != null) {
                    this.connection.disconnect(ABORT_CONNECTION);
                }
            }

            this.minecraft.gui.setScreen(this.parent);
        }).bounds(this.width / 2 - 100, this.height / 4 + 120 + 12, 200, 20).build());
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        long current = Util.getMillis();
        if (current - this.lastNarration > 2000L) {
            this.lastNarration = current;
            this.minecraft.getNarrator().saySystemNow(Component.translatable("narrator.joining"));
        }

        graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 - 50, -1);
    }
}