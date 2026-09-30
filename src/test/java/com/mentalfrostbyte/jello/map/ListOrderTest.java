package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ListOrderTest {

    @Test
    void aRowBelongsWhereItsMiddleIs() {
        // Rows 70 high: the middle of a row whose top is at 0 is at 35 - still the first place.
        assertEquals(0, ListOrder.slot(0, 70, 5));
        assertEquals(0, ListOrder.slot(34, 70, 5));
        // Past half a row's height it is in the second place.
        assertEquals(1, ListOrder.slot(35, 70, 5));
        assertEquals(1, ListOrder.slot(104, 70, 5));
        assertEquals(2, ListOrder.slot(105, 70, 5));
    }

    @Test
    void pulledPastEitherEndItTakesTheFirstOrLastPlace() {
        assertEquals(0, ListOrder.slot(-500, 70, 5));
        assertEquals(4, ListOrder.slot(5000, 70, 5));
        assertEquals(0, ListOrder.slot(123, 70, 0));
        assertEquals(0, ListOrder.slot(123, 70, 1));
    }

    @Test
    void movingAnItemShiftsTheOnesBetween() {
        List<String> list = List.of("a", "b", "c", "d");
        assertEquals(List.of("b", "c", "a", "d"), ListOrder.moved(list, 0, 2));
        assertEquals(List.of("d", "a", "b", "c"), ListOrder.moved(list, 3, 0));
        assertEquals(list, ListOrder.moved(list, 1, 1));
        // The original is not touched.
        assertEquals(List.of("a", "b", "c", "d"), list);
    }
}
