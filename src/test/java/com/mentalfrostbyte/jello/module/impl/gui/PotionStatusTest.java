package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.gui.PotionStatus.Entry;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.effect.MobEffectCategory;
import org.junit.jupiter.api.Test;

class PotionStatusTest {

    private static final Entry SPEED = new Entry("Speed", MobEffectCategory.BENEFICIAL, 1200, false, false, true);
    private static final Entry REGENERATION = new Entry("Regeneration", MobEffectCategory.BENEFICIAL, 300, false, false, true);
    private static final Entry POISON = new Entry("Poison", MobEffectCategory.HARMFUL, 100, false, false, true);
    private static final Entry GLOWING = new Entry("Glowing", MobEffectCategory.NEUTRAL, 400, false, false, true);
    private static final Entry HASTE_BEACON = new Entry("Haste", MobEffectCategory.BENEFICIAL, 260, false, true, true);
    private static final Entry NIGHT_VISION = new Entry("Night Vision", MobEffectCategory.BENEFICIAL, -1, true, false, true);
    private static final Entry HIDDEN = new Entry("Luck", MobEffectCategory.BENEFICIAL, 50, false, false, false);
    private static final List<Entry> ALL = List.of(SPEED, REGENERATION, POISON, GLOWING, HASTE_BEACON, NIGHT_VISION, HIDDEN);

    private static List<String> names(PotionStatus status) {
        return status.listed(ALL, Function.identity()).stream().map(Entry::name).toList();
    }

    @Test
    void isAnInterfaceModuleThatTheConfigSwitchesOnByDefault() {
        PotionStatus status = new PotionStatus();
        assertFalse(status.isEnabled(), "on by default is the config's doing, not the constructor's");
        assertTrue(status.isEnabledByDefault(), "it replaces vanilla's icons, which are always there");
        assertEquals(ModuleCategory.INTERFACE, status.getCategory());
        assertEquals("PotionStatus", status.getName());
    }

    @Test
    void cardsByDefaultAndInlineFollowsTheArrayListsLookSoTheCardSettingsHide() {
        PotionStatus status = new PotionStatus();
        assertEquals(PotionStatus.Layout.LIST, status.getLayout());
        assertTrue(status.setting("Background").orElseThrow().isVisible());
        assertTrue(status.setting("Duration Bar").orElseThrow().isVisible());
        layout(status, PotionStatus.Layout.INLINE);
        assertFalse(status.setting("Background").orElseThrow().isVisible());
        assertFalse(status.setting("Duration Bar").orElseThrow().isVisible());
        assertTrue(status.setting("Show Level").orElseThrow().isVisible());
    }

    @Test
    void aConfigSavedWithTheDroppedCompactLayoutKeepsTheCurrentOne() {
        PotionStatus status = new PotionStatus();
        layout(status, PotionStatus.Layout.INLINE);
        assertFalse(status.setting("Layout").orElseThrow().fromJson(new JsonPrimitive("COMPACT")));
        assertEquals(PotionStatus.Layout.INLINE, status.getLayout(), "an unknown saved value is ignored, not thrown on");
        assertEquals(List.of(PotionStatus.Layout.LIST, PotionStatus.Layout.INLINE), List.of(PotionStatus.Layout.values()));
    }

    @Test
    void kindPutsGoodEffectsFirstAsVanillasRowsDoAndEndlessOnesLastInEachKind() {
        PotionStatus status = new PotionStatus();
        assertEquals(List.of("Haste", "Regeneration", "Speed", "Night Vision", "Glowing", "Poison"), names(status));
    }

    @Test
    void durationPutsWhatRunsOutFirstOnTop() {
        PotionStatus status = new PotionStatus();
        sort(status, PotionStatus.Order.DURATION);
        assertEquals(List.of("Poison", "Haste", "Regeneration", "Glowing", "Speed", "Night Vision"), names(status));
    }

    @Test
    void nameIsAlphabetical() {
        PotionStatus status = new PotionStatus();
        sort(status, PotionStatus.Order.NAME);
        assertEquals(List.of("Glowing", "Haste", "Night Vision", "Poison", "Regeneration", "Speed"), names(status));
    }

    @Test
    void anEffectVanillaGivesNoIconIsNotShownAndAmbientOnesOnlyWhenAsked() {
        PotionStatus status = new PotionStatus();
        assertFalse(names(status).contains("Luck"), "show_icon false: hidden, as vanilla hides it");
        assertTrue(names(status).contains("Haste"));
        ((BooleanSetting) status.setting("Hide Ambient").orElseThrow()).set(true);
        assertFalse(names(status).contains("Haste"), "a beacon's effect is ambient");
        assertEquals(5, names(status).size());
    }

    @Test
    void levelIsRomanFromTwoToTenAndLeftOutForOne() {
        assertEquals("", PotionStatus.level(0));
        assertEquals("II", PotionStatus.level(1));
        assertEquals("IV", PotionStatus.level(3));
        assertEquals("IX", PotionStatus.level(8));
        assertEquals("X", PotionStatus.level(9));
        assertEquals("11", PotionStatus.level(10));
        assertEquals("256", PotionStatus.level(255));
        assertEquals("", PotionStatus.level(-1), "a negative amplifier from a command is not a level");
    }

    @Test
    void blinkOnlyInTheLastTenSecondsAndNeverVanishes() {
        assertEquals(1F, PotionStatus.blink(PotionStatus.EXPIRING_TICKS + 1, false));
        assertEquals(1F, PotionStatus.blink(5, true), "endless effects never run out");
        float lowest = 1F, highest = 0F;
        for (float t = PotionStatus.EXPIRING_TICKS; t >= 0F; t -= 0.25F) {
            float alpha = PotionStatus.blink(t, false);
            assertTrue(alpha > 0.4F && alpha <= 1F, "at " + t + ": " + alpha);
            lowest = Math.min(lowest, alpha);
            highest = Math.max(highest, alpha);
        }
        assertTrue(lowest < 0.6F && highest > 0.95F, "it does pulse: " + lowest + ".." + highest);
        // Deeper near the end than at the start of the ten seconds.
        float early = 1F, late = 1F;
        for (float t = 200F; t > 180F; t -= 0.25F) early = Math.min(early, PotionStatus.blink(t, false));
        for (float t = 20F; t > 0F; t -= 0.25F) late = Math.min(late, PotionStatus.blink(t, false));
        assertTrue(late < early, early + " then " + late);
    }

    @SuppressWarnings("unchecked")
    private static void layout(PotionStatus status, PotionStatus.Layout layout) {
        ((EnumSetting<PotionStatus.Layout>) status.setting("Layout").orElseThrow()).set(layout);
    }

    @SuppressWarnings("unchecked")
    private static void sort(PotionStatus status, PotionStatus.Order order) {
        ((EnumSetting<PotionStatus.Order>) status.setting("Sort").orElseThrow()).set(order);
    }
}
