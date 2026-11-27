package com.mcrelay.agent.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;

/**
 * Minecraft VarInt21 长度前缀组帧。
 *  - FrameDecoder: 读取 VarInt 长度，切出一个完整包载荷
 *  - FrameEncoder: 写入 VarInt(长度) + 载荷
 *
 * 注意：这里处理的是"外层长度"，不含压缩的 Data Length（那个在 McCompress 里）。
 */
public final class McFraming {

    @ChannelHandler.Sharable
    public static class FrameEncoder extends MessageToByteEncoder<ByteBuf> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            writeVarInt(out, msg.readableBytes());
            out.writeBytes(msg);
        }
    }

    public static class FrameDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            int len;
            try {
                len = readVarInt(in);
            } catch (VarIntTooBigException e) {
                in.resetReaderIndex();
                return;
            }
            if (len < 0) {
                throw new DecoderException("负的包长度: " + len);
            }
            if (in.readableBytes() < len) {
                in.resetReaderIndex();
                return;
            }
            out.add(in.readSlice(len).retain());
        }
    }

    // ---- VarInt 读写工具（被本包多处复用） ----

    public static int readVarInt(ByteBuf in) {
        int numRead;
        int result = 0;
        int read;
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                throw new VarIntTooBigException("not enough bytes");
            }
            read = in.readByte();
            numRead = read & 0x7F;
            result |= (numRead << (7 * i));
            if ((read & 0x80) == 0) return result;
        }
        throw new VarIntTooBigException("VarInt too big");
    }

    public static void writeVarInt(ByteBuf out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static int varIntSize(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) { value >>>= 7; n++; }
        return n;
    }

    public static String readString(ByteBuf in, int maxLen) {
        int len = readVarInt(in);
        if (len > maxLen * 4) throw new DecoderException("字符串过长: " + len);
        byte[] b = new byte[len];
        in.readBytes(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf out, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.writeBytes(b);
    }

    public static java.util.UUID readUuid(ByteBuf in) {
        return new java.util.UUID(in.readLong(), in.readLong());
    }

    public static void writeUuid(ByteBuf out, java.util.UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    static final class VarIntTooBigException extends RuntimeException {
        VarIntTooBigException(String m) { super(m); }
    }

    static final class DecoderException extends RuntimeException {
        DecoderException(String m) { super(m); }
    }

    private McFraming() {}
}
