package com.mcrelay.agent.adapter;

import com.mcrelay.agent.VersionDetect;
import com.mcrelay.agent.util.McLog;

/**
 * 按检测到的版本挑选适配器。
 *
 * 加载器（Forge / NeoForge / Fabric / 原版）不单独分适配器：
 * 三家的差别只在方法名混淆方式上（SRG / intermediary / 官方名），而 PacketCodec 和
 * Reflect 全部按"类型 + 签名"定位成员，不认名字，所以同一个适配器四家通用。
 *
 * 真正需要分适配器的是**协议世代**：
 *   1.8 – 1.12.2   PacketBuffer、writePacketData、JoinGame 无注册表、玩家/生物分开的 spawn 包
 *   1.13 – 1.20.1  FriendlyByteBuf、write(buf)、JoinGame 带注册表 NBT、AddPlayer 独立
 *   1.20.2+        configuration 阶段、StreamCodec、AddPlayer 并入 AddEntity
 */
public final class AdapterRegistry {

    private AdapterRegistry() {}

    public static RelayAdapter forVersion(VersionDetect.Info info) {
        // 优先用协议号 —— 版本字符串在不同版本/加载器下格式不一，
        // 实测 1.21.8 会拿到整个 record 的 toString，正则认不出来就误判成 1.20.1。
        int protocol = info.protocol > 0 ? info.protocol : protocolOf(info.version);
        McLog.info("AdapterRegistry: version=" + info.version + " loader=" + info.loader +
            " protocol=" + protocol + (info.protocol > 0 ? "（来自 SharedConstants）" : "（由版本号推断）"));

        if (protocol >= 393) {
            // 1.13 – 26.2 (393 – 776) 走 ModernAdapter
            return new ModernAdapter();
        }
        // 1.8.x – 1.12.2 (47 – 340) 走 LegacyAdapter
        McLog.info("AdapterRegistry: 使用 LegacyAdapter (protocol=" + protocol + ")");
        return new LegacyAdapter(protocol);
    }

    /** 版本号 → 协议号。只需要粗分世代，同一世代内取该世代起始协议号即可。 */
    private static int protocolOf(String version) {
        if (version == null) return 763;
        String v = version.trim();
        if (v.startsWith("26."))   return v.startsWith("26.1") ? 775 : 776;
        if (v.startsWith("1.20"))  return v.equals("1.20") || v.startsWith("1.20.1") ? 763 : 764;
        if (v.startsWith("1.21"))  return 767;
        if (v.startsWith("1.19"))  return 759;
        if (v.startsWith("1.18"))  return 757;
        if (v.startsWith("1.17"))  return 755;
        if (v.startsWith("1.16"))  return 735;
        if (v.startsWith("1.15"))  return 573;
        if (v.startsWith("1.14"))  return 477;
        if (v.startsWith("1.13"))  return 393;
        if (v.startsWith("1.12"))  return 335;
        if (v.startsWith("1.11"))  return 315;
        if (v.startsWith("1.10"))  return 210;
        if (v.startsWith("1.9"))   return 107;
        if (v.startsWith("1.8"))   return 47;
        McLog.warn("AdapterRegistry: 认不出版本 " + v + "，按 1.20.1 处理");
        return 763;
    }
}
