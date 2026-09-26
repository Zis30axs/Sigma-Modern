package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import org.jspecify.annotations.Nullable;

/**
 * Sigma's TabGUI: the module categories in a small panel in the top-left corner, driven from the keyboard in game.
 * Up and Down pick a category, Right or Enter opens its modules beside it, Up and Down pick a module, Right or Enter
 * switches it, and Left goes back.
 *
 * <p>This class is the menu itself - which categories and modules it offers, where the selection is, what a key does -
 * and holds no drawing. {@code gui.modern.ModernTabGui} draws it for SigmaModern and calls {@link #markShown()} every
 * frame it does; keys only move the menu while one has, so a presentation that doesn't draw it leaves the arrows
 * alone instead of switching modules nobody can see.</p>
 *
 * <p>A key the game or a module keybind already uses is left to them: someone who walks with the arrow keys keeps
 * walking. The key still reaches the game either way; the menu never swallows it.</p>
 */
public class TabGui extends Module {

    /** What a key asks of the menu. */
    public enum Nav { UP, DOWN, LEFT, RIGHT, ENTER }

    /** How long after the last frame that drew the menu it still takes keys. */
    private static final long SHOWN_NANOS = 250_000_000L;

    private final ModuleManager modules;

    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "How solid the dark glass behind the panels is. 0 draws none.", 0.6F, 0.0F, 1.0F, 0.05F));

    private final BooleanSetting keybinds = this.register(new BooleanSetting(
            "Keybinds", "Shows a module's key beside its name, when it has one.", true));

    private final BooleanSetting animations = this.register(new BooleanSetting(
            "Animations", "Glides the selection between rows and slides the module panel open.", true));

    private int category;

    private int module;

    private boolean open;

    private long lastShown = Long.MIN_VALUE;

    public TabGui(final ModuleManager modules) {
        super(ModuleCategory.INTERFACE, "TabGUI", "Browse and switch modules from the keyboard with the arrow keys");
        this.modules = modules;
    }

    /** The categories that have a module to offer, in their usual order. */
    public List<ModuleCategory> categories() {
        List<ModuleCategory> found = new ArrayList<>();
        for (ModuleCategory candidate : ModuleCategory.values()) {
            if (!this.modules(candidate).isEmpty()) {
                found.add(candidate);
            }
        }

        return found;
    }

    /** {@code category}'s modules, minus this one: switching the menu off from inside it would only lose it. */
    public List<Module> modules(final ModuleCategory category) {
        List<Module> found = new ArrayList<>(this.modules.byCategory(category));
        found.remove(this);
        return found;
    }

    /** The highlighted category's place in {@link #categories()}. */
    public int selectedCategory() {
        return this.category;
    }

    /** The highlighted module's place in the open category's {@link #modules(ModuleCategory)}. */
    public int selectedModule() {
        return this.module;
    }

    /** Whether the highlighted category's modules are open, and Up and Down move through them. */
    public boolean isOpen() {
        return this.open;
    }

    /** Moves the menu one step. Returns false for a key that means nothing where the selection is. */
    public boolean press(final Nav nav) {
        List<ModuleCategory> categories = this.categories();
        if (categories.isEmpty()) {
            return false;
        }

        this.category = Math.floorMod(this.category, categories.size());
        if (!this.open) {
            switch (nav) {
                case UP -> this.category = Math.floorMod(this.category - 1, categories.size());
                case DOWN -> this.category = Math.floorMod(this.category + 1, categories.size());
                case RIGHT, ENTER -> {
                    this.open = true;
                    this.module = 0;
                }
                case LEFT -> {
                    return false;
                }
            }

            return true;
        }

        List<Module> offered = this.modules(categories.get(this.category));
        this.module = Math.floorMod(this.module, offered.size());
        switch (nav) {
            case UP -> this.module = Math.floorMod(this.module - 1, offered.size());
            case DOWN -> this.module = Math.floorMod(this.module + 1, offered.size());
            case LEFT -> this.open = false;
            case RIGHT, ENTER -> offered.get(this.module).toggle();
        }

        return true;
    }

    /** Switched back on, the menu starts at the categories rather than inside whatever was open. */
    @Override
    protected void onDisable() {
        this.open = false;
    }

    /** Called by a presentation for every frame it draws the menu; see the class comment. */
    public void markShown() {
        this.lastShown = System.nanoTime();
    }

    @EventTarget
    public void onKeyPress(final EventKeyPress event) {
        if (event.getAction() == EventKeyPress.Action.RELEASE || event.getKeyType() != InputConstants.Type.KEYSYM) {
            return;
        }

        Nav nav = nav(event.getKeyCode());
        // A held Up or Down runs through the list; a held Right or Enter must not switch a module over and over.
        if (nav == null || event.getAction() == EventKeyPress.Action.REPEAT && nav != Nav.UP && nav != Nav.DOWN) {
            return;
        }

        if (System.nanoTime() - this.lastShown > SHOWN_NANOS || this.boundElsewhere(event.getKey())) {
            return;
        }

        this.press(nav);
    }

    static @Nullable Nav nav(final int keyCode) {
        return switch (keyCode) {
            case InputConstants.KEY_UP -> Nav.UP;
            case InputConstants.KEY_DOWN -> Nav.DOWN;
            case InputConstants.KEY_LEFT -> Nav.LEFT;
            case InputConstants.KEY_RIGHT -> Nav.RIGHT;
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> Nav.ENTER;
            default -> null;
        };
    }

    private boolean boundElsewhere(final InputConstants.Key key) {
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (mapping.matches(key)) {
                return true;
            }
        }

        for (Module other : this.modules.all()) {
            if (other.getKeybind().isBound() && other.getKeybind().key().equals(key)) {
                return true;
            }
        }

        return false;
    }

    public float getBackground() {
        return this.background.get();
    }

    public boolean showsKeybinds() {
        return this.keybinds.get();
    }

    public boolean isAnimated() {
        return this.animations.get();
    }
}
