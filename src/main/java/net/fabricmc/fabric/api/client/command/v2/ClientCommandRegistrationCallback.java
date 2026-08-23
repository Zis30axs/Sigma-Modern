package net.fabricmc.fabric.api.client.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.commands.CommandBuildContext;

// MODIFIED for porting: embedded stand-in for fabric-api
public interface ClientCommandRegistrationCallback {
    Event<ClientCommandRegistrationCallback> EVENT = EventFactory.createArrayBacked(
        ClientCommandRegistrationCallback.class,
        callbacks -> (dispatcher, registryAccess) -> {
            for (final ClientCommandRegistrationCallback callback : callbacks) {
                callback.register(dispatcher, registryAccess);
            }
        }
    );

    void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess);
}
