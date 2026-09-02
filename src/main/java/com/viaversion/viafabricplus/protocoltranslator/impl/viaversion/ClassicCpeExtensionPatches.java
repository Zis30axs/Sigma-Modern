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
import com.viaversion.viafabricplus.features.classic.cpe_extension.CPEAdditions;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.Protocol1_19_3To1_19_4;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.packet.ClientboundPackets1_19_4;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.raphimc.vialegacy.protocol.classic.c0_28_30toa1_0_15.packet.ServerboundPacketsc0_28;
import net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.Protocolc0_30cpeToc0_28_30;
import net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolExtension;
import net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.packet.ClientboundPacketsc0_30cpe;

/**
 * MODIFIED for porting: rebuilds the three ViaFabricPlus {@code features/classic/cpe_extension} mixins whose
 * {@code @Mixin} target is a ViaLegacy jar type - the {@code ClassicProtocolExtension} enum, the
 * {@code ClientboundPacketsc0_30cpe} packet enum and {@code Protocolc0_30cpeToc0_28_30}. Together they are the whole
 * CPE {@code EnvWeatherType} feature: without them the extension is never negotiated, so
 * {@link CPEAdditions#isSnowing()} - already consumed by {@code ClientLevel#getPrecipitationAt} - can never become
 * true and no c0.30cpe server can send weather.
 *
 * <p>Ordering is load bearing, and is why the runtime packet constant is created here instead of in
 * {@code CPEAdditions.init()} like upstream. {@code AbstractProtocol#initialize()} ends in
 * {@code registerPacketIdChanges}, which walks every constant of the unmapped clientbound packet enum as snapshotted
 * by {@code PacketTypeMap.ofUnsequenced} in the protocol's constructor, and hard-fails ("Packet %s in %s has no
 * mapping") for a constant that has neither a same-named mapped packet nor an already registered handler.
 * {@code ClientboundPacketsc0_28} has no EnvWeatherType, and upstream only survives that walk because its mixin
 * injects at {@code registerPackets} RETURN - inside {@code initialize()}, before the walk. This class runs after
 * ViaLegacy has registered and initialised its protocols, so creating the constant now keeps it out of that snapshot;
 * {@code AbstractProtocol#register} only reads state()/getId()/direction() of a packet type and the id-keyed mapping
 * array grows on demand, so registering the handler afterwards still lands.
 */
public final class ClassicCpeExtensionPatches {

    // wiki.vg Classic Protocol Extension: EnvWeatherType is extension version 1, clientbound packet id 0x1F.
    private static final int ENV_WEATHER_TYPE_PACKET_ID = 31;
    private static final int ENV_WEATHER_TYPE_VERSION = 1;

    private ClassicCpeExtensionPatches() {
    }

    /**
     * Never throws. {@code ProtocolTranslator#init} calls {@code ViaFabricPlusProtocolPatches#apply()} - and through
     * it this method - inside a {@code CompletableFuture.runAsync} that logs nothing, so an escaping exception would
     * silently skip the AUTO_DETECT registration and {@code ViaFabricPlusProtocol#initialize()} that follow it. The
     * same guard keeps the ordering below safe under failure: giving up anywhere before the last two lines leaves the
     * extension unadvertised, so a server can never send a packet id the pre-netty splitter would close the channel
     * over.
     */
    public static void apply() {
        try {
            install();
        } catch (final Throwable t) {
            ViaFabricPlusImpl.INSTANCE.getLogger().error("Failed to install the CPE EnvWeatherType extension, it stays disabled", t);
        }
    }

    private static void install() {
        if (CPEAdditions.EXT_WEATHER_TYPE != null) {
            return; // Already installed - the enum surgery below must not run twice
        }

        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        awaitMappings(protocolManager, Protocolc0_30cpeToc0_28_30.class, Protocol1_19_3To1_19_4.class);

        final Protocolc0_30cpeToc0_28_30 protocol = protocolManager.getProtocol(Protocolc0_30cpeToc0_28_30.class);
        if (protocol == null) {
            ViaFabricPlusImpl.INSTANCE.getLogger().warn("Protocolc0_30cpeToc0_28_30 is not registered, the CPE EnvWeatherType extension stays disabled");
            return;
        }

        // was VFP features/classic/cpe_extension MixinClientboundPacketsc0_30cpe#addCustomPackets (@Inject HEAD cancellable on
        // the static getPacket, answering from CPEAdditions.CUSTOM_PACKETS before the REGISTRY[id] read). c0.30cpe
        // only. getPacket feeds PreNettyLengthPrepender, which closes the channel on an unknown classic packet id, so
        // the runtime constant has to be reachable from the 256-slot REGISTRY array - which has no setter and no
        // accessor, the one hook here that needs reflection. Every failure path leaves the extension unadvertised,
        // which is exactly the unpatched behaviour.
        final ClientboundPacketsc0_30cpe envWeatherType;
        try {
            envWeatherType = CPEAdditions.createNewPacket(
                ClassicProtocolExtension.ENV_WEATHER_TYPE, ENV_WEATHER_TYPE_PACKET_ID, (user, buf) -> buf.readByte());
        } catch (final Throwable t) {
            ViaFabricPlusImpl.INSTANCE.getLogger().error("Failed to create the CPE EnvWeatherType packet type, the extension stays disabled", t);
            return;
        }

        if (!registerPreNettyPacketId(envWeatherType)) {
            CPEAdditions.CUSTOM_PACKETS.remove(ENV_WEATHER_TYPE_PACKET_ID);
            return;
        }
        CPEAdditions.EXT_WEATHER_TYPE = envWeatherType;

        // was VFP features/classic/cpe_extension MixinProtocolc0_30cpeToc0_28_30#extendPackets (@Inject registerPackets RETURN,
        // registering the EnvWeatherType packet). c0.30cpe only; the handler body is upstream's verbatim, and
        // registerClientbound(CU, CM, PacketHandler) with a null mapped type is the same call the mixin makes.
        protocol.registerClientbound(envWeatherType, null, wrapper -> {
            wrapper.cancel();
            final byte weatherType = wrapper.read(Types.BYTE);

            final PacketWrapper changeRainState = PacketWrapper.create(ClientboundPackets1_19_4.GAME_EVENT, wrapper.user());
            changeRainState.write(Types.UNSIGNED_BYTE, weatherType == 0 /* sunny */ ? (short) 1 : (short) 2); // start raining
            changeRainState.write(Types.FLOAT, 0F); // unused
            changeRainState.send(Protocol1_19_3To1_19_4.class);

            if (weatherType == 1 /* raining */ || weatherType == 2 /* snowing */) {
                final PacketWrapper changeRainType = PacketWrapper.create(ClientboundPackets1_19_4.GAME_EVENT, wrapper.user());
                changeRainType.write(Types.UNSIGNED_BYTE, (short) 7); // set rain gradient
                changeRainType.write(Types.FLOAT, 1F);
                changeRainType.send(Protocol1_19_3To1_19_4.class);
            }
            CPEAdditions.setSnowing(weatherType == 2);
        });

        // was VFP features/classic/cpe_extension MixinProtocolc0_30cpeToc0_28_30#resetSnowing (@Inject init HEAD), clearing the
        // global snow flag while the protocol is set up for a connection so a stale state cannot leak into the next
        // server. c0.30cpe only. Protocol#init has no public hook, so the reset is appended to the client's own
        // classic login packet: it is sent exactly once per connection and always before the server's first CPE
        // packet, because a classic server answers nothing until it has the Player Identification. This deliberately
        // does not reproduce upstream's incidental resets from ProtocolTranslator#createDummyUserConnection, which
        // also calls init() and can therefore clear the flag mid-session upstream.
        protocol.appendServerbound(ServerboundPacketsc0_28.LOGIN, wrapper -> CPEAdditions.setSnowing(false));

        // was VFP features/classic/cpe_extension MixinClassicProtocolExtension#allowExtensions_isSupported,
        // #allowExtensions_getHighestSupportedVersion and #allowExtensions_supportsVersion (all @Inject HEAD
        // cancellable, forcing true/1/true for anything in CPEAdditions.ALLOWED_EXTENSIONS). c0.30cpe only.
        // getSupportedVersions() hands back the live IntOpenHashSet the constructor filled from an empty int[], so
        // adding version 1 makes isSupported() true and getHighestSupportedVersion() 1 - the two values
        // Protocolc0_30cpeToc0_28_30's EXTENSION_PROTOCOL_ENTRY handler reads when it advertises the client's
        // extension list. supportsVersion is only reachable from ClassicProtocolExtension#byNameAndVersion, which
        // nothing in ViaLegacy, ViaBedrock, ViaBackwards, ViaAprilFools, ViaVersion or this tree calls, so the set
        // answering only version 1 instead of every int is not observable. Done last: advertising an extension whose
        // packet id is missing from REGISTRY would close the connection, see above.
        //
        // The ALLOWED_EXTENSIONS entry is bookkeeping only - it was the list the deleted mixin consulted and nothing
        // in this tree reads it, so the IntSet on the next line is what actually advertises the extension.
        CPEAdditions.allowExtension(ClassicProtocolExtension.ENV_WEATHER_TYPE);
        ClassicProtocolExtension.ENV_WEATHER_TYPE.getSupportedVersions().add(ENV_WEATHER_TYPE_VERSION);
    }

    // Writes the runtime packet constant into ClientboundPacketsc0_30cpe.REGISTRY, the private static final array
    // getPacket(int) reads. Only the array reference is read reflectively - the slot write is a plain array store, so
    // no final field is ever rewritten. Returns false if anything goes wrong, leaving the extension unadvertised.
    private static boolean registerPreNettyPacketId(final ClientboundPacketsc0_30cpe packet) {
        try {
            final Field registryField = ClientboundPacketsc0_30cpe.class.getDeclaredField("REGISTRY");
            registryField.setAccessible(true);
            final ClientboundPacketsc0_30cpe[] registry = (ClientboundPacketsc0_30cpe[]) registryField.get(null);
            if (registry == null || packet.getId() < 0 || packet.getId() >= registry.length) {
                ViaFabricPlusImpl.INSTANCE.getLogger()
                    .warn("ClientboundPacketsc0_30cpe.REGISTRY cannot hold packet id {}, the CPE EnvWeatherType extension stays disabled", packet.getId());
                return false;
            }

            registry[packet.getId()] = packet;
            return true;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .error("Failed to add the CPE EnvWeatherType packet to ClientboundPacketsc0_30cpe.REGISTRY, the extension stays disabled", e);
            return false;
        }
    }

    // Same barrier as ViaFabricPlusProtocolPatches#awaitMappings: blocks until each protocol's registerPackets() has
    // run. Protocolc0_30cpeToc0_28_30 carries no mapping data, so its future is null and it is already initialised by
    // the time this runs - ViaLegacy registers it from an enable listener inside ViaManagerImpl.initAndLoad.
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
                ViaFabricPlusImpl.INSTANCE.getLogger().error("Failed to load mappings for {}, its ViaFabricPlus patches may not apply", protocol.getSimpleName(), e);
            }
        }
    }

}
