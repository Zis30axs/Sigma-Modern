package com.mcrelay.agent.adapter;

import com.mcrelay.agent.proxy.McFraming;
import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 用 MC 自己的包对象 + write() 生成 payload。
 *
 * 为什么不手写序列化：手写要自己查 registry ID、自己排字段顺序，错一个字节整包报废，
 * 而且错法很隐蔽 —— 实测把 EntityType ID 写成 0，B 那边所有生物变成 allay；
 * 写成 42 就全变 giant。构造 MC 的包对象再调 write()，ID 和字段顺序都由游戏自己保证。
 *
 * 跨版本：write 方法按"1 参 + 参数是 buffer 类 + 返回 void"定位，不认名字，
 * 因此 Mojang 名(write)、SRG 名(m_5779_)、1.12.2 的 writePacketData 全都能命中。
 * 1.20.2+ 改用 StreamCodec，在 encode 里额外兜一层。
 */
public final class PacketCodec {

    private PacketCodec() {}

    /** buffer 包装类：1.13+ 是 FriendlyByteBuf (Fabric: class_2540)，1.12.2 及更早是 PacketBuffer。 */
    private static Class<?> bufClass() {
        Class<?> c = Reflect.tryCls("net.minecraft.network.FriendlyByteBuf");
        if (c == null) c = Reflect.tryCls("net.minecraft.network.PacketBuffer");
        if (c == null) c = Reflect.tryCls("net.minecraft.class_2540");
        return c;
    }

    private static Object newBuffer(ByteBuf raw) throws Exception {
        Class<?> regBufClass = Reflect.tryCls("net.minecraft.network.RegistryFriendlyByteBuf");
        if (regBufClass == null) regBufClass = Reflect.tryCls("net.minecraft.class_9129");
        if (regBufClass != null) {
            Object ra = registryAccess();
            if (ra != null) {
                try {
                    for (Method m : regBufClass.getDeclaredMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterCount() != 1) continue;
                        if (!m.getParameterTypes()[0].isInstance(ra)) continue;
                        if (!java.util.function.Function.class.isAssignableFrom(m.getReturnType())) continue;
                        m.setAccessible(true);
                        Object fn = m.invoke(null, ra);
                        @SuppressWarnings("unchecked")
                        Object buf = ((java.util.function.Function<Object, Object>) fn).apply(raw);
                        if (buf != null) return buf;
                    }
                } catch (Throwable ignored) {}
                for (Constructor<?> ctor : regBufClass.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length != 2) continue;
                    if (!p[0].isAssignableFrom(ByteBuf.class)) continue;
                    if (!p[1].isInstance(ra)) continue;
                    try {
                        ctor.setAccessible(true);
                        return ctor.newInstance(raw, ra);
                    } catch (Throwable ignored) {}
                }
            }
            McLog.warn("PacketCodec: 有 RegistryFriendlyByteBuf 但构造不出来，退回普通 buffer");
        }
        Class<?> bc = bufClass();
        if (bc == null) return null;
        return bc.getConstructor(ByteBuf.class).newInstance(raw);
    }

    /** 从 A 的 ClientPacketListener 拿 RegistryAccess，供 RegistryFriendlyByteBuf 使用。 */
    private static Object registryAccess() {
        try {
            Object mc = Reflect.findMinecraftInstance();
            if (mc == null) return null;
            Class<?> cpl = Reflect.tryCls("net.minecraft.client.multiplayer.ClientPacketListener");
            if (cpl == null) cpl = Reflect.tryCls("net.minecraft.class_634");
            Object conn = cpl != null ? Reflect.invokeByReturnType(mc, cpl) : null;
            if (conn == null && cpl != null) conn = Reflect.findFieldByType(mc, cpl);
            if (conn == null) return null;
            Class<?> raClass = Reflect.tryCls("net.minecraft.core.RegistryAccess");
            if (raClass == null) raClass = Reflect.tryCls("net.minecraft.class_5455");
            if (raClass == null) return null;
            Object ra = Reflect.invokeByReturnType(conn, raClass);
            return ra != null ? ra : Reflect.findFieldByType(conn, raClass);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object construct(Class<?> pktClass, Object... args) {
        if (pktClass == null) return null;
        outer:
        for (Constructor<?> ctor : pktClass.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length != args.length) continue;
            for (int i = 0; i < p.length; i++) {
                if (!paramAccepts(p[i], args[i])) continue outer;
            }
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            } catch (Throwable t) {
                McLog.warn("construct " + pktClass.getSimpleName() + " 失败: " + rootMsg(t));
            }
        }
        return null;
    }

    public static Object callStaticFactory(Class<?> owner, Class<?> returnType, Object... args) {
        outer:
        for (Method m : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!returnType.isAssignableFrom(m.getReturnType())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != args.length) continue;
            for (int i = 0; i < p.length; i++) {
                if (!paramAccepts(p[i], args[i])) continue outer;
            }
            try {
                m.setAccessible(true);
                Object r = m.invoke(null, args);
                if (r != null) return r;
            } catch (Throwable t) {
                McLog.warn("callStaticFactory " + owner.getSimpleName() + "." + m.getName() +
                    " 失败: " + rootMsg(t));
            }
        }
        return null;
    }

    public static ByteBuf build(String pktClassName, Object... ctorArgs) {
        Class<?> pktClass = Reflect.tryCls(pktClassName);
        if (pktClass == null) {
            McLog.warn("PacketCodec: 找不到包类 " + pktClassName);
            return null;
        }
        Object pkt = construct(pktClass, ctorArgs);
        if (pkt == null) {
            McLog.warn("PacketCodec: 无匹配构造器 " + pktClass.getSimpleName() +
                "(" + describe(ctorArgs) + ")");
            return null;
        }
        return encode(pktClass, pkt);
    }

    public static ByteBuf encode(Class<?> pktClass, Object pkt) {
        int id = PacketIdResolver.clientboundPlayIdOf(pktClass);
        if (id < 0) id = PacketIdResolver.resolve(pktClass.getName());
        if (id < 0) {
            McLog.warn("PacketCodec: 解析不到包 ID " + pktClass.getSimpleName());
            return null;
        }
        return encodeWithId(id, pktClass, pkt);
    }

    /** 按显式给定的包 ID 编码（完全兼容 1.8.9–26.2 原版混淆 PacketBuffer / FriendlyByteBuf / StreamCodec）。 */
    public static ByteBuf encodeWithId(int id, Class<?> pktClass, Object pkt) {
        if (id < 0 || pktClass == null || pkt == null) return null;
        ByteBuf buf = Unpooled.buffer();
        McFraming.writeVarInt(buf, id);
        try {
            Object fbb = newBuffer(buf);
            if (fbb != null) {
                Method write = findWrite(pktClass, fbb.getClass());
                if (write != null) {
                    write.invoke(pkt, fbb);
                    return buf;
                }
                if (encodeViaStreamCodec(pktClass, pkt, fbb)) {
                    return buf;
                }
            }
            // 原版 1.8.9 / 1.12.2 / 1.16.5 混淆环境：直接从 writePacketData(PacketBuffer) 形参反推混淆的 PacketBuffer 子类！
            for (Class<?> c = pktClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 1 || m.getReturnType() != void.class) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    Class<?> param = m.getParameterTypes()[0];
                    if (ByteBuf.class.isAssignableFrom(param)) {
                        Constructor<?> ctor = param.getConstructor(ByteBuf.class);
                        ctor.setAccessible(true);
                        Object obfBuf = ctor.newInstance(buf);
                        m.setAccessible(true);
                        m.invoke(pkt, obfBuf);
                        return buf;
                    }
                }
            }

            McLog.warn("PacketCodec: " + pktClass.getSimpleName() + " 既无 write() 也无 StreamCodec");
            buf.release();
            return null;
        } catch (Throwable t) {
            McLog.warn("PacketCodec: 编码 " + pktClass.getSimpleName() + " 失败: " + rootMsg(t));
            buf.release();
            return null;
        }
    }

    /**
     * 找 write(buffer)：1 参、参数是 buffer 类、返回 void。
     * 不按名字找 —— 混淆环境下是 SRG 名，1.12.2 又叫 writePacketData。
     */
    private static Method findWrite(Class<?> pktClass, Class<?> bufClass) {
        Method fallback = null;
        for (Class<?> c = pktClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != void.class) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(bufClass)) continue;
                m.setAccessible(true);
                // 参数类型完全等于 buffer 类的优先（避免命中 Object 参数的方法）
                if (m.getParameterTypes()[0] == bufClass) return m;
                if (fallback == null) fallback = m;
            }
        }
        return fallback;
    }

    /** 1.20.2+ : 包类的静态 STREAM_CODEC / TYPE 字段上的 encode(buf, value)。 */
    private static boolean encodeViaStreamCodec(Class<?> pktClass, Object pkt, Object fbb) {
        try {
            Class<?> scClass = Reflect.tryCls("net.minecraft.network.codec.StreamCodec");
            if (scClass == null) return false;
            for (Class<?> c = pktClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (!scClass.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object codec = f.get(null);
                    if (codec == null) continue;
                    for (Method m : codec.getClass().getMethods()) {
                        if (m.getParameterCount() != 2) continue;
                        if (!m.getName().equals("encode")) continue;
                        m.setAccessible(true);
                        m.invoke(codec, fbb, pkt);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            McLog.warn("PacketCodec: StreamCodec 编码失败: " + rootMsg(t));
        }
        return false;
    }

    /** 形参能否接受这个实参（含基本类型装箱）。 */
    private static boolean paramAccepts(Class<?> param, Object arg) {
        if (arg == null) return !param.isPrimitive();
        if (!param.isPrimitive()) return param.isInstance(arg);
        if (param == int.class)     return arg instanceof Integer;
        if (param == boolean.class) return arg instanceof Boolean;
        if (param == long.class)    return arg instanceof Long;
        if (param == float.class)   return arg instanceof Float;
        if (param == double.class)  return arg instanceof Double;
        if (param == byte.class)    return arg instanceof Byte;
        if (param == short.class)   return arg instanceof Short;
        if (param == char.class)    return arg instanceof Character;
        return false;
    }

    private static String describe(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
