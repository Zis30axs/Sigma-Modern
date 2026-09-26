package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ProtocolVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.github.retrooper.packetevents.impl.netty.BuildData;
import io.github.retrooper.packetevents.impl.netty.factory.NettyPacketEventsBuilder;
import io.github.retrooper.packetevents.impl.netty.manager.player.PlayerManagerAbstract;
import io.github.retrooper.packetevents.impl.netty.manager.protocol.ProtocolManagerAbstract;
import io.github.retrooper.packetevents.impl.netty.manager.server.ServerManagerAbstract;
import io.netty.util.ReferenceCountUtil;

/**
 * PacketEvents without a server: an API instance that injects into nothing. Packets reach it only through
 * {@link GrimEngine}, and everything Grim writes to the player comes back to {@link GrimEngine#grimWrites} instead of
 * going out on a channel.
 */
final class LocalPacketEvents {

    private LocalPacketEvents() {
    }

    static PacketEventsAPI<BuildData> create(final ServerVersion serverVersion, final GrimEngine engine) {
        ServerManagerAbstract server = new ServerManagerAbstract() {
            @Override
            public ServerVersion getVersion() {
                return serverVersion;
            }
        };
        PlayerManagerAbstract players = new PlayerManagerAbstract() {
            @Override
            public int getPing(final Object player) {
                return 0;
            }

            @Override
            public Object getChannel(final Object player) {
                return engine.channel();
            }
        };
        ChannelInjector injector = new ChannelInjector() {
            @Override
            public void inject() {
            }

            @Override
            public void uninject() {
            }

            @Override
            public void updateUser(final Object channel, final User user) {
            }

            @Override
            public void setPlayer(final Object channel, final Object player) {
            }

            @Override
            public boolean isPlayerSet(final Object channel) {
                return true;
            }

            @Override
            public boolean isProxy() {
                return false;
            }
        };
        PacketEventsSettings settings = new PacketEventsSettings()
                .checkForUpdates(false)
                .bStats(false)
                .reEncodeByDefault(false)
                .kickOnPacketException(false)
                .debug(false);
        return NettyPacketEventsBuilder.buildNoCache(new BuildData("sigma-selfcheck"), injector, new Protocol(engine), server, players, settings);
    }

    /**
     * Where PacketEvents would write to and read from the player's channel. Every wrapper Grim writes goes to the
     * engine; raw buffers (nothing in Grim writes those) are dropped.
     */
    private static final class Protocol extends ProtocolManagerAbstract {

        private final GrimEngine engine;

        private Protocol(final GrimEngine engine) {
            this.engine = engine;
        }

        @Override
        public ProtocolVersion getPlatformVersion() {
            return ProtocolVersion.UNKNOWN;
        }

        @Override
        public void sendPacket(final Object channel, final PacketWrapper<?> wrapper) {
            this.engine.grimWrites(wrapper);
        }

        @Override
        public void sendPacketSilently(final Object channel, final PacketWrapper<?> wrapper) {
            this.engine.grimWrites(wrapper);
        }

        @Override
        public void writePacket(final Object channel, final PacketWrapper<?> wrapper) {
            this.engine.grimWrites(wrapper);
        }

        @Override
        public void receivePacket(final Object channel, final PacketWrapper<?> wrapper) {
            // nothing may be injected as if the player had sent it
        }

        @Override
        public void sendPacket(final Object channel, final Object buffer) {
            ReferenceCountUtil.release(buffer);
        }

        @Override
        public void sendPacketSilently(final Object channel, final Object buffer) {
            ReferenceCountUtil.release(buffer);
        }

        @Override
        public void writePacket(final Object channel, final Object buffer) {
            ReferenceCountUtil.release(buffer);
        }

        @Override
        public void writePacketSilently(final Object channel, final Object buffer) {
            ReferenceCountUtil.release(buffer);
        }

        @Override
        public void receivePacket(final Object channel, final Object buffer) {
            ReferenceCountUtil.release(buffer);
        }

        @Override
        public void receivePacketSilently(final Object channel, final Object buffer) {
            ReferenceCountUtil.release(buffer);
        }
    }
}
