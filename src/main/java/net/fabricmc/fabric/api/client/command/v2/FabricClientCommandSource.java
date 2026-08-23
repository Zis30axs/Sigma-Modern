package net.fabricmc.fabric.api.client.command.v2;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.PermissionSetSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;

// MODIFIED for porting: embedded stand-in for fabric-api
public interface FabricClientCommandSource extends SharedSuggestionProvider {
    void sendFeedback(Component feedback);

    void sendError(Component error);

    LocalPlayer getPlayer();

    @Override
    default PermissionSet permissions() {
        return PermissionSet.NO_PERMISSIONS;
    }

    @Override
    default Collection<String> getOnlinePlayerNames() {
        return List.of();
    }

    @Override
    default Collection<String> getAllTeams() {
        return List.of();
    }

    @Override
    default Stream<Identifier> getAvailableSounds() {
        return Stream.of();
    }

    @Override
    default CompletableFuture<Suggestions> customSuggestion(final CommandContext<?> context) {
        return Suggestions.empty();
    }

    @Override
    default Set<ResourceKey<Level>> levels() {
        return Set.of();
    }

    @Override
    default RegistryAccess registryAccess() {
        final Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    @Override
    default FeatureFlagSet enabledFeatures() {
        final Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.enabledFeatures() : FeatureFlags.DEFAULT_FLAGS;
    }
}