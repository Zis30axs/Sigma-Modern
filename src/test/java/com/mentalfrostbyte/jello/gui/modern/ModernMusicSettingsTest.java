package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModernMusicSettingsTest {
    @Test
    void everyControlIsReachableAfterScrollingACompactWindow() {
        ModernMusicView.Box content = new ModernMusicView.Box(40, 60, 220, 90);
        ModernMusicSettings.FxLayout initial = ModernMusicSettings.fxLayout(content, 0F);
        for (int i = 0; i < initial.switches().length; i++) {
            ModernMusicView.Box control = initial.switches()[i];
            float scroll = scrollTo(content, initial, control);
            ModernMusicSettings.FxLayout shown = ModernMusicSettings.fxLayout(content, scroll);
            ModernMusicView.Box displayed = shown.switches()[i];
            assertEquals(i, shown.switchAt(displayed.x() + displayed.w() / 2, displayed.y() + displayed.h() / 2));
        }
        for (int i = 0; i < initial.sliders().length; i++) {
            float scroll = scrollTo(content, initial, initial.sliders()[i]);
            ModernMusicSettings.FxLayout shown = ModernMusicSettings.fxLayout(content, scroll);
            ModernMusicView.Box displayed = shown.sliders()[i];
            assertEquals(i, shown.dialAt(displayed.x() + displayed.w() / 2, displayed.y() + displayed.h() / 2));
        }
        ModernMusicSettings.FxLayout bottom = ModernMusicSettings.fxLayout(content, ModernMusicSettings.clampedScroll(content, 10_000F));
        ModernMusicView.Box particles = bottom.particles();
        for (int i = 0; i < 3; i++) {
            assertEquals(i, bottom.particleAt(particles.x() + particles.w() * (i + 0.5) / 3, particles.y() + 5));
        }
    }

    @Test
    void controlsOutsideTheViewportCannotBeClicked() {
        ModernMusicView.Box content = new ModernMusicView.Box(40, 60, 220, 90);
        ModernMusicSettings.FxLayout shown = ModernMusicSettings.fxLayout(content, 80F);
        for (ModernMusicView.Box control : shown.switches()) {
            if (!shown.visible(control)) assertEquals(-1, shown.switchAt(control.x() + 2, control.y() + 2));
        }
        for (ModernMusicView.Box control : shown.sliders()) {
            if (!shown.visible(control)) assertEquals(-1, shown.dialAt(control.x() + 2, control.y() + 2));
        }
        assertFalse(shown.visible(shown.particles()));
        assertEquals(-1, shown.particleAt(shown.particles().x() + 2, shown.particles().y() + 2));
        assertEquals(-1, shown.switchAt(content.x() - 1, shown.viewport().y() + 2));
        assertEquals(-1, shown.dialAt(content.x() + content.w(), shown.viewport().y() + 2));
    }

    @Test
    void visibleLabelCannotActivateAFullyClippedSlider() {
        ModernMusicView.Box content = new ModernMusicView.Box(40, 60, 220, 175);
        ModernMusicSettings.FxLayout shown = ModernMusicSettings.fxLayout(content, 0F);
        int environment = 0;
        double x = content.x() + 10, y = shown.sliderY()[environment] + 2;
        assertTrue(shown.viewport().contains(x, y));
        assertFalse(shown.visible(shown.sliders()[environment]));
        assertEquals(-1, shown.dialAt(x, y));
    }

    @Test
    void onlyTheVisiblePartOfAPartiallyClippedSwitchIsClickable() {
        ModernMusicView.Box content = new ModernMusicView.Box(40, 60, 220, 90);
        ModernMusicSettings.FxLayout shown = ModernMusicSettings.fxLayout(content, 10F);
        ModernMusicView.Box water = shown.switches()[0];
        assertTrue(shown.visible(water));
        assertEquals(-1, shown.switchAt(water.x() + 2, shown.viewport().y() - 1));
        assertEquals(0, shown.switchAt(water.x() + 2, shown.viewport().y() + 1));
    }

    @Test
    void scrollLimitIsStableAndShrinksWhenTheWindowGrows() {
        ModernMusicView.Box compact = new ModernMusicView.Box(40, 60, 220, 90);
        float limit = ModernMusicSettings.clampedScroll(compact, 10_000F);
        assertTrue(limit > 0F);
        assertEquals(limit, ModernMusicSettings.clampedScroll(compact, limit + 18F));
        ModernMusicSettings.FxLayout bottom = ModernMusicSettings.fxLayout(compact, limit);
        assertTrue(bottom.particles().y() + bottom.particles().h() <= compact.y() + compact.h());
        assertEquals(0F, ModernMusicSettings.clampedScroll(new ModernMusicView.Box(40, 60, 220, 500), limit));
        assertEquals(0F, ModernMusicSettings.clampedScroll(compact, -18F));
        assertEquals(0F, ModernMusicSettings.clampedScroll(compact, Float.NaN));
    }

    @Test
    void collapsedViewportHasNoActionableControls() {
        ModernMusicView.Box content = new ModernMusicView.Box(40, 60, 220, 20);
        ModernMusicSettings.FxLayout shown = ModernMusicSettings.fxLayout(content, 0F);
        assertEquals(0, shown.viewport().h());
        assertEquals(-1, shown.switchAt(50, 85));
        assertEquals(-1, shown.dialAt(50, 230));
        assertEquals(-1, shown.particleAt(50, shown.particles().y() + 1));
    }

    private static float scrollTo(ModernMusicView.Box content, ModernMusicSettings.FxLayout layout, ModernMusicView.Box control) {
        return ModernMusicSettings.clampedScroll(content,
            control.y() + control.h() / 2F - (layout.viewport().y() + layout.viewport().h() / 2F));
    }
}
