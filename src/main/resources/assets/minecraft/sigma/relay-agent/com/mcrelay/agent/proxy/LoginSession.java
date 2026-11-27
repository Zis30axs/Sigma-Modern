package com.mcrelay.agent.proxy;

import io.netty.buffer.ByteBuf;

/**
 * B 侧登录流程。每个协议世代一份完整实现，互不共用。
 *
 * 为什么每个版本单独写而不是抽公共逻辑：不同世代的握手流程差别是结构性的
 * （1.20.2 起 login 和 play 之间多了 configuration 阶段），硬抽成一份会变成
 * 满是 if(protocol >= 764) 的代码，改一处坏一片。宁可重复几行发压缩包的代码，
 * 也要让每个版本的流程能独立读、独立改。
 *
 * ClientHandler 只负责收发和状态派发，具体流程全在实现类里。
 */
public interface LoginSession {

    /** 是否已进入 play 态（决定 B 的上行包是否转发给真服务器）。 */
    boolean inPlay();

    /**
     * 处理 B 的一个上行包。
     * @param packetId 包 ID（已读出，buf 的 readerIndex 指向后续字段）
     * @param buf      包体
     */
    void handle(int packetId, ByteBuf buf) throws Exception;
}
