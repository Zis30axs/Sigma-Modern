package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;

/** Requests forward sprinting through this tick's input; vanilla owns the actual sprint conditions. */
public final class Sprint extends Module {

    private final BooleanSetting stopOnHit = this.register(new BooleanSetting("Stop On Hit",
            "Lets an attack that cancels sprinting finish this tick before sprinting again next tick.", true));

    public Sprint() {
        super(ModuleCategory.MOVEMENT, "Sprint", "Automatically sprints while moving forward under normal sprint conditions.");
    }

    @EventTarget(EventPriority.HIGHEST)
    public void onInput(final EventMovementInput event) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()
                || !mc.isWindowActive() || mc.gui.screen() != null || mc.gui.overlay() != null) {
            return;
        }
        if (this.stopOnHit.get() && mc.player.didAttackStopSprintThisTick()) {
            event.setSprint(false);
            return;
        }

        // When Silent/Claude3 turns the keys right after EventMovementInput, a camera retreat/strafe can become
        // forward in reported space; LocalPlayer#aiStep strips synthesized sprint again if the turned keys are not forward.
        MovementCorrector corrector = MovementCorrector.current();
        boolean turnsKeys = corrector != null && corrector.turnsKeys() && !mc.player.isPassenger();
        // BlockFly, InvMove and Stuck run later and can override this request.
        if ((turnsKeys ? event.isMoving() : event.getForward() > 0F) && !event.getInput().shift()) {
            event.setSprint(true);
        }
    }

    public boolean shouldAutoSprint(final boolean forward, final boolean backward, final boolean movingInput, final boolean sneaking) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) {
            return false;
        }
        if (this.stopOnHit.get() && mc.player.didAttackStopSprintThisTick()) {
            return false;
        }
        MovementCorrector corrector = MovementCorrector.current();
        boolean turnsKeys = corrector != null && corrector.turnsKeys() && !mc.player.isPassenger();
        return (turnsKeys ? movingInput : (forward && !backward)) && !sneaking;
    }
}
