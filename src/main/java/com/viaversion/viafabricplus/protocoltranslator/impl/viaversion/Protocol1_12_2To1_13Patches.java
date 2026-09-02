/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.protocoltranslator.impl.viaversion;

import com.viaversion.viafabricplus.ViaFabricPlusImpl;
import com.viaversion.viafabricplus.features.world.footstep_particle.FootStepParticle1_12_2;
import com.viaversion.viafabricplus.util.network.SyncTasks;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.mcstructs.text.stringformat.StringFormat;
import com.viaversion.viaversion.libs.mcstructs.text.stringformat.handling.ColorHandling;
import com.viaversion.viaversion.libs.mcstructs.text.stringformat.handling.DeserializerUnknownHandling;
import com.viaversion.viaversion.protocols.v1_12_2to1_13.Protocol1_12_2To1_13;
import com.viaversion.viaversion.protocols.v1_12_2to1_13.packet.ClientboundPackets1_13;
import com.viaversion.viaversion.protocols.v1_12to1_12_1.packet.ClientboundPackets1_12_1;
import com.viaversion.viaversion.util.ChatColorUtil;
import com.viaversion.viaversion.util.SerializerVersion;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixins that target ViaVersion's 1.12.2 -> 1.13 protocol,
 * its particle mapping tables and {@code ComponentUtil}. Sigma-Modern has no Mixin runtime and cannot edit the
 * ViaVersion jar, so the equivalent behaviour is installed through ViaVersion's own public protocol API.
 *
 * <p>Everything here hangs off {@link Protocol1_12_2To1_13}, which is only in the pipeline for targets
 * {@code <= 1.12.2} - the same range every rebuilt hook applies to upstream. See
 * {@link ViaFabricPlusProtocolPatches} for why patches have to wait on the mapping-loader future first.
 */
public final class Protocol1_12_2To1_13Patches {

    // 1.12.2 particle id of minecraft:footstep. ViaVersion's ParticleIdMappings1_13 table maps it to -1
    // ("footstep -> REMOVED"), which is what makes its LEVEL_PARTICLES handler cancel the packet.
    private static final int FOOTSTEP_PARTICLE_ID_1_12_2 = 28;

    // Verbatim copy of Protocol1_12_2To1_13's private static SCOREBOARD_TEAM_NAME_REWRITE table
    // (Protocol1_12_2To1_13.java:92-113). Needed because rewriteTeamMemberName, which uses it, is protected.
    private static final Map<Character, Character> SCOREBOARD_TEAM_NAME_REWRITE = new HashMap<>();

    static {
        SCOREBOARD_TEAM_NAME_REWRITE.put('0', 'g');
        SCOREBOARD_TEAM_NAME_REWRITE.put('1', 'h');
        SCOREBOARD_TEAM_NAME_REWRITE.put('2', 'i');
        SCOREBOARD_TEAM_NAME_REWRITE.put('3', 'j');
        SCOREBOARD_TEAM_NAME_REWRITE.put('4', 'p');
        SCOREBOARD_TEAM_NAME_REWRITE.put('5', 'q');
        SCOREBOARD_TEAM_NAME_REWRITE.put('6', 's');
        SCOREBOARD_TEAM_NAME_REWRITE.put('7', 't');
        SCOREBOARD_TEAM_NAME_REWRITE.put('8', 'u');
        SCOREBOARD_TEAM_NAME_REWRITE.put('9', 'v');
        SCOREBOARD_TEAM_NAME_REWRITE.put('a', 'w');
        SCOREBOARD_TEAM_NAME_REWRITE.put('b', 'x');
        SCOREBOARD_TEAM_NAME_REWRITE.put('c', 'y');
        SCOREBOARD_TEAM_NAME_REWRITE.put('d', 'z');
        SCOREBOARD_TEAM_NAME_REWRITE.put('e', '!');
        SCOREBOARD_TEAM_NAME_REWRITE.put('f', '?');
        SCOREBOARD_TEAM_NAME_REWRITE.put('k', '#');
        SCOREBOARD_TEAM_NAME_REWRITE.put('l', '(');
        SCOREBOARD_TEAM_NAME_REWRITE.put('m', ')');
        SCOREBOARD_TEAM_NAME_REWRITE.put('n', ':');
        SCOREBOARD_TEAM_NAME_REWRITE.put('o', ';');
        SCOREBOARD_TEAM_NAME_REWRITE.put('r', '/');
    }

    private Protocol1_12_2To1_13Patches() {
    }

    public static void apply() {
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager, Protocol1_12_2To1_13.class);

        final Protocol1_12_2To1_13 protocol = protocolManager.getProtocol(Protocol1_12_2To1_13.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_12_2To1_13 is not registered, footstep particles and legacy text styles will not be fixed");
            return;
        }

        // Both patches below need ViaVersion's own handler for the packet to already exist: the footstep one reads the
        // mapped values that handler writes, and replaceClientbound throws (Preconditions.checkNotNull) when there is
        // no handler to replace. awaitMappings only *waits* for registerPackets() - if MAPPINGS.load() failed the
        // future completes exceptionally and initialize() never ran, so the mappings stay empty. Checking here keeps a
        // failed mapping load from throwing out of ViaFabricPlusProtocolPatches#apply(), which would skip the
        // AUTO_DETECT registration and ViaFabricPlusProtocol#initialize() that follow it, plus every patch wired
        // after this one. Same guard shape as LegacyItemAndRecipePatches#applyRecipeReset.
        if (!protocol.hasRegisteredClientbound(ClientboundPackets1_12_1.LEVEL_PARTICLES)
            || !protocol.hasRegisteredClientbound(ClientboundPackets1_12_1.SET_OBJECTIVE)
            || !protocol.hasRegisteredClientbound(ClientboundPackets1_12_1.SET_PLAYER_TEAM)) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .warn("Protocol1_12_2To1_13 has no packet handlers, footstep particles and legacy text styles will not be fixed");
            return;
        }

        applyFootStepParticle(protocol);
        applyLegacyTextSections(protocol);
    }

    // was VFP features/world/footstep_particle MixinParticleIdMappings1_13#replaceFootStepId (@ModifyArg on the
    // NewParticle constructor in add(I)V), MixinMappingDataBase#passthroughFootStepParticle (@Inject HEAD
    // cancellable on getNewParticleId), MixinParticleMappings#getNewId and #mappedIdentifier (overwrite-style
    // overrides), and MixinParticleIdMappings1_13#checkFootStepIdOverlap (@Inject <clinit> RETURN).
    //
    // Applies to targets <= 1.12.2, the only ones that can emit 1.12.2 particle id 28 (minecraft:footstep).
    // Upstream makes that id map to FootStepParticle1_12_2.RAW_ID and then teaches every later protocol's
    // particle mapping to pass RAW_ID through unchanged, so the synthetic viafabricplus:footstep id survives
    // the whole 1.12.2 -> 26.2 chain and the client spawns the particle from a normal LEVEL_PARTICLES packet.
    // None of that is reachable here: the id table is a private static List built in a <clinit>, and the
    // ParticleMappings instance lives in MappingDataBase.particleMappings (protected, built inside load(), no
    // setter) whose int mappings are arrays sized to the old registry, so RAW_ID cannot even be stored.
    //
    // Equivalent: ViaVersion's own LEVEL_PARTICLES handler already resolves id 28 to -1 and cancels the packet,
    // leaving every mapped value readable on the wrapper. Appending to that handler chain (appendClientbound
    // runs after a cancel - PacketHandler#then does not short-circuit) recovers the 1.12.2 fields and hands the
    // spawn to the client as a sync task, which lands in ClientPacketListener#handleParticleEvent exactly as
    // the surviving packet would have. alwaysShow = false matches what Protocol1_21_2To1_21_4 writes for
    // pre-1.21.4 servers; overrideLimiter is the 1.12.2 long-distance flag.
    //
    // MixinParticleMappings#mappedIdentifier and MixinRegistrySyncManager have no counterpart: RAW_ID never
    // enters a ViaVersion mapping or a Fabric registry-sync map in this rebuild - see VFP_AUDIT.md.
    private static void applyFootStepParticle(final Protocol1_12_2To1_13 protocol) {
        // was VFP features/world/footstep_particle MixinParticleIdMappings1_13#checkFootStepIdOverlap
        // (@Inject <clinit> RETURN). Upstream asserts the synthetic id cannot collide with a real 1.12.2
        // particle id, which also catches RAW_ID still being 0 because FootStepParticle1_12_2.init() had not
        // run yet. Only the second half can happen here (nothing compares RAW_ID against a 1.12.2 id any
        // more), so the check is that RAW_ID still resolves to viafabricplus:footstep. Upstream throws from a
        // ViaVersion <clinit>; this runs inside the shared patch chain, so it logs and leaves the feature
        // inert - which is the unpatched behaviour - instead of taking unrelated patches down with it.
        final SimpleParticleType footStepType = resolveFootStepParticle();
        if (footStepType == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().error(
                "FootStepParticle1_12_2.RAW_ID ({}) does not resolve to {}, the 1.12.2 footstep particle stays disabled",
                FootStepParticle1_12_2.RAW_ID, FootStepParticle1_12_2.ID
            );
            return;
        }

        protocol.appendClientbound(ClientboundPackets1_12_1.LEVEL_PARTICLES, wrapper -> {
            // Types.INT 0 is the particle id, Types.INT 1 the count; ViaVersion only overwrites the id on the
            // paths it does not cancel, so an id of 28 here is always the footstep it just dropped.
            if (!wrapper.isCancelled() || wrapper.get(Types.INT, 0) != FOOTSTEP_PARTICLE_ID_1_12_2) {
                return;
            }

            final boolean longDistance = wrapper.get(Types.BOOLEAN, 0);
            final double x = wrapper.get(Types.FLOAT, 0).doubleValue();
            final double y = wrapper.get(Types.FLOAT, 1).doubleValue();
            final double z = wrapper.get(Types.FLOAT, 2).doubleValue();
            final float offsetX = wrapper.get(Types.FLOAT, 3);
            final float offsetY = wrapper.get(Types.FLOAT, 4);
            final float offsetZ = wrapper.get(Types.FLOAT, 5);
            final float speed = wrapper.get(Types.FLOAT, 6);
            final int count = wrapper.get(Types.INT, 1);

            final String uuid = SyncTasks.executeSyncTask(data -> {
                final ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null || Minecraft.getInstance().level == null) {
                    return;
                }

                connection.handleParticleEvent(new ClientboundLevelParticlesPacket(
                    footStepType, longDistance, false, x, y, z, offsetX, offsetY, offsetZ, speed, count
                ));
            });

            wrapper.create(ClientboundPackets1_13.CUSTOM_PAYLOAD, payload -> {
                payload.write(Types.STRING, SyncTasks.PACKET_SYNC_IDENTIFIER);
                payload.write(Types.STRING, uuid);
            }).send(Protocol1_12_2To1_13.class);
        });
    }

    // FootStepParticle1_12_2.RAW_ID is filled in by FootStepParticle1_12_2.init() (FeaturesLoading.java:48),
    // which registers viafabricplus:footstep into the frozen vanilla particle registry.
    private static SimpleParticleType resolveFootStepParticle() {
        final ParticleType<?> particleType = BuiltInRegistries.PARTICLE_TYPE.byId(FootStepParticle1_12_2.RAW_ID);
        if (!(particleType instanceof SimpleParticleType footStepType)) {
            return null;
        }
        if (!FootStepParticle1_12_2.ID.equals(BuiltInRegistries.PARTICLE_TYPE.getKey(particleType))) {
            return null;
        }

        return footStepType;
    }

    // was VFP features/scoreboard MixinComponentUtil#dontSkipEmptySections (@Redirect swapping
    // StringFormat#fromString(String, ColorHandling, DeserializerUnknownHandling) for the 4-arg overload with
    // skipEmpty = false, inside ComponentUtil#legacyToJson and #legacyToJsonString(String, boolean)).
    //
    // Applies to every pre-1.13 target, ungated upstream. The 3-arg overload delegates to the 4-arg one with
    // skipEmpty = true (verified in the shaded mcstructs bytecode), which drops formatting-only runs: a legacy
    // team prefix that is nothing but a colour code converts to a bare {"text":""}, and a prefix whose trailing
    // colour code has no text after it loses that colour entirely. The already ported read side,
    // PlayerTeam#getFormattedName -> vfpGetLastStyle (PlayerTeam.java:126-147), looks for the last styled
    // sibling of the prefix to colour the member name the way a legacy colour-code string would, so without the
    // empty-but-styled sections it has nothing to find and pre-1.13 team / nametag colours are lost.
    //
    // ComponentUtil is a static utility in the jar and StringFormat.vanilla() builds a fresh
    // PrioritizingStringFormat on every call, so there is no instance to substitute and no Via API for it. The
    // only public route is to re-do the conversion in the handlers that need it: these two are ViaVersion's own
    // bodies (Protocol1_12_2To1_13.java:401-419 and :421-470) with legacyToJson swapped for the 4-arg form.
    // replaceClientbound keeps the registered mapped type, so packet ids stay correct.
    //
    // Scope note: the other ComponentUtil callers - item display name and lore (ItemPacketRewriter1_13:299,
    // ItemPacketRewriter1_14:213), entity custom names (EntityPacketRewriter1_13:207), banner / command block /
    // sign names (BlockEntityProvider and its handlers) and ConfigSection - sit inside ViaVersion's item,
    // entity-data and block-entity rewriters and keep the empty-section-skipping behaviour. That costs nothing
    // visible: skipEmpty only decides whether zero-length styled siblings are emitted, and those render as
    // nothing. It matters solely to code that inspects component structure, and the one such reader in this
    // tree is PlayerTeam#vfpGetLastStyle over the team prefix - which is fed by the two packets above.
    private static void applyLegacyTextSections(final Protocol1_12_2To1_13 protocol) {
        protocol.replaceClientbound(ClientboundPackets1_12_1.SET_OBJECTIVE, wrapper -> {
            wrapper.passthrough(Types.STRING); // Objective name
            final byte mode = wrapper.passthrough(Types.BYTE); // Mode

            // On create or update
            if (mode == 0 || mode == 2) {
                final String value = wrapper.read(Types.STRING); // Value
                wrapper.write(Types.COMPONENT, legacyToJson(value));

                final String type = wrapper.read(Types.STRING);
                // integer or hearts
                wrapper.write(Types.VAR_INT, type.equals("integer") ? 0 : 1);
            }
        });

        protocol.replaceClientbound(ClientboundPackets1_12_1.SET_PLAYER_TEAM, wrapper -> {
            wrapper.passthrough(Types.STRING); // Team Name
            final byte action = wrapper.passthrough(Types.BYTE); // Mode

            if (action == 0 || action == 2) {
                final String displayName = wrapper.read(Types.STRING); // Display Name
                wrapper.write(Types.COMPONENT, legacyToJson(displayName));

                final String prefix = wrapper.read(Types.STRING); // Prefix moved
                String suffix = wrapper.read(Types.STRING); // Suffix moved

                wrapper.passthrough(Types.BYTE); // Flags

                wrapper.passthrough(Types.STRING); // Name Tag Visibility
                wrapper.passthrough(Types.STRING); // Collision rule

                // Handle new colors
                int colour = wrapper.read(Types.BYTE).intValue();
                if (colour == -1) {
                    colour = 21; // -1 changed to 21
                }

                if (Via.getConfig().is1_13TeamColourFix()) {
                    final char lastColorChar = protocol.getLastColorChar(prefix);
                    colour = ChatColorUtil.getColorOrdinal(lastColorChar);
                    suffix = ChatColorUtil.COLOR_CHAR + Character.toString(lastColorChar) + suffix;
                }

                wrapper.write(Types.VAR_INT, colour);

                wrapper.write(Types.COMPONENT, legacyToJson(prefix)); // Prefix
                wrapper.write(Types.COMPONENT, legacyToJson(suffix)); // Suffix
            }

            if (action == 0 || action == 3 || action == 4) {
                final String[] names = wrapper.read(Types.STRING_ARRAY); // Entities
                for (int i = 0; i < names.length; i++) {
                    names[i] = rewriteTeamMemberName(names[i]);
                }
                wrapper.write(Types.STRING_ARRAY, names);
            }
        });
    }

    // ComponentUtil#legacyToJson with the redirect already applied: skipEmpty = false keeps formatting-only
    // sections, so a trailing style survives the legacy -> JSON conversion as an empty styled sibling.
    private static JsonElement legacyToJson(final String message) {
        return SerializerVersion.V1_12.toJson(
            StringFormat.vanilla().fromString(message, ColorHandling.RESET, DeserializerUnknownHandling.WHITE, false)
        );
    }

    // Verbatim copy of Protocol1_12_2To1_13#rewriteTeamMemberName (Protocol1_12_2To1_13.java:893-911), which is
    // protected and therefore unreachable from here; the two handlers above have to keep applying it.
    private static String rewriteTeamMemberName(String name) {
        // The Display Name is just colours which overwrites the suffix
        // It also overwrites for ANY colour in name but most plugins
        // will just send colour as an invisible character
        if (ChatColorUtil.stripColor(name).isEmpty()) {
            final StringBuilder newName = new StringBuilder();
            for (int i = 1; i < name.length(); i += 2) {
                final char colorChar = name.charAt(i);
                Character rewrite = SCOREBOARD_TEAM_NAME_REWRITE.get(colorChar);
                if (rewrite == null) {
                    rewrite = colorChar;
                }
                newName.append(ChatColorUtil.COLOR_CHAR).append(rewrite);
            }
            name = newName.toString();
        }
        return name;
    }

    // Same barrier as ViaFabricPlusProtocolPatches#awaitMappings: registerPackets() runs on Via's
    // mapping-loader pool, and replaceClientbound throws if the handler being replaced is not registered yet.
    @SafeVarargs
    private static void awaitMappings(final ProtocolManager protocolManager, final Class<? extends Protocol>... protocols) {
        for (final Class<? extends Protocol> protocol : protocols) {
            final CompletableFuture<Void> future = protocolManager.getMappingLoaderFuture(protocol);
            if (future == null) {
                continue;
            }

            try {
                future.join();
            } catch (final CompletionException | CancellationException e) {
                ViaFabricPlusImpl.INSTANCE.getLogger()
                    .error("Failed to load mappings for {}, its ViaFabricPlus patches may not apply", protocol.getSimpleName(), e);
            }
        }
    }
}
