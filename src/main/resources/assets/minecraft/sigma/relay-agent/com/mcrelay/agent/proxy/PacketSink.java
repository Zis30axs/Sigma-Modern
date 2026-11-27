package com.mcrelay.agent.proxy;

import io.netty.buffer.ByteBuf;

/** 向 B 发送一个 payload（[包ID][字段...]），由 Compressor+FrameEncoder 组帧。 */
public interface PacketSink {
    void send(ByteBuf payload);
}
