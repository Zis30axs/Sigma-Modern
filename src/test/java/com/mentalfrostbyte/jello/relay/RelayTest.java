package com.mentalfrostbyte.jello.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.command.CommandManager;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.misc.Relay;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayTest {

    @BeforeAll
    static void initVersion() {
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void lanBroadcastFormatsAndRecognizesSigmaRelayMotd() {
        String motd = RelayLanBroadcast.buildMotd("Steve", "mc.example.net", false);
        assertTrue(RelayLanBroadcast.isSigmaRelayMotd(motd));
        assertFalse(RelayLanBroadcast.isSigmaRelayMotd("A normal singleplayer LAN world"));
        assertFalse(RelayLanBroadcast.isSigmaRelayMotd(null));

        String packet = RelayLanBroadcast.formatAnnouncement(motd, 25577);
        assertTrue(packet.startsWith("[MOTD]"));
        assertTrue(packet.endsWith("[/MOTD][AD]25577[/AD]"));
    }

    @Test
    void relayCommandManagesModulePortStateAndSuggestions(@TempDir final Path tempDir) {
        CommandManager commands = new CommandManager();
        commands.registerDefaults();
        ModuleManager modules = new ModuleManager();
        Relay relay = new Relay();
        modules.register(relay);

        List<String> output = new ArrayList<>();
        int[] saves = {0};
        CommandContext ctx = new CommandContext(modules, commands, tempDir.resolve("profiles"), output::add, () -> saves[0]++);

        assertEquals(Relay.DEFAULT_PORT, relay.parsedPort());
        assertTrue(commands.interceptChat(".relay status", ctx));
        assertTrue(output.getLast().contains("OFF"));

        assertTrue(commands.interceptChat(".relay port 25588", ctx));
        assertEquals(25588, relay.parsedPort());
        assertEquals(1, saves[0]);

        assertTrue(commands.interceptChat(".relay port 80", ctx));
        assertEquals(25588, relay.parsedPort()); // privileged port rejected

        assertTrue(commands.interceptChat(".relay on", ctx));
        assertTrue(relay.isEnabled());

        assertTrue(commands.interceptChat(".relay off", ctx));
        assertFalse(relay.isEnabled());

        List<String> suggestions = commands.complete(".relay p", ctx);
        assertEquals(List.of("port"), suggestions);
    }

    @Test
    void controllerMirrorPeeksNativeServerboundPacketsWithoutMovingReaderIndex() {
        ProtocolInfo<ServerGamePacketListener> serverboundPlay = GameProtocols.SERVERBOUND_TEMPLATE.bind(
            net.minecraft.network.RegistryFriendlyByteBuf.decorator(net.minecraft.core.RegistryAccess.EMPTY),
            () -> false
        );
        RelayControllerMirror mirror = new RelayControllerMirror();

        ByteBuf moveBuf = RelayPacketIds.encodePacket(
            serverboundPlay,
            new ServerboundMovePlayerPacket.PosRot(12.5, 64.0, -30.25, 135.0F, -22.5F, true, false)
        );
        try {
            int readerBefore = moveBuf.readerIndex();
            int id = RelayPacketIds.peekPacketId(moveBuf);
            mirror.observeControllerPacket(id, moveBuf);
            assertEquals(readerBefore, moveBuf.readerIndex());
            assertTrue(mirror.hasPosition());
            assertTrue(mirror.hasRotation());
            assertEquals(12.5, mirror.x(), 1.0E-6);
            assertEquals(64.0, mirror.y(), 1.0E-6);
            assertEquals(-30.25, mirror.z(), 1.0E-6);
            assertEquals(135.0F, mirror.yRot(), 1.0E-4F);
            assertEquals(-22.5F, mirror.xRot(), 1.0E-4F);
            assertTrue(mirror.onGround());
        } finally {
            moveBuf.release();
        }

        ByteBuf slotBuf = RelayPacketIds.encodePacket(serverboundPlay, new ServerboundSetCarriedItemPacket(4));
        try {
            mirror.observeControllerPacket(RelayPacketIds.peekPacketId(slotBuf), slotBuf);
            assertEquals(4, mirror.selectedHotbarSlot());
        } finally {
            slotBuf.release();
        }

        ByteBuf swingBuf = RelayPacketIds.encodePacket(serverboundPlay, new ServerboundSwingPacket(InteractionHand.OFF_HAND));
        try {
            mirror.observeControllerPacket(RelayPacketIds.peekPacketId(swingBuf), swingBuf);
            assertEquals(1L, mirror.swingCount());
            assertEquals(InteractionHand.OFF_HAND, mirror.lastSwingHand());
        } finally {
            swingBuf.release();
        }

        ByteBuf cmdBuf = RelayPacketIds.encodePacket(serverboundPlay, new ServerboundChatCommandPacket("gamemode creative"));
        try {
            int readerBefore = cmdBuf.readerIndex();
            String cmd = RelayControllerMirror.peekChatOrCommandText(RelayPacketIds.peekPacketId(cmdBuf), cmdBuf);
            assertEquals("gamemode creative", cmd);
            assertEquals(readerBefore, cmdBuf.readerIndex());
        } finally {
            cmdBuf.release();
        }
    }

    @Test
    void outboundDropWhitelistPreservesProtocolStateMachineAndBlocksGameplayPackets() {
        RelayCoordinator coordinator = RelayCoordinator.get();
        assertTrue(RelayChannelBridge.OutboundDrop.shouldAllowHostPacket(
            new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")), coordinator));
        assertTrue(RelayChannelBridge.OutboundDrop.shouldAllowHostPacket(
            ServerboundConfigurationAcknowledgedPacket.INSTANCE, coordinator));
        assertTrue(RelayChannelBridge.OutboundDrop.shouldAllowHostPacket(
            ServerboundFinishConfigurationPacket.INSTANCE, coordinator));
        assertTrue(RelayChannelBridge.OutboundDrop.shouldAllowHostPacket(
            new ServerboundChatAckPacket(3), coordinator));

        assertFalse(RelayChannelBridge.OutboundDrop.shouldAllowHostPacket(
            new ServerboundMovePlayerPacket.StatusOnly(true, false), coordinator));
        assertFalse(RelayChannelBridge.OutboundDrop.shouldAllowHostPacket(
            new ServerboundKeepAlivePacket(42L), coordinator));
    }

    @Test
    void inboundCopySkipsIncompleteBundleTailWhenControllerAttachesMidBundle() {
        RelayCoordinator coordinator = RelayCoordinator.get();
        ProtocolInfo<ClientGamePacketListener> dummyPlay = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
            buf -> {
                throw new UnsupportedOperationException();
            }
        );

        List<Integer> forwardedIds = new ArrayList<>();
        RelayProxySession fakeSession = new RelayProxySession(coordinator) {
            @Override
            public boolean isInPlay() {
                return true;
            }
        };
        EmbeddedChannel controllerWire = new EmbeddedChannel(
            new io.netty.channel.ChannelOutboundHandlerAdapter() {
                @Override
                public void write(final ChannelHandlerContext ctx, final Object msg, final io.netty.channel.ChannelPromise promise) {
                    if (msg instanceof ByteBuf b) {
                        forwardedIds.add(RelayPacketIds.peekPacketId(b));
                        b.release();
                        promise.trySuccess();
                        return;
                    }
                    ctx.write(msg, promise);
                }
            },
            fakeSession
        );

        EmbeddedChannel hostChannel = new EmbeddedChannel();
        try {
            // Build a host pipeline with InboundCopy right before a PLAY PacketDecoder.
            RelayChannelBridge.InboundCopy inboundCopy = new RelayChannelBridge.InboundCopy(coordinator);
            hostChannel.pipeline().addLast(RelayChannelBridge.HANDLER_INBOUND, inboundCopy);
            hostChannel.pipeline().addLast("decoder", new PacketDecoder<>(dummyPlay));
            // Absorb raw bytes before PacketDecoder tries to instantiate RegistryFriendlyByteBuf.
            hostChannel.pipeline().addAfter(RelayChannelBridge.HANDLER_INBOUND, "absorb", new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(final ChannelHandlerContext ctx, final ByteBuf msg) {
                }
            });

            // 1. Before controller attaches, server opens a bundle on Host A.
            ByteBuf openDelimiter = Unpooled.buffer(4);
            VarInt.write(openDelimiter, RelayPacketIds.S2C_BUNDLE_DELIMITER);
            hostChannel.writeInbound(openDelimiter);

            // 2. Controller B attaches mid-bundle!
            assertTrue(coordinator.tryClaimSession(fakeSession, "TesterB"));

            // 3. Tail packet inside the already-open bundle + closing delimiter MUST NOT be forwarded.
            ByteBuf midBundlePacket = Unpooled.buffer(8);
            VarInt.write(midBundlePacket, 0x26);
            midBundlePacket.writeLong(123L);
            hostChannel.writeInbound(midBundlePacket);

            ByteBuf closeDelimiter = Unpooled.buffer(4);
            VarInt.write(closeDelimiter, RelayPacketIds.S2C_BUNDLE_DELIMITER);
            hostChannel.writeInbound(closeDelimiter);

            assertTrue(forwardedIds.isEmpty(), "Incomplete bundle tail must not leak to Controller B");

            // 4. The very next packet is on a clean boundary and MUST be forwarded!
            ByteBuf cleanPacket = Unpooled.buffer(8);
            VarInt.write(cleanPacket, 0x26);
            cleanPacket.writeLong(456L);
            hostChannel.writeInbound(cleanPacket);

            assertEquals(List.of(0x26), forwardedIds);
        } finally {
            coordinator.onControllerDisconnected(fakeSession);
            controllerWire.finishAndReleaseAll();
            hostChannel.finishAndReleaseAll();
        }
    }

    @Test
    void proxyServerAnswersNativeStatusAndPingOverLoopback() throws Exception {
        RelayCoordinator coordinator = RelayCoordinator.get();
        RelayProxyServer server = new RelayProxyServer(0, false, coordinator);
        MultiThreadIoEventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            server.start();
            int port = server.port();
            assertTrue(port > 0);

            BlockingQueue<Packet<?>> received = new LinkedBlockingQueue<>();
            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline()
                            .addLast("splitter", new Varint21FrameDecoder(null))
                            .addLast("prepender", new Varint21LengthFieldPrepender())
                            .addLast("collector", new SimpleChannelInboundHandler<ByteBuf>() {
                                @Override
                                protected void channelRead0(final ChannelHandlerContext ctx, final ByteBuf msg) {
                                    received.add(StatusProtocols.CLIENTBOUND.codec().decode(msg));
                                }
                            });
                    }
                })
                .connect("127.0.0.1", port)
                .sync()
                .channel();

            try {
                client.writeAndFlush(RelayPacketIds.encodePacket(
                    HandshakeProtocols.SERVERBOUND,
                    new ClientIntentionPacket(
                        SharedConstants.getCurrentVersion().protocolVersion(),
                        "127.0.0.1",
                        port,
                        ClientIntent.STATUS
                    )
                )).sync();
                client.writeAndFlush(RelayPacketIds.encodePacket(
                    StatusProtocols.SERVERBOUND,
                    ServerboundStatusRequestPacket.INSTANCE
                )).sync();

                Packet<?> first = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(first);
                assertTrue(first instanceof ClientboundStatusResponsePacket);
                ClientboundStatusResponsePacket statusPacket = (ClientboundStatusResponsePacket) first;
                assertTrue(RelayLanBroadcast.isSigmaRelayMotd(statusPacket.status().description().getString()));
                assertEquals(
                    SharedConstants.getCurrentVersion().protocolVersion(),
                    statusPacket.status().version().orElseThrow().protocol()
                );

                long pingTimestamp = 987654321L;
                client.writeAndFlush(RelayPacketIds.encodePacket(
                    StatusProtocols.SERVERBOUND,
                    new ServerboundPingRequestPacket(pingTimestamp)
                )).sync();

                Packet<?> second = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(second);
                assertTrue(second instanceof ClientboundPongResponsePacket);
                assertEquals(pingTimestamp, ((ClientboundPongResponsePacket) second).time());
            } finally {
                client.close().syncUninterruptibly();
            }

            // Also verify RelayJvmInjector.probeRelayStatus parses version & protocol accurately!
            RelayJvmInjector.ProbeStatus probe = RelayJvmInjector.probeRelayStatus("127.0.0.1", port, 2_000);
            assertNotNull(probe);
            assertEquals(SharedConstants.getCurrentVersion().protocolVersion(), probe.protocol());
        } finally {
            server.stop();
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void agentBuilderCompilesJava8CompatibleJarWithAgentManifest() throws Exception {
        Path agentJar = RelayAgentBuilder.ensureAgentJar();
        assertTrue(java.nio.file.Files.isRegularFile(agentJar));
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJar.toFile())) {
            java.util.jar.Manifest mf = jar.getManifest();
            assertNotNull(mf);
            assertEquals("com.mcrelay.agent.RelayAgent", mf.getMainAttributes().getValue("Agent-Class"));
            assertEquals("true", mf.getMainAttributes().getValue("Can-Redefine-Classes"));

            java.util.jar.JarEntry entry = jar.getJarEntry("com/mcrelay/agent/RelayAgent.class");
            assertNotNull(entry);
            try (java.io.InputStream in = jar.getInputStream(entry)) {
                byte[] header = in.readNBytes(8);
                assertEquals(8, header.length);
                int majorVersion = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                assertEquals(52, majorVersion, "Agent bytecode must target Java 8 (class version 52)");
            }
        }
    }

    @Test
    void jvmInjectorClassifiesMinecraftCommandLinesAndExtractsVersions() {
        assertTrue(RelayJvmInjector.classifyMinecraftProcess(
            "net.minecraft.client.main.Main",
            "java -Xmx4G net.minecraft.client.main.Main --version 1.20.1 --accessToken 0"
        ));
        assertTrue(RelayJvmInjector.classifyMinecraftProcess(
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "java -cp ... net.fabricmc.loader.impl.launch.knot.KnotClient --version 1.21.4"
        ));
        assertFalse(RelayJvmInjector.classifyMinecraftProcess(
            "org.apache.maven.surefire.booter.ForkedBooter",
            "java -jar surefirebooter.jar"
        ));

        assertEquals("1.20.1", RelayJvmInjector.guessMinecraftVersion("--username Steve --version 1.20.1 --assetIndex 5"));
        assertEquals("1.21.4", RelayJvmInjector.guessMinecraftVersion("/home/user/.minecraft/versions/1.21.4/1.21.4.jar"));
        assertEquals("26.2", RelayJvmInjector.guessMinecraftVersion("-Dminecraft.version=26.2"));
    }

    @Test
    void jvmInjectorHotAttachesCompiledAgentIntoLiveExternalJavaProcess(@TempDir final Path tempWorkDir) throws Exception {
        // Compile a tiny sleeper target process to verify real VirtualMachine.attach + loadAgent end-to-end.
        Path dummySrc = tempWorkDir.resolve("DummyTargetJvm.java");
        java.nio.file.Files.writeString(dummySrc, """
            public class DummyTargetJvm {
                public static void main(String[] args) throws Exception {
                    System.out.println("READY");
                    System.out.flush();
                    Thread.sleep(30_000L);
                }
            }
            """);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", tempWorkDir.toString(), dummySrc.toString()));

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process targetProc = new ProcessBuilder(javaBin, "-cp", tempWorkDir.toString(), "DummyTargetJvm")
            .directory(tempWorkDir.toFile())
            .redirectErrorStream(true)
            .start();

        try {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(targetProc.getInputStream()))) {
                assertEquals("READY", reader.readLine());
            }

            String targetPid = Long.toString(targetProc.pid());
            List<RelayJvmInjector.JvmTarget> allJvms = RelayJvmInjector.listProcesses(true);
            assertTrue(allJvms.stream().anyMatch(t -> t.pid().equals(targetPid)));

            List<String> logs = new ArrayList<>();
            RelayJvmInjector.inject(targetPid, 25597, logs::add);
            assertTrue(logs.stream().anyMatch(l -> l.contains("Hot-injection succeeded")));

            // Verify RelayAgent.agentmain actually ran inside the external JVM and wrote its marker!
            Path marker = tempWorkDir.resolve("mc-relay-loaded.txt");
            long deadline = System.currentTimeMillis() + 5_000L;
            while (!java.nio.file.Files.exists(marker) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertTrue(java.nio.file.Files.isRegularFile(marker), "Injected agent must execute inside target JVM");
            String markerText = java.nio.file.Files.readString(marker);
            assertTrue(markerText.contains("agentmain called, args=port=25597"));
        } finally {
            targetProc.destroyForcibly();
            targetProc.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void relayTakeoverResolvesLegacyAndModernProtocolsAndBlocksControllerIdentityLeak() throws Exception {
        // 1. Legacy (1.8.9=47, 1.12.2=340, 1.16.5=754, 1.18.2=758) and Modern (1.19.x–26.2) resolve accurately.
        for (int proto : new int[]{47, 107, 340, 393, 754, 758, 762, 763, 764, 765, 766, 772, 776}) {
            assertEquals(proto, RelayJvmInjector.resolveViaProtocol(proto, "unknown").getVersion());
        }
        assertEquals("1.8.9", RelayJvmInjector.guessMinecraftVersion(
            "java net.minecraft.launchwrapper.Launch --version 1.8.9 --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker"
        ));
        assertEquals("1.12.2", RelayJvmInjector.guessMinecraftVersion("/home/user/.minecraft/versions/1.12.2/1.12.2.jar"));

        // 2. Agent's LegacyAdapter, PacketIdResolver, and PacketBuilder.loginSuccess compile cleanly for Java 8 (major=52)
        //    and encode exact wire formats from 1.8.9 (47) through 26.2 (776).
        Path agentJar = RelayAgentBuilder.ensureAgentJar();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJar.toFile())) {
            java.util.jar.JarEntry legacyEntry = jar.getJarEntry("com/mcrelay/agent/adapter/LegacyAdapter.class");
            assertNotNull(legacyEntry, "LegacyAdapter must be packaged inside mc-relay-agent.jar");
            try (java.io.InputStream in = jar.getInputStream(legacyEntry)) {
                byte[] header = in.readNBytes(8);
                int majorVersion = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                assertEquals(52, majorVersion, "LegacyAdapter bytecode must target Java 8 (class version 52)");
            }
        }

        java.util.UUID authClUuid = java.util.UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");
        List<String[]> authClProps = List.<String[]>of(new String[]{"textures", "eyJ0ZXh0dXJlcyI6e319", "authcl-sig"});
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{agentJar.toUri().toURL()},
                RelayTest.class.getClassLoader())) {
            Class<?> pbClass = Class.forName("com.mcrelay.agent.proxy.PacketBuilder", true, loader);
            java.lang.reflect.Method loginSuccess = pbClass.getMethod(
                "loginSuccess", java.util.UUID.class, String.class, List.class, int.class
            );

            // 2a. 1.8.x – 1.15.2 (< 735): hyphenated String UUID + username, no properties
            for (int proto : new int[]{47, 340, 578}) {
                ByteBuf buf = (ByteBuf) loginSuccess.invoke(null, authClUuid, "AuthClPlayer", authClProps, proto);
                try {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                    assertEquals(0x02, VarInt.read(fbb));
                    assertEquals(authClUuid.toString(), fbb.readUtf(36));
                    assertEquals("AuthClPlayer", fbb.readUtf(16));
                    assertEquals(0, fbb.readableBytes(), "Protocol " + proto + " LoginSuccess trailing bytes");
                } finally {
                    buf.release();
                }
            }

            // 2b. 1.16 – 1.18.2 (735..758): 16-byte binary UUID + username, no properties
            for (int proto : new int[]{735, 754, 758}) {
                ByteBuf buf = (ByteBuf) loginSuccess.invoke(null, authClUuid, "AuthClPlayer", authClProps, proto);
                try {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                    assertEquals(0x02, VarInt.read(fbb));
                    assertEquals(authClUuid, fbb.readUUID());
                    assertEquals("AuthClPlayer", fbb.readUtf(16));
                    assertEquals(0, fbb.readableBytes(), "Protocol " + proto + " LoginSuccess trailing bytes");
                } finally {
                    buf.release();
                }
            }

            // 2c. 1.19+ (>= 759): binary UUID + username + signed skin properties (+ 1.20.5/26.2 trailer)
            for (int proto : new int[]{762, 763, 764, 766, 772, 776}) {
                ByteBuf buf = (ByteBuf) loginSuccess.invoke(null, authClUuid, "AuthClPlayer", authClProps, proto);
                try {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                    assertEquals(0x02, VarInt.read(fbb)); // LoginSuccess ID
                    assertEquals(authClUuid, fbb.readUUID());
                    assertEquals("AuthClPlayer", fbb.readUtf(16));
                    assertEquals(1, VarInt.read(fbb));
                    assertEquals("textures", fbb.readUtf());
                    assertEquals("eyJ0ZXh0dXJlcyI6e319", fbb.readUtf());
                    assertTrue(fbb.readBoolean());
                    assertEquals("authcl-sig", fbb.readUtf());
                    if (proto == 766) {
                        assertFalse(fbb.readBoolean()); // strictErrorHandling
                    } else if (proto == 776) {
                        assertNotNull(fbb.readUUID());  // 26.2 sessionId
                    }
                    assertEquals(0, fbb.readableBytes(), "Protocol " + proto + " LoginSuccess trailing bytes");
                } finally {
                    buf.release();
                }
            }

            // 2d. PacketIdResolver & AdapterRegistry dispatch for legacy protocols
            Class<?> pirClass = Class.forName("com.mcrelay.agent.adapter.PacketIdResolver", true, loader);
            java.lang.reflect.Method s2cPlayId = pirClass.getMethod("s2cPlayId", int.class, String.class);
            java.lang.reflect.Method cpByProto = pirClass.getMethod("customPayloadIdByProtocol", int.class);
            assertEquals(0x01, s2cPlayId.invoke(null, 47, "LOGIN"));
            assertEquals(0x23, s2cPlayId.invoke(null, 340, "LOGIN"));
            assertEquals(0x24, s2cPlayId.invoke(null, 754, "LOGIN"));
            assertEquals(0x26, s2cPlayId.invoke(null, 758, "LOGIN"));
            assertEquals(0x17, cpByProto.invoke(null, 47));
            assertEquals(0x09, cpByProto.invoke(null, 340));
            assertEquals(0x0B, cpByProto.invoke(null, 754));
            assertEquals(0x0A, cpByProto.invoke(null, 758));

            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> vdLoaderCls = (Class<? extends Enum>) Class.forName(
                "com.mcrelay.agent.VersionDetect$Loader", true, loader
            );
            Class<?> vdInfoCls = Class.forName("com.mcrelay.agent.VersionDetect$Info", true, loader);
            Class<?> regCls = Class.forName("com.mcrelay.agent.adapter.AdapterRegistry", true, loader);
            java.lang.reflect.Method forVersion = regCls.getMethod("forVersion", vdInfoCls);
            java.lang.reflect.Constructor<?> infoCtor = vdInfoCls.getConstructor(String.class, vdLoaderCls, int.class);

            Object info189 = infoCtor.newInstance("1.8.9", Enum.valueOf(vdLoaderCls, "FORGE"), 47);
            Object adapter189 = forVersion.invoke(null, info189);
            assertEquals("com.mcrelay.agent.adapter.LegacyAdapter", adapter189.getClass().getName());

            Object info1122 = infoCtor.newInstance("1.12.2", Enum.valueOf(vdLoaderCls, "VANILLA"), 340);
            Object adapter1122 = forVersion.invoke(null, info1122);
            assertEquals("com.mcrelay.agent.adapter.LegacyAdapter", adapter1122.getClass().getName());

            Object info1165 = infoCtor.newInstance("1.16.5", Enum.valueOf(vdLoaderCls, "FABRIC"), 754);
            Object adapter1165 = forVersion.invoke(null, info1165);
            assertEquals("com.mcrelay.agent.adapter.ModernAdapter", adapter1165.getClass().getName());
        }

        // 3. A Connection flagged with sigma$setRelayTakeover(true) suppresses B's CustomPayload
        //    (Brand / ClientSpoof / ClientIdSpoofer in CONFIGURATION and PLAY) while passing normal gameplay packets.
        net.minecraft.network.Connection conn = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
        conn.sigma$setRelayTakeover(true);
        EmbeddedChannel dummyWire = new EmbeddedChannel();
        try {
            conn.channel = dummyWire;
            // Brand custom payload MUST be dropped before outbound wire (both CONFIGURATION and PLAY)!
            conn.send(new ServerboundCustomPayloadPacket(new BrandPayload("lunarclient:spoofed")));
            assertTrue(dummyWire.outboundMessages().isEmpty(), "B's CustomPayload must never leak on Relay takeover");

            // Normal gameplay movement packet MUST pass through!
            conn.send(new ServerboundMovePlayerPacket.StatusOnly(true, false));
            assertEquals(1, dummyWire.outboundMessages().size());
        } finally {
            dummyWire.finishAndReleaseAll();
        }
    }
}

