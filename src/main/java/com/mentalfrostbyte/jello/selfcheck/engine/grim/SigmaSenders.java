package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.sender.SenderFactory;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * Who can send Grim a command or receive its messages: the local player ({@link SigmaPlayers.Native}) and the console,
 * whose output is the self-check log. The console may do anything; the player's permissions are
 * {@link SigmaPlayers#hasPermission}.
 */
final class SigmaSenders extends SenderFactory<Object> {

    /** Stands for the console. */
    static final Object CONSOLE = new Object() {
        @Override
        public String toString() {
            return "console";
        }
    };

    private final GrimChat chat;

    SigmaSenders(final GrimChat chat) {
        this.chat = chat;
    }

    Sender console() {
        return this.wrap(CONSOLE);
    }

    @Override
    protected UUID getUniqueId(final Object sender) {
        return sender instanceof SigmaPlayers.Native player ? player.uuid() : Sender.CONSOLE_UUID;
    }

    @Override
    protected String getName(final Object sender) {
        return sender instanceof SigmaPlayers.Native player ? player.name() : Sender.CONSOLE_NAME;
    }

    @Override
    protected void sendMessage(final Object sender, final String message) {
        if (sender instanceof SigmaPlayers.Native) {
            this.chat.sendLegacy(message);
        } else {
            this.chat.console(message);
        }
    }

    @Override
    protected void sendMessage(final Object sender, final Component message) {
        if (sender instanceof SigmaPlayers.Native) {
            this.chat.send(message);
        } else {
            this.chat.console(message);
        }
    }

    @Override
    protected boolean hasPermission(final Object sender, final String permission) {
        return sender == CONSOLE || SigmaPlayers.hasPermission(permission);
    }

    @Override
    protected boolean hasPermission(final Object sender, final String permission, final boolean defaultIfUnset) {
        return this.hasPermission(sender, permission);
    }

    @Override
    protected void performCommand(final Object sender, final String command) {
        // nothing here runs commands on anyone's behalf
    }

    @Override
    protected boolean isConsole(final Object sender) {
        return sender == CONSOLE;
    }

    @Override
    protected boolean isPlayer(final Object sender) {
        return sender instanceof SigmaPlayers.Native;
    }
}
