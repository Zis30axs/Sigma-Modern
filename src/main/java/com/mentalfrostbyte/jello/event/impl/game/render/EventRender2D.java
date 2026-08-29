package com.mentalfrostbyte.jello.event.impl.game.render;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import com.mentalfrostbyte.jello.event.EventState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The in-game HUD pass, fired at the head ({@link EventState#PRE}) and tail
 * ({@link EventState#POST}) of vanilla HUD extraction. Cancelling the PRE event suppresses the
 * vanilla HUD; POST is where client overlays draw so they sit on top of it.
 *
 * <p>26.2 renders the interface in two phases - draw calls are recorded into a render state during
 * extraction and submitted to the GPU afterwards - so {@link GuiGraphicsExtractor} is what a
 * listener draws with, not a live command buffer.</p>
 *
 * <p>Only fired while a level is being rendered, so nothing here draws over the title screen. It does
 * fire with an in-game screen open, but the screen is extracted afterwards and therefore covers it -
 * the same layering the vanilla HUD gets.</p>
 */
public class EventRender2D extends CancellableEvent {

    private final GuiGraphicsExtractor graphics;
    private final DeltaTracker deltaTracker;

    public EventRender2D(final EventState state, final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
        super(state);
        this.graphics = graphics;
        this.deltaTracker = deltaTracker;
    }

    public GuiGraphicsExtractor getGraphics() {
        return this.graphics;
    }

    public DeltaTracker getDeltaTracker() {
        return this.deltaTracker;
    }

    /** Fraction of the current tick already elapsed, for interpolating animations. */
    public float getPartialTick() {
        return this.deltaTracker.getGameTimeDeltaPartialTick(false);
    }

    public int getWidth() {
        return this.graphics.guiWidth();
    }

    public int getHeight() {
        return this.graphics.guiHeight();
    }
}
