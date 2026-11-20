package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Lists all client commands or prints usage details for a single command. */
public final class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public List<String> aliases() {
        return List.of("h", "?");
    }

    @Override
    public String usage() {
        return "[command]";
    }

    @Override
    public String description() {
        return "Lists client commands or shows details for one command.";
    }

    @Override
    public String descriptionZh() {
        return "列出所有客户端命令，或查看单条命令的详细用法。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        String p = ctx.prefix();
        if (args.length == 0) {
            ctx.reply(ctx.t(
                    "§7Available commands (§f" + ctx.commands().all().size() + "§7):",
                    "§7可用命令（§f" + ctx.commands().all().size() + "§7）："));
            for (Command command : ctx.commands().all()) {
                String usage = command.usage().isEmpty() ? "" : " " + command.usage();
                ctx.raw("  §b" + p + command.name() + "§7" + usage + " §8- §f" + command.localizedDescription(ctx));
            }
            return;
        }

        Optional<Command> found = ctx.commands().find(args[0]);
        if (found.isEmpty()) {
            ctx.error(ctx.t("Unknown command '" + args[0] + "'.", "未知命令 '" + args[0] + "'。"));
            return;
        }

        Command cmd = found.get();
        String usage = cmd.usage().isEmpty() ? "" : " " + cmd.usage();
        ctx.reply("§f" + p + cmd.name() + "§7" + usage);
        ctx.raw("  §7" + cmd.localizedDescription(ctx));
        if (!cmd.aliases().isEmpty()) {
            ctx.raw("  §8" + ctx.t("Aliases: ", "别名：") + "§7" + String.join(", ", cmd.aliases()));
        }
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        return ctx.commands().all().stream()
                .map(Command::name)
                .filter(n -> n.startsWith(partial))
                .toList();
    }
}
