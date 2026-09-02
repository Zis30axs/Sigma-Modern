package net.minecraft.client.multiplayer.resolver;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.util.bedrock.NetherNetInetSocketAddress;
import dev.kastle.netty.channel.nethernet.config.NetherNetAddress;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import com.google.common.annotations.VisibleForTesting;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ServerNameResolver {
    public static final ServerNameResolver DEFAULT = new ServerNameResolver(
        ServerAddressResolver.SYSTEM, ServerRedirectHandler.createDnsSrvRedirectHandler(), AddressCheck.createFromService()
    );
    private final ServerAddressResolver resolver;
    public final ServerRedirectHandler redirectHandler;
    private final AddressCheck addressCheck;

    @VisibleForTesting
    ServerNameResolver(final ServerAddressResolver resolver, final ServerRedirectHandler redirectHandler, final AddressCheck addressCheck) {
        this.resolver = resolver;
        this.redirectHandler = redirectHandler;
        this.addressCheck = addressCheck;
    }

    public Optional<ResolvedServerAddress> resolveAddress(final ServerAddress address) {
        // MODIFIED for porting: was VFP core/connection/bedrock MixinServerNameResolver#returnNetherNetAddressEarly
        // (@Inject HEAD cancellable). A NetherNet target has no DNS name to look up - the ServerAddress only carries
        // the network id - so resolution is short-circuited into a NetherNetInetSocketAddress. Guarded by the
        // presence of the address, not by a version. It must stay above the Bedrock branch below, which would
        // otherwise DNS-resolve the synthetic ".nethernet.viafabricplus.localhost" host and fail.
        final NetherNetAddress netherNetAddress = address.viaFabricPlus$getNetherNetAddress();
        if (netherNetAddress != null) {
            return Optional.of(new ResolvedServerAddress() {
                @Override
                public String getHostName() {
                    return netherNetAddress.getNetworkId();
                }

                @Override
                public String getHostIp() {
                    return netherNetAddress.getNetworkId();
                }

                @Override
                public int getPort() {
                    return 0;
                }

                @Override
                public InetSocketAddress asInetSocketAddress() {
                    return new NetherNetInetSocketAddress(netherNetAddress);
                }
            });
        }

        // MODIFIED for porting: was VFP features/bedrock/networking MixinServerNameResolver#oldResolveBehaviour
        // (@Inject HEAD cancellable). Bedrock resolves raw: no AddressCheck allow-list and no SRV redirect lookup,
        // so the client dials exactly the host/port the RakNet server was entered as.
        if (ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            return this.resolver.resolve(address);
        }

        // MODIFIED for porting: was VFP networking/srv_resolving MixinServerNameResolver#oldResolveBehaviour
        // (@Inject HEAD cancellable). For <= 1.16.4 the SRV redirect is already applied in
        // ServerAddress#parseString, so this method must resolve and nothing else - no blocked-server check
        // and no second redirect lookup on the already-resolved address.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_16_4)) {
            return this.resolver.resolve(address);
        }

        Optional<ResolvedServerAddress> resolvedAddress = this.resolver.resolve(address);
        if ((!resolvedAddress.isPresent() || this.addressCheck.isAllowed(resolvedAddress.get())) && this.addressCheck.isAllowed(address)) {
            Optional<ServerAddress> redirectedAddress = this.redirectHandler.lookupRedirect(address);
            if (redirectedAddress.isPresent()) {
                resolvedAddress = this.resolver.resolve(redirectedAddress.get()).filter(this.addressCheck::isAllowed);
            }

            return resolvedAddress;
        } else {
            return Optional.empty();
        }
    }
}
