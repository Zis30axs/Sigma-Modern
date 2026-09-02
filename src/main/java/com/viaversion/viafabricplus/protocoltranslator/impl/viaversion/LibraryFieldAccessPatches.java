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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolExtension;
import net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.storage.ExtensionProtocolMetadataStorage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.util.FastBinaryMinHeap;
import org.jetbrains.annotations.Nullable;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixins in {@code injection/mixin/core/access} whose
 * {@code @Mixin} target is a library class shipped in a jar - ViaBedrock's {@code ChunkTracker}, ViaLegacy's
 * {@code ExtensionProtocolMetadataStorage} and netty-transport-raknet's {@code RakSessionCodec}. Each of those
 * mixins does nothing but implement a {@code viaFabricPlus$} accessor interface over a {@code @Shadow} private
 * field. Without a Mixin runtime the library class can never implement that interface, so the
 * {@code (IChunkTracker)} / {@code (IRakSessionCodec)} / {@code (IExtensionProtocolMetadataStorage)} casts the
 * ported consumers still carried would have thrown {@link ClassCastException} on the first Bedrock connection
 * (F3 overlay) and on {@code /viafabricplus listextensions}.
 *
 * <p>None of the six fields has a public route: javap shows {@code subChunkRequests}, {@code pendingSubChunks}
 * and {@code chunks} private final with no getter; {@code outgoingPackets} and {@code sentDatagrams} private with
 * only {@code getPing}/{@code getRTT}/{@code getMtu}/{@code getMetrics} public, and {@code RakChannelMetrics} is a
 * sink of event callbacks that exposes no queue depth; {@code serverExtensions} private final behind
 * {@code addServerExtension}/{@code hasServerExtension}/{@code getExtensionCount} only. So they are read
 * reflectively, the way {@code AccountProfileKeyPairManager} reads AuthLib private fields, and every failure path
 * degrades to the readout being hidden instead of to an exception.
 *
 * <p>This class touches no {@code Protocol}, so the mapping-loader barrier in
 * {@link ViaFabricPlusProtocolPatches} does not apply to it.
 */
public final class LibraryFieldAccessPatches {

    /**
     * Returned by the size accessors when the field could not be read, so callers can drop the readout instead of
     * printing a wrong number.
     */
    public static final int UNAVAILABLE = -1;

    /**
     * The six lookups live in a nested class so that {@code LibraryFieldAccessPatches} itself initializes without
     * linking ViaBedrock, ViaLegacy or netty-transport-raknet. A moved class then raises {@link NoClassDefFoundError}
     * where {@link #apply()} can catch it, instead of out of this class' initializer at the call site.
     */
    private static final class Fields {

        private static final @Nullable Field CHUNK_TRACKER_SUB_CHUNK_REQUESTS = findField(ChunkTracker.class, "subChunkRequests");
        private static final @Nullable Field CHUNK_TRACKER_PENDING_SUB_CHUNKS = findField(ChunkTracker.class, "pendingSubChunks");
        private static final @Nullable Field CHUNK_TRACKER_CHUNKS = findField(ChunkTracker.class, "chunks");
        private static final @Nullable Field RAK_SESSION_OUTGOING_PACKETS = findField(RakSessionCodec.class, "outgoingPackets");
        private static final @Nullable Field RAK_SESSION_SENT_DATAGRAMS = findField(RakSessionCodec.class, "sentDatagrams");
        private static final @Nullable Field CPE_SERVER_EXTENSIONS = findField(ExtensionProtocolMetadataStorage.class, "serverExtensions");

        private Fields() {
        }

    }

    private static volatile boolean readFailureLogged;

    private LibraryFieldAccessPatches() {
    }

    /**
     * Forces the six lookups off the render thread - they would otherwise run on the first F3 press - and reports in a
     * single line which readouts a renamed library field has taken away. Never throws, because
     * {@code ViaFabricPlusProtocolPatches#apply()} calls this and would skip everything after the call.
     */
    public static void apply() {
        try {
            final List<String> unavailable = new ArrayList<>();
            if (Fields.CHUNK_TRACKER_SUB_CHUNK_REQUESTS == null || Fields.CHUNK_TRACKER_PENDING_SUB_CHUNKS == null
                || Fields.CHUNK_TRACKER_CHUNKS == null) {
                unavailable.add("ViaBedrock ChunkTracker (F3 chunk tracker counters)");
            }
            if (Fields.RAK_SESSION_OUTGOING_PACKETS == null || Fields.RAK_SESSION_SENT_DATAGRAMS == null) {
                unavailable.add("RakNet RakSessionCodec (F3 transmit/retransmit queue counters)");
            }
            if (Fields.CPE_SERVER_EXTENSIONS == null) {
                unavailable.add("ViaLegacy ExtensionProtocolMetadataStorage (listextensions command)");
            }
            if (!unavailable.isEmpty()) {
                ViaFabricPlusImpl.INSTANCE.getLogger()
                    .warn("Library fields could not be accessed, the readouts they feed stay hidden: {}", String.join(", ", unavailable));
            }
        } catch (final Throwable t) {
            // A renamed field is already handled by findField; this catches the harder case of a MOVED class, which
            // raises NoClassDefFoundError out of Fields' initializer - a run/jars override jar can do that, and
            // ViaBedrock is a 0.0.x library. ProtocolTranslator calls ViaFabricPlusProtocolPatches#apply() inside a
            // runAsync that logs nothing, so letting it escape would silently skip the AUTO_DETECT registration and
            // ViaFabricPlusProtocol#initialize() that follow it.
            ViaFabricPlusImpl.INSTANCE.getLogger()
                .error("Failed to prepare library field access, the F3 counters and listextensions output stay hidden", t);
        }
    }

    // was VFP core/access MixinChunkTracker#viaFabricPlus$getSubChunkRequests (interface impl over @Shadow @Final
    // private Set subChunkRequests). Bedrock targets only, no version gate upstream, and the mixin body is exactly
    // subChunkRequests.size() - reading the same Set reflectively and sizing it yields the same number.
    public static int subChunkRequests(final ChunkTracker chunkTracker) {
        return size(read(Fields.CHUNK_TRACKER_SUB_CHUNK_REQUESTS, chunkTracker));
    }

    // was VFP core/access MixinChunkTracker#viaFabricPlus$getPendingSubChunks (interface impl over @Shadow @Final
    // private Set pendingSubChunks). Same shape as above.
    public static int pendingSubChunks(final ChunkTracker chunkTracker) {
        return size(read(Fields.CHUNK_TRACKER_PENDING_SUB_CHUNKS, chunkTracker));
    }

    // was VFP core/access MixinChunkTracker#viaFabricPlus$getChunks (interface impl over @Shadow @Final private
    // Long2ObjectMap<BedrockChunk> chunks). The relocated fastutil Long2ObjectMap extends java.util.Map, so the
    // value is sized through Map#size without this file depending on the relocation path.
    public static int chunks(final ChunkTracker chunkTracker) {
        return size(read(Fields.CHUNK_TRACKER_CHUNKS, chunkTracker));
    }

    // was VFP core/access MixinRakSessionCodec#viaFabricPlus$getOutgoingPackets (interface impl over @Shadow
    // private FastBinaryMinHeap<EncapsulatedPacket> outgoingPackets). Bedrock targets only, no version gate.
    // FastBinaryMinHeap#size is public, only the field is not, so the heap is fetched reflectively and sized.
    public static int outgoingPackets(final RakSessionCodec rakSessionCodec) {
        return size(read(Fields.RAK_SESSION_OUTGOING_PACKETS, rakSessionCodec));
    }

    // was VFP core/access MixinRakSessionCodec#viaFabricPlus$SentDatagrams (interface impl over @Shadow private
    // IntObjectMap<RakDatagramPacket> sentDatagrams). netty IntObjectMap extends java.util.Map, so Map#size is the
    // same read the mixin body does.
    public static int sentDatagrams(final RakSessionCodec rakSessionCodec) {
        return size(read(Fields.RAK_SESSION_SENT_DATAGRAMS, rakSessionCodec));
    }

    // was VFP core/access MixinExtensionProtocolMetadataStorage#viaFabricPlus$getServerExtensions (interface impl
    // over @Shadow @Final private EnumMap<ClassicProtocolExtension, Integer> serverExtensions). c0_30cpe only, no
    // version gate - the caller checks user.has(ExtensionProtocolMetadataStorage.class). The live map is returned,
    // not a copy, exactly like the mixin.
    //
    // Rebuilding the map from the public API was rejected rather than overlooked: the EXT_ENTRY handler in
    // Protocolc0_30cpeToc0_28_30 calls addServerExtension(ClassicProtocolExtension.byName(name), rawServerVersion)
    // with no filtering, so a stored version can be one ViaLegacy does not implement, and probing
    // hasServerExtension(extension, version) over ClassicProtocolExtension#getSupportedVersions would silently
    // drop exactly those entries that upstream prints.
    public static @Nullable Map<ClassicProtocolExtension, Integer> serverExtensions(final ExtensionProtocolMetadataStorage storage) {
        if (!(read(Fields.CPE_SERVER_EXTENSIONS, storage) instanceof EnumMap<?, ?> serverExtensions)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        final Map<ClassicProtocolExtension, Integer> typed = (Map<ClassicProtocolExtension, Integer>) serverExtensions;
        return typed;
    }

    private static @Nullable Field findField(final Class<?> owner, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null; // apply() turns the whole set of misses into one warning
        }
    }

    private static @Nullable Object read(final @Nullable Field field, final Object target) {
        if (field == null) {
            return null;
        }

        try {
            return field.get(target);
        } catch (final IllegalAccessException | RuntimeException e) {
            if (!readFailureLogged) {
                readFailureLogged = true; // these accessors run per rendered frame, so log this once
                ViaFabricPlusImpl.INSTANCE.getLogger()
                    .error("Failed to read {}.{}, the readouts it feeds stay hidden", field.getDeclaringClass().getSimpleName(), field.getName(), e);
            }
            return null;
        }
    }

    private static int size(final @Nullable Object container) {
        if (container instanceof Map<?, ?> map) { // ChunkTracker#chunks, RakSessionCodec#sentDatagrams
            return map.size();
        } else if (container instanceof Set<?> set) { // ChunkTracker#subChunkRequests, ChunkTracker#pendingSubChunks
            return set.size();
        } else if (container instanceof FastBinaryMinHeap<?> heap) { // RakSessionCodec#outgoingPackets
            return heap.size();
        } else {
            // Also the null case: RakSessionCodec clears outgoingPackets and sentDatagrams when the session closes
            // (javap shows aconst_null putfield for both), where the upstream this.field.size() would throw.
            return UNAVAILABLE;
        }
    }

}
