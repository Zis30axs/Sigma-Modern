package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.Scoreboard;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

/** The server sidebar in Modern's glass and serif type, sharing the Alt editor's saved layout. */
public final class ModernScoreboard {
    private static final String LAYOUT_ID = "scoreboard";
    private static final int MARGIN = 10, PAD = 10, HEADER = 27, ROW = 15, FOOTER = 8;
    private static final int MIN_WIDTH = 144, MAX_WIDTH = 300, SCORE_GAP = 12;
    private static final Comparator<PlayerScoreEntry> DISPLAY_ORDER = Comparator.comparingInt(PlayerScoreEntry::value)
            .reversed().thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    record Entry(Component name, Component score) {}

    private ModernScoreboard() {}

    /** Drawn after the right-hand HUD stacks so its default position can avoid their current bounds. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        boolean editing = ModernHudEditor.isEditing();
        if (!ModernHud.isActive() || mc.player == null || mc.level == null || (mc.gui.hud.isHidden() && !editing)) return;
        Scoreboard module = Modules.enabled(Scoreboard.class);
        if (module == null) return;
        Objective objective = sidebarObjective(mc.level.getScoreboard(), mc.player.getScoreboardName());
        if (objective == null && !editing) return;

        // Preview data never enters the world's scoreboard, and disappears as soon as the editor closes.
        Component title = objective == null
                ? Component.literal(ModernText.t("Scoreboard · Preview", "记分板 · 预览")) : objective.getDisplayName();
        List<Entry> entries = objective == null ? List.of(
                new Entry(Component.literal(ModernText.t("Alt + drag to move", "按住 Alt 拖动")), Component.empty()),
                new Entry(Component.literal(ModernText.t("Right-click to reset", "右键重置位置")), Component.empty())) : entries(objective);

        // Fixed metrics retain rich server text without depending on the chat module's toggle or font size.
        ModernChatFont font = ModernChat.popupFont();
        int contentWidth = font.width(title);
        for (Entry entry : entries) {
            int scoreWidth = module.showsScores() ? font.width(entry.score) : 0;
            contentWidth = Math.max(contentWidth, font.width(entry.name) + (scoreWidth > 0 ? SCORE_GAP + scoreWidth : 0));
        }
        int width = Math.clamp(contentWidth + PAD * 2, MIN_WIDTH, MAX_WIDTH);
        int height = HEADER + entries.size() * ROW + FOOTER;
        float scale = Math.min(module.getScale(), Math.min(Math.max(0, g.guiWidth() - MARGIN * 2) / (float)width,
                Math.max(0, g.guiHeight() - MARGIN * 2) / (float)height));
        if (scale <= 0F) return;
        float scaledWidth = width * scale, scaledHeight = height * scale;
        // The original sidebar's right-side anchor, with room around the card and its shadow.
        float defaultX = g.guiWidth() - MARGIN - scaledWidth;
        float defaultY = g.guiHeight() * 0.5F - scaledHeight * 2F / 3F;
        var bounds = ModernHudEditor.place(g, LAYOUT_ID, ModernText.t("Scoreboard", "记分板"),
                defaultX, defaultY, scaledWidth, scaledHeight);
        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.x(), bounds.y());
            g.pose().scale(scale, scale);
            draw(g, font, module, title, entries, width, height);
        } finally {
            g.pose().popMatrix();
        }
        g.nextStratum();
    }

    /** Mirrors vanilla's team-coloured sidebar precedence, including fallback when that slot is empty. */
    static @Nullable Objective sidebarObjective(net.minecraft.world.scores.Scoreboard scoreboard, String playerName) {
        PlayerTeam team = scoreboard.getPlayersTeam(playerName);
        if (team != null && team.getColor().isPresent()) {
            Objective objective = scoreboard.getDisplayObjective(team.getColor().get().displaySlot());
            if (objective != null) return objective;
        }
        return scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    static List<Entry> entries(Objective objective) {
        net.minecraft.world.scores.Scoreboard scoreboard = objective.getScoreboard();
        NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        return scoreboard.listPlayerScores(objective).stream()
                .filter(score -> !score.isHidden())
                .sorted(DISPLAY_ORDER)
                .limit(15)
                .map(score -> new Entry(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(score.owner()), score.ownerName()),
                        score.formatValue(format)))
                .toList();
    }

    private static void draw(GuiGraphicsExtractor g, ModernChatFont font, Scoreboard module, Component title,
                             List<Entry> entries, int width, int height) {
        float background = module.getBackground();
        // Normalize the decorative alphas so the default keeps the original glass appearance.
        float tint = background / (192F / 255F);
        if (background > 0F) ModernStyle.dropShadow(g, 0, 2, width, height, 9, 0.65F * tint);
        if (module.hasBlur()) g.sigma$blurRounded(0, 0, width, height, 9, module.getBlurStrength());
        if (background > 0F) {
            ModernStyle.rounded(g, 0, 0, width, height, 9, Math.round(102 * tint) << 24 | 0x8BBFD9);
            ModernStyle.rounded(g, 1, 1, width - 2, height - 2, 8, Math.round(255 * background) << 24 | 0x0A1926);
            ModernStyle.fillGradient(g, PAD, 1, width - PAD, HEADER - 1, Math.round(36 * tint) << 24 | 0xC3EFFF, 0x00C3EFFF);
            ModernStyle.fill(g, PAD, HEADER - 1, width - PAD, HEADER, Math.round(48 * tint) << 24 | 0x9DDEFF);
            ModernStyle.rounded(g, width / 2 - 12, HEADER - 1, 24, 1, 0, Math.round(176 * tint) << 24 | 0x9DDEFF);
        }

        int contentWidth = width - PAD * 2;
        FormattedCharSequence heading = fit(title, font, contentWidth);
        ModernChat.drawStyled(g, heading, (width - font.width(heading)) / 2F, 8, ModernStyle.TEXT, 1F, module.hasTextShadow(), font);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int nameWidth = font.width(entry.name), fullScoreWidth = module.showsScores() ? font.width(entry.score) : 0;
            int scoreLimit = nameWidth == 0 || nameWidth + SCORE_GAP + fullScoreWidth <= contentWidth
                    ? contentWidth : contentWidth * 2 / 5;
            FormattedCharSequence score = module.showsScores() ? fit(entry.score, font, scoreLimit) : FormattedCharSequence.EMPTY;
            int scoreWidth = font.width(score);
            FormattedCharSequence name = fit(entry.name, font, contentWidth - (scoreWidth > 0 ? SCORE_GAP + scoreWidth : 0));
            int y = HEADER + 2 + i * ROW;
            ModernChat.drawStyled(g, name, PAD, y, ModernStyle.TEXT, 1F, module.hasTextShadow(), font);
            if (scoreWidth > 0) {
                ModernChat.drawStyled(g, score, width - PAD - scoreWidth, y, ModernStyle.MUTED, 1F, module.hasTextShadow(), font);
            }
        }
    }

    /** Truncate only the visible runs; keep component styles, legacy colour codes and resource-pack glyphs. */
    private static FormattedCharSequence fit(Component text, ModernChatFont font, int width) {
        if (width <= 0) return FormattedCharSequence.EMPTY;
        if (font.width(text) <= width) return text.getVisualOrderText();
        Component ellipsis = Component.literal("…");
        FormattedText shortened = font.substrByWidth(text, Math.max(0, width - font.width(ellipsis)));
        return Language.getInstance().getVisualOrder(FormattedText.composite(shortened, ellipsis));
    }
}
