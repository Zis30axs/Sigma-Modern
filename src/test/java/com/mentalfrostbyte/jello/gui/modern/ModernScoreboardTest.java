package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ModernScoreboardTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void thePlayersTeamColorSidebarWinsAndMissingTeamSlotsFallBack() {
        Scoreboard scoreboard = new Scoreboard();
        Objective global = objective(scoreboard, "global");
        Objective blue = objective(scoreboard, "blue");
        Objective red = objective(scoreboard, "red");
        scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, global);
        scoreboard.setDisplayObjective(TeamColor.BLUE.displaySlot(), blue);
        scoreboard.setDisplayObjective(TeamColor.RED.displaySlot(), red);
        PlayerTeam team = scoreboard.addPlayerTeam("local-team");
        scoreboard.addPlayerToTeam("local-player", team);

        assertSame(global, ModernScoreboard.sidebarObjective(scoreboard, "local-player"));
        assertSame(global, ModernScoreboard.sidebarObjective(scoreboard, "unaffiliated-player"));
        team.setColor(Optional.of(TeamColor.BLUE));
        assertSame(blue, ModernScoreboard.sidebarObjective(scoreboard, "local-player"));
        scoreboard.setDisplayObjective(TeamColor.BLUE.displaySlot(), null);
        assertSame(global, ModernScoreboard.sidebarObjective(scoreboard, "local-player"));
        scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
        assertNull(ModernScoreboard.sidebarObjective(scoreboard, "local-player"));
        scoreboard.setDisplayObjective(TeamColor.BLUE.displaySlot(), blue);
        assertSame(blue, ModernScoreboard.sidebarObjective(scoreboard, "local-player"));
    }

    @Test
    void hiddenHighScoresNeverConsumeTheFifteenVisibleRows() {
        Scoreboard scoreboard = new Scoreboard();
        Objective objective = objective(scoreboard, "rows");
        for (int i = 0; i < 20; i++) {
            score(objective, "#hidden" + i, 100 + i);
            score(objective, "visible" + i, i);
        }

        List<String> names = ModernScoreboard.entries(objective).stream().map(entry -> entry.name().getString()).toList();
        assertEquals(IntStream.range(0, 15).mapToObj(i -> "visible" + (19 - i)).toList(), names);
    }

    @Test
    void scoresDescendAndTiesUseCaseInsensitiveOwnersRatherThanDisplayNames() {
        Scoreboard scoreboard = new Scoreboard();
        Objective objective = objective(scoreboard, "order");
        score(objective, "Zulu", 100);
        score(objective, "alpha", 10).display(Component.literal("Z display"));
        score(objective, "Bravo", 10).display(Component.literal("A display"));
        score(objective, "charlie", 10);
        score(objective, "first-alphabetically", -5);

        assertEquals(List.of("Zulu", "Z display", "A display", "charlie", "first-alphabetically"),
                ModernScoreboard.entries(objective).stream().map(entry -> entry.name().getString()).toList());
    }

    @Test
    void teamDecorationsAndDisplayOverridesKeepTheirIndividualStyles() {
        Scoreboard scoreboard = new Scoreboard();
        Objective objective = objective(scoreboard, "styled-names");
        PlayerTeam team = scoreboard.addPlayerTeam("green");
        team.setColor(Optional.of(TeamColor.GREEN));
        team.setPlayerPrefix(Component.literal("[VIP] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        team.setPlayerSuffix(Component.literal(" !").withStyle(ChatFormatting.GRAY, ChatFormatting.UNDERLINE));
        scoreboard.addPlayerToTeam("original-owner", team);
        ScoreAccess score = score(objective, "original-owner", 1);
        score.display(Component.literal("Alias").withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));

        Component shown = ModernScoreboard.entries(objective).getFirst().name();
        assertEquals("[VIP] Alias !", shown.getString());
        List<Component> runs = shown.toFlatList();
        assertEquals(3, runs.size());
        assertEquals(TextColor.GOLD, runs.get(0).getStyle().getColor());
        assertTrue(runs.get(0).getStyle().isBold());
        assertEquals(TextColor.AQUA, runs.get(1).getStyle().getColor());
        assertTrue(runs.get(1).getStyle().isItalic());
        assertEquals(TextColor.GRAY, runs.get(2).getStyle().getColor());
        assertTrue(runs.get(2).getStyle().isUnderlined());

        score.display(null);
        List<Component> original = ModernScoreboard.entries(objective).getFirst().name().toFlatList();
        assertEquals("original-owner", original.get(1).getString());
        assertEquals(TextColor.GREEN, original.get(1).getStyle().getColor());
    }

    @Test
    void numberFormatsPreserveBlankFixedStyledAndPerRowOverrides() {
        Scoreboard scoreboard = new Scoreboard();
        Objective objective = objective(scoreboard, "formats");
        score(objective, "default", 40);
        score(objective, "blank", 30).numberFormatOverride(BlankFormat.INSTANCE);
        Component fixed = Component.literal("READY").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        score(objective, "fixed", 20).numberFormatOverride(new FixedFormat(fixed));
        score(objective, "styled", 10).numberFormatOverride(new StyledFormat(Style.EMPTY.withColor(ChatFormatting.BLUE)));

        List<ModernScoreboard.Entry> entries = ModernScoreboard.entries(objective);
        assertEquals("40", entries.get(0).score().getString());
        assertEquals(TextColor.RED, entries.get(0).score().getStyle().getColor());
        assertEquals("", entries.get(1).score().getString());
        assertEquals(fixed, entries.get(2).score());
        assertEquals("10", entries.get(3).score().getString());
        assertEquals(TextColor.BLUE, entries.get(3).score().getStyle().getColor());

        objective.setNumberFormat(BlankFormat.INSTANCE);
        entries = ModernScoreboard.entries(objective);
        assertEquals("", entries.get(0).score().getString());
        assertEquals(fixed, entries.get(2).score());
        assertEquals("10", entries.get(3).score().getString());

        Component objectiveLabel = Component.literal("points").withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC);
        objective.setNumberFormat(new FixedFormat(objectiveLabel));
        entries = ModernScoreboard.entries(objective);
        assertEquals(objectiveLabel, entries.get(0).score());
        assertEquals("", entries.get(1).score().getString());
        assertEquals(fixed, entries.get(2).score());
        assertEquals(TextColor.BLUE, entries.get(3).score().getStyle().getColor());
    }

    private static Objective objective(Scoreboard scoreboard, String name) {
        return scoreboard.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
    }

    private static ScoreAccess score(Objective objective, String owner, int value) {
        ScoreAccess score = objective.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), objective);
        score.set(value);
        return score;
    }
}
