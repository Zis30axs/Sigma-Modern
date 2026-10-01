package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.TextEntryScreen;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.map.MapManager;
import com.mentalfrostbyte.jello.map.Waypoint;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.Easing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * "Jello Maps": a white sheet over the blurred game with the waypoints in a column at the left (260 px) and the map of
 * what has been seen of this world at the right. The title stands above the sheet and the world's name at its right end. The
 * map is dragged with the mouse and zoomed with the wheel or the control in its corner; a right-click on it opens a card to
 * name a waypoint there. Escape closes the card first, then the page.
 *
 * <p>What is on the map is {@link MapManager}'s - recorded while Jello is the presentation, from the chunks the game has
 * loaded; opening the page colours the ones around you first, so a world that has just been joined is not blank. The page
 * is 850 x 550 pixels of the old client's layout, smaller when the window is.</p>
 */
public final class JelloMapsScreen extends Screen implements ModernBlurredBackdrop, TextEntryScreen {
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;
    private static final int SIDE = 260;

    private final Animation appear = new Animation(200, 200);
    private final MapManager.@Nullable Session session;
    private final @Nullable JelloMapView map;
    private final @Nullable JelloWaypointList list;
    private @Nullable JelloWaypointPopover popover;
    private long lastFrame;

    public JelloMapsScreen() {
        super(Component.literal("Jello Maps"));
        MapManager manager = Client.getInstance().getMapManager();
        MapManager.Session open = manager.session();
        if (open != null) {
            // The chunks around the player, so a fresh world is not an empty map.
            manager.record(96);
        }

        this.session = open;
        this.map = open == null ? null : new JelloMapView(open);
        this.list = open == null || this.map == null ? null : new JelloWaypointList(open, this.map::centreOn);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTypingText() {
        return this.popover != null && this.popover.typing();
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        GuiVisuals.blurBackground(graphics);
    }

    @Override
    public void removed() {
        Client.getInstance().getMapManager().flush();
        if (this.map != null) {
            this.map.release();
        }

        super.removed();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        if (this.session == null || this.map == null || this.list == null) {
            this.onClose();
            return;
        }

        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            long now = System.nanoTime();
            float dt = this.lastFrame == 0L ? 0.0F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
            this.lastFrame = now;
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();
            float appear = this.appear.calcPercent();
            float pop = Easing.easeOutBack(appear, 0.0F, 1.0F, 1.0F);

            c.fill(0, 0, w, h, LegacyCanvas.alpha(INK, 0.25F * appear));

            // The sheet: up to 850 x 550, less when the window is small (the title needs 70 px above it).
            int pw = Math.max(300, Math.min(850, w - 40));
            // Top and height on whole GUI units: the map is clipped in strips, and a scissor can only start on one.
            int unit = Math.max(1, c.guiScale());
            int ph = Math.max(200, Math.min(550, h - 150)) / unit * unit;
            int px = (w - pw) / 2;
            int py = (h - ph) / 2 / unit * unit;

            c.push();
            try {
                c.scaleAbout(0.8F + pop * 0.2F, 0.8F + pop * 0.2F, w / 2.0F, h / 2.0F);
                c.text(Face.JELLO_MEDIUM, 40.0F, "Jello Maps", px, Math.max(8, py - 70), LegacyCanvas.alpha(WHITE, appear));
                String world = this.session.world().name();
                c.text(Face.JELLO_LIGHT, 24.0F, world, px + pw - c.textWidth(Face.JELLO_LIGHT, 24.0F, world) - 10, Math.max(8, py - 62) + 8,
                    LegacyCanvas.alpha(WHITE, 0.5F * appear));

                c.outerGlow(px + 7, py + 7, pw - 14, ph - 14, 20.0F, 0.6F * appear);
                c.rounded(px, py, pw, ph, 14, LegacyCanvas.alpha(WHITE, 0.88F * appear));

                this.map.x = px + SIDE;
                this.map.y = py;
                this.map.w = pw - SIDE;
                this.map.h = ph;
                this.list.x = px;
                this.list.y = py;
                this.list.h = ph;
                boolean covered = this.popover != null && !this.popover.closed() && this.popover.contains(mx, my);
                double lx = covered ? -1.0E6 : mx;
                double ly = covered ? -1.0E6 : my;
                this.map.draw(c, lx, ly, appear);
                this.list.draw(c, lx, ly, appear, dt);

                c.fill(px + SIDE, py, px + SIDE + 1, py + ph, LegacyCanvas.alpha(INK, 0.14F * appear));
                c.text(Face.JELLO_LIGHT, 25.0F, "Waypoints", px + 30, py + 25, LegacyCanvas.alpha(INK, 0.6F * appear));
                String centre = Math.round(this.map.view.centreX()) + "  " + Math.round(this.map.view.centreZ());
                c.text(Face.JELLO_LIGHT, 14.0F, centre, px + SIDE - 23 - c.textWidth(Face.JELLO_LIGHT, 14.0F, centre), py + 35,
                    LegacyCanvas.alpha(INK, 0.4F * appear));

                if (this.popover != null) {
                    this.popover.draw(c, mx, my, appear);
                    if (this.popover.closed()) {
                        this.popover = null;
                    }
                }
            } finally {
                c.pop();
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (this.map == null || this.list == null || this.session == null) {
            return true;
        }

        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        int button = event.button();
        if (this.popover != null && !this.popover.closing()) {
            if (this.popover.contains(mx, my)) {
                this.popover.mouseClicked(mx, my);
                return true;
            }

            // A click anywhere else puts the card away, and goes on to do what it would have done.
            this.popover.close();
        }

        if (this.list.mouseClicked(mx, my, button)) {
            return true;
        }

        this.map.mouseClicked(mx, my, button, (px, py, blockX, blockZ) -> this.popover = new JelloWaypointPopover(
            px, py, this.width * this.minecraft.getWindow().getGuiScale(), this.height * this.minecraft.getWindow().getGuiScale(),
            this.session.dimension(), blockX, blockZ, this::add));
        return true;
    }

    private void add(final Waypoint waypoint) {
        if (this.session != null) {
            this.session.waypoints().add(waypoint);
        }
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        if (this.popover != null && !this.popover.closing()) {
            this.popover.mouseDragged(mx);
            return true;
        }

        if (this.list != null) {
            this.list.mouseDragged(mx, my);
        }
        if (this.map != null) {
            this.map.mouseDragged(mx, my);
        }

        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (this.list != null) {
            this.list.mouseReleased();
        }
        if (this.map != null) {
            this.map.mouseReleased();
        }

        return true;
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        double mx = LegacyCanvas.toLegacy(x);
        double my = LegacyCanvas.toLegacy(y);
        if (this.list != null && this.list.mouseScrolled(mx, my, scrollY)) {
            return true;
        }

        if (this.map != null && this.map.contains(mx, my)) {
            this.map.mouseScrolled(scrollY);
        }

        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.popover != null && !this.popover.closing()) {
            return this.popover.keyPressed(event);
        }

        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
        }

        return true;
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        return this.popover != null && this.popover.charTyped(event);
    }
}
