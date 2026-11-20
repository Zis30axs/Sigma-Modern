package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Lists registered modules, optionally filtered by category or enabled state. */
public final class ModulesCommand implements Command {

    @Override
    public String name() {
        return "modules";
    }

    @Override
    public List<String> aliases() {
        return List.of("mods", "list");
    }

    @Override
    public String usage() {
        return "[category|enabled]";
    }

    @Override
    public String description() {
        return "Lists all modules, enabled modules, or modules in one category.";
    }

    @Override
    public String descriptionZh() {
        return "列出全部模块、已开启模块，或按分类筛选模块。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        List<Module> list;
        String header;

        if (args.length == 0) {
            list = List.copyOf(ctx.modules().all());
            header = ctx.t("All modules", "全部模块");
        } else if ("enabled".equalsIgnoreCase(args[0]) || "on".equalsIgnoreCase(args[0])) {
            list = ctx.modules().all().stream().filter(Module::isEnabled).toList();
            header = ctx.t("Enabled modules", "已开启模块");
        } else {
            ModuleCategory category = parseCategory(args[0]);
            if (category == null) {
                ctx.error(ctx.t("Unknown category '" + args[0] + "'.", "未知分类 '" + args[0] + "'。"));
                return;
            }
            list = ctx.modules().byCategory(category);
            header = ctx.t(category.name() + " modules", category.name() + " 分类模块");
        }

        if (list.isEmpty()) {
            ctx.reply(ctx.t("§7" + header + ": none.", "§7" + header + "：无。"));
            return;
        }

        String formatted = list.stream()
                .map(m -> (m.isEnabled() ? "§a" : "§7") + m.getName())
                .collect(Collectors.joining("§8, "));
        ctx.reply(ctx.t(
                "§7" + header + " (§f" + list.size() + "§7): " + formatted,
                "§7" + header + "（§f" + list.size() + "§7）：" + formatted));
    }

    private static ModuleCategory parseCategory(final String raw) {
        for (ModuleCategory category : ModuleCategory.values()) {
            if (category.name().equalsIgnoreCase(raw)) {
                return category;
            }
        }
        return null;
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new java.util.ArrayList<>();
        if ("enabled".startsWith(partial)) {
            out.add("enabled");
        }
        for (ModuleCategory category : ModuleCategory.values()) {
            String lower = category.name().toLowerCase(Locale.ROOT);
            if (lower.startsWith(partial)) {
                out.add(lower);
            }
        }
        return out;
    }
}
