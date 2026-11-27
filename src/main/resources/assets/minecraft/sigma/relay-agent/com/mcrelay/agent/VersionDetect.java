package com.mcrelay.agent;

import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;

/**
 * 版本 / 加载器检测。
 *
 * v2: 使用类型匹配获取 MC 实例，尝试 SharedConstants 读取精确版本。
 */
public final class VersionDetect {

    public enum Loader { FORGE, NEOFORGE, FABRIC, QUILT, VANILLA, UNKNOWN }

    public static class Info {
        public final String version;
        public final Loader loader;
        /** A 的协议号；读不到为 -1。比版本字符串可靠，优先用它选适配器。 */
        public final int protocol;
        public Info(String v, Loader l) { this(v, l, -1); }
        public Info(String v, Loader l, int p) { this.version = v; this.loader = l; this.protocol = p; }
        @Override public String toString() {
            return "MC " + version + " / " + loader + (protocol > 0 ? " (protocol " + protocol + ")" : "");
        }
    }

    /** 只判断版本号形状，挡掉把整个结构体 toString 当版本号的情况。 */
    private static boolean looksLikeVersion(String s) {
        return s != null && s.matches("\\d+\\.\\d+(\\.\\d+)?(-.*)?");
    }

    /**
     * 读 SharedConstants 里的协议号。
     *
     * 版本字符串在不同版本/加载器下花样太多（record 的 toString、Forge 的自定义格式），
     * 协议号是个纯 int，直接可信。
     */
    private static int detectProtocol() {
        // 1) 1.8 - 1.13.2 (含原版混淆/Forge/OptiFine/Lunar/AuthCl): RealmsSharedConstants 从不混淆
        try {
            Class<?> rsc = Reflect.tryCls("net.minecraft.realms.RealmsSharedConstants");
            if (rsc != null) {
                java.lang.reflect.Field f = rsc.getDeclaredField("NETWORK_PROTOCOL_VERSION");
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof Integer && (Integer) v > 0) {
                    McLog.info("VersionDetect: RealmsSharedConstants 协议号=" + v);
                    return (Integer) v;
                }
            }
        } catch (Throwable ignored) {}

        // 2) 1.14+ SharedConstants (Mojmap/Forge: net.minecraft.SharedConstants; Fabric: net.minecraft.class_155)
        for (String scName : new String[]{"net.minecraft.SharedConstants", "net.minecraft.class_155"}) {
            try {
                Class<?> sc = Reflect.tryCls(scName);
                if (sc == null) continue;
                // Fabric/Mojmap 静态 getProtocolVersion(): ()I
                for (String mName : new String[]{"getProtocolVersion", "m_142972_", "method_16673"}) {
                    try {
                        Object pv = Reflect.invokeStatic(sc, mName);
                        if (pv instanceof Integer && (Integer) pv > 0) {
                            McLog.info("VersionDetect: " + scName + "." + mName + " 协议号=" + pv);
                            return (Integer) pv;
                        }
                    } catch (Throwable ignored) {}
                }
                Object wv = null;
                for (String n : new String[]{"getCurrentVersion", "getVersion", "m_215274_", "m_183709_", "method_16672"}) {
                    try { wv = Reflect.invokeStatic(sc, n); if (wv != null) break; } catch (Throwable ignored) {}
                }
                if (wv == null) continue;
                for (String n : new String[]{"getProtocolVersion", "protocolVersion", "m_183476_", "method_48019"}) {
                    Object v = Reflect.invokeAnyTyped(wv, Integer.class, new String[]{n});
                    if (v instanceof Integer && (Integer) v > 0) {
                        McLog.info("VersionDetect: 协议号=" + v);
                        return (Integer) v;
                    }
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("protocolVersion=(\\d+)").matcher(String.valueOf(wv));
                if (m.find()) {
                    int p = Integer.parseInt(m.group(1));
                    McLog.info("VersionDetect: 从 toString 提取协议号=" + p);
                    return p;
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    public static Info detect() {
        Loader loader = Loader.UNKNOWN;
        try {
            if (Reflect.tryCls("net.neoforged.fml.loading.FMLLoader") != null
                || Reflect.tryCls("net.neoforged.neoforge.common.NeoForge") != null) {
                loader = Loader.NEOFORGE;
            } else if (Reflect.tryCls("net.minecraftforge.fml.loading.FMLLoader") != null
                || Reflect.tryCls("net.minecraftforge.fml.common.Loader") != null
                || Reflect.tryCls("net.minecraftforge.common.MinecraftForge") != null
                || Reflect.tryCls("cpw.mods.fml.common.Loader") != null) {
                loader = Loader.FORGE;
            } else if (Reflect.tryCls("org.quiltmc.loader.impl.QuiltLoaderImpl") != null) {
                loader = Loader.QUILT;
            } else if (Reflect.tryCls("net.fabricmc.loader.impl.FabricLoaderImpl") != null
                || Reflect.tryCls("net.fabricmc.loader.api.FabricLoader") != null) {
                loader = Loader.FABRIC;
            } else {
                try {
                    if (Reflect.findMinecraftClass() != null) loader = Loader.VANILLA;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        String version = "unknown";

        // 策略 0: 1.8 - 1.13.2 RealmsSharedConstants.VERSION_STRING
        try {
            Class<?> rsc = Reflect.tryCls("net.minecraft.realms.RealmsSharedConstants");
            if (rsc != null) {
                java.lang.reflect.Field vf = rsc.getDeclaredField("VERSION_STRING");
                vf.setAccessible(true);
                Object vs = vf.get(null);
                if (vs instanceof String && looksLikeVersion((String) vs)) {
                    version = (String) vs;
                }
            }
        } catch (Throwable ignored) {}

        // 策略 1: Forge / NeoForge (含 1.8.9/1.12.2 Loader.MC_VERSION & 1.13+ FMLLoader)
        if ("unknown".equals(version) && (loader == Loader.FORGE || loader == Loader.NEOFORGE)) {
            version = tryForgeVersion();
        }

        // 策略 1b: Fabric Loader ModContainer("minecraft")
        if ("unknown".equals(version) && (loader == Loader.FABRIC || loader == Loader.QUILT)) {
            version = tryFabricVersion();
        }

        // 策略 2: SharedConstants.getCurrentVersion().getName()
        if ("unknown".equals(version)) {
            version = trySharedConstants();
        }

        // 策略 3: 从 launchedVersion 字段
        if ("unknown".equals(version)) {
            version = guessFromBrand();
        }

        // 策略 4: 通过已加载类推断
        if ("unknown".equals(version)) {
            version = guessFromLoadedClasses();
        }

        int protocol = detectProtocol();
        if (protocol <= 0) {
            protocol = protocolOfVersion(version);
            if (protocol > 0) {
                McLog.info("VersionDetect: 协议号读不到，按版本 " + version + " 推断=" + protocol);
            }
        }
        return new Info(version, loader, protocol);
    }

    private static int protocolOfVersion(String v) {
        if (v == null) return -1;
        String clean = v.trim();
        // 提取前缀纯版本号（例如 "1.8.9-OptiFine_HD_U_M5" -> "1.8.9"）
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(clean);
        if (m.find()) clean = m.group(1);
        switch (clean) {
            case "1.20":    case "1.20.1": return 763;
            case "1.20.2":                 return 764;
            case "1.20.3":  case "1.20.4": return 765;
            case "1.20.5":  case "1.20.6": return 766;
            case "1.21":    case "1.21.1": return 767;
            case "1.21.2":  case "1.21.3": return 768;
            case "1.21.4":                 return 769;
            case "1.21.5":                 return 770;
            case "1.21.6":                 return 771;
            case "1.21.7":  case "1.21.8": return 772;
            case "1.21.9":  case "1.21.10": return 773;
            case "1.21.11":                return 774;
            case "26.1":    case "26.1.1": case "26.1.2": return 775;
            case "26.2":                   return 776;
            case "1.19.4":                 return 762;
            case "1.19.3":                 return 761;
            case "1.19.1":  case "1.19.2": return 760;
            case "1.19":                   return 759;
            case "1.18.2":                 return 758;
            case "1.18":    case "1.18.1": return 757;
            case "1.17.1":                 return 756;
            case "1.17":                   return 755;
            case "1.16.4":  case "1.16.5": return 754;
            case "1.16.3":                 return 753;
            case "1.16.2":                 return 751;
            case "1.16.1":                 return 736;
            case "1.16":                   return 735;
            case "1.15.2":                 return 578;
            case "1.15.1":                 return 575;
            case "1.15":                   return 573;
            case "1.14.4":                 return 498;
            case "1.14.3":                 return 490;
            case "1.14.2":                 return 485;
            case "1.14.1":                 return 480;
            case "1.14":                   return 477;
            case "1.13.2":                 return 404;
            case "1.13.1":                 return 401;
            case "1.13":                   return 393;
            case "1.12.2":                 return 340;
            case "1.12.1":                 return 338;
            case "1.12":                   return 335;
            case "1.11.1":  case "1.11.2": return 316;
            case "1.11":                   return 315;
            case "1.10":    case "1.10.1": case "1.10.2": return 210;
            case "1.9.3":   case "1.9.4":  return 110;
            case "1.9.2":                  return 109;
            case "1.9.1":                  return 108;
            case "1.9":                    return 107;
            case "1.8": case "1.8.1": case "1.8.2": case "1.8.3": case "1.8.4":
            case "1.8.5": case "1.8.6": case "1.8.7": case "1.8.8": case "1.8.9":
                return 47;
            default:
                McLog.warn("VersionDetect: 认不出版本 " + v + " 的协议号，返回 -1");
                return -1;
        }
    }

    private static String tryForgeVersion() {
        // 1.8.9 - 1.12.2 Forge: Loader.MC_VERSION / MinecraftForge.MC_VERSION
        for (String cn : new String[]{
                "net.minecraftforge.fml.common.Loader",
                "net.minecraftforge.common.MinecraftForge",
                "cpw.mods.fml.common.Loader"}) {
            try {
                Class<?> c = Reflect.tryCls(cn);
                if (c == null) continue;
                java.lang.reflect.Field f = c.getDeclaredField("MC_VERSION");
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof String && looksLikeVersion((String) v)) {
                    McLog.info("VersionDetect: 从 " + c.getSimpleName() + ".MC_VERSION 读到版本=" + v);
                    return (String) v;
                }
            } catch (Throwable ignored) {}
        }
        // 1.13+ Forge / NeoForge FMLLoader
        for (String cn : new String[]{
                "net.neoforged.fml.loading.FMLLoader",
                "net.minecraftforge.fml.loading.FMLLoader"}) {
            try {
                Class<?> fmlLoader = Reflect.tryCls(cn);
                if (fmlLoader == null) continue;
                for (String name : new String[]{"getMCVersionString", "versionInfo", "m_2910_"}) {
                    try {
                        Object result = Reflect.invokeStatic(fmlLoader, name);
                        if (result instanceof String && looksLikeVersion((String) result)) {
                            McLog.info("VersionDetect: 从 " + fmlLoader.getSimpleName() + " 读到版本=" + result);
                            return (String) result;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    private static String tryFabricVersion() {
        try {
            Class<?> fl = Reflect.tryCls("net.fabricmc.loader.api.FabricLoader");
            if (fl == null) return "unknown";
            Object inst = Reflect.invokeStatic(fl, "getInstance");
            if (inst == null) return "unknown";
            Object opt = Reflect.invoke(inst, "getModContainer", "minecraft");
            if (opt instanceof java.util.Optional && ((java.util.Optional<?>) opt).isPresent()) {
                Object mcMod = ((java.util.Optional<?>) opt).get();
                Object meta = mcMod.getClass().getMethod("getMetadata").invoke(mcMod);
                Object ver = meta.getClass().getMethod("getVersion").invoke(meta);
                Object friendly = ver.getClass().getMethod("getFriendlyString").invoke(ver);
                if (friendly instanceof String && looksLikeVersion((String) friendly)) {
                    McLog.info("VersionDetect: FabricLoader 版本=" + friendly);
                    return (String) friendly;
                }
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static String trySharedConstants() {
        for (String scName : new String[]{"net.minecraft.SharedConstants", "net.minecraft.class_155"}) {
            try {
                Class<?> sc = Reflect.tryCls(scName);
                if (sc == null) continue;

                Object worldVersion = null;
                for (String name : new String[]{"getCurrentVersion", "getVersion", "m_215274_", "m_183709_", "method_16672"}) {
                    try {
                        worldVersion = Reflect.invokeStatic(sc, name);
                        if (worldVersion != null) break;
                    } catch (Throwable ignored) {}
                }

                if (worldVersion == null) continue;

                for (String name : new String[]{"getName", "name", "getId", "id", "m_215279_", "method_48018"}) {
                    try {
                        Object result = Reflect.invoke(worldVersion, name);
                        if (result instanceof String && looksLikeVersion((String) result)) {
                            McLog.info("VersionDetect: SharedConstants 版本=" + result);
                            return (String) result;
                        }
                    } catch (Throwable ignored) {}
                }

                String str = String.valueOf(worldVersion);
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:id|name)=(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(str);
                if (m.find()) {
                    McLog.info("VersionDetect: 从 toString 提取版本=" + m.group(1));
                    return m.group(1);
                }
                m = java.util.regex.Pattern.compile("\\b(\\d+\\.\\d+(?:\\.\\d+)?)\\b").matcher(str);
                if (m.find()) {
                    McLog.info("VersionDetect: 从 toString 兜底提取版本=" + m.group(1));
                    return m.group(1);
                }
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    private static String guessFromBrand() {
        try {
            Object mc = Reflect.findMinecraftInstance();
            if (mc != null) {
                Object brand = Reflect.fieldAny(mc, "launchedVersion", "f_90983_", "field_110447_Z", "field_1739");
                if (brand instanceof String) return (String) brand;
                Object gv = Reflect.invokeAny(mc, new String[]{"getVersion", "func_175600_c", "method_1515"});
                if (gv instanceof String) return (String) gv;
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static String guessFromLoadedClasses() {
        // 1.20.2+ 特征：配置阶段协议包
        if (Reflect.tryCls("net.minecraft.network.protocol.configuration.ServerboundConfigurationPacket") != null ||
            Reflect.tryCls("net.minecraft.network.protocol.configuration.ClientboundConfigurationPacket") != null) {
            return "1.20.2+";
        }

        // 1.20.0/1.20.1 特征：SmithingTemplateItem (1.20 新增)
        if (Reflect.tryCls("net.minecraft.world.item.SmithingTemplateItem") != null) {
            return "1.20.1";
        }

        // 1.19.3+ 特征：PlayerInfoUpdatePacket
        if (Reflect.tryCls("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket") != null) {
            return "1.19.3+";
        }

        // 1.12.2 特征
        if (Reflect.tryCls("net.minecraft.client.network.NetHandlerPlayClient") != null ||
            Reflect.tryCls("net.minecraft.client.multiplayer.NetHandlerPlayClient") != null) {
            return "1.12.x";
        }

        return "unknown";
    }
}
