package net.minecraft.client.multiplayer;

import com.google.common.base.Strings;
import com.mojang.authlib.exceptions.MinecraftClientException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.InsecurePublicKeyException.MissingException;
import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.authlib.yggdrasil.YggdrasilUserApiService;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse.KeyPair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viafabricplus.features.networking.legacy_chat_signature.KeyPairResponse1_19_0;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class AccountProfileKeyPairManager implements ProfileKeyPairManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Duration MINIMUM_PROFILE_KEY_REFRESH_INTERVAL = Duration.ofHours(1L);
    private static final Path PROFILE_KEY_PAIR_DIR = Path.of("profilekeys");
    private final UserApiService userApiService;
    private final Path profileKeyPairPath;
    private CompletableFuture<Optional<ProfileKeyPair>> keyPair = CompletableFuture.completedFuture(Optional.empty());
    private Instant nextProfileKeyRefreshTime = Instant.EPOCH;
    // MODIFIED for porting: was VFP legacy_chat_signature MixinYggdrasilUserApiService @Shadow minecraftClient /
    // routeKeyPair. Without a mixin runtime the two fields the overwrite needs are read reflectively; if AuthLib ever
    // renames them both stay null and fetchProfileKeyPair falls back to the plain vanilla request.
    private static final @Nullable Field VFP_MINECRAFT_CLIENT_FIELD = vfpFindYggdrasilField("minecraftClient");
    private static final @Nullable Field VFP_ROUTE_KEY_PAIR_FIELD = vfpFindYggdrasilField("routeKeyPair");

    public AccountProfileKeyPairManager(final UserApiService userApiService, final UUID profileId, final Path gameDirectory) {
        this.userApiService = userApiService;
        this.profileKeyPairPath = gameDirectory.resolve(PROFILE_KEY_PAIR_DIR).resolve(profileId + ".json");
    }

    @Override
    public CompletableFuture<Optional<ProfileKeyPair>> prepareKeyPair() {
        this.nextProfileKeyRefreshTime = Instant.now().plus(MINIMUM_PROFILE_KEY_REFRESH_INTERVAL);
        this.keyPair = this.keyPair.thenCompose(this::readOrFetchProfileKeyPair);
        return this.keyPair;
    }

    @Override
    public boolean shouldRefreshKeyPair() {
        return this.keyPair.isDone() && Instant.now().isAfter(this.nextProfileKeyRefreshTime)
            ? this.keyPair.join().map(ProfileKeyPair::dueRefresh).orElse(true)
            : false;
    }

    private CompletableFuture<Optional<ProfileKeyPair>> readOrFetchProfileKeyPair(final Optional<ProfileKeyPair> cachedKeyPair) {
        return CompletableFuture.supplyAsync(() -> {
            if (cachedKeyPair.isPresent() && !cachedKeyPair.get().dueRefresh()) {
                if (!SharedConstants.IS_RUNNING_IN_IDE) {
                    this.writeProfileKeyPair(null);
                }

                return cachedKeyPair;
            } else {
                try {
                    ProfileKeyPair fetchedKeyPair = this.fetchProfileKeyPair(this.userApiService);
                    this.writeProfileKeyPair(fetchedKeyPair);
                    return Optional.ofNullable(fetchedKeyPair);
                } catch (IOException | CryptException | MinecraftClientException e) {
                    LOGGER.error("Failed to retrieve profile key pair", e);
                    this.writeProfileKeyPair(null);
                    return cachedKeyPair;
                }
            }
        }, Util.nonCriticalIoPool());
    }

    private Optional<ProfileKeyPair> readProfileKeyPair() {
        if (Files.notExists(this.profileKeyPairPath)) {
            return Optional.empty();
        }

        try (BufferedReader bufferedReader = Files.newBufferedReader(this.profileKeyPairPath)) {
            return ProfileKeyPair.CODEC.parse(JsonOps.INSTANCE, StrictJsonParser.parse(bufferedReader)).result();
        } catch (Exception e) {
            LOGGER.error("Failed to read profile key pair file {}", this.profileKeyPairPath, e);
            return Optional.empty();
        }
    }

    private void writeProfileKeyPair(final @Nullable ProfileKeyPair profileKeyPair) {
        try {
            Files.deleteIfExists(this.profileKeyPairPath);
        } catch (IOException e) {
            LOGGER.error("Failed to delete profile key pair file {}", this.profileKeyPairPath, e);
        }

        if (profileKeyPair != null) {
            if (SharedConstants.IS_RUNNING_IN_IDE) {
                ProfileKeyPair.CODEC.encodeStart(JsonOps.INSTANCE, profileKeyPair).ifSuccess(jsonStr -> {
                    try {
                        Files.createDirectories(this.profileKeyPairPath.getParent());
                        Files.writeString(this.profileKeyPairPath, jsonStr.toString());
                    } catch (Exception e) {
                        LOGGER.error("Failed to write profile key pair file {}", this.profileKeyPairPath, e);
                    }
                });
            }
        }
    }

    private @Nullable ProfileKeyPair fetchProfileKeyPair(final UserApiService userApiService) throws CryptException, IOException {
        // MODIFIED for porting: was VFP legacy_chat_signature MixinYggdrasilUserApiService#getKeyPair (@Overwrite) plus
        // MixinKeyPairResponse (@Unique viaFabricPlus$legacyKeySignature). AuthLib's KeyPairResponse is a final record in a
        // library jar, so it can neither deserialise the pre-1.20-rc1 'publicKeySignature' nor carry the extra field: the
        // certificates route is posted with the superset record here and the legacy signature is handed on by hand.
        final MinecraftClient vfpMinecraftClient = (MinecraftClient)vfpReadYggdrasilField(VFP_MINECRAFT_CLIENT_FIELD, userApiService);
        final URL vfpRouteKeyPair = (URL)vfpReadYggdrasilField(VFP_ROUTE_KEY_PAIR_FIELD, userApiService);
        KeyPairResponse keyPair;
        byte @Nullable [] vfpLegacyKeySignature = null;
        if (vfpMinecraftClient != null && vfpRouteKeyPair != null) {
            final KeyPairResponse1_19_0 legacyKeyPair = vfpMinecraftClient.post(vfpRouteKeyPair, KeyPairResponse1_19_0.class);
            if (legacyKeyPair == null) {
                return null;
            }

            keyPair = new KeyPairResponse(
                legacyKeyPair.keyPair(), legacyKeyPair.publicKeySignatureV2(), legacyKeyPair.expiresAt(), legacyKeyPair.refreshedAfter()
            );
            final ByteBuffer legacySignature = legacyKeyPair.publicKeySignature();
            if (legacySignature != null && legacySignature.array().length != 0) {
                vfpLegacyKeySignature = legacySignature.array();
            } else {
                ViaFabricPlusImpl.INSTANCE
                    .getLogger()
                    .error("Could not get legacy public key signature. 1.19.0 with secure-profiles enabled will not work!");
            }
        } else {
            keyPair = userApiService.getKeyPair();
        }

        if (keyPair != null) {
            ProfilePublicKey.Data publicKeyData = parsePublicKey(keyPair);
            // MODIFIED for porting: was VFP legacy_chat_signature MixinAccountProfileKeyPairManager#trackLegacyKey
            // (@Inject parsePublicKey RETURN) - the Data is not observed between its only return and this call.
            // ConnectScreen reads it back for a target of exactly 1.19.0 to install the ChatSession1_19_0.
            publicKeyData.viafabricplus$setLegacyPublicKeySignature(vfpLegacyKeySignature);
            return new ProfileKeyPair(
                Crypt.stringToPemRsaPrivateKey(keyPair.keyPair().privateKey()), new ProfilePublicKey(publicKeyData), Instant.parse(keyPair.refreshedAfter())
            );
        } else {
            return null;
        }
    }

    // MODIFIED for porting: mixin-free stand-in for MixinYggdrasilUserApiService's @Shadow @Final field access. Returns
    // null for anything that is not a YggdrasilUserApiService (the offline service, for instance), which is exactly where
    // upstream's overwrite does not apply either.
    private static @Nullable Field vfpFindYggdrasilField(final String name) {
        try {
            final Field field = YggdrasilUserApiService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Failed to access YggdrasilUserApiService.{}, the legacy public key signature will be missing", name, e);
            return null;
        }
    }

    private static @Nullable Object vfpReadYggdrasilField(final @Nullable Field field, final UserApiService userApiService) {
        if (field == null || !(userApiService instanceof YggdrasilUserApiService)) {
            return null;
        }

        try {
            return field.get(userApiService);
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.error("Failed to read YggdrasilUserApiService.{}, the legacy public key signature will be missing", field.getName(), e);
            return null;
        }
    }

    private static ProfilePublicKey.Data parsePublicKey(final KeyPairResponse response) throws CryptException {
        KeyPair keyPair = response.keyPair();
        if (keyPair != null
            && !Strings.isNullOrEmpty(keyPair.publicKey())
            && response.publicKeySignature() != null
            && response.publicKeySignature().array().length != 0) {
            try {
                Instant expiresAt = Instant.parse(response.expiresAt());
                PublicKey key = Crypt.stringToRsaPublicKey(keyPair.publicKey());
                ByteBuffer signature = response.publicKeySignature();
                return new ProfilePublicKey.Data(expiresAt, key, signature.array());
            } catch (DateTimeException | IllegalArgumentException e) {
                throw new CryptException(e);
            }
        } else {
            throw new CryptException(new MissingException("Missing public key"));
        }
    }
}