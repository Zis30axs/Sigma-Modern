package net.minecraft.client.multiplayer.resolver;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import com.google.common.annotations.VisibleForTesting;
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
