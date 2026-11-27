package com.mcrelay.agent.util;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反射工具：跨 ClassLoader 访问 MC 内部类。
 *
 * Forge/Fabric 使用自定义 ClassLoader（ModuleClassLoader / LaunchClassLoader）加载
 * net.minecraft.* 类，agent 的系统 ClassLoader 看不到它们。
 *
 * 解决策略（按优先级）：
 *   1. Class.forName(name) — 系统 ClassLoader，能找到 Forge bootstrap 类和 JDK 类
 *   2. Class.forName(name, true, mcClassLoader) — 找到的 MC ClassLoader
 *   3. Class.forName(name, true, contextClassLoader) — 线程上下文 ClassLoader
 *   4. Instrumentation.getAllLoadedClasses() 扫描 — 终极后备
 *
 * 一旦通过策略 4 找到 MC 类，会缓存其 ClassLoader 供后续使用。
 */
public final class Reflect {

    private static Instrumentation instrumentation;
    private static volatile ClassLoader mcClassLoader;
    private static final Map<String, Class<?>> cache = new HashMap<>();

    private Reflect() {}

    /**
     * 由 RelayAgent.agentmain 调用，传入 Instrumentation 实例。
     * 立即扫描已加载类，找到 MC 的 ClassLoader。
     */
    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
        McLog.info("Reflect: Instrumentation 已设置");
        findMcClassLoader();
    }

    /**
     * 扫描所有已加载类，找到 net.minecraft.client.Minecraft 或任意 net.minecraft.* 类，
     * 记录其 ClassLoader。
     */
    private static volatile Class<?> cachedMinecraftClass;

    private static void findMcClassLoader() {
        if (instrumentation == null) return;
        try {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            // 优先找 Minecraft 主类（Mojmap / MCP / Forge / Fabric Intermediary）
            for (Class<?> c : loaded) {
                String n = c.getName();
                if (n.equals("net.minecraft.client.Minecraft") || n.equals("net.minecraft.class_310")) {
                    mcClassLoader = c.getClassLoader();
                    cachedMinecraftClass = c;
                    McLog.info("Reflect: 找到 MC ClassLoader (via " + n + "): " + mcClassLoader);
                    return;
                }
            }
            // 回退：找任意 net.minecraft 类（包括原版未混淆的 RealmsSharedConstants / ClientBrandRetriever）
            for (Class<?> c : loaded) {
                if (c.getName().startsWith("net.minecraft.") && c.getClassLoader() != null) {
                    mcClassLoader = c.getClassLoader();
                    McLog.info("Reflect: 找到 MC ClassLoader (via " + c.getName() + "): " + mcClassLoader);
                    return;
                }
            }
            // 再回退：通过 GameProfile 或 Netty Channel 所在 ClassLoader 定位（兼容完全混淆环境）
            for (Class<?> c : loaded) {
                if ((c.getName().equals("com.mojang.authlib.GameProfile")
                    || c.getName().equals("io.netty.channel.Channel")) && c.getClassLoader() != null) {
                    mcClassLoader = c.getClassLoader();
                    McLog.info("Reflect: 找到 MC ClassLoader (via " + c.getName() + "): " + mcClassLoader);
                    return;
                }
            }
            McLog.warn("Reflect: getAllLoadedClasses 中未找到 net.minecraft.* 类（MC 可能尚未完全加载）");
        } catch (Throwable t) {
            McLog.warn("Reflect: 查找 MC ClassLoader 失败: " + t);
        }
    }

    /**
     * 定位 Minecraft 主类：兼容 Forge/MCP/Mojmap (net.minecraft.client.Minecraft)、
     * Fabric/Quilt (net.minecraft.class_310) 以及 1.8.9–26.2 原版混淆名（如 ave / bib / djz）。
     */
    public static Class<?> findMinecraftClass() {
        if (cachedMinecraftClass != null) return cachedMinecraftClass;
        Class<?> c = tryCls("net.minecraft.client.Minecraft");
        if (c == null) c = tryCls("net.minecraft.class_310");
        if (c != null) {
            cachedMinecraftClass = c;
            return c;
        }
        if (instrumentation != null) {
            try {
                Class<?> sessionSvc = tryCls("com.mojang.authlib.minecraft.MinecraftSessionService");
                for (Class<?> cand : instrumentation.getAllLoadedClasses()) {
                    if (cand == null || cand.isInterface() || cand.isEnum() || cand.isArray()) continue;
                    String cn = cand.getName();
                    if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
                        || cn.startsWith("jdk.") || cn.startsWith("com.mcrelay.")
                        || cn.startsWith("io.netty.") || cn.startsWith("org.")) continue;
                    boolean hasSelfSingleton = false;
                    boolean hasMcField = false;
                    for (Field f : cand.getDeclaredFields()) {
                        int mod = f.getModifiers();
                        if (Modifier.isStatic(mod) && f.getType() == cand) {
                            hasSelfSingleton = true;
                        } else if (!Modifier.isStatic(mod)) {
                            if ((sessionSvc != null && sessionSvc.isAssignableFrom(f.getType()))
                                || f.getType() == java.net.Proxy.class) {
                                hasMcField = true;
                            }
                        }
                    }
                    if (hasSelfSingleton && hasMcField) {
                        cachedMinecraftClass = cand;
                        if (mcClassLoader == null && cand.getClassLoader() != null) {
                            mcClassLoader = cand.getClassLoader();
                        }
                        McLog.info("Reflect: 通过单例+字段特征定位到混淆 Minecraft 类: " + cn);
                        return cand;
                    }
                }
            } catch (Throwable ignored) {}
        }
        throw new RuntimeException("找不到 Minecraft 主类");
    }

    /** 获取当前运行中的 Minecraft 单例实例（兼容 1.8.9–26.2 全环境）。 */
    public static Object findMinecraftInstance() {
        Class<?> mcClass = findMinecraftClass();
        Object mc = findStaticFieldByType(mcClass, mcClass);
        if (mc != null) return mc;
        mc = invokeStaticByReturnType(mcClass, mcClass);
        if (mc != null) return mc;
        return invokeStaticAny(mcClass, new String[]{
            "getInstance", "getMinecraft", "m_91296_", "func_71410_x", "method_1551"
        });
    }

    /** 返回当前 JVM 所有已加载类（无 Instrumentation 时返回空数组）。 */
    public static Class<?>[] loadedClasses() {
        if (instrumentation == null) return new Class<?>[0];
        try {
            return instrumentation.getAllLoadedClasses();
        } catch (Throwable ignored) {
            return new Class<?>[0];
        }
    }

    /**
     * 查找类，按多策略依次尝试。找到后缓存。
     * @throws RuntimeException 如果所有策略都失败
     */
    public static Class<?> cls(String name) {
        Class<?> cached = cache.get(name);
        if (cached != null) return cached;

        // 策略 1: 系统 ClassLoader（JDK 类、Forge bootstrap 类）
        try {
            Class<?> c = Class.forName(name);
            cache.put(name, c);
            return c;
        } catch (ClassNotFoundException ignored) {}

        // 策略 2: MC ClassLoader（如果已找到）
        if (mcClassLoader != null) {
            try {
                Class<?> c = Class.forName(name, true, mcClassLoader);
                cache.put(name, c);
                return c;
            } catch (ClassNotFoundException ignored) {}
        }

        // 策略 3: 线程上下文 ClassLoader
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && ctx != ClassLoader.getSystemClassLoader()) {
            try {
                Class<?> c = Class.forName(name, true, ctx);
                cache.put(name, c);
                return c;
            } catch (ClassNotFoundException ignored) {}
        }

        // 策略 4: 扫描所有已加载类（终极后备）
        if (instrumentation != null) {
            try {
                for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                    if (c.getName().equals(name)) {
                        // 缓存 ClassLoader 供后续使用
                        if (mcClassLoader == null && c.getClassLoader() != null) {
                            mcClassLoader = c.getClassLoader();
                            McLog.info("Reflect: 通过 getAllLoadedClasses 找到 MC ClassLoader: " + mcClassLoader);
                        }
                        cache.put(name, c);
                        return c;
                    }
                }
            } catch (Throwable ignored) {}
        }

        throw new RuntimeException("找不到类: " + name + "（已尝试系统/MC/上下文/Instrumentation 四种 ClassLoader）");
    }

    /**
     * 同 cls() 但失败时返回 null 而非抛异常。
     * 用于探测类是否存在（如版本检测）。
     */
    public static Class<?> tryCls(String name) {
        try { return cls(name); } catch (Throwable t) { return null; }
    }

    /**
     * 重新扫描已加载类，更新 MC ClassLoader。
     * 当初始扫描时 MC 尚未加载，可在稍后调用此方法重试。
     */
    public static void rescanClassLoader() {
        if (mcClassLoader == null) {
            findMcClassLoader();
        }
    }

    public static Object invokeStatic(Class<?> c, String method, Object... args) {
        try {
            // 搜索类自身及父类
            Class<?> cur = c;
            while (cur != null) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(null, args);
                    }
                }
                cur = cur.getSuperclass();
            }
            throw new NoSuchMethodException(c.getName() + "." + method + "/" + args.length);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * 尝试多个方法名调用静态方法，返回第一个成功的结果。
     * 用于跨版本兼容（如 getInstance vs getMinecraft）。
     */
    public static Object invokeStaticAny(Class<?> c, String[] methodNames, Object... args) {
        RuntimeException last = null;
        for (String name : methodNames) {
            try {
                return invokeStatic(c, name, args);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last != null ? last : new RuntimeException("所有方法名都失败");
    }

    /**
     * 诊断：列出类的所有声明方法名（含父类），用于排查方法名不匹配问题。
     */
    public static String debugMethods(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                sb.append(cur.getSimpleName()).append(".")
                  .append(m.getName()).append("(")
                  .append(m.getParameterCount()).append(") ");
            }
            cur = cur.getSuperclass();
        }
        return sb.toString();
    }

    /**
     * 诊断：列出类的所有声明字段名（含父类）。
     */
    public static String debugFields(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        Class<?> cur = c;
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                sb.append(cur.getSimpleName()).append(".")
                  .append(f.getName()).append(" ");
            }
            cur = cur.getSuperclass();
        }
        return sb.toString();
    }

    public static Object invoke(Object obj, String method, Object... args) {
        try {
            Class<?> c = obj.getClass();
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                }
            }
            // 向上查找
            Class<?> sup = c.getSuperclass();
            while (sup != null) {
                for (Method m : sup.getDeclaredMethods()) {
                    if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(obj, args);
                    }
                }
                sup = sup.getSuperclass();
            }
            throw new NoSuchMethodException(c.getName() + "." + method);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static Object field(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ignored) {}
                c = c.getSuperclass();
            }
            throw new NoSuchFieldException(obj.getClass().getName() + "." + name);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static Object field(Object obj, String name, Object value) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return obj;
                } catch (NoSuchFieldException ignored) {}
                c = c.getSuperclass();
            }
            throw new NoSuchFieldException(obj.getClass().getName() + "." + name);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 在类层级中按名查找字段（返回 Field，供多次高效访问）。 */
    public static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        throw new RuntimeException("字段不存在: " + name);
    }

    public static Method findMethod(Class<?> c, String name, int argc) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new RuntimeException("方法不存在: " + name + "/" + argc);
    }

    // ======================== 类型匹配查找（不依赖名称映射） ========================

    /**
     * 在类的静态字段中查找指定类型的字段，返回其值。
     * 用于获取单例实例（如 Minecraft.instance）。
     * @param owner 要搜索的类
     * @param targetType 期望的字段类型
     * @return 字段值，或 null
     */
    public static Object findStaticFieldByType(Class<?> owner, Class<?> targetType) {
        Class<?> cur = owner;
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                try {
                    int mod = f.getModifiers();
                    if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                    if (targetType.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val != null) {
                            McLog.info("Reflect: 通过类型匹配找到静态字段 " + cur.getSimpleName() +
                                "." + f.getName() + " (type=" + f.getType().getSimpleName() + ")");
                            return val;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 在对象中查找指定类型的实例字段，返回其值。
     * @param obj 要搜索的对象
     * @param targetType 期望的字段类型
     * @param skipTypes 要跳过的类型（避免匹配到自身或无关字段）
     * @return 字段值，或 null
     */
    public static Object findFieldByType(Object obj, Class<?> targetType, Class<?>... skipTypes) {
        Class<?> cur = obj.getClass();
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                try {
                    int mod = f.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod)) continue;
                    Class<?> ft = f.getType();
                    if (!targetType.isAssignableFrom(ft)) continue;
                    // 跳过指定类型
                    boolean skip = false;
                    for (Class<?> st : skipTypes) {
                        if (st.isAssignableFrom(ft)) { skip = true; break; }
                    }
                    if (skip) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null) {
                        McLog.info("Reflect: 通过类型匹配找到字段 " + cur.getSimpleName() +
                            "." + f.getName() + " (type=" + ft.getSimpleName() + ")");
                        return val;
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 在类中查找返回指定类型的静态方法（0参数），并调用它。
     * @param owner 要搜索的类
     * @param returnType 期望的返回类型
     * @return 方法返回值，或 null
     */
    public static Object invokeStaticByReturnType(Class<?> owner, Class<?> returnType) {
        Class<?> cur = owner;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                try {
                    int mod = m.getModifiers();
                    if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                    if (m.getParameterCount() != 0) continue;
                    if (returnType.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        Object val = m.invoke(null);
                        if (val != null) {
                            McLog.info("Reflect: 通过返回类型匹配找到静态方法 " + cur.getSimpleName() +
                                "." + m.getName() + " (returns=" + m.getReturnType().getSimpleName() + ")");
                            return val;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 在对象中查找返回指定类型的方法（0参数），并调用它。
     * @param obj 要搜索的对象
     * @param returnType 期望的返回类型
     * @return 方法返回值，或 null
     */
    public static Object invokeByReturnType(Object obj, Class<?> returnType) {
        Class<?> cur = obj.getClass();
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                try {
                    int mod = m.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod)) continue;
                    if (m.getParameterCount() != 0) continue;
                    if (returnType.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        Object val = m.invoke(obj);
                        if (val != null) {
                            McLog.info("Reflect: 通过返回类型匹配找到方法 " + cur.getSimpleName() +
                                "." + m.getName() + " (returns=" + m.getReturnType().getSimpleName() + ")");
                            return val;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 尝试多个字段名获取字段值，第一个成功的返回。
     */
    public static Object fieldAny(Object obj, String... names) {
        for (String name : names) {
            try { return field(obj, name); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 尝试多个方法名调用，第一个成功的返回。
     */
    public static Object invokeAny(Object obj, String[] methodNames, Object... args) {
        for (String name : methodNames) {
            try { return invoke(obj, name, args); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 同 invokeAny，但只调用返回类型匹配的方法（void 方法一律拒绝）。
     *
     * 必须用于读取值的场景：invokeAny 只按"名字 + 参数个数"匹配，跨版本 SRG 名列表里
     * 一旦某个名字在当前版本指向了别的方法，就会被盲调。这不是读不到值的问题，而是
     * 会执行带副作用的方法 —— 例如 1.20.1 的 m_146870_ 是 Entity.discard()（把实体
     * 从世界移除），拿它当 getYRot() 试探会直接删掉玩家实体。
     *
     * @param returnType 期望的返回类型；基本类型请传包装类（如 Double.class 匹配 double）
     */
    public static Object invokeAnyTyped(Object obj, Class<?> returnType, String[] methodNames, Object... args) {
        for (String name : methodNames) {
            Class<?> cur = obj.getClass();
            while (cur != null) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (m.getParameterCount() != args.length) continue;
                    Class<?> rt = box(m.getReturnType());
                    if (rt == void.class || !returnType.isAssignableFrom(rt)) {
                        McLog.warn("Reflect: 跳过 " + cur.getSimpleName() + "." + name +
                            " —— 返回类型 " + m.getReturnType().getSimpleName() +
                            " 不匹配期望 " + returnType.getSimpleName() + "（疑为跨版本 SRG 名冲突）");
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                        return m.invoke(obj, args);
                    } catch (Throwable ignored) {}
                }
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 读取 Entity.getId()：声明类是 Entity 本身、无参、返回 int 的方法。
     *
     * 不能用 invokeAnyTyped 试名字 —— Entity 上有多个无参返回 int 的方法
     * （getId / getAirSupply / getTicksFrozen …），名字猜错就拿到别的值。
     * 实测误配会得到 5473855 这种离谱的实体 ID，导致 B 认不出自己是哪个实体。
     *
     * 判据：Entity 类里第一个「无参 + 返回 int + 名字匹配已知 getId 候选」的方法；
     * 名字全不匹配时退回按声明顺序找 —— getId 在 Entity 源码里定义得很早。
     */
    public static Integer readEntityId(Object entity) {
        return readEntityId(entity, null);
    }

    public static Integer readEntityId(Object entity, Object world) {
        if (entity == null) return null;
        Class<?> entityClass = tryCls("net.minecraft.world.entity.Entity");
        if (entityClass == null) entityClass = tryCls("net.minecraft.entity.Entity");
        if (entityClass == null) entityClass = tryCls("net.minecraft.class_1297");
        if (entityClass == null || !entityClass.isInstance(entity)) {
            // 原版完全混淆环境：沿着继承链向上找 Object 之前的最顶层基类（即 Entity）
            Class<?> cur = entity.getClass();
            while (cur.getSuperclass() != null && cur.getSuperclass() != Object.class) {
                cur = cur.getSuperclass();
            }
            entityClass = cur;
        }
        String[] knownMethods = {"getId", "m_19879_", "getEntityId", "func_145782_y", "method_5628"};
        List<Method> candidates = new ArrayList<>();
        for (Method m : entityClass.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != int.class) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            m.setAccessible(true);
            for (String km : knownMethods) {
                if (m.getName().equals(km)) {
                    try { return (Integer) m.invoke(entity); } catch (Throwable ignored) {}
                }
            }
            candidates.add(m);
        }
        String[] knownFields = {"id", "f_19843_", "entityId", "field_145783_c", "field_5988"};
        for (String kf : knownFields) {
            try {
                Field f = entityClass.getDeclaredField(kf);
                if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return (Integer) f.get(entity);
                }
            } catch (Throwable ignored) {}
        }
        // 若提供了 world，用 world.getEntity(int) == entity 反向精确锁定混淆的 getId 方法
        if (world != null) {
            Method lookup = findMethodByReturnAndParams(world.getClass(), entityClass, int.class);
            if (lookup != null) {
                for (Method m : candidates) {
                    try {
                        Integer candId = (Integer) m.invoke(entity);
                        if (candId != null && lookup.invoke(world, candId) == entity) {
                            return candId;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        // 最后回退：Entity 源码里第一个非静态 int 字段即为 entityId（在 Entity.<init> 中紧接静态计数器声明）
        for (Field f : entityClass.getDeclaredFields()) {
            if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    return (Integer) f.get(entity);
                } catch (Throwable ignored) {}
            }
        }
        McLog.warn("readEntityId: 未找到实体 ID，候选无参 int 方法 " + candidates.size() + " 个");
        return null;
    }

    /** 基本类型 → 包装类，便于用 isAssignableFrom 统一比较。 */
    private static Class<?> box(Class<?> c) {
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return c;
    }

    /**
     * 查找实例方法，按参数类型匹配（不依赖方法名）。
     * 参数类型使用 isAssignableFrom 检查，支持接口/父类匹配。
     * @param owner 要搜索的类
     * @param argTypes 实际参数类型
     * @return 匹配的 Method，或 null
     */
    public static Method findMethodByParamTypes(Class<?> owner, Class<?>... argTypes) {
        Class<?> cur = owner;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != argTypes.length) continue;
                    boolean match = true;
                    for (int i = 0; i < argTypes.length; i++) {
                        if (!m.getParameterTypes()[i].isAssignableFrom(argTypes[i])) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        m.setAccessible(true);
                        McLog.info("Reflect: 通过参数类型匹配找到方法 " + cur.getSimpleName() +
                            "." + m.getName() + "(" + argTypes.length + " params)");
                        return m;
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 查找实例方法，按返回类型 + 参数类型双重匹配（完全不依赖方法名）。
     *
     * 混淆环境下方法名是 SRG 的，猜名字既不可靠又危险（猜错会调到别的方法）。
     * 返回类型 + 参数签名一起匹配基本能唯一定位，例如
     * Level.getChunk(int,int) → LevelChunk。
     *
     * @param returnType 期望返回类型；基本类型直接传 int.class 等
     */
    public static Method findMethodByReturnAndParams(Class<?> owner, Class<?> returnType, Class<?>... paramTypes) {
        Class<?> cur = owner;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != paramTypes.length) continue;
                    if (!returnType.isAssignableFrom(m.getReturnType())) continue;
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!m.getParameterTypes()[i].isAssignableFrom(paramTypes[i])) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        m.setAccessible(true);
                        McLog.info("Reflect: 类型匹配到方法 " + cur.getSimpleName() + "." + m.getName() +
                            " → " + m.getReturnType().getSimpleName());
                        return m;
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 查找静态方法，按返回类型和参数类型匹配（不依赖方法名）。
     * @param owner 要搜索的类
     * @param returnType 期望的返回类型
     * @param paramTypes 参数类型
     * @return 匹配的 Method，或 null
     */
    public static Method findStaticMethodByParamType(Class<?> owner, Class<?> returnType, Class<?>... paramTypes) {
        Class<?> cur = owner;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != paramTypes.length) continue;
                    if (!returnType.isAssignableFrom(m.getReturnType())) continue;
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!m.getParameterTypes()[i].isAssignableFrom(paramTypes[i])) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        m.setAccessible(true);
                        McLog.info("Reflect: 通过类型匹配找到静态方法 " + cur.getSimpleName() +
                            "." + m.getName() + " (returns=" + m.getReturnType().getSimpleName() + ")");
                        return m;
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
