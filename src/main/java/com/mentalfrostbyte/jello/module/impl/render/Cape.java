package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.cape.CapeLibrary;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * Puts a cape on your own player: one of Sigma's painted capes, or a PNG from {@code sigma5/capes/}.
 *
 * <p>The cape is client-side only - it replaces the cape in the skin your client draws you with, so you see
 * it on yourself (third person, the inventory, the ClickGUI preview) and nobody else does. With an elytra on,
 * the elytra wears it too, the same way the game paints an official cape onto one.</p>
 *
 * <p>The choice is a cape id kept in a text setting so it saves with the rest of the config; it is hidden
 * from the generic settings list because the SigmaModern ClickGUI picks it from a panel with a preview
 * instead. A saved id whose cape is gone (a deleted file) falls back to the first cape rather than failing.</p>
 */
public class Cape extends Module {

    public static final String DEFAULT_CAPE = "sigma";

    private final TextSetting choice = this.register(new TextSetting(
            "Cape", "Which cape you wear.", DEFAULT_CAPE, 160));

    public Cape() {
        super(ModuleCategory.RENDER, "Cape", "Wear a client-side cape, painted or from your own PNG");
        this.choice.visibleWhen(() -> false);
    }

    public String selectedId() {
        return this.choice.get();
    }

    public void select(final String id) {
        this.choice.set(id);
    }

    /** The cape this module puts on, or null when there is none to put on at all. */
    public CapeLibrary.@Nullable Entry selected() {
        return CapeLibrary.get(this.choice.get());
    }

    /**
     * Called by the player renderer for every avatar it draws: when this module is on and the avatar is you,
     * swaps the chosen cape into the skin and makes sure the cape is shown.
     */
    public static void apply(final Entity entity, final AvatarRenderState state) {
        if (entity != Minecraft.getInstance().player) {
            return;
        }
        Cape cape = Modules.enabled(Cape.class);
        if (cape == null) {
            return;
        }
        CapeLibrary.Entry entry = cape.selected();
        if (entry != null) {
            state.skin = withCape(state.skin, entry);
            state.showCape = true;
        }
    }

    /** {@code skin} with {@code entry} as its cape. An elytra texture of its own, if the skin has one, stays. */
    public static PlayerSkin withCape(final PlayerSkin skin, final CapeLibrary.Entry entry) {
        return new PlayerSkin(skin.body(), entry.asset(), skin.elytra(), skin.model(), skin.secure());
    }
}
