package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.command.CloudCommandService;
import ac.grim.grimac.platform.api.PlatformLoader;
import ac.grim.grimac.platform.api.PlatformPlugin;
import ac.grim.grimac.platform.api.PlatformServer;
import ac.grim.grimac.platform.api.command.CommandService;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.ItemResetHandler;
import ac.grim.grimac.platform.api.manager.MessagePlaceHolderManager;
import ac.grim.grimac.platform.api.manager.PermissionRegistrationManager;
import ac.grim.grimac.platform.api.manager.PlatformPluginManager;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.sender.SenderFactory;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jspecify.annotations.Nullable;

/**
 * Grim's platform, as SelfDetection provides it for one connection: what a Bukkit or Fabric server would provide,
 * minus everything that would act on a server or on the player.
 */
final class SigmaGrimLoader implements PlatformLoader {

    static final String PLUGIN_NAME = "GrimAC";

    private final PacketEventsAPI<?> packetEvents;
    private final SigmaScheduler scheduler;
    private final SigmaPlayers.Factory players;
    private final SigmaSenders senders;
    private final GrimPlugin plugin;
    private final Server server = new Server();
    private final CommandManager<Sender> commandManager;
    private final CommandService commandService;

    SigmaGrimLoader(final EngineContext context, final PacketEventsAPI<?> packetEvents, final SigmaScheduler scheduler,
                    final SigmaPlayers.Factory players, final SigmaSenders senders) {
        this.packetEvents = packetEvents;
        this.scheduler = scheduler;
        this.players = players;
        this.senders = senders;
        this.plugin = new BasicGrimPlugin(consoleLogger(context), context.dataFolder().toFile(), grimVersion(),
                "GrimAC embedded in the Sigma client by SelfDetection", List.of("GrimAC contributors"));
        GrimAPI.INSTANCE.getExtensionManager().registerResolver(key ->
                key == this.plugin || PLUGIN_NAME.equalsIgnoreCase(String.valueOf(key)) ? this.plugin : null);

        this.commandManager = new CommandManager<>(ExecutionCoordinator.simpleCoordinator(),
                CommandRegistrationHandler.nullCommandRegistrationHandler()) {
            @Override
            public boolean hasPermission(final Sender sender, final String permission) {
                return permission.isEmpty() || sender.hasPermission(permission);
            }
        };
        this.commandService = new CloudCommandService(() -> this.commandManager, new Arguments());
    }

    /** Runs {@code .grim <arguments>} for the local player (or the console, before anyone has logged in). */
    void execute(final Sender sender, final String input) {
        this.commandManager.commandExecutor().executeCommand(sender, input).whenComplete((result, failure) -> {
            if (failure == null) {
                return;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            sender.sendMessage(Component.text(message, NamedTextColor.RED));
        });
    }

    /** Grim's version string, from the grimac.properties it ships with. */
    private static String grimVersion() {
        try (InputStream in = SigmaGrimLoader.class.getClassLoader().getResourceAsStream("grimac.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "unknown";
    }

    /** Grim's log goes to the self-check console, like a server plugin's goes to the server console. */
    private static Logger consoleLogger(final EngineContext context) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        logger.addHandler(new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (!this.isLoggable(record)) {
                    return;
                }
                String line = "[" + record.getLevel().getName() + "] " + record.getMessage();
                if (record.getThrown() != null) {
                    line += " (" + record.getThrown() + ")";
                }
                context.log(line);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @Override
    public SigmaScheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    public SigmaPlayers.Factory getPlatformPlayerFactory() {
        return this.players;
    }

    @Override
    public PacketEventsAPI<?> getPacketEvents() {
        return this.packetEvents;
    }

    @Override
    public ItemResetHandler getItemResetHandler() {
        return ITEM_RESET;
    }

    @Override
    public CommandService getCommandService() {
        return this.commandService;
    }

    @Override
    public SenderFactory<?> getSenderFactory() {
        return this.senders;
    }

    @Override
    public GrimPlugin getPlugin() {
        return this.plugin;
    }

    @Override
    public PlatformPluginManager getPluginManager() {
        return NO_PLUGINS;
    }

    @Override
    public PlatformServer getPlatformServer() {
        return this.server;
    }

    @Override
    public void registerAPIService() {
        GrimAPIProvider.init(GrimAPI.INSTANCE.getExternalAPI());
    }

    @Override
    public MessagePlaceHolderManager getMessagePlaceHolderManager() {
        return (player, message) -> message;
    }

    @Override
    public PermissionRegistrationManager getPermissionManager() {
        return (permission, defaultValue) -> {
        };
    }

    /** The "server": commands a punishment would run are reported, never run. */
    private final class Server implements PlatformServer {

        @Override
        public String getPlatformImplementationString() {
            return "Sigma SelfDetection";
        }

        @Override
        public void dispatchCommand(final Sender sender, final String command) {
            EngineContext context = SigmaGrim.context();
            if (context != null) {
                context.verdicts().punishment(command);
            }
        }

        @Override
        public Sender getConsoleSender() {
            return SigmaGrimLoader.this.senders.console();
        }

        @Override
        public void registerOutgoingPluginChannel(final String name) {
        }

        @Override
        public double getTPS() {
            return 20.0;
        }
    }

    /** No other server plugins: every integration Grim looks for (ViaBackwards, TAB, LuckPerms ...) is absent. */
    private static final PlatformPluginManager NO_PLUGINS = new PlatformPluginManager() {
        @Override
        public PlatformPlugin[] getPlugins() {
            return new PlatformPlugin[0];
        }

        @Override
        public @Nullable PlatformPlugin getPlugin(final String name) {
            return null;
        }
    };

    /** Item usage lives in Grim's packet state; there is no server-side item usage to reset. */
    private static final ItemResetHandler ITEM_RESET = new ItemResetHandler() {
        @Override
        public void resetItemUsage(final PlatformPlayer player) {
        }

        @Override
        public @Nullable InteractionHand getItemUsageHand(final PlatformPlayer player) {
            return null;
        }

        @Override
        public boolean isUsingItem(final PlatformPlayer player) {
            return false;
        }
    };

    /** Commands that take a player only ever take the local player. */
    private final class Arguments implements CloudPlatformCommandArguments {

        @Override
        public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
            ArgumentParser<Sender, PlayerSelector> parser = (final CommandContext<Sender> context, final CommandInput input) -> {
                String name = input.readString();
                PlatformPlayer player = SigmaGrimLoader.this.players.getFromName(name);
                if (player == null) {
                    return ArgumentParseResult.failure(new IllegalArgumentException("Only you are checked here, not " + name + "."));
                }
                return ArgumentParseResult.success(new Selector(player.getSender(), name));
            };
            return ParserDescriptor.of(parser, PlayerSelector.class);
        }

        @Override
        public SuggestionProvider<Sender> onlinePlayerSuggestions() {
            return SuggestionProvider.blockingStrings((context, input) ->
                    SigmaGrimLoader.this.players.getOnlinePlayers().stream().map(PlatformPlayer::getName).toList());
        }
    }

    private record Selector(Sender player, String inputString) implements PlayerSelector {

        @Override
        public boolean isSingle() {
            return true;
        }

        @Override
        public Sender getSinglePlayer() {
            return this.player;
        }

        @Override
        public Collection<Sender> getPlayers() {
            return List.of(this.player);
        }
    }
}
