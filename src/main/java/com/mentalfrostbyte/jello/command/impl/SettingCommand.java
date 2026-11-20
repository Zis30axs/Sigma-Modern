package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** Reads, updates or resets a module's settings from chat. */
public final class SettingCommand implements Command {

    @Override
    public String name() {
        return "setting";
    }

    @Override
    public List<String> aliases() {
        return List.of("set", "s");
    }

    @Override
    public String usage() {
        return "<module> [setting] [value|reset]";
    }

    @Override
    public String description() {
        return "Lists or edits a module's settings (quote names with spaces, e.g. \"Alert Level\").";
    }

    @Override
    public String descriptionZh() {
        return "查看、修改或重置模块参数（含空格的参数名请加引号，如 \"Alert Level\"）。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length == 0) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        Optional<Module> foundModule = ctx.modules().find(args[0]);
        if (foundModule.isEmpty()) {
            ctx.error(ctx.t("No module named '" + args[0] + "'.", "未找到模块 '" + args[0] + "'。"));
            return;
        }
        Module module = foundModule.get();

        if (args.length == 1) {
            if (module.settings().isEmpty()) {
                ctx.reply(ctx.t(
                        "§f" + module.getName() + " §7has no settings.",
                        "§f" + module.getName() + " §7没有可配置参数。"));
                return;
            }
            ctx.reply(ctx.t(
                    "§7Settings for §f" + module.getName() + "§7:",
                    "§f" + module.getName() + " §7的参数列表："));
            for (Setting<?> setting : module.settings()) {
                ctx.raw("  §b" + setting.getName() + " §8= §f" + formatValue(setting));
            }
            return;
        }

        Optional<Setting<?>> foundSetting = findSetting(module, args[1]);
        if (foundSetting.isEmpty()) {
            ctx.error(ctx.t(
                    module.getName() + " has no setting named '" + args[1] + "'.",
                    module.getName() + " 没有名为 '" + args[1] + "' 的参数。"));
            return;
        }
        Setting<?> setting = foundSetting.get();

        if (args.length == 2) {
            ctx.reply("§f" + module.getName() + "." + setting.getName() + " §8= §b" + formatValue(setting));
            return;
        }

        String rawValue = joinFrom(args, 2);
        if ("reset".equalsIgnoreCase(rawValue)) {
            setting.reset();
            ctx.saveConfig();
            ctx.reply(ctx.t(
                    "§7Reset §f" + module.getName() + "." + setting.getName() + " §7to §b" + formatValue(setting) + "§7.",
                    "§7已将 §f" + module.getName() + "." + setting.getName() + " §7重置为 §b" + formatValue(setting) + "§7。"));
            return;
        }

        String error = applyValue(ctx, setting, rawValue);
        if (error != null) {
            ctx.error(error);
            return;
        }

        ctx.saveConfig();
        ctx.reply(ctx.t(
                "§7Set §f" + module.getName() + "." + setting.getName() + " §7to §b" + formatValue(setting) + "§7.",
                "§7已将 §f" + module.getName() + "." + setting.getName() + " §7设为 §b" + formatValue(setting) + "§7。"));
    }

    private static Optional<Setting<?>> findSetting(final Module module, final String query) {
        String normalized = normalize(query);
        for (Setting<?> setting : module.settings()) {
            if (setting.getName().equalsIgnoreCase(query) || normalize(setting.getName()).equals(normalized)) {
                return Optional.of(setting);
            }
        }
        return Optional.empty();
    }

    private static String normalize(final String s) {
        return s.replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String applyValue(final CommandContext ctx, final Setting<?> setting, final String raw) {
        if (setting instanceof BooleanSetting flag) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if ("true".equals(lower) || "on".equals(lower) || "1".equals(lower)) {
                flag.set(true);
                return null;
            }
            if ("false".equals(lower) || "off".equals(lower) || "0".equals(lower)) {
                flag.set(false);
                return null;
            }
            if ("toggle".equals(lower)) {
                flag.set(!flag.get());
                return null;
            }
            return ctx.t(
                    "Expected true/false/on/off/toggle for " + flag.getName() + ".",
                    flag.getName() + " 仅支持 true/false/on/off/toggle。");
        }

        if (setting instanceof NumberSetting number) {
            try {
                float parsed = Float.parseFloat(raw);
                number.set(parsed);
                return null;
            } catch (final NumberFormatException ignored) {
                return ctx.t(
                        "Expected a number between " + number.getMin() + " and " + number.getMax() + ".",
                        "请输入 " + number.getMin() + " 到 " + number.getMax() + " 之间的数值。");
            }
        }

        if (setting instanceof EnumSetting<?> choice) {
            return applyEnum(ctx, choice, raw);
        }

        if (setting instanceof TextSetting text) {
            text.set(raw);
            return null;
        }

        if (setting instanceof ColorSetting color) {
            String hex = raw.startsWith("#") ? raw.substring(1) : raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
            try {
                long parsed = Long.parseLong(hex, 16);
                if (hex.length() == 6) {
                    parsed |= 0xFF000000L;
                }
                color.set((int) parsed);
                return null;
            } catch (final NumberFormatException ignored) {
                return ctx.t(
                        "Expected hex color like #FF3366 or #80FF3366.",
                        "请输入十六进制颜色值，如 #FF3366 或 #80FF3366。");
            }
        }

        return ctx.t(
                "Setting '" + setting.getName() + "' cannot be edited via chat; use the GUI.",
                "参数 '" + setting.getName() + "' 不支持在聊天栏修改，请在界面中调整。");
    }

    private static <E extends Enum<E>> String applyEnum(final CommandContext ctx, final EnumSetting<E> setting, final String raw) {
        String target = normalize(raw);
        for (E option : setting.getOptions()) {
            if (option.name().equalsIgnoreCase(raw)
                    || normalize(option.name()).equals(target)
                    || normalize(EnumSetting.label(option)).equals(target)) {
                setting.set(option);
                return null;
            }
        }
        String options = setting.getOptions().stream().map(Enum::name).collect(Collectors.joining(", "));
        return ctx.t(
                "Invalid option '" + raw + "'. Choices: " + options,
                "无效选项 '" + raw + "'，可选项：" + options);
    }

    private static String formatValue(final Setting<?> setting) {
        if (setting instanceof ColorSetting color) {
            return String.format(Locale.ROOT, "#%08X", color.get());
        }
        if (setting instanceof EnumSetting<?> choice) {
            return choice.get().name();
        }
        return String.valueOf(setting.get());
    }

    private static String joinFrom(final String[] args, final int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
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
        Optional<Module> module = ctx.modules().find(args[0]);
        if (module.isEmpty()) {
            return List.of();
        }
        if (args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return module.get().settings().stream()
                    .map(Setting::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }
        if (args.length == 3) {
            Optional<Setting<?>> setting = findSetting(module.get(), args[1]);
            if (setting.isPresent()) {
                String partial = args[2].toLowerCase(Locale.ROOT);
                if (setting.get() instanceof BooleanSetting) {
                    return List.of("true", "false", "reset").stream().filter(s -> s.startsWith(partial)).toList();
                }
                if (setting.get() instanceof EnumSetting<?> choice) {
                    return choice.getOptions().stream()
                            .map(Enum::name)
                            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                            .toList();
                }
            }
        }
        return List.of();
    }
}
