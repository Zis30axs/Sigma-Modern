package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.Compass;
import com.mentalfrostbyte.jello.module.impl.gui.Coords;
import com.mentalfrostbyte.jello.module.impl.gui.InfoHud;
import com.mentalfrostbyte.jello.module.impl.gui.InfoHud.Coordinates;
import com.mentalfrostbyte.jello.module.impl.gui.KeyStrokes;
import com.mentalfrostbyte.jello.module.impl.gui.MiniMap;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Jello's small HUD modules, each as the old client drew it in framebuffer pixels: {@link KeyStrokes} on the left under
 * the TabGUI, {@link Coords} below it, the {@link Compass} along the top and the {@link InfoHud} strip in the bottom-left
 * corner. What each shows is on the module; the modules themselves hold nothing to draw with.
 */
final class JelloWidgets {
    private static final int TEXT = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;

    private static final Animation COORDS_ANIMATION = new Animation(1500, 1500, Animation.Direction.BACKWARDS);
    private static double lastX, lastY, lastZ;

    private static final List<Ripple> RIPPLES = new ArrayList<>();
    private static final boolean[] HELD = new boolean[Key.values().length];

    private JelloWidgets() {}

    static void reset() {
        JelloMiniMap.reset();
        RIPPLES.clear();
        java.util.Arrays.fill(HELD, false);
    }

    /**
     * Draws the widgets that stack down the left side, starting at {@code top}, and the ones that keep to a corner. Only
     * the bottom-left strip and the compass show with F3's text up, as before.
     */
    static void render(final LegacyCanvas c, final Minecraft mc, final int top, final boolean debug) {
        LocalPlayer player = mc.player;
        int y = top;
        if (!debug) {
            if (Modules.enabled(MiniMap.class) != null) {
                JelloMiniMap.render(c, mc, 10, y);
                y += JelloMiniMap.SIZE + 10;
            } else {
                JelloMiniMap.reset();
            }

            if (Modules.enabled(KeyStrokes.class) != null) {
                keyStrokes(c, mc.options, y);
                y += 160;
            } else {
                reset();
            }

            if (Modules.enabled(Coords.class) != null) {
                coords(c, player, y);
            }
        }

        if (Modules.enabled(Compass.class) != null) {
            compass(c, player, debug ? 60 : 0);
        }

        InfoHud info = Modules.enabled(InfoHud.class);
        if (info != null) {
            info(c, info, player);
        }
    }

    // ------------------------------------------------------------------ KeyStrokes

    private enum Key {
        LEFT(0.0F, 1.0F, 48, o -> o.keyLeft),
        RIGHT(2.0F, 1.0F, 48, o -> o.keyRight),
        FORWARD(1.0F, 0.0F, 48, o -> o.keyUp),
        BACK(1.0F, 1.0F, 48, o -> o.keyDown),
        ATTACK(0.0F, 2.0F, 74, o -> o.keyAttack),
        USE(1.02F, 2.0F, 74, o -> o.keyUse);

        private static final int HEIGHT = 48;
        private static final int PADDING = 3;

        private final float column;
        private final float row;
        private final int width;
        private final Function<Options, KeyMapping> mapping;

        Key(final float column, final float row, final int width, final Function<Options, KeyMapping> mapping) {
            this.column = column;
            this.row = row;
            this.width = width;
            this.mapping = mapping;
        }

        int x() {
            return (int) (this.column * (this.width + PADDING));
        }

        int y() {
            return (int) (this.row * (HEIGHT + PADDING));
        }

        String label(final Options options) {
            return switch (this) {
                case ATTACK -> "L";
                case USE -> "R";
                default -> this.mapping.apply(options).getTranslatedKeyMessage().getString();
            };
        }
    }

    /** A pale circle spreading out of a key that has just been let go. */
    private record Ripple(Key key, Animation animation) {}

    private static void keyStrokes(final LegacyCanvas c, final Options options, final int top) {
        final int left = 10;
        for (Key key : Key.values()) {
            boolean down = key.mapping.apply(options).isDown();
            if (HELD[key.ordinal()] && !down) {
                RIPPLES.add(new Ripple(key, new Animation(300, 300)));
            }
            HELD[key.ordinal()] = down;

            int x = left + key.x();
            int y = top + key.y();
            c.fill(x, y, x + key.width, y + Key.HEIGHT, LegacyCanvas.alpha(down ? TEXT : INK, 0.5F));
            c.outerGlow(x, y, key.width, Key.HEIGHT, 10.0F, 0.75F);
            String label = key.label(options);
            c.text(Face.JELLO_LIGHT, 18.0F, label, x + (key.width - c.textWidth(Face.JELLO_LIGHT, 18.0F, label)) / 2.0F, y + 12, TEXT);
        }

        for (Iterator<Ripple> it = RIPPLES.iterator(); it.hasNext(); ) {
            Ripple ripple = it.next();
            Key key = ripple.key();
            float progress = ripple.animation().calcPercent();
            int x = left + key.x();
            int y = top + key.y();
            float alpha = (1.0F - progress * (0.5F + progress * 0.5F)) * 0.8F;
            c.scissor(x, y, x + key.width, y + Key.HEIGHT);
            try {
                c.disc(x + key.width / 2.0F, y + Key.HEIGHT / 2.0F, (key.width - 4) * progress + 4.0F, LegacyCanvas.alpha(0xFFA9A9A9, alpha));
            } finally {
                c.unscissor();
            }
            if (progress == 1.0F) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ Coords

    private static void coords(final LegacyCanvas c, final LocalPlayer player, final int y) {
        boolean moved = player.getX() != lastX || player.getY() != lastY || player.getZ() != lastZ;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        if (moved || !player.onGround() || player.isShiftKeyDown()) {
            COORDS_ANIMATION.changeDirection(Animation.Direction.FORWARDS);
        } else if (COORDS_ANIMATION.calcPercent() == 1.0F) {
            COORDS_ANIMATION.changeDirection(Animation.Direction.BACKWARDS);
        }

        float progress = COORDS_ANIMATION.calcPercent();
        float strength = Math.min(1.0F, 0.6F + progress * 2.0F);
        String text = String.format("%.0f %.0f %.0f", player.getX(), player.getY(), player.getZ());
        float textX = 85.0F;
        float fit = Math.min(1.0F, 150.0F / c.textWidth(Face.JELLO_LIGHT, 18.0F, text));
        if (COORDS_ANIMATION.getDirection() != Animation.Direction.FORWARDS) {
            fit *= 0.9F + Easing.easeInQuad(Math.min(1.0F, progress * 8.0F), 0.0F, 1.0F, 1.0F) * 0.1F;
        } else {
            fit *= 0.9F + Easing.easeOutBack(Math.min(1.0F, progress * 7.0F), 0.0F, 1.0F, 1.0F) * 0.1F;
        }

        c.push();
        try {
            c.scaleAbout(fit, fit, textX, y + 10);
            // The old client set a blurred copy of the text under it; the same darkening, in four nudged copies.
            int shade = LegacyCanvas.alpha(0xFF000000, 0.14F * strength);
            for (int[] step : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                c.text(Face.JELLO_LIGHT, 18.0F, text, textX + step[0], y + step[1], shade);
            }
            c.text(Face.JELLO_LIGHT, 18.0F, text, textX, y, LegacyCanvas.alpha(TEXT, 0.8F * strength));
        } finally {
            c.pop();
        }
    }

    // ------------------------------------------------------------------ Compass

    private static void compass(final LegacyCanvas c, final LocalPlayer player, final int shift) {
        final int half = 5;
        final int spacing = 60;
        float yaw = normalize(player.getYRot());
        List<Integer> headings = headings((int) yaw, half);
        int centre = headings.get(half);
        if (centre == 0 && yaw > 345.0F) {
            centre = 360;
        }

        float within = 7.0F + yaw - centre;
        double offset = within / 15.0F * spacing;
        c.image(LegacyTexture.JELLO_SHADOW, c.width() / 2.0F - half * spacing * 1.5F, -40.0F, half * spacing * 2 * 1.5F, 220 + shift,
                LegacyCanvas.alpha(TEXT, 0.25F));
        int index = 0;
        for (int heading : headings) {
            index++;
            double rise = Math.max(0.0, Math.min((index * spacing - offset) / (spacing * half), 1.0));
            double fall = Math.max(0.0, Math.min(2.25 - (index * spacing - offset) / (spacing * half), 1.0));
            float alpha = (float) Math.min(rise, fall);
            heading(c, c.width() / 2 + index * spacing - (int) offset - (half + 1) * spacing - 2, 30 + shift, spacing, heading, alpha * 0.8F);
        }
    }

    private static void heading(final LegacyCanvas c, final int x, final int y, final int width, final int degrees, final float alpha) {
        String label = switch (degrees) {
            case 0 -> "S";
            case 90 -> "W";
            case 180 -> "N";
            case 270 -> "E";
            case 45 -> "SW";
            case 135 -> "NW";
            case 225 -> "NE";
            case 315 -> "SE";
            default -> Integer.toString(degrees);
        };
        int color = LegacyCanvas.alpha(TEXT, alpha);
        if (Character.isDigit(label.charAt(0))) {
            // A tick and the bearing under it.
            c.fill(x + width / 2 - 1, y + 28, x + width / 2 + 1, y + 38, LegacyCanvas.alpha(TEXT, alpha * 0.5F));
            c.text(Face.JELLO_LIGHT, 18.0F, label, x + (width - c.textWidth(Face.JELLO_LIGHT, 18.0F, label)) / 2.0F, y + 40, color);
        } else if (label.length() == 1) {
            c.text(Face.JELLO_MEDIUM, 40.0F, label, x + (width - c.textWidth(Face.JELLO_MEDIUM, 40.0F, label)) / 2.0F, y + 10, color);
        } else {
            c.text(Face.JELLO_LIGHT, 25.0F, label, x + (width - c.textWidth(Face.JELLO_LIGHT, 25.0F, label)) / 2.0F, y + 20, color);
        }
    }

    /** The headings shown: {@code half} steps of 15 degrees each side of the one nearest the way you face. */
    static List<Integer> headings(final int yaw, final int half) {
        int nearest = (yaw + 7) / 15 * 15;
        List<Integer> shown = new ArrayList<>();
        for (int heading = nearest - 15 * half; heading < nearest; heading += 15) {
            shown.add((int) normalize(heading));
        }
        for (int heading = nearest; heading < nearest + 15 * (half + 1); heading += 15) {
            shown.add((int) normalize(heading));
        }

        return shown;
    }

    /** {@code degrees} brought into 0 (inclusive) to 360. */
    static float normalize(final float degrees) {
        float wrapped = degrees % 360.0F;
        // + 0 turns the -0 that a whole turn back leaves into a plain 0.
        return wrapped < 0.0F ? wrapped + 360.0F : wrapped + 0.0F;
    }

    // ------------------------------------------------------------------ InfoHUD

    private static void info(final LegacyCanvas c, final InfoHud info, final LocalPlayer player) {
        int height = c.height();
        int x = 14;
        if (info.showsPlayer()) {
            x += player(c, player, 0, height - 22, 114);
        }
        if (info.showsArmor()) {
            x += armor(c, player, x, height - 14) + 10;
        }
        if (info.getCoordinates() != Coordinates.NONE) {
            String text = info.getCoordinates() == Coordinates.PRECISE
                    ? String.format("%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ())
                    : String.format("%d %d %d", Math.round(player.getX()), Math.round(player.getY()), Math.round(player.getZ()));
            c.text(Face.JELLO_MEDIUM, 20.0F, text, x, height - 42, LegacyCanvas.alpha(TEXT, 0.8F));
        }
    }

    /** The character, {@code size} pixels tall, standing on {@code y} with its centre at {@code x + size / 2}. */
    private static int player(final LegacyCanvas c, final LocalPlayer player, final int x, final int y, final int size) {
        GuiGraphicsExtractor g = c.graphics();
        int scale = c.guiScale();
        // The model is placed in GUI units and takes no part in the canvas's scaling.
        int left = x / scale;
        int right = (x + size) / scale;
        int bottom = y / scale;
        int top = (y - size) / scale;
        float centreX = (left + right) / 2.0F;
        float centreY = (top + bottom) / 2.0F;
        // The inventory model follows a point; putting the point straight ahead at the height the player is looking
        // faces it forward with its head tilted as yours is.
        float pointerY = centreY + 40.0F * (float) Math.tan(Math.toRadians(player.getXRot() / 20.0F));
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, left, top, right, bottom, Math.round(size / 2.0F / scale), 0.0625F,
                centreX, pointerY, player);
        return size - 24;
    }

    /** The worn armor, boots at the bottom, each 32 pixels with a bar for its wear; returns how wide the column is. */
    private static int armor(final LegacyCanvas c, final LocalPlayer player, final int x, final int bottom) {
        int worn = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            worn++;
            int y = bottom - 32 * worn;
            c.push();
            try {
                c.translate(x, y);
                c.scale(2.0F, 2.0F);
                c.graphics().item(stack, 0, 0);
            } finally {
                c.pop();
            }

            if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
                float durability = 1.0F - (float) stack.getDamageValue() / stack.getMaxDamage();
                c.fill(x + 2, y + 28, x + 30, y + 33, LegacyCanvas.alpha(INK, 0.5F));
                c.fill(x + 2, y + 28, x + 2 + Math.round(28.0F * durability), y + 31,
                        LegacyCanvas.alpha(durability > 0.2F ? 0xFF6DCE27 : 0xFFFF5555, 0.9F));
            }
        }

        return worn != 0 ? 32 : -7;
    }
}
