package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.command.CommandManager;

/** Displays or changes the client command prefix. */
public final class PrefixCommand implements Command {

    @Override
    public String name() {
        return "prefix";
    }

    @Override
    public String usage() {
        return "[symbol]";
    }

    @Override
    public String description() {
        return "Shows or changes the chat command prefix (default '.').";
    }

    @Override
    public String descriptionZh() {
        return "查看或修改聊天命令前缀（默认为 '.'）。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length == 0) {
            ctx.reply(ctx.t(
                    "§7Current command prefix: §f" + ctx.prefix(),
                    "§7当前命令前缀：§f" + ctx.prefix()));
            return;
        }

        String candidate = args[0];
        if (!CommandManager.isValidPrefix(candidate)) {
            ctx.error(ctx.t(
                    "Prefix must be 1-2 non-whitespace characters and cannot start with '/'.",
                    "前缀必须为 1–2 个非空白字符，且不能以 '/' 开头。"));
            return;
        }

        ctx.commands().setPrefix(candidate);
        ctx.saveConfig();
        ctx.reply(ctx.t(
                "§7Command prefix changed to §f" + candidate,
                "§7命令前缀已修改为 §f" + candidate));
    }
}
