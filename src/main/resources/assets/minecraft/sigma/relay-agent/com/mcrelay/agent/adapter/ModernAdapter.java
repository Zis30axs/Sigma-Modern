package com.mcrelay.agent.adapter;

import com.mcrelay.agent.proxy.McFraming;
import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1.13 – 1.20.1 适配器。每次 B 连入时，从 A 的活内存现场重建整个世界状态。
 *
 * 两条铁律：
 *
 * 1) **不缓存任何东西。** 所有包在 B 连入的那一刻现读现发。之前用 WorldSnapshot 录
 *    S→A 的包再回放，结果是 A 跑图越久缓存越旧，B 看到的地形和 A 对不上，重连后
 *    区块还不一致。A 的内存就是唯一事实来源。
 *
 * 2) **不手写序列化。** 一律构造 MC 自己的包对象、调它的 write()。手写就要自己查
 *    registry ID：写成 0 时 B 那边所有生物变 allay，写成 42 全变 giant —— 这类 bug
 *    只在肉眼看到怪物长错样子时才会暴露。让游戏自己写，ID 和字段顺序都不会错。
 *
 * 发包顺序照抄原版服务端的进服序列，顺序本身有因果（见 sendWorld）。
 */
public class ModernAdapter implements RelayAdapter {

    private static final String P = "net.minecraft.network.protocol.game.";

    /** JoinGame 用的实体 ID，后续包复用，避免两次读取之间 A 换维度导致 ID 不一致。 */
    private volatile Integer joinGameEntityId;

    /**
     * B 声明的协议号。决定 JoinGame 的字段结构 —— 763 及以下注册表塞在包里，
     * 764+ 挪去了 configuration 阶段。默认 763（1.20.1）。
     */
    private volatile int clientProtocol = 763;

    @Override
    public void setClientProtocol(int protocol) {
        this.clientProtocol = protocol;
        McLog.info("ModernAdapter: 客户端协议 " + protocol);
    }

    // ======================== 基础对象获取 ========================

    private Object mc() {
        Object mc = Reflect.findMinecraftInstance();
        if (mc == null) throw new IllegalStateException("拿不到 Minecraft 实例");
        return mc;
    }

    private Object player(Object mc) {
        for (String cn : new String[]{
                "net.minecraft.client.player.LocalPlayer",
                "net.minecraft.client.entity.player.ClientPlayerEntity",
                "net.minecraft.client.entity.EntityPlayerSP",
                "net.minecraft.class_746"}) {
            Class<?> lp = Reflect.tryCls(cn);
            if (lp != null) {
                Object p = Reflect.findFieldByType(mc, lp);
                if (p != null) return p;
            }
        }
        Object byName = Reflect.fieldAny(mc, "player", "f_91074_", "field_71439_g", "field_1724");
        if (byName != null) return byName;
        throw new IllegalStateException("拿不到 LocalPlayer —— A 可能不在服务器中");
    }

    private Object level(Object player) {
        for (String cn : new String[]{
                "net.minecraft.client.multiplayer.ClientLevel",
                "net.minecraft.client.world.ClientWorld",
                "net.minecraft.world.level.Level",
                "net.minecraft.world.World",
                "net.minecraft.class_638"}) {
            Class<?> cl = Reflect.tryCls(cn);
            if (cl != null) {
                Object l = Reflect.findFieldByType(player, cl);
                if (l != null) return l;
            }
        }
        Object byName = Reflect.fieldAny(player, "level", "world", "f_19853_", "field_70170_p", "field_6002");
        if (byName != null) return byName;
        throw new IllegalStateException("拿不到 ClientLevel");
    }

    private Object connection(Object mc) {
        for (String cn : new String[]{
                "net.minecraft.client.multiplayer.ClientPacketListener",
                "net.minecraft.client.network.play.ClientPlayNetHandler",
                "net.minecraft.client.network.NetHandlerPlayClient",
                "net.minecraft.class_634"}) {
            Class<?> cpl = Reflect.tryCls(cn);
            if (cpl != null) {
                Object c = Reflect.invokeByReturnType(mc, cpl);
                if (c != null) return c;
                c = Reflect.findFieldByType(mc, cpl);
                if (c != null) return c;
            }
        }
        return Reflect.invokeAny(mc, new String[]{"getConnection", "m_91390_", "func_147114_u", "method_1562"});
    }

    /** 在 MC 主线程执行并等结果 —— 区块/实体集合由主线程改，别处遍历会 CME。 */
    private <T> T onMain(java.util.concurrent.Callable<T> task, T fallback, String what) {
        Object mc;
        try { mc = mc(); } catch (Throwable t) { mc = null; }
        if (mc != null) {
            Object same = Reflect.invokeAnyTyped(mc, Boolean.class,
                new String[]{"isSameThread", "m_18695_", "isOnExecutionThread", "func_213162_bc", "method_18854"});
            if (Boolean.TRUE.equals(same)) {
                try { return task.call(); } catch (Throwable t) {
                    McLog.error(what + " 失败(主线程内联)", t);
                    return fallback;
                }
            }
            java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
            Runnable job = () -> {
                try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); }
            };
            Method dispatch = null;
            for (String n : new String[]{"m_18707_", "submit", "m_18689_", "tell", "execute", "enqueue", "func_213160_bf", "method_20493"}) {
                try { dispatch = Reflect.findMethod(mc.getClass(), n, 1); } catch (Throwable ignored) { continue; }
                if (dispatch.getParameterTypes()[0].isAssignableFrom(Runnable.class)) break;
                dispatch = null;
            }
            if (dispatch != null) {
                try {
                    dispatch.invoke(mc, job);
                    return f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    McLog.warn(what + " 主线程超时(15s)");
                    return fallback;
                } catch (Throwable t) {
                    McLog.error(what + " 主线程执行失败", t);
                    return fallback;
                }
            }
        }
        McLog.warn(what + ": 无法调度到主线程，改为当前线程直接读（可能撞并发修改）");
        try { return task.call(); } catch (Throwable t) {
            McLog.error(what + " 失败", t);
            return fallback;
        }
    }

    // ======================== 玩家档案 ========================

    @Override
    public PlayerProfile readProfile() {
        try {
            Object mcObj = mc();
            Object p = player(mcObj);
            Class<?> gpClass = Reflect.tryCls("com.mojang.authlib.GameProfile");
            Object gp = gpClass != null ? Reflect.invokeByReturnType(p, gpClass) : null;
            if (gp == null && gpClass != null) {
                Object conn = connection(mcObj);
                if (conn != null) gp = Reflect.invokeByReturnType(conn, gpClass);
            }
            String name = null;
            UUID uuid = null;
            List<String[]> props = new ArrayList<String[]>();
            if (gp != null) {
                Object n = Reflect.invokeAny(gp, new String[]{"getName", "name"});
                if (n instanceof String) name = (String) n;
                Object u = Reflect.invokeAny(gp, new String[]{"getId", "id"});
                if (u instanceof UUID) uuid = (UUID) u;
                Object propMap = Reflect.invokeAny(gp, new String[]{"getProperties", "properties"});
                extractProfileProperties(propMap, props);
            }
            if (uuid == null) {
                Object u = Reflect.invokeAnyTyped(p, UUID.class, new String[]{"getUUID", "m_20148_", "getUniqueID", "func_110124_au"});
                if (u instanceof UUID) uuid = (UUID) u;
            }
            return new PlayerProfile(
                name != null ? name : "Player",
                uuid != null ? uuid : UUID.randomUUID(),
                readGamemode(),
                props
            );
        } catch (Throwable t) {
            McLog.warn("读取玩家档案失败: " + t.getMessage());
            return new PlayerProfile("Player", UUID.randomUUID(), 0);
        }
    }

    private void extractProfileProperties(Object propMap, List<String[]> out) {
        if (propMap == null) return;
        try {
            Object valuesObj = Reflect.invokeAny(propMap, new String[]{"values"});
            if (!(valuesObj instanceof Iterable)) return;
            for (Object prop : (Iterable<?>) valuesObj) {
                if (prop == null) continue;
                Object pn = Reflect.invokeAny(prop, new String[]{"getName", "name"});
                Object pv = Reflect.invokeAny(prop, new String[]{"getValue", "value"});
                Object ps = Reflect.invokeAny(prop, new String[]{"getSignature", "signature"});
                if (pn instanceof String && pv instanceof String) {
                    out.add(new String[]{(String) pn, (String) pv, ps instanceof String ? (String) ps : null});
                }
            }
        } catch (Throwable ignored) {}
    }

    private int readGamemode() {
        try {
            Object conn = connection(mc());
            Object gt = Reflect.invokeAny(conn,
                new String[]{"getLocalGameMode", "m_105206_", "m_104906_"});
            return gt instanceof Enum ? ((Enum<?>) gt).ordinal() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ======================== JoinGame ========================

    @Override
    public ByteBuf buildJoinGame() throws Exception {
        return onMain(() -> {
            Object mc = mc();
            Object player = player(mc);
            Object lvl = level(player);
            Object conn = connection(mc);

            Integer eid = Reflect.readEntityId(player, lvl);
            if (eid == null) throw new IllegalStateException("读不到 A 的实体 ID");
            joinGameEntityId = eid;

            int pktId = PacketIdResolver.resolve(P + "ClientboundLoginPacket");
            if (pktId < 0) pktId = PacketIdResolver.s2cPlayId(clientProtocol, "LOGIN");
            if (pktId < 0) throw new IllegalStateException("解析不到 JoinGame 包 ID");

            if (clientProtocol >= 764) {
                return buildJoinGameModern(pktId, eid, lvl);
            }
            if (clientProtocol >= 759) {
                return buildJoinGame1201(pktId, eid, lvl, conn);
            }
            return buildJoinGamePre119(pktId, eid, lvl, conn);
        }, null, "JoinGame 构造");
    }

    /** 1.13 – 1.18.2 (393 – 758) JoinGame 精确序列化。 */
    private ByteBuf buildJoinGamePre119(int pktId, int eid, Object lvl, Object conn) {
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        buf.writeInt(eid);
        int gm = readGamemode() & 0x03;

        if (clientProtocol >= 751) {
            // 1.16.2 – 1.18.2 (751 – 758)
            buf.writeBoolean(false); // hardcore
            buf.writeByte(gm);
            buf.writeByte(-1);       // previousGamemode
            Object dimName = dimensionName(lvl);
            McFraming.writeVarInt(buf, 1);
            writeResourceLocation(buf, dimName);
            if (!writeLiveRegistry(buf, conn)) {
                throw new IllegalStateException("无法序列化 1.16.2–1.18.2 注册表 NBT");
            }
            if (!writeDimensionTypeNbt(buf, lvl, conn)) {
                throw new IllegalStateException("无法序列化 1.16.2–1.18.2 DimensionType NBT");
            }
            writeResourceLocation(buf, dimName);
            buf.writeLong(0L); // hashedSeed
            McFraming.writeVarInt(buf, 20); // maxPlayers
            int vd = viewDistance();
            McFraming.writeVarInt(buf, vd);
            if (clientProtocol >= 757) {
                // 1.18 – 1.18.2 新增 simulationDistance
                McFraming.writeVarInt(buf, vd);
            }
            buf.writeBoolean(false); // reducedDebugInfo
            buf.writeBoolean(true);  // showDeathScreen
            buf.writeBoolean(false); // isDebug
            buf.writeBoolean(isFlat(lvl));
            return buf;
        }

        if (clientProtocol >= 735) {
            // 1.16 – 1.16.1 (735 – 736)
            buf.writeByte(gm);
            buf.writeByte(-1);
            Object dimName = dimensionName(lvl);
            McFraming.writeVarInt(buf, 1);
            writeResourceLocation(buf, dimName);
            if (!writeLiveRegistry(buf, conn)) {
                throw new IllegalStateException("无法序列化 1.16 注册表 NBT");
            }
            writeResourceLocation(buf, dimensionTypeName(lvl));
            writeResourceLocation(buf, dimName);
            buf.writeLong(0L);
            buf.writeByte(20);
            McFraming.writeVarInt(buf, viewDistance());
            buf.writeBoolean(false);
            buf.writeBoolean(true);
            buf.writeBoolean(false);
            buf.writeBoolean(isFlat(lvl));
            return buf;
        }

        // 1.13 – 1.15.2 (393 – 578)
        buf.writeByte(gm);
        buf.writeInt(0); // dimension int (0=overworld)
        if (clientProtocol <= 404) {
            buf.writeByte(2); // 1.13.x difficulty
        } else if (clientProtocol >= 573) {
            buf.writeLong(0L); // 1.15.x hashedSeed
        }
        buf.writeByte(20);
        McFraming.writeString(buf, "default");
        if (clientProtocol >= 477) {
            McFraming.writeVarInt(buf, viewDistance()); // 1.14+ viewDistance
        }
        buf.writeBoolean(false); // reducedDebugInfo
        if (clientProtocol >= 573) {
            buf.writeBoolean(true); // 1.15+ showDeathScreen
        }
        return buf;
    }

    /** 1.16.2 – 1.18.2 的 JoinGame 里 DimensionType 是内联 NamedNBT CompoundTag。 */
    private boolean writeDimensionTypeNbt(ByteBuf buf, Object lvl, Object conn) {
        try {
            Object dimType = Reflect.invokeAny(lvl, new String[]{"dimensionType", "m_6042_", "func_230315_m_", "method_8597"});
            if (dimType == null) {
                Class<?> dtClass = Reflect.tryCls("net.minecraft.world.level.dimension.DimensionType");
                if (dtClass == null) dtClass = Reflect.tryCls("net.minecraft.world.DimensionType");
                if (dtClass == null) dtClass = Reflect.tryCls("net.minecraft.class_2874");
                if (dtClass != null) dimType = Reflect.invokeByReturnType(lvl, dtClass);
            }
            if (dimType != null) {
                Class<?> codecCls = Reflect.tryCls("com.mojang.serialization.Codec");
                if (codecCls != null) {
                    for (Field f : dimType.getClass().getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !codecCls.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object codec = f.get(null);
                        if (codec == null) continue;
                        Object nbt = encodeWithCodec(codec, dimType, null);
                        if (nbt != null && writeNamedNbt(buf, nbt)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 1.19 – 1.20.1：注册表塞在 JoinGame 里。 */
    private ByteBuf buildJoinGame1201(int pktId, int eid, Object lvl, Object conn) {
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        buf.writeInt(eid);
        buf.writeBoolean(false);                       // hardcore
        buf.writeByte(readGamemode());
        buf.writeByte(-1);                             // previousGamemode
        Object dimName = dimensionName(lvl);
        McFraming.writeVarInt(buf, 1);                 // levels 数量
        writeResourceLocation(buf, dimName);

        if (!writeLiveRegistry(buf, conn)) {
            throw new IllegalStateException(
                "无法序列化 A 的注册表 —— B 会因为查不到生物群系/维度 ID 而无法加载世界");
        }

        writeResourceLocation(buf, dimensionTypeName(lvl));
        writeResourceLocation(buf, dimName);
        buf.writeLong(0L);                             // hashedSeed
        McFraming.writeVarInt(buf, 20);                // maxPlayers
        int vd = viewDistance();
        McFraming.writeVarInt(buf, vd);                // viewDistance
        McFraming.writeVarInt(buf, vd);                // simulationDistance
        buf.writeBoolean(false);                       // reducedDebugInfo
        buf.writeBoolean(true);                        // showDeathScreen
        buf.writeBoolean(false);                       // isDebug
        buf.writeBoolean(isFlat(lvl));
        buf.writeBoolean(false);                       // lastDeathLocation: 空 (1.19+)
        if (clientProtocol >= 763) {                   // portalCooldown: 1.20 (763) 新增
            McFraming.writeVarInt(buf, 0);
        }

        McLog.info("JoinGame(pre-1.20.2 格式, protocol=" + clientProtocol + ") 构造完成: "
            + buf.readableBytes() + " bytes, entityId=" + eid);
        return buf;
    }

    /**
     * 1.20.2+ (764+)：不含注册表 —— 那些在 configuration 阶段已经逐条发过了。
     *
     * 字段（1.20.2 起基本稳定，1.21.2 加了 seaLevel）：
     *   EntityID(int) Hardcore(bool) DimensionNames(数组) MaxPlayers(VarInt)
     *   ViewDistance SimulationDistance ReducedDebugInfo ShowDeathScreen
     *   DoLimitedCrafting DimensionType(VarInt 注册表 ID) DimensionName
     *   HashedSeed(long) GameMode(byte) PreviousGameMode(byte)
     *   IsDebug IsFlat DeathLocation(Optional) PortalCooldown(VarInt)
     *   [SeaLevel(VarInt) — 1.21.2+] EnforcesSecureChat(bool)
     */
    private ByteBuf buildJoinGameModern(int pktId, int eid, Object lvl) {
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        buf.writeInt(eid);
        buf.writeBoolean(false);                       // hardcore

        Object dimName = dimensionName(lvl);
        McFraming.writeVarInt(buf, 1);                 // dimension names 数量
        writeResourceLocation(buf, dimName);

        McFraming.writeVarInt(buf, 20);                // maxPlayers
        int vd = viewDistance();
        McFraming.writeVarInt(buf, vd);                // viewDistance
        McFraming.writeVarInt(buf, vd);                // simulationDistance
        buf.writeBoolean(false);                       // reducedDebugInfo
        buf.writeBoolean(true);                        // showDeathScreen
        buf.writeBoolean(false);                       // doLimitedCrafting

        // 1.20.2 起 DimensionType 是注册表里的数字 ID，不是 ResourceLocation
        McFraming.writeVarInt(buf, dimensionTypeId(lvl));
        writeResourceLocation(buf, dimName);           // dimension name
        buf.writeLong(0L);                             // hashedSeed
        buf.writeByte(readGamemode());
        buf.writeByte(-1);                             // previousGamemode
        buf.writeBoolean(false);                       // isDebug
        buf.writeBoolean(isFlat(lvl));
        buf.writeBoolean(false);                       // deathLocation: 空
        McFraming.writeVarInt(buf, 0);                 // portalCooldown
        if (clientProtocol >= 768) {                   // 1.21.2+
            McFraming.writeVarInt(buf, 63);            // seaLevel
        }
        if (clientProtocol >= 776) {                   // 26.2+
            buf.writeBoolean(false);                   // onlineMode
        }
        buf.writeBoolean(false);                       // enforcesSecureChat

        McLog.info("JoinGame(1.20.2+ 格式, protocol=" + clientProtocol + ") 构造完成: "
            + buf.readableBytes() + " bytes, entityId=" + eid);
        return buf;
    }

    /** 维度类型在注册表里的数字 ID（1.20.2+ 的 JoinGame 用它而非名字）。 */
    private int dimensionTypeId(Object lvl) {
        try {
            Object holder = Reflect.invokeAny(lvl,
                new String[]{"dimensionTypeRegistration", "dimensionTypeId", "m_204156_"});
            if (holder != null) {
                Object conn = connection(mc());
                Object ra = conn != null ? registryAccess(conn) : null;
                if (ra != null) {
                    Object keyOpt = Reflect.invokeAny(holder, new String[]{"unwrapKey", "m_203543_"});
                    if (keyOpt instanceof java.util.Optional && ((java.util.Optional<?>) keyOpt).isPresent()) {
                        Object rk = ((java.util.Optional<?>) keyOpt).get();
                        Object loc = Reflect.invokeAny(rk, new String[]{"location", "m_135782_"});
                        for (Object[] reg : synchronizedRegistries(ra)) {
                            if (!"minecraft:dimension_type".equals(reg[0].toString())) continue;
                            Object value = Reflect.invokeAny(holder, new String[]{"value", "m_203334_"});
                            Object id = Reflect.invokeAnyTyped(reg[1], Integer.class,
                                new String[]{"getId", "m_7447_"}, value);
                            if (id instanceof Integer && (Integer) id >= 0) return (Integer) id;
                        }
                        McLog.warn("dimensionTypeId: 在注册表里找不到 " + loc + "，用 0");
                    }
                }
            }
        } catch (Throwable t) {
            McLog.warn("dimensionTypeId 读取失败: " + t.getMessage());
        }
        return 0;   // overworld 通常是 0
    }

    /**
     * 写 A 的真实注册表 NBT。
     *
     * 必须是真的：区块里的生物群系调色板按数字 ID 引用注册表，硬编码一个最小集的话
     * B 查不到那些 ID，整块区块解析失败 —— 表现为世界加载不出来、tick 不跑。
     * 实测真实注册表约 35 KB，硬编码最小集只有 2 KB。
     */
    private boolean writeLiveRegistry(ByteBuf buf, Object conn) {
        try {
            Object ra = registryAccess(conn);
            if (ra == null) {
                McLog.warn("注册表: 拿不到 RegistryAccess/DynamicRegistries");
                return false;
            }
            Object nbt = encodeRegistry(ra);
            if (nbt == null) return false;

            int before = buf.readableBytes();
            if (!writeNamedNbt(buf, nbt)) return false;
            McLog.info("注册表: 已写入 " + (buf.readableBytes() - before) + " bytes");
            return true;
        } catch (Throwable t) {
            McLog.warn("注册表写入失败: " + t);
            return false;
        }
    }

    /** 用 MC 自己的 Codec 把 RegistryAccess / DynamicRegistries 编码成 NBT。 */
    private Object encodeRegistry(Object registryAccess) {
        try {
            Class<?> codecClass = Reflect.cls("com.mojang.serialization.Codec");
            Object nbtOps = registryOps();
            if (nbtOps == null) nbtOps = resolveNbtOpsInstance();
            if (nbtOps == null) return null;

            Object target = registryAccess;
            for (String fn : new String[]{
                    "net.minecraft.core.RegistryAccess$Frozen",
                    "net.minecraft.class_5455$class_6890"}) {
                Class<?> frozen = Reflect.tryCls(fn);
                if (frozen != null && !frozen.isInstance(registryAccess)) {
                    Object fr = Reflect.invokeByReturnType(registryAccess, frozen);
                    if (fr != null) { target = fr; break; }
                }
            }

            Method encodeStart = null;
            for (Method m : codecClass.getMethods()) {
                if (m.getParameterCount() == 2 && m.getName().equals("encodeStart")) {
                    m.setAccessible(true); encodeStart = m; break;
                }
            }
            if (encodeStart == null) return null;

            List<Class<?>> holderClasses = new ArrayList<>();
            for (String hn : new String[]{
                    "net.minecraft.resources.RegistryDataLoader",
                    "net.minecraft.core.RegistrySynchronization",
                    "net.minecraft.resources.RegistrySynchronization",
                    "net.minecraft.class_7780",
                    "net.minecraft.core.registries.Registries",
                    P + "ClientboundLoginPacket",
                    "net.minecraft.network.play.server.SJoinGamePacket",
                    "net.minecraft.class_2678",
                    "net.minecraft.core.RegistryAccess",
                    "net.minecraft.util.registry.DynamicRegistries",
                    "net.minecraft.util.registry.DynamicRegistries$Impl",
                    "net.minecraft.class_5455",
                    "net.minecraft.class_5455$class_5457"}) {
                Class<?> h = Reflect.tryCls(hn);
                if (h != null && !holderClasses.contains(h)) holderClasses.add(h);
            }
            int joinId = PacketIdResolver.s2cPlayId(clientProtocol, "LOGIN");
            Class<?> joinCls = PacketIdResolver.clientboundPlayClassById(joinId);
            if (joinCls != null && !holderClasses.contains(joinCls)) holderClasses.add(joinCls);
            for (Class<?> rc = registryAccess.getClass(); rc != null && rc != Object.class; rc = rc.getSuperclass()) {
                if (!holderClasses.contains(rc)) holderClasses.add(rc);
                if (rc.getEnclosingClass() != null && !holderClasses.contains(rc.getEnclosingClass())) {
                    holderClasses.add(rc.getEnclosingClass());
                }
                for (Class<?> itf : rc.getInterfaces()) {
                    if (!holderClasses.contains(itf)) holderClasses.add(itf);
                }
            }

            String firstError = null;
            int tried = 0;
            for (Class<?> holder : holderClasses) {
                for (Field f : holder.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!codecClass.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object codec = f.get(null);
                        if (codec == null) continue;
                        tried++;
                        Object res = encodeStart.invoke(codec, nbtOps, target);
                        Object opt = invokeInterfaceNoArg(res, "result");
                        if (opt == null) opt = Reflect.invokeAny(res, new String[]{"result"});
                        if (opt instanceof java.util.Optional && ((java.util.Optional<?>) opt).isPresent()) {
                            McLog.info("注册表: 使用 " + holder.getSimpleName() + "." + f.getName());
                            return ((java.util.Optional<?>) opt).get();
                        }
                        if (firstError == null) {
                            Object err = invokeInterfaceNoArg(res, "error");
                            if (err instanceof java.util.Optional && ((java.util.Optional<?>) err).isPresent()) {
                                firstError = holder.getSimpleName() + "." + f.getName()
                                    + " → " + ((java.util.Optional<?>) err).get();
                            }
                        }
                    } catch (Throwable t) {
                        Throwable root = t;
                        while (root.getCause() != null) root = root.getCause();
                        if (firstError == null) {
                            firstError = holder.getSimpleName() + "." + f.getName() + " 抛 "
                                + root.getClass().getSimpleName() + ": " + root.getMessage();
                        }
                    }
                }
            }
            McLog.warn("注册表: 所有候选 Codec 都编不出来（试了 " + tried + " 个"
                + (firstError != null ? "，首个失败: " + firstError : "") + "）");
            return null;
        } catch (Throwable t) {
            McLog.warn("注册表编码异常: " + t);
            return null;
        }
    }

    private Object resolveNbtOpsInstance() {
        for (String cn : new String[]{
                "net.minecraft.nbt.NbtOps",
                "net.minecraft.nbt.NBTDynamicOps",
                "net.minecraft.class_2509"}) {
            Class<?> c = Reflect.tryCls(cn);
            if (c != null) {
                Object inst = Reflect.findStaticFieldByType(c, c);
                if (inst != null) return inst;
            }
        }
        Class<?> dynOpsCls = Reflect.tryCls("com.mojang.serialization.DynamicOps");
        if (dynOpsCls != null) {
            for (Class<?> cand : Reflect.loadedClasses()) {
                if (cand == null || !dynOpsCls.isAssignableFrom(cand) || cand.isInterface()) continue;
                String n = cand.getName();
                if (n.startsWith("com.mojang.") || n.startsWith("com.mcrelay.")) continue;
                Object inst = Reflect.findStaticFieldByType(cand, cand);
                if (inst != null) return inst;
            }
        }
        return null;
    }

    /**
     * 按**文件格式**写 NBT：[类型][名字长度][名字][内容]（即 NamedNBT，开头 0A 00 00）。
     * 兼容 Mojmap (NbtIo)、1.16.5 Forge (CompressedStreamTools)、Fabric (class_2507) 与原版混淆 Tag.write(DataOutput)。
     */
    private boolean writeNamedNbt(ByteBuf buf, Object nbt) {
        if (nbt == null) return false;
        try {
            for (String ioName : new String[]{
                    "net.minecraft.nbt.NbtIo",
                    "net.minecraft.nbt.CompressedStreamTools",
                    "net.minecraft.class_2507"}) {
                Class<?> nbtIo = Reflect.tryCls(ioName);
                if (nbtIo == null) continue;
                for (Method m : nbtIo.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 2) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (!p[0].isInstance(nbt) || p[1] != java.io.DataOutput.class) continue;
                    String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("unnamed") || n.contains("any")) continue;
                    m.setAccessible(true);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    m.invoke(null, nbt, new java.io.DataOutputStream(bos));
                    if (bos.size() > 0) {
                        buf.writeBytes(bos.toByteArray());
                        return true;
                    }
                }
            }
            // 原版混淆通用回退：CompoundTag.write(DataOutput) 仅写内容，前置 0x0A + 0x0000 即标准 NamedNBT
            for (Class<?> c = nbt.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == java.io.DataOutput.class
                        && m.getReturnType() == void.class) {
                        m.setAccessible(true);
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
                        dos.writeByte(0x0A);
                        dos.writeShort(0);
                        m.invoke(nbt, dos);
                        dos.flush();
                        buf.writeBytes(bos.toByteArray());
                        return true;
                    }
                }
            }
            McLog.warn("注册表: 找不到 NamedNBT 写出方法 (" + nbt.getClass().getName() + ")");
            return false;
        } catch (Throwable t) {
            McLog.warn("注册表: NamedNBT 写入异常: " + t);
            return false;
        }
    }

    /**
     * 把 NBT 按**网络格式**写进 buf。
     *
     * 网络格式是匿名 NBT：[类型字节][内容]，没有名字字段。
     * 而 NbtIo.write(CompoundTag, DataOutput) 产出的是文件格式：[类型][名字长度][名字][内容]，
     * 多出的两字节名字长度会让接收方后续全部错位 —— 实测 ViaVersion 报
     * "Could not find tag with ID 97/110/-128"，那些数字其实是字符串内容被当成了类型标记。
     *
     * 所以优先用 FriendlyByteBuf.writeNbt()（MC 自己的网络序列化），
     * 只有在找不到它时才退回 NbtIo.writeUnnamedTag。
     */
    private boolean writeNbt(ByteBuf buf, Object nbt) {
        // 路径 1: FriendlyByteBuf.writeNbt(Tag) —— 就是网络格式
        try {
            Class<?> fbbClass = Reflect.tryCls("net.minecraft.network.FriendlyByteBuf");
            Class<?> tagClass = Reflect.tryCls("net.minecraft.nbt.Tag");
            if (fbbClass != null && tagClass != null && tagClass.isInstance(nbt)) {
                Object fbb = fbbClass.getConstructor(ByteBuf.class).newInstance(buf);
                // 形参必须正好是 Tag 接口（writeNbt(Tag)），不能是 CompoundTag 或别的 ——
                // 按"名字含 nbt"松匹配会命中 writeNbtWithCodec 这类签名不同的方法。
                for (Method m : fbbClass.getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != tagClass) continue;
                    m.setAccessible(true);
                    int before = buf.readableBytes();
                    try {
                        m.invoke(fbb, nbt);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (buf.readableBytes() > before) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            McLog.warn("注册表: FriendlyByteBuf.writeNbt 失败: " + t.getMessage());
        }

        // 路径 2: NbtIo.writeUnnamedTag / writeAnyTag —— 同样是匿名格式
        try {
            Class<?> nbtIo = Reflect.cls("net.minecraft.nbt.NbtIo");
            Class<?> tagClass = Reflect.tryCls("net.minecraft.nbt.Tag");
            if (tagClass == null || !tagClass.isInstance(nbt)) return false;
            for (Method m : nbtIo.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 2) continue;
                Class<?>[] p = m.getParameterTypes();
                if (!p[0].isAssignableFrom(nbt.getClass())) continue;
                if (p[1] != java.io.DataOutput.class) continue;
                // 只认名字里带 unnamed/any 的 —— 普通 write() 是文件格式，会多写名字字段
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (!n.contains("unnamed") && !n.contains("any")) continue;
                m.setAccessible(true);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                m.invoke(null, nbt, new java.io.DataOutputStream(bos));
                buf.writeBytes(bos.toByteArray());
                return true;
            }
            McLog.warn("注册表: 找不到写匿名 NBT 的方法（网络格式不能用 NbtIo.write，那是文件格式）");
            return false;
        } catch (Throwable t) {
            McLog.warn("注册表: NBT 写入异常: " + t);
            return false;
        }
    }

    // ======================== configuration 阶段（1.20.2+）========================

    /**
     * 逐条发送注册表。1.20.2 起注册表从 JoinGame 挪到了 configuration 阶段，
     * 每个注册表一个 RegistryData 包。
     *
     * 三条硬约束（错一条 B 就连不上或区块解不出来）：
     *  1. 条目的**发送顺序就是它的数字 ID**。区块里的生物群系调色板按数字 ID 引用，
     *     顺序和 A 不一致的话区块解码直接坏掉，而且不会报错，只是渲染不出来。
     *     所以必须按注册表自身的迭代顺序发，不能用 HashMap 之类打乱。
     *  2. 漏发条目 → 客户端一旦引用到不存在的 ID 就断开连接。所以全量发，
     *     不做"双方都认识就省略"的优化。
     *  3. 只有 registrySynchronized 的注册表要发（维度类型、生物群系、聊天类型等），
     *     方块/物品那些是双方硬编码的，不走网络。
     */
    @Override
    public int sendConfigurationPackets(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object conn = connection(mc());
            if (conn == null) {
                McLog.warn("configuration: 拿不到 ClientPacketListener");
                return list;
            }
            Object ra = registryAccess(conn);
            if (ra == null) {
                McLog.warn("configuration: 拿不到 RegistryAccess");
                return list;
            }

            // 包类名在不同版本可能换位置，多试几个。
            // 不用 tryCls 预过滤 —— resolveConfiguration 有硬编码兜底，
            // 类加载失败时它仍能按简单类名给出 ID。
            int pktId = -1;
            for (String cn : new String[]{
                    "net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket",
                    "net.minecraft.network.protocol.common.ClientboundRegistryDataPacket",
                    "net.minecraft.network.protocol.game.ClientboundRegistryDataPacket"}) {
                pktId = PacketIdResolver.resolveConfiguration(cn);
                if (pktId >= 0) break;
            }
            if (pktId < 0) {
                pktId = clientProtocol < 766 ? 0x05 : 0x07;
                McLog.warn("configuration: RegistryData 回退使用协议 " + clientProtocol + " 默认 ID 0x" + Integer.toHexString(pktId));
            }

            if (clientProtocol < 766) {
                // 1.20.2 - 1.20.4 (764 - 765): 单个全量匿名网络 NBT CompoundTag
                Object nbt = encodeRegistry(ra);
                if (nbt != null) {
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, pktId);
                    if (writeNbt(b, nbt)) {
                        list.add(b);
                        McLog.info("configuration(764-765): 已序列化全量注册表 CompoundTag (" + b.readableBytes() + " bytes)");
                    } else {
                        b.release();
                    }
                }
            } else {
                for (Object[] reg : synchronizedRegistries(ra)) {
                    try {
                        ByteBuf b = buildRegistryDataPacket(pktId, reg[0], reg[1]);
                        if (b != null) list.add(b);
                    } catch (Throwable t) {
                        McLog.warn("configuration: 注册表 " + reg[0] + " 序列化失败: " + t.getMessage());
                    }
                }
                McLog.info("configuration: 序列化 " + list.size() + " 个注册表");
            }

            // 特性开关（FeatureFlags）必须在 tag 之前发。
            //
            // 客户端用它决定哪些方块/物品"存在"。开关没同步时，客户端按最小特性集
            // 处理，一部分方块的行为被裁掉 —— 实测梯子爬不上去就是这个原因
            // （攀爬判定依赖方块被启用），而水有阻力是因为流体走的是另一套判据。
            // 高版本服务器往往开了额外特性，缺这个包时错得更多。
            try {
                ByteBuf feat = buildEnabledFeaturesPacket();
                if (feat != null) {
                    list.add(feat);
                    McLog.info("configuration: 已补发 UpdateEnabledFeatures");
                } else {
                    McLog.warn("configuration: UpdateEnabledFeatures 构造失败 —— 部分方块行为会不正常");
                }
            } catch (Throwable t) {
                McLog.warn("configuration: UpdateEnabledFeatures 序列化失败: " + t);
            }

            // 标签（tags）必须补上，否则 B 的碰撞行为全错。
            //
            // 客户端靠 tag 判断方块行为：FluidTags.WATER/LAVA 决定液体有没有阻力，
            // BlockTags.CLIMBABLE 决定梯子藤蔓能不能爬。tag 为空时客户端认为
            // "水不是水、梯子不是梯子" —— 方块显示完全正常（那只看 BlockState），
            // 但所有依赖 tag 的碰撞和交互全失效：液体没阻力、梯子摸不到、
            // 有时直接穿墙、Grim 报模拟不一致。
            //
            // 1.20.1 的 tag 是 PLAY 阶段发的，重进世界服务器会重发，所以"进出一次就好了"；
            // 1.21.8 移到了 configuration 阶段，我不发就永远没有，重进也不补 ——
            // 这正是 1.21.8 比 1.20.1 问题多的根本原因。
            try {
                ByteBuf tags = buildUpdateTagsPacket(ra);
                if (tags != null) {
                    list.add(tags);
                    McLog.info("configuration: 已补发 UpdateTags（碰撞/流体/攀爬判据）");
                } else {
                    McLog.warn("configuration: UpdateTags 构造失败 —— B 的液体和梯子碰撞会不正常");
                }
            } catch (Throwable t) {
                McLog.warn("configuration: UpdateTags 序列化失败: " + t);
            }
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "注册表");

        for (ByteBuf b : out) sender.send(b);
        return out.size();
    }

    /**
     * 列出需要同步给客户端的注册表：{ResourceLocation key, Registry 实例}。
     *
     * RegistrySynchronization.NETWORK_SYNCHRONIZED（或同类字段）里记着哪些要发。
     * 拿不到就退回遍历 RegistryAccess 里所有 registry，按"名字在已知同步列表里"筛。
     */
    private List<Object[]> synchronizedRegistries(Object ra) {
        List<Object[]> result = new ArrayList<>();
        try {
            // registries() 返回 Stream<RegistryEntry>。名字在混淆环境里不可靠，
            // 所以先按名试，再退回"扫所有无参方法，取返回 Stream 的那个"。
            Object stream = Reflect.invokeAny(ra, new String[]{"registries", "m_206193_"});
            if (stream == null) {
                stream = findRegistryStream(ra);
            }
            if (stream == null) {
                McLog.warn("configuration: 在 " + ra.getClass().getName()
                    + " 上找不到 registries() —— 无参方法有: " + listNoArgMethods(ra));
                return result;
            }

            // Stream 直接用 Java API 收集，别走反射 ——
            // toList()/collect() 是 Stream 接口的默认方法，实现类（ReferencePipeline$3）
            // 自己没声明，Reflect.invoke 只翻实际类的方法表，必然找不到。
            java.util.List<?> all;
            if (stream instanceof java.util.stream.Stream) {
                all = ((java.util.stream.Stream<?>) stream).collect(java.util.stream.Collectors.toList());
            } else if (stream instanceof Iterable) {
                java.util.List<Object> tmp = new ArrayList<>();
                for (Object o : (Iterable<?>) stream) tmp.add(o);
                all = tmp;
            } else {
                McLog.warn("configuration: registries() 返回了无法遍历的类型: "
                    + stream.getClass().getName());
                return result;
            }
            McLog.info("configuration: RegistryAccess 里共有 " + all.size() + " 个注册表");

            java.util.Set<String> want = networkSyncKeys();
            java.util.List<String> skipped = new ArrayList<>();
            for (Object entry : all) {
                try {
                    Object key = invokeInterfaceNoArg(entry, "key");
                    Object value = invokeInterfaceNoArg(entry, "value");
                    if (key == null) key = Reflect.invokeAny(entry, new String[]{"key", "f_206227_"});
                    if (value == null) value = Reflect.invokeAny(entry, new String[]{"value", "f_206228_"});
                    if (key == null || value == null) {
                        // record 的访问器名可能不同，按类型兜一层
                        Object[] kv = entryKeyValue(entry);
                        if (kv == null) continue;
                        key = kv[0];
                        value = kv[1];
                    }
                    Object loc = invokeInterfaceNoArg(key, "location");
                    if (loc == null) loc = Reflect.invokeAny(key, new String[]{"location", "m_135782_"});
                    if (loc == null) loc = key;   // key 本身可能就是 ResourceLocation
                    String name = loc.toString();
                    if (!want.isEmpty() && !want.contains(name)) {
                        skipped.add(name);
                        continue;
                    }
                    result.add(new Object[]{loc, value});
                } catch (Throwable ignored) {}
            }
            if (result.isEmpty() && !skipped.isEmpty()) {
                McLog.warn("configuration: 同步名单一个都没匹配上。实际注册表名: "
                    + String.join(", ", skipped.subList(0, Math.min(skipped.size(), 25))));
            }
        } catch (Throwable t) {
            McLog.warn("configuration: 列举注册表失败: " + t);
        }
        return result;
    }

    /** 扫无参方法，找返回 Stream 的那个（registries()）。 */
    private Object findRegistryStream(Object ra) {
        for (Class<?> c = ra.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!java.util.stream.Stream.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(ra);
                    if (r != null) {
                        McLog.info("configuration: registries() 按类型匹配到 "
                            + c.getSimpleName() + "." + m.getName());
                        return r;
                    }
                } catch (Throwable ignored) {}
            }
        }
        // 接口上的默认方法
        for (Class<?> itf : ra.getClass().getInterfaces()) {
            for (Method m : itf.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!java.util.stream.Stream.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(ra);
                    if (r != null) {
                        McLog.info("configuration: registries() 从接口 " + itf.getSimpleName()
                            + "." + m.getName() + " 匹配到");
                        return r;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** RegistryEntry 是 record，访问器名可能变；按返回类型取 key 和 value。 */
    private Object[] entryKeyValue(Object entry) {
        Object key = null, value = null;
        Class<?> rkClass = Reflect.tryCls("net.minecraft.resources.ResourceKey");
        Class<?> regClass = Reflect.tryCls("net.minecraft.core.Registry");
        if (rkClass != null) key = Reflect.invokeByReturnType(entry, rkClass);
        if (regClass != null) value = Reflect.invokeByReturnType(entry, regClass);
        return (key != null && value != null) ? new Object[]{key, value} : null;
    }

    private String listNoArgMethods(Object o) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Class<?> c = o.getClass(); c != null && c != Object.class && n < 30; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                sb.append(m.getName()).append("->")
                  .append(m.getReturnType().getSimpleName()).append(' ');
                if (++n >= 30) break;
            }
        }
        return sb.toString();
    }

    /**
     * 需要同步的注册表名单。
     *
     * 先试着从 RegistrySynchronization 里读（跨版本自动跟着变），读不到就用
     * 1.20.2–1.21.x 的已知名单兜底。
     */
    private java.util.Set<String> networkSyncKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        try {
            Class<?> rs = Reflect.tryCls("net.minecraft.resources.RegistrySynchronization");
            if (rs == null) rs = Reflect.tryCls("net.minecraft.core.RegistrySynchronization");
            if (rs != null) {
                for (Field f : rs.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!java.util.Map.class.isAssignableFrom(f.getType())
                        && !java.util.Set.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(null);
                    java.util.Collection<?> c = v instanceof java.util.Map
                        ? ((java.util.Map<?, ?>) v).keySet()
                        : (v instanceof java.util.Collection ? (java.util.Collection<?>) v : null);
                    if (c == null || c.isEmpty()) continue;
                    for (Object k : c) {
                        Object loc = Reflect.invokeAny(k, new String[]{"location", "m_135782_"});
                        if (loc != null) keys.add(loc.toString());
                    }
                    if (!keys.isEmpty()) {
                        McLog.info("configuration: 同步名单来自 " + rs.getSimpleName() + "." + f.getName()
                            + "（" + keys.size() + " 个）");
                        return keys;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 兜底：1.20.2–1.21.x 的同步注册表
        java.util.Collections.addAll(keys,
            "minecraft:dimension_type",
            "minecraft:worldgen/biome",
            "minecraft:chat_type",
            "minecraft:trim_pattern",
            "minecraft:trim_material",
            "minecraft:wolf_variant",
            "minecraft:painting_variant",
            "minecraft:damage_type",
            "minecraft:banner_pattern",
            "minecraft:enchantment",
            "minecraft:jukebox_song",
            "minecraft:instrument",
            "minecraft:test_environment",
            "minecraft:test_instance",
            "minecraft:cat_variant",
            "minecraft:cow_variant",
            "minecraft:chicken_variant",
            "minecraft:frog_variant",
            "minecraft:pig_variant",
            "minecraft:dialog");
        McLog.info("configuration: 使用兜底同步名单（" + keys.size() + " 个）");
        return keys;
    }

    /**
     * 构造 ClientboundUpdateTagsPacket：把 A 内存里每个注册表的 tag 全量发给 B。
     *
     * 不手写 NBT —— tag 的线格式是 {注册表名: {tag名: [数字ID数组]}}，
     * 数字 ID 必须和 B 的注册表顺序完全一致，手拼错一个字节整包报废。
     * 直接调 MC 自己的 TagNetworkSerialization.serializeToNetwork(Registry)
     * 拿到 NetworkPayload，再让包对象自己 write()。
     *
     * 为什么不用现成的 serializeTagsToNetwork(LayeredRegistryAccess)：
     * 那个要 server 侧的 RegistryLayer，客户端没有。逐个注册表调私有的
     * serializeToNetwork 反而干净。
     */
    private ByteBuf buildUpdateTagsPacket(Object ra) throws Exception {
        Class<?> pktClass = Reflect.tryCls(
            "net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket");
        if (pktClass == null) {
            pktClass = Reflect.tryCls("net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket");
        }
        Class<?> tnsClass = Reflect.tryCls("net.minecraft.tags.TagNetworkSerialization");
        if (pktClass == null || tnsClass == null) {
            McLog.warn("configuration: 找不到 UpdateTagsPacket / TagNetworkSerialization");
            return null;
        }

        // 私有静态 serializeToNetwork(Registry) → NetworkPayload
        Class<?> payloadClass = Reflect.tryCls(
            "net.minecraft.tags.TagNetworkSerialization$NetworkPayload");
        Method toNetwork = null;
        for (Method m : tnsClass.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (payloadClass != null && !payloadClass.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            toNetwork = m;
            break;
        }
        if (toNetwork == null) {
            McLog.warn("configuration: 在 TagNetworkSerialization 上找不到 serializeToNetwork(Registry)");
            return null;
        }

        // 遍历 RegistryAccess 里所有注册表，逐个序列化它的 tag。
        // 空的（大多数注册表没有 tag）不放进 map，跟原版行为一致。
        java.util.Map<Object, Object> tagMap = new java.util.LinkedHashMap<>();
        int withTags = 0;
        for (Object[] entry : allRegistries(ra)) {
            Object regKey = entry[0];      // ResourceKey<? extends Registry<?>>
            Object registry = entry[1];
            if (regKey == null || registry == null) continue;
            try {
                Object payload = toNetwork.invoke(null, registry);
                if (payload == null) continue;
                Object empty = Reflect.invokeAnyTyped(payload, Boolean.class,
                    new String[]{"isEmpty"});
                if (empty instanceof Boolean && (Boolean) empty) continue;
                tagMap.put(regKey, payload);
                withTags++;
            } catch (Throwable ignored) {
                // 某个注册表序列化不出来不致命，跳过它比整包不发好
            }
        }
        if (tagMap.isEmpty()) {
            McLog.warn("configuration: 一个注册表的 tag 都没序列化出来");
            return null;
        }
        McLog.info("configuration: tag 覆盖 " + withTags + " 个注册表");

        Object pkt = PacketCodec.construct(pktClass, tagMap);
        if (pkt == null) {
            McLog.warn("configuration: 构造不出 UpdateTagsPacket(Map)");
            return null;
        }
        int id = PacketIdResolver.resolveConfiguration(pktClass.getName());
        if (id < 0) {
            McLog.warn("configuration: 解析不到 UpdateTags 的包 ID");
            return null;
        }
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        if (!writePacketBody(pktClass, pkt, buf)) {
            buf.release();
            return null;
        }
        return buf;
    }

    /**
     * RegistryAccess 里的**全部**注册表 {ResourceKey, Registry}。
     *
     * 和 synchronizedRegistries 不同：tag 要覆盖 block/fluid/item/entity_type 这些
     * 内置注册表（碰撞判据就在 block 和 fluid 的 tag 里），
     * 而那些注册表不在"需要同步内容"的名单上。
     */
    private List<Object[]> allRegistries(Object ra) {
        List<Object[]> out = new ArrayList<>();
        try {
            Object stream = Reflect.invokeAny(ra, new String[]{"registries", "m_206193_"});
            if (stream == null) stream = findRegistryStream(ra);
            java.util.List<?> all;
            if (stream instanceof java.util.stream.Stream) {
                all = ((java.util.stream.Stream<?>) stream).collect(java.util.stream.Collectors.toList());
            } else if (stream instanceof Iterable) {
                java.util.List<Object> tmp = new ArrayList<>();
                for (Object o : (Iterable<?>) stream) tmp.add(o);
                all = tmp;
            } else {
                return out;
            }
            for (Object e : all) {
                // RegistryEntry(key, value) —— 两个访问器都在接口/record 上
                Object k = invokeInterfaceNoArg(e, "key");
                Object v = invokeInterfaceNoArg(e, "value");
                if (k != null && v != null) out.add(new Object[]{k, v});
            }
        } catch (Throwable t) {
            McLog.warn("configuration: 遍历全部注册表失败: " + t);
        }
        return out;
    }

    /**
     * 构造 ClientboundUpdateEnabledFeaturesPacket：把 A 启用的特性开关同步给 B。
     *
     * 客户端用 FeatureFlagSet 判断哪些方块/物品"存在"。不发这个包，B 按最小特性集
     * 处理，一部分方块行为被裁掉。高版本服务器常开额外特性（trade_rebalance、
     * redstone_experiments、minecart_improvements），缺这个包时错得更多。
     *
     * 取值链：ClientPacketListener.enabledFeatures() → FeatureFlagSet，
     * 再用 FeatureFlags.REGISTRY.toNames(set) 转成 Set<ResourceLocation>。
     */
    private ByteBuf buildEnabledFeaturesPacket() throws Exception {
        Class<?> pktClass = Reflect.tryCls(
            "net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket");
        if (pktClass == null) return null;

        Object conn = connection(mc());
        if (conn == null) return null;

        Class<?> setClass = Reflect.tryCls("net.minecraft.world.flag.FeatureFlagSet");
        if (setClass == null) return null;
        Object flagSet = Reflect.invokeByReturnType(conn, setClass);
        if (flagSet == null) flagSet = Reflect.findFieldByType(conn, setClass);
        if (flagSet == null) {
            McLog.warn("configuration: 拿不到 A 的 enabledFeatures");
            return null;
        }

        Class<?> flagsClass = Reflect.tryCls("net.minecraft.world.flag.FeatureFlags");
        Class<?> regClass = Reflect.tryCls("net.minecraft.world.flag.FeatureFlagRegistry");
        if (flagsClass == null || regClass == null) return null;
        Object registry = Reflect.findStaticFieldByType(flagsClass, regClass);
        if (registry == null) {
            McLog.warn("configuration: 找不到 FeatureFlags.REGISTRY");
            return null;
        }

        Method toNames = Reflect.findMethodByReturnAndParams(regClass, java.util.Set.class, setClass);
        if (toNames == null) {
            McLog.warn("configuration: 在 FeatureFlagRegistry 上找不到 toNames(FeatureFlagSet)");
            return null;
        }
        toNames.setAccessible(true);
        Object names = toNames.invoke(registry, flagSet);
        if (!(names instanceof java.util.Set)) {
            McLog.warn("configuration: FeatureFlagRegistry.toNames 返回了 " + names);
            return null;
        }
        McLog.info("configuration: 特性开关 " + ((java.util.Set<?>) names).size() + " 项: " + names);

        Object pkt = PacketCodec.construct(pktClass, names);
        if (pkt == null) {
            McLog.warn("configuration: 构造不出 UpdateEnabledFeaturesPacket(Set)");
            return null;
        }
        int id = PacketIdResolver.resolveConfiguration(pktClass.getName());
        if (id < 0) return null;
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        if (!writePacketBody(pktClass, pkt, buf)) {
            buf.release();
            return null;
        }
        return buf;
    }

    /** 调包对象的 write(buf)/STREAM_CODEC 把包体写进 buf。 */
    private boolean writePacketBody(Class<?> pktClass, Object pkt, ByteBuf buf) {
        ByteBuf body = PacketCodec.encode(pktClass, pkt);
        if (body == null) return false;
        try {
            // encode 出来的开头是包 ID（PacketCodec 自己写的），跳过它只取包体
            McFraming.readVarInt(body);
            buf.writeBytes(body);
            return true;
        } finally {
            body.release();
        }
    }

    /**
     * 构造一个 RegistryData 包：[registryId][entryCount]{[entryId][hasData][NBT]}*
     *
     * 每个条目用它自己的 Codec 编成 NBT。条目顺序 = 注册表迭代顺序 = 数字 ID，
     * 绝不能重排。
     */
    private ByteBuf buildRegistryDataPacket(int pktId, Object registryKey, Object registry) throws Exception {
        // 拿注册表元素的 Codec：优先用注册表自己声明的
        Object elementCodec = registryElementCodec(registryKey, registry);

        List<Object[]> entries = new ArrayList<>();   // {ResourceLocation, NBT or null}
        StringBuilder diag = new StringBuilder();
        Iterable<?> iterable = collectHolders(registry, diag);
        if (iterable == null) {
            McLog.warn("configuration: " + registryKey + " 取不到 Holder 列表，候选方法: " + diag);
            return null;
        }

        int noKey = 0, noNbt = 0;
        int total = 0;
        String firstNbtError = null;
        for (Object holder : iterable) {
            total++;
            // unwrapKey()/value() 声明在 Holder 接口上，实现类的方法表里没有 ——
            // 必须用 getMethods() 翻继承链，Reflect.invoke 在这里会全部落空。
            Object loc = holderLocation(holder);
            if (loc == null) { noKey++; continue; }
            String[] vErr = new String[1];
            Object value = invokeInterfaceNoArg(holder, "value", vErr);
            if (value == null) value = Reflect.invokeAny(holder, new String[]{"value", "m_203334_"});
            if (value == null) {
                noNbt++;
                if (firstNbtError == null) {
                    firstNbtError = "holder.value() 返回 null（holder 类型 "
                        + holder.getClass().getName()
                        + (vErr[0] != null ? "，" + vErr[0] : "") + "）";
                }
                entries.add(new Object[]{loc, null});
                continue;
            }
            if (elementCodec == null) {
                noNbt++;
                if (firstNbtError == null) firstNbtError = "没有 elementCodec";
                entries.add(new Object[]{loc, null});
                continue;
            }
            String[] err = new String[1];
            Object nbt = encodeWithCodec(elementCodec, value, err);
            if (nbt == null) {
                noNbt++;
                if (firstNbtError == null) firstNbtError = err[0];
            }
            entries.add(new Object[]{loc, nbt});
        }
        if (noKey > 0 || noNbt > 0) {
            McLog.warn("configuration: " + registryKey + " 共 " + total + " 个 holder，"
                + noKey + " 条读不到 key、" + noNbt + " 条没编出 NBT"
                + (firstNbtError != null ? "；首个失败原因: " + firstNbtError : ""));
        }

        if (entries.isEmpty()) {
            McLog.warn("configuration: " + registryKey + " 一个条目都没收集到"
                + "（holders 遍历出 " + total + " 项，codec="
                + (elementCodec == null ? "null" : elementCodec.getClass().getSimpleName()) + "）");
            return null;
        }

        // 有条目编不出 NBT 就整个注册表别发。
        // 写 hasData=false 的条目在原版客户端是合法的（表示"用你本地数据包里的定义"），
        // 但 ViaVersion 这类翻译层会严格要求有数据，直接报
        // "Server unexpectedly sent null registry data entry for minecraft:ocean" 然后断开。
        // 宁可少发一个注册表（客户端用自己的默认值），也不要发残缺的。
        if (noNbt > 0) {
            McLog.warn("configuration: " + registryKey + " 有 " + noNbt + "/" + entries.size()
                + " 条编不出 NBT，整个注册表跳过（发残缺条目会被 ViaVersion 拒绝）");
            return null;
        }

        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        McFraming.writeString(buf, registryKey.toString());
        McFraming.writeVarInt(buf, entries.size());
        for (Object[] e : entries) {
            McFraming.writeString(buf, e[0].toString());
            buf.writeBoolean(true);
            if (!writeNbt(buf, e[1])) {
                McLog.warn("configuration: " + registryKey + " 的 NBT 写入失败，放弃该注册表");
                buf.release();
                return null;
            }
        }
        McLog.info("configuration: " + registryKey + " → " + entries.size() + " 条目，"
            + buf.readableBytes() + " bytes");
        return buf;
    }

    /** 从 Holder 取出条目的 ResourceLocation。 */
    private Object holderLocation(Object holder) {
        Object keyOpt = invokeInterfaceNoArg(holder, "unwrapKey");
        if (keyOpt == null) keyOpt = Reflect.invokeAny(holder, new String[]{"unwrapKey", "m_203543_"});
        Object rk = keyOpt instanceof java.util.Optional && ((java.util.Optional<?>) keyOpt).isPresent()
            ? ((java.util.Optional<?>) keyOpt).get() : keyOpt;
        if (rk == null) return null;
        Object loc = invokeInterfaceNoArg(rk, "location");
        if (loc == null) loc = Reflect.invokeAny(rk, new String[]{"location", "m_135782_"});
        return loc;
    }

    /**
     * 调用无参方法，用 getMethods() 翻整条继承链。
     *
     * Reflect.invoke 只看对象实际类声明的方法，而 Holder/Registry/DataResult 这些
     * 关键方法都定义在接口上（实现类是 Reference/ReferencePipeline 之类），
     * 用它必然落空 —— 实测就是这个原因导致注册表一条都读不出来。
     */
    private Object invokeInterfaceNoArg(Object obj, String name) {
        return invokeInterfaceNoArg(obj, name, null);
    }

    /** 同上，但把异常回填到 errOut —— 吞掉异常等于把诊断信息一起吞了。 */
    private Object invokeInterfaceNoArg(Object obj, String name, String[] errOut) {
        if (obj == null) return null;
        boolean found = false;
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().equals(name)) continue;
                found = true;
                m.setAccessible(true);
                return m.invoke(obj);
            }
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            if (errOut != null) {
                errOut[0] = obj.getClass().getSimpleName() + "." + name + "() 抛 "
                    + root.getClass().getSimpleName() + ": " + root.getMessage();
            }
            return null;
        }
        if (!found && errOut != null) {
            errOut[0] = obj.getClass().getSimpleName() + " 上没有无参的 " + name + "()";
        }
        return null;
    }

    /**
     * 取注册表的 Holder 列表。
     *
     * 不能按名字找：1.21.2 起 Mojang 把 Registry.holders() 改名成 listElements()，
     * 按名字落空后随便挑一个"返回 Stream 的无参方法"会命中兄弟方法 ——
     * 实测 getTags() 让 enchantment 遍历出 22 个标签（每个 value() 都是 null），
     * listElementIds() 返回的是 ResourceKey 不是 Holder，其余注册表则拿到空的标签流，
     * 于是 21 个注册表全部 0 条目。
     *
     * 判据改成"流里的元素确实是 Holder"，名字怎么变都不影响。
     */
    private Iterable<?> collectHolders(Object registry, StringBuilder diag) {
        Class<?> holderClass = Reflect.tryCls("net.minecraft.core.Holder");
        List<Object> best = null;
        String bestName = null;

        for (Method m : registry.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?> rt = m.getReturnType();
            // 只认 Stream / Collection：Registry 自己也是 Iterable，
            // 误调 freeze() 之类会把注册表冻上。
            if (!java.util.stream.Stream.class.isAssignableFrom(rt)
                && !java.util.Collection.class.isAssignableFrom(rt)) continue;

            List<Object> items;
            try {
                m.setAccessible(true);
                Object r = m.invoke(registry);
                if (r instanceof java.util.stream.Stream) {
                    items = new ArrayList<>();
                    ((java.util.stream.Stream<?>) r).forEach(items::add);
                } else if (r instanceof java.util.Collection) {
                    items = new ArrayList<>((java.util.Collection<?>) r);
                } else {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (items.isEmpty()) continue;

            Object first = items.get(0);
            boolean isHolder = holderClass != null
                ? holderClass.isInstance(first)
                : invokeInterfaceNoArg(first, "value") != null;
            diag.append(m.getName()).append('=').append(items.size())
                .append(isHolder ? "(Holder) " : "(" + first.getClass().getSimpleName() + ") ");
            if (!isHolder) continue;

            if (best == null || items.size() > best.size()) {
                best = items;
                bestName = m.getName();
            }
        }
        if (bestName != null && holderMethodLogged.add(bestName)) {
            McLog.info("configuration: Holder 列表来自 " + bestName + "()");
        }
        return best;
    }

    /** 只为日志：同一个方法名只报一次，别刷 21 行。 */
    private final java.util.Set<String> holderMethodLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 取注册表元素的 Codec。 */
    private Object registryElementCodec(Object registryKey, Object registry) {
        // 优先从 RegistryDataLoader 拿 elementCodec —— 那是"把内容完整写出来"的 Codec。
        // Registry 自己的 byNameCodec() 只编码引用名（"minecraft:ocean" 这种字符串），
        // 拿它去编 configuration 的注册表内容会失败，B 那边就收到 null 条目。
        Class<?> codecClass = Reflect.tryCls("com.mojang.serialization.Codec");
        try {
            Class<?> rdl = Reflect.tryCls("net.minecraft.resources.RegistryDataLoader");
            if (rdl != null && codecClass != null) {
                for (Field f : rdl.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (!(v instanceof java.util.List)) continue;
                    for (Object item : (java.util.List<?>) v) {
                        Object k = invokeInterfaceNoArg(item, "key");
                        if (k == null) k = Reflect.invokeAny(item, new String[]{"key"});
                        if (k == null) {
                            Class<?> rkClass = Reflect.tryCls("net.minecraft.resources.ResourceKey");
                            if (rkClass != null) k = Reflect.invokeByReturnType(item, rkClass);
                        }
                        Object loc = invokeInterfaceNoArg(k, "location");
                        if (loc == null && k != null) loc = Reflect.invokeAny(k, new String[]{"location", "m_135782_"});
                        if (loc == null) loc = k;
                        if (loc == null || !loc.toString().equals(registryKey.toString())) continue;

                        Object c = invokeInterfaceNoArg(item, "elementCodec");
                        if (c == null) c = Reflect.invokeAny(item, new String[]{"elementCodec"});
                        if (c == null) c = Reflect.invokeByReturnType(item, codecClass);
                        if (c != null) {
                            McLog.info("configuration: " + registryKey + " 使用 RegistryDataLoader 的 elementCodec");
                            return c;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            McLog.warn("configuration: 从 RegistryDataLoader 取 Codec 失败: " + t);
        }

        // 退而求其次：Registry 上返回 Codec 的无参方法。可能拿到 byNameCodec，
        // 那样编不出内容，但至少让流程能继续、日志能看出问题在哪。
        if (codecClass != null) {
            for (Method m : registry.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!codecClass.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object c = m.invoke(registry);
                    if (c != null) {
                        McLog.info("configuration: " + registryKey + " 回退用 "
                            + m.getName() + "() 的 Codec");
                        return c;
                    }
                } catch (Throwable ignored) {}
            }
        }
        McLog.warn("configuration: " + registryKey + " 找不到任何 Codec");
        return null;
    }

    /** codec.encodeStart(NbtOps.INSTANCE, value) → NBT。 */
    private Object encodeWithCodec(Object codec, Object value, String[] errOut) {
        try {
            Object ops = registryOps();
            if (ops == null) {
                if (errOut != null) errOut[0] = "registryOps() 返回 null";
                return null;
            }
            Method encodeStart = null;
            for (Method m : codec.getClass().getMethods()) {
                if (m.getParameterCount() != 2) continue;
                if (!m.getName().equals("encodeStart")) continue;
                encodeStart = m;
                break;
            }
            if (encodeStart == null) {
                if (errOut != null) {
                    errOut[0] = "在 " + codec.getClass().getName() + " 上找不到 encodeStart";
                }
                return null;
            }

            encodeStart.setAccessible(true);
            Object res;
            try {
                res = encodeStart.invoke(codec, ops, value);
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null) root = root.getCause();
                if (errOut != null) {
                    errOut[0] = "encodeStart 抛异常: " + root.getClass().getName() + ": " + root.getMessage();
                }
                return null;
            }
            if (res == null) {
                if (errOut != null) errOut[0] = "encodeStart 返回 null";
                return null;
            }

            // DataResult.result() 是接口方法，用 getMethods() 才翻得到 ——
            // Reflect.invoke 只看实现类自己的方法表，这里会落空。
            for (Method rm : res.getClass().getMethods()) {
                if (rm.getParameterCount() != 0) continue;
                if (!rm.getName().equals("result")) continue;
                rm.setAccessible(true);
                Object opt = rm.invoke(res);
                if (opt instanceof java.util.Optional && ((java.util.Optional<?>) opt).isPresent()) {
                    return ((java.util.Optional<?>) opt).get();
                }
                break;
            }
            // 编码失败时把 DataResult 里的错误信息取出来
            for (Method em : res.getClass().getMethods()) {
                if (em.getParameterCount() != 0) continue;
                if (!em.getName().equals("error")) continue;
                em.setAccessible(true);
                Object err = em.invoke(res);
                if (err instanceof java.util.Optional && ((java.util.Optional<?>) err).isPresent()) {
                    if (errOut != null) errOut[0] = "Codec 拒绝: " + ((java.util.Optional<?>) err).get();
                    return null;
                }
                break;
            }
            if (errOut != null) errOut[0] = "DataResult 既无 result 也无 error（" + res.getClass().getName() + "）";
            return null;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            if (errOut != null) errOut[0] = root.getClass().getName() + ": " + root.getMessage();
            return null;
        }
    }

    /**
     * 编码注册表条目用的 DynamicOps。
     *
     * 优先 RegistryOps —— 很多注册表元素（生物群系里的音乐、维度类型的 tag 等）
     * 内部要按 ResourceKey 解析引用，纯 NbtOps 没有注册表上下文，编码直接失败，
     * 结果就是每个条目都编不出 NBT。缓存一份，别每条都重建。
     */
    private volatile Object cachedOps;

    private Object registryOps() {
        Object cached = cachedOps;
        if (cached != null) return cached;
        try {
            Object nbtOps = resolveNbtOpsInstance();
            if (nbtOps == null) return null;

            Class<?> regOpsClass = Reflect.tryCls("net.minecraft.resources.RegistryOps");
            if (regOpsClass == null) regOpsClass = Reflect.tryCls("net.minecraft.util.registry.WorldGenSettingsExport");
            if (regOpsClass == null) regOpsClass = Reflect.tryCls("net.minecraft.class_5382");
            Object conn = connection(mc());
            Object ra = conn != null ? registryAccess(conn) : null;
            if (regOpsClass == null) {
                McLog.warn("configuration: 找不到 RegistryOps 类，退回纯 NbtOps");
            } else if (ra == null) {
                McLog.warn("configuration: 拿不到 RegistryAccess，退回纯 NbtOps");
            } else {
                StringBuilder tried = new StringBuilder();
                for (Method m : regOpsClass.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (!regOpsClass.isAssignableFrom(m.getReturnType())) continue;
                    tried.append(m.getName()).append('(')
                         .append(m.getParameterTypes()[0].getSimpleName()).append(',')
                         .append(m.getParameterTypes()[1].getSimpleName()).append(") ");
                    try {
                        m.setAccessible(true);
                        Object ops = m.invoke(null, nbtOps, ra);
                        if (ops != null) {
                            McLog.info("configuration: 使用 RegistryOps."
                                + m.getName() + "（带注册表上下文）编码");
                            cachedOps = ops;
                            return ops;
                        }
                    } catch (Throwable t) {
                        Throwable root = t;
                        while (root.getCause() != null) root = root.getCause();
                        tried.append("[失败:").append(root.getClass().getSimpleName()).append("] ");
                    }
                }
                McLog.warn("configuration: 构造不出 RegistryOps，退回纯 NbtOps（部分条目会编不出来）。"
                    + "试过的静态方法: " + tried);
            }
            cachedOps = nbtOps;
            return nbtOps;
        } catch (Throwable t) {
            McLog.warn("configuration: 构造 DynamicOps 失败: " + t);
            return null;
        }
    }

    private Object registryAccess(Object conn) {
        if (conn == null) return null;
        for (String cn : new String[]{
                "net.minecraft.core.RegistryAccess",
                "net.minecraft.util.registry.DynamicRegistries",
                "net.minecraft.util.registry.DynamicRegistries$Impl",
                "net.minecraft.class_5455"}) {
            Class<?> raClass = Reflect.tryCls(cn);
            if (raClass == null) continue;
            Object ra = Reflect.invokeByReturnType(conn, raClass);
            if (ra == null) ra = Reflect.findFieldByType(conn, raClass);
            if (ra != null) return ra;
        }
        try {
            Object ra = Reflect.invokeAny(conn, new String[]{
                "registryAccess", "func_239165_n_", "m_104910_", "method_29091"
            });
            if (ra != null) return ra;
        } catch (Throwable ignored) {}
        return null;
    }

    // ======================== 初始世界 ========================

    @Override
    public void sendRemainingInitialPackets(PacketSender sender) {
        sendWorld(sender);
    }

    /**
     * 按原版服务端的进服顺序发全套包。每次 B 连入都完整跑一遍。
     *
     * 顺序有因果：
     *   位置 → 区块中心 → 区块：区块中心决定客户端的区块接收窗口，窗口外的区块
     *   会被直接丢弃，而窗口要跟着玩家位置走。
     *   AddPlayer 之前必须先有该玩家的 PlayerInfo 条目，否则客户端不生成实体。
     *   SetDefaultSpawnPosition 放最后：1.20.1 靠它结束"加载世界中"并开始 tick。
     */
    private void sendWorld(PacketSender sender) {
        McLog.info("=== 开始从 A 的内存重建世界 ===");

        step("玩家列表", () -> sendPlayerInfo(sender));
        step("玩家位置", () -> sendPosition(sender));
        step("区块中心", () -> sendChunkCenter(sender));
        step("区块", () -> sendChunks(sender));
        step("区块加载事件", () -> sendChunksLoadStart(sender));
        step("队伍规则", () -> sendTeams(sender));       // 实体碰撞开关在队伍包里
        step("计分板", () -> sendScoreboard(sender));
        step("背包", () -> sendInventory(sender));
        step("玩家状态", () -> sendPlayerState(sender));
        step("实体", () -> sendEntities(sender));
        step("出生点", () -> sendSpawnPosition(sender));

        McLog.info("=== 世界重建完成 ===");
    }

    private void step(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            McLog.error(name + " 发送失败", t);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    // ======================== 玩家列表 (tab) ========================

    /**
     * tab 列表：把 A 看到的所有在线玩家发给 B。
     *
     * 之前只发了 A 自己一个条目，所以 B 的 tab 是空的，而且其他玩家的实体也生成不出来
     * （AddPlayer 要求先有 PlayerInfo 条目）。这里直接用 MC 的包对象，一次带上所有玩家。
     */
    private void sendPlayerInfo(PacketSender sender) throws Exception {
        if (clientProtocol <= 760) {
            sendPlayerInfoPre1193(sender);
            return;
        }
        ByteBuf buf = onMain(() -> {
            Object conn = connection(mc());
            if (conn == null) return null;

            // ClientPacketListener 里的在线玩家信息集合
            java.util.Collection<?> infos = playerInfos(conn);
            if (infos == null || infos.isEmpty()) {
                McLog.warn("tab: 拿不到在线玩家列表");
                return null;
            }

            Class<?> pc = Reflect.tryCls(P + "ClientboundPlayerInfoUpdatePacket");
            if (pc == null) pc = Reflect.tryCls("net.minecraft.class_7828");
            if (pc == null) return null;
            Object pkt = buildPlayerInfoPacket(pc, infos);
            if (pkt == null) return null;
            return PacketCodec.encode(pc, pkt);
        }, null, "玩家列表");

        if (buf != null) {
            sender.send(buf);
            McLog.info("tab 列表已发送");
        } else {
            // 退回手写：只发 A 自己，至少保证 B 自己的实体能生成
            sendPlayerInfoFallback(sender);
        }
    }

    /** 1.13 – 1.19.2 (393 – 760): ClientboundPlayerInfoPacket (Action 0 = ADD_PLAYER)。 */
    private void sendPlayerInfoPre1193(PacketSender sender) {
        int id = PacketIdResolver.s2cPlayId(clientProtocol, "PLAYER_INFO");
        if (id < 0) return;

        List<Object[]> rows = onMain(() -> {
            List<Object[]> list = new ArrayList<Object[]>();
            java.util.Set<UUID> seen = new java.util.HashSet<UUID>();
            Object conn = connection(mc());
            java.util.Collection<?> infos = conn != null ? playerInfos(conn) : null;
            Class<?> gpClass = Reflect.tryCls("com.mojang.authlib.GameProfile");
            if (infos != null && gpClass != null) {
                for (Object info : infos) {
                    Object[] row = rowFromProfile(
                        Reflect.invokeByReturnType(info, gpClass), null, Boolean.TRUE);
                    if (row != null && seen.add((UUID) row[0])) list.add(row);
                }
            }
            if (gpClass != null) {
                for (Object e : allEntities(level(player(mc())))) {
                    Object gp = Reflect.invokeByReturnType(e, gpClass);
                    if (gp != null) {
                        Object[] row = rowFromProfile(gp, null, Boolean.TRUE);
                        if (row != null && seen.add((UUID) row[0])) list.add(row);
                    }
                }
            }
            PlayerProfile self = readProfile();
            if (seen.add(self.uuid)) {
                list.add(new Object[]{self.uuid, self.name, self.properties, self.gamemode, Boolean.TRUE});
            }
            return list;
        }, new ArrayList<Object[]>(), "1.13–1.19.2 玩家列表");

        if (rows.isEmpty()) return;
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        McFraming.writeVarInt(buf, 0); // ADD_PLAYER
        McFraming.writeVarInt(buf, rows.size());
        for (Object[] r : rows) {
            McFraming.writeUuid(buf, (UUID) r[0]);
            McFraming.writeString(buf, (String) r[1]);
            @SuppressWarnings("unchecked")
            List<String[]> props = (List<String[]>) r[2];
            McFraming.writeVarInt(buf, props.size());
            for (String[] pr : props) {
                McFraming.writeString(buf, pr[0]);
                McFraming.writeString(buf, pr[1]);
                boolean signed = pr.length > 2 && pr[2] != null && !pr[2].isEmpty();
                buf.writeBoolean(signed);
                if (signed) McFraming.writeString(buf, pr[2]);
            }
            McFraming.writeVarInt(buf, (Integer) r[3]);
            McFraming.writeVarInt(buf, 0);   // latency
            buf.writeBoolean(false);         // displayName
            if (clientProtocol == 759 || clientProtocol == 760) {
                buf.writeBoolean(false);     // 1.19 – 1.19.2 hasSigData
            }
        }
        sender.send(buf);
        McLog.info("tab(1.13–1.19.2): 已发送 " + rows.size() + " 人");
    }

    /**
     * 取在线玩家信息集合。
     *
     * ClientPacketListener 里有两个：listedPlayers（只含 tab 里可见的）和
     * playerInfoMap.values()（全部，含被隐藏的假人/机器人）。服务器常把假人设成
     * listed=false，只取第一个匹配到的集合就会漏掉它们 —— 所以取元素最多的那个。
     */
    private java.util.Collection<?> playerInfos(Object conn) {
        java.util.Collection<?> best = null;
        String from = null;

        // 无参方法返回的集合
        for (Method m : conn.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!java.util.Collection.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
                Object r = m.invoke(conn);
                java.util.Collection<?> c = asPlayerInfoCollection(r);
                if (c != null && (best == null || c.size() > best.size())) {
                    best = c;
                    from = m.getName() + "()";
                }
            } catch (Throwable ignored) {}
        }

        // 字段里的 Map<UUID, PlayerInfo> / Collection —— 假人一般只在这里
        for (Class<?> k = conn.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(conn);
                    if (v == null) continue;
                    java.util.Collection<?> c = v instanceof java.util.Map
                        ? asPlayerInfoCollection(((java.util.Map<?, ?>) v).values())
                        : asPlayerInfoCollection(v);
                    if (c != null && (best == null || c.size() > best.size())) {
                        best = c;
                        from = k.getSimpleName() + "." + f.getName();
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (best != null) {
            McLog.info("tab: 玩家来源 " + from + " → " + best.size() + " 人");
        }
        return best;
    }

    /** 是不是非空的 PlayerInfo 集合。 */
    private java.util.Collection<?> asPlayerInfoCollection(Object o) {
        if (!(o instanceof java.util.Collection)) return null;
        java.util.Collection<?> c = (java.util.Collection<?>) o;
        if (c.isEmpty()) return null;
        Object first = c.iterator().next();
        if (first == null) return null;
        return first.getClass().getName().contains("PlayerInfo") ? c : null;
    }

    /** 按名字挑 Action 枚举常量拼成 EnumSet。枚举常量名不参与混淆，可以按名取。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object actionSet(Class<?> actionClass, String... names) {
        java.util.EnumSet set = java.util.EnumSet.noneOf((Class) actionClass);
        Object[] consts = actionClass.getEnumConstants();
        if (consts == null) return set;
        for (String want : names) {
            for (Object c : consts) {
                if (((Enum<?>) c).name().equals(want)) {
                    set.add((Enum) c);
                    break;
                }
            }
        }
        if (set.isEmpty()) {
            McLog.warn("tab: Action 常量名都对不上，退回 allOf");
            return java.util.EnumSet.allOf((Class) actionClass);
        }
        return set;
    }

    private static boolean isRecordClass(Class<?> cls) {
        Class<?> sup = cls != null ? cls.getSuperclass() : null;
        return sup != null && "java.lang.Record".equals(sup.getName());
    }

    /** 用 EnumSet<Action> + List<Entry> 构造 PlayerInfoUpdate 包。 */
    private Object buildPlayerInfoPacket(Class<?> pc, java.util.Collection<?> infos) {
        try {
            Class<?> actionClass = null;
            Class<?> entryClass = null;
            for (Class<?> inner : pc.getDeclaredClasses()) {
                if (inner.isEnum()) actionClass = inner;
                else if (isRecordClass(inner) || inner.getSimpleName().contains("Entry")) entryClass = inner;
            }
            if (actionClass == null || entryClass == null) {
                McLog.warn("tab: 找不到 Action/Entry 内部类");
                return null;
            }

            // 只带我们真正能填出值的 action。
            // 不能用 EnumSet.allOf：INITIALIZE_CHAT 要求 entry 里有 chatSession，
            // 而客户端侧的 PlayerInfo 未必有，写包时会 NPE 让整个 tab 发不出去。
            Object actions = actionSet(actionClass,
                "ADD_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED", "UPDATE_LATENCY");

            List<Object> entries = new ArrayList<>();
            for (Object info : infos) {
                Object entry = entryFromPlayerInfo(entryClass, info);
                if (entry != null) entries.add(entry);
            }
            if (entries.isEmpty()) return null;

            for (java.lang.reflect.Constructor<?> ctor : pc.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length != 2) continue;
                // 形参声明的是 EnumSet<Action>，但也可能是 Set；用形参能否接受实参来判断，
                // 而不是反过来问实参的类型 —— 之前写反了，导致这里永远匹配不上，
                // 于是走兜底只发 A 自己，其他玩家因为缺 PlayerInfo 条目全部不生成。
                if (!p[0].isAssignableFrom(actions.getClass())) continue;
                // 形参是 Collection 而不是 List（实测构造器签名是 (EnumSet, Collection)），
                // 要问"形参能不能装下 List"，而不是"形参是不是 List"
                if (!p[1].isAssignableFrom(java.util.ArrayList.class)) continue;
                ctor.setAccessible(true);
                McLog.info("tab: 构造 " + entries.size() + " 个玩家条目");
                return ctor.newInstance(actions, entries);
            }
            // 仍然没有 (Set, List) 构造器：打出所有构造器签名，便于定位
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Constructor<?> ctor : pc.getDeclaredConstructors()) {
                sb.append("(");
                Class<?>[] p = ctor.getParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(p[i].getSimpleName());
                }
                sb.append(") ");
            }
            McLog.warn("tab: 找不到 (Set, List) 构造器；现有构造器: " + sb);
            return null;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            McLog.warn("tab: 构造包失败: " + root.getClass().getName() + ": " + root.getMessage());
            return null;
        }
    }

    /** 从客户端的 PlayerInfo 拼出包里的 Entry record。 */
    private Object entryFromPlayerInfo(Class<?> entryClass, Object info) {
        try {
            Object profile = Reflect.invokeByReturnType(info,
                Reflect.cls("com.mojang.authlib.GameProfile"));
            if (profile == null) return null;
            UUID id = (UUID) profile.getClass().getMethod("getId").invoke(profile);

            Object gameMode = null;
            Class<?> gtClass = Reflect.tryCls("net.minecraft.world.level.GameType");
            if (gtClass != null) gameMode = Reflect.invokeByReturnType(info, gtClass);

            Integer latency = (Integer) Reflect.invokeAnyTyped(info, Integer.class,
                new String[]{"getLatency", "m_105315_"});

            Object displayName = null;
            Class<?> compClass = Reflect.tryCls("net.minecraft.network.chat.Component");
            if (compClass != null) displayName = Reflect.invokeByReturnType(info, compClass);

            for (java.lang.reflect.Constructor<?> ctor : entryClass.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                ctor.setAccessible(true);
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) {
                    Class<?> t = p[i];
                    if (t == UUID.class) args[i] = id;
                    else if (t.isInstance(profile)) args[i] = profile;
                    else if (gtClass != null && t == gtClass) args[i] = gameMode;
                    else if (t == int.class) args[i] = latency != null ? latency : 0;
                    else if (t == boolean.class) args[i] = Boolean.TRUE;   // listed
                    else if (compClass != null && t == compClass) args[i] = displayName;
                    else args[i] = null;                                   // chatSession 等
                }
                try { return ctor.newInstance(args); } catch (Throwable ignored) {}
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 兜底：手写 PlayerInfoUpdate，带上**所有**在线玩家。
     *
     * 只发 A 自己是不够的 —— AddPlayer 要求对应的 PlayerInfo 条目先存在，
     * 缺条目的玩家客户端直接不生成实体，表现为"其他玩家压根不显示"。
     */
    private void sendPlayerInfoFallback(PacketSender sender) {
        try {
            int id = PacketIdResolver.resolve(P + "ClientboundPlayerInfoUpdatePacket");
            if (id < 0) return;

            List<Object[]> rows = onMain(() -> {
                List<Object[]> list = new ArrayList<>();
                java.util.Set<UUID> seen = new java.util.HashSet<>();
                Object conn = connection(mc());
                java.util.Collection<?> infos = conn != null ? playerInfos(conn) : null;
                if (infos != null) {
                    for (Object info : infos) {
                        Object[] row = rowFromProfile(
                            Reflect.invokeByReturnType(info, Reflect.cls("com.mojang.authlib.GameProfile")),
                            Reflect.invokeByReturnType(info, Reflect.tryCls("net.minecraft.world.level.GameType")),
                            Reflect.invokeAnyTyped(info, Boolean.class, new String[]{"isListed", "m_245259_"}));
                        if (row != null && seen.add((UUID) row[0])) list.add(row);
                    }
                }

                // 服务器的假人/机器人常常只有实体、没有 tab 条目，而 AddPlayer 缺条目就会被
                // 客户端丢弃 —— 所以从玩家实体本身补出条目。listed=false 保证它们不会
                // 错误地出现在 B 的 tab 里，但实体能正常生成。
                int added = 0;
                Class<?> playerClass = Reflect.tryCls("net.minecraft.world.entity.player.Player");
                if (playerClass != null) {
                    for (Object e : allEntities(level(player(mc())))) {
                        if (!playerClass.isInstance(e)) continue;
                        Object[] row = rowFromProfile(
                            Reflect.invokeByReturnType(e, Reflect.cls("com.mojang.authlib.GameProfile")),
                            null, Boolean.FALSE);
                        if (row != null && seen.add((UUID) row[0])) {
                            list.add(row);
                            added++;
                        }
                    }
                }
                if (added > 0) McLog.info("tab: 从实体补出 " + added + " 个条目（假人/机器人）");

                if (list.isEmpty()) {
                    PlayerProfile self = readProfile();
                    list.add(new Object[]{self.uuid, self.name, new ArrayList<String[]>(),
                        self.gamemode, Boolean.TRUE});
                }
                return list;
            }, new ArrayList<Object[]>(), "tab 兜底");

            if (rows.isEmpty()) return;

            // 取 Action 枚举：bit 位置和字段顺序都由它的声明顺序决定
            String[] want = {"ADD_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED"};
            Object[] acts = playerInfoActions();
            if (acts == null) {
                McLog.warn("tab 兜底: 找不到 Action 枚举，放弃");
                return;
            }

            ByteBuf buf = Unpooled.buffer();
            McFraming.writeVarInt(buf, id);

            // actions 是**定长 BitSet**：ceil(常量数/8) 个字节，没有长度前缀。
            // 之前写成 VarInt(1)+long(8) 共 9 字节，客户端只读 1 字节当 bitset，
            // 紧接着把 long 的首字节 0x00 当成"条目数=0" —— 于是一个玩家都不加载。
            byte[] mask = new byte[(acts.length + 7) / 8];
            for (int i = 0; i < acts.length; i++) {
                if (contains(want, ((Enum<?>) acts[i]).name())) mask[i / 8] |= (byte) (1 << (i % 8));
            }
            buf.writeBytes(mask);

            McFraming.writeVarInt(buf, rows.size());
            for (Object[] r : rows) {
                McFraming.writeUuid(buf, (UUID) r[0]);
                // 字段必须按枚举声明顺序写，和 bitset 的位序一致
                for (Object act : acts) {
                    String an = ((Enum<?>) act).name();
                    if (!contains(want, an)) continue;
                    switch (an) {
                        case "ADD_PLAYER": {
                            McFraming.writeString(buf, (String) r[1]);
                            @SuppressWarnings("unchecked")
                            List<String[]> props = (List<String[]>) r[2];
                            McFraming.writeVarInt(buf, props.size());
                            for (String[] pr : props) {
                                McFraming.writeString(buf, pr[0]);
                                McFraming.writeString(buf, pr[1]);
                                buf.writeBoolean(pr[2] != null);
                                if (pr[2] != null) McFraming.writeString(buf, pr[2]);
                            }
                            break;
                        }
                        case "UPDATE_GAME_MODE": McFraming.writeVarInt(buf, (Integer) r[3]); break;
                        case "UPDATE_LISTED":    buf.writeBoolean(Boolean.TRUE.equals(r[4])); break;
                        default: break;
                    }
                }
            }
            sender.send(buf);
            McLog.info("tab: 兜底手写，共 " + rows.size() + " 个玩家 (actions=" +
                mask.length + " 字节)");
        } catch (Throwable t) {
            McLog.warn("tab 兜底失败: " + t);
        }
    }

    /** 从 GameProfile 拼出兜底包要用的一行：{uuid, name, props, gamemode, listed}。 */
    private Object[] rowFromProfile(Object gp, Object gameType, Object listed) {
        try {
            if (gp == null) return null;
            UUID uuid = (UUID) gp.getClass().getMethod("getId").invoke(gp);
            String nm = (String) gp.getClass().getMethod("getName").invoke(gp);
            if (uuid == null || nm == null) return null;

            // 皮肤属性：不带的话所有人都是史蒂夫
            List<String[]> props = new ArrayList<>();
            Object pm = gp.getClass().getMethod("getProperties").invoke(gp);
            Object vals = pm != null ? Reflect.invokeAny(pm, new String[]{"values"}) : null;
            if (vals instanceof Iterable) {
                for (Object pr : (Iterable<?>) vals) {
                    String pn = (String) Reflect.invokeAny(pr, new String[]{"getName", "name"});
                    String pv = (String) Reflect.invokeAny(pr, new String[]{"getValue", "value"});
                    String ps = (String) Reflect.invokeAny(pr, new String[]{"getSignature", "signature"});
                    if (pn != null && pv != null) props.add(new String[]{pn, pv, ps});
                }
            }
            int gmOrd = gameType instanceof Enum ? ((Enum<?>) gameType).ordinal() : 0;
            boolean isListed = !(listed instanceof Boolean) || (Boolean) listed;
            return new Object[]{uuid, nm, props, gmOrd, isListed};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 PlayerInfoUpdate 包里的 Action 枚举常量（声明顺序 = bit 顺序 = 字段顺序）。 */
    private Object[] playerInfoActions() {
        try {
            Class<?> pc = Reflect.cls(P + "ClientboundPlayerInfoUpdatePacket");
            for (Class<?> inner : pc.getDeclaredClasses()) {
                if (inner.isEnum()) return inner.getEnumConstants();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) if (a.equals(s)) return true;
        return false;
    }

    // ======================== 位置 / 区块 ========================

    private void sendPosition(PacketSender sender) throws Exception {
        int id = PacketIdResolver.resolve(P + "ClientboundPlayerPositionPacket");
        if (id < 0) id = PacketIdResolver.s2cPlayId(clientProtocol, "PLAYER_POS");
        if (id < 0) return;
        double[] pos = readPose();
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        buf.writeDouble(pos[0]);
        buf.writeDouble(pos[1]);
        buf.writeDouble(pos[2]);
        buf.writeFloat((float) pos[3]);
        buf.writeFloat((float) pos[4]);
        buf.writeByte(0);                    // flags: 全绝对
        McFraming.writeVarInt(buf, 1);       // teleportId
        sender.send(buf);
    }

    private double[] readPose() {
        return onMain(() -> {
            Object p = player(mc());
            return new double[]{
                dbl(p, "getX", "m_20185_", "func_226277_ct_", "method_23317"),
                dbl(p, "getY", "m_20186_", "func_226278_cu_", "method_23318"),
                dbl(p, "getZ", "m_20189_", "func_226281_cx_", "method_23321"),
                flt(p, "getYRot", "m_146908_", "method_36454"),
                flt(p, "getXRot", "m_146909_", "method_36455"),
            };
        }, new double[]{0, 64, 0, 0, 0}, "位置读取");
    }

    private void sendChunkCenter(PacketSender sender) throws Exception {
        int id = PacketIdResolver.resolve(P + "ClientboundSetChunkCacheCenterPacket");
        if (id < 0) id = PacketIdResolver.s2cPlayId(clientProtocol, "CHUNK_CENTER");
        if (id < 0) return;
        double[] pos = readPose();
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        McFraming.writeVarInt(buf, (int) Math.floor(pos[0]) >> 4);
        McFraming.writeVarInt(buf, (int) Math.floor(pos[2]) >> 4);
        sender.send(buf);
    }

    private void sendChunksLoadStart(PacketSender sender) throws Exception {
        Class<?> pc = Reflect.tryCls(P + "ClientboundGameEventPacket");
        if (pc == null) pc = Reflect.tryCls("net.minecraft.class_2668");
        if (pc == null) {
            return;
        }
        Class<?> typeClass = Reflect.tryCls(P + "ClientboundGameEventPacket$Type");
        if (typeClass == null) typeClass = Reflect.tryCls("net.minecraft.class_2668$class_5402");
        if (typeClass == null) {
            return;
        }
        Object type = null;
        for (String n : new String[]{"LEVEL_CHUNKS_LOAD_START", "f_132156_", "field_29173"}) {
            try {
                Field f = pc.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(null);
                if (typeClass.isInstance(v)) { type = v; break; }
            } catch (Throwable ignored) {}
        }
        if (type == null) return;
        Object pkt = PacketCodec.construct(pc, type, 0.0f);
        if (pkt == null) return;
        ByteBuf b = PacketCodec.encode(pc, pkt);
        if (b != null) {
            sender.send(b);
            McLog.info("区块加载事件: 已发送 LEVEL_CHUNKS_LOAD_START");
        }
    }

    /** 现场序列化 A 周围的区块（1.18+ 带光照合一包；1.13–1.17.1 独立 LevelChunk + LightUpdate 包）。 */
    private void sendChunks(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object p = player(mc());
            Object lvl = level(p);

            if (clientProtocol <= 756) {
                return buildPre118Chunks(p, lvl);
            }

            Class<?> lightClass = Reflect.tryCls("net.minecraft.world.level.lighting.LevelLightEngine");
            if (lightClass == null) lightClass = Reflect.tryCls("net.minecraft.class_3568");
            Object light = lightClass != null ? Reflect.invokeByReturnType(lvl, lightClass) : null;
            Class<?> csClass = Reflect.tryCls("net.minecraft.world.level.chunk.ChunkSource");
            if (csClass == null) csClass = Reflect.tryCls("net.minecraft.class_2802");
            Object cs = csClass != null ? Reflect.invokeByReturnType(lvl, csClass) : null;
            if (light == null && cs != null && lightClass != null) {
                light = Reflect.invokeByReturnType(cs, lightClass);
            }
            if (cs == null || light == null) {
                McLog.warn("区块: 拿不到 ChunkSource/LightEngine");
                return list;
            }

            Class<?> lcClass = Reflect.tryCls("net.minecraft.world.level.chunk.LevelChunk");
            if (lcClass == null) lcClass = Reflect.tryCls("net.minecraft.class_2818");
            if (lcClass == null) return list;
            Method getChunk = Reflect.findMethodByReturnAndParams(
                cs.getClass(), lcClass, int.class, int.class, boolean.class);
            if (getChunk == null) {
                McLog.warn("区块: 找不到 getChunk(int,int,boolean)");
                return list;
            }

            Class<?> pktClass = Reflect.tryCls(P + "ClientboundLevelChunkWithLightPacket");
            if (pktClass == null) pktClass = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "CHUNK_DATA"));
            if (pktClass == null) return list;
            double[] pose = readPose();
            int cx = (int) Math.floor(pose[0]) >> 4;
            int cz = (int) Math.floor(pose[2]) >> 4;
            int r = viewDistance();

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    try {
                        Object chunk = getChunk.invoke(cs, cx + dx, cz + dz, false);
                        if (chunk == null) continue;
                        Object pkt = chunkPacket(pktClass, chunk, light);
                        if (pkt == null) continue;
                        ByteBuf b = PacketCodec.encode(pktClass, pkt);
                        if (b != null) list.add(b);
                    } catch (Throwable ignored) {}
                }
            }
            McLog.info("区块: 序列化 " + list.size() + " 个");
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "区块");

        // 1.20.2+ 的区块必须包在批次里发。
        //
        // 源码里 1.20.2 新增了 ClientboundChunkBatchStartPacket /
        // ClientboundChunkBatchFinishedPacket（1.20.1 没有这两个类）。
        // 客户端把 Start…Finished 之间的区块当作一批，收到 Finished 才交给渲染器，
        // 并回一个 ServerboundChunkBatchReceivedPacket。
        // 只发裸区块包，B 就一直等不到批次结束 —— 区块数据到了却不渲染，
        // 碰撞箱也处在半初始化状态（显示正常但走不过去、Grim 报模拟）。
        // 这就是 1.21.8 和 1.20.1 表现不同的根本原因，不是漏发了别的东西。
        int batchStart = PacketIdResolver.resolve(P + "ClientboundChunkBatchStartPacket");
        int batchFinish = PacketIdResolver.resolve(P + "ClientboundChunkBatchFinishedPacket");
        boolean batched = batchStart >= 0 && batchFinish >= 0;

        if (batched) {
            ByteBuf s = Unpooled.buffer();
            McFraming.writeVarInt(s, batchStart);   // 无字段
            sender.send(s);
        }

        for (ByteBuf b : out) sender.send(b);

        if (batched) {
            ByteBuf f = Unpooled.buffer();
            McFraming.writeVarInt(f, batchFinish);
            McFraming.writeVarInt(f, out.size());   // batchSize
            sender.send(f);
            McLog.info("区块: 已用 ChunkBatch 包裹 " + out.size() + " 个区块（1.20.2+ 必需）");
        } else {
            McLog.info("区块: 该版本无 ChunkBatch 协议，按 1.20.1 方式直接发送");
        }
    }

    /** 1.13 – 1.17.1 (393 – 756): 分别构造 LevelChunkPacket 与 LightUpdatePacket。 */
    private List<ByteBuf> buildPre118Chunks(Object p, Object lvl) {
        List<ByteBuf> list = new ArrayList<ByteBuf>();
        int chunkPktId = PacketIdResolver.s2cPlayId(clientProtocol, "CHUNK_DATA");
        Class<?> chunkPktCls = PacketIdResolver.clientboundPlayClassById(chunkPktId);
        if (chunkPktCls == null) {
            chunkPktCls = Reflect.tryCls(P + "ClientboundLevelChunkPacket");
            if (chunkPktCls == null) chunkPktCls = Reflect.tryCls("net.minecraft.network.play.server.SChunkDataPacket");
            if (chunkPktCls == null) chunkPktCls = Reflect.tryCls("net.minecraft.network.play.server.SPacketChunkData");
            if (chunkPktCls == null) chunkPktCls = Reflect.tryCls("net.minecraft.class_2672");
        }
        if (chunkPktCls == null) return list;

        Class<?> chunkClass = null;
        for (java.lang.reflect.Constructor<?> ctor : chunkPktCls.getDeclaredConstructors()) {
            Class<?>[] pts = ctor.getParameterTypes();
            if ((pts.length == 1 || pts.length == 2) && !pts[0].isPrimitive() && !ByteBuf.class.isAssignableFrom(pts[0])) {
                chunkClass = pts[0];
                break;
            }
        }
        if (chunkClass == null) chunkClass = Reflect.tryCls("net.minecraft.world.level.chunk.LevelChunk");
        if (chunkClass == null) chunkClass = Reflect.tryCls("net.minecraft.world.chunk.Chunk");
        if (chunkClass == null) return list;

        Method getChunk3 = null;
        Method getChunk2 = null;
        Object csTarget = lvl;
        for (Field f : lvl.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                Object cs = f.get(lvl);
                if (cs == null) continue;
                Method m3 = Reflect.findMethodByReturnAndParams(cs.getClass(), chunkClass, int.class, int.class, boolean.class);
                if (m3 != null) { csTarget = cs; getChunk3 = m3; break; }
                Method m2 = Reflect.findMethodByReturnAndParams(cs.getClass(), chunkClass, int.class, int.class);
                if (m2 != null) { csTarget = cs; getChunk2 = m2; break; }
            } catch (Throwable ignored) {}
        }
        if (getChunk3 == null && getChunk2 == null) {
            getChunk2 = Reflect.findMethodByReturnAndParams(lvl.getClass(), chunkClass, int.class, int.class);
        }
        if (getChunk3 == null && getChunk2 == null) return list;

        int lightId = PacketIdResolver.s2cPlayId(clientProtocol, "LIGHT_UPDATE");
        Class<?> lightPktCls = lightId >= 0 ? PacketIdResolver.clientboundPlayClassById(lightId) : null;
        Object lightEngine = null;
        for (String ln : new String[]{
                "net.minecraft.world.level.lighting.LevelLightEngine",
                "net.minecraft.world.lighting.WorldLightManager",
                "net.minecraft.class_3568"}) {
            Class<?> lc = Reflect.tryCls(ln);
            if (lc != null) {
                lightEngine = Reflect.invokeByReturnType(lvl, lc);
                if (lightEngine != null) break;
            }
        }

        double[] pose = readPose();
        int cx = (int) Math.floor(pose[0]) >> 4;
        int cz = (int) Math.floor(pose[2]) >> 4;
        int r = viewDistance();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                try {
                    Object chunk = getChunk3 != null
                        ? getChunk3.invoke(csTarget, cx + dx, cz + dz, false)
                        : getChunk2.invoke(csTarget, cx + dx, cz + dz);
                    if (chunk == null || chunk.getClass().getSimpleName().contains("Empty")) continue;
                    // 1.17.x: (LevelChunk); 1.13–1.16.5: (Chunk, 65535)
                    Object cpkt = PacketCodec.construct(chunkPktCls, chunk);
                    if (cpkt == null) cpkt = PacketCodec.construct(chunkPktCls, chunk, 0xFFFF);
                    if (cpkt == null) continue;
                    ByteBuf cb = PacketCodec.encodeWithId(chunkPktId, chunkPktCls, cpkt);
                    if (cb != null) list.add(cb);

                    if (lightPktCls != null && lightEngine != null) {
                        Object chunkPos = Reflect.invokeAny(chunk, new String[]{"getPos", "m_7697_", "func_76632_l", "method_12004"});
                        if (chunkPos != null) {
                            Object lpkt = PacketCodec.construct(lightPktCls, chunkPos, lightEngine, null, null, Boolean.TRUE);
                            if (lpkt == null) lpkt = PacketCodec.construct(lightPktCls, chunkPos, lightEngine, Boolean.TRUE);
                            if (lpkt != null) {
                                ByteBuf lb = PacketCodec.encodeWithId(lightId, lightPktCls, lpkt);
                                if (lb != null) list.add(lb);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        McLog.info("区块(1.13–1.17.1): 已序列化 " + list.size() + " 个包");
        return list;
    }

    /**
     * 构造区块包，**带完整光照掩码**。
     *
     * 1.21.8 的 ClientboundLevelChunkWithLightPacket 只有一个构造器：
     *   (LevelChunk, LevelLightEngine, BitSet skyYMask, BitSet blockYMask)
     * 没有 2 参版本。我原先传 (chunk, light, null, null) —— 两个 null 掩码的含义是
     * "这个区块一段光照都没有"，于是 ClientboundLightUpdatePacketData 写出空光照。
     *
     * 方块数据是对的，所以 B 那边显示正常；但客户端在光照未就绪前不会把区块
     * 标记为可碰撞 —— 表现为看得见却能穿过去、面前没东西 Grim 也报模拟。
     * 依赖光照传播的水、梯子、藤蔓首当其冲，这正是 1.20.1 上先暴露的那几类方块。
     * 服务器发的区块当然带完整光照，所以"重新加载一次世界就好了"。
     *
     * null 表示全选：BitSet 里把该维度所有 light section 都置位，
     * 让 LightEngine 把每一段的真实光照都写进去。段数 = 世界高度/16 + 2
     * （上下各多一段边界），从 LevelLightEngine 自己问，不硬编码。
     */
    private Object chunkPacket(Class<?> pktClass, Object chunk, Object light) {
        java.util.BitSet all = allLightSections(light);
        Object pkt = PacketCodec.construct(pktClass, chunk, light, all, all);
        if (pkt != null) return pkt;
        // 老版本（1.20.1 及部分分支）存在 2 参构造器，光照由包自己算
        pkt = PacketCodec.construct(pktClass, chunk, light);
        if (pkt != null) return pkt;
        // 最后才退回 null 掩码 —— 至少方块能显示，并明确告知光照不全
        if (lightMaskWarned.compareAndSet(false, true)) {
            McLog.warn("区块: 构造不出带光照掩码的包，退回空掩码（B 的碰撞箱可能不正常）");
        }
        return PacketCodec.construct(pktClass, chunk, light, null, null);
    }

    private final java.util.concurrent.atomic.AtomicBoolean lightMaskWarned =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** 该世界所有 light section 的掩码（全选）。 */
    private java.util.BitSet allLightSections(Object light) {
        // LevelLightEngine.getLightSectionCount() 直接给段数（已含上下各一段 padding），
        // 用它比自己拿 min/max 相减稳。名字变了再退回相减。
        int count = -1;
        Object n = Reflect.invokeAnyTyped(light, Integer.class,
            new String[]{"getLightSectionCount", "m_164446_"});
        if (n instanceof Integer && (Integer) n > 0) {
            count = (Integer) n;
        } else {
            Object lo = Reflect.invokeAnyTyped(light, Integer.class,
                new String[]{"getMinLightSection", "m_164447_"});
            Object hi = Reflect.invokeAnyTyped(light, Integer.class,
                new String[]{"getMaxLightSection", "m_164448_"});
            if (lo instanceof Integer && hi instanceof Integer && (Integer) hi > (Integer) lo) {
                count = (Integer) hi - (Integer) lo;
            }
        }
        if (count <= 0) {
            count = 26;   // -64..320 的世界：24 段 + 上下各 1 段 padding
            if (sectionCountWarned.compareAndSet(false, true)) {
                McLog.warn("区块: 问不出 light section 段数，按 26 段处理");
            }
        }
        java.util.BitSet bs = new java.util.BitSet(count);
        bs.set(0, count);
        return bs;
    }

    private final java.util.concurrent.atomic.AtomicBoolean sectionCountWarned =
        new java.util.concurrent.atomic.AtomicBoolean();

    // ======================== 队伍（含碰撞规则）========================

    /**
     * 队伍包。**实体碰撞开关就在这里** —— Team 的 collisionRule 字段决定玩家之间
     * 能不能互相挤开。不发这个包，B 就是默认的"所有人都能推"，表现为走路被别的
     * 实体挤走。名字颜色、前后缀、看不见队友等也都靠这个包。
     */
    private void sendTeams(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object lvl = level(player(mc()));
            Class<?> sbClass = Reflect.tryCls("net.minecraft.world.scores.Scoreboard");
            Object sb = sbClass != null ? Reflect.invokeByReturnType(lvl, sbClass) : null;
            if (sb == null) {
                McLog.info("队伍: 拿不到 Scoreboard");
                return list;
            }

            java.util.Collection<?> teams = collectionOf(sb, "PlayerTeam");
            if (teams == null || teams.isEmpty()) {
                McLog.info("队伍: 服务器没有队伍");
                return list;
            }

            Class<?> pc = Reflect.cls(P + "ClientboundSetPlayerTeamPacket");
            for (Object team : teams) {
                // 静态工厂 createAddOrModifyPacket(PlayerTeam, boolean)
                Object pkt = PacketCodec.callStaticFactory(pc, pc, team, Boolean.TRUE);
                if (pkt == null) continue;
                ByteBuf b = PacketCodec.encode(pc, pkt);
                if (b != null) list.add(b);
            }
            McLog.info("队伍: 序列化 " + list.size() + " 个（含碰撞规则）");
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "队伍");

        for (ByteBuf b : out) sender.send(b);
    }

    // ======================== 计分板 ========================

    private void sendScoreboard(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object lvl = level(player(mc()));
            Class<?> sbClass = Reflect.tryCls("net.minecraft.world.scores.Scoreboard");
            Object sb = sbClass != null ? Reflect.invokeByReturnType(lvl, sbClass) : null;
            if (sb == null) return list;

            Class<?> objClass = Reflect.cls("net.minecraft.world.scores.Objective");
            Class<?> objPc = Reflect.cls(P + "ClientboundSetObjectivePacket");
            Class<?> dispPc = Reflect.cls(P + "ClientboundSetDisplayObjectivePacket");
            Class<?> scorePc = Reflect.cls(P + "ClientboundSetScorePacket");

            // 所有 objective 先建出来
            java.util.Collection<?> objectives = collectionOf(sb, "Objective");
            if (objectives != null) {
                for (Object obj : objectives) {
                    Object pkt = PacketCodec.construct(objPc, obj, 0);   // 0 = ADD
                    if (pkt == null) continue;
                    ByteBuf b = PacketCodec.encode(objPc, pkt);
                    if (b != null) list.add(b);
                }
            }

            // 显示槽位（侧边栏在 slot 1）
            Method getDisplay = Reflect.findMethodByReturnAndParams(
                sb.getClass(), objClass, int.class);
            if (getDisplay != null) {
                for (int slot = 0; slot < 19; slot++) {
                    try {
                        Object obj = getDisplay.invoke(sb, slot);
                        if (obj == null) continue;
                        Object pkt = PacketCodec.construct(dispPc, slot, obj);
                        if (pkt == null) continue;
                        ByteBuf b = PacketCodec.encode(dispPc, pkt);
                        if (b != null) list.add(b);
                    } catch (Throwable ignored) {}
                }
            }

            // 分数
            if (objectives != null && scorePc != null) {
                Method getScores = Reflect.findMethodByReturnAndParams(
                    sb.getClass(), java.util.Collection.class, objClass);
                if (getScores != null) {
                    for (Object obj : objectives) {
                        try {
                            Object scores = getScores.invoke(sb, obj);
                            if (!(scores instanceof java.util.Collection)) continue;
                            for (Object score : (java.util.Collection<?>) scores) {
                                Object pkt = PacketCodec.construct(scorePc, score);
                                if (pkt == null) continue;
                                ByteBuf b = PacketCodec.encode(scorePc, pkt);
                                if (b != null) list.add(b);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            McLog.info("计分板: 序列化 " + list.size() + " 个包");
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "计分板");

        for (ByteBuf b : out) sender.send(b);
    }

    // ======================== 背包 / 玩家状态 ========================

    private void sendInventory(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object p = player(mc());

            // AbstractContainerMenu.inventoryMenu —— 直接拿玩家的容器，
            // 槽位顺序由它自己保证（手拼 46 格顺序很容易错）
            Class<?> menuClass = Reflect.tryCls("net.minecraft.world.inventory.AbstractContainerMenu");
            Object menu = menuClass != null ? Reflect.findFieldByType(p, menuClass) : null;
            if (menu == null) {
                McLog.warn("背包: 拿不到 ContainerMenu");
                return list;
            }

            // getItems() 返回 NonNullList<ItemStack>，getCarried() 返回 ItemStack。
            // 按返回类型区分，不猜名字。
            Class<?> stackClass = Reflect.tryCls("net.minecraft.world.item.ItemStack");
            Object items = null, carried = null;
            for (Method m : menu.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                try {
                    if (items == null && java.util.List.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        Object r = m.invoke(menu);
                        if (r instanceof java.util.List && !((java.util.List<?>) r).isEmpty()
                            && stackClass != null && stackClass.isInstance(((java.util.List<?>) r).get(0))) {
                            items = r;
                        }
                    } else if (carried == null && stackClass != null
                        && stackClass.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        carried = m.invoke(menu);
                    }
                } catch (Throwable ignored) {}
                if (items != null && carried != null) break;
            }
            if (!(items instanceof java.util.List)) {
                McLog.warn("背包: 拿不到 items");
                return list;
            }

            Class<?> pc = Reflect.tryCls(P + "ClientboundContainerSetContentPacket");
            if (pc == null) pc = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "WINDOW_ITEMS"));
            if (pc != null) {
                Object pkt = PacketCodec.construct(pc, 0, 0, items, carried);
                if (pkt == null) pkt = PacketCodec.construct(pc, 0, items); // 1.13–1.17 (<= 755)
                if (pkt != null) {
                    ByteBuf b = PacketCodec.encode(pc, pkt);
                    if (b != null) {
                        list.add(b);
                        McLog.info("背包: " + ((java.util.List<?>) items).size() + " 格");
                    }
                }
            }

            // 手持栏位。1.21.2 起 ClientboundSetCarriedItemPacket 改名成
            // ClientboundSetHeldSlotPacket（同名的 Serverbound 版仍在，别混）。
            int carriedId = PacketIdResolver.resolve(P + "ClientboundSetHeldSlotPacket");
            if (carriedId < 0) carriedId = PacketIdResolver.resolve(P + "ClientboundSetCarriedItemPacket");
            if (carriedId < 0) carriedId = PacketIdResolver.s2cPlayId(clientProtocol, "HELD_ITEM");
            if (carriedId >= 0) {
                Class<?> invClass = Reflect.tryCls("net.minecraft.world.entity.player.Inventory");
                if (invClass == null) invClass = Reflect.tryCls("net.minecraft.entity.player.PlayerInventory");
                if (invClass == null) invClass = Reflect.tryCls("net.minecraft.class_1661");
                Object inv = invClass != null ? Reflect.findFieldByType(p, invClass) : null;
                Object sel = inv != null ? Reflect.fieldAny(inv, "selected", "f_35977_", "currentItem", "field_70461_c", "field_7545") : null;
                ByteBuf b = Unpooled.buffer();
                McFraming.writeVarInt(b, carriedId);
                b.writeByte(sel instanceof Integer ? (Integer) sel : 0);
                list.add(b);
            }
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "背包");

        for (ByteBuf b : out) sender.send(b);
    }

    private void sendPlayerState(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object p = player(mc());
            Object lvl = level(p);

            int healthId = PacketIdResolver.resolve(P + "ClientboundSetHealthPacket");
            if (healthId < 0) healthId = PacketIdResolver.s2cPlayId(clientProtocol, "HEALTH");
            if (healthId >= 0) {
                Class<?> fdCls = Reflect.tryCls("net.minecraft.world.food.FoodData");
                if (fdCls == null) fdCls = Reflect.tryCls("net.minecraft.util.FoodStats");
                if (fdCls == null) fdCls = Reflect.tryCls("net.minecraft.class_1702");
                Object fd = fdCls != null ? Reflect.invokeByReturnType(p, fdCls) : null;
                int food = 20; float sat = 5f;
                if (fd != null) {
                    Object f = Reflect.invokeAnyTyped(fd, Integer.class, new String[]{"getFoodLevel", "m_38702_", "func_75116_a", "method_7586"});
                    if (f instanceof Integer) food = (Integer) f;
                    Object s = Reflect.invokeAnyTyped(fd, Float.class, new String[]{"getSaturationLevel", "m_38722_", "func_75115_e", "method_7589"});
                    if (s instanceof Float) sat = (Float) s;
                }
                ByteBuf b = Unpooled.buffer();
                McFraming.writeVarInt(b, healthId);
                b.writeFloat(flt(p, "getHealth", "m_21223_", "func_110143_aJ", "method_6032"));
                McFraming.writeVarInt(b, food);
                b.writeFloat(sat);
                list.add(b);
            }

            int expId = PacketIdResolver.resolve(P + "ClientboundSetExperiencePacket");
            if (expId < 0) expId = PacketIdResolver.s2cPlayId(clientProtocol, "EXPERIENCE");
            if (expId >= 0) {
                Object prog = Reflect.fieldAny(p, "experienceProgress", "f_36107_", "experience", "field_71106_cc", "field_7510");
                Object lvlN = Reflect.fieldAny(p, "experienceLevel", "f_36078_", "field_71068_ca", "field_7520");
                Object total = Reflect.fieldAny(p, "totalExperience", "f_36079_", "experienceTotal", "field_71067_cb", "field_7495");
                ByteBuf b = Unpooled.buffer();
                McFraming.writeVarInt(b, expId);
                b.writeFloat(prog instanceof Number ? ((Number) prog).floatValue() : 0f);
                McFraming.writeVarInt(b, lvlN instanceof Integer ? (Integer) lvlN : 0);
                McFraming.writeVarInt(b, total instanceof Integer ? (Integer) total : 0);
                list.add(b);
            }

            int abilId = PacketIdResolver.resolve(P + "ClientboundPlayerAbilitiesPacket");
            if (abilId < 0) abilId = PacketIdResolver.s2cPlayId(clientProtocol, "ABILITIES");
            if (abilId >= 0) {
                Class<?> abCls = Reflect.tryCls("net.minecraft.world.entity.player.Abilities");
                if (abCls == null) abCls = Reflect.tryCls("net.minecraft.entity.player.PlayerCapabilities");
                if (abCls == null) abCls = Reflect.tryCls("net.minecraft.class_1656");
                Object ab = abCls != null ? Reflect.findFieldByType(p, abCls) : null;
                if (ab != null) {
                    int flags = 0;
                    if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "invulnerable", "f_35921_", "disableDamage", "field_75102_a", "field_7480"))) flags |= 1;
                    if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "flying", "f_35935_", "isFlying", "field_75100_b", "field_7479")))             flags |= 2;
                    if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "mayfly", "f_35936_", "allowFlying", "field_75101_c", "field_7478")))          flags |= 4;
                    if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "instabuild", "f_35937_", "isCreativeMode", "field_75098_d", "field_7477")))   flags |= 8;
                    Object fly = Reflect.fieldAny(ab, "flyingSpeed", "f_35939_", "flySpeed", "field_75096_f", "field_7481");
                    Object walk = Reflect.fieldAny(ab, "walkingSpeed", "f_35940_", "walkSpeed", "field_75097_g", "field_7482");
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, abilId);
                    b.writeByte(flags);
                    b.writeFloat(fly instanceof Number ? ((Number) fly).floatValue() : 0.05f);
                    b.writeFloat(walk instanceof Number ? ((Number) walk).floatValue() : 0.1f);
                    list.add(b);
                }
            }

            int timeId = PacketIdResolver.resolve(P + "ClientboundSetTimePacket");
            if (timeId < 0) timeId = PacketIdResolver.s2cPlayId(clientProtocol, "TIME");
            if (timeId >= 0) {
                Object day = Reflect.invokeAnyTyped(lvl, Long.class, new String[]{"getDayTime", "m_46468_", "func_72820_D", "method_8532"});
                Object game = Reflect.invokeAnyTyped(lvl, Long.class, new String[]{"getGameTime", "m_46467_", "func_82737_E", "method_8510"});
                ByteBuf b = Unpooled.buffer();
                McFraming.writeVarInt(b, timeId);
                b.writeLong(game instanceof Number ? ((Number) game).longValue() : 0L);
                b.writeLong(day instanceof Number ? ((Number) day).longValue() : 0L);
                list.add(b);
            }
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "玩家状态");

        for (ByteBuf b : out) sender.send(b);
        McLog.info("玩家状态: " + out.size() + " 个包");
    }

    // ======================== 实体 ========================

    private void sendEntities(PacketSender sender) throws Exception {
        List<ByteBuf> out = onMain(() -> {
            List<ByteBuf> list = new ArrayList<>();
            Object p = player(mc());
            Object lvl = level(p);
            Integer selfId = joinGameEntityId != null ? joinGameEntityId : Reflect.readEntityId(p, lvl);

            List<Object> entities = allEntities(lvl);
            if (entities.isEmpty()) {
                McLog.warn("实体: 一个都没找到");
                return list;
            }

            Class<?> addEntityPc = Reflect.tryCls(P + "ClientboundAddEntityPacket");
            if (addEntityPc == null) addEntityPc = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "ADD_ENTITY"));

            Class<?> addPlayerPc = Reflect.tryCls(P + "ClientboundAddPlayerPacket");
            if (addPlayerPc == null && clientProtocol <= 763) {
                addPlayerPc = PacketIdResolver.clientboundPlayClassById(
                    PacketIdResolver.s2cPlayId(clientProtocol, "ADD_PLAYER"));
            }

            Class<?> addMobPc = Reflect.tryCls(P + "ClientboundAddMobPacket");
            if (addMobPc == null && clientProtocol <= 758) {
                addMobPc = PacketIdResolver.clientboundPlayClassById(
                    PacketIdResolver.s2cPlayId(clientProtocol, "ADD_MOB"));
            }

            Class<?> dataPc = Reflect.tryCls(P + "ClientboundSetEntityDataPacket");
            if (dataPc == null) dataPc = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "ENTITY_DATA"));

            Class<?> motionPc = Reflect.tryCls(P + "ClientboundSetEntityMotionPacket");
            if (motionPc == null) motionPc = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "ENTITY_MOTION"));

            Class<?> headPc = Reflect.tryCls(P + "ClientboundRotateHeadPacket");
            if (headPc == null) headPc = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "ROTATE_HEAD"));

            Class<?> equipPc = Reflect.tryCls(P + "ClientboundSetEquipmentPacket");
            if (equipPc == null) equipPc = PacketIdResolver.clientboundPlayClassById(
                PacketIdResolver.s2cPlayId(clientProtocol, "EQUIPMENT"));

            Class<?> playerClass = Reflect.tryCls("net.minecraft.world.entity.player.Player");
            if (playerClass == null) playerClass = Reflect.tryCls("net.minecraft.entity.player.PlayerEntity");
            if (playerClass == null) playerClass = Reflect.tryCls("net.minecraft.class_1657");
            Class<?> gpClass = Reflect.tryCls("com.mojang.authlib.GameProfile");

            int players = 0, others = 0, failed = 0;
            String firstFail = null;
            for (Object e : entities) {
                try {
                    Integer eid = Reflect.readEntityId(e, lvl);
                    if (eid == null || eid.equals(selfId)) continue;

                    boolean isPlayer = (playerClass != null && playerClass.isInstance(e))
                        || (gpClass != null && Reflect.invokeByReturnType(e, gpClass) != null);

                    ByteBuf spawn = null;
                    if (isPlayer) {
                        if (addPlayerPc != null) {
                            Object pkt = PacketCodec.construct(addPlayerPc, e);
                            if (pkt != null) spawn = PacketCodec.encode(addPlayerPc, pkt);
                        }
                        if (spawn == null) spawn = spawnViaAddEntity(addEntityPc, e, eid);
                        if (spawn != null) players++;
                    } else {
                        // 1.13 – 1.18.2 (<= 758): 生物必须走 AddMobPacket(LivingEntity)
                        if (addMobPc != null && !e.getClass().getSimpleName().contains("ArmorStand")) {
                            Object mp = PacketCodec.construct(addMobPc, e);
                            if (mp != null) spawn = PacketCodec.encode(addMobPc, mp);
                        }
                        if (spawn == null) spawn = spawnViaAddEntity(addEntityPc, e, eid);
                        if (spawn != null) others++;
                    }
                    if (spawn == null) {
                        failed++;
                        if (firstFail == null) firstFail = e.getClass().getSimpleName();
                        continue;
                    }
                    list.add(spawn);

                    if (dataPc != null) {
                        Class<?> sedClass = Reflect.tryCls("net.minecraft.network.syncher.SynchedEntityData");
                        if (sedClass == null) sedClass = Reflect.tryCls("net.minecraft.network.datasync.EntityDataManager");
                        if (sedClass == null) sedClass = Reflect.tryCls("net.minecraft.class_2945");
                        Object data = sedClass != null ? Reflect.invokeByReturnType(e, sedClass) : null;
                        if (data != null) {
                            // 1.13 – 1.19.2 (<= 760) 直接支持 3 参构造器 (eid, synchedEntityData, sendAll=true)
                            Object pkt = PacketCodec.construct(dataPc, eid, data, Boolean.TRUE);
                            if (pkt == null) {
                                Object vals = allDataValues(data);
                                if (vals != null) pkt = PacketCodec.construct(dataPc, eid, vals);
                            }
                            if (pkt != null) {
                                ByteBuf b = PacketCodec.encode(dataPc, pkt);
                                if (b != null) list.add(b);
                            }
                        }
                    }
                    if (motionPc != null) {
                        Object pkt = PacketCodec.construct(motionPc, e);
                        if (pkt != null) {
                            ByteBuf b = PacketCodec.encode(motionPc, pkt);
                            if (b != null) list.add(b);
                        }
                    }
                    if (headPc != null) {
                        float hy = flt(e, "getYHeadRot", "m_6080_", "func_70079_am", "method_5791");
                        Object pkt = PacketCodec.construct(headPc, e, (byte) (int) (hy * 256.0f / 360.0f));
                        if (pkt != null) {
                            ByteBuf b = PacketCodec.encode(headPc, pkt);
                            if (b != null) list.add(b);
                        }
                    }
                    if (equipPc != null) {
                        ByteBuf b = equipmentPacket(equipPc, e, eid);
                        if (b != null) list.add(b);
                    }
                } catch (Throwable t) {
                    McLog.warn("实体序列化失败: " + t.getMessage());
                }
            }
            McLog.info("实体: " + players + " 个玩家 + " + others + " 个其他，共 " + list.size() + " 个包"
                + (failed > 0 ? "；" + failed + " 个生成不出 spawn 包（首个: " + firstFail + "）" : ""));
            return list;
        }, java.util.Collections.<ByteBuf>emptyList(), "实体");

        for (ByteBuf b : out) sender.send(b);
    }

    /**
     * 构造 AddEntity 包。
     *
     * 1.21.x 的构造器只剩 (Entity, ServerEntity[, int]) 和全字段版 ——
     * ServerEntity 是服务端类，客户端根本没有实例，所以前两个永远构造不出来。
     * 实测 127 个实体全部静默失败、B 那边一个实体都看不到。
     * 全字段版没有服务端依赖，各版本都在，用它兜底：
     *   (int id, UUID, double x,y,z, float xRot,yRot, EntityType, int data, Vec3 motion, double yHeadRot)
     */
    private ByteBuf spawnViaAddEntity(Class<?> addEntityPc, Object e, int eid) {
        if (addEntityPc == null) return null;

        // 老版本仍有 (Entity) / (Entity,int)，能用就用 —— 字段顺序由游戏自己保证
        Object pkt = PacketCodec.construct(addEntityPc, e);
        if (pkt == null) pkt = PacketCodec.construct(addEntityPc, e, 0);
        if (pkt != null) return PacketCodec.encode(addEntityPc, pkt);

        try {
            Object type = entityTypeOf(e);
            if (type == null) return null;
            Object uuid = Reflect.invokeAny(e, new String[]{"getUUID", "m_20148_", "getUniqueID"});
            if (!(uuid instanceof java.util.UUID)) return null;

            Object motion = Reflect.invokeAny(e, new String[]{"getDeltaMovement", "m_20184_"});
            if (motion == null) {
                Class<?> vec3 = Reflect.tryCls("net.minecraft.world.phys.Vec3");
                if (vec3 != null) motion = PacketCodec.construct(vec3, 0.0d, 0.0d, 0.0d);
            }
            if (motion == null) return null;

            pkt = PacketCodec.construct(addEntityPc,
                eid, uuid,
                dbl(e, "getX", "m_20185_"), dbl(e, "getY", "m_20186_"), dbl(e, "getZ", "m_20189_"),
                flt(e, "getXRot", "m_146909_"), flt(e, "getYRot", "m_146908_"),
                type, 0, motion, (double) flt(e, "getYHeadRot", "m_6080_"));
            if (pkt == null) return null;
            return PacketCodec.encode(addEntityPc, pkt);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 实体的 EntityType 对象（不是数字 ID —— 写错数字会让生物全变 allay/giant）。 */
    private Object entityTypeOf(Object e) {
        Class<?> etClass = Reflect.tryCls("net.minecraft.world.entity.EntityType");
        if (etClass == null) return null;
        Object t = Reflect.invokeByReturnType(e, etClass);
        return t != null ? t : Reflect.invokeAny(e, new String[]{"getType", "m_6095_"});
    }

    /** 装备（手持 + 盔甲），否则 B 看到的玩家都是空手。 */
    private ByteBuf equipmentPacket(Class<?> pc, Object entity, int eid) {
        try {
            Class<?> living = Reflect.tryCls("net.minecraft.world.entity.LivingEntity");
            if (living == null || !living.isInstance(entity)) return null;
            Class<?> slotClass = Reflect.cls("net.minecraft.world.entity.EquipmentSlot");
            Object[] slots = slotClass.getEnumConstants();
            if (slots == null) return null;

            Method getItem = null;
            for (Method m : living.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != slotClass) continue;
                if (!m.getReturnType().getName().contains("ItemStack")) continue;
                m.setAccessible(true); getItem = m; break;
            }
            if (getItem == null) return null;

            List<Object> pairs = new ArrayList<>();
            for (Object slot : slots) {
                Object stack = getItem.invoke(entity, slot);
                if (stack == null) continue;
                Object pair = makePair(slot, stack);
                if (pair != null) pairs.add(pair);
            }
            if (pairs.isEmpty()) return null;

            Object pkt = PacketCodec.construct(pc, eid, pairs);
            return pkt != null ? PacketCodec.encode(pc, pkt) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 level 里全部实体：试所有无参 Iterable 方法，取元素最多的那个。 */
    @SuppressWarnings("unchecked")
    private List<Object> allEntities(Object lvl) {
        Class<?> entityClass = Reflect.tryCls("net.minecraft.world.entity.Entity");
        if (entityClass == null) return new ArrayList<>();
        List<Object> best = new ArrayList<>();
        String from = null;
        for (Class<?> c = lvl.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!Iterable.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(lvl);
                    if (!(r instanceof Iterable)) continue;
                    List<Object> snap = new ArrayList<>();
                    for (Object o : (Iterable<Object>) r) {
                        if (o != null && entityClass.isInstance(o)) snap.add(o);
                    }
                    if (snap.size() > best.size()) { best = snap; from = m.getName(); }
                } catch (Throwable ignored) {}
            }
        }
        if (from != null) McLog.info("实体来源: " + from + "() → " + best.size() + " 个");
        return best;
    }

    // ======================== 出生点 ========================

    /** 1.20.1 靠这个包结束"加载世界中"并让 tick 跑起来。 */
    private void sendSpawnPosition(PacketSender sender) throws Exception {
        int id = PacketIdResolver.resolve(P + "ClientboundSetDefaultSpawnPositionPacket");
        if (id < 0) return;
        double[] pos = readPose();
        long x = (long) Math.floor(pos[0]), y = (long) Math.floor(pos[1]), z = (long) Math.floor(pos[2]);
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        buf.writeLong(((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF));
        buf.writeFloat(0f);
        sender.send(buf);
    }

    // ======================== 小工具 ========================

    private java.util.Collection<?> collectionOf(Object owner, String elementTypeHint) {
        for (Method m : owner.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!java.util.Collection.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
                Object r = m.invoke(owner);
                if (!(r instanceof java.util.Collection)) continue;
                java.util.Collection<?> c = (java.util.Collection<?>) r;
                if (c.isEmpty()) continue;
                Object first = c.iterator().next();
                if (first != null && first.getClass().getName().contains(elementTypeHint)) return c;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 取实体的**全量**同步数据。
     *
     * SynchedEntityData 有两个取值方法，语义差很多：
     *   getNonDefaultValues() / m_135379_  —— 只返回和默认值不同的项
     *   getAll() / m_135384_               —— 全部项
     * 而 packDirty() 只返回自上次同步后改过的，对新客户端几乎是空的。
     *
     * 给刚连入的客户端必须发全量，否则它对没收到的项一律用默认值：
     * 盔甲架的 invisible 位丢了就不隐身，羊的颜色项丢了就变成白色。
     */
    private Object allDataValues(Object entityData) {
        // 按名字找 getAll，但要校验返回的确实是 DataValue 列表 ——
        // 名字对上不代表方法对，m_20088_ 那次教训就是这么来的。
        Object all = Reflect.invokeAny(entityData, new String[]{"getAll", "m_135384_"});
        if (isDataValueList(all)) return all;
        // 名字对不上时按签名找：无参、返回 List、且返回的元素是 DataValue/DataItem
        for (Method m : entityData.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!java.util.List.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
                Object r = m.invoke(entityData);
                if (!(r instanceof java.util.List)) continue;
                java.util.List<?> list = (java.util.List<?>) r;
                if (list.isEmpty()) continue;
                String cn = list.get(0).getClass().getName();
                if (cn.contains("DataValue") || cn.contains("DataItem")) {
                    return list;
                }
            } catch (Throwable ignored) {}
        }
        // 兜底：至少发脏值，聊胜于无
        Object dirty = Reflect.invokeAny(entityData, new String[]{"getNonDefaultValues", "m_135379_"});
        return isDataValueList(dirty) ? dirty : null;
    }

    /** 是不是 DataValue/DataItem 的非空列表。 */
    private boolean isDataValueList(Object o) {
        if (!(o instanceof java.util.List)) return false;
        java.util.List<?> l = (java.util.List<?>) o;
        if (l.isEmpty()) return false;
        Object first = l.get(0);
        if (first == null) return false;
        String cn = first.getClass().getName();
        return cn.contains("DataValue") || cn.contains("DataItem");
    }

    /** 反射构造 Pair —— agent 编译期没有 datafixers 依赖。 */
    private Object makePair(Object first, Object second) {
        try {
            Class<?> pairClass = Reflect.cls("com.mojang.datafixers.util.Pair");
            Method of = pairClass.getMethod("of", Object.class, Object.class);
            return of.invoke(null, first, second);
        } catch (Throwable t) {
            return null;
        }
    }

    private double dbl(Object o, String... names) {
        Object v = Reflect.invokeAnyTyped(o, Double.class, names);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private float flt(Object o, String... names) {
        Object v = Reflect.invokeAnyTyped(o, Float.class, names);
        return v instanceof Number ? ((Number) v).floatValue() : 0f;
    }

    private int viewDistance() {
        try {
            Class<?> optClass = Reflect.tryCls("net.minecraft.client.Options");
            Object opts = optClass != null ? Reflect.findFieldByType(mc(), optClass) : null;
            if (opts != null) {
                Object ov = Reflect.fieldAny(opts, "renderDistance", "f_92084_");
                Object val = ov instanceof Integer ? ov
                    : Reflect.invokeAnyTyped(ov, Integer.class, new String[]{"get", "m_231551_"});
                if (val instanceof Integer && (Integer) val > 0) return Math.min((Integer) val, 12);
            }
        } catch (Throwable ignored) {}
        return 8;
    }

    private Object dimensionName(Object lvl) {
        Object key = Reflect.invokeAny(lvl, new String[]{"dimension", "m_46472_"});
        Object loc = key != null ? Reflect.invokeAny(key, new String[]{"location", "m_135782_"}) : null;
        return loc != null ? loc : resourceLocation("minecraft", "overworld");
    }

    private Object dimensionTypeName(Object lvl) {
        try {
            Object dt = Reflect.invokeAny(lvl, new String[]{"dimensionTypeId", "dimensionTypeRegistration", "m_204156_"});
            if (dt != null) {
                Object key = Reflect.invokeAny(dt, new String[]{"unwrapKey"});
                if (key instanceof java.util.Optional && ((java.util.Optional<?>) key).isPresent()) {
                    Object rk = ((java.util.Optional<?>) key).get();
                    Object loc = Reflect.invokeAny(rk, new String[]{"location", "m_135782_"});
                    if (loc != null) return loc;
                }
            }
        } catch (Throwable ignored) {}
        return resourceLocation("minecraft", "overworld");
    }

    private boolean isFlat(Object lvl) {
        Object f = Reflect.invokeAnyTyped(lvl, Boolean.class, new String[]{"isFlat", "m_104583_"});
        return Boolean.TRUE.equals(f);
    }

    private Object resourceLocation(String ns, String path) {
        try {
            Class<?> rl = Reflect.cls("net.minecraft.resources.ResourceLocation");
            return rl.getConstructor(String.class, String.class).newInstance(ns, path);
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeResourceLocation(ByteBuf buf, Object rl) {
        McFraming.writeString(buf, rl != null ? rl.toString() : "minecraft:overworld");
    }
}
