package com.mcrelay.agent.adapter;

import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
/**
 * 运行时包 ID 解析器：从 MC 的 ConnectionProtocol / PacketType 反射查找包 ID。
 *
 * 1.20.1 中，每个包类有静态 TYPE 字段（PacketType），PacketType.getId() 返回协议 ID。
 * 不同版本字段/方法名可能不同，这里用多路径尝试 + 硬编码回退。
 */
public final class PacketIdResolver {

    private static final Map<String, Integer> FALLBACK = new HashMap<>();
    private static boolean initialized = false;
    private static boolean useReflection = false;

    // 1.20.1 (协议 763) 硬编码回退 ID
    // 来源: PrismarineJS/minecraft-data data/pc/1.20/protocol.json (play.toClient)
    // 已验证: 1.20.1 继承 1.20 的协议定义，protocolVersion=763
    static {
        FALLBACK.put("ClientboundLoginPacket", 0x28);               // JoinGame (login)
        FALLBACK.put("ClientboundAddEntityPacket", 0x00);            // Spawn Entity (spawn_entity)
        FALLBACK.put("ClientboundLevelChunkWithLightPacket", 0x24); // Chunk Data + Light (map_chunk)
        FALLBACK.put("ClientboundPlayerInfoUpdatePacket", 0x3A);    // Player Info Update (player_info)
        FALLBACK.put("ClientboundPlayerPositionPacket", 0x3C);      // Sync Player Position (position)
        FALLBACK.put("ClientboundSetEntityDataPacket", 0x58);       // Set Entity Metadata (entity_metadata)
        FALLBACK.put("ClientboundSetEntityMotionPacket", 0x5A);     // Set Entity Velocity (entity_velocity)
        FALLBACK.put("ClientboundRemoveEntitiesPacket", 0x42);      // Remove Entities (remove_entities)
        FALLBACK.put("ClientboundKeepAlivePacket", 0x23);           // Keep Alive (keep_alive)
        FALLBACK.put("ClientboundSetEquipmentPacket", 0x5B);        // Set Equipment (entity_equipment_update? — approx)
        FALLBACK.put("ClientboundUpdateAttributesPacket", 0x75);    // Update Attributes (entity_properties)
        FALLBACK.put("ClientboundRotateHeadPacket", 0x48);          // Set Head Rotation (entity_head_look)
        FALLBACK.put("ClientboundSetPassengersPacket", 0x60);       // Set Passengers (passengers)
    }

    private PacketIdResolver() {}

    /**
     * 尝试初始化：检测运行时反射是否可用。
     * 如果 PacketType.getId() 反射成功，后续用反射查找；否则用硬编码回退。
     */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            // 尝试找到一个包的 TYPE.getId()
            int testId = resolveByReflection("net.minecraft.network.protocol.game.ClientboundKeepAlivePacket");
            if (testId >= 0) {
                useReflection = true;
                McLog.info("PacketIdResolver: 运行时反射可用 (KeepAlive ID=" + testId + ")");
            }
        } catch (Throwable t) {
            McLog.warn("PacketIdResolver: 运行时反射不可用，使用硬编码回退 ID: " + t.getMessage());
        }
    }

    /**
     * 按包类全名解析包 ID。
     * @param fullClassName 包的全限定名，如 "net.minecraft.network.protocol.game.ClientboundLoginPacket"
     * @return 包 ID，或 -1 表示无法解析
     */
    public static int resolve(String fullClassName) {
        if (!initialized) init();

        // 路径 0: 从 ConnectionProtocol.PLAY 的 classToId 表直接读真实 ID。
        // 这是游戏自己用的那张表，跨版本永远正确，优先于任何硬编码猜测。
        int id = resolveViaConnectionProtocol(fullClassName);
        if (id >= 0) return id;

        if (useReflection) {
            id = resolveByReflection(fullClassName);
            if (id >= 0) return id;
        }

        // 回退到硬编码 —— 只有协议号确实是 763(1.20.1) 时才允许。
        // FALLBACK 表是照 1.20.1 抄的，拿它去发 772 是灾难性的：
        // 区块包在 763 是 0x24，在 772 那个位置是 PlaySoundEvent，
        // B 收到后报 "Packet 33 was larger than I expected" 立刻断开。
        // 宁可这个包不发（少一样东西），也不能发一个 ID 错的包（整条流报废）。
        String simple = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
        Integer fb = FALLBACK.get(simple);
        if (fb != null) {
            if (isLegacy763()) {
                McLog.warn("PacketIdResolver: 使用回退 ID " + String.format("0x%02X", fb) + " for " + simple);
                return fb;
            }
            McLog.error("PacketIdResolver: " + simple + " 解析不到 ID，且当前协议不是 763，"
                + "拒绝使用 1.20.1 的硬编码 ID（会让 B 收到错包直接断开）");
            return -1;
        }
        McLog.error("PacketIdResolver: 无法解析包 ID: " + fullClassName);
        return -1;
    }

    /** 当前是否 1.20.1(763) —— 只有这时 FALLBACK 表才是对的。 */
    private static Boolean legacy763;

    private static boolean isLegacy763() {
        if (legacy763 != null) return legacy763;
        int p = -1;
        try {
            Class<?> sc = Reflect.tryCls("net.minecraft.SharedConstants");
            if (sc != null) {
                Object wv = null;
                for (String n : new String[]{"getCurrentVersion", "getVersion", "m_215274_"}) {
                    try { wv = Reflect.invokeStatic(sc, n); if (wv != null) break; } catch (Throwable ignored) {}
                }
                if (wv != null) {
                    for (String n : new String[]{"getProtocolVersion", "protocolVersion", "m_183476_"}) {
                        Object v = Reflect.invokeAnyTyped(wv, Integer.class, new String[]{n});
                        if (v instanceof Integer && (Integer) v > 0) { p = (Integer) v; break; }
                    }
                    if (p < 0) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("protocolVersion=(\\d+)").matcher(String.valueOf(wv));
                        if (m.find()) p = Integer.parseInt(m.group(1));
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 读不到协议号时保守放行：老版本(1.20.1 及更早)本来就走这条路，
        // 而 1.20.5+ 一定读得到（上面的 details() 路径依赖同一套反射）。
        legacy763 = (p < 0 || p == 763);
        McLog.info("PacketIdResolver: 协议号=" + p + "，硬编码回退表"
            + (legacy763 ? "可用" : "已禁用（非 763）"));
        return legacy763;
    }

    private static void ensureProtocolTable() {
        if (protocolTableTried) return;
        protocolTableTried = true;
        try { initProtocolTable(); } catch (Throwable t) {
            McLog.warn("PacketIdResolver: 读取 ConnectionProtocol 表失败: " + t);
        }
    }

    /**
     * 解析 SERVERBOUND（C→S）包 ID。
     * 查的是 ConnectionProtocol.PLAY 的 SERVERBOUND PacketSet 的 classToId 表。
     */
    public static int resolveServerbound(String fullClassName) {
        if (!initialized) init();

        Integer cached = SB_RESOLVED.get(fullClassName);
        if (cached != null) return cached;

        if (!protocolTableTried) {
            protocolTableTried = true;
            try { initProtocolTable(); } catch (Throwable t) {
                McLog.warn("PacketIdResolver: 读取 ConnectionProtocol 表失败: " + t);
            }
        }
        if (sbClassToIdMap == null || sbClassToIdGetInt == null) {
            int id = resolveViaLocTable(sbLocToId, fullClassName, "PLAY/SB");
            if (id >= 0) {
                SB_RESOLVED.put(fullClassName, id);
                return id;
            }
            McLog.warn("PacketIdResolver: SERVERBOUND 表不可用，无法解析: " + fullClassName);
            return -1;
        }
        try {
            Class<?> pktClass = Reflect.tryCls(fullClassName);
            if (pktClass == null) return -1;
            Object r = sbClassToIdGetInt.invoke(sbClassToIdMap, pktClass);
            if (r instanceof Integer) {
                int id = (Integer) r;
                if (id >= 0) {
                    SB_RESOLVED.put(fullClassName, id);
                    McLog.info("PacketIdResolver: Serverbound " + pktClass.getSimpleName() +
                        " → 0x" + Integer.toHexString(id) + " (来自 ConnectionProtocol 表)");
                    return id;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * 按运行时 Class 直接反查 PLAY/SERVERBOUND 包 ID（完全无视原版 ProGuard / Fabric Intermediary / Forge SRG 类名混淆）。
     */
    public static int serverboundPlayIdOf(Class<?> pktClass) {
        if (pktClass == null) return -1;
        String name = pktClass.getName();
        Integer cached = SB_RESOLVED.get(name);
        if (cached != null) return cached;

        ensureProtocolTable();
        Integer fromMap = sbClassMap.get(pktClass);
        if (fromMap != null && fromMap >= 0) {
            SB_RESOLVED.put(name, fromMap);
            return fromMap;
        }
        if (sbClassToIdMap != null && sbClassToIdGetInt != null) {
            try {
                Object r = sbClassToIdGetInt.invoke(sbClassToIdMap, pktClass);
                if (r instanceof Integer && (Integer) r >= 0) {
                    int id = (Integer) r;
                    SB_RESOLVED.put(name, id);
                    return id;
                }
            } catch (Throwable ignored) {}
        }
        if (sbLocToId != null) {
            String loc = locationOf(pktClass);
            if (loc != null) {
                Integer id = sbLocToId.get(loc);
                if (id != null && id >= 0) {
                    SB_RESOLVED.put(name, id);
                    return id;
                }
            }
        }
        return -1;
    }

    /** 按运行时 Class 直接反查 PLAY/CLIENTBOUND 包 ID（兼容 1.8.9–26.2 全环境）。 */
    public static int clientboundPlayIdOf(Class<?> pktClass) {
        if (pktClass == null) return -1;
        ensureProtocolTable();
        Integer fromMap = cbClassMap.get(pktClass);
        if (fromMap != null && fromMap >= 0) return fromMap;
        if (classToIdMap != null && classToIdGetInt != null) {
            try {
                Object r = classToIdGetInt.invoke(classToIdMap, pktClass);
                if (r instanceof Integer && (Integer) r >= 0) return (Integer) r;
            } catch (Throwable ignored) {}
        }
        if (cbLocToId != null) {
            String loc = locationOf(pktClass);
            if (loc != null) {
                Integer id = cbLocToId.get(loc);
                if (id != null && id >= 0) return id;
            }
        }
        return -1;
    }

    /** 按精确协议包 ID 反查宿主运行时 PLAY/CLIENTBOUND 包类（无视 MCP/SRG/Intermediary/原版混淆）。 */
    public static Class<?> clientboundPlayClassById(int packetId) {
        if (packetId < 0) return null;
        ensureProtocolTable();
        return cbIdToClass.get(packetId);
    }

    /**
     * 1.8.x – 1.20.1 (47 – 763) 经 ViaVersion 表核对的精确 S→C PLAY 包 ID 表。
     */
    public static int s2cPlayId(int p, String key) {
        if ("LOGIN".equals(key)) {
            if (p == 47) return 0x01;
            if (p >= 107 && p <= 340) return 0x23;
            if (p >= 393 && p <= 498) return 0x25;
            if (p >= 573 && p <= 578) return 0x26;
            if (p == 735 || p == 736) return 0x25;
            if (p >= 751 && p <= 754) return 0x24;
            if (p >= 755 && p <= 758) return 0x26;
            if (p == 759) return 0x23;
            if (p == 760) return 0x25;
            if (p == 761) return 0x24;
            if (p == 762 || p == 763) return 0x28;
        } else if ("PLAYER_INFO".equals(key)) {
            if (p == 47) return 0x38;
            if (p >= 107 && p <= 335) return 0x2D;
            if (p >= 338 && p <= 340) return 0x2E;
            if (p >= 393 && p <= 404) return 0x30;
            if (p >= 477 && p <= 498) return 0x33;
            if (p >= 573 && p <= 578) return 0x34;
            if (p == 735 || p == 736) return 0x33;
            if (p >= 751 && p <= 754) return 0x32;
            if (p >= 755 && p <= 758) return 0x36;
            if (p == 759) return 0x34;
            if (p == 760) return 0x37;
        } else if ("PLAYER_POS".equals(key)) {
            if (p == 47) return 0x08;
            if (p >= 107 && p <= 335) return 0x2E;
            if (p >= 338 && p <= 340) return 0x2F;
            if (p >= 393 && p <= 404) return 0x32;
            if (p >= 477 && p <= 498) return 0x35;
            if (p >= 573 && p <= 578) return 0x36;
            if (p == 735 || p == 736) return 0x35;
            if (p >= 751 && p <= 754) return 0x34;
            if (p >= 755 && p <= 758) return 0x38;
            if (p == 759) return 0x36;
            if (p == 760) return 0x39;
            if (p == 761) return 0x38;
            if (p == 762 || p == 763) return 0x3C;
        } else if ("CHUNK_DATA".equals(key)) {
            if (p == 47) return 0x21;
            if (p >= 107 && p <= 340) return 0x20;
            if (p >= 393 && p <= 404) return 0x22;
            if (p >= 477 && p <= 498) return 0x21;
            if (p >= 573 && p <= 578) return 0x22;
            if (p == 735 || p == 736) return 0x21;
            if (p >= 751 && p <= 754) return 0x20;
            if (p >= 755 && p <= 758) return 0x22;
        } else if ("LIGHT_UPDATE".equals(key)) {
            if (p >= 477 && p <= 498) return 0x24;
            if (p >= 573 && p <= 578) return 0x25;
            if (p == 735 || p == 736) return 0x24;
            if (p >= 751 && p <= 754) return 0x23;
            if (p >= 755 && p <= 758) return 0x25;
        } else if ("CHUNK_CENTER".equals(key)) {
            if (p >= 477 && p <= 498) return 0x40;
            if (p >= 573 && p <= 578) return 0x41;
            if (p >= 735 && p <= 754) return 0x40;
            if (p >= 755 && p <= 758) return 0x49;
        } else if ("ADD_ENTITY".equals(key)) {
            if (p == 47) return 0x0E;
            if (p >= 107 && p <= 761) return 0x00;
            if (p == 762 || p == 763) return 0x01;
        } else if ("ADD_MOB".equals(key)) {
            if (p == 47) return 0x0F;
            if (p >= 107 && p <= 578) return 0x03;
            if (p >= 735 && p <= 758) return 0x02;
        } else if ("ADD_PLAYER".equals(key)) {
            if (p == 47) return 0x0C;
            if (p >= 107 && p <= 578) return 0x05;
            if (p >= 735 && p <= 758) return 0x04;
            if (p >= 759 && p <= 761) return 0x02;
            if (p == 762 || p == 763) return 0x03;
        } else if ("ENTITY_DATA".equals(key)) {
            if (p == 47) return 0x1C;
            if (p >= 107 && p <= 316) return 0x39;
            if (p == 335) return 0x3B;
            if (p >= 338 && p <= 340) return 0x3C;
            if (p >= 393 && p <= 404) return 0x3F;
            if (p >= 477 && p <= 498) return 0x43;
            if (p >= 573 && p <= 754) return 0x44;
            if (p >= 755 && p <= 759) return 0x4D;
            if (p == 760) return 0x50;
            if (p == 761) return 0x4E;
            if (p == 762 || p == 763) return 0x52;
        } else if ("ENTITY_MOTION".equals(key)) {
            if (p == 47) return 0x12;
            if (p >= 107 && p <= 316) return 0x3B;
            if (p == 335) return 0x3D;
            if (p >= 338 && p <= 340) return 0x3E;
            if (p >= 393 && p <= 404) return 0x41;
            if (p >= 477 && p <= 498) return 0x45;
            if (p >= 573 && p <= 754) return 0x46;
            if (p >= 755 && p <= 759) return 0x4F;
        } else if ("ROTATE_HEAD".equals(key)) {
            if (p == 47) return 0x19;
            if (p >= 107 && p <= 316) return 0x34;
            if (p == 335) return 0x35;
            if (p >= 338 && p <= 340) return 0x36;
            if (p >= 393 && p <= 404) return 0x39;
            if (p >= 477 && p <= 498) return 0x3B;
            if (p >= 573 && p <= 578) return 0x3C;
            if (p == 735 || p == 736) return 0x3B;
            if (p >= 751 && p <= 754) return 0x3A;
            if (p >= 755 && p <= 758) return 0x3E;
        } else if ("EQUIPMENT".equals(key)) {
            if (p == 47) return 0x04;
            if (p >= 107 && p <= 316) return 0x3C;
            if (p == 335) return 0x3E;
            if (p >= 338 && p <= 340) return 0x3F;
            if (p >= 393 && p <= 404) return 0x42;
            if (p >= 477 && p <= 498) return 0x46;
            if (p >= 573 && p <= 754) return 0x47;
            if (p >= 755 && p <= 759) return 0x50;
        } else if ("WINDOW_ITEMS".equals(key)) {
            if (p == 47) return 0x30;
            if (p >= 107 && p <= 340) return 0x14;
            if (p >= 393 && p <= 404) return 0x15;
            if (p >= 477 && p <= 498) return 0x14;
            if (p >= 573 && p <= 578) return 0x15;
            if (p == 735 || p == 736) return 0x14;
            if (p >= 751 && p <= 754) return 0x13;
            if (p >= 755 && p <= 758) return 0x14;
        } else if ("HELD_ITEM".equals(key)) {
            if (p == 47) return 0x09;
            if (p >= 107 && p <= 316) return 0x37;
            if (p == 335) return 0x39;
            if (p >= 338 && p <= 340) return 0x3A;
            if (p >= 393 && p <= 404) return 0x3D;
            if (p >= 477 && p <= 498) return 0x3F;
            if (p >= 573 && p <= 578) return 0x40;
            if (p >= 735 && p <= 754) return 0x3F;
            if (p >= 755 && p <= 758) return 0x48;
        } else if ("HEALTH".equals(key)) {
            if (p == 47) return 0x06;
            if (p >= 107 && p <= 316) return 0x3E;
            if (p == 335) return 0x40;
            if (p >= 338 && p <= 340) return 0x41;
            if (p >= 393 && p <= 404) return 0x44;
            if (p >= 477 && p <= 498) return 0x48;
            if (p >= 573 && p <= 754) return 0x49;
            if (p >= 755 && p <= 759) return 0x52;
        } else if ("EXPERIENCE".equals(key)) {
            if (p == 47) return 0x1F;
            if (p >= 107 && p <= 316) return 0x3D;
            if (p == 335) return 0x3F;
            if (p >= 338 && p <= 340) return 0x40;
            if (p >= 393 && p <= 404) return 0x43;
            if (p >= 477 && p <= 498) return 0x47;
            if (p >= 573 && p <= 754) return 0x48;
            if (p >= 755 && p <= 759) return 0x51;
        } else if ("ABILITIES".equals(key)) {
            if (p == 47) return 0x39;
            if (p >= 107 && p <= 335) return 0x2B;
            if (p >= 338 && p <= 340) return 0x2C;
            if (p >= 393 && p <= 404) return 0x2E;
            if (p >= 477 && p <= 498) return 0x31;
            if (p >= 573 && p <= 578) return 0x32;
            if (p == 735 || p == 736) return 0x31;
            if (p >= 751 && p <= 754) return 0x30;
            if (p >= 755 && p <= 758) return 0x32;
        } else if ("TIME".equals(key)) {
            if (p == 47) return 0x03;
            if (p >= 107 && p <= 316) return 0x44;
            if (p == 335) return 0x46;
            if (p >= 338 && p <= 340) return 0x47;
            if (p >= 393 && p <= 404) return 0x4A;
            if (p >= 477 && p <= 498) return 0x4E;
            if (p >= 573 && p <= 578) return 0x4F;
            if (p >= 735 && p <= 754) return 0x4E;
            if (p == 755 || p == 756) return 0x58;
            if (p >= 757 && p <= 759) return 0x59;
        } else if ("SPAWN_POS".equals(key)) {
            if (p == 47) return 0x05;
            if (p >= 107 && p <= 316) return 0x43;
            if (p == 335) return 0x45;
            if (p >= 338 && p <= 340) return 0x46;
            if (p >= 393 && p <= 404) return 0x49;
            if (p >= 477 && p <= 498) return 0x4D;
            if (p >= 573 && p <= 578) return 0x4E;
            if (p >= 735 && p <= 754) return 0x42;
            if (p >= 755 && p <= 758) return 0x4B;
        } else if ("TEAMS".equals(key)) {
            if (p == 47) return 0x3E;
            if (p >= 107 && p <= 316) return 0x41;
            if (p == 335) return 0x43;
            if (p >= 338 && p <= 340) return 0x44;
            if (p >= 393 && p <= 404) return 0x47;
            if (p >= 477 && p <= 498) return 0x4B;
            if (p >= 573 && p <= 754) return 0x4C;
            if (p >= 755 && p <= 759) return 0x55;
        }
        return -1;
    }

    /** 精确的各协议版本 PLAY 阶段 C→S CustomPayload 包 ID 表（防混淆环境反射不到类名）。 */
    public static int customPayloadIdByProtocol(int protocol) {
        if (protocol == 47) return 0x17;                          // 1.8.x
        if (protocol >= 107 && protocol <= 316) return 0x09;      // 1.9 - 1.11.2
        if (protocol == 335) return 0x0A;                         // 1.12
        if (protocol >= 338 && protocol <= 340) return 0x09;      // 1.12.1 - 1.12.2
        if (protocol >= 393 && protocol <= 404) return 0x0A;      // 1.13 - 1.13.2
        if (protocol >= 477 && protocol <= 754) return 0x0B;      // 1.14 - 1.16.5
        if (protocol >= 755 && protocol <= 758) return 0x0A;      // 1.17 - 1.18.2
        if (protocol == 759 || protocol == 761) return 0x0C;      // 1.19, 1.19.3
        if (protocol == 760 || protocol == 762 || protocol == 763) return 0x0D; // 1.19.1-1.19.2, 1.19.4-1.20.1
        if (protocol == 764) return 0x0F;                         // 1.20.2
        if (protocol == 765) return 0x10;                         // 1.20.3 - 1.20.4
        if (protocol == 766 || protocol == 767) return 0x12;      // 1.20.5 - 1.21.1
        if (protocol >= 768 && protocol <= 770) return 0x14;      // 1.21.2 - 1.21.5
        if (protocol >= 771) return 0x15;                         // 1.21.6+
        return -1;
    }

    /** 精确的各协议版本 PLAY 阶段 C→S ChatSessionUpdate 包 ID 表。 */
    public static int chatSessionUpdateIdByProtocol(int protocol) {
        if (protocol == 761) return 0x20;                         // 1.19.3
        if (protocol >= 762 && protocol <= 765) return 0x06;      // 1.19.4 - 1.20.4
        if (protocol == 766 || protocol == 767) return 0x07;      // 1.20.5 - 1.21.1
        if (protocol >= 768 && protocol <= 770) return 0x08;      // 1.21.2 - 1.21.5
        if (protocol >= 771) return 0x09;                         // 1.21.6+
        return -1;
    }

    /** 缓存：包类全名 → 真实协议 ID。两个方向 ID 空间不同，必须分开缓存。 */
    private static final Map<String, Integer> RESOLVED = new HashMap<>();
    private static final Map<String, Integer> SB_RESOLVED = new HashMap<>();
    private static final Map<String, Integer> CFG_RESOLVED = new HashMap<>();
    private static final Map<Integer, Class<?>> cbIdToClass = new HashMap<>();
    private static final Map<Class<?>, Integer> cbClassMap = new HashMap<>();
    private static final Map<Class<?>, Integer> sbClassMap = new HashMap<>();

    /**
     * configuration 阶段 S→C 包的硬编码 ID（1.20.2 – 1.21.x 稳定）。
     * 仅在反射拿不到协议表时兜底用。
     */
    private static final Map<String, Integer> CONFIG_FALLBACK = new HashMap<>();
    static {
        // 索引即包 ID，按 1.21.8 的 ConfigurationProtocols 注册顺序核对过（从 0 起）：
        // 0=custom_payload 1=disconnect 2=finish_configuration 3=keep_alive 4=ping
        // 5=reset_chat 6=registry_data 7/8=resource_pack 9=store_cookie 10=transfer
        // 11=update_enabled_features 12=update_tags 13=select_known_packs
        CONFIG_FALLBACK.put("ClientboundRegistryDataPacket", 0x06);
        CONFIG_FALLBACK.put("ClientboundFinishConfigurationPacket", 0x02);
        CONFIG_FALLBACK.put("ClientboundSelectKnownPacks", 0x0D);
        CONFIG_FALLBACK.put("ClientboundUpdateTagsPacket", 0x0C);
        CONFIG_FALLBACK.put("ClientboundUpdateEnabledFeaturesPacket", 0x0B);
    }
    private static boolean protocolTableTried = false;
    private static Object classToIdMap;           // CLIENTBOUND 的 Object2IntMap<Class<?>>
    private static Method classToIdGetInt;
    private static Object sbClassToIdMap;         // SERVERBOUND 的 Object2IntMap<Class<?>>
    private static Method sbClassToIdGetInt;
    private static Object cfgClassToIdMap;        // CONFIGURATION/CLIENTBOUND
    private static Method cfgClassToIdGetInt;

    /** 1.20.5+ 的表：ResourceLocation 字符串 → 包 ID。旧版为 null。 */
    private static Map<String, Integer> cbLocToId;
    private static Map<String, Integer> sbLocToId;
    private static Map<String, Integer> cfgLocToId;
    /** 包类名 → PacketType 的 ResourceLocation，避免重复扫字段。 */
    private static final Map<String, String> CLASS_TO_LOC = new HashMap<>();

    /** 在 1.20.5+ 的 loc→id 表里查包 ID，查不到返回 -1。 */
    private static int resolveViaLocTable(Map<String, Integer> table, String fullClassName, String tag) {
        if (table == null) return -1;
        Class<?> pktClass = Reflect.tryCls(fullClassName);
        if (pktClass == null) return -1;
        String loc = locationOf(pktClass);
        if (loc == null) {
            McLog.warn("PacketIdResolver: " + tag + " 取不到 " + pktClass.getSimpleName()
                + " 的 PacketType（无法查 ID 表）");
            return -1;
        }
        Integer id = table.get(loc);
        if (id == null) {
            McLog.warn("PacketIdResolver: " + tag + " 表里没有 " + loc);
            return -1;
        }
        McLog.info("PacketIdResolver: " + tag + " " + pktClass.getSimpleName()
            + " → 0x" + Integer.toHexString(id) + " (" + loc + ")");
        return id;
    }

    /**
     * 从 ConnectionProtocol.PLAY 的 CLIENTBOUND PacketSet 中读取包 ID。
     *
     * 1.20.1 的包类没有 TYPE 字段（那是 1.20.2+ 才有的），原先只能退化到硬编码表。
     * 但游戏内部维护着 classToId 映射，反射读它即可拿到当前版本的准确 ID。
     */
    private static synchronized int resolveViaConnectionProtocol(String fullClassName) {
        Integer cached = RESOLVED.get(fullClassName);
        if (cached != null) return cached;

        ensureProtocolTable();
        Class<?> pktClass = Reflect.tryCls(fullClassName);
        if (pktClass != null) {
            Integer direct = cbClassMap.get(pktClass);
            if (direct != null && direct >= 0) {
                RESOLVED.put(fullClassName, direct);
                return direct;
            }
        }
        if (classToIdMap == null || classToIdGetInt == null) {
            return resolveViaLocTable(cbLocToId, fullClassName, "PLAY/CB");
        }

        try {
            if (pktClass == null) return -1;
            Object r = classToIdGetInt.invoke(classToIdMap, pktClass);
            if (r instanceof Integer) {
                int id = (Integer) r;
                if (id >= 0) {
                    RESOLVED.put(fullClassName, id);
                    McLog.info("PacketIdResolver: " + pktClass.getSimpleName() +
                        " → 0x" + Integer.toHexString(id) + " (来自 ConnectionProtocol 表)");
                    return id;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * 解析 CONFIGURATION 阶段的 CLIENTBOUND 包 ID（1.20.2+）。
     *
     * configuration 是独立的协议阶段，包 ID 空间和 PLAY 完全不同，
     * 不能用 resolve() 去查。
     */
    public static int resolveConfiguration(String fullClassName) {
        if (!initialized) init();

        Integer cached = CFG_RESOLVED.get(fullClassName);
        if (cached != null) return cached;

        if (!protocolTableTried) {
            protocolTableTried = true;
            try { initProtocolTable(); } catch (Throwable t) {
                McLog.warn("PacketIdResolver: 读取 ConnectionProtocol 表失败: " + t);
            }
        }
        if (cfgClassToIdMap == null || cfgClassToIdGetInt == null) {
            // 1.20.5+ 先查 loc→id 表，真拿不到才用硬编码
            int id = resolveViaLocTable(cfgLocToId, fullClassName, "CONFIG/CB");
            if (id >= 0) {
                CFG_RESOLVED.put(fullClassName, id);
                return id;
            }
            String simple = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
            Integer fb = CONFIG_FALLBACK.get(simple);
            if (fb != null) {
                McLog.warn("PacketIdResolver: CONFIGURATION 表不可用，" + simple
                    + " 使用硬编码 ID 0x" + Integer.toHexString(fb));
                CFG_RESOLVED.put(fullClassName, fb);
                return fb;
            }
            McLog.warn("PacketIdResolver: CONFIGURATION 表不可用: " + fullClassName);
            return -1;
        }
        try {
            Class<?> pktClass = Reflect.tryCls(fullClassName);
            if (pktClass != null) {
                Object r = cfgClassToIdGetInt.invoke(cfgClassToIdMap, pktClass);
                if (r instanceof Integer) {
                    int id = (Integer) r;
                    if (id >= 0) {
                        CFG_RESOLVED.put(fullClassName, id);
                        McLog.info("PacketIdResolver: [config] " + pktClass.getSimpleName() +
                            " → 0x" + Integer.toHexString(id));
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {}

        String simple = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
        Integer fb = CONFIG_FALLBACK.get(simple);
        if (fb != null) {
            McLog.warn("PacketIdResolver: [config] " + simple + " 表内未命中，使用硬编码 ID 0x"
                + Integer.toHexString(fb));
            CFG_RESOLVED.put(fullClassName, fb);
            return fb;
        }
        return -1;
    }

    private static void initProtocolTable() throws Exception {
        try {
            initProtocolTableLegacy();
        } catch (Throwable t) {
            McLog.warn("PacketIdResolver: 旧路径(ConnectionProtocol/EnumConnectionState)失败: " + t);
        }

        if (cbIdToClass.isEmpty() && (classToIdMap == null || cfgClassToIdMap == null)) {
            McLog.info("PacketIdResolver: 旧路径未拿全 ID 表（play="
                + (classToIdMap != null || !cbIdToClass.isEmpty()) + " config=" + (cfgClassToIdMap != null)
                + "），尝试 1.20.5+ 的 ProtocolInfo 路径");
            initProtocolTableModern();
        }
    }

    private static void initProtocolTableLegacy() throws Exception {
        Class<?> cpClass = Reflect.tryCls("net.minecraft.network.ConnectionProtocol");
        if (cpClass == null) cpClass = Reflect.tryCls("net.minecraft.network.EnumConnectionState");
        if (cpClass == null) cpClass = Reflect.tryCls("net.minecraft.class_2539"); // Fabric NetworkState

        if (cpClass != null && cpClass.getEnumConstants() != null) {
            if (tryInitFromProtocolEnum(cpClass)) return;
        }

        // 原版完全混淆环境（1.8.9–1.20.4）：扫描 4 或 5 个常量的枚举，寻找 ordinal=1 (PLAY) 内含方向包表的枚举
        for (Class<?> cand : Reflect.loadedClasses()) {
            if (cand == null || !cand.isEnum()) continue;
            String cn = cand.getName();
            if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
                || cn.startsWith("io.netty.") || cn.startsWith("com.google.") || cn.startsWith("org.")) continue;
            Object[] consts;
            try { consts = cand.getEnumConstants(); } catch (Throwable ignored) { continue; }
            if (consts == null || (consts.length != 4 && consts.length != 5)) continue;
            try {
                if (tryInitFromProtocolEnum(cand)) {
                    McLog.info("PacketIdResolver: 已从混淆枚举 " + cn + " 加载 PLAY 协议表");
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean tryInitFromProtocolEnum(Class<?> cpClass) {
        Object[] protocols = cpClass.getEnumConstants();
        if (protocols == null || protocols.length < 2) return false;
        Object play = pickEnum(protocols, "PLAY", 1);
        if (play == null) return false;

        Object flows = Reflect.findFieldByType(play, java.util.Map.class);
        if (!(flows instanceof java.util.Map)) return false;
        java.util.Map<?, ?> flowMap = (java.util.Map<?, ?>) flows;
        if (flowMap.isEmpty()) return false;

        boolean foundAny = false;
        // 遍历 PLAY 的两个方向（CLIENTBOUND 包数 > 50，SERVERBOUND 包数在 15..55 之间）
        for (Map.Entry<?, ?> fe : flowMap.entrySet()) {
            Object dirKey = fe.getKey();
            Object holder = fe.getValue();
            if (holder == null) continue;

            // 情况 A: 1.8 - 1.13.2 EnumConnectionState -> BiMap<Integer, Class<?>>
            if (holder instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) holder;
                if (m.size() >= 15) {
                    boolean isCb = isClientboundFlow(dirKey, m.size());
                    for (Map.Entry<?, ?> pe : m.entrySet()) {
                        if (pe.getKey() instanceof Integer && pe.getValue() instanceof Class) {
                            Integer id = (Integer) pe.getKey();
                            Class<?> cls = (Class<?>) pe.getValue();
                            if (isCb) {
                                cbIdToClass.put(id, cls);
                                cbClassMap.put(cls, id);
                            } else {
                                sbClassMap.put(cls, id);
                            }
                            foundAny = true;
                        }
                    }
                    continue;
                }
            }

            // 情况 B: 1.14 - 1.20.4 ConnectionProtocol -> PacketSet 内含 Object2IntMap<Class<?>>
            Object[] extracted = extractClassToId(holder);
            if (extracted != null) {
                Object o2i = extracted[0];
                Method getInt = (Method) extracted[1];
                int sz = (o2i instanceof java.util.Map) ? ((java.util.Map<?, ?>) o2i).size() : -1;
                boolean isCb = isClientboundFlow(dirKey, sz);
                if (isCb) {
                    classToIdMap = o2i;
                    classToIdGetInt = getInt;
                } else {
                    sbClassToIdMap = o2i;
                    sbClassToIdGetInt = getInt;
                }
                if (o2i instanceof java.util.Map) {
                    for (Map.Entry<?, ?> pe : ((java.util.Map<?, ?>) o2i).entrySet()) {
                        if (pe.getKey() instanceof Class && pe.getValue() instanceof Integer) {
                            Class<?> cls = (Class<?>) pe.getKey();
                            Integer id = (Integer) pe.getValue();
                            if (id >= 0) {
                                if (isCb) {
                                    cbIdToClass.put(id, cls);
                                    cbClassMap.put(cls, id);
                                } else {
                                    sbClassMap.put(cls, id);
                                }
                                foundAny = true;
                            }
                        }
                    }
                } else {
                    foundAny = true;
                }
            }
        }

        if (foundAny) {
            McLog.info("PacketIdResolver: 协议表加载成功 (" + cpClass.getName()
                + ", CB=" + cbIdToClass.size() + ", SB=" + sbClassMap.size() + ")");
        }

        // CONFIGURATION 阶段（1.20.2 - 1.20.4）
        Object config = pickEnumOrNull(protocols, "CONFIGURATION");
        if (config == null && protocols.length == 5) {
            config = protocols[4]; // HANDSHAKING(0), PLAY(1), STATUS(2), LOGIN(3), CONFIGURATION(4)
        }
        if (config != null) {
            Object cfgFlows = Reflect.findFieldByType(config, java.util.Map.class);
            if (cfgFlows instanceof java.util.Map) {
                for (Map.Entry<?, ?> fe : ((java.util.Map<?, ?>) cfgFlows).entrySet()) {
                    if (isClientboundFlow(fe.getKey(), -1)) {
                        Object[] cfg = extractClassToId(fe.getValue());
                        if (cfg != null) {
                            cfgClassToIdMap = cfg[0];
                            cfgClassToIdGetInt = (Method) cfg[1];
                            McLog.info("PacketIdResolver: 已接入 CONFIGURATION/CLIENTBOUND 表");
                        }
                    }
                }
            }
        }
        return foundAny;
    }

    private static boolean isClientboundFlow(Object dirEnum, int mapSize) {
        if (dirEnum instanceof Enum) {
            String n = ((Enum<?>) dirEnum).name().toUpperCase(java.util.Locale.ROOT);
            if (n.contains("CLIENT")) return true;
            if (n.contains("SERVER")) return false;
        }
        if (mapSize > 55) return true;
        if (mapSize >= 15 && mapSize <= 55) return false;
        return dirEnum instanceof Enum && ((Enum<?>) dirEnum).ordinal() == 1;
    }

    /** 按名字取枚举常量，没有就返回 null（不做 ordinal 回退 —— 老版本压根没这个常量）。 */
    private static Object pickEnumOrNull(Object[] constants, String name) {
        for (Object o : constants) {
            if (o instanceof Enum && ((Enum<?>) o).name().equals(name)) return o;
        }
        return null;
    }

    /**
     * 1.20.5+ 的包 ID 解析。
     *
     * 这些版本没有绑定好的 ID 表可扫：GameProtocols 里只有 SERVERBOUND_TEMPLATE /
     * CLIENTBOUND_TEMPLATE，类型是 UnboundProtocol，必须 bind(Function, Context)
     * 之后才产生 Object2IntMap。所以"递归找 Object2IntMap"在 PLAY 上必然扑空 ——
     * 实测扑空后会拿 LoginProtocols 的表（LOGIN 只有 5 个包）当 PLAY 用，
     * 于是所有 PLAY 包退回 1.20.1 硬编码：区块包按 763 发 0x24，772 那边 0x24
     * 是 PlaySoundEvent，B 直接报 "Packet 33 larger than I expected" 断开。
     *
     * 正路是 ProtocolInfo.Details：模板实现了 DetailsProvider，details().listPackets()
     * 会把 (PacketType, id) 全枚举出来，不需要 bind。PacketType 是 record，
     * 拿不到包类，但它的 id() 是 ResourceLocation（如 minecraft:level_chunk_with_light），
     * 而包类的实例方法 type() 返回同一个 PacketType —— 用它建立 类名→ID 的映射。
     */
    private static void initProtocolTableModern() {
        String[] holders = {
            "net.minecraft.network.protocol.game.GameProtocols",
            "net.minecraft.network.protocol.configuration.ConfigurationProtocols",
            "net.minecraft.network.protocol.login.LoginProtocols",
        };
        boolean anyHolder = false;
        for (String hn : holders) {
            Class<?> holder = Reflect.tryCls(hn);
            if (holder == null) {
                McLog.warn("PacketIdResolver: 找不到类 " + hn);
                continue;
            }
            anyHolder = true;
            boolean isConfig = hn.contains("configuration");
            boolean isLogin = hn.contains("login");
            StringBuilder seen = new StringBuilder();
            for (Field f : holder.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object protocolInfo = f.get(null);
                    seen.append(f.getName()).append(':')
                        .append(protocolInfo == null ? "null" : protocolInfo.getClass().getSimpleName())
                        .append(' ');
                    if (protocolInfo == null) continue;

                    Map<String, Integer> byLoc = listPacketsViaDetails(protocolInfo);
                    if (byLoc == null || byLoc.isEmpty()) continue;

                    boolean clientbound = looksClientbound(f.getName(), protocolInfo);
                    // LOGIN 阶段的表绝不能占用 PLAY 的槽位 —— 之前就是这么错的。
                    if (isLogin) continue;

                    if (isConfig) {
                        if (clientbound && cfgLocToId == null) {
                            cfgLocToId = byLoc;
                            McLog.info("PacketIdResolver: [1.20.5+] 接入 CONFIGURATION/CLIENTBOUND ("
                                + holder.getSimpleName() + "." + f.getName()
                                + "，" + byLoc.size() + " 个包)");
                        }
                    } else if (clientbound) {
                        if (cbLocToId == null) {
                            cbLocToId = byLoc;
                            McLog.info("PacketIdResolver: [1.20.5+] 接入 PLAY/CLIENTBOUND ("
                                + holder.getSimpleName() + "." + f.getName()
                                + "，" + byLoc.size() + " 个包)");
                        }
                    } else if (sbLocToId == null) {
                        sbLocToId = byLoc;
                        McLog.info("PacketIdResolver: [1.20.5+] 接入 PLAY/SERVERBOUND ("
                            + holder.getSimpleName() + "." + f.getName()
                            + "，" + byLoc.size() + " 个包)");
                    }
                } catch (Throwable ignored) {}
            }
            McLog.info("PacketIdResolver: " + holder.getSimpleName() + " 静态字段 → " + seen);
        }
        if (!anyHolder) {
            McLog.warn("PacketIdResolver: GameProtocols/ConfigurationProtocols 都不存在，"
                + "该版本的协议表结构未知");
        }
        if (cbLocToId == null) {
            McLog.warn("PacketIdResolver: [1.20.5+] 仍未找到 CLIENTBOUND 的 ID 表，将退回硬编码");
        }
        if (cfgLocToId == null) {
            McLog.warn("PacketIdResolver: [1.20.5+] 未找到 CONFIGURATION 的 ID 表 —— "
                + "注册表将发不出去，B 会因为缺维度/生物群系定义而无法进入世界");
        }
    }

    /**
     * 用 ProtocolInfo.Details.listPackets 枚举 {ResourceLocation → 包 ID}。
     *
     * details() 声明在 DetailsProvider 接口上，UnboundProtocol 和绑定后的
     * ProtocolInfo 都实现了它，所以模板阶段就能拿到全表，不需要 bind。
     * 用动态代理实现 PacketVisitor.accept(PacketType, int) 收集。
     */
    private static Map<String, Integer> listPacketsViaDetails(Object protocolInfo) {
        try {
            Object details = null;
            for (Method m : protocolInfo.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().equals("details")) continue;
                m.setAccessible(true);
                details = m.invoke(protocolInfo);
                break;
            }
            if (details == null) return null;

            Class<?> visitorClass = Reflect.tryCls("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
            if (visitorClass == null) return null;

            Method listPackets = null;
            for (Method m : details.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!m.getName().equals("listPackets")) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(visitorClass)) continue;
                listPackets = m;
                break;
            }
            if (listPackets == null) return null;

            final Map<String, Integer> out = new HashMap<>();
            Object visitor = java.lang.reflect.Proxy.newProxyInstance(
                visitorClass.getClassLoader(),
                new Class<?>[]{visitorClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("accept") && args != null && args.length == 2) {
                        // PacketType.id() → ResourceLocation
                        Object loc = null;
                        for (Method im : args[0].getClass().getMethods()) {
                            if (im.getParameterCount() != 0) continue;
                            if (!im.getName().equals("id")) continue;
                            im.setAccessible(true);
                            loc = im.invoke(args[0]);
                            break;
                        }
                        if (loc != null && args[1] instanceof Integer) {
                            out.put(loc.toString(), (Integer) args[1]);
                        }
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });

            listPackets.setAccessible(true);
            listPackets.invoke(details, visitor);
            return out;
        } catch (Throwable t) {
            McLog.warn("PacketIdResolver: listPackets 失败: " + t);
            return null;
        }
    }

    /**
     * 包类 → 它的 PacketType 的 ResourceLocation 字符串。
     *
     * 包类有实例方法 type() 返回 PacketType，但那是实例方法，没有现成实例可调。
     * 改从静态字段里找 PacketType（多数包类有），再退回构造一个"最小实例"太危险，
     * 所以最后一招是扫 GamePacketTypes 之类的常量持有类，按字段名匹配。
     */
    private static String locationOf(Class<?> pktClass) {
        String cached = CLASS_TO_LOC.get(pktClass.getName());
        if (cached != null) return cached;

        Class<?> ptClass = Reflect.tryCls("net.minecraft.network.protocol.PacketType");
        if (ptClass == null) return null;

        // 1) 包类自己的静态 PacketType 字段
        for (Field f : pktClass.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!ptClass.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object pt = f.get(null);
                String loc = locOf(pt);
                if (loc != null) {
                    CLASS_TO_LOC.put(pktClass.getName(), loc);
                    return loc;
                }
            } catch (Throwable ignored) {}
        }

        // 2) 常量持有类（GamePacketTypes / CommonPacketTypes / ConfigurationPacketTypes）：
        //    字段的泛型实参就是包类本身，用它精确匹配，不靠名字猜。
        for (String hn : new String[]{
                "net.minecraft.network.protocol.game.GamePacketTypes",
                "net.minecraft.network.protocol.common.CommonPacketTypes",
                "net.minecraft.network.protocol.configuration.ConfigurationPacketTypes",
                "net.minecraft.network.protocol.login.LoginPacketTypes"}) {
            Class<?> holder = Reflect.tryCls(hn);
            if (holder == null) continue;
            for (Field f : holder.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!ptClass.isAssignableFrom(f.getType())) continue;
                try {
                    java.lang.reflect.Type g = f.getGenericType();
                    if (!(g instanceof java.lang.reflect.ParameterizedType)) continue;
                    java.lang.reflect.Type[] targs =
                        ((java.lang.reflect.ParameterizedType) g).getActualTypeArguments();
                    if (targs.length != 1 || targs[0] != pktClass) continue;
                    f.setAccessible(true);
                    String loc = locOf(f.get(null));
                    if (loc != null) {
                        CLASS_TO_LOC.put(pktClass.getName(), loc);
                        return loc;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static String locOf(Object packetType) {
        if (packetType == null) return null;
        try {
            for (Method m : packetType.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().equals("id")) continue;
                m.setAccessible(true);
                Object loc = m.invoke(packetType);
                return loc != null ? loc.toString() : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 字段名或 ProtocolInfo.flow() 判断方向。 */
    private static boolean looksClientbound(String fieldName, Object protocolInfo) {
        String n = fieldName.toUpperCase(java.util.Locale.ROOT);
        if (n.contains("CLIENTBOUND")) return true;
        if (n.contains("SERVERBOUND")) return false;
        try {
            Object flow = Reflect.invokeAny(protocolInfo, new String[]{"flow", "id"});
            if (flow instanceof Enum) return ((Enum<?>) flow).name().contains("CLIENTBOUND");
        } catch (Throwable ignored) {}
        return true;
    }

    /** 在对象里递归找 Object2IntMap<Class> —— 1.20.5+ 它被包了好几层。 */
    private static Object[] deepFindClassToId(Object root, int depth) {
        if (root == null || depth > 4) return null;
        for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(root);
                    if (v == null) continue;
                    if (v.getClass().getName().contains("Object2Int")) {
                        Method getInt = findGetInt(v.getClass());
                        if (getInt != null) return new Object[]{v, getInt};
                    }
                    // 往下钻一层（跳过明显不可能的类型，避免爆栈）
                    String vn = v.getClass().getName();
                    if (vn.startsWith("java.lang") || vn.startsWith("java.util.concurrent")) continue;
                    Object[] deeper = deepFindClassToId(v, depth + 1);
                    if (deeper != null) return deeper;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 从 PacketSet 里取出 {Object2IntMap, getInt方法}，找不到返回 null。 */
    private static Object[] extractClassToId(Object packetSet) {
        if (packetSet == null) return null;
        for (Class<?> c = packetSet.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(packetSet);
                    if (v == null) continue;
                    if (!v.getClass().getName().contains("Object2Int")) continue;
                    Method getInt = findGetInt(v.getClass());
                    if (getInt == null) continue;
                    return new Object[]{v, getInt};
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 按枚举常量名挑选，找不到则退回指定 ordinal。 */
    private static Object pickEnum(Object[] constants, String name, int fallbackOrdinal) {
        for (Object o : constants) {
            if (o instanceof Enum && ((Enum<?>) o).name().equals(name)) return o;
        }
        return fallbackOrdinal < constants.length ? constants[fallbackOrdinal] : null;
    }

    private static Method findGetInt(Class<?> mapClass) {
        for (Method m : mapClass.getMethods()) {
            if (!m.getName().equals("getInt")) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != int.class) continue;
            if (!m.getParameterTypes()[0].isAssignableFrom(Class.class)) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /**
     * 反射路径 1：包类.TYPE.getId()
     * 反射路径 2：通过 ConnectionProtocol 查找
     */
    private static int resolveByReflection(String fullClassName) {
        try {
            Class<?> packetClass = Reflect.cls(fullClassName);
            // 路径 1: 按名称找 TYPE 静态字段
            Field typeField = null;
            for (Field f : packetClass.getDeclaredFields()) {
                if (f.getName().equals("TYPE")) {
                    typeField = f;
                    break;
                }
            }
            // 路径 1b: 按类型找 TYPE 静态字段（防 SRG 混淆）
            if (typeField == null) {
                Class<?> packetTypeClass = Reflect.tryCls("net.minecraft.network.protocol.PacketType");
                if (packetTypeClass != null) {
                    for (Field f : packetClass.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) &&
                            packetTypeClass.isAssignableFrom(f.getType())) {
                            typeField = f;
                            break;
                        }
                    }
                }
            }
            if (typeField != null) {
                typeField.setAccessible(true);
                Object packetType = typeField.get(null);
                if (packetType != null) {
                    Integer id = tryGetId(packetType);
                    if (id != null) return id;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** 尝试多种方法名获取包 ID。 */
    private static Integer tryGetId(Object packetType) {
        for (String methodName : new String[]{"getId", "id", "getProtocolId"}) {
            try {
                Method m = packetType.getClass().getMethod(methodName);
                m.setAccessible(true);
                Object result = m.invoke(packetType);
                if (result instanceof Integer) return (Integer) result;
            } catch (Throwable ignored) {}
        }
        // 也尝试在接口层级查找
        for (Class<?> iface : packetType.getClass().getInterfaces()) {
            for (String methodName : new String[]{"getId", "id"}) {
                try {
                    Method m = iface.getMethod(methodName);
                    m.setAccessible(true);
                    Object result = m.invoke(packetType);
                    if (result instanceof Integer) return (Integer) result;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
