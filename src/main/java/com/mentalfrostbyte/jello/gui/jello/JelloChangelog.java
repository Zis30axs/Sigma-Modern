package com.mentalfrostbyte.jello.gui.jello;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyScroll;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The Changelog page that opens over Jello's main menu ({@code ChangelogScreen}).
 *
 * <p>A big "Changelog" and "You're currently using Sigma ..." top left, and under them a scrolling list of
 * releases: a medium-weight version heading, then one " - line" per change. Each release rises 100 px into place
 * while it fades in, the next one starting when the previous is half way (going the other way they all leave at
 * once). The list is the bundled {@code sigma/changelog.json}, unless {@code sigma5/changelog.json} exists,
 * which is how the old client's local file worked.</p>
 */
public final class JelloChangelog {
    private static final String BUNDLED = "/assets/minecraft/sigma/changelog.json";
    private static final int LEFT = 100;
    private static final int TOP = 200;
    private static final int FIRST_ENTRY = 75;

    private record Entry(String title, List<String> changes) {
        int height() {
            return 55 + 22 * this.changes.size() + 75;
        }
    }

    private final List<Entry> entries = load();
    private final Animation[] reveal;
    private final Animation page = new Animation(380, 200, Animation.Direction.BACKWARDS);
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);
    private boolean open;
    private int contentHeight;
    private int viewHeight;
    private int viewWidth;

    /** How many times the page has been opened this session (the old client's counter, for its window-title joke). */
    public static int opened;

    public JelloChangelog() {
        this.reveal = new Animation[this.entries.size()];
        for (int i = 0; i < this.reveal.length; i++) {
            this.reveal[i] = new Animation(370, 150, Animation.Direction.BACKWARDS);
        }
    }

    public boolean isOpen() {
        return this.open;
    }

    public void setOpen(final boolean open) {
        if (open && !this.open) {
            opened++;
        }
        this.open = open;
        if (open) {
            this.scroll.setOffset(0);
        }
    }

    /** Fully shut: nothing left to draw. */
    public boolean hidden() {
        return !this.open && this.page.calcPercent() == 0.0F;
    }

    public void wheel(final double delta) {
        this.scroll.wheel(delta, this.contentHeight, this.viewHeight);
    }

    public boolean press(final double mx, final double my) {
        return this.open && this.scroll.press(mx, my, LEFT + this.viewWidth, TOP, this.viewHeight, this.contentHeight, this.viewHeight);
    }

    public void drag(final double my) {
        this.scroll.drag(my, TOP, this.viewHeight, this.contentHeight, this.viewHeight);
    }

    public void release() {
        this.scroll.release();
    }

    /** {@code alpha} is the overlay's own fade (1 when fully open). */
    public void draw(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        this.page.changeDirection(this.open ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float a = alpha * this.page.calcPercent();
        if (a <= 0.0F) {
            for (Animation reveal : this.reveal) {
                reveal.changeDirection(Animation.Direction.BACKWARDS);
            }
            return;
        }

        int white = 0xFFFEFEFE;
        c.text(Face.JELLO_LIGHT, 36, "Changelog", 100, 100, LegacyCanvas.alpha(white, a));
        c.text(Face.JELLO_LIGHT, 25, "You're currently using Sigma " + Client.FULL_VERSION, 100, 150, LegacyCanvas.alpha(white, 0.6F * a));

        this.viewWidth = c.width() - 200;
        this.viewHeight = c.height() - 200;
        this.contentHeight = FIRST_ENTRY;
        for (Entry entry : this.entries) {
            this.contentHeight += entry.height();
        }
        this.scroll.clamp(this.contentHeight, this.viewHeight);

        // Entries come in one after another: the next starts once the one before is half way.
        boolean stillArriving = false;
        for (Animation reveal : this.reveal) {
            if (this.open) {
                if (!stillArriving) {
                    reveal.changeDirection(Animation.Direction.FORWARDS);
                }
                stillArriving = stillArriving || reveal.calcPercent() < 0.5F;
            } else {
                reveal.changeDirection(Animation.Direction.BACKWARDS);
            }
        }

        c.scissor(LEFT, TOP, LEFT + this.viewWidth, TOP + this.viewHeight);
        int y = FIRST_ENTRY - this.scroll.offset();
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            float t = SmoothInterpolator.interpolate(this.reveal[i].calcPercent(), 0.17, 1.0, 0.51, 1.0);
            float entryAlpha = a * this.reveal[i].calcPercent();
            float rise = (1.0F - t) * 100.0F;
            int top = TOP + y;
            if (top + entry.height() > TOP - 100 && top < TOP + this.viewHeight + 100) {
                c.text(Face.JELLO_MEDIUM, 40, entry.title(), LEFT, top + rise, LegacyCanvas.alpha(white, entryAlpha));
                for (int line = 0; line < entry.changes().size(); line++) {
                    c.text(Face.JELLO_LIGHT, 20, " - " + entry.changes().get(line), LEFT, top + 55 + line * 22 + rise,
                        LegacyCanvas.alpha(white, 0.8F * entryAlpha));
                }
            }
            y += entry.height();
        }
        c.unscissor();

        boolean over = mx >= LEFT && mx < LEFT + this.viewWidth && my >= TOP && my < TOP + this.viewHeight;
        this.scroll.draw(c, LEFT + this.viewWidth, TOP, this.viewHeight, this.contentHeight, this.viewHeight, over, a);
    }

    private static List<Entry> load() {
        List<Entry> result = new ArrayList<>();
        try {
            Path local = Minecraft.getInstance().gameDirectory.toPath().resolve("sigma5").resolve("changelog.json");
            JsonArray array;
            if (Files.isRegularFile(local)) {
                try (Reader reader = Files.newBufferedReader(local, StandardCharsets.UTF_8)) {
                    array = JsonParser.parseReader(reader).getAsJsonArray();
                }
            } else {
                try (InputStream in = JelloChangelog.class.getResourceAsStream(BUNDLED)) {
                    if (in == null) {
                        throw new IOException("Missing " + BUNDLED);
                    }
                    array = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
                }
            }
            for (JsonElement element : array) {
                JsonObject release = element.getAsJsonObject();
                List<String> changes = new ArrayList<>();
                for (JsonElement change : release.getAsJsonArray("changes")) {
                    changes.add(change.getAsString());
                }
                result.add(new Entry(release.get("title").getAsString(), changes));
            }
        } catch (IOException | RuntimeException failure) {
            // A malformed local file only costs the list, never the menu.
            Client.logger.warn("Cannot read the changelog", failure);
        }
        return result;
    }
}
