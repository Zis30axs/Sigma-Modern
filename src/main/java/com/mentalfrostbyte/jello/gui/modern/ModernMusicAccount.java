package com.mentalfrostbyte.jello.gui.modern;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.netease.NeteaseAccount;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The music window's account page: signing in to NetEase by scanning a QR code with the NetEase app, and, once
 * signed in, who it is and whether VIP songs play in full.
 *
 * <p>The code is drawn as crisp dark modules on an opaque white card with a four-module quiet zone, at a whole
 * number of GUI pixels per module - whatever the window's theme or fade, a phone must be able to read it. Too
 * small a window for that shows a message instead of an unreadable code. A code is fetched as soon as the page
 * is shown; one that ran out is refreshed with a click.</p>
 */
final class ModernMusicAccount {
    private static final int QUIET = 4, MIN_MODULE = 2;
    private static final int INK = 0xFF0A1420, PAPER = 0xFFFFFFFF;

    private record Layout(int rx, int rw, int titleY, int top, int bottom) {}

    private final Map<String, Float> anim = new HashMap<>();
    private @Nullable String encodedFor;
    private @Nullable BitMatrix matrix;
    private ModernMusicView.@Nullable Box card, signOut, retryProfile;
    private float dt, time;

    /** The page inside the window's content area: a title, then the code (or the account) below it. */
    private static Layout layout(ModernMusicView.Box c) {
        return new Layout(c.x(), c.w(), c.y(), c.y() + 20, c.y() + c.h());
    }

    private static @Nullable NeteaseAccount account() {
        return Client.getInstance().getMusicLibrary().account();
    }

    // --- rendering ------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor g, ModernMusicView.Box c, int mx, int my, float dt, float time) {
        this.dt = dt;
        this.time = time;
        this.card = this.signOut = this.retryProfile = null;
        Layout l = layout(c);
        int x = c.x(), w = c.w();
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernText.t("NetEase account", "网易云账号"), l.rx(), l.titleY() + 2F, 1F, 0xFFF0F6FC);
        NeteaseAccount account = account();
        if (account == null) {
            centered(g, ModernText.t("No account in the offline preview", "离线预览没有账号"), x, w, (l.top() + l.bottom()) / 2F, 0.9F, 0xFF9DBCD0);
            return;
        }
        NeteaseAccount.State state = account.state();
        if (state.phase() == NeteaseAccount.Phase.SIGNED_IN) {
            drawSignedIn(g, l, x, w, account, mx, my);
        } else {
            // A code is wanted as soon as the page is on show.
            if (state.phase() == NeteaseAccount.Phase.IDLE) {
                account.startLogin();
                state = account.state();
            }
            drawCode(g, l, x, w, state, mx, my);
        }
    }

    /** The QR code on its card, what's happening under it, and (room permitting) two lines on what signing in does. */
    private void drawCode(GuiGraphicsExtractor g, Layout l, int x, int w, NeteaseAccount.State state, int mx, int my) {
        NeteaseAccount.Phase phase = state.phase();
        String status = switch (phase) {
            case FETCHING, IDLE -> ModernText.t("Getting a code...", "正在获取二维码…");
            case WAITING -> state.lapsed() ? ModernText.t("Signed out (expired) - scan again", "登录已过期 · 请重新扫码")
                : ModernText.t("Scan with the NetEase Cloud Music app", "用网易云音乐 App 扫码登录");
            case SCANNED -> ModernText.t("Scanned - confirm on your phone", "已扫码 · 请在手机上确认");
            case EXPIRED -> ModernText.t("The code expired", "二维码已过期");
            case DENIED -> ModernText.t("NetEase refused the sign-in (security check)", "网易云拒绝了本次登录（安全校验）");
            case FAILED -> ModernText.t("Couldn't get a code - check the network", "无法获取二维码 · 请检查网络");
            case SIGNED_IN -> "";
        };
        int statusColor = phase == NeteaseAccount.Phase.DENIED || phase == NeteaseAccount.Phase.FAILED ? ModernRows.BAD
            : phase == NeteaseAccount.Phase.SCANNED ? ModernRows.GOOD
            : state.lapsed() ? ModernRows.FAIR : 0xFFD3E4F0;

        int space = l.bottom() - l.top();
        int statusH = 16, hintsH = 26;
        int size = Math.min(l.rw() - 8, space - statusH - hintsH - 8);
        boolean hints = true;
        if (size < 110) {
            hints = false;
            size = Math.min(l.rw() - 8, space - statusH - 6);
        }

        String qr = state.qrText();
        if (qr != null && !qr.equals(this.encodedFor)) {
            this.encodedFor = qr;
            this.matrix = encode(qr);
        }
        BitMatrix code = qr == null ? null : this.matrix;
        int modules = code == null ? 29 : code.getWidth();
        int module = size / (modules + QUIET * 2);
        if (module < MIN_MODULE) {
            // A code this small wouldn't scan; say so rather than draw one.
            String text = ModernText.t("Make the window taller to show the code", "窗口太小，请放大窗口以显示二维码");
            float cy = (l.top() + l.bottom()) / 2F - 6F;
            for (String line : ModernTypography.wrap(ModernTypography.Face.TEXT, text, 0.86F, l.rw(), 2)) {
                centered(g, line, x, w, cy, 0.86F, ModernRows.FAIR);
                cy += 11F;
            }
            return;
        }
        int cardSize = module * (modules + QUIET * 2);
        int cx = x + (w - cardSize) / 2, cy = l.top();
        ModernMusicView.Box card = this.card = new ModernMusicView.Box(cx, cy, cardSize, cardSize);
        ModernStyle.dropShadow(g, cx, cy + 3, cardSize, cardSize, 8, 0.7F);

        if (code == null || phase == NeteaseAccount.Phase.FETCHING) {
            ModernStyle.rounded(g, cx, cy, cardSize, cardSize, 8, 0x26FFFFFF);
            ModernMusicView.spinner(g, cx + cardSize / 2F, cy + cardSize / 2F, 7F, this.time, 0xFFCDEEFF);
        } else {
            ModernStyle.rounded(g, cx, cy, cardSize, cardSize, 8, PAPER);
            drawModules(g, code, cx + module * QUIET, cy + module * QUIET, module);
            boolean spent = phase == NeteaseAccount.Phase.EXPIRED || phase == NeteaseAccount.Phase.DENIED || phase == NeteaseAccount.Phase.FAILED;
            if (phase == NeteaseAccount.Phase.SCANNED) {
                // Scanned: the code steps back behind who scanned it.
                ModernStyle.rounded(g, cx, cy, cardSize, cardSize, 8, 0xEBFFFFFF);
                float mid = cy + cardSize / 2F;
                ModernIcons.draw(g, ModernIcons.Icon.CHECK, cx + cardSize / 2F - 9F, mid - 22F, 18F, 0xFF1F8FC7);
                String who = state.scanner() == null ? ModernText.t("Scanned", "已扫码") : state.scanner();
                who = ModernTypography.wrap(ModernTypography.Face.TEXT, who, 0.95F, cardSize - 16F, 1).getFirst();
                centered(g, who, x, w, mid + 2F, 0.95F, INK);
            } else if (spent) {
                float hover = animate("refresh", card.contains(mx, my) ? 1F : 0F, 14F);
                ModernStyle.rounded(g, cx, cy, cardSize, cardSize, 8, ModernStyle.mix(0xD90B1826, 0xE6123049, hover));
                float mid = cy + cardSize / 2F;
                ModernIcons.draw(g, ModernIcons.Icon.REFRESH, cx + cardSize / 2F - 8F, mid - 20F, 16F, 0xFFE3F2FA);
                centered(g, ModernText.t("Click for a new code", "点击刷新"), x, w, mid + 3F, 0.9F, 0xFFE3F2FA);
            }
        }

        float sy = cy + cardSize + 8F;
        for (String line : ModernTypography.wrap(ModernTypography.Face.TEXT, status, 0.86F, l.rw(), 1)) {
            centered(g, line, x, w, sy, 0.86F, statusColor);
        }
        if (hints && sy + 12F + 22F <= l.bottom()) {
            centered(g, ModernText.t("Signed in, VIP songs play in full", "登录后可完整播放 VIP 歌曲"), x, w, sy + 14F, 0.68F, 0xFF7F9CB2);
            centered(g, ModernText.t("The login stays on this computer", "登录信息只保存在本机"), x, w, sy + 24F, 0.68F, 0xFF7F9CB2);
        }
    }

    /** Dark modules, one fill per horizontal run so a code is a few hundred quads rather than a thousand. */
    private static void drawModules(GuiGraphicsExtractor g, BitMatrix code, int x0, int y0, int module) {
        int n = code.getWidth();
        for (int row = 0; row < n; row++) {
            int col = 0;
            while (col < n) {
                if (!code.get(col, row)) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < n && code.get(col, row)) col++;
                ModernStyle.fill(g, x0 + start * module, y0 + row * module, x0 + col * module, y0 + (row + 1) * module, INK);
            }
        }
    }

    /** The QR modules for {@code text}, without a quiet zone (the card draws its own). */
    static @Nullable BitMatrix encode(String text) {
        try {
            // Level L: an on-screen code isn't scuffed, and fewer modules means bigger ones.
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L));
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }

    private void drawSignedIn(GuiGraphicsExtractor g, Layout l, int x, int w, NeteaseAccount account, int mx, int my) {
        CompletableFuture<NeteaseAccount.Profile> pending = account.profile();
        NeteaseAccount.Profile profile = pending.isDone() && !pending.isCompletedExceptionally() ? pending.join() : null;
        boolean failed = pending.isCompletedExceptionally();

        int avatar = 52;
        float ax = x + (w - avatar) / 2F, ay = l.top() + 8F;
        ModernStyle.halo(g, Math.round(ax), Math.round(ay), avatar, avatar, avatar / 2, ModernStyle.GLOW, 0.35F);
        if (profile != null && profile.avatarUrl() != null) {
            Track face = new Track("netease-avatar:" + profile.userId(), profile.nickname(), "", "", "", 0L, profile.avatarUrl() + "?param=128y128");
            ModernCovers.draw(g, face, ax, ay, avatar, 0.5F, 0xFFFFFFFF);
        } else {
            ModernStyle.rounded(g, Math.round(ax), Math.round(ay), avatar, avatar, avatar / 2, 0xFF1B3550);
            ModernIcons.draw(g, ModernIcons.Icon.PERSON, ax + avatar / 2F - 11F, ay + avatar / 2F - 11F, 22F, 0xFFB9D7EA);
        }

        float ny = ay + avatar + 10F;
        String name = profile != null && !profile.nickname().isEmpty() ? profile.nickname() : ModernText.t("Signed in", "已登录");
        name = ModernTypography.wrap(ModernTypography.Face.DISPLAY, name, 1.35F, l.rw(), 1).getFirst();
        centered(g, ModernTypography.Face.DISPLAY, name, x, w, ny, 1.35F, 0xFFF0F6FC);

        float line = ny + 22F;
        if (profile != null) {
            String vip = profile.vip() ? ModernText.t("VIP - VIP songs play in full", "VIP 会员 · VIP 歌曲可完整播放")
                : ModernText.t("No VIP - VIP songs stay 30 s previews", "非会员 · VIP 歌曲仍只能试听");
            centered(g, ModernTypography.wrap(ModernTypography.Face.TEXT, vip, 0.82F, l.rw(), 1).getFirst(), x, w, line, 0.82F,
                profile.vip() ? ModernRows.GOOD : ModernRows.FAIR);
        } else if (failed) {
            String text = ModernText.t("Signed in - couldn't read the profile, click to retry", "已登录 · 无法读取账号资料，点击重试");
            text = ModernTypography.wrap(ModernTypography.Face.TEXT, text, 0.82F, l.rw(), 1).getFirst();
            float tw = ModernTypography.width(ModernTypography.Face.TEXT, text, 0.82F);
            this.retryProfile = new ModernMusicView.Box(Math.round(x + (w - tw) / 2F) - 4, Math.round(line) - 3, Math.round(tw) + 8, 14);
            centered(g, text, x, w, line, 0.82F, ModernRows.FAIR);
        } else {
            ModernMusicView.spinner(g, x + w / 2F, line + 4F, 5F, this.time, 0xFFB6E5FF);
        }

        int bw = 92, bh = 22;
        int by = Math.min(l.bottom() - bh - 14, Math.round(line + 24F));
        ModernMusicView.Box button = this.signOut = new ModernMusicView.Box(x + (w - bw) / 2, by, bw, bh);
        float hover = animate("sign-out", button.contains(mx, my) ? 1F : 0F, 16F);
        ModernStyle.rounded(g, button.x(), button.y(), button.w(), button.h(), 11, ModernStyle.mix(0x33CDEBFF, 0x66FF7A86, hover));
        ModernStyle.rounded(g, button.x() + 1, button.y() + 1, button.w() - 2, button.h() - 2, 10, ModernStyle.mix(0xF0122536, 0xF0301C26, hover));
        centered(g, ModernText.t("Sign out", "退出登录"), x, w, button.y() + 6.5F, 0.9F, ModernStyle.mix(0xFFD8E8F3, 0xFFFFD6DA, hover));
        if (by + bh + 12 <= l.bottom()) {
            centered(g, ModernText.t("Saved in sigma5/netease_cookie.dat", "登录信息保存在 sigma5/netease_cookie.dat"), x, w, by + bh + 8F, 0.62F, 0xFF6F8CA2);
        }
    }

    private static void centered(GuiGraphicsExtractor g, String text, int x, int w, float y, float scale, int color) {
        centered(g, ModernTypography.Face.TEXT, text, x, w, y, scale, color);
    }

    private static void centered(GuiGraphicsExtractor g, ModernTypography.Face face, String text, int x, int w, float y, float scale, int color) {
        ModernTypography.draw(g, face, text, x + (w - ModernTypography.width(face, text, scale)) / 2F, y, scale, color);
    }

    private float animate(String key, float target, float speed) {
        float current = this.anim.getOrDefault(key, target);
        float next = ModernStyle.smooth(current, target, this.dt, speed);
        this.anim.put(key, next);
        return next;
    }

    // --- input ------------------------------------------------------------------------------------

    /** Uses the last frame's card and buttons - the page draws before it can be clicked. */
    void mouseClicked(ModernMusicView.Box c, double mx, double my, int button) {
        if (button != 0) return;
        NeteaseAccount account = account();
        if (account == null) return;
        NeteaseAccount.Phase phase = account.state().phase();
        if (this.card != null && this.card.contains(mx, my)
            && (phase == NeteaseAccount.Phase.EXPIRED || phase == NeteaseAccount.Phase.DENIED || phase == NeteaseAccount.Phase.FAILED)) {
            account.startLogin();
        } else if (phase == NeteaseAccount.Phase.SIGNED_IN && this.signOut != null && this.signOut.contains(mx, my)) {
            account.signOut();
        } else if (phase == NeteaseAccount.Phase.SIGNED_IN && this.retryProfile != null && this.retryProfile.contains(mx, my)) {
            account.refreshProfile();
        }
    }
}
