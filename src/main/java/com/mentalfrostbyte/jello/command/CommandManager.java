package com.mentalfrostbyte.jello.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.command.impl.BindCommand;
import com.mentalfrostbyte.jello.command.impl.ConfigCommand;
import com.mentalfrostbyte.jello.command.impl.HelpCommand;
import com.mentalfrostbyte.jello.command.impl.ModulesCommand;
import com.mentalfrostbyte.jello.command.impl.PrefixCommand;
import com.mentalfrostbyte.jello.command.impl.SayCommand;
import com.mentalfrostbyte.jello.command.impl.SettingCommand;
import com.mentalfrostbyte.jello.command.impl.SuspectsCommand;
import com.mentalfrostbyte.jello.command.impl.ToggleCommand;
import com.mentalfrostbyte.jello.command.impl.VClipCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry, tokenizer and dispatcher for local client commands (default prefix {@code "."}).
 *
 * <p>Intercepted in {@code ClientPacketListener#sendChat} before a chat line is signed or sent to the
 * server, so mistyped client commands are answered locally instead of leaking into public chat.</p>
 */
public final class CommandManager {

    public static final String DEFAULT_PREFIX = ".";
    private static final String CONFIG_KEY = "commandPrefix";

    private final Map<String, Command> canonical = new LinkedHashMap<>();
    private final Map<String, Command> lookup = new LinkedHashMap<>();
    private String prefix = DEFAULT_PREFIX;
    private boolean defaultsRegistered;

    public void registerDefaults() {
        if (this.defaultsRegistered) {
            return;
        }
        this.defaultsRegistered = true;
        this.register(new HelpCommand());
        this.register(new ToggleCommand());
        this.register(new BindCommand());
        this.register(new SettingCommand());
        this.register(new ModulesCommand());
        this.register(new ConfigCommand());
        this.register(new PrefixCommand());
        this.register(new SuspectsCommand());
        this.register(new VClipCommand());
        this.register(new SayCommand());
    }

    public void register(final Command command) {
        Objects.requireNonNull(command, "command");
        String key = command.name().toLowerCase(Locale.ROOT);
        if (this.lookup.containsKey(key)) {
            throw new IllegalStateException("Command '" + key + "' is already registered");
        }
        for (String alias : command.aliases()) {
            String aliasKey = alias.toLowerCase(Locale.ROOT);
            if (this.lookup.containsKey(aliasKey)) {
                throw new IllegalStateException("Command alias '" + aliasKey + "' is already registered");
            }
        }

        this.canonical.put(key, command);
        this.lookup.put(key, command);
        for (String alias : command.aliases()) {
            this.lookup.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    public Collection<Command> all() {
        return Collections.unmodifiableCollection(this.canonical.values());
    }

    public Optional<Command> find(final String nameOrAlias) {
        if (nameOrAlias == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.lookup.get(nameOrAlias.toLowerCase(Locale.ROOT)));
    }

    public String prefix() {
        return this.prefix;
    }

    public void setPrefix(final String newPrefix) {
        if (!isValidPrefix(newPrefix)) {
            throw new IllegalArgumentException("Invalid command prefix: " + newPrefix);
        }
        this.prefix = newPrefix;
    }

    public static boolean isValidPrefix(final String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > 2) {
            return false;
        }
        if (candidate.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isWhitespace(candidate.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public void read(final JsonObject root) {
        if (root == null || !root.has(CONFIG_KEY)) {
            return;
        }
        JsonElement element = root.get(CONFIG_KEY);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            if (isValidPrefix(value)) {
                this.prefix = value;
            }
        }
    }

    public void write(final JsonObject root) {
        if (root != null) {
            root.addProperty(CONFIG_KEY, this.prefix);
        }
    }

    /**
     * Checks whether {@code rawMessage} is a client command and runs it if so.
     *
     * @return {@code true} if the line was handled locally and must NOT be sent to the server
     */
    public boolean interceptChat(final String rawMessage, final CommandContext ctx) {
        if (rawMessage == null || !rawMessage.startsWith(this.prefix)) {
            return false;
        }

        String body = rawMessage.substring(this.prefix.length()).trim();
        if (body.isEmpty()) {
            return false;
        }

        List<String> tokens = tokenize(body);
        if (tokens.isEmpty()) {
            return false;
        }

        String commandName = tokens.get(0);
        Optional<Command> command = this.find(commandName);
        if (command.isEmpty()) {
            ctx.error(ctx.t(
                    "Unknown command '" + commandName + "'. Try " + this.prefix + "help",
                    "未知命令 '" + commandName + "'，输入 " + this.prefix + "help 查看可用命令。"));
            return true;
        }

        String[] args = tokens.subList(1, tokens.size()).toArray(String[]::new);
        try {
            command.get().execute(ctx, args);
        } catch (final RuntimeException failure) {
            ctx.error(ctx.t("Command failed: ", "命令执行失败：") + failure.getMessage());
        }
        return true;
    }

    /**
     * Returns tab-completion suggestions for a partial command input (including the leading prefix).
     */
    public List<String> complete(final String input, final CommandContext ctx) {
        if (input == null || !input.startsWith(this.prefix)) {
            return List.of();
        }
        String body = input.substring(this.prefix.length());
        boolean trailingSpace = body.endsWith(" ");
        List<String> tokens = tokenize(body.trim());

        if (tokens.isEmpty() || (tokens.size() == 1 && !trailingSpace)) {
            String partial = tokens.isEmpty() ? "" : tokens.get(0).toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String name : this.canonical.keySet()) {
                if (name.startsWith(partial)) {
                    matches.add(this.prefix + name);
                }
            }
            return matches;
        }

        Optional<Command> command = this.find(tokens.get(0));
        if (command.isEmpty()) {
            return List.of();
        }

        List<String> argList = new ArrayList<>(tokens.subList(1, tokens.size()));
        if (trailingSpace) {
            argList.add("");
        }
        return command.get().suggest(ctx, argList.toArray(String[]::new));
    }

    /**
     * Splits a command line on whitespace while preserving double-quoted segments (e.g. {@code "Alert Level"}).
     */
    public static List<String> tokenize(final String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(ch) && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
