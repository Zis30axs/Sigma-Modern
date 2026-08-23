package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

// MODIFIED for porting: embedded stand-in for fabric-api
public final class ClientTickEvents {
    public static final Event<StartClientTick> START_CLIENT_TICK = EventFactory.createArrayBacked(
        StartClientTick.class,
        callbacks -> client -> {
            for (final StartClientTick callback : callbacks) {
                callback.onStartClientTick(client);
            }
        }
    );

    public static final Event<StartLevelTick> START_LEVEL_TICK = EventFactory.createArrayBacked(
        StartLevelTick.class,
        callbacks -> world -> {
            for (final StartLevelTick callback : callbacks) {
                callback.onStartLevelTick(world);
            }
        }
    );

    private ClientTickEvents() {
    }

    @FunctionalInterface
    public interface StartClientTick {
        void onStartClientTick(Minecraft client);
    }

    @FunctionalInterface
    public interface StartLevelTick {
        void onStartLevelTick(ClientLevel world);
    }
}