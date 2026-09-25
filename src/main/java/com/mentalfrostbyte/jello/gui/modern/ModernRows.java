package com.mentalfrostbyte.jello.gui.modern;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * SigmaModern's rendering of the vanilla world and server list rows.
 *
 * <p>The vanilla list widgets stay in charge of everything else - loading, selection, double-click and
 * Enter to join, Shift+arrow reordering, narration, pinging - and call in here from their
 * {@code extractContent} only to draw. The row keeps vanilla's click geometry: a 32×32 icon at the content
 * origin whose click joins (and, for servers, whose left quarters reorder), so the icon shows those
 * affordances on hover.</p>
 */
public final class ModernRows {
    /** {@link #server}'s answer to "what is the pointer over?", for the caller's tooltips and cursor. */
    public static final int REGION_NONE = 0, REGION_PING = 1, REGION_PLAYERS = 2, REGION_ICON_ACTION = 3;

    private static final int NAME = 0xFFF2F8FC;
    private static final int SUB = 0xFF8FB0C4;
    private static final int FAINT = 0xFF6F8FA4;
    static final int GOOD = 0xFF7FE3FF;
    static final int FAIR = 0xFFF2C46B;
    static final int BAD = 0xFFFF7A86;

    private ModernRows() {}

    public static boolean active() {
        return ModernScreens.active();
    }

    // --- worlds -----------------------------------------------------------------------------------------

    public static void world(GuiGraphicsExtractor g, LevelSummary summary, Identifier icon, boolean hasIcon,
                             int x, int y, int w, int h, boolean hovered, boolean overIcon, boolean playable, int mouseX, int mouseY) {
        if (hovered) ModernStyle.rounded(g, x - 8, y - 2, w + 12, h + 4, 7, 0x12FFFFFF);
        worldIcon(g, icon, hasIcon, x, y, 32);
        if (hovered && playable) {
            ModernStyle.rounded(g, x, y, 32, 32, 4, 0x99050F1A);
            ModernIcons.draw(g, ModernIcons.Icon.PLAY, x + 9F, y + 9F, 14F, overIcon ? 0xFFFFFFFF : 0xCCBFE8FF);
        }

        Warning warning = warning(summary);
        int textX = x + 40;
        int textW = w - 40 - (warning != null ? 16 : 4);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(summary.getLevelName(), Math.round(textW / 1.08F)), textX, y, 1.08F, NAME);
        long lastPlayed = summary.getLastPlayed();
        String when = lastPlayed == -1L ? summary.getLevelId()
            : WorldSelectionList.DATE_FORMAT.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastPlayed), ZoneId.systemDefault()));
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(when, textW), textX, y + 12.5F, 1F, SUB);
        styled(g, summary.getInfo(), textX, y + 22.5F, textW, FAINT);

        if (warning != null) {
            ModernIcons.draw(g, ModernIcons.Icon.WARNING, x + w - 12F, y + 1F, 11F, warning.color());
            if (overIcon || ModernStyle.inside(mouseX, mouseY, x + w - 14, y - 1, 15, 14)) {
                Minecraft minecraft = Minecraft.getInstance();
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (Component line : warning.lines()) lines.addAll(minecraft.font.split(line, 175));
                g.setTooltipForNextFrame(lines, mouseX, mouseY);
            }
        }
    }

    /** A world's icon, or - for a world that never saved one - a small painted mountain on night blue. */
    static void worldIcon(GuiGraphicsExtractor g, Identifier icon, boolean hasIcon, int x, int y, int size) {
        ModernStyle.rounded(g, x - 1, y - 1, size + 2, size + 2, 5, 0x3DFFFFFF);
        if (hasIcon) {
            g.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0F, 0F, size, size, 64, 64, 64, 64, ModernStyle.a(0xFFFFFFFF));
        } else {
            ModernStyle.rounded(g, x, y, size, size, 4, 0xFF15324A);
            ModernStyle.fillGradient(g, x + 3, y + 1, x + size - 3, y + size / 2, 0x3389CFF0, 0x00000000);
            float glyph = size * 0.62F;
            ModernIcons.draw(g, ModernIcons.Icon.MOUNTAIN, x + (size - glyph) / 2F, y + (size - glyph) / 2F + size * 0.04F, glyph, 0xFFA9DCF5);
        }
    }

    record Warning(int color, List<Component> lines) {}

    /** The same conditions and texts as vanilla's icon tooltips, most severe first. */
    static Warning warning(LevelSummary summary) {
        if (summary instanceof LevelSummary.SymlinkLevelSummary || summary instanceof LevelSummary.CorruptedLevelSummary) {
            return new Warning(BAD, List.of(summary.getInfo()));
        }
        if (summary.isLocked()) return new Warning(BAD, List.of(Component.translatable("selectWorld.locked")));
        if (summary.requiresManualConversion()) return new Warning(BAD, List.of(Component.translatable("selectWorld.conversion.tooltip")));
        if (!summary.isCompatible()) return new Warning(BAD, List.of(Component.translatable("selectWorld.incompatible.tooltip")));
        if (summary.shouldBackup()) {
            if (summary.isDowngrade()) {
                return new Warning(BAD, List.of(Component.translatable("selectWorld.tooltip.fromNewerVersion1"),
                    Component.translatable("selectWorld.tooltip.fromNewerVersion2")));
            }
            if (!SharedConstants.getCurrentVersion().stable()) {
                return new Warning(FAIR, List.of(Component.translatable("selectWorld.tooltip.snapshot1"),
                    Component.translatable("selectWorld.tooltip.snapshot2")));
            }
        }
        return null;
    }

    // --- servers ----------------------------------------------------------------------------------------

    /**
     * Draws an online server row from values the vanilla entry has already made ViaFabricPlus-aware (the
     * MOTD falls back to the address and the ping bar disappears when VFP disables pinging for the target
     * version), and reports which interactive region the pointer is over.
     */
    public static int server(GuiGraphicsExtractor g, ServerData data, Component motd, Identifier icon, boolean hasIcon, Component status,
                             boolean pingHidden, boolean canMoveUp, boolean canMoveDown,
                             int x, int y, int w, int h, boolean hovered, int mouseX, int mouseY) {
        if (hovered) ModernStyle.rounded(g, x - 8, y - 2, w + 12, h + 4, 7, 0x12FFFFFF);
        serverIcon(g, icon, hasIcon, x, y, 32);
        int region = REGION_NONE;
        if (hovered) {
            int relX = mouseX - x, relY = mouseY - y;
            boolean overIcon = relX >= 0 && relX < 32 && relY >= 0 && relY < 32;
            ModernStyle.rounded(g, x, y, 32, 32, 4, 0x99050F1A);
            boolean overJoin = overIcon && relX >= 16;
            ModernIcons.draw(g, ModernIcons.Icon.PLAY, x + 17F, y + 9.5F, 13F, overJoin ? 0xFFFFFFFF : 0xB3BFE8FF);
            if (canMoveUp) {
                boolean over = overIcon && relX < 16 && relY < 16;
                ModernIcons.draw(g, ModernIcons.Icon.CHEVRON_UP, x + 2F, y + 2F, 12F, over ? 0xFFFFFFFF : 0x99BFE8FF);
                if (over) region = REGION_ICON_ACTION;
            }
            if (canMoveDown) {
                boolean over = overIcon && relX < 16 && relY >= 16;
                ModernIcons.draw(g, ModernIcons.Icon.CHEVRON_DOWN, x + 2F, y + 18F, 12F, over ? 0xFFFFFFFF : 0x99BFE8FF);
                if (over) region = REGION_ICON_ACTION;
            }
            if (overJoin) region = REGION_ICON_ACTION;
        }

        // Right column: signal bars, and the player count / status to their left.
        int barsW = pingHidden ? 0 : 16;
        int barsX = x + w - barsW;
        if (!pingHidden) {
            signal(g, data, barsX, y + 1);
            if (ModernStyle.inside(mouseX, mouseY, barsX - 1, y - 1, barsW + 2, 12)) region = REGION_PING;
        }
        int statusW = Math.round(styledWidth(status));
        int statusX = barsX - (pingHidden ? 0 : 6) - statusW;
        styled(g, status, statusX, y, statusW + 1, SUB);
        if (statusW > 0 && ModernStyle.inside(mouseX, mouseY, statusX - 1, y - 1, statusW + 2, 12)) region = REGION_PLAYERS;

        int textX = x + 40;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(data.name, Math.round((statusX - 8 - textX) / 1.08F)),
            textX, y, 1.08F, NAME);
        styledLines(g, motd, textX, y + 12.5F, x + w - textX, 10F, 2, FAINT);
        return region;
    }

    static void serverIcon(GuiGraphicsExtractor g, Identifier icon, boolean hasIcon, int x, int y, int size) {
        ModernStyle.rounded(g, x - 1, y - 1, size + 2, size + 2, 5, 0x3DFFFFFF);
        if (hasIcon) {
            g.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0F, 0F, size, size, 64, 64, 64, 64, ModernStyle.a(0xFFFFFFFF));
        } else {
            ModernStyle.rounded(g, x, y, size, size, 4, 0xFF142B3D);
            float glyph = size * 0.56F;
            ModernIcons.draw(g, ModernIcons.Icon.SERVER, x + (size - glyph) / 2F, y + (size - glyph) / 2F, glyph, 0xFF8FC4E3);
        }
    }

    /** Five rising bars colored by latency; a travelling pulse while pinging; a glyph when unreachable/incompatible. */
    static void signal(GuiGraphicsExtractor g, ServerData data, int x, int y) {
        ServerData.State state = data.state();
        if (state == ServerData.State.UNREACHABLE) {
            ModernIcons.draw(g, ModernIcons.Icon.CLOSE, x + 3F, y - 1F, 11F, BAD);
            return;
        }
        if (state == ServerData.State.INCOMPATIBLE) {
            ModernIcons.draw(g, ModernIcons.Icon.WARNING, x + 3F, y - 1F, 11F, FAIR);
            return;
        }
        int lit;
        int color;
        if (state == ServerData.State.SUCCESSFUL) {
            long ping = data.ping;
            lit = ping < 150L ? 5 : ping < 300L ? 4 : ping < 600L ? 3 : ping < 1000L ? 2 : 1;
            color = lit >= 4 ? GOOD : lit == 3 ? FAIR : BAD;
        } else {
            lit = -1;
            color = GOOD;
        }
        float wave = (Util.getMillis() % 1000L) / 1000F * 7F - 1F;
        for (int i = 0; i < 5; i++) {
            int barH = 3 + i * 2;
            int bx = x + i * 3, by = y + 11 - barH;
            int c;
            if (lit < 0) {
                float d = Math.abs(wave - i);
                c = ModernTypography.fade(color, Math.max(0.18F, 1F - d * 0.45F));
            } else {
                c = i < lit ? color : 0x33FFFFFF;
            }
            ModernStyle.fill(g, bx, by, bx + 2, y + 11, c);
        }
    }

    // --- LAN ----------------------------------------------------------------------------------------------

    public static void lanServer(GuiGraphicsExtractor g, Component title, String motd, Component address,
                                 int x, int y, int w, int h, boolean hovered) {
        if (hovered) ModernStyle.rounded(g, x - 8, y - 2, w + 12, h + 4, 7, 0x12FFFFFF);
        ModernStyle.rounded(g, x - 1, y - 1, 34, 34, 5, 0x3DFFFFFF);
        ModernStyle.rounded(g, x, y, 32, 32, 4, 0xFF123A3A);
        ModernIcons.draw(g, ModernIcons.Icon.LAN, x + 7F, y + 6F, 18F, 0xFF8FE8D8);
        int textX = x + 40, textW = w - 40;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(title.getString(), textW), textX, y, 1.08F, NAME);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(motd, textW), textX, y + 12.5F, 1F, SUB);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(address.getString(), textW), textX, y + 22.5F, 1F, FAINT);
    }

    public static void lanHeader(GuiGraphicsExtractor g, Component label, int x, int y, int w, int h) {
        String text = label.getString();
        float textW = ModernTypography.width(ModernTypography.Face.TEXT, text, 1F);
        // The animated dots change every few hundred ms; lay out for their widest form so nothing shifts.
        float dotsW = ModernTypography.width(ModernTypography.Face.TEXT, "O O O", 1F);
        float total = 16F + textW + 6F + dotsW;
        float left = x + (w - total) / 2F;
        float cy = y + h / 2F;
        int lineY = Math.round(cy);
        ModernStyle.fill(g, x + 8, lineY, Math.round(left) - 10, lineY + 1, 0x1FFFFFFF);
        ModernStyle.fill(g, Math.round(left + total) + 10, lineY, x + w - 8, lineY + 1, 0x1FFFFFFF);
        ModernIcons.draw(g, ModernIcons.Icon.LAN, left, cy - 6F, 11F, 0xFF7F9FB4);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, text, left + 16F, cy - 5F, 1F, 0xFF8FB0C4);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, LoadingDotsText.get(Util.getMillis()), left + 16F + textW + 6F, cy - 5F, 1F, 0xFF6F8FA4);
    }

    // --- styled text --------------------------------------------------------------------------------------

    /**
     * Draws up to {@code maxLines} lines of {@code text} (split at its newlines, as server MOTDs are) in the
     * serif, one run per color - including colors from legacy § codes - each line cut off with an ellipsis
     * at {@code maxWidth}. Bold/italic/obfuscated styling is ignored.
     */
    static void styledLines(GuiGraphicsExtractor g, Component text, float x, float y, float maxWidth, float lineHeight, int maxLines, int defaultColor) {
        List<List<Run>> lines = lines(text, defaultColor);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) drawRuns(g, lines.get(i), x, y + i * lineHeight, maxWidth);
    }

    static void styled(GuiGraphicsExtractor g, Component text, float x, float y, float maxWidth, int defaultColor) {
        styledLines(g, text, x, y, maxWidth, 0F, 1, defaultColor);
    }

    /** Width of the first line of {@code text}. */
    static float styledWidth(Component text) {
        List<List<Run>> lines = lines(text, 0);
        float width = 0F;
        if (!lines.isEmpty()) for (Run run : lines.getFirst()) width += ModernTypography.width(ModernTypography.Face.TEXT, run.text(), 1F);
        return width;
    }

    private static void drawRuns(GuiGraphicsExtractor g, List<Run> runs, float x, float y, float maxWidth) {
        float cursor = x, limit = x + maxWidth;
        for (Run run : runs) {
            float runW = ModernTypography.width(ModernTypography.Face.TEXT, run.text(), 1F);
            if (cursor + runW > limit) {
                String cut = ModernTypography.ellipsize(run.text(), Math.max(0, Math.round(limit - cursor)));
                if (!cut.isEmpty()) ModernTypography.draw(g, ModernTypography.Face.TEXT, cut, cursor, y, 1F, run.color());
                return;
            }
            ModernTypography.draw(g, ModernTypography.Face.TEXT, run.text(), cursor, y, 1F, run.color());
            cursor += runW;
        }
    }

    private record Run(String text, int color) {}

    /**
     * Server texts are colored for vanilla's light-on-dark-dirt look; §0/§1/§8-style dark colors all but
     * vanish on Modern's dark glass (a player count's "/" is dark gray). Lift anything too dark toward white,
     * keeping its hue.
     */
    private static int legible(int argb) {
        int r = argb >> 16 & 0xFF, gr = argb >> 8 & 0xFF, b = argb & 0xFF;
        float luma = (0.2126F * r + 0.7152F * gr + 0.0722F * b) / 255F;
        return luma >= 0.42F ? argb : ModernStyle.mix(argb, 0xFFFFFFFF, (0.42F - luma) / (1F - luma) + 0.08F);
    }

    private static List<List<Run>> lines(Component text, int defaultColor) {
        // ServerData's motd/status stay null until the row's first ping starts; draw nothing rather than fail.
        if (text == null) return List.of();
        List<List<Run>> lines = new ArrayList<>();
        List<Run> line = new ArrayList<>();
        lines.add(line);
        StringBuilder current = new StringBuilder();
        int[] currentColor = {defaultColor};
        List<Run>[] open = new List[]{line};
        text.getVisualOrderText().accept((index, style, codepoint) -> {
            if (codepoint == 10) { // newline
                if (!current.isEmpty()) open[0].add(new Run(current.toString(), currentColor[0]));
                current.setLength(0);
                open[0] = new ArrayList<>();
                lines.add(open[0]);
                return true;
            }
            TextColor color = style.getColor();
            int argb = color == null ? defaultColor : legible(0xFF000000 | color.getValue());
            if (argb != currentColor[0] && !current.isEmpty()) {
                open[0].add(new Run(current.toString(), currentColor[0]));
                current.setLength(0);
            }
            currentColor[0] = argb;
            current.appendCodePoint(codepoint);
            return true;
        });
        if (!current.isEmpty()) open[0].add(new Run(current.toString(), currentColor[0]));
        return lines;
    }
}
