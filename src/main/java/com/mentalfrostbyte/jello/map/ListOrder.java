package com.mentalfrostbyte.jello.map;

import java.util.ArrayList;
import java.util.List;

/** The arithmetic of dragging a row to a new place in a list of equal rows; the list draws, this decides. */
public final class ListOrder {
    private ListOrder() {}

    /**
     * The place a dragged row belongs in: the row its middle is over, counted from 0. {@code top} is where the row's top
     * edge is, in the list's own coordinates (scrolled with it); a row pulled past either end takes the first or last place.
     */
    public static int slot(final double top, final int rowHeight, final int count) {
        if (count <= 0) {
            return 0;
        }

        int slot = (int) Math.floor((top + rowHeight / 2.0) / rowHeight);
        return Math.max(0, Math.min(count - 1, slot));
    }

    /** {@code list} with the item at {@code from} taken out and put back at {@code to}. */
    public static <T> List<T> moved(final List<T> list, final int from, final int to) {
        List<T> out = new ArrayList<>(list);
        T item = out.remove(from);
        out.add(Math.max(0, Math.min(out.size(), to)), item);
        return out;
    }
}
