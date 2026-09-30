package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.account.SigmaAccountManager;
import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountEntry;
import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountType;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.util.Util;

/**
 * What the Jello and Classic alt managers do to accounts, apart from how they look.
 *
 * <p>The old managers stored e-mail and password pairs and logged in with them. Sigma-Modern's
 * {@link SigmaAccountManager} deliberately does not: accounts are a Microsoft device-code sign-in or an offline
 * name, and switching hands a fresh session to the client. The screens keep the old workflow - add, click to
 * select, click again to log in, delete, sort, search - on top of that, and this is the shared part: the
 * network work runs on daemon threads and everything that touches the screen comes back through
 * {@link Minecraft#execute}.</p>
 */
public final class AccountOps {

    public enum Sort {
        DATE_ADDED("Date Added"), ALPHABETICAL("Alphabetical"), LAST_USED("Last Used"), USE_COUNT("Use count");

        public final String label;

        Sort(final String label) {
            this.label = label;
        }
    }

    private final SigmaAccountManager accounts = Client.getInstance().getAccountManager();
    private final Minecraft mc = Minecraft.getInstance();
    private final Runnable changed;

    private volatile String status = "";
    private volatile String deviceCode = "";
    private volatile boolean signingIn;
    private volatile String switchingId;
    private volatile String failedId;
    private volatile long failedAtMillis;

    /** {@code changed} runs on the render thread whenever the list or a status the screen shows has changed. */
    public AccountOps(final Runnable changed) {
        this.changed = changed;
    }

    public SigmaAccountManager manager() {
        return this.accounts;
    }

    public String status() {
        return this.status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    /** The code the Microsoft sign-in is waiting for, or {@code ""}. */
    public String deviceCode() {
        return this.deviceCode;
    }

    public boolean signingIn() {
        return this.signingIn;
    }

    /** The id of the account a login is running for, or {@code null}. */
    public String switchingId() {
        return this.switchingId;
    }

    public boolean busy() {
        return this.signingIn || this.switchingId != null;
    }

    /** Whether {@code account}'s login just failed (for the shake/flash the old rows showed). */
    public boolean failedRecently(final AccountEntry account) {
        return account.getId().equals(this.failedId) && System.currentTimeMillis() - this.failedAtMillis < 2500L;
    }

    public long failedAtMillis() {
        return this.failedAtMillis;
    }

    /** Whether the client is currently playing as {@code account}. */
    public boolean isCurrent(final AccountEntry account) {
        return account.getProfileId().equals(this.mc.getUser().getProfileId());
    }

    public List<AccountEntry> list(final Sort sort, final String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Comparator<AccountEntry> order = switch (sort) {
            case ALPHABETICAL -> Comparator.comparing((AccountEntry a) -> a.getName().toLowerCase(Locale.ROOT));
            case LAST_USED -> Comparator.comparingLong(AccountEntry::getLastUsed).reversed();
            case USE_COUNT -> Comparator.comparingInt(AccountEntry::getUseCount).reversed();
            case DATE_ADDED -> Comparator.comparingLong(AccountEntry::getDateAdded).reversed();
        };
        return this.accounts.accounts().stream()
            .filter(a -> needle.isEmpty() || a.getName().toLowerCase(Locale.ROOT).contains(needle)
                || a.getType().name().toLowerCase(Locale.ROOT).contains(needle))
            .sorted(order)
            .toList();
    }

    public Optional<AccountEntry> find(final String id) {
        return this.accounts.accounts().stream().filter(a -> a.getId().equals(id)).findFirst();
    }

    /** Starts a Microsoft device-code sign-in; the browser opens and the code is copied. */
    public void microsoftLogin(final Consumer<AccountEntry> added) {
        if (this.busy()) {
            return;
        }
        this.signingIn = true;
        this.deviceCode = "";
        this.status = "Starting Microsoft login...";
        this.changed.run();

        Thread thread = new Thread(() -> {
            try {
                AccountEntry account = this.accounts.loginMicrosoft(code -> {
                    this.deviceCode = code.getUserCode();
                    this.status = "Finish the Microsoft login in your browser.";
                    this.mc.execute(() -> {
                        this.mc.keyboardHandler.setClipboard(code.getUserCode());
                        Util.getPlatform().openUri(code.getDirectVerificationUri());
                        this.changed.run();
                    });
                });
                this.mc.execute(() -> {
                    this.signingIn = false;
                    this.deviceCode = "";
                    this.status = "Added " + account.getName() + ".";
                    added.accept(account);
                    this.changed.run();
                });
            } catch (Exception failure) {
                Client.logger.error("Microsoft account login failed", failure);
                this.mc.execute(() -> {
                    this.signingIn = false;
                    this.deviceCode = "";
                    this.status = "Microsoft login failed: " + concise(failure);
                    this.changed.run();
                });
            }
        }, "Sigma-Microsoft-Login");
        thread.setDaemon(true);
        thread.start();
    }

    /** Adds an offline account; throws {@link IllegalArgumentException} with the reason for a bad name. */
    public AccountEntry addOffline(final String name) {
        AccountEntry account = this.accounts.addOffline(name);
        this.status = "Added offline account " + account.getName() + ".";
        this.changed.run();
        return account;
    }

    /** Logs in as {@code account} now (title screen only): the old "double click to connect". */
    public void use(final AccountEntry account, final Runnable done) {
        if (this.busy()) {
            return;
        }
        if (this.mc.level != null || this.mc.player != null || this.mc.getConnection() != null) {
            this.fail(account, "Switch accounts from the title screen.");
            return;
        }
        this.switchingId = account.getId();
        this.status = "Logging in...";
        this.changed.run();

        Thread thread = new Thread(() -> {
            try {
                SigmaAccountManager.LaunchIdentity identity = this.accounts.resolveForUse(account.getId());
                User user = new User(identity.name(), identity.profileId(), identity.accessToken(), Optional.empty(), Optional.empty());
                this.mc.sigmaSwitchUser(user, account.getType() == AccountType.OFFLINE).whenComplete((switched, failure) ->
                    this.mc.execute(() -> {
                        this.switchingId = null;
                        if (failure != null) {
                            Client.logger.error("Sigma hot account switch failed", failure);
                            this.fail(account, "Login failed: " + concise(failure));
                        } else if (!switched) {
                            this.fail(account, "Return to the title screen before switching accounts.");
                        } else {
                            this.accounts.selectForNextLaunch(account.getId());
                            this.status = "Logged in. (" + identity.name() + ")";
                            this.changed.run();
                            if (done != null) {
                                done.run();
                            }
                        }
                    })
                );
            } catch (Throwable failure) {
                Client.logger.error("Sigma account refresh failed", failure);
                this.mc.execute(() -> {
                    this.switchingId = null;
                    this.fail(account, "Login failed: " + concise(failure));
                });
            }
        }, "Sigma-Account-Switch");
        thread.setDaemon(true);
        thread.start();
    }

    public void delete(final AccountEntry account) {
        if (this.busy()) {
            return;
        }
        String name = account.getName();
        if (this.accounts.remove(account.getId())) {
            this.status = "Deleted " + name + ".";
            this.changed.run();
        }
    }

    public void useLauncherIdentity() {
        if (this.busy()) {
            return;
        }
        this.accounts.useLauncherIdentity();
        this.status = "The launcher's account will be used on the next launch.";
        this.changed.run();
    }

    private void fail(final AccountEntry account, final String message) {
        this.failedId = account.getId();
        this.failedAtMillis = System.currentTimeMillis();
        this.status = message;
        this.changed.run();
    }

    private static String concise(final Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() > 96 ? message.substring(0, 93) + "..." : message;
    }
}
