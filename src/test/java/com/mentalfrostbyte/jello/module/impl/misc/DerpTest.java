package com.mentalfrostbyte.jello.module.impl.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DerpTest {

    @Test
    void jitterFacesForwardThenBackwardInTurn() {
        List<Boolean> cycle = new ArrayList<>();
        for (int tick = 0; tick < 6; tick++) {
            cycle.add(Derp.jitterBackward(tick, 2, 1));
        }
        assertEquals(List.of(false, false, true, false, false, true), cycle);
        assertTrue(Derp.jitterBackward(5, 0, 3), "no forward ticks: always backward");
        assertFalse(Derp.jitterBackward(5, 0, 0), "an empty cycle never turns");
    }

    @Test
    void onlyTheCurrentModesSettingsAreShown() {
        Derp derp = new Derp();
        assertFalse(this.visible(derp, "Yaw Value"), "Random needs no value");
        assertFalse(this.visible(derp, "Spin Speed"));
        this.choose(derp, "Yaw", Derp.YawMode.SPIN);
        assertTrue(this.visible(derp, "Spin Speed"));
        this.choose(derp, "Pitch", Derp.PitchMode.STATIC);
        assertTrue(this.visible(derp, "Pitch Value"));
    }

    private boolean visible(final Derp derp, final String name) {
        return derp.setting(name).map(Setting::isVisible).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> void choose(final Derp derp, final String name, final E value) {
        ((EnumSetting<E>) derp.setting(name).orElseThrow()).set(value);
    }
}
