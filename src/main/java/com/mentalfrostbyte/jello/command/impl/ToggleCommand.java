package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.module.Module;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Toggles or explicitly enables/disables a module. */
public final class ToggleCommand implements Command {

    @Override
    public String name() {
        return "toggle";
    }

    @Override
    public List<String> aliases() {
        return List.of("t");
    }

    @Override
    public String usage() {
        return "<module> [on|off]";
    }

    @Override
    public String description() {
        return "Switches a module on or off.";
    }

    @Override
    public String descriptionZh() {
        return "开启、关闭或切换指定模块。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length < 1) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        Optional<Module> found = ctx.modules().find(args[0]);
        if (found.isEmpty()) {
            ctx.error(ctx.t("No module named '" + args[0] + "'.", "未找到模块 '" + args[0] + "'。"));
            return;
        }

        Module module = found.get();
        if (args.length >= 2) {
            String state = args[1].toLowerCase(Locale.ROOT);
            if ("on".equals(state) || "true".equals(state) || "enable".equals(state)) {
                module.setEnabled(true);
            } else if ("off".equals(state) || "false".equals(state) || "disable".equals(state)) {
                module.setEnabled(false);
            } else {
                ctx.error(ctx.t("Expected 'on' or 'off', got '" + args[1] + "'.",
                        "参数应为 on 或 off，当前输入为 '" + args[1] + "'。"));
                return;
            }
        } else {
            module.toggle();
        }

        ctx.saveConfig();
        ctx.reply(ctx.t(
                "§f" + module.getName() + " §7is now " + (module.isEnabled() ? "§aON" : "§cOFF") + "§7.",
                "§f" + module.getName() + " §7已" + (module.isEnabled() ? "§a开启" : "§c关闭") + "§7。"));
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return ctx.modules().all().stream()
                    .map(Module::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }
        if (args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream().filter(s -> s.startsWith(partial)).toList();
        }
        return List.of();
    }
}
