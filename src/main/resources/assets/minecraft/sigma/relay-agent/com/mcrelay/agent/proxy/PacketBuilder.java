package com.mcrelay.agent.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.UUID;

/**
 * 构造发往 B 的服务端包（payload = [包ID VarInt][字段...]）。
 * ClientHandler 把 payload 直接 writeAndFlush，由 Compressor+FrameEncoder 组装成帧。
 *
 * 协议号 763 (1.20.1)。登录态包 ID 跨版本较稳定；Play 态包由适配器构造。
 */
public final class PacketBuilder {

    // ===== Login 态 (S→C) =====
    public static final int LOGIN_DISCONNECT = 0x00;
    public static final int LOGIN_ENCRYPTION_REQUEST = 0x01;
    public static final int LOGIN_SUCCESS = 0x02;
    public static final int LOGIN_SET_COMPRESSION = 0x03;

    // ===== Login 态 (C→S) =====
    public static final int C2S_HANDSHAKE = 0x00;
    public static final int C2S_LOGIN_START = 0x00;

    // ===== Status 态 =====
    public static final int C2S_STATUS_REQUEST = 0x00;
    public static final int C2S_STATUS_PING = 0x01;
    public static final int S2C_STATUS_RESPONSE = 0x00;
    public static final int S2C_STATUS_PONG = 0x01;

    /**
     * Login Success (0x02)。
     *
     * 【CONFIRMED 1.20.1】1.19.4+（协议 762+）Login Success 的 UUID 字段从 String(36) 改为二进制 UUID(16)。
     * 1.20.1（协议 763）继承此格式。用 String 格式会导致 B 客户端解析错位，报 "Packet was larger than I expected"。
     */
    public static final boolean LOGIN_SUCCESS_UUID_AS_STRING = false;

    public static ByteBuf loginSuccess(UUID uuid, String username) {
        return loginSuccess(uuid, username, java.util.Collections.<String[]>emptyList(), 763);
    }

    public static ByteBuf loginSuccess(UUID uuid, String username, int clientProtocol) {
        return loginSuccess(uuid, username, java.util.Collections.<String[]>emptyList(), clientProtocol);
    }

    public static ByteBuf loginSuccess(UUID uuid, String username, java.util.List<String[]> properties, int clientProtocol) {
        ByteBuf b = Unpooled.buffer();
        McFraming.writeVarInt(b, LOGIN_SUCCESS);
        if (LOGIN_SUCCESS_UUID_AS_STRING || (clientProtocol > 0 && clientProtocol < 735)) {
            McFraming.writeString(b, uuid.toString());
        } else {
            McFraming.writeUuid(b, uuid);
        }
        McFraming.writeString(b, username);
        // Properties array was added to LoginSuccess in 1.19 (protocol 759)
        if (clientProtocol <= 0 || clientProtocol >= 759) {
            int propCount = properties != null ? properties.size() : 0;
            McFraming.writeVarInt(b, propCount);
            for (int i = 0; i < propCount; i++) {
                String[] prop = properties.get(i);
                McFraming.writeString(b, prop[0]);
                McFraming.writeString(b, prop[1]);
                boolean signed = prop.length > 2 && prop[2] != null && !prop[2].isEmpty();
                b.writeBoolean(signed);
                if (signed) {
                    McFraming.writeString(b, prop[2]);
                }
            }
        }
        if (clientProtocol == 766 || clientProtocol == 767) {
            // 1.20.5 - 1.21.1 had trailing boolean strictErrorHandling
            b.writeBoolean(false);
        } else if (clientProtocol >= 776) {
            // 26.2+ added UUID sessionId
            McFraming.writeUuid(b, UUID.randomUUID());
        }
        return b;
    }

    /** Set Compression (0x03)：threshold >=0 启用压缩。 */
    public static ByteBuf setCompression(int threshold) {
        ByteBuf b = Unpooled.buffer();
        McFraming.writeVarInt(b, LOGIN_SET_COMPRESSION);
        McFraming.writeVarInt(b, threshold);
        return b;
    }

    /** Login Disconnect (0x00)：reason 为 JSON 文本。 */
    public static ByteBuf loginDisconnect(String jsonReason) {
        ByteBuf b = Unpooled.buffer();
        McFraming.writeVarInt(b, LOGIN_DISCONNECT);
        McFraming.writeString(b, jsonReason);
        return b;
    }

    /**
     * Status Response (0x00)：服务器列表信息。
     *
     * 版本名和协议号跟着 A 的实际版本走 —— 写死 1.20.1 的话，B 的服务器列表会一直
     * 显示"需要 1.20.1"，用 ViaVersion 的人还会误以为翻译没生效。
     */
    public static ByteBuf statusResponse() {
        String versionName = "1.20.1";
        int protocol = 763;
        try {
            com.mcrelay.agent.VersionDetect.Info info = com.mcrelay.agent.VersionDetect.detect();
            if (info.version != null && info.version.matches("\\d+\\.\\d+(\\.\\d+)?")) {
                versionName = info.version;
            }
            if (info.protocol > 0) protocol = info.protocol;
        } catch (Throwable ignored) {}

        String json = "{\"version\":{\"name\":\"" + versionName + "\",\"protocol\":" + protocol
            + "},\"players\":{\"max\":1,\"online\":1,\"sample\":[]},\"description\":{\"text\":\"MC-Relay Proxy\"},"
            + "\"enforcesSecureChat\":false}";
        ByteBuf b = Unpooled.buffer();
        McFraming.writeVarInt(b, S2C_STATUS_RESPONSE);
        McFraming.writeString(b, json);
        return b;
    }

    /** Status Pong (0x01)。 */
    public static ByteBuf statusPong(long payload) {
        ByteBuf b = Unpooled.buffer();
        McFraming.writeVarInt(b, S2C_STATUS_PONG);
        b.writeLong(payload);
        return b;
    }

    /**
     * 用离线模式算法由玩家名生成 UUID（与 vanilla 服务端一致）。
     * UUID = md5("OfflinePlayer:" + name) 处理后。
     */
    public static UUID offlineUuid(String name) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            md.update(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] d = md.digest();
            d[6] &= 0x0f; d[6] |= 0x30; // version 3
            d[8] &= 0x3f; d[8] |= 0x80;
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (d[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (d[i] & 0xff);
            return new UUID(msb, lsb);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        }
    }

    private PacketBuilder() {}
}
