package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Grim's chat lines on their way to the player's chat and to the console log.
 *
 * <p>Grim's messages carry click events that run {@code /grim ...}. In the client such a click would send the command
 * to the real server, which is exactly what must not happen; each one becomes a suggestion of the same command with
 * the local prefix ({@code .grim ...}), so a click puts it in the chat box and Enter runs it here.</p>
 */
final class GrimChat {

    private final EngineContext context;

    GrimChat(final EngineContext context) {
        this.context = context;
    }

    void send(final Component message) {
        JsonElement json = GsonComponentSerializer.gson().serializeToTree(message);
        this.context.verdicts().message(localiseClicks(json, this.context.commandPrefix()).toString());
    }

    void sendLegacy(final String message) {
        this.send(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    void console(final Component message) {
        this.context.log(PlainTextComponentSerializer.plainText().serialize(message));
    }

    void console(final String message) {
        this.console(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    /** Rewrites every click that runs or suggests {@code /grim ...} into a suggestion of {@code <prefix>grim ...}. */
    static JsonElement localiseClicks(final JsonElement element, final String prefix) {
        if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, localiseClicks(array.get(i), prefix));
            }
        } else if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                entry.setValue(localiseClicks(entry.getValue(), prefix));
            }
            if (object.get("click_event") instanceof JsonObject click) {
                localiseClick(click, prefix);
            }
        }
        return element;
    }

    private static void localiseClick(final JsonObject click, final String prefix) {
        String action = click.has("action") ? click.get("action").getAsString() : "";
        if (!action.equals("run_command") && !action.equals("suggest_command")) {
            return;
        }
        String command = click.has("command") ? click.get("command").getAsString() : "";
        String bare = command.startsWith("/") ? command.substring(1) : command;
        if (!bare.toLowerCase(Locale.ROOT).startsWith("grim")) {
            return;
        }
        click.addProperty("action", "suggest_command");
        click.addProperty("command", prefix + bare);
    }
}
