package com.mentalfrostbyte.jello.module.impl.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringDecomposer;
import org.junit.jupiter.api.Test;

class ModuleAntiCheatTest {

    @Test
    void startsOffWithEveryCheckOnAtBalancedSensitivity() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        assertFalse(module.isEnabled());
        assertEquals(ModuleCategory.MISC, module.getCategory());
        assertEquals("AntiCheat", module.getName());
        for (String check : new String[] {"Speed", "Flight", "GroundSpoof", "NoSlow", "Notify", "NameTag"}) {
            assertTrue(((BooleanSetting) module.setting(check).orElseThrow()).get(), check + " should default on");
        }

        @SuppressWarnings("unchecked")
        EnumSetting<ModuleAntiCheat.Sensitivity> sensitivity =
                (EnumSetting<ModuleAntiCheat.Sensitivity>) module.setting("Sensitivity").orElseThrow();
        assertEquals(ModuleAntiCheat.Sensitivity.BALANCED, sensitivity.get());
    }

    @Test
    void switchingOnAndOffClearsWhatWasFlagged() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        Component name = Component.literal("Steve");
        module.suspects().update(id, "Steve", "Speed", 5.0);
        assertEquals(5.0, module.suspects().totalLevel(id), 1.0E-9);
        assertTrue(module.marksName(id));

        module.setEnabled(true);
        assertTrue(module.isEnabled());
        assertEquals(0.0, module.suspects().totalLevel(id), 1.0E-9, "enabling starts fresh");
        assertFalse(module.marksName(id));
        assertSame(name, module.tagged(id, name));

        module.suspects().update(id, "Steve", "Speed", 5.0);
        assertTrue(module.marksName(id));
        module.setEnabled(false);
        assertFalse(module.isEnabled());
        assertEquals(0.0, module.suspects().totalLevel(id), 1.0E-9, "disabling forgets the flags");
        assertFalse(module.marksName(id));
        assertSame(name, module.tagged(id, name));
    }

    @Test
    void forgettingASuspectRemovesThemFromTheList() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        module.suspects().update(a, "A", "Speed", 4.0);
        module.suspects().update(b, "B", "Flight", 4.0);
        assertTrue(module.marksName(a));
        assertTrue(module.marksName(b));

        module.forget(a);
        assertEquals(0.0, module.suspects().totalLevel(a), 1.0E-9);
        assertEquals(4.0, module.suspects().totalLevel(b), 1.0E-9);
        assertFalse(module.marksName(a));
        assertTrue(module.marksName(b));

        module.forgetAll();
        assertTrue(module.suspects().ranked().isEmpty());
        assertFalse(module.marksName(b));
    }

    @Test
    void theNameTagIsLeftAloneUntilTheAlertLevelIsReached() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        Component name = Component.literal("Steve");

        assertSame(name, module.tagged(id, name));
        assertFalse(module.marksName(id));

        module.suspects().update(id, "Steve", "Speed", Math.nextDown(3.0));
        assertSame(name, module.tagged(id, name), "even immediately below the alert level stays unmarked");
        assertFalse(module.marksName(id));

        module.suspects().update(id, "Steve", "Speed", 3.0);
        assertTrue(module.marksName(id), "the alert boundary is inclusive");
        Component tagged = module.tagged(id, name);
        assertEquals("Steve", tagged.getString(), "warning icons are separate from the name's text");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), tagged.toFlatList().getFirst().getStyle().getColor());
    }

    @Test
    void theNameMarkUsesTheTotalAcrossChecksAndTheCurrentAlertLevel() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 1.5);
        module.suspects().update(id, "Steve", "Flight", 1.5);
        assertTrue(module.marksName(id), "neither individual check reaches 3, but their total does");

        ((NumberSetting) module.setting("Alert Level").orElseThrow()).set(3.5F);
        assertFalse(module.marksName(id));
        module.suspects().update(id, "Steve", "Speed", 2.0);
        assertTrue(module.marksName(id));
        module.suspects().update(id, "Steve", "Flight", 1.0);
        assertFalse(module.marksName(id), "decay below the threshold removes the mark");
    }

    @Test
    void theWholeNameTurnsRedWithoutLosingStylesEventsOrChangingTheOriginal() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 4.0);
        Style parentStyle = Style.EMPTY.withColor(ChatFormatting.BLUE).withBold(true)
                .withClickEvent(new ClickEvent.SuggestCommand("/msg Steve "))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Player profile")))
                .withInsertion("Steve").withShadowColor(0xFF123456)
                .withFont(new FontDescription.Resource(Identifier.withDefaultNamespace("uniform")));
        Component name = Component.literal("[Server] ").setStyle(parentStyle)
                .append(Component.literal("[VIP] ").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC))
                .append(Component.literal("Steve").withStyle(ChatFormatting.BLUE)
                        .append(Component.literal("玩家").withStyle(ChatFormatting.GREEN, ChatFormatting.UNDERLINE)))
                .append(Component.literal(" Jr").setStyle(Style.EMPTY.withBold(false).withColor(ChatFormatting.AQUA)));
        List<Component> originalRuns = name.toFlatList();

        Component marked = module.tagged(id, name);
        List<Component> markedRuns = marked.toFlatList();
        assertEquals("[Server] [VIP] Steve玩家 Jr", marked.getString());
        assertEquals(originalRuns.size(), markedRuns.size());
        for (int index = 0; index < originalRuns.size(); index++) {
            assertEquals(originalRuns.get(index).getString(), markedRuns.get(index).getString());
            assertEquals(originalRuns.get(index).getStyle().withColor(ChatFormatting.RED),
                    markedRuns.get(index).getStyle(), "only each run's color may change");
        }
        assertEquals(originalRuns, name.toFlatList(), "rendering a warning must not mutate the server's component");
    }

    @Test
    void changingWorldClearsNameMarks() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 4.0);
        assertTrue(module.marksName(id));
        module.onLoadWorld(new EventLoadWorld(null));
        assertFalse(module.marksName(id));
        Component name = Component.literal("Steve");
        assertSame(name, module.tagged(id, name));
    }

    @Test
    void legacyColorsCannotOverrideRedAndKeepTheirOtherFormatting() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 4.0);
        Component name = Component.literal("§a[VIP] §lSteve §r🦊").setStyle(Style.EMPTY
                .withColor(ChatFormatting.BLUE).withItalic(true)
                .withClickEvent(new ClickEvent.SuggestCommand("/msg Steve "))
                .withInsertion("Steve"));
        List<Style> expectedGlyphStyles = new ArrayList<>();
        StringDecomposer.iterateFormatted(name, Style.EMPTY, (position, style, codePoint) -> {
            expectedGlyphStyles.add(style.withColor(ChatFormatting.RED));
            return true;
        });

        Component marked = module.tagged(id, name);
        List<Style> actualGlyphStyles = new ArrayList<>();
        StringDecomposer.iterateFormatted(marked, Style.EMPTY, (position, style, codePoint) -> {
            actualGlyphStyles.add(style);
            return true;
        });
        assertEquals("[VIP] Steve 🦊", marked.getString());
        assertEquals(expectedGlyphStyles, actualGlyphStyles,
                "legacy color/reset codes must not recolor glyphs or discard other styles and events");
        assertEquals("§a[VIP] §lSteve §r🦊", name.getString());
    }

    @Test
    void theNameTagCanBeSwitchedOff() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        ((BooleanSetting) module.setting("NameTag").orElseThrow()).set(false);
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 9.0);
        Component name = Component.literal("Steve");
        assertFalse(module.marksName(id));
        assertSame(name, module.tagged(id, name));
        ((BooleanSetting) module.setting("NameTag").orElseThrow()).set(true);
        assertTrue(module.marksName(id), "turning name marks back on immediately reflects the current flags");
    }
}
