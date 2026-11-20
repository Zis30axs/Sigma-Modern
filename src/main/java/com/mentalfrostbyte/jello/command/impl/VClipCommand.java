package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Teleports the local player vertically by a given offset in blocks. */
public final class VClipCommand implements Command {

    @Override
    public String name() {
        return "vclip";
    }

    @Override
    public List<String> aliases() {
        return List.of("vc");
    }

    @Override
    public String usage() {
        return "<blocks>";
    }

    @Override
    public String description() {
        return "Shifts your vertical position up or down by the given number of blocks.";
    }

    @Override
    public String descriptionZh() {
        return "沿垂直方向向上或向下位移指定格数。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length < 1) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        double offset;
        try {
            offset = Double.parseDouble(args[0]);
        } catch (final NumberFormatException ignored) {
            ctx.error(ctx.t(
                    "Expected a numeric offset, got '" + args[0] + "'.",
                    "请输入数字格数，当前输入为 '" + args[0] + "'。"));
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            ctx.error(ctx.t("Not in a world.", "当前未进入世界。"));
            return;
        }

        player.setPos(player.getX(), player.getY() + offset, player.getZ());
        ctx.reply(String.format(Locale.ROOT,
                ctx.t("§7Clipped §f%+.2f §7blocks vertically.", "§7已垂直位移 §f%+.2f §7格。"),
                offset));
    }
}
