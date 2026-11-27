package com.mcrelay.agent.adapter;

import com.mcrelay.agent.VersionDetect;
import com.mcrelay.agent.proxy.McFraming;
import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 1.8.x – 1.12.2（协议 47 – 340）全世代世界快照重建适配器。
 *
 * 兼容环境：
 *  - Forge 1.8.9 / 1.9.4 / 1.10.2 / 1.11.2 / 1.12.2 (SRG / MCP)
 *  - 原版 / OptiFine / Lunar / Feather / 网易及第三方 AuthCl 启动器（完全混淆或半混淆）
 *
 * 设计要点：
 *  - 固定小包（JoinGame、PlayerInfo、PlayerPosLook、UpdateHealth、Abilities、Time、HeldItem）
 *    直接按经 ViaVersion 核对的精确字节协议构造，零混淆依赖；
 *  - 复杂状态包（ChunkData、SpawnPlayer、SpawnMob、SpawnObject、EntityMetadata、WindowItems、
 *    EntityEquipment、Teams、Scoreboard）直接通过 PacketIdResolver.clientboundPlayClassById(id)
 *    从宿主自身的 EnumConnectionState.PLAY 反查真实包类并调用其构造器 + writePacketData，
 *    连混淆类名都不需要猜。
 */
public class LegacyAdapter implements RelayAdapter {

    private volatile int clientProtocol;
    private volatile Integer joinGameEntityId;

    public LegacyAdapter(int hostProtocol) {
        this.clientProtocol = hostProtocol > 0 ? hostProtocol : 340;
    }

    @Override
    public void setClientProtocol(int protocol) {
        if (protocol > 0) {
            this.clientProtocol = protocol;
        }
        McLog.info("LegacyAdapter: 客户端协议=" + this.clientProtocol);
    }

    private int proto() {
        if (clientProtocol > 0 && clientProtocol <= 340) return clientProtocol;
        try {
            int hp = VersionDetect.detect().protocol;
            if (hp > 0 && hp <= 340) return hp;
        } catch (Throwable ignored) {}
        return 340;
    }

    // ======================== 核心对象定位（兼容 SRG / MCP / 原版混淆） ========================

    private Object mc() {
        Object mc = Reflect.findMinecraftInstance();
        if (mc == null) throw new IllegalStateException("拿不到 Minecraft 实例");
        return mc;
    }

    private Object player(Object mc) {
        Class<?> sp = Reflect.tryCls("net.minecraft.client.entity.EntityPlayerSP");
        if (sp != null) {
            Object p = Reflect.findFieldByType(mc, sp);
            if (p != null) return p;
        }
        Object byName = Reflect.fieldAny(mc, "player", "thePlayer", "field_71439_g");
        if (byName != null) return byName;

        // 原版完全混淆回退：Minecraft 实例字段里唯一在继承链上持有 GameProfile 的实体对象就是 thePlayer
        Class<?> gpClass = Reflect.tryCls("com.mojang.authlib.GameProfile");
        if (gpClass != null) {
            for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object cand = f.get(mc);
                        if (cand == null || cand == mc) continue;
                        String cn = cand.getClass().getName();
                        if (cn.startsWith("java.") || cn.startsWith("com.mojang.")) continue;
                        if (Reflect.findFieldByType(cand, gpClass) != null
                            && hasThreeDoubles(cand.getClass())) {
                            return cand;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        throw new IllegalStateException("拿不到 EntityPlayerSP —— 宿主可能尚未进入服务器");
    }

    private static boolean hasThreeDoubles(Class<?> cls) {
        int doubles = 0;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == double.class) {
                    doubles++;
                    if (doubles >= 3) return true;
                }
            }
        }
        return false;
    }

    private Object world(Object mc, Object player) {
        Class<?> wc = Reflect.tryCls("net.minecraft.client.multiplayer.WorldClient");
        if (wc == null) wc = Reflect.tryCls("net.minecraft.world.World");
        if (wc != null) {
            Object w = Reflect.findFieldByType(mc, wc);
            if (w != null) return w;
            if (player != null) {
                w = Reflect.findFieldByType(player, wc);
                if (w != null) return w;
            }
        }
        Object byName = Reflect.fieldAny(mc, "world", "theWorld", "field_71441_e");
        if (byName != null) return byName;
        if (player != null) {
            byName = Reflect.fieldAny(player, "world", "worldObj", "field_70170_p");
            if (byName != null) return byName;
        }
        // 原版混淆回退：theWorld 是 mc 字段里唯一内部 List 包含 player 实例的世界对象
        if (player != null) {
            for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object cand = f.get(mc);
                        if (cand == null || cand == mc || cand == player) continue;
                        for (Class<?> wcCur = cand.getClass(); wcCur != null && wcCur != Object.class; wcCur = wcCur.getSuperclass()) {
                            for (Field lf : wcCur.getDeclaredFields()) {
                                if (Modifier.isStatic(lf.getModifiers()) || !List.class.isAssignableFrom(lf.getType())) continue;
                                lf.setAccessible(true);
                                Object list = lf.get(cand);
                                if (list instanceof List && ((List<?>) list).contains(player)) {
                                    return cand;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        throw new IllegalStateException("拿不到 WorldClient");
    }

    private Object netHandler(Object mc, Object player) {
        Class<?> nhc = Reflect.tryCls("net.minecraft.client.network.NetHandlerPlayClient");
        if (nhc == null) nhc = Reflect.tryCls("net.minecraft.client.multiplayer.NetHandlerPlayClient");
        if (nhc != null) {
            Object c = Reflect.invokeByReturnType(mc, nhc);
            if (c != null) return c;
            if (player != null) {
                c = Reflect.findFieldByType(player, nhc);
                if (c != null) return c;
            }
        }
        Object byMethod = Reflect.invokeAny(mc, new String[]{"getConnection", "getNetHandler", "func_147114_u"});
        if (byMethod != null) return byMethod;
        if (player != null) {
            Object byField = Reflect.fieldAny(player, "connection", "sendQueue", "field_71174_a");
            if (byField != null) return byField;
            // 原版混淆回退：在 player 字段里找内部持有 NetworkManager(含 Channel) 的对象
            for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object cand = f.get(player);
                        if (cand == null) continue;
                        if (holdsActiveChannel(cand)) return cand;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static boolean holdsActiveChannel(Object obj) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    if (v instanceof io.netty.channel.Channel) return true;
                    if (Reflect.findFieldByType(v, io.netty.channel.Channel.class) != null) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private <T> T onMain(final Callable<T> task, T fallback, String what) {
        Object mc;
        try { mc = mc(); } catch (Throwable t) { mc = null; }
        if (mc != null) {
            boolean onClientThread = "Client thread".equals(Thread.currentThread().getName());
            if (!onClientThread) {
                Object b = Reflect.invokeAnyTyped(mc, Boolean.class,
                    new String[]{"isCallingFromMinecraftThread", "func_152345_ab"});
                if (Boolean.TRUE.equals(b)) onClientThread = true;
            }
            if (onClientThread) {
                try { return task.call(); } catch (Throwable t) {
                    McLog.error(what + " 失败(主线程内联)", t);
                    return fallback;
                }
            }
            final CompletableFuture<T> fut = new CompletableFuture<T>();
            Runnable job = new Runnable() {
                @Override
                public void run() {
                    try { fut.complete(task.call()); } catch (Throwable t) { fut.completeExceptionally(t); }
                }
            };
            Method sched = findRunnableScheduler(mc.getClass());
            if (sched != null) {
                try {
                    sched.invoke(mc, job);
                    return fut.get(15, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    McLog.error(what + " 主线程调度失败", t);
                    return fallback;
                }
            }
        }
        try { return task.call(); } catch (Throwable t) {
            McLog.error(what + " 失败", t);
            return fallback;
        }
    }

    private static Method findRunnableScheduler(Class<?> mcClass) {
        for (String name : new String[]{"addScheduledTask", "func_152344_a"}) {
            for (Class<?> c = mcClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(Runnable.class)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        for (Class<?> c = mcClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] == Runnable.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    // ======================== 1. 宿主 AuthCl 账号档案提取 ========================

    @Override
    public PlayerProfile readProfile() {
        try {
            Object mcObj = mc();
            Object p = player(mcObj);
            Class<?> gpClass = Reflect.tryCls("com.mojang.authlib.GameProfile");
            Object gp = null;
            if (gpClass != null) {
                gp = Reflect.invokeByReturnType(p, gpClass);
                if (gp == null) gp = Reflect.findFieldByType(p, gpClass);
            }
            String name = null;
            UUID uuid = null;
            List<String[]> props = new ArrayList<String[]>();
            if (gp != null) {
                Object n = Reflect.invokeAny(gp, new String[]{"getName", "name"});
                if (n instanceof String) name = (String) n;
                Object u = Reflect.invokeAny(gp, new String[]{"getId", "id"});
                if (u instanceof UUID) uuid = (UUID) u;
                Object pm = Reflect.invokeAny(gp, new String[]{"getProperties", "properties"});
                extractProfileProperties(pm, props);
            }
            if (uuid == null) {
                Object u = Reflect.invokeAnyTyped(p, UUID.class, new String[]{"getUniqueID", "func_110124_au"});
                if (u instanceof UUID) uuid = (UUID) u;
            }
            return new PlayerProfile(
                name != null ? name : "Player",
                uuid != null ? uuid : UUID.randomUUID(),
                readGamemode(mcObj),
                props
            );
        } catch (Throwable t) {
            McLog.warn("LegacyAdapter 读取玩家档案失败: " + t.getMessage());
            return new PlayerProfile("Player", UUID.randomUUID(), 0);
        }
    }

    private void extractProfileProperties(Object propMap, List<String[]> out) {
        if (propMap == null) return;
        try {
            Object vals = Reflect.invokeAny(propMap, new String[]{"values"});
            if (!(vals instanceof Iterable)) return;
            for (Object prop : (Iterable<?>) vals) {
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

    private int readGamemode(Object mcObj) {
        try {
            Object ctrl = Reflect.fieldAny(mcObj, "playerController", "field_71442_b");
            if (ctrl != null) {
                Object gt = Reflect.invokeAny(ctrl, new String[]{"getCurrentGameType", "func_178889_l"});
                if (gt == null) {
                    for (Field f : ctrl.getClass().getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) && f.getType().isEnum()) {
                            f.setAccessible(true);
                            gt = f.get(ctrl);
                            if (gt != null) break;
                        }
                    }
                }
                if (gt instanceof Enum) {
                    int ord = ((Enum<?>) gt).ordinal();
                    // GameType: NOT_SET(-1, ord 0), SURVIVAL(0, ord 1), CREATIVE(1, ord 2), ADVENTURE(2, ord 3), SPECTATOR(3, ord 4)
                    Object id = Reflect.invokeAnyTyped(gt, Integer.class, new String[]{"getID", "func_77148_a"});
                    if (id instanceof Integer && (Integer) id >= 0) return (Integer) id;
                    return ord > 0 ? ord - 1 : 0;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private int readDimension(Object player, Object world) {
        try {
            Object dim = Reflect.fieldAny(player, "dimension", "field_71093_bK");
            if (dim instanceof Integer) return (Integer) dim;
            Object prov = Reflect.fieldAny(world, "provider", "field_73011_w");
            if (prov != null) {
                Object d = Reflect.invokeAnyTyped(prov, Integer.class,
                    new String[]{"getDimension", "getDimensionId", "func_177502_q", "field_76574_g"});
                if (d instanceof Integer) return (Integer) d;
                Object df = Reflect.fieldAny(prov, "dimensionId", "field_76574_g");
                if (df instanceof Integer) return (Integer) df;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private int readDifficulty(Object world) {
        try {
            Object diff = Reflect.invokeAny(world, new String[]{"getDifficulty", "func_175659_aa"});
            if (diff instanceof Enum) {
                return ((Enum<?>) diff).ordinal() & 0x03;
            }
        } catch (Throwable ignored) {}
        return 2; // NORMAL
    }

    // ======================== 2. JoinGame (1.8.x – 1.12.2) ========================

    @Override
    public ByteBuf buildJoinGame() throws Exception {
        return onMain(new Callable<ByteBuf>() {
            @Override
            public ByteBuf call() {
                Object mcObj = mc();
                Object p = player(mcObj);
                Object w = world(mcObj, p);
                Integer eid = Reflect.readEntityId(p, w);
                if (eid == null) throw new IllegalStateException("读不到宿主实体 ID");
                joinGameEntityId = eid;

                int pr = proto();
                int pktId = PacketIdResolver.s2cPlayId(pr, "LOGIN");
                if (pktId < 0) pktId = (pr == 47) ? 0x01 : 0x23;

                int gm = readGamemode(mcObj) & 0x03;
                int dim = readDimension(p, w);
                int diff = readDifficulty(w);

                ByteBuf buf = Unpooled.buffer();
                McFraming.writeVarInt(buf, pktId);
                buf.writeInt(eid);
                buf.writeByte(gm);
                if (pr <= 107) {
                    // 1.8.x (47) 与 1.9.0 (107): dimension 为 1 字节 byte
                    buf.writeByte(dim);
                } else {
                    // 1.9.1 – 1.12.2 (108 – 340): dimension 为 4 字节 int
                    buf.writeInt(dim);
                }
                buf.writeByte(diff);
                buf.writeByte(20); // maxPlayers
                McFraming.writeString(buf, "default");
                buf.writeBoolean(false); // reducedDebugInfo
                McLog.info("LegacyAdapter JoinGame(protocol=" + pr + ") 构造成功: entityId=" + eid + ", dim=" + dim);
                return buf;
            }
        }, null, "Legacy JoinGame");
    }

    // ======================== 3. 世界状态完整重建 ========================

    @Override
    public void sendRemainingInitialPackets(final PacketSender sender) {
        McLog.info("=== LegacyAdapter 开始重建世界 (protocol=" + proto() + ") ===");
        step("玩家列表", new ThrowingRunnable() { @Override public void run() throws Exception { sendPlayerInfo(sender); } });
        step("玩家位置", new ThrowingRunnable() { @Override public void run() throws Exception { sendPosition(sender); } });
        step("区块数据", new ThrowingRunnable() { @Override public void run() throws Exception { sendChunks(sender); } });
        step("出生点",   new ThrowingRunnable() { @Override public void run() throws Exception { sendSpawnPos(sender); } });
        step("队伍规则", new ThrowingRunnable() { @Override public void run() throws Exception { sendTeams(sender); } });
        step("计分板",   new ThrowingRunnable() { @Override public void run() throws Exception { sendScoreboard(sender); } });
        step("背包栏位", new ThrowingRunnable() { @Override public void run() throws Exception { sendInventory(sender); } });
        step("玩家状态", new ThrowingRunnable() { @Override public void run() throws Exception { sendPlayerState(sender); } });
        step("周围实体", new ThrowingRunnable() { @Override public void run() throws Exception { sendEntities(sender); } });
        McLog.info("=== LegacyAdapter 世界重建完成 ===");
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private void step(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            McLog.warn("LegacyAdapter [" + name + "] 跳过: " + t.getMessage());
        }
    }

    // ---- 玩家列表 (PlayerListItem ADD_PLAYER = 0) ----
    private void sendPlayerInfo(PacketSender sender) {
        final int pr = proto();
        final int pktId = PacketIdResolver.s2cPlayId(pr, "PLAYER_INFO");
        if (pktId < 0) return;

        List<Object[]> rows = onMain(new Callable<List<Object[]>>() {
            @Override
            public List<Object[]> call() {
                List<Object[]> list = new ArrayList<Object[]>();
                java.util.Set<UUID> seen = new java.util.HashSet<UUID>();
                Object mcObj = mc();
                Object p = player(mcObj);
                Object w = world(mcObj, p);
                Object nh = netHandler(mcObj, p);
                Class<?> gpClass = Reflect.tryCls("com.mojang.authlib.GameProfile");

                // 1) NetHandlerPlayClient 里的 NetworkPlayerInfo 集合
                if (nh != null && gpClass != null) {
                    for (Class<?> c = nh.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object v = f.get(nh);
                                Collection<?> col = v instanceof Map ? ((Map<?, ?>) v).values()
                                    : (v instanceof Collection ? (Collection<?>) v : null);
                                if (col == null || col.isEmpty()) continue;
                                Object first = col.iterator().next();
                                if (first == null || Reflect.invokeByReturnType(first, gpClass) == null) continue;
                                for (Object info : col) {
                                    Object gp = Reflect.invokeByReturnType(info, gpClass);
                                    Object[] row = rowFromProfile(gp, 0);
                                    if (row != null && seen.add((UUID) row[0])) list.add(row);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                // 2) 从世界玩家实体补齐（保证假人/NPC 也能被 SpawnPlayer 正常渲染）
                if (gpClass != null) {
                    for (Object e : allEntities(w)) {
                        Object gp = Reflect.invokeByReturnType(e, gpClass);
                        if (gp == null) gp = Reflect.findFieldByType(e, gpClass);
                        if (gp != null) {
                            Object[] row = rowFromProfile(gp, 0);
                            if (row != null && seen.add((UUID) row[0])) list.add(row);
                        }
                    }
                }

                // 3) 确保宿主自身一定在列
                PlayerProfile self = readProfile();
                if (seen.add(self.uuid)) {
                    list.add(new Object[]{self.uuid, self.name, self.properties, self.gamemode});
                }
                return list;
            }
        }, new ArrayList<Object[]>(), "Legacy 玩家列表");

        if (rows.isEmpty()) return;
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        McFraming.writeVarInt(buf, 0); // Action 0 = ADD_PLAYER
        McFraming.writeVarInt(buf, rows.size());
        for (Object[] r : rows) {
            McFraming.writeUuid(buf, (UUID) r[0]);
            McFraming.writeString(buf, (String) r[1]);
            @SuppressWarnings("unchecked")
            List<String[]> props = (List<String[]>) r[2];
            McFraming.writeVarInt(buf, props.size());
            for (String[] prp : props) {
                McFraming.writeString(buf, prp[0]);
                McFraming.writeString(buf, prp[1]);
                boolean signed = prp.length > 2 && prp[2] != null && !prp[2].isEmpty();
                buf.writeBoolean(signed);
                if (signed) McFraming.writeString(buf, prp[2]);
            }
            McFraming.writeVarInt(buf, (Integer) r[3]); // gamemode
            McFraming.writeVarInt(buf, 0);              // ping
            buf.writeBoolean(false);                    // hasDisplayName
        }
        sender.send(buf);
        McLog.info("LegacyAdapter: 已发送 PlayerListItem (" + rows.size() + " 人)");
    }

    private Object[] rowFromProfile(Object gp, int gm) {
        if (gp == null) return null;
        try {
            Object u = Reflect.invokeAny(gp, new String[]{"getId", "id"});
            Object n = Reflect.invokeAny(gp, new String[]{"getName", "name"});
            if (!(u instanceof UUID) || !(n instanceof String)) return null;
            String name = (String) n;
            if (name.length() > 16) name = name.substring(0, 16);
            List<String[]> props = new ArrayList<String[]>();
            extractProfileProperties(Reflect.invokeAny(gp, new String[]{"getProperties", "properties"}), props);
            return new Object[]{u, name, props, gm};
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 玩家坐标与朝向 ----
    private double[] readPose(Object p) {
        Double x = dblField(p, "posX", "field_70165_t");
        Double y = dblField(p, "posY", "field_70163_u");
        Double z = dblField(p, "posZ", "field_70161_v");
        if (x == null || y == null || z == null) {
            // 原版混淆回退：从 AxisAlignedBB 碰撞箱精确反推脚底坐标 ((minX+maxX)/2, minY, (minZ+maxZ)/2)
            double[] bb = readAabbFeet(p);
            if (bb != null) {
                x = bb[0]; y = bb[1]; z = bb[2];
            }
        }
        Float yaw = fltField(p, "rotationYaw", "field_70177_z");
        Float pitch = fltField(p, "rotationPitch", "field_70125_A");
        if (yaw == null || pitch == null) {
            float[] rot = readFirstTwoEntityFloats(p);
            if (yaw == null) yaw = rot[0];
            if (pitch == null) pitch = rot[1];
        }
        return new double[]{
            x != null ? x : 0.0,
            y != null ? y : 64.0,
            z != null ? z : 0.0,
            yaw != null ? yaw : 0.0f,
            pitch != null ? pitch : 0.0f
        };
    }

    private static double[] readAabbFeet(Object entity) {
        Class<?> root = rootEntityClass(entity.getClass());
        for (Field f : root.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                Object box = f.get(entity);
                if (box == null) continue;
                List<Double> ds = new ArrayList<Double>();
                for (Field bf : box.getClass().getDeclaredFields()) {
                    if (!Modifier.isStatic(bf.getModifiers()) && bf.getType() == double.class) {
                        bf.setAccessible(true);
                        ds.add(bf.getDouble(box));
                    }
                }
                if (ds.size() == 6) {
                    // AxisAlignedBB 恒为 (minX, minY, minZ, maxX, maxY, maxZ)
                    return new double[]{(ds.get(0) + ds.get(3)) * 0.5, ds.get(1), (ds.get(2) + ds.get(5)) * 0.5};
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static float[] readFirstTwoEntityFloats(Object entity) {
        Class<?> root = rootEntityClass(entity.getClass());
        float[] out = new float[]{0f, 0f};
        int idx = 0;
        for (Field f : root.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == float.class) {
                try {
                    f.setAccessible(true);
                    out[idx++] = f.getFloat(entity);
                    if (idx == 2) break; // Entity 前两个 float 恒为 rotationYaw, rotationPitch
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static Class<?> rootEntityClass(Class<?> c) {
        while (c.getSuperclass() != null && c.getSuperclass() != Object.class) {
            c = c.getSuperclass();
        }
        return c;
    }

    private static Double dblField(Object obj, String... names) {
        Object v = Reflect.fieldAny(obj, names);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static Float fltField(Object obj, String... names) {
        Object v = Reflect.fieldAny(obj, names);
        return v instanceof Number ? ((Number) v).floatValue() : null;
    }

    private void sendPosition(PacketSender sender) {
        final int pr = proto();
        int pktId = PacketIdResolver.s2cPlayId(pr, "PLAYER_POS");
        if (pktId < 0) return;
        double[] pose = onMain(new Callable<double[]>() {
            @Override
            public double[] call() { return readPose(player(mc())); }
        }, new double[]{0, 64, 0, 0, 0}, "Legacy 位置");

        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        buf.writeDouble(pose[0]);
        buf.writeDouble(pose[1]);
        buf.writeDouble(pose[2]);
        buf.writeFloat((float) pose[3]);
        buf.writeFloat((float) pose[4]);
        buf.writeByte(0); // flags: 全部绝对坐标
        if (pr >= 107) {
            // 1.9+ 新增 VarInt teleportId
            McFraming.writeVarInt(buf, 1);
        }
        sender.send(buf);
    }

    // ---- 区块快照 (1.8.x S21PacketChunkData / 1.9–1.12.2 SPacketChunkData) ----
    private void sendChunks(PacketSender sender) {
        final int pr = proto();
        final int chunkPktId = PacketIdResolver.s2cPlayId(pr, "CHUNK_DATA");
        if (chunkPktId < 0) return;

        List<ByteBuf> chunks = onMain(new Callable<List<ByteBuf>>() {
            @Override
            public List<ByteBuf> call() {
                List<ByteBuf> out = new ArrayList<ByteBuf>();
                Object mcObj = mc();
                Object p = player(mcObj);
                Object w = world(mcObj, p);

                Class<?> pktClass = PacketIdResolver.clientboundPlayClassById(chunkPktId);
                if (pktClass == null) {
                    pktClass = Reflect.tryCls("net.minecraft.network.play.server.SPacketChunkData");
                    if (pktClass == null) pktClass = Reflect.tryCls("net.minecraft.network.play.server.S21PacketChunkData");
                }
                if (pktClass == null) {
                    McLog.warn("LegacyAdapter: 找不到 ChunkData 包类");
                    return out;
                }

                // 从 ChunkData 包构造器第一参数反推当前环境的 Chunk 类（完全无视原版混淆）
                Class<?> chunkClass = null;
                for (Constructor<?> ctor : pktClass.getDeclaredConstructors()) {
                    Class<?>[] pts = ctor.getParameterTypes();
                    if ((pts.length == 2 || pts.length == 3) && !pts[0].isPrimitive()) {
                        chunkClass = pts[0];
                        break;
                    }
                }
                if (chunkClass == null) chunkClass = Reflect.tryCls("net.minecraft.world.chunk.Chunk");
                if (chunkClass == null) return out;

                // 从 world 找 getChunkFromChunkCoords(int, int) -> Chunk
                Method getChunk = Reflect.findMethodByReturnAndParams(w.getClass(), chunkClass, int.class, int.class);
                Object chunkTarget = w;
                if (getChunk == null) {
                    // 尝试 world.getChunkProvider()
                    for (Class<?> c = w.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            try {
                                f.setAccessible(true);
                                Object cp = f.get(w);
                                if (cp == null) continue;
                                Method m = Reflect.findMethodByReturnAndParams(cp.getClass(), chunkClass, int.class, int.class);
                                if (m != null) {
                                    chunkTarget = cp;
                                    getChunk = m;
                                    break;
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (getChunk != null) break;
                    }
                }
                if (getChunk == null) {
                    McLog.warn("LegacyAdapter: 找不到 getChunk(int,int)");
                    return out;
                }

                double[] pose = readPose(p);
                int cx = ((int) Math.floor(pose[0])) >> 4;
                int cz = ((int) Math.floor(pose[2])) >> 4;
                int radius = readRenderDistance(mcObj);

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        try {
                            Object chunk = getChunk.invoke(chunkTarget, cx + dx, cz + dz);
                            if (chunk == null || isEmptyChunk(chunk)) continue;
                            Object pkt;
                            if (pr <= 109) {
                                // 1.8.x – 1.9.2: (Chunk, boolean initialize=true, int mask=0xFFFF)
                                pkt = PacketCodec.construct(pktClass, chunk, Boolean.TRUE, 0xFFFF);
                                if (pkt == null) pkt = PacketCodec.construct(pktClass, chunk, 0xFFFF);
                            } else {
                                // 1.9.4 – 1.12.2: (Chunk, int mask=0xFFFF)
                                pkt = PacketCodec.construct(pktClass, chunk, 0xFFFF);
                                if (pkt == null) pkt = PacketCodec.construct(pktClass, chunk, Boolean.TRUE, 0xFFFF);
                            }
                            if (pkt == null) continue;
                            ByteBuf encoded = PacketCodec.encodeWithId(chunkPktId, pktClass, pkt);
                            if (encoded != null) out.add(encoded);
                        } catch (Throwable ignored) {}
                    }
                }
                McLog.info("LegacyAdapter: 已序列化区块 " + out.size() + " 个");
                return out;
            }
        }, new ArrayList<ByteBuf>(), "Legacy 区块");

        for (ByteBuf b : chunks) sender.send(b);
    }

    private static boolean isEmptyChunk(Object chunk) {
        if (chunk.getClass().getSimpleName().contains("Empty")) return true;
        Object empty = Reflect.invokeAnyTyped(chunk, Boolean.class, new String[]{"isEmpty", "func_76621_g"});
        return Boolean.TRUE.equals(empty);
    }

    private int readRenderDistance(Object mcObj) {
        try {
            Object gs = Reflect.fieldAny(mcObj, "gameSettings", "field_71474_y");
            if (gs != null) {
                Object rd = Reflect.fieldAny(gs, "renderDistanceChunks", "field_151451_c");
                if (rd instanceof Integer && (Integer) rd >= 2 && (Integer) rd <= 32) {
                    return (Integer) rd;
                }
            }
        } catch (Throwable ignored) {}
        return 10;
    }

    private void sendSpawnPos(PacketSender sender) {
        int pr = proto();
        int pktId = PacketIdResolver.s2cPlayId(pr, "SPAWN_POS");
        if (pktId < 0) return;
        double[] pose = onMain(new Callable<double[]>() {
            @Override
            public double[] call() { return readPose(player(mc())); }
        }, new double[]{0, 64, 0, 0, 0}, "Legacy 出生点");
        int x = (int) Math.floor(pose[0]);
        int y = (int) Math.floor(pose[1]);
        int z = (int) Math.floor(pose[2]);
        long packed = (((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL);
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, pktId);
        buf.writeLong(packed);
        sender.send(buf);
    }

    // ---- 队伍与计分板 ----
    private void sendTeams(PacketSender sender) {
        final int pr = proto();
        final int teamsId = PacketIdResolver.s2cPlayId(pr, "TEAMS");
        if (teamsId < 0) return;
        List<ByteBuf> pkts = onMain(new Callable<List<ByteBuf>>() {
            @Override
            public List<ByteBuf> call() {
                List<ByteBuf> out = new ArrayList<ByteBuf>();
                Object mcObj = mc();
                Object w = world(mcObj, player(mcObj));
                Class<?> pktClass = PacketIdResolver.clientboundPlayClassById(teamsId);
                if (pktClass == null) {
                    pktClass = Reflect.tryCls("net.minecraft.network.play.server.SPacketTeams");
                    if (pktClass == null) pktClass = Reflect.tryCls("net.minecraft.network.play.server.S3EPacketTeams");
                }
                if (pktClass == null) return out;

                Class<?> teamClass = null;
                for (Constructor<?> ctor : pktClass.getDeclaredConstructors()) {
                    Class<?>[] pts = ctor.getParameterTypes();
                    if (pts.length == 2 && pts[1] == int.class && !pts[0].isPrimitive()) {
                        teamClass = pts[0];
                        break;
                    }
                }
                if (teamClass == null) return out;

                Object sb = findScoreboard(w);
                if (sb == null) return out;
                for (Method m : sb.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && Collection.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            Object col = m.invoke(sb);
                            if (!(col instanceof Collection) || ((Collection<?>) col).isEmpty()) continue;
                            Object first = ((Collection<?>) col).iterator().next();
                            if (!teamClass.isInstance(first)) continue;
                            for (Object team : (Collection<?>) col) {
                                Object pkt = PacketCodec.construct(pktClass, team, 0);
                                ByteBuf b = PacketCodec.encodeWithId(teamsId, pktClass, pkt);
                                if (b != null) out.add(b);
                            }
                            break;
                        } catch (Throwable ignored) {}
                    }
                }
                return out;
            }
        }, new ArrayList<ByteBuf>(), "Legacy 队伍");
        for (ByteBuf b : pkts) sender.send(b);
    }

    private void sendScoreboard(PacketSender sender) {
        final int pr = proto();
        final int objId = (pr == 47) ? 0x3B : (pr <= 316 ? 0x3F : (pr == 335 ? 0x41 : 0x42));
        final int dispId = (pr == 47) ? 0x3D : (pr <= 316 ? 0x38 : (pr == 335 ? 0x3A : 0x3B));
        final int scoreId = (pr == 47) ? 0x3C : (pr <= 316 ? 0x42 : (pr == 335 ? 0x44 : 0x45));

        List<ByteBuf> pkts = onMain(new Callable<List<ByteBuf>>() {
            @Override
            public List<ByteBuf> call() {
                List<ByteBuf> out = new ArrayList<ByteBuf>();
                Object mcObj = mc();
                Object w = world(mcObj, player(mcObj));
                Object sb = findScoreboard(w);
                if (sb == null) return out;

                Class<?> objPktCls = PacketIdResolver.clientboundPlayClassById(objId);
                Class<?> dispPktCls = PacketIdResolver.clientboundPlayClassById(dispId);
                Class<?> scorePktCls = PacketIdResolver.clientboundPlayClassById(scoreId);
                if (objPktCls == null || dispPktCls == null) return out;

                Class<?> objClass = null;
                for (Constructor<?> ctor : objPktCls.getDeclaredConstructors()) {
                    Class<?>[] pts = ctor.getParameterTypes();
                    if (pts.length == 2 && pts[1] == int.class && !pts[0].isPrimitive()) {
                        objClass = pts[0];
                        break;
                    }
                }
                if (objClass == null) return out;

                Collection<?> objectives = null;
                for (Method m : sb.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && Collection.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            Object col = m.invoke(sb);
                            if (col instanceof Collection && !((Collection<?>) col).isEmpty()
                                && objClass.isInstance(((Collection<?>) col).iterator().next())) {
                                objectives = (Collection<?>) col;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                if (objectives == null) return out;

                for (Object obj : objectives) {
                    Object pkt = PacketCodec.construct(objPktCls, obj, 0);
                    ByteBuf b = PacketCodec.encodeWithId(objId, objPktCls, pkt);
                    if (b != null) out.add(b);
                }

                Method getDisp = Reflect.findMethodByReturnAndParams(sb.getClass(), objClass, int.class);
                if (getDisp != null) {
                    for (int slot = 0; slot < 19; slot++) {
                        try {
                            Object obj = getDisp.invoke(sb, slot);
                            if (obj == null) continue;
                            Object pkt = PacketCodec.construct(dispPktCls, slot, obj);
                            ByteBuf b = PacketCodec.encodeWithId(dispId, dispPktCls, pkt);
                            if (b != null) out.add(b);
                        } catch (Throwable ignored) {}
                    }
                }

                if (scorePktCls != null) {
                    Method getScores = Reflect.findMethodByReturnAndParams(sb.getClass(), Collection.class, objClass);
                    if (getScores != null) {
                        for (Object obj : objectives) {
                            try {
                                Object scores = getScores.invoke(sb, obj);
                                if (!(scores instanceof Collection)) continue;
                                for (Object sc : (Collection<?>) scores) {
                                    Object pkt = PacketCodec.construct(scorePktCls, sc);
                                    ByteBuf b = PacketCodec.encodeWithId(scoreId, scorePktCls, pkt);
                                    if (b != null) out.add(b);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                return out;
            }
        }, new ArrayList<ByteBuf>(), "Legacy 计分板");
        for (ByteBuf b : pkts) sender.send(b);
    }

    private Object findScoreboard(Object world) {
        Class<?> sbClass = Reflect.tryCls("net.minecraft.scoreboard.Scoreboard");
        if (sbClass != null) {
            Object sb = Reflect.invokeByReturnType(world, sbClass);
            if (sb != null) return sb;
            sb = Reflect.findFieldByType(world, sbClass);
            if (sb != null) return sb;
        }
        return Reflect.invokeAny(world, new String[]{"getScoreboard", "func_96441_U"});
    }

    // ---- 背包与手持槽位 ----
    private void sendInventory(PacketSender sender) {
        final int pr = proto();
        final int winItemsId = PacketIdResolver.s2cPlayId(pr, "WINDOW_ITEMS");
        final int heldId = PacketIdResolver.s2cPlayId(pr, "HELD_ITEM");

        List<ByteBuf> pkts = onMain(new Callable<List<ByteBuf>>() {
            @Override
            public List<ByteBuf> call() {
                List<ByteBuf> out = new ArrayList<ByteBuf>();
                Object mcObj = mc();
                Object p = player(mcObj);

                if (winItemsId >= 0) {
                    Class<?> winCls = PacketIdResolver.clientboundPlayClassById(winItemsId);
                    if (winCls == null) {
                        winCls = Reflect.tryCls("net.minecraft.network.play.server.SPacketWindowItems");
                        if (winCls == null) winCls = Reflect.tryCls("net.minecraft.network.play.server.S30PacketWindowItems");
                    }
                    Object container = Reflect.fieldAny(p, "inventoryContainer", "field_71069_bz");
                    if (container == null) {
                        Class<?> contCls = Reflect.tryCls("net.minecraft.inventory.Container");
                        if (contCls != null) container = Reflect.findFieldByType(p, contCls);
                    }
                    if (winCls != null && container != null) {
                        Object items = Reflect.invokeAny(container, new String[]{"getInventory", "func_75138_a"});
                        if (items == null) {
                            for (Method m : container.getClass().getMethods()) {
                                if (m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                                    try {
                                        Object r = m.invoke(container);
                                        if (r instanceof List && ((List<?>) r).size() >= 36) {
                                            items = r;
                                            break;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        if (items != null) {
                            Object pkt = PacketCodec.construct(winCls, 0, items);
                            ByteBuf b = PacketCodec.encodeWithId(winItemsId, winCls, pkt);
                            if (b != null) out.add(b);
                        }
                    }
                }

                if (heldId >= 0) {
                    int slot = 0;
                    Object inv = Reflect.fieldAny(p, "inventory", "field_71071_by");
                    if (inv != null) {
                        Object s = Reflect.fieldAny(inv, "currentItem", "field_70461_c");
                        if (s instanceof Integer && (Integer) s >= 0 && (Integer) s <= 8) {
                            slot = (Integer) s;
                        }
                    }
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, heldId);
                    b.writeByte(slot);
                    out.add(b);
                }
                return out;
            }
        }, new ArrayList<ByteBuf>(), "Legacy 背包");
        for (ByteBuf b : pkts) sender.send(b);
    }

    // ---- 血量 / 经验 / 飞行能力 / 时间 ----
    private void sendPlayerState(PacketSender sender) {
        final int pr = proto();
        List<ByteBuf> pkts = onMain(new Callable<List<ByteBuf>>() {
            @Override
            public List<ByteBuf> call() {
                List<ByteBuf> out = new ArrayList<ByteBuf>();
                Object mcObj = mc();
                Object p = player(mcObj);
                Object w = world(mcObj, p);

                int healthId = PacketIdResolver.s2cPlayId(pr, "HEALTH");
                if (healthId >= 0) {
                    float hp = 20.0f;
                    Object hv = Reflect.invokeAnyTyped(p, Float.class, new String[]{"getHealth", "func_110143_aJ"});
                    if (hv instanceof Float) hp = (Float) hv;
                    int food = 20;
                    float sat = 5.0f;
                    Object stats = Reflect.invokeAny(p, new String[]{"getFoodStats", "func_71024_bL"});
                    if (stats != null) {
                        Object fl = Reflect.invokeAnyTyped(stats, Integer.class, new String[]{"getFoodLevel", "func_75116_a"});
                        if (fl instanceof Integer) food = (Integer) fl;
                        Object sl = Reflect.invokeAnyTyped(stats, Float.class, new String[]{"getSaturationLevel", "func_75115_e"});
                        if (sl instanceof Float) sat = (Float) sl;
                    }
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, healthId);
                    b.writeFloat(hp);
                    McFraming.writeVarInt(b, food);
                    b.writeFloat(sat);
                    out.add(b);
                }

                int expId = PacketIdResolver.s2cPlayId(pr, "EXPERIENCE");
                if (expId >= 0) {
                    Float bar = fltField(p, "experience", "field_71106_cc");
                    Object lvl = Reflect.fieldAny(p, "experienceLevel", "field_71068_ca");
                    Object tot = Reflect.fieldAny(p, "experienceTotal", "field_71067_cb");
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, expId);
                    b.writeFloat(bar != null ? bar : 0.0f);
                    McFraming.writeVarInt(b, lvl instanceof Integer ? (Integer) lvl : 0);
                    McFraming.writeVarInt(b, tot instanceof Integer ? (Integer) tot : 0);
                    out.add(b);
                }

                int abilId = PacketIdResolver.s2cPlayId(pr, "ABILITIES");
                if (abilId >= 0) {
                    Object ab = Reflect.fieldAny(p, "capabilities", "field_71075_bZ");
                    int flags = 0;
                    float fly = 0.05f, walk = 0.1f;
                    if (ab != null) {
                        if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "disableDamage", "field_75102_a"))) flags |= 1;
                        if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "isFlying", "field_75100_b")))      flags |= 2;
                        if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "allowFlying", "field_75101_c")))   flags |= 4;
                        if (Boolean.TRUE.equals(Reflect.fieldAny(ab, "isCreativeMode", "field_75098_d"))) flags |= 8;
                        Float fs = fltField(ab, "flySpeed", "field_75096_f");
                        Float ws = fltField(ab, "walkSpeed", "field_75097_g");
                        if (fs != null) fly = fs;
                        if (ws != null) walk = ws;
                    }
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, abilId);
                    b.writeByte(flags);
                    b.writeFloat(fly);
                    b.writeFloat(walk);
                    out.add(b);
                }

                int timeId = PacketIdResolver.s2cPlayId(pr, "TIME");
                if (timeId >= 0) {
                    Object total = Reflect.invokeAnyTyped(w, Long.class, new String[]{"getTotalWorldTime", "func_82737_E"});
                    Object day = Reflect.invokeAnyTyped(w, Long.class, new String[]{"getWorldTime", "func_72820_D"});
                    ByteBuf b = Unpooled.buffer();
                    McFraming.writeVarInt(b, timeId);
                    b.writeLong(total instanceof Long ? (Long) total : 0L);
                    b.writeLong(day instanceof Long ? (Long) day : 6000L);
                    out.add(b);
                }
                return out;
            }
        }, new ArrayList<ByteBuf>(), "Legacy 玩家状态");
        for (ByteBuf b : pkts) sender.send(b);
    }

    // ---- 周围全部玩家、生物与投射物/盔甲架实体 ----
    private void sendEntities(PacketSender sender) {
        final int pr = proto();
        final int addPlayerId = PacketIdResolver.s2cPlayId(pr, "ADD_PLAYER");
        final int addMobId = PacketIdResolver.s2cPlayId(pr, "ADD_MOB");
        final int addObjId = PacketIdResolver.s2cPlayId(pr, "ADD_ENTITY");
        final int metaId = PacketIdResolver.s2cPlayId(pr, "ENTITY_DATA");
        final int motionId = PacketIdResolver.s2cPlayId(pr, "ENTITY_MOTION");
        final int headId = PacketIdResolver.s2cPlayId(pr, "ROTATE_HEAD");
        final int equipId = PacketIdResolver.s2cPlayId(pr, "EQUIPMENT");

        List<ByteBuf> pkts = onMain(new Callable<List<ByteBuf>>() {
            @Override
            public List<ByteBuf> call() {
                List<ByteBuf> out = new ArrayList<ByteBuf>();
                Object mcObj = mc();
                Object p = player(mcObj);
                Object w = world(mcObj, p);
                Integer selfId = joinGameEntityId != null ? joinGameEntityId : Reflect.readEntityId(p, w);

                Class<?> addPlayerCls = PacketIdResolver.clientboundPlayClassById(addPlayerId);
                Class<?> addMobCls = PacketIdResolver.clientboundPlayClassById(addMobId);
                Class<?> addObjCls = PacketIdResolver.clientboundPlayClassById(addObjId);
                Class<?> metaCls = PacketIdResolver.clientboundPlayClassById(metaId);
                Class<?> motionCls = PacketIdResolver.clientboundPlayClassById(motionId);
                Class<?> headCls = PacketIdResolver.clientboundPlayClassById(headId);
                Class<?> equipCls = PacketIdResolver.clientboundPlayClassById(equipId);
                Class<?> armorStandCls = Reflect.tryCls("net.minecraft.entity.item.EntityArmorStand");

                for (Object e : allEntities(w)) {
                    try {
                        Integer eid = Reflect.readEntityId(e, w);
                        if (eid == null || eid.equals(selfId)) continue;

                        ByteBuf spawn = null;
                        // 1) 其他玩家: SpawnPlayer(EntityPlayer)
                        if (addPlayerCls != null) {
                            Object sp = PacketCodec.construct(addPlayerCls, e);
                            if (sp != null) spawn = PacketCodec.encodeWithId(addPlayerId, addPlayerCls, sp);
                        }
                        // 2) 盔甲架优先走 SpawnObject(e, 78)，与原版 EntityTrackerEntry 一致
                        if (spawn == null && addObjCls != null
                            && ((armorStandCls != null && armorStandCls.isInstance(e))
                                || e.getClass().getSimpleName().contains("ArmorStand"))) {
                            Object so = PacketCodec.construct(addObjCls, e, 78);
                            if (so != null) spawn = PacketCodec.encodeWithId(addObjId, addObjCls, so);
                        }
                        // 3) 生物: SpawnMob(EntityLivingBase)
                        if (spawn == null && addMobCls != null) {
                            Object sm = PacketCodec.construct(addMobCls, e);
                            if (sm != null) spawn = PacketCodec.encodeWithId(addMobId, addMobCls, sm);
                        }
                        // 4) 掉落物 / 末影水晶 / 船 / 矿车 / 投射物: SpawnObject
                        if (spawn == null && addObjCls != null) {
                            int objType = guessLegacyObjectType(e);
                            if (objType > 0) {
                                Object so = objType == 2
                                    ? PacketCodec.construct(addObjCls, e, 2, 1)
                                    : PacketCodec.construct(addObjCls, e, objType);
                                if (so != null) spawn = PacketCodec.encodeWithId(addObjId, addObjCls, so);
                            }
                        }
                        if (spawn == null) continue;
                        out.add(spawn);

                        // DataWatcher / EntityDataManager 全量元数据（隐身盔甲架、掉落物物品、名字标签、颜色）
                        if (metaCls != null) {
                            Object watcher = findDataWatcher(e, metaCls);
                            if (watcher != null) {
                                Object mp = PacketCodec.construct(metaCls, eid, watcher, Boolean.TRUE);
                                ByteBuf mb = PacketCodec.encodeWithId(metaId, metaCls, mp);
                                if (mb != null) out.add(mb);
                            }
                        }

                        if (motionCls != null) {
                            Object vp = PacketCodec.construct(motionCls, e);
                            ByteBuf vb = PacketCodec.encodeWithId(motionId, motionCls, vp);
                            if (vb != null) out.add(vb);
                        }

                        if (headCls != null) {
                            Float hy = fltField(e, "rotationYawHead", "field_70759_as");
                            if (hy == null) hy = 0f;
                            byte angle = (byte) (int) (hy * 256.0f / 360.0f);
                            Object hp = PacketCodec.construct(headCls, e, angle);
                            ByteBuf hb = PacketCodec.encodeWithId(headId, headCls, hp);
                            if (hb != null) out.add(hb);
                        }

                        if (equipCls != null) {
                            appendLegacyEquipment(out, pr, equipId, equipCls, e, eid);
                        }
                    } catch (Throwable ignored) {}
                }
                McLog.info("LegacyAdapter: 实体同步完成，共发送 " + out.size() + " 个包");
                return out;
            }
        }, new ArrayList<ByteBuf>(), "Legacy 实体");

        for (ByteBuf b : pkts) sender.send(b);
    }

    private Object findDataWatcher(Object entity, Class<?> metaPktCls) {
        Class<?> watcherClass = null;
        for (Constructor<?> ctor : metaPktCls.getDeclaredConstructors()) {
            Class<?>[] pts = ctor.getParameterTypes();
            if (pts.length == 3 && pts[0] == int.class && pts[2] == boolean.class) {
                watcherClass = pts[1];
                break;
            }
        }
        if (watcherClass != null) {
            Object w = Reflect.invokeByReturnType(entity, watcherClass);
            if (w != null) return w;
            return Reflect.findFieldByType(entity, watcherClass);
        }
        return null;
    }

    private void appendLegacyEquipment(List<ByteBuf> out, int pr, int equipId, Class<?> equipCls, Object entity, int eid) {
        if (pr == 47) {
            // 1.8.x: (int entityId, int slot [0..4], ItemStack)
            Method getEq = null;
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class
                        && !m.getReturnType().isPrimitive() && m.getReturnType() != String.class) {
                        if (m.getName().equals("getEquipmentInSlot") || m.getName().equals("func_71124_b") || getEq == null) {
                            m.setAccessible(true);
                            getEq = m;
                            if (!m.getName().startsWith("a")) break;
                        }
                    }
                }
            }
            if (getEq != null) {
                for (int slot = 0; slot < 5; slot++) {
                    try {
                        Object stack = getEq.invoke(entity, slot);
                        if (stack == null) continue;
                        Object pkt = PacketCodec.construct(equipCls, eid, slot, stack);
                        ByteBuf b = PacketCodec.encodeWithId(equipId, equipCls, pkt);
                        if (b != null) out.add(b);
                    } catch (Throwable ignored) {}
                }
            }
        } else {
            // 1.9 – 1.12.2: (int entityId, EntityEquipmentSlot, ItemStack)
            Class<?> slotEnum = null;
            for (Constructor<?> ctor : equipCls.getDeclaredConstructors()) {
                Class<?>[] pts = ctor.getParameterTypes();
                if (pts.length == 3 && pts[0] == int.class && pts[1].isEnum()) {
                    slotEnum = pts[1];
                    break;
                }
            }
            if (slotEnum == null) return;
            Method getSlotItem = null;
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == slotEnum
                        && !m.getReturnType().isPrimitive()) {
                        m.setAccessible(true);
                        getSlotItem = m;
                        break;
                    }
                }
                if (getSlotItem != null) break;
            }
            if (getSlotItem == null) return;
            Object[] slots = slotEnum.getEnumConstants();
            if (slots == null) return;
            for (Object slot : slots) {
                try {
                    Object stack = getSlotItem.invoke(entity, slot);
                    if (stack == null) continue;
                    Object pkt = PacketCodec.construct(equipCls, eid, slot, stack);
                    ByteBuf b = PacketCodec.encodeWithId(equipId, equipCls, pkt);
                    if (b != null) out.add(b);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static int guessLegacyObjectType(Object e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("ItemFrame")) return 71;
        if (n.contains("ArmorStand")) return 78;
        if (n.contains("Item")) return 2;
        if (n.contains("Boat")) return 1;
        if (n.contains("Minecart")) return 10;
        if (n.contains("EnderCrystal") || n.contains("EndCrystal")) return 51;
        if (n.contains("TNTPrimed")) return 50;
        if (n.contains("FallingBlock")) return 70;
        if (n.contains("Arrow")) return 60;
        if (n.contains("Snowball")) return 61;
        if (n.contains("Egg")) return 62;
        if (n.contains("Fireball")) return 63;
        if (n.contains("EnderPearl")) return 65;
        if (n.contains("Potion")) return 73;
        if (n.contains("FishHook")) return 90;
        return -1;
    }

    private List<Object> allEntities(Object world) {
        List<Object> best = new ArrayList<Object>();
        for (Class<?> c = world.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(world);
                    if (!(v instanceof List) || ((List<?>) v).isEmpty()) continue;
                    Object first = ((List<?>) v).get(0);
                    if (first != null && hasThreeDoubles(first.getClass()) && ((List<?>) v).size() > best.size()) {
                        best = new ArrayList<Object>((List<?>) v);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return best;
    }
}
