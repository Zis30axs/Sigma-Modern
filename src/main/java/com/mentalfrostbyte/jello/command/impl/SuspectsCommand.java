package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.anticheat.alert.Suspects;
import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Inspects or clears players flagged by {@link ModuleAntiCheat}. */
public final class SuspectsCommand implements Command {

    @Override
    public String name() {
        return "suspects";
    }

    @Override
    public List<String> aliases() {
        return List.of("ac", "anticheat");
    }

    @Override
    public String usage() {
        return "[list|clear|forget <player>]";
    }

    @Override
    public String description() {
        return "Lists, forgets or clears players flagged by AntiCheat.";
    }

    @Override
    public String descriptionZh() {
        return "查看、忽略单个或清空 AntiCheat 标记的可疑玩家。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        ModuleAntiCheat antiCheat;
        try {
            antiCheat = ctx.modules().get(ModuleAntiCheat.class);
        } catch (final IllegalStateException missing) {
            ctx.error(ctx.t("AntiCheat module is not registered.", "AntiCheat 模块未注册。"));
            return;
        }

        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            List<Suspects.Entry> ranked = antiCheat.suspects().ranked();
            if (ranked.isEmpty()) {
                ctx.reply(antiCheat.isEnabled()
                        ? ctx.t("§7No suspects flagged yet.", "§7暂无被标记的可疑玩家。")
                        : ctx.t("§7No suspects flagged (§eAntiCheat is currently OFF§7).",
                                "§7暂无可疑玩家（§eAntiCheat 当前未开启§7）。"));
                return;
            }
            ctx.reply(ctx.t(
                    "§7Flagged suspects (§f" + ranked.size() + "§7):",
                    "§7已标记的可疑玩家（§f" + ranked.size() + "§7）："));
            for (Suspects.Entry entry : ranked) {
                String breakdown = entry.levels().entrySet().stream()
                        .map(e -> String.format(Locale.ROOT, "%s %.1f", e.getKey(), e.getValue()))
                        .collect(Collectors.joining(", "));
                ctx.raw(String.format(Locale.ROOT, "  §f%s §cVL %.1f §8[%s]",
                        entry.name(), entry.total(), breakdown));
            }
            return;
        }

        if ("clear".equalsIgnoreCase(args[0])) {
            antiCheat.forgetAll();
            ctx.reply(ctx.t("§7Cleared all AntiCheat suspects.", "§7已清空所有 AntiCheat 可疑玩家记录。"));
            return;
        }

        if ("forget".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " forget <player>");
                return;
            }
            String target = args[1];
            for (Suspects.Entry entry : antiCheat.suspects().ranked()) {
                if (entry.name().equalsIgnoreCase(target)) {
                    antiCheat.forget(entry.uuid());
                    ctx.reply(ctx.t(
                            "§7Forgot suspect §f" + entry.name() + "§7.",
                            "§7已移除可疑玩家 §f" + entry.name() + "§7。"));
                    return;
                }
            }
            ctx.error(ctx.t("No suspect named '" + target + "'.", "名单中未找到玩家 '" + target + "'。"));
            return;
        }

        ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "clear", "forget").stream().filter(s -> s.startsWith(partial)).toList();
        }
        return List.of();
    }
}
