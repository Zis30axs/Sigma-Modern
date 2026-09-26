package com.mentalfrostbyte.jello.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EnumSettingTest {

    private enum Mode { ONE_SEVEN, PUSHDOWN, NIGHT_VISION }

    private enum Named {
        FAST("Fast!");

        private final String shown;

        Named(final String shown) {
            this.shown = shown;
        }

        @Override
        public String toString() {
            return this.shown;
        }
    }

    @Test
    void anOptionCanBePickedByItsPlace() {
        EnumSetting<Mode> mode = new EnumSetting<>("Mode", "test", Mode.ONE_SEVEN);
        assertEquals(0, mode.index());
        mode.setIndex(2);
        assertEquals(Mode.NIGHT_VISION, mode.get());
        assertEquals(2, mode.index());
        mode.setIndex(99);
        assertEquals(Mode.NIGHT_VISION, mode.get(), "clamped to the list");
        mode.setIndex(-1);
        assertEquals(Mode.ONE_SEVEN, mode.get());
    }

    @Test
    void steppingStopsAtEitherEndWhileCyclingWraps() {
        EnumSetting<Mode> mode = new EnumSetting<>("Mode", "test", Mode.ONE_SEVEN);
        mode.step(-1);
        assertEquals(Mode.ONE_SEVEN, mode.get());
        mode.step(1);
        assertEquals(Mode.PUSHDOWN, mode.get());
        mode.step(5);
        assertEquals(Mode.NIGHT_VISION, mode.get());
        mode.cycle();
        assertEquals(Mode.ONE_SEVEN, mode.get());
    }

    @Test
    void labelsReadAsWordsUnlessTheEnumNamesItself() {
        assertEquals("One Seven", EnumSetting.label(Mode.ONE_SEVEN));
        assertEquals("Night Vision", EnumSetting.label(Mode.NIGHT_VISION));
        assertEquals("Fast!", EnumSetting.label(Named.FAST));
    }
}
