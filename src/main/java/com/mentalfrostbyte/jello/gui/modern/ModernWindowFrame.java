package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mojang.blaze3d.platform.Window;
import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.HMONITOR;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Windows client-area frame. Keeps the OS resize, drag, snap and maximize behavior. */
public final class ModernWindowFrame {
    private interface Api extends StdCallLibrary {
        Api INSTANCE = Native.load("user32", Api.class);
        Pointer SetWindowLongPtrW(HWND hwnd, int index, Pointer procedure);
        LRESULT CallWindowProcW(Pointer previous, HWND hwnd, int message, WPARAM wp, LPARAM lp);
        boolean GetWindowRect(HWND hwnd, RECT rect);
        boolean GetClientRect(HWND hwnd, RECT rect);
        boolean GetCursorPos(POINT point);
        boolean ScreenToClient(HWND hwnd, POINT point);
        boolean IsZoomed(HWND hwnd);
        boolean ShowWindow(HWND hwnd, int command);
        boolean SetWindowPos(HWND hwnd, HWND after, int x, int y, int w, int h, int flags);
        HMONITOR MonitorFromWindow(HWND hwnd, int flags);
        boolean GetMonitorInfoW(HMONITOR monitor, WinUser.MONITORINFO info);
    }
    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DwmSetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
        int DwmGetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }
    // Rounded corners come from DWM (Windows 11; Windows 10 ignores the attribute and stays square), not from
    // a window region: a region also takes the window off DWM's frame rendering, and its corners are jagged.
    private static final int DWMWA_NCRENDERING_POLICY = 2;
    private static final int DWMNCRP_USEWINDOWSTYLE = 0;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWCP_DEFAULT = 0;
    private static final int DWMWCP_ROUND = 2;
    // Caption geometry in GUI units. WM_NCHITTEST and render() both derive from these, so the drawn buttons
    // are exactly the areas Windows treats as minimize/maximize/close.
    static final int CAPTION_H = 22;
    static final int BUTTON_W = 34;
    private static final float[] hover = new float[3];
    private static long lastRender;
    private static HWND hwnd;
    private static Pointer previous;
    // Keep the native callback strongly reachable until it has been uninstalled.
    private static WinUser.WindowProc procedure;
    private static long windowHandle;
    private static int guiScale = 1;
    private static boolean interactive;
    private static boolean oldMaximized;
    private static boolean failed;
    private ModernWindowFrame() {}

    public static void close() {
        if (previous == null) return;
        Api.INSTANCE.SetWindowLongPtrW(hwnd, -4, previous);
        previous = null;
        procedure = null;
        windowHandle = 0;
    }

    public static void update(Window current) {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8 || failed) return;
        Screen screen = Minecraft.getInstance().gui.screen();
        // ModeSelectScreen is the shared, non-Modern selector: it must always present the native frame,
        // regardless of the last-selected ClientMode, so leaving Modern for it restores Win10/11 chrome.
        boolean wanted = Client.getInstance().getClientModeManager().get() == ClientMode.SIGMA_MODERN
            && !current.isFullscreen()
            && !(screen instanceof ModeSelectScreen);
        updateFrame(current.handle(), wanted, current.getGuiScale(), screen != null);
        if (DEBUG_DWM_STATE) logDwmState(current.handle(), screen);
    }

    private static final boolean DEBUG_DWM_STATE = Boolean.getBoolean("sigma.debug.frameDwmState");
    private static int loggedNcRendering = -1;

    /** Debug: logs whenever DWM starts or stops rendering this window's frame (off = the Windows 7 "Basic" look). */
    private static void logDwmState(long handle, Screen screen) {
        HWND window = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(handle)));
        IntByReference enabled = new IntByReference();
        int result = Dwm.INSTANCE.DwmGetWindowAttribute(window, 1 /* DWMWA_NCRENDERING_ENABLED */, enabled, 4);
        int value = result == 0 ? enabled.getValue() : -2;
        if (value != loggedNcRendering) {
            loggedNcRendering = value;
            org.slf4j.LoggerFactory.getLogger("Sigma/Frame").info("Sigma debug: DWM frame rendering {} (custom frame {}, screen {})",
                value == 1 ? "ON" : value == 0 ? "OFF" : "unknown", previous != null ? "installed" : "native",
                screen == null ? "none" : screen.getClass().getSimpleName());
        }
    }

    static void updateFrame(long handle, boolean wanted, int scale, boolean screenOpen) {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8 || failed) return;
        guiScale = Math.max(1, scale);
        interactive = screenOpen;
        if (wanted && previous == null) {
            windowHandle = handle;
            hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(handle)));
            procedure = ModernWindowFrame::message;
            previous = Api.INSTANCE.SetWindowLongPtrW(hwnd, -4, CallbackReference.getFunctionPointer(procedure));
            if (previous == null) { failed = true; procedure = null; return; }
            dwm(DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND);
            frameChanged();
        } else if (!wanted && previous != null) {
            Api.INSTANCE.SetWindowLongPtrW(hwnd, -4, previous);
            previous = null;
            procedure = null;
            // Hand the frame back to DWM exactly as a plain window has it.
            dwm(DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DEFAULT);
            dwm(DWMWA_NCRENDERING_POLICY, DWMNCRP_USEWINDOWSTYLE);
            frameChanged();
            oldMaximized = false;
        }
        if (wanted && previous != null) {
            // Only the caption's maximize/restore glyph depends on this; the frame itself needs no per-size work.
            oldMaximized = Api.INSTANCE.IsZoomed(hwnd);
        }
    }

    private static void dwm(int attribute, int value) {
        try {
            Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, new IntByReference(value), 4);
        } catch (Throwable ignored) {
            // Older systems without the attribute (or dwmapi) simply keep their default look.
        }
    }

    private static void frameChanged() {
        Api.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020);
    }

    private static LRESULT message(HWND handle, int msg, WPARAM wp, LPARAM lp) {
        if (msg == 0x0083) { // WM_NCCALCSIZE: client content covers the former caption.
            if (wp.longValue() != 0 && Api.INSTANCE.IsZoomed(handle)) {
                WinUser.MONITORINFO info = new WinUser.MONITORINFO();
                if (Api.INSTANCE.GetMonitorInfoW(Api.INSTANCE.MonitorFromWindow(handle, 2), info)) {
                    Pointer rect = new Pointer(lp.longValue());
                    rect.setInt(0, info.rcWork.left); rect.setInt(4, info.rcWork.top);
                    rect.setInt(8, info.rcWork.right); rect.setInt(12, info.rcWork.bottom);
                }
            }
            return new LRESULT(0);
        }
        if (msg == 0x0084 && interactive) { // WM_NCHITTEST
            // Client coordinates: when maximized the window rect overhangs the monitor by the invisible
            // resize border, but the client area (what we draw) starts exactly at the screen edge.
            POINT point = new POINT((short)(lp.longValue() & 0xFFFF), (short)((lp.longValue() >> 16) & 0xFFFF));
            Api.INSTANCE.ScreenToClient(handle, point);
            RECT client = new RECT();
            Api.INSTANCE.GetClientRect(handle, client);
            int x = point.x, y = point.y, w = client.right, h = client.bottom;
            double scale = guiScale;
            int edge = Math.max(5, (int)(3 * scale));
            if (!Api.INSTANCE.IsZoomed(handle)) {
                boolean left = x < edge, right = x >= w - edge, top = y < edge, bottom = y >= h - edge;
                if (top) return new LRESULT(left ? 13 : right ? 14 : 12);
                if (bottom) return new LRESULT(left ? 16 : right ? 17 : 15);
                if (left || right) return new LRESULT(left ? 10 : 11);
            }
            if (y < CAPTION_H * scale) {
                if (x >= w - BUTTON_W * scale) return new LRESULT(20);
                if (x >= w - 2 * BUTTON_W * scale) return new LRESULT(9);
                if (x >= w - 3 * BUTTON_W * scale) return new LRESULT(8);
                // A screen's own widget up here (Create World's and Statistics' tab bars sit at y = 0) must stay
                // clickable rather than become a drag handle. The window proc runs inside glfwPollEvents on the
                // render thread, so reading the current screen here is safe.
                Screen screen = Minecraft.getInstance().gui.screen();
                if (screen != null && screen.getChildAt(x / scale, y / scale).isPresent()) return new LRESULT(1);
                return new LRESULT(2);
            }
            return new LRESULT(1);
        }
        if (msg == 0x00A1) { // WM_NCLBUTTONDOWN; custom transparent caption controls.
            int hit = wp.intValue();
            if (hit == 20) { GLFW.glfwSetWindowShouldClose(windowHandle, true); return new LRESULT(0); }
            if (hit == 9) { Api.INSTANCE.ShowWindow(handle, Api.INSTANCE.IsZoomed(handle) ? 9 : 3); return new LRESULT(0); }
            if (hit == 8) { Api.INSTANCE.ShowWindow(handle, 6); return new LRESULT(0); }
        }
        if (msg == 0x0086) { // WM_NCACTIVATE
            // DefWindowProc has to see this (and WM_NCPAINT): swallowing them makes Windows hand frame painting
            // to the app and switch DWM's frame rendering off for good, so the native frame came back as the
            // Windows 7 "Basic" one. lParam -1 still keeps it from painting a caption over ours.
            return Api.INSTANCE.CallWindowProcW(previous, handle, msg, wp, new LPARAM(-1));
        }
        return Api.INSTANCE.CallWindowProcW(previous, handle, msg, wp, lp);
    }

    /**
     * The custom caption: a soft top scrim so it reads over any screen, the window title with a Σ mark on
     * the left, and minimize / maximize-restore / close on the right, each with a hover fade (close turns
     * red). Only drawn while a screen is open - the same condition under which the caption is hit-tested.
     */
    /** GUI pixels at the top of the window that the custom caption covers (0 when it isn't installed). */
    public static int reservedTop() {
        return previous == null ? 0 : CAPTION_H;
    }

    public static void render(GuiGraphicsExtractor g) {
        if (previous == null) return;
        long now = System.nanoTime();
        float dt = lastRender == 0 ? 0F : Math.min(0.05F, (now - lastRender) / 1_000_000_000F);
        lastRender = now;
        int w = g.guiWidth();
        g.nextStratum();
        ModernStyle.fillGradient(g, 0, 0, w, CAPTION_H + 12, 0x70040C15, 0x00040C15);

        int hovered = hoveredButton();
        for (int i = 0; i < 3; i++) {
            hover[i] = ModernStyle.smooth(hover[i], hovered == i ? 1F : 0F, dt, 18F);
            int x = w - (3 - i) * BUTTON_W;
            boolean close = i == 2;
            if (hover[i] > 0.01F) {
                int base = close ? 0xE24556 : 0xFFFFFF;
                int alpha = Math.round((close ? 235 : 44) * hover[i]);
                ModernStyle.fill(g, x, 0, x + BUTTON_W, CAPTION_H, alpha << 24 | base);
            }
            ModernIcons.Icon icon = i == 0 ? ModernIcons.Icon.MINIMIZE
                : i == 1 ? (oldMaximized ? ModernIcons.Icon.RESTORE : ModernIcons.Icon.MAXIMIZE)
                : ModernIcons.Icon.CLOSE;
            int color = ModernStyle.mix(0xE8DCEBF5, 0xFFFFFFFF, hover[i]);
            ModernIcons.draw(g, icon, x + (BUTTON_W - 12) / 2F, (CAPTION_H - 12) / 2F, 12F, color);
        }

        ModernIcons.draw(g, ModernIcons.Icon.SIGMA, 9F, 5F, 12F, 0xFFBFE8FF);
        String title = Minecraft.getInstance().getWindow().getTitle();
        int room = w - 3 * BUTTON_W - 16 - 27;
        // Vanilla screens put widgets (a tab bar, say) up in this strip; the title yields to the first one it meets.
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().gui.screen();
        if (screen != null) {
            for (int x = 27; x < 27 + room; x += 4) {
                if (screen.getChildAt(x, CAPTION_H / 2).isPresent()) {
                    room = x - 27 - 10;
                    break;
                }
            }
        }
        if (title != null && !title.isEmpty() && room > 24) {
            ModernTypography.draw(g, ModernTypography.ellipsize(title, room), 27, 7, 0xE0DCEBF5, false);
        }
    }

    /**
     * Which caption button the OS cursor is over: 0 minimize, 1 maximize, 2 close, -1 none. GLFW can't
     * tell us - over those buttons WM_NCHITTEST reports non-client areas, where Windows stops sending the
     * client mouse-move messages GLFW's cursor position is built from.
     */
    private static int hoveredButton() {
        if (hwnd == null) return -1;
        POINT point = new POINT();
        if (!Api.INSTANCE.GetCursorPos(point) || !Api.INSTANCE.ScreenToClient(hwnd, point)) return -1;
        RECT client = new RECT();
        if (!Api.INSTANCE.GetClientRect(hwnd, client)) return -1;
        if (point.x < 0 || point.y < 0 || point.x >= client.right || point.y >= CAPTION_H * guiScale) return -1;
        int fromRight = (client.right - point.x) / Math.max(1, BUTTON_W * guiScale);
        return fromRight == 0 ? 2 : fromRight == 1 ? 1 : fromRight == 2 ? 0 : -1;
    }
}
