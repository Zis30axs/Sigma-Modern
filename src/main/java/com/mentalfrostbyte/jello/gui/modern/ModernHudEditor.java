package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Hold either Alt to edit HUD positions through a non-pausing screen and vanilla's input ownership. */
public final class ModernHudEditor extends Screen {
    private static final Map<String, Widget> WIDGETS = new LinkedHashMap<>();
    private static final HudLayout.Frame FRAME = new HudLayout.Frame();
    private static @Nullable GuiGraphicsExtractor layoutGraphics;
    private static @Nullable ModernHudEditor active;
    private static boolean blockedUntilAltRelease;
    private final ClientLevel level;
    private @Nullable String dragging;
    private float grabX, grabY;
    private boolean dirty;

    private record Widget(String id, String label, float defaultX, float defaultY, float width, float height,
                          float viewportWidth, float viewportHeight, boolean following, HudLayout.Rect bounds) {}

    private ModernHudEditor(ClientLevel level) {
        super(Component.literal("HUD Layout"));
        this.level = level;
    }

    public static boolean isEditing() { return Minecraft.getInstance().gui.screen() instanceof ModernHudEditor; }
    public static boolean allowsHud() { return Minecraft.getInstance().gui.screen() == null || isEditing(); }
    public static boolean hasPosition(String id) { return layout().hasPosition(id); }
    public static boolean hasCustom(String id) { return hasPosition(id); }
    /** A gameplay chord can take over from held Alt without reopening the editor until Alt is released. */
    public static void finishForShortcut() {
        if (active != null) active.closeEditor(true);
    }
    private static HudLayout layout() { return Client.getInstance().getHudLayout(); }

    public static void beginFrame(GuiGraphicsExtractor graphics) {
        layoutGraphics = graphics;
        WIDGETS.clear();
        FRAME.clear();
    }

    /** Fixed, non-draggable UI still leaves space for automatically positioned HUD widgets. */
    static void reserve(GuiGraphicsExtractor g, String id, float x, float y, float width, float height) {
        if (layoutGraphics != g) beginFrame(g);
        FRAME.reserve(id, new HudLayout.Rect(x, y, width, height, 0, 0));
    }

    public static HudLayout.Rect place(GuiGraphicsExtractor g, String id, String label, float defaultX, float defaultY,
                                       float width, float height) {
        return place(g, id, label, defaultX, defaultY, width, height, false);
    }

    public static HudLayout.Rect placeFollowing(GuiGraphicsExtractor g, String id, String label, float defaultX, float defaultY,
                                                float width, float height) {
        return place(g, id, label, defaultX, defaultY, width, height, true);
    }

    private static HudLayout.Rect place(GuiGraphicsExtractor g, String id, String label, float defaultX, float defaultY,
                                        float width, float height, boolean following) {
        if (layoutGraphics != g) beginFrame(g);
        HudLayout.Rect result = layout().resolve(id, defaultX, defaultY, width, height, g.guiWidth(), g.guiHeight(), following);
        // Moving world anchors must neither drift away from their target nor push the screen HUD around.
        if (!following) result = FRAME.place(id, result, g.guiWidth(), g.guiHeight(), !hasPosition(id));
        if (isEditing()) {
            WIDGETS.put(id, new Widget(id, label, defaultX, defaultY, width, height, g.guiWidth(), g.guiHeight(), following, result));
        }
        return result;
    }

    /** Called before module keybinds. GLFW still receives Alt+Tab normally; no OS key is captured. */
    public static boolean handleKey(KeyEvent event, int action) {
        int key = event.key();
        if (key != GLFW.GLFW_KEY_LEFT_ALT && key != GLFW.GLFW_KEY_RIGHT_ALT) return false;
        Minecraft mc = Minecraft.getInstance();
        int other = key == GLFW.GLFW_KEY_LEFT_ALT ? GLFW.GLFW_KEY_RIGHT_ALT : GLFW.GLFW_KEY_LEFT_ALT;
        boolean held = action != GLFW.GLFW_RELEASE || InputConstants.isKeyDown(mc.getWindow(), other);
        if (!held) {
            blockedUntilAltRelease = false;
            if (active != null) {
                active.closeEditor(false);
                return true;
            }
        } else if (action == GLFW.GLFW_PRESS && !blockedUntilAltRelease && active == null
                && mc.level != null && mc.player != null && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed()
                && mc.gui.screen() == null && mc.gui.overlay() == null
                && Client.getInstance().getClientModeManager().get() == ClientMode.SIGMA_MODERN) {
            mc.gui.setScreen(new ModernHudEditor(mc.level));
            return true;
        }
        return active != null;
    }

    /** Called once per frame as well as by the screen tick, so mode/world changes cannot leave input captured. */
    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        boolean alt = altDown(mc);
        if (!alt) blockedUntilAltRelease = false;
        if (active != null && (mc.gui.screen() != active || mc.level != active.level || mc.player == null
                || mc.player.isDeadOrDying() || mc.player.isSleeping()
                || !mc.isWindowActive() || mc.gui.overlay() != null || !alt
                || Client.getInstance().getClientModeManager().get() != ClientMode.SIGMA_MODERN)) {
            active.closeEditor(alt);
        }
    }

    /** GLFW's focus callback updates Window.focused first, preventing setScreen(null) from grabbing an inactive window. */
    public static void onWindowFocusChanged(boolean focused) {
        if (!focused && active != null) active.closeEditor(true);
    }

    private static boolean altDown(Minecraft mc) {
        return InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
    }

    @Override public void added() {
        active = this;
        WIDGETS.clear();
        minecraft.mouseHandler.sigma$resetHudEditorInput();
        KeyMapping.releaseAll();
        if (minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
            if (minecraft.player != null && minecraft.player.isUsingItem()) minecraft.gameMode.releaseUsingItem(minecraft.player);
        }
    }

    @Override public void removed() {
        save();
        dragging = null;
        if (active == this) active = null;
        blockedUntilAltRelease = altDown(minecraft);
        minecraft.mouseHandler.sigma$resetHudEditorInput();
        KeyMapping.releaseAll();
    }

    private void closeEditor(boolean blockHeldAlt) {
        blockedUntilAltRelease = blockHeldAlt;
        save();
        dragging = null;
        if (minecraft.gui.screen() == this) minecraft.gui.setScreen(null);
        else if (active == this) active = null;
        if (minecraft.gui.overlay() != null) minecraft.mouseHandler.releaseMouse();
        minecraft.mouseHandler.sigma$resetHudEditorInput();
        KeyMapping.releaseAll();
    }

    private void save() {
        if (dirty) {
            dirty = false;
            Client.getInstance().saveConfig();
        }
    }

    @Override public void tick() { update(); }
    @Override public void resize(int width, int height) {
        dragging = null;
        save();
        super.resize(width, height);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void onClose() { closeEditor(true); }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {}

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) onClose();
        return true;
    }
    @Override public boolean keyReleased(KeyEvent event) { return true; }
    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) { return true; }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ArrayList<Widget> ordered = new ArrayList<>(WIDGETS.values());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Widget widget = ordered.get(i);
            if (!widget.bounds.contains(event.x(), event.y())) continue;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                if (layout().reset(widget.id)) { dirty = true; save(); }
            } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                dragging = widget.id;
                grabX = (float)event.x() - widget.bounds.x();
                grabY = (float)event.y() - widget.bounds.y();
            }
            break;
        }
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        Widget widget = dragging == null ? null : WIDGETS.get(dragging);
        if (widget != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            layout().move(widget.id, (float)event.x() - grabX, (float)event.y() - grabY, widget.defaultX, widget.defaultY,
                    widget.width, widget.height, widget.viewportWidth, widget.viewportHeight, widget.following);
            dirty = true;
        }
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) { dragging = null; save(); }
        return true;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        for (Widget widget : WIDGETS.values()) {
            HudLayout.Rect box = widget.bounds;
            boolean hover = box.contains(mouseX, mouseY) || widget.id.equals(dragging);
            int x = Math.round(box.x()), y = Math.round(box.y()), right = Math.round(box.x() + box.width()), bottom = Math.round(box.y() + box.height());
            int color = hover ? 0xFFB6E1FF : 0x889ACAF2;
            g.fill(x - 1, y - 1, right + 1, y, color);
            g.fill(x - 1, bottom, right + 1, bottom + 1, color);
            g.fill(x - 1, y, x, bottom, color);
            g.fill(right, y, right + 1, bottom, color);
            if (hover) ModernTypography.draw(g, widget.label, x + 3, Math.max(2, y - 13), 0xFFE8F5FF, true);
        }
        String hint = "按住 Alt 编辑 · 左键拖动 · 右键重置 · 松开保存";
        int w = ModernTypography.width(hint) + 22;
        int x = Math.max(4, (g.guiWidth() - w) / 2), y = Math.max(4, g.guiHeight() - 30);
        ModernStyle.rounded(g, x, y, w, 22, 8, 0xDD141C2C);
        ModernTypography.draw(g, hint, x + 11, y + 5, 0xFFE6F1FF, false);
    }
}
