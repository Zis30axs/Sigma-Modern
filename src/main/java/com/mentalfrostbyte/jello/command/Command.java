package com.mentalfrostbyte.jello.command;

import java.util.List;

/**
 * One client-side chat command handled locally before a message is signed or sent to the server.
 */
public interface Command {

    /** Canonical lowercase command name (without prefix), e.g. {@code "toggle"}. */
    String name();

    /** Optional shorthand aliases (without prefix), e.g. {@code ["t"]}. */
    default List<String> aliases() {
        return List.of();
    }

    /** Argument syntax shown in {@code .help}, without the leading prefix or command name. */
    String usage();

    /** Short one-line English summary shown in {@code .help}. */
    String description();

    /** Optional Chinese summary shown when the client language is {@code zh_cn}. */
    default String descriptionZh() {
        return this.description();
    }

    /** Returns the localized summary for {@code ctx}. */
    default String localizedDescription(final CommandContext ctx) {
        return ctx.isChinese() ? this.descriptionZh() : this.description();
    }

    /** Executes the command with tokenized arguments (excluding the command name itself). */
    void execute(CommandContext ctx, String[] args);

    /** Returns candidate completions for the last token in {@code args}. */
    default List<String> suggest(final CommandContext ctx, final String[] args) {
        return List.of();
    }
}
