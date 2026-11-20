package com.mentalfrostbyte.jello.command.impl;

import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import com.mentalfrostbyte.jello.config.ModuleConfig;
import com.mentalfrostbyte.jello.util.io.JsonFileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Saves, loads, lists and deletes named module config profiles under {@code sigma5/profiles}. */
public final class ConfigCommand implements Command {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,48}");

    @Override
    public String name() {
        return "config";
    }

    @Override
    public List<String> aliases() {
        return List.of("cfg", "profile");
    }

    @Override
    public String usage() {
        return "<save|load|list|delete> [name]";
    }

    @Override
    public String description() {
        return "Manages named module configuration profiles.";
    }

    @Override
    public String descriptionZh() {
        return "保存、加载、列出或删除命名配置预设（Profiles）。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length == 0) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        Path dir = ctx.profilesDirectory();

        if ("list".equals(action)) {
            List<String> profiles = listProfiles(dir);
            if (profiles.isEmpty()) {
                ctx.reply(ctx.t(
                        "§7No saved profiles yet. Use §f" + ctx.prefix() + "config save <name>§7.",
                        "§7暂无已保存的配置预设，可使用 §f" + ctx.prefix() + "config save <name> §7创建。"));
                return;
            }
            ctx.reply(ctx.t(
                    "§7Profiles (§f" + profiles.size() + "§7): §f" + String.join("§8, §f", profiles),
                    "§7配置预设（§f" + profiles.size() + "§7）：§f" + String.join("§8, §f", profiles)));
            return;
        }

        if (args.length < 2) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + action + " <name>");
            return;
        }

        String profileName = args[1];
        if (!SAFE_NAME.matcher(profileName).matches()) {
            ctx.error(ctx.t(
                    "Profile name may only contain letters, digits, '.', '_' and '-' (1-48 chars).",
                    "预设名只能包含字母、数字、'.'、'_' 和 '-'（1–48 字符）。"));
            return;
        }

        Path file = dir.resolve(profileName + ".json");
        switch (action) {
            case "save" -> {
                JsonObject root = new JsonObject();
                ModuleConfig.write(root, ctx.modules());
                try {
                    Files.createDirectories(dir);
                    JsonFileUtil.write(file, root);
                    ctx.reply(ctx.t(
                            "§7Saved profile §f" + profileName + "§7.",
                            "§7已保存配置预设 §f" + profileName + "§7。"));
                } catch (final IOException failure) {
                    ctx.error(ctx.t(
                            "Could not save profile '" + profileName + "': " + failure.getMessage(),
                            "保存预设 '" + profileName + "' 失败：" + failure.getMessage()));
                }
            }
            case "load" -> {
                if (!Files.exists(file)) {
                    ctx.error(ctx.t(
                            "Profile '" + profileName + "' does not exist.",
                            "配置预设 '" + profileName + "' 不存在。"));
                    return;
                }
                JsonObject root = JsonFileUtil.read(file);
                ModuleConfig.read(root, ctx.modules());
                ctx.saveConfig();
                ctx.reply(ctx.t(
                        "§7Loaded profile §f" + profileName + "§7.",
                        "§7已加载配置预设 §f" + profileName + "§7。"));
            }
            case "delete", "remove", "rm" -> {
                try {
                    if (Files.deleteIfExists(file)) {
                        ctx.reply(ctx.t(
                                "§7Deleted profile §f" + profileName + "§7.",
                                "§7已删除配置预设 §f" + profileName + "§7。"));
                    } else {
                        ctx.error(ctx.t(
                                "Profile '" + profileName + "' does not exist.",
                                "配置预设 '" + profileName + "' 不存在。"));
                    }
                } catch (final IOException failure) {
                    ctx.error(ctx.t(
                            "Could not delete profile '" + profileName + "': " + failure.getMessage(),
                            "删除预设 '" + profileName + "' 失败：" + failure.getMessage()));
                }
            }
            default -> ctx.error(ctx.t(
                    "Unknown subcommand '" + args[0] + "'. Use save, load, list or delete.",
                    "未知子命令 '" + args[0] + "'，请使用 save、load、list 或 delete。"));
        }
    }

    private static List<String> listProfiles(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (final IOException ignored) {
            return List.of();
        }
    }

    @Override
    public List<String> suggest(final CommandContext ctx, final String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return List.of("save", "load", "list", "delete").stream().filter(s -> s.startsWith(partial)).toList();
        }
        if (args.length == 2 && ("load".equalsIgnoreCase(args[0]) || "delete".equalsIgnoreCase(args[0]))) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return listProfiles(ctx.profilesDirectory()).stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }
        return List.of();
    }
}
