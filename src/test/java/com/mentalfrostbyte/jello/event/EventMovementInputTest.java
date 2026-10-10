package com.mentalfrostbyte.jello.event;

import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventMovementInputTest {
    @Test void scriptedDirectionChangesBothReportedKeysAndMovement() {
        EventMovementInput event = new EventMovementInput(Input.EMPTY, Vec2.ZERO);
        event.setForward(-0.4F);
        event.setStrafe(0.7F);
        event.setJump(true);
        event.setSprint(true);
        assertTrue(event.getInput().backward());
        assertFalse(event.getInput().forward());
        assertTrue(event.getInput().left());
        assertTrue(event.getInput().jump());
        assertTrue(event.getInput().sprint());
        assertEquals(new Vec2(0.7F, -0.4F), event.getMoveVector());
        event.setShift(true);
        assertEquals(new Vec2(0.7F, -0.4F), event.getMoveVector(), "Eagle must retain scripted movement");
    }

    @Test void wholeInputReplacementClearsPreviousMovement() {
        EventMovementInput event = new EventMovementInput(new Input(true, false, true, false, false, false, false));
        assertEquals(1.0F, event.getMoveVector().length(), 1.0E-5F);
        event.setInput(Input.EMPTY);
        assertEquals(Vec2.ZERO, event.getMoveVector());
        assertFalse(event.isMoving());
    }

    @Test void nonfiniteOrExcessiveScriptValuesCannotEscapeTheInputRange() {
        EventMovementInput event = new EventMovementInput(Input.EMPTY);
        event.setForward(Float.NaN);
        event.setStrafe(8);
        assertEquals(0, event.getForward());
        assertEquals(1, event.getStrafe());
        event.setStrafe(Float.POSITIVE_INFINITY);
        assertFalse(event.isMoving());
    }
}
