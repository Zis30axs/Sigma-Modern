package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.map.ListOrder;
import com.mentalfrostbyte.jello.map.MapManager;
import com.mentalfrostbyte.jello.map.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The column of waypoints beside the map: a 70-pixel row each with its colour, name and spot. A click on a row takes the map
 * there. Holding the three bars at the right of a row pulls it out of line: dropping it between two others moves it there (the
 * others slide aside to make room), dropping it on the bin that slides in at the bottom-left deletes it.
 *
 * <p>The list shows the waypoints of the dimension being mapped, in the order {@code WaypointStore} keeps them, and reads the
 * store again whenever its version changes. Coordinates are {@code LegacyCanvas} pixels; the list's top-left is
 * ({@code x}, {@code y}) and its first row is {@link #TOP} below that.</p>
 */
final class JelloWaypointList {
    static final int WIDTH = 260;
    static final int ROW = 70;
    static final int TOP = 65;
    private static final int HANDLE = 62;
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;
    private static final int DELETE = 0xFFFF5555;

    private static final class Row {
        final Waypoint waypoint;
        // Where the row is, from the top of the list's content, and where it is going.
        float y;
        float target;
        float hover;
        float gone;
        boolean leaving;

        Row(final Waypoint waypoint, final float y) {
            this.waypoint = waypoint;
            this.y = y;
            this.target = y;
        }
    }

    private final MapManager.Session session;
    private final Consumer<Waypoint> centre;
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private final List<Row> rows = new ArrayList<>();
    private int version = -1;
    private @Nullable Row held;
    private float grab;
    private float heldTop;
    private float bin;
    private boolean overBin;
    // The list's top-left and height, in canvas pixels: set by the page each frame.
    int x;
    int y;
    int h;

    JelloWaypointList(final MapManager.Session session, final Consumer<Waypoint> centre) {
        this.session = session;
        this.centre = centre;
    }

    private int viewHeight() {
        return this.h - TOP;
    }

    private int contentHeight() {
        int count = 0;
        for (Row row : this.rows) {
            if (!row.leaving) {
                count++;
            }
        }

        return count * ROW;
    }

    private boolean isOver(final double mx, final double my) {
        return mx >= this.x && mx < this.x + WIDTH && my >= this.y + TOP && my < this.y + this.h;
    }

    /** Takes the store's list as it now is, keeping the rows that are still in it (and what they were doing). */
    private void sync() {
        // While a row is being pulled the list stays as it is, so the row does not vanish under the pointer.
        if (this.held != null || this.version == this.session.waypoints().version()) {
            return;
        }

        this.version = this.session.waypoints().version();
        List<Waypoint> now = this.session.waypoints().in(this.session.dimension());
        List<Row> next = new ArrayList<>();
        for (int i = 0; i < now.size(); i++) {
            Row known = null;
            for (Row row : this.rows) {
                if (row.waypoint.equals(now.get(i))) {
                    known = row;
                    break;
                }
            }

            next.add(known != null ? known : new Row(now.get(i), i * ROW));
        }

        this.rows.clear();
        this.rows.addAll(next);
    }

    // ------------------------------------------------------------------ drawing

    void draw(final LegacyCanvas c, final double mx, final double my, final float alpha, final float dt) {
        this.sync();
        this.layout(dt);
        this.scroll.clamp(this.contentHeight(), this.viewHeight());
        boolean over = this.isOver(mx, my);

        c.scissor(this.x, this.y + TOP, this.x + WIDTH, this.y + this.h);
        try {
            Row pulled = this.held;
            for (Row row : this.rows) {
                if (row != pulled) {
                    this.row(c, row, mx, my, over, alpha, dt);
                }
            }

            // The row being pulled is drawn last, over the others.
            if (pulled != null) {
                this.row(c, pulled, mx, my, over, alpha, dt);
            }
        } finally {
            c.unscissor();
        }

        if (this.rows.isEmpty()) {
            float middle = this.y + TOP + this.viewHeight() / 2.0F;
            c.textCentered(Face.JELLO_LIGHT, 16.0F, "Right-click the map", this.x + WIDTH / 2.0F, middle - 12, LegacyCanvas.alpha(INK, 0.35F * alpha));
            c.textCentered(Face.JELLO_LIGHT, 16.0F, "to add a waypoint", this.x + WIDTH / 2.0F, middle + 12, LegacyCanvas.alpha(INK, 0.35F * alpha));
        }

        this.scroll.draw(c, this.x + WIDTH, this.y + TOP, this.viewHeight(), this.contentHeight(), this.viewHeight(), over, alpha);
        this.bin(c, alpha, dt);
    }

    /** Works out where every row wants to be and moves it there: the others make room for the one that is pulled. */
    private void layout(final float dt) {
        int live = 0;
        for (Row row : this.rows) {
            if (!row.leaving) {
                live++;
            }
        }

        int slot = this.held != null ? ListOrder.slot(this.heldTop, ROW, live) : -1;
        int index = 0;
        for (Row row : this.rows) {
            if (row.leaving || row == this.held) {
                continue;
            }

            if (index == slot) {
                index++;
            }

            row.target = index * ROW;
            index++;
        }

        float ease = Math.min(1.0F, dt * 14.0F);
        for (Row row : this.rows) {
            if (row == this.held) {
                row.y = this.heldTop;
            } else if (!row.leaving) {
                row.y += (row.target - row.y) * ease;
            }

            if (row.leaving) {
                row.gone = Math.min(1.0F, row.gone + dt / 0.2F);
            }
        }

        Row finished = null;
        for (Row row : this.rows) {
            if (row.leaving && row.gone >= 1.0F) {
                finished = row;
                break;
            }
        }

        if (finished != null) {
            // Only now does the waypoint leave the file; the list reads it back and forgets the row.
            this.session.waypoints().remove(finished.waypoint);
        }
    }

    private void row(final LegacyCanvas c, final Row row, final double mx, final double my, final boolean listHovered, final float alpha, final float dt) {
        float slide = row.gone * row.gone * WIDTH;
        float rx = this.x + slide;
        float ry = this.y + TOP + row.y - this.scroll.offset();
        if (ry + ROW < this.y + TOP || ry > this.y + this.h) {
            return;
        }

        boolean pulled = row == this.held;
        boolean hovered = listHovered && !row.leaving && this.held == null && my >= ry && my < ry + ROW && mx >= rx && mx < rx + WIDTH;
        boolean onHandle = hovered && mx >= rx + WIDTH - HANDLE;
        row.hover += ((hovered || pulled ? 1.0F : 0.0F) - row.hover) * Math.min(1.0F, dt * 12.0F);

        int left = Math.round(rx);
        int top = Math.round(ry);
        c.fill(left, top, left + WIDTH, top + ROW, LegacyCanvas.alpha(0xFFF6F6F6, row.hover * alpha));
        c.text(Face.JELLO_LIGHT, 20.0F, c.fit(Face.JELLO_LIGHT, 20.0F, row.waypoint.name(), WIDTH - 68 - 50), left + 68, top + 14, LegacyCanvas.alpha(INK, 0.8F * alpha));
        c.text(Face.JELLO_LIGHT, 14.0F, "x:" + row.waypoint.x() + " z:" + row.waypoint.z(), left + 68, top + 38, LegacyCanvas.alpha(INK, 0.5F * alpha));

        // The three bars to hold it by.
        int bars = LegacyCanvas.alpha(INK, (pulled || onHandle ? 0.4F : 0.2F) * alpha);
        for (int i = 0; i < 3; i++) {
            c.fill(left + WIDTH - 43, top + 27 + i * 5, left + WIDTH - 23, top + 29 + i * 5, bars);
        }

        // The colour: a dot with a slightly darker ring.
        int colour = row.waypoint.color();
        c.disc(left + 35, top + ROW / 2.0F, 10.0F, LegacyCanvas.fade(LegacyCanvas.shiftTowardsOther(colour, INK, 0.9F), alpha));
        c.disc(left + 35, top + ROW / 2.0F, 8.5F, LegacyCanvas.fade(colour, alpha));
    }

    /** The bin that slides in from the left while a row is pulled: red when the row is over it. */
    private void bin(final LegacyCanvas c, final float alpha, final float dt) {
        this.bin += ((this.held != null ? 1.0F : 0.0F) - this.bin) * Math.min(1.0F, dt * 10.0F);
        if (this.bin < 0.01F) {
            return;
        }

        int shift = Math.round((1.0F - this.bin) * (1.0F - this.bin) * 30.0F);
        c.image(LegacyTexture.TRASHCAN, this.x - shift + 18, this.y + this.h - 46, 22, 26,
            LegacyCanvas.alpha(this.overBin ? DELETE : INK, this.bin * 0.5F * alpha));
    }

    // ------------------------------------------------------------------ input

    /** A press in the list; only the left button does anything, but the list takes every button's press that lands on it. */
    boolean mouseClicked(final double mx, final double my, final int button) {
        if (!this.isOver(mx, my)) {
            return false;
        }

        if (button != 0) {
            return true;
        }

        if (this.scroll.press(mx, my, this.x + WIDTH, this.y + TOP, this.viewHeight(), this.contentHeight(), this.viewHeight())) {
            return true;
        }

        float content = (float) (my - this.y - TOP + this.scroll.offset());
        for (Row row : this.rows) {
            if (row.leaving || content < row.y || content >= row.y + ROW) {
                continue;
            }

            if (mx >= this.x + WIDTH - HANDLE) {
                this.held = row;
                this.grab = content - row.y;
                this.heldTop = row.y;
            } else {
                this.centre.accept(row.waypoint);
            }

            break;
        }

        return true;
    }

    void mouseDragged(final double mx, final double my) {
        if (this.scroll.dragging()) {
            this.scroll.drag(my, this.y + TOP, this.viewHeight(), this.contentHeight(), this.viewHeight());
            return;
        }

        if (this.held == null) {
            return;
        }

        float content = (float) (my - this.y - TOP + this.scroll.offset());
        // The row follows the pointer anywhere in the list (so it can be taken to the bin); its place is worked out from where it is.
        this.heldTop = Math.max(-ROW / 2.0F, Math.min(this.viewHeight() + this.scroll.offset() - ROW / 2.0F, content - this.grab));
        this.overBin = mx >= this.x + 10 && mx < this.x + 50 && my >= this.y + this.h - 50 && my < this.y + this.h - 10;
    }

    void mouseReleased() {
        this.scroll.release();
        Row dropped = this.held;
        if (dropped == null) {
            return;
        }

        this.held = null;
        if (this.overBin) {
            dropped.leaving = true;
            this.overBin = false;
            return;
        }

        List<Row> live = new ArrayList<>();
        for (Row row : this.rows) {
            if (!row.leaving) {
                live.add(row);
            }
        }

        int from = live.indexOf(dropped);
        int to = ListOrder.slot(this.heldTop, ROW, live.size());
        List<Waypoint> order = new ArrayList<>();
        for (Row row : live) {
            order.add(row.waypoint);
        }

        // The store's version moves on, the list reads the new order, and the rows slide from where they are into it.
        this.session.waypoints().reorder(this.session.dimension(), ListOrder.moved(order, from, to));
    }

    boolean mouseScrolled(final double mx, final double my, final double delta) {
        if (!this.isOver(mx, my)) {
            return false;
        }

        this.scroll.wheel(delta, this.contentHeight(), this.viewHeight());
        return true;
    }
}
