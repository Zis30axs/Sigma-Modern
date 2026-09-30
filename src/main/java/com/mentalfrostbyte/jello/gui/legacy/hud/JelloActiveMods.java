package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jello's module list ({@code ActiveMods}): the names of the switched-on modules down the right edge in Helvetica
 * Neue Light, white with a soft dark glow behind them so they read over any scenery.
 *
 * <p>A module switched on grows in (0.86 of its size to full, fading up over 150 ms) and pushes the lines below it
 * down as it does; one switched off shrinks and fades away the same way and the lines close up behind it. The lines
 * are ordered widest first, measured in the 20 point face whatever size they are drawn at - the old client's
 * comparator - so the list steps in from the edge.</p>
 */
final class JelloActiveMods {
    private static final int MARGIN = 10;
    private static final Map<Module, Animation> ANIMATIONS = new IdentityHashMap<>();
    private static final Map<String, Float> NAME_WIDTHS = new HashMap<>();

    private JelloActiveMods() {}

    static void reset() {
        ANIMATIONS.clear();
    }

    /** {@code top} is where the first line starts, in framebuffer pixels. */
    static void render(final LegacyCanvas c, final ModuleArrayList list, final int top) {
        Face face = Face.JELLO_LIGHT;
        float size = list.getSize().points();
        boolean tiny = list.getSize() == ModuleArrayList.Size.TINY;
        int margin = tiny ? MARGIN - 3 : MARGIN;
        int screenWidth = c.width();
        boolean animated = list.isAnimated();
        float lineHeight = c.textHeight(face, size);

        float y = top;
        for (Module module : ordered(c, list)) {
            Animation animation = animation(module);
            animation.changeDirection(module.isEnabled() ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            float visible = 1.0F;
            float scale = 1.0F;
            if (animated) {
                visible = animation.calcPercent();
                if (visible == 0.0F) {
                    continue;
                }
                scale = 0.86F + 0.14F * visible;
            } else if (!module.isEnabled()) {
                continue;
            }

            String label = LegacyHud.label(module.getName(), list.suffixOf(module));
            float width = c.textWidth(face, size, label);
            int glow = LegacyCanvas.alpha(0xFFFEFEFE, 0.36F * visible * (float) Math.sqrt(Math.min(1.2F, width / 63.0F)));
            int color = LegacyCanvas.alpha(0xFFFFFFFF, visible * 0.95F);

            c.push();
            try {
                c.scaleAbout(scale, scale, screenWidth - margin - width / 2.0F, y + 12.0F);
                c.image(LegacyTexture.JELLO_SHADOW, screenWidth - width * 1.5F - margin - 20.0F, y - 20.0F, width * 3.0F,
                        lineHeight + 1.0F + 40.0F, glow);
                c.text(face, size, label, screenWidth - margin - width, y, color);
            } finally {
                c.pop();
            }

            y += (lineHeight + 1.0F) * Easing.easeInOutQuad(visible, 0.0F, 1.0F, 1.0F);
        }
    }

    private static Animation animation(final Module module) {
        return ANIMATIONS.computeIfAbsent(module, m -> {
            Animation created = new Animation(150, 150, m.isEnabled() ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            // A list that has just appeared shows what is on, rather than growing all of it in at once.
            created.setProgress(m.isEnabled() ? 1.0F : 0.0F);
            return created;
        });
    }

    /** The modules the list names, widest name first; a tie keeps the order the modules were registered in. */
    private static List<Module> ordered(final LegacyCanvas c, final ModuleArrayList list) {
        List<Module> modules = new ArrayList<>();
        for (Module module : Client.getInstance().getModuleManager().all()) {
            if (list.lists(module)) {
                modules.add(module);
            }
        }

        modules.sort((a, b) -> Float.compare(nameWidth(c, b), nameWidth(c, a)));
        return modules;
    }

    private static float nameWidth(final LegacyCanvas c, final Module module) {
        return NAME_WIDTHS.computeIfAbsent(module.getName(), name -> c.textWidth(Face.JELLO_LIGHT, 20.0F, name));
    }
}
