package com.mentalfrostbyte.jello.command;

import com.mentalfrostbyte.jello.module.ModuleManager;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Dependencies and local output sink passed to every {@link Command} execution.
 *
 * <p>Kept independent of {@code Minecraft.getInstance()} so commands and parsing logic can be exercised
 * directly in unit tests without a running game client.</p>
 */
public final class CommandContext {

    private static final String PREFIX = "§b[Sigma] §r";

    private final ModuleManager modules;
    private final CommandManager commands;
    private final Path profilesDirectory;
    private final Consumer<String> printer;
    private final Runnable saveConfig;
    private final BooleanSupplier chinese;

    public CommandContext(final ModuleManager modules, final CommandManager commands,
                          final Path profilesDirectory, final Consumer<String> printer,
                          final Runnable saveConfig) {
        this(modules, commands, profilesDirectory, printer, saveConfig, () -> false);
    }

    public CommandContext(final ModuleManager modules, final CommandManager commands,
                          final Path profilesDirectory, final Consumer<String> printer,
                          final Runnable saveConfig, final BooleanSupplier chinese) {
        this.modules = Objects.requireNonNull(modules, "modules");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.profilesDirectory = Objects.requireNonNull(profilesDirectory, "profilesDirectory");
        this.printer = Objects.requireNonNull(printer, "printer");
        this.saveConfig = Objects.requireNonNull(saveConfig, "saveConfig");
        this.chinese = Objects.requireNonNull(chinese, "chinese");
    }

    public ModuleManager modules() {
        return this.modules;
    }

    public CommandManager commands() {
        return this.commands;
    }

    public Path profilesDirectory() {
        return this.profilesDirectory;
    }

    public String prefix() {
        return this.commands.prefix();
    }

    public boolean isChinese() {
        return this.chinese.getAsBoolean();
    }

    /** Picks the Chinese or English copy matching the current client language. */
    public String t(final String en, final String zh) {
        return this.isChinese() ? zh : en;
    }

    public void saveConfig() {
        this.saveConfig.run();
    }

    /** Prints a standard formatted reply line to local chat. */
    public void reply(final String message) {
        this.printer.accept(PREFIX + message);
    }

    /** Prints a highlighted error line to local chat. */
    public void error(final String message) {
        this.printer.accept(PREFIX + "§c" + message);
    }

    /** Prints a raw line without the {@code [Sigma]} prefix (for multi-line listings). */
    public void raw(final String message) {
        this.printer.accept(message);
    }
}
