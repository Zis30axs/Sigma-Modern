package com.mentalfrostbyte.jello.util.text;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * Talking to the chat: printing a line locally, and saying something to the server.
 *
 * <p>These lived as static methods on the interface that also handed out the {@code Minecraft}
 * instance, which meant every class wanting {@code mc} also inherited a chat API it had no use for.</p>
 */
public final class ChatUtil {

    private ChatUtil() {
    }

    /** Prints a line into the local chat. Nobody else sees it. */
    public static void print(final Component message) {
        ChatComponent chat = Minecraft.getInstance().gui.hud.getChat();
        chat.addClientSystemMessage(message);
    }

    public static void print(final String message) {
        print(Component.literal(message));
    }

    /**
     * Sends chat to the server, routing a leading {@code /} to the command path so it is signed and
     * encoded the way the server expects. Does nothing when not connected.
     */
    public static void send(final String message) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }

        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (trimmed.startsWith("/")) {
            connection.sendCommand(trimmed.substring(1));
        } else {
            connection.sendChat(trimmed);
        }
    }
}
