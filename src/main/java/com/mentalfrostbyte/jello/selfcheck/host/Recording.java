package com.mentalfrostbyte.jello.selfcheck.host;

import com.mentalfrostbyte.jello.selfcheck.api.Direction;
import io.netty.buffer.ByteBuf;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A connection's wire traffic, as the session saw it, in the order it saw it: both directions, the ends of read
 * batches, and where transactions went in and came back. Replaying one into an engine needs no game at all,
 * which is what makes transaction ordering testable.
 *
 * <pre>
 * header:  int magic "SGSC", int format version, int protocol, UTF protocol name
 * event:   byte kind, long nanoTime, then
 *          CLIENTBOUND / SERVERBOUND        int length, bytes (packet id varint first)
 *          BATCH_END                        -
 *          TX_SENT / TX_ACK / TX_DROP       int id  (an engine's transaction)
 *          MARK_SENT / MARK_ACK / MARK_DROP int id  (format 2: the session's own marker)
 * </pre>
 *
 * <p>Format 2 recordings carry a marker transaction behind every server packet, sent by the session itself while it
 * records. Where the client answered the marker behind packet <i>i</i> is exactly where it had finished handling
 * <i>i</i>, which is what {@link Replay} needs to answer an engine's transactions where the client would have.</p>
 */
public final class Recording {

    public static final int MAGIC = 0x53475343;
    public static final int FORMAT = 2;

    public enum Kind {
        CLIENTBOUND, SERVERBOUND, BATCH_END, TX_SENT, TX_ACK, TX_DROP, MARK_SENT, MARK_ACK, MARK_DROP;

        private static final Kind[] VALUES = values();
    }

    /** One recorded event; {@code bytes} for packets, {@code id} for transactions. */
    public record Event(Kind kind, long nanoTime, int id, byte @Nullable [] bytes) {
    }

    public record Header(int format, int protocol, String protocolName) {

        /** Whether the recording carries a marker behind every server packet (format 2 on). */
        public boolean hasMarkers() {
            return this.format >= 2;
        }
    }

    private Recording() {
    }

    /** Writes a recording. Used from the network thread only. */
    public static final class Writer implements AutoCloseable {

        private final DataOutputStream out;

        public Writer(final Path file, final int protocol, final String protocolName) throws IOException {
            this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
            this.out.writeInt(MAGIC);
            this.out.writeInt(FORMAT);
            this.out.writeInt(protocol);
            this.out.writeUTF(protocolName);
        }

        /** Records the readable bytes of {@code buffer} without moving its reader index. */
        public void packet(final Direction direction, final long nanoTime, final ByteBuf buffer) throws IOException {
            this.out.writeByte((direction == Direction.CLIENTBOUND ? Kind.CLIENTBOUND : Kind.SERVERBOUND).ordinal());
            this.out.writeLong(nanoTime);
            int length = buffer.readableBytes();
            this.out.writeInt(length);
            buffer.getBytes(buffer.readerIndex(), this.out, length);
        }

        public void packet(final Direction direction, final long nanoTime, final byte[] bytes) throws IOException {
            this.out.writeByte((direction == Direction.CLIENTBOUND ? Kind.CLIENTBOUND : Kind.SERVERBOUND).ordinal());
            this.out.writeLong(nanoTime);
            this.out.writeInt(bytes.length);
            this.out.write(bytes);
        }

        public void batchEnd(final long nanoTime) throws IOException {
            this.out.writeByte(Kind.BATCH_END.ordinal());
            this.out.writeLong(nanoTime);
        }

        public void transaction(final Kind kind, final long nanoTime, final int id) throws IOException {
            if (kind.ordinal() < Kind.TX_SENT.ordinal()) {
                throw new IllegalArgumentException("not a transaction event: " + kind);
            }
            this.out.writeByte(kind.ordinal());
            this.out.writeLong(nanoTime);
            this.out.writeInt(id);
        }

        @Override
        public void close() throws IOException {
            this.out.close();
        }
    }

    /** Reads a recording back, event by event. */
    public static final class Reader implements AutoCloseable {

        private final DataInputStream in;
        private final Header header;

        public Reader(final Path file) throws IOException {
            this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
            if (this.in.readInt() != MAGIC) {
                this.in.close();
                throw new IOException(file + " is not a self-check recording");
            }
            int format = this.in.readInt();
            if (format < 1 || format > FORMAT) {
                this.in.close();
                throw new IOException(file + " uses recording format " + format + ", this build reads 1 to " + FORMAT);
            }
            this.header = new Header(format, this.in.readInt(), this.in.readUTF());
        }

        public Header header() {
            return this.header;
        }

        /** The next event, or null at the end. A recording cut off mid-event (the game crashed) ends there too. */
        public @Nullable Event next() throws IOException {
            int kindIndex;
            try {
                kindIndex = this.in.readUnsignedByte();
            } catch (EOFException end) {
                return null;
            }
            if (kindIndex >= Kind.VALUES.length) {
                throw new IOException("unknown event kind " + kindIndex);
            }
            Kind kind = Kind.VALUES[kindIndex];
            try {
                long nanoTime = this.in.readLong();
                return switch (kind) {
                    case CLIENTBOUND, SERVERBOUND -> {
                        byte[] bytes = new byte[this.in.readInt()];
                        this.in.readFully(bytes);
                        yield new Event(kind, nanoTime, 0, bytes);
                    }
                    case BATCH_END -> new Event(kind, nanoTime, 0, null);
                    case TX_SENT, TX_ACK, TX_DROP, MARK_SENT, MARK_ACK, MARK_DROP -> new Event(kind, nanoTime, this.in.readInt(), null);
                };
            } catch (EOFException truncated) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            this.in.close();
        }
    }
}
