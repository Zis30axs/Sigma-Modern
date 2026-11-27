package com.mcrelay.agent.proxy;

import com.mcrelay.agent.util.McLog;
import com.mcrelay.agent.util.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 简单 ByteBuf 包装，用于构造 Minecraft 包。
 *
 * 简单类型直接写入底层 ByteBuf。
 * NBT 写入委托给 MC 自己的 FriendlyByteBuf.writeNbt()，确保 root NBT 格式正确
 *   (type byte + empty name + content)，与 vanilla 协议一致。
 * ResourceLocation 手动写为 "namespace:path" 字符串。
 */
public final class FriendlyBuf implements AutoCloseable {

    private final io.netty.buffer.ByteBuf underlying;

    public FriendlyBuf(io.netty.buffer.ByteBuf buf) {
        this.underlying = buf;
    }

    public io.netty.buffer.ByteBuf buf() { return underlying; }

    // ===== 简单类型：直接写入 ByteBuf =====

    public FriendlyBuf writeVarInt(int v) { McFraming.writeVarInt(underlying, v); return this; }
    public FriendlyBuf writeInt(int v) { underlying.writeInt(v); return this; }
    public FriendlyBuf writeByte(int v) { underlying.writeByte(v); return this; }
    public FriendlyBuf writeBoolean(boolean v) { underlying.writeBoolean(v); return this; }
    public FriendlyBuf writeLong(long v) { underlying.writeLong(v); return this; }
    public FriendlyBuf writeFloat(float v) { underlying.writeFloat(v); return this; }
    public FriendlyBuf writeDouble(double v) { underlying.writeDouble(v); return this; }
    public FriendlyBuf writeShort(int v) { underlying.writeShort(v); return this; }

    public FriendlyBuf writeUtf(String s) { McFraming.writeString(underlying, s); return this; }
    public FriendlyBuf writeUtf(String s, int max) { McFraming.writeString(underlying, s); return this; }
    public FriendlyBuf writeUUID(java.util.UUID u) { McFraming.writeUuid(underlying, u); return this; }

    // ===== NBT 写入：委托给 MC 的 FriendlyByteBuf.writeNbt() =====
    //
    // 为什么不自己写？
    //   Minecraft 协议中的 NBT 是 "root NBT" 格式:
    //     [type byte] [name length (2 bytes)] [name UTF-8] [tag content]
    //   vanilla MC 的 FriendlyByteBuf.writeNbt() 调用 NbtIo.writeAnyTag(tag, output)，
    //   它会写 type byte + empty name(0x0000) + content。
    //
    //   如果直接调用 Tag.write(DataOutput)，只写 content，不写 type byte 和 name，
    //   导致协议解析错误 (ViaVersion 报 "dimensions is null")。
    //
    //   所以直接委托给 MC 自己的 FriendlyByteBuf，让它用正确的方式序列化。

    private static Constructor<?> mcFbbConstructor;
    private static Method mcWriteNbtMethod;
    private static boolean nbtInitialized = false;

    private static synchronized void initNbt() {
        if (nbtInitialized) return;
        nbtInitialized = true;
        try {
            Class<?> fbbClass = Reflect.tryCls("net.minecraft.network.FriendlyByteBuf");
            Class<?> tagClass = Reflect.tryCls("net.minecraft.nbt.Tag");
            if (fbbClass == null || tagClass == null) {
                McLog.warn("FriendlyBuf: FriendlyByteBuf 或 Tag 类未找到，NBT 写入将使用 fallback");
                return;
            }

            // FriendlyByteBuf(ByteBuf) 构造函数
            mcFbbConstructor = fbbClass.getConstructor(io.netty.buffer.ByteBuf.class);

            // 查找 writeNbt(Tag) 方法:
            //   - 1 个参数
            //   - 参数类型是 Tag 或其子类
            //   - 返回类型是 FriendlyByteBuf (方法链)
            for (Method m : fbbClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 &&
                    tagClass.isAssignableFrom(m.getParameterTypes()[0]) &&
                    fbbClass.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    mcWriteNbtMethod = m;
                    McLog.info("FriendlyBuf: MC writeNbt 方法 = " + m.getName() +
                        " param=" + m.getParameterTypes()[0].getSimpleName());
                    break;
                }
            }

            if (mcWriteNbtMethod == null) {
                // 回退: 尝试找任何 1 参数且参数为 Tag 类型的方法
                for (Method m : fbbClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 &&
                        tagClass.isAssignableFrom(m.getParameterTypes()[0])) {
                        m.setAccessible(true);
                        mcWriteNbtMethod = m;
                        McLog.info("FriendlyBuf: MC writeNbt 方法 (宽松匹配) = " + m.getName());
                        break;
                    }
                }
            }

            if (mcWriteNbtMethod == null) {
                McLog.warn("FriendlyBuf: 未找到 writeNbt 方法，将使用 fallback");
            }
        } catch (Throwable t) {
            McLog.error("FriendlyBuf: NBT 初始化失败", t);
        }
    }

    /**
     * 写 NBT 到 buffer。
     * 委托给 MC 的 FriendlyByteBuf.writeNbt()，确保 root NBT 格式正确。
     */
    public FriendlyBuf writeNbt(Object tag) {
        if (tag == null) {
            // null NBT 写为 TAG_End (0x00)
            underlying.writeByte(0);
            return this;
        }

        initNbt();

        if (mcWriteNbtMethod != null && mcFbbConstructor != null) {
            try {
                Object realFbb = mcFbbConstructor.newInstance(underlying);
                int before = underlying.writerIndex();
                mcWriteNbtMethod.invoke(realFbb, tag);
                int written = underlying.writerIndex() - before;
                if (written > 0) {
                    McLog.info("FriendlyBuf.writeNbt: 写入 " + written + " bytes via MC FriendlyByteBuf." +
                        mcWriteNbtMethod.getName() + "()");
                    return this;
                }
            } catch (Throwable t) {
                McLog.warn("FriendlyBuf.writeNbt 委托 MC FriendlyByteBuf 失败: " + t.getMessage());
            }
        }

        // 混淆/1.16.5 Forge/Fabric 通用兜底：直接调用 Tag.write(DataOutput) 写出 NamedNBT (0x0A + 0x0000 + payload)
        for (Class<?> c = tag.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == java.io.DataOutput.class
                    && m.getReturnType() == void.class) {
                    try {
                        m.setAccessible(true);
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
                        dos.writeByte(0x0A);
                        dos.writeShort(0);
                        m.invoke(tag, dos);
                        dos.flush();
                        underlying.writeBytes(bos.toByteArray());
                        return this;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Fallback: 写入空 CompoundTag (root format: type + name + end)
        McLog.warn("FriendlyBuf.writeNbt fallback: 写入空 CompoundTag (NBT 数据丢失!)");
        underlying.writeByte(0x0A);
        underlying.writeShort(0);
        underlying.writeByte(0x00);
        return this;
    }

    // ===== ResourceLocation 写入：手动写为字符串 =====

    /**
     * 写 ResourceLocation 到 buffer。
     * ResourceLocation 在协议中就是 "namespace:path" 字符串 (VarInt-length + UTF-8)。
     */
    public FriendlyBuf writeResourceLocation(Object rl) {
        String str = resourceLocationToString(rl);
        McFraming.writeString(underlying, str);
        return this;
    }

    /** 将 ResourceLocation 对象转为 "namespace:path" 字符串。 */
    private String resourceLocationToString(Object rl) {
        if (rl == null) return "minecraft:overworld";
        if (rl instanceof String) return ensureNamespace((String) rl);

        // 尝试调用 getNamespace() + getPath()
        try {
            String ns = (String) rl.getClass().getMethod("getNamespace").invoke(rl);
            String path = (String) rl.getClass().getMethod("getPath").invoke(rl);
            if (ns != null && path != null) return ns + ":" + path;
        } catch (Throwable ignored) {}

        // 尝试调用 toString()
        try {
            String s = rl.toString();
            if (s != null && s.contains(":")) return s;
        } catch (Throwable ignored) {}

        return "minecraft:overworld";
    }

    private String ensureNamespace(String s) {
        if (s.contains(":")) return s;
        return "minecraft:" + s;
    }

    // ===== 其他 =====

    public FriendlyBuf writeBooleanThen(boolean b) { underlying.writeBoolean(b); return this; }

    public FriendlyBuf writeVarIntArray(java.util.List<Integer> arr) {
        McFraming.writeVarInt(underlying, arr.size());
        for (int i : arr) McFraming.writeVarInt(underlying, i);
        return this;
    }

    @Override public void close() {}
}
