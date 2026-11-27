package com.mcrelay.agent.adapter;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * 版本相关适配器：读取被注入客户端 A 的当前状态，为 B 构造进入 Play 态所需的初始包。
 *
 * 调用顺序（由 ClientHandler 编排）：
 *  1) readProfile()                          —— 取玩家档案（用于 Login Success）
 *  2) buildJoinGame()                        —— 构造 JoinGame；失败则 ClientHandler 在 LOGIN 态发 Disconnect
 *  3) sendRemainingInitialPackets(sender)    —— JoinGame 之后的 PlayerInfo/区块/实体等（可异步）
 */
public interface RelayAdapter {

    PlayerProfile readProfile();

    /** 构造 JoinGame 载荷。失败抛异常，由调用方在 LOGIN 态发 Disconnect，避免状态错乱。 */
    ByteBuf buildJoinGame() throws Exception;

    /** 发送 JoinGame 之后的其余初始包。可在任意线程调用。 */
    void sendRemainingInitialPackets(PacketSender sender);

    /**
     * 发送 configuration 阶段的包（注册表、标签、资源包等）。
     *
     * 只有 1.20.2+（协议 764+）有这个阶段：注册表从 JoinGame 里挪到了这里。
     * 旧版本用不到，默认什么都不发。
     *
     * @return 发出的包数
     */
    default int sendConfigurationPackets(PacketSender sender) throws Exception {
        return 0;
    }

    /**
     * 告知 B 声明的协议号。
     *
     * 同一个适配器要面对不同协议的 B（1.20.1 的 B 和 1.21.8 的 B 都可能连进来），
     * 而 JoinGame 的字段结构随协议变，所以在构造包之前必须先知道对方是谁。
     * 默认空实现 —— 只关心单一版本的适配器不用管。
     */
    default void setClientProtocol(int protocol) {}

    interface PacketSender {
        void send(ByteBuf payload);
    }

    class PlayerProfile {
        public final String name;
        public final UUID uuid;
        public final int gamemode; // 0生存 1创造 2冒险 3旁观
        /** 每个元素为 {name, value, signatureOrNull}，完整继承宿主 AuthCl 的皮肤/披风与扩展属性。 */
        public final java.util.List<String[]> properties;
        public PlayerProfile(String name, UUID uuid, int gamemode) {
            this(name, uuid, gamemode, java.util.Collections.<String[]>emptyList());
        }
        public PlayerProfile(String name, UUID uuid, int gamemode, java.util.List<String[]> properties) {
            this.name = name;
            this.uuid = uuid;
            this.gamemode = gamemode;
            this.properties = properties != null ? properties : java.util.Collections.<String[]>emptyList();
        }
    }
}
