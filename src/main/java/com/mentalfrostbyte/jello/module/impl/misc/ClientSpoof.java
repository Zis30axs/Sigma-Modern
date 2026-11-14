package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;

/**
 * Fakes the client brand sent to the server on login.
 *
 * <p>Ported from the author's earlier SigmaClient (juzibujiji/SigmaClient,
 * {@code module/impl/misc/ClientSpoof.java} and the {@code ClientBrandRetriever} hook): the same
 * modes and brand strings, on this codebase's module system. The brand is read from here by
 * {@code ClientBrandRetriever.getClientModName()} at login time, so the module must be enabled
 * before joining for the spoof to apply.</p>
 */
public final class ClientSpoof extends Module {

    public enum Mode {
        VANILLA,
        GEYSER,
        LUNAR,
        CHEATBREAKER,
        CUSTOM
    }

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode",
            "Which client to pretend to be when the brand is sent on login.", Mode.LUNAR));
    private final TextSetting brandName = this.register(new TextSetting("BrandName",
            "Brand sent when Mode is Custom.", ""));

    public ClientSpoof() {
        super(ModuleCategory.MISC, "ClientSpoof", "Fakes the client brand sent to the server on login.");
    }

    public String getSpoofedBrand(final String originalBrand) {
        if (!this.isEnabled()) {
            return originalBrand;
        }

        return switch (this.mode.get()) {
            case VANILLA -> "vanilla";
            case GEYSER -> "geyser";
            case LUNAR -> "lunarclient:v2.16.8-2433";
            case CHEATBREAKER -> "CB";
            case CUSTOM -> this.brandName.get();
        };
    }
}
