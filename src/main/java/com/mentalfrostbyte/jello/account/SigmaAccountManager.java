package com.mentalfrostbyte.jello.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.util.io.JsonFileUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sigma's modern account store.
 *
 * <p>The historical client kept email/password credentials and session tokens in a lightly-obfuscated
 * local file. This port keeps the old account-list workflow but uses refreshable Microsoft OAuth state
 * through MinecraftAuth and never persists Microsoft account passwords.</p>
 *
 * <p>The selected identity is still persisted for the next launch, but the title-screen account manager can
 * also apply it immediately. Unlike Sigma 5's mutable Session fields, the 26.2 port rebuilds the account-bound
 * client services so the visible name, multiplayer authentication, social state and signing keys stay coherent.</p>
 */
public final class SigmaAccountManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Accounts");
    private static final String USER_AGENT = "Sigma-Modern/26.2";
    private static final int FILE_VERSION = 1;

    private final Path file;
    private final List<AccountEntry> accounts = new ArrayList<>();
    private String selectedId;

    public SigmaAccountManager(final Path file) {
        this.file = file;
    }

    public synchronized void load() {
        this.accounts.clear();
        this.selectedId = null;

        JsonObject root = JsonFileUtil.read(this.file);
        if (root.has("selected") && !root.get("selected").isJsonNull()) {
            this.selectedId = root.get("selected").getAsString();
        }

        if (!root.has("accounts") || !root.get("accounts").isJsonArray()) {
            return;
        }

        for (JsonElement element : root.getAsJsonArray("accounts")) {
            if (!element.isJsonObject()) {
                continue;
            }

            try {
                AccountEntry account = AccountEntry.fromJson(element.getAsJsonObject());
                if (account != null) {
                    this.accounts.add(account);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Ignoring a malformed Sigma account entry", failure);
            }
        }

        if (this.selectedId != null && this.find(this.selectedId) == null) {
            this.selectedId = null;
        }
    }

    public synchronized void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        if (this.selectedId != null) {
            root.addProperty("selected", this.selectedId);
        }

        JsonArray array = new JsonArray();
        for (AccountEntry account : this.accounts) {
            array.add(account.toJson());
        }
        root.add("accounts", array);

        try {
            JsonFileUtil.write(this.file, root);
            this.restrictFilePermissions();
        } catch (IOException failure) {
            LOGGER.error("Could not save Sigma accounts to {}", this.file, failure);
        }
    }

    public synchronized List<AccountEntry> accounts() {
        return this.accounts.stream()
            .sorted(Comparator.comparingLong(AccountEntry::getDateAdded).reversed())
            .toList();
    }

    public synchronized Optional<AccountEntry> selected() {
        return Optional.ofNullable(this.find(this.selectedId));
    }

public synchronized String selectedId() {
    return this.selectedId;
}

/** Resolve/refresh credentials for a hot switch without committing the selection until the client accepts it. */
public LaunchIdentity resolveForUse(final String id) throws Exception {
    AccountEntry account;
    synchronized (this) {
        account = this.find(id);
    }
    if (account == null) {
        throw new IllegalArgumentException("Unknown Sigma account: " + id);
    }

    LaunchIdentity identity = this.resolveIdentity(account);
    synchronized (this) {
        if (!this.accounts.contains(account)) {
            throw new IllegalStateException("Account was removed while credentials were refreshing");
        }
        // Persist refreshed OAuth state even before switching, but leave selectedId untouched on failure.
        this.save();
    }
    return identity;
}

    public AccountEntry loginMicrosoft(final Consumer<MsaDeviceCode> deviceCodeConsumer) throws Exception {
        JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient(USER_AGENT))
            .login(DeviceCodeMsaAuthService::new, deviceCodeConsumer);

        // MinecraftAuth resolves lazily. Force the pieces the launcher needs before persisting the state.
        MinecraftToken token = authManager.getMinecraftToken().getUpToDate();
        MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
        if (token.getToken().isBlank()) {
            throw new IllegalStateException("Microsoft login returned an empty Minecraft access token");
        }

        AccountEntry incoming = AccountEntry.microsoft(profile, JavaAuthManager.toJson(authManager));
        synchronized (this) {
            AccountEntry existing = this.find(incoming.id);
            if (existing != null) {
                existing.updateMicrosoft(profile, incoming.authState);
                this.save();
                return existing;
            }

            this.accounts.add(incoming);
            this.save();
            return incoming;
        }
    }

    public synchronized AccountEntry addOffline(final String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Offline username must be 3-16 characters: A-Z, a-z, 0-9 or _");
        }

        UUID uuid = offlineUuid(name);
        String id = uuid.toString();
        AccountEntry existing = this.find(id);
        if (existing != null) {
            return existing;
        }

        AccountEntry account = AccountEntry.offline(name, uuid);
        this.accounts.add(account);
        this.save();
        return account;
    }

    public synchronized boolean selectForNextLaunch(final String id) {
        AccountEntry account = this.find(id);
        if (account == null) {
            return false;
        }

        this.selectedId = account.id;
        this.save();
        return true;
    }

    public synchronized void useLauncherIdentity() {
        this.selectedId = null;
        this.save();
    }

    public synchronized boolean remove(final String id) {
        boolean removed = this.accounts.removeIf(account -> account.id.equals(id));
        if (!removed) {
            return false;
        }

        if (id.equals(this.selectedId)) {
            this.selectedId = null;
        }
        this.save();
        return true;
    }

    /** Resolve and refresh the persisted account before Minecraft constructs any user-bound services. */
    public static Optional<LaunchIdentity> resolveSelectedForLaunch(final Path file) {
        SigmaAccountManager manager = new SigmaAccountManager(file);
        manager.load();
        return manager.resolveSelectedForLaunch();
    }

private Optional<LaunchIdentity> resolveSelectedForLaunch() {
    AccountEntry account;
    synchronized (this) {
        account = this.find(this.selectedId);
    }
    if (account == null) {
        return Optional.empty();
    }

    try {
        LaunchIdentity identity = this.resolveIdentity(account);
        synchronized (this) {
            account.lastUsed = System.currentTimeMillis();
            account.useCount++;
            this.save();
        }
        return Optional.of(identity);
    } catch (Exception failure) {
        LOGGER.error("Could not refresh selected Sigma account '{}'; falling back to launcher identity", account.name, failure);
        return Optional.empty();
    }
}

private LaunchIdentity resolveIdentity(final AccountEntry account) throws Exception {
    if (account.type == AccountType.OFFLINE) {
        return new LaunchIdentity(account.name, account.profileId, "0");
    }
    if (account.authState == null) {
        throw new IllegalStateException("Microsoft account has no OAuth state");
    }

    JavaAuthManager authManager = JavaAuthManager.fromJson(
        MinecraftAuth.createHttpClient(USER_AGENT), account.authState
    );
    MinecraftToken token = authManager.getMinecraftToken().getUpToDate();
    MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
    synchronized (this) {
        account.updateMicrosoft(profile, JavaAuthManager.toJson(authManager));
    }
    return new LaunchIdentity(profile.getName(), profile.getId(), token.getToken());
}

private AccountEntry find(final String id) {
        if (id == null) {
            return null;
        }
        for (AccountEntry account : this.accounts) {
            if (account.id.equals(id)) {
                return account;
            }
        }
        return null;
    }

    private void restrictFilePermissions() {
        try {
            if (Files.getFileStore(this.file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(this.file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException failure) {
            LOGGER.debug("Could not tighten account-file permissions for {}", this.file, failure);
        }
    }

    private static UUID offlineUuid(final String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public enum AccountType {
        MICROSOFT,
        OFFLINE
    }

    public record LaunchIdentity(String name, UUID profileId, String accessToken) {
    }

    public static final class AccountEntry {
        private final String id;
        private final AccountType type;
        private String name;
        private UUID profileId;
        private JsonObject authState;
        private final long dateAdded;
        private long lastUsed;
        private int useCount;

        private AccountEntry(
            final String id,
            final AccountType type,
            final String name,
            final UUID profileId,
            final JsonObject authState,
            final long dateAdded,
            final long lastUsed,
            final int useCount
        ) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.profileId = profileId;
            this.authState = authState;
            this.dateAdded = dateAdded;
            this.lastUsed = lastUsed;
            this.useCount = useCount;
        }

        private static AccountEntry microsoft(final MinecraftProfile profile, final JsonObject authState) {
            long now = System.currentTimeMillis();
            return new AccountEntry(
                profile.getId().toString(), AccountType.MICROSOFT, profile.getName(), profile.getId(), authState, now, 0L, 0
            );
        }

        private static AccountEntry offline(final String name, final UUID profileId) {
            long now = System.currentTimeMillis();
            return new AccountEntry(profileId.toString(), AccountType.OFFLINE, name, profileId, null, now, 0L, 0);
        }

        private static AccountEntry fromJson(final JsonObject json) {
            AccountType type = AccountType.valueOf(json.get("type").getAsString().toUpperCase(Locale.ROOT));
            String name = json.get("name").getAsString();
            UUID profileId = UUID.fromString(json.get("uuid").getAsString());
            String id = json.has("id") ? json.get("id").getAsString() : profileId.toString();
            JsonObject authState = json.has("auth") && json.get("auth").isJsonObject() ? json.getAsJsonObject("auth") : null;
            long dateAdded = json.has("dateAdded") ? json.get("dateAdded").getAsLong() : System.currentTimeMillis();
            long lastUsed = json.has("lastUsed") ? json.get("lastUsed").getAsLong() : 0L;
            int useCount = json.has("useCount") ? json.get("useCount").getAsInt() : 0;
            if (type == AccountType.MICROSOFT && authState == null) {
                return null;
            }
            return new AccountEntry(id, type, name, profileId, authState, dateAdded, lastUsed, useCount);
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", this.id);
            json.addProperty("type", this.type.name().toLowerCase(Locale.ROOT));
            json.addProperty("name", this.name);
            json.addProperty("uuid", this.profileId.toString());
            json.addProperty("dateAdded", this.dateAdded);
            json.addProperty("lastUsed", this.lastUsed);
            json.addProperty("useCount", this.useCount);
            if (this.authState != null) {
                json.add("auth", this.authState);
            }
            return json;
        }

        private void updateMicrosoft(final MinecraftProfile profile, final JsonObject authState) {
            this.name = profile.getName();
            this.profileId = profile.getId();
            this.authState = authState;
        }

        public String getId() {
            return this.id;
        }

        public AccountType getType() {
            return this.type;
        }

        public String getName() {
            return this.name;
        }

        public UUID getProfileId() {
            return this.profileId;
        }

        public long getDateAdded() {
            return this.dateAdded;
        }

        public long getLastUsed() {
            return this.lastUsed;
        }

        public int getUseCount() {
            return this.useCount;
        }
    }
}
