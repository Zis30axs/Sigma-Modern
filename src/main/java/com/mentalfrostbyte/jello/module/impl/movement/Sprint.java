package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/** Requests forward sprinting through this tick's input; vanilla owns the actual sprint conditions. */
public final class Sprint extends Module {

    public Sprint() {
        super(ModuleCategory.MOVEMENT, "Sprint", "Automatically sprints while moving forward under normal sprint conditions.");
    }

    @EventTarget(EventPriority.HIGHEST)
    public void onInput(final EventMovementInput event) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()
                || !mc.isWindowActive() || mc.gui.screen() != null || mc.gui.overlay() != null) {
            return;
        }

        // BlockFly, InvMove and Stuck run later and can override this request.
        if (event.getForward() > 0F && !event.getInput().shift()) {
            event.setSprint(true);
        }
    }
}
