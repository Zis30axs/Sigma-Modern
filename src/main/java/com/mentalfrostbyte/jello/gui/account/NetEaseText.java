package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.Client;
import net.minecraft.network.chat.Component;

public final class NetEaseText {
    private NetEaseText() {}

    public static Component label(String key, Object... args) {
        return Component.literal(Client.getInstance().getTranslations().get("netease." + key, args));
    }
}
