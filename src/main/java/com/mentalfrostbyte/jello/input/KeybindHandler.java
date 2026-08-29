package com.mentalfrostbyte.jello.input;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mentalfrostbyte.jello.module.BindMode;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import org.lwjgl.glfw.GLFW;

/**
 * Turns key presses into module state.
 *
 * <p>All of it lives here rather than in the modules: a module says which key it answers to and what a
 * press means, and never listens to the keyboard itself.</p>
 *
 * <p>Only a real press counts. GLFW repeats a held key several times a second, which is what made the old
 * client toggle a module over and over while a key was down.</p>
 *
 * <p>A {@link BindMode#HOLD} module is switched off by the key's release, and, failing that, by noticing on
 * the next tick that the key is no longer down. The release is easy to miss - opening a screen or losing
 * focus mid-press swallows it - and a module stuck on because of that is worse than a check per tick.</p>
 */
public final class KeybindHandler implements MinecraftInstance {

    private final ModuleManager modules;

    public KeybindHandler(final ModuleManager modules) {
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    @EventTarget
    public void onKeyPress(final EventKeyPress event) {
        if (event.getAction() == EventKeyPress.Action.REPEAT) {
            return;
        }

        boolean pressed = event.getAction() == EventKeyPress.Action.PRESS;
        for (Module module : this.modules.all()) {
            Keybind keybind = module.getKeybind();
            if (!keybind.isBound() || !keybind.key().equals(event.getKey())) {
                continue;
            }

            switch (keybind.mode()) {
                case TOGGLE -> {
                    if (pressed) {
                        module.toggle();
                    }
                }
                case HOLD -> module.setEnabled(pressed);
            }
        }
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (!event.isPre()) {
            return;
        }

        for (Module module : this.modules.all()) {
            Keybind keybind = module.getKeybind();
            if (module.isEnabled() && keybind.mode() == BindMode.HOLD && keybind.isBound()
                && !this.isHeld(keybind.key())) {
                module.setEnabled(false);
            }
        }
    }

    /**
     * Whether a key is physically down right now. A scan code cannot be asked about, so it is reported as
     * held and left to its release event.
     */
    private boolean isHeld(final InputConstants.Key key) {
        long handle = mc.getWindow().handle();
        return switch (key.getType()) {
            case KEYSYM -> GLFW.glfwGetKey(handle, key.getValue()) != GLFW.GLFW_RELEASE;
            case MOUSE -> GLFW.glfwGetMouseButton(handle, key.getValue()) != GLFW.GLFW_RELEASE;
            case SCANCODE -> true;
        };
    }
}
