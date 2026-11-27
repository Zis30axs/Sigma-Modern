package com.mcrelay.agent.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 游戏内聊天提示工具：通过反射向 MC 客户端发送系统聊天消息。
 *
 * v2 完全重写：使用类型匹配替代名称匹配，解决 Forge SRG 映射名问题。
 *   - MC 实例: findStaticFieldByType / invokeStaticByReturnType
 *   - Component: findStaticMethodByParamType (1 String param → Component)
 *   - player/gui: findFieldByType
 *   - getChat(): invokeByReturnType
 *   - displayClientMessage/addMessage: findMethodByParamTypes
 */
public final class ChatHelper {

    private static Boolean initialized = null;
    private static Class<?> mcClass;
    private static Class<?> componentClass;
    private static Method literalMethod;       // Component.literal(String)
    private static Constructor<?> textCtor;    // 1.12.2 fallback
    private static boolean quietMcWarn = false;

    private ChatHelper() {}

    private static boolean init() {
        if (initialized != null) return initialized;
        try {
            mcClass = Reflect.findMinecraftClass();

            // === Component 创建 ===
            // 策略 1: Component.literal(String) — 1.19+ (Mojmap/Forge & Fabric class_2561)
            for (String compName : new String[]{"net.minecraft.network.chat.Component", "net.minecraft.class_2561"}) {
                try {
                    Class<?> cc = Reflect.tryCls(compName);
                    if (cc == null) continue;
                    for (String name : new String[]{"literal", "m_237113_", "method_43470"}) {
                        literalMethod = findMethod(cc, name, 1);
                        if (literalMethod != null) {
                            componentClass = cc;
                            McLog.info("ChatHelper: Component 创建方法 (by name): " + name);
                            break;
                        }
                    }
                    if (literalMethod == null) {
                        literalMethod = Reflect.findStaticMethodByParamType(cc, cc, String.class);
                        if (literalMethod != null) {
                            componentClass = cc;
                            McLog.info("ChatHelper: Component 创建方法 (by type): " + literalMethod.getName());
                        }
                    }
                    if (literalMethod != null) break;
                } catch (Throwable ignored) {}
            }

            // 策略 2: 1.9-1.12.2 TextComponentString / 1.8.9 ChatComponentText / 1.14-1.18.2 TextComponent / Fabric class_2585
            if (literalMethod == null) {
                for (String tcName : new String[]{
                        "net.minecraft.util.text.TextComponentString",
                        "net.minecraft.util.ChatComponentText",
                        "net.minecraft.network.chat.TextComponent",
                        "net.minecraft.class_2585"}) {
                    try {
                        Class<?> tcClass = Reflect.tryCls(tcName);
                        if (tcClass == null) continue;
                        textCtor = tcClass.getConstructor(String.class);
                        componentClass = tcClass;
                        McLog.info("ChatHelper: 使用 " + tcClass.getSimpleName() + "(String) 构造器");
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            initialized = (literalMethod != null || textCtor != null);
            if (initialized) {
                McLog.info("ChatHelper: 初始化成功 (compClass=" +
                    (componentClass != null ? componentClass.getName() : "null") + ")");
            } else {
                McLog.warn("ChatHelper: 无法创建 Component，聊天消息将仅输出到日志");
            }
            return initialized;
        } catch (Throwable t) {
            initialized = false;
            McLog.warn("ChatHelper: 初始化失败: " + t.getMessage());
            return false;
        }
    }

    /** 获取 Minecraft 实例（类型匹配优先） */
    private static Object getMcInstance() {
        return Reflect.findMinecraftInstance();
    }

    /** 创建文本组件 */
    private static Object createComponent(String message) {
        if (literalMethod != null) {
            try { return literalMethod.invoke(null, message); } catch (Throwable ignored) {}
        }
        if (textCtor != null) {
            try { return textCtor.newInstance(message); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 发送游戏内聊天消息。如果反射失败则静默忽略（降级为仅日志）。
     */
    public static void send(String message) {
        McLog.info("[Chat] " + message);
        if (!init()) return;
        try {
            Object mc = getMcInstance();
            if (mc == null) {
                if (!quietMcWarn) {
                    McLog.warn("ChatHelper: 无法获取 Minecraft 实例");
                    quietMcWarn = true;
                }
                return;
            }

            Object component = createComponent(message);
            if (component == null) return;

            // === 路径 1: player.displayClientMessage(Component, boolean) ===
            try {
                Object player = findPlayer(mc);
                if (player != null) {
                    // 按参数类型匹配: (Component, boolean)
                    Method dcm = Reflect.findMethodByParamTypes(
                        player.getClass(), component.getClass(), boolean.class);
                    if (dcm != null) {
                        dcm.invoke(player, component, false);
                        return;
                    }
                    // 按名称尝试 (Mojang + SRG)
                    for (String name : new String[]{"displayClientMessage", "m_241121_"}) {
                        Method m = findMethod(player.getClass(), name, 2);
                        if (m != null) {
                            m.invoke(player, component, false);
                            return;
                        }
                    }
                    // 1.12.2: player.sendMessage(Component)
                    for (String name : new String[]{"sendMessage", "func_145747_a"}) {
                        Method m = findMethod(player.getClass(), name, 1);
                        if (m != null) {
                            m.invoke(player, component);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // === 路径 2: gui.getChat().addMessage(Component) ===
            try {
                Object gui = findGui(mc);
                if (gui == null) return;

                Object chat = findChat(gui);
                if (chat == null) return;

                // 按参数类型匹配: (Component)
                Method addMsg = Reflect.findMethodByParamTypes(chat.getClass(), component.getClass());
                if (addMsg != null) {
                    addMsg.invoke(chat, component);
                    return;
                }
                // 按名称尝试 (Mojang + SRG)
                for (String name : new String[]{"addMessage", "m_9377_", "printChatMessage"}) {
                    Method m = findMethod(chat.getClass(), name, 1);
                    if (m != null) {
                        m.invoke(chat, component);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** 通过类型匹配查找 player 字段 */
    private static Object findPlayer(Object mc) {
        Class<?> playerClass = Reflect.tryCls("net.minecraft.client.player.LocalPlayer");
        if (playerClass == null) {
            playerClass = Reflect.tryCls("net.minecraft.client.entity.EntityPlayerSP");
        }
        if (playerClass != null) {
            return Reflect.findFieldByType(mc, playerClass);
        }
        return null;
    }

    /** 通过类型匹配查找 gui 字段 */
    private static Object findGui(Object mc) {
        Class<?> guiClass = Reflect.tryCls("net.minecraft.client.gui.Gui");
        if (guiClass == null) {
            guiClass = Reflect.tryCls("net.minecraft.client.gui.GuiIngame");
        }
        if (guiClass != null) {
            return Reflect.findFieldByType(mc, guiClass);
        }
        return null;
    }

    /** 通过类型匹配查找 chat 对象 (gui.getChat()) */
    private static Object findChat(Object gui) {
        Class<?> chatClass = Reflect.tryCls("net.minecraft.client.gui.components.ChatComponent");
        if (chatClass == null) {
            chatClass = Reflect.tryCls("net.minecraft.client.gui.GuiNewChat");
        }
        if (chatClass != null) {
            return Reflect.invokeByReturnType(gui, chatClass);
        }
        return null;
    }

    /**
     * 重置初始化状态，允许重新尝试。
     */
    public static void reset() {
        initialized = null;
        mcClass = null;
        componentClass = null;
        literalMethod = null;
        textCtor = null;
        quietMcWarn = false;
    }

    private static Method findMethod(Class<?> cls, String name, int argc) {
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}
