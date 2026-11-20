package com.mentalfrostbyte.jello.command.impl;

import com.mentalfrostbyte.jello.command.Command;
import com.mentalfrostbyte.jello.command.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Sends raw text directly to server chat without re-intercepting the command prefix. */
public final class SayCommand implements Command {

    @Override
    public String name() {
        return "say";
    }

    @Override
    public String usage() {
        return "<message>";
    }

    @Override
    public String description() {
        return "Sends a literal message to server chat (useful for messages starting with the prefix).";
    }

    @Override
    public String descriptionZh() {
        return "直接向服务器发送原文聊天消息（用于发送以前缀开头的消息）。";
    }

    @Override
    public void execute(final CommandContext ctx, final String[] args) {
        if (args.length == 0) {
            ctx.error(ctx.t("Usage: ", "用法：") + ctx.prefix() + this.name() + " " + this.usage());
            return;
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            ctx.error(ctx.t("Not connected to a server.", "当前未连接到服务器。"));
            return;
        }

        connection.sendRawChat(String.join(" ", args));
    }
}
