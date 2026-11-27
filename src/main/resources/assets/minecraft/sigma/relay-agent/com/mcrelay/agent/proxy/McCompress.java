package com.mcrelay.agent.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minecraft 压缩编解码（1.20.1 及前后通用）。
 *
 * 帧内格式：[Data Length VarInt][payload]
 *  - threshold < 0：未启用压缩，Data Length 恒为 0，payload 原样（透传）。
 *  - 启用后：若 payload 长度 >= threshold → Data Length = 原长，payload = zlib 压缩；
 *           否则 Data Length = 0，payload 原样。
 *
 * 用 volatile threshold 实现"先以 -1 透传，收到 SetCompression 后切到真实阈值"，
 * 避免运行时改管道带来的顺序问题。
 *
 * Deflater/Inflater 绝不能跨调用复用：写 B 的有两条线程（Netty eventLoop 转发
 * 服务器实时包、mc-relay-init 线程重建世界），共享一个 Deflater 会让
 * setInput/deflate/reset 交错执行，压缩流互相污染。实测症状是 B 那边
 * ViaVersion 报 "Packet was larger than I expected"、实体 UUID 位置读到
 * "minecraft:brand" 这种字符串 —— 包边界全乱了，于是区块不渲染。
 * 每次 encode/decode 新建一个，用完立刻 end() 释放本地内存。
 */
public final class McCompress {

    public static class Decompressor extends ByteToMessageDecoder {
        private volatile int threshold = -1;

        public void setThreshold(int t) { this.threshold = t; }
        public int getThreshold() { return threshold; }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (threshold < 0) {
                // 未启用：直接透传整段（已是去掉外层长度后的载荷）
                out.add(in.readSlice(in.readableBytes()).retain());
                return;
            }
            int dataLen = McFraming.readVarInt(in);
            if (dataLen == 0) {
                out.add(in.readSlice(in.readableBytes()).retain());
                return;
            }
            if (dataLen < threshold) {
                throw new IllegalStateException("压缩数据小于阈值: " + dataLen + " < " + threshold);
            }
            int compressedLen = in.readableBytes();
            byte[] compressed = new byte[compressedLen];
            in.readBytes(compressed);
            byte[] outBuf = new byte[dataLen];
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            // 同样要循环：单次 inflate 不保证解完整个流
            int total = 0;
            try {
                while (total < dataLen && !inflater.finished()) {
                    int n = inflater.inflate(outBuf, total, dataLen - total);
                    if (n <= 0) break;
                    total += n;
                }
            } catch (Exception e) {
                throw new RuntimeException("解压失败", e);
            } finally {
                inflater.end();
            }
            if (total != dataLen) {
                throw new IllegalStateException("解压长度不匹配: " + total + " != " + dataLen);
            }
            out.add(Unpooled.wrappedBuffer(outBuf));
        }
    }

    public static class Compressor extends MessageToByteEncoder<ByteBuf> {
        private volatile int threshold = -1;

        public void setThreshold(int t) { this.threshold = t; }
        public int getThreshold() { return threshold; }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            int len = msg.readableBytes();
            if (threshold < 0) {
                // 压缩未启用：直接透传，不加任何前缀
                out.writeBytes(msg);
                return;
            }
            if (len < threshold) {
                // 压缩已启用但包太小：Data Length = 0 表示未压缩
                McFraming.writeVarInt(out, 0);
                out.writeBytes(msg);
                return;
            }
            byte[] data = new byte[len];
            msg.readBytes(data);
            McFraming.writeVarInt(out, len);

            // 必须循环 deflate 直到 finished()。
            // 原实现只调一次 deflate(buf, 0, len, SYNC_FLUSH)，而且输出缓冲区只有 len 字节：
            //  - 一次 deflate 不保证写完，区块包（10-50KB）几乎必然被截断
            //  - 压缩后比原文长时（小包/随机数据）直接静默溢出丢数据
            // 截断的 zlib 流会让 B 解压失败，表现为区块不渲染、角色不能动。
            Deflater deflater = new Deflater();
            try {
                deflater.setInput(data);
                deflater.finish();
                byte[] buf = new byte[8192];
                while (!deflater.finished()) {
                    int n = deflater.deflate(buf);
                    if (n <= 0) break;   // 防御：不产出也不结束时避免死循环
                    out.writeBytes(buf, 0, n);
                }
            } finally {
                deflater.end();
            }
        }
    }

    private McCompress() {}
}
