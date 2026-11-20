package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.module.BindMode;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Binds or unbinds a module keybind, or lists all active binds. */
public final class BindCommand implements Command {

    @Override
    public String name() {
        return "bind";
    }

    @Override
    public List<String> aliases() {
        return List.of("b");
    }

    @Override
    public String usage() {
        return "<module> <key|none> [toggle|hold] | list";
    }

    @Override
    public String description() {
        return "Binds a module to a key, clears a bind, or lists all binds.";
    }

    @Override
    public String descriptionZh() {
        return "为模块绑定按键、清除按键绑定，或列出所有已绑定模块。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length == 0) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        if ("list".equalsIgnoreCase(args[0])) {
            List<Module> bound = ctx.modules().all().stream()
                    .filter(m -> m.getKeybind().isBound())
                    .toList();
            if (bound.isEmpty()) {
                ctx.reply(ctx.t("§7No modules are currently bound.", "§7当前没有已绑定按键的模块。"));
                return;
            }
            ctx.reply(ctx.t(
                    "§7Bound modules (§f" + bound.size() + "§7):",
                    "§7已绑定按键的模块（§f" + bound.size() + "§7）："));
            for (Module module : bound) {
                Keybind bind = module.getKeybind();
                ctx.raw("  §f" + module.getName() + " §8-> §b" + shortKeyName(bind.key())
                        + " §7(" + modeLabel(ctx, bind.mode()) + ")");
            }
            return;
        }

        if (args.length < 2) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        Optional<Module> found = ctx.modules().find(args[0]);
        if (found.isEmpty()) {
            ctx.error(ctx.t("No module named '" + args[0] + "'.", "未找到模块 '" + args[0] + "'。"));
            return;
        }
        Module module = found.get();

        String rawKey = args[1].toLowerCase(Locale.ROOT);
        if ("none".equals(rawKey) || "clear".equals(rawKey) || "unbind".equals(rawKey)) {
            module.setKeybind(Keybind.UNBOUND);
            ctx.saveConfig();
            ctx.reply(ctx.t(
                    "§7Unbound §f" + module.getName() + "§7.",
                    "§7已清除 §f" + module.getName() + " §7的按键绑定。"));
            return;
        }

        Optional<InputConstants.Key> key = resolveKey(rawKey);
        if (key.isEmpty()) {
            ctx.error(ctx.t(
                    "Unknown key '" + args[1] + "'. Try names like r, f, lshift, rshift, mouse4 or none.",
                    "未知按键 '" + args[1] + "'，可尝试 r、f、lshift、rshift、mouse4 或 none。"));
            return;
        }

        BindMode mode = module.getKeybind().mode();
        if (args.length >= 3) {
            String rawMode = args[2].toLowerCase(Locale.ROOT);
            if ("toggle".equals(rawMode)) {
                mode = BindMode.TOGGLE;
            } else if ("hold".equals(rawMode)) {
                mode = BindMode.HOLD;
            } else {
                ctx.error(ctx.t(
                        "Unknown bind mode '" + args[2] + "'. Use 'toggle' or 'hold'.",
                        "未知绑定模式 '" + args[2] + "'，请使用 toggle 或 hold。"));
                return;
            }
        }

        module.setKeybind(new Keybind(key.get(), mode));
        ctx.saveConfig();
        ctx.reply(ctx.t(
                "§7Bound §f" + module.getName() + " §7to §b" + shortKeyName(key.get()) + " §7(" + modeLabel(ctx, mode) + ").",
                "§7已将 §f" + module.getName() + " §7绑定至 §b" + shortKeyName(key.get()) + " §7(" + modeLabel(ctx, mode) + ")。"));
    }

    private static String modeLabel(final CommandContext ctx, final BindMode mode) {
        return mode == BindMode.HOLD ? ctx.t("hold", "按住") : ctx.t("toggle", "切换");
    }

    static Optional<InputConstants.Key> resolveKey(final String raw) {
        String normalized = switch (raw) {
            case "lshift" -> "key.keyboard.left.shift";
            case "rshift" -> "key.keyboard.right.shift";
            case "lctrl", "lcontrol" -> "key.keyboard.left.control";
            case "rctrl", "rcontrol" -> "key.keyboard.right.control";
            case "lalt" -> "key.keyboard.left.alt";
            case "ralt" -> "key.keyboard.right.alt";
            case "esc" -> "key.keyboard.escape";
            case "mb3", "mouse3", "middle" -> "key.mouse.middle";
            case "mb4", "mouse4" -> "key.mouse.4";
            case "mb5", "mouse5" -> "key.mouse.5";
            default -> raw.startsWith("key.") ? raw : "key.keyboard." + raw;
        };
        try {
            return Optional.of(InputConstants.getKey(normalized));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String shortKeyName(final InputConstants.Key key) {
        String name = key.getName();
        if (name.startsWith("key.keyboard.")) {
            return name.substring("key.keyboard.".length()).toUpperCase(Locale.ROOT);
        }
        if (name.startsWith("key.mouse.")) {
            return "MOUSE_" + name.substring("key.mouse.".length()).toUpperCase(Locale.ROOT);
        }
        return name;
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new java.util.ArrayList<>();
            if ("list".startsWith(partial)) {
                out.add("list");
            }
            ctx.modules().all().stream()
                    .map(Module::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .forEach(out::add);
            return out;
        }
        if (args.length == 3) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            return List.of("toggle", "hold").stream().filter(s -> s.startsWith(partial)).toList();
        }
        return List.of();
    }
}
