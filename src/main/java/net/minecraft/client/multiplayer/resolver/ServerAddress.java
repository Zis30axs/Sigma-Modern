package net.minecraft.client.multiplayer.resolver;

import com.google.common.net.HostAndPort;
import com.mojang.logging.LogUtils;
import java.net.IDN;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public final class ServerAddress implements com.viaversion.viafabricplus.injection.access.core.bedrock.IServerAddress {
    // MODIFIED for porting: was VFP bedrock MixinServerAddress @Unique field
    private dev.kastle.netty.channel.nethernet.config.NetherNetAddress viaFabricPlus$netherNetAddress;

    @Override
    public dev.kastle.netty.channel.nethernet.config.NetherNetAddress viaFabricPlus$getNetherNetAddress() {
        return this.viaFabricPlus$netherNetAddress;
    }

    @Override
    public void viaFabricPlus$setNetherNetAddress(final dev.kastle.netty.channel.nethernet.config.NetherNetAddress address) {
        this.viaFabricPlus$netherNetAddress = address;
    }
    private static final Logger LOGGER = LogUtils.getLogger();
    private final HostAndPort hostAndPort;
    private static final ServerAddress INVALID = new ServerAddress(HostAndPort.fromParts("server.invalid", 25565));

    public ServerAddress(final String host, final int port) {
        this(HostAndPort.fromParts(host, port));
    }

    private ServerAddress(final HostAndPort hostAndPort) {
        this.hostAndPort = hostAndPort;
    }

    public String getHost() {
        try {
            return IDN.toASCII(this.hostAndPort.getHost());
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public int getPort() {
        return this.hostAndPort.getPort();
    }

    public static ServerAddress parseString(final @Nullable String input) {
        if (input == null) {
            return INVALID;
        }

        try {
            HostAndPort result = HostAndPort.fromString(input).withDefaultPort(25565);
            ServerAddress addr = new ServerAddress(result);
            if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_16_4)) {
                return ServerNameResolver.DEFAULT.redirectHandler.lookupRedirect(addr).orElse(addr);
            }
            return addr;
        } catch (IllegalArgumentException e) {
            LOGGER.info("Failed to parse URL {}", input, e);
            return INVALID;
        }
    }

    public static boolean isValidAddress(final String input) {
        try {
            HostAndPort hostAndPort = HostAndPort.fromString(input);
            String host = hostAndPort.getHost();
            if (!host.isEmpty()) {
                IDN.toASCII(host);
                return true;
            }
        } catch (IllegalArgumentException var3) {
        }

        return false;
    }

    public static int parsePort(final String str) {
        try {
            return Integer.parseInt(str.trim());
        } catch (Exception var2) {
            return 25565;
        }
    }

    @Override
    public String toString() {
        return this.hostAndPort.toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else {
            return o instanceof ServerAddress serverAddress ? this.hostAndPort.equals(serverAddress.hostAndPort) : false;
        }
    }

    @Override
    public int hashCode() {
        return this.hostAndPort.hashCode();
    }
}
