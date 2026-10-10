package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.gui.modern.ModernStyle;
import com.mentalfrostbyte.jello.gui.modern.ModernTypography;
import com.mentalfrostbyte.jello.module.impl.combat.AimAssist;
import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimStyleModel;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Mouse-operated local practice scene; never spawns entities or sends attacks to a server. */
public final class AimAssistTrainingScreen extends Screen implements ModernBlurredBackdrop {
    private final @Nullable Screen parent;
    private final AimAssist module;
    private final AimStyleModel model;
    private final AimAssistTrainingSession session;
    private long lastFrame;
    private double mouseX, mouseY;
    private boolean dirty, error, closeAfterFailure;
    private String message = "";

    private record Box(int x, int y, int width, int height) {
        boolean contains(double x, double y) {
            return x >= this.x && x < this.x + this.width && y >= this.y && y < this.y + this.height;
        }
    }

    private record Layout(Box panel, Box arena, Box[] scenarios, Box start, Box save, Box reset, Box back) {}

    public AimAssistTrainingScreen(@Nullable Screen parent, AimAssist module) {
        super(Component.literal(tr("title")));
        this.parent = parent;
        this.module = Objects.requireNonNull(module, "module");
        this.model = module.getStyleModel();
        this.session = new AimAssistTrainingSession(this.model);
        String profileError = module.getProfileError();
        if (profileError != null && !profileError.isBlank()) {
            this.error = true;
            this.message = tr("load_failed");
        }
    }

    /** The Training setting is an action only for this exact registered module instance. */
    public static boolean isTrainingSetting(@Nullable Setting<?> setting) {
        return setting instanceof TextSetting && Client.getInstance().isStarted()
            && Client.getInstance().getModuleManager().get(AimAssist.class).getTrainingSetting() == setting;
    }

    public static String settingLabel() {
        AimAssist module = Client.getInstance().getModuleManager().get(AimAssist.class);
        return tr(module.getStyleModel().isReady() ? "open_ready" : "open");
    }

    public static AimAssistTrainingScreen open(@Nullable Screen parent) {
        return new AimAssistTrainingScreen(parent, Client.getInstance().getModuleManager().get(AimAssist.class));
    }

    @Override protected void init() { this.pause(); }
    @Override public boolean isPauseScreen() { return true; }

    private Layout layout() {
        int w = Math.max(1, Math.min(720, this.width - 16));
        int h = Math.max(1, Math.min(460, this.height - 16));
        int x = (this.width - w) / 2, y = (this.height - h) / 2;
        int inner = Math.max(1, w - 24), gap = 6;
        int quarter = Math.max(1, (inner - gap * 3) / 4);
        boolean dense = this.height < 340;
        Box[] scenarios = new Box[4];
        for (int i = 0; i < 4; i++) scenarios[i] = new Box(x + 12 + i * (quarter + gap), y + (dense ? 32 : 50), quarter, 34);
        int footer = y + h - 34;
        return new Layout(new Box(x, y, w, h), new Box(x + 12, y + (dense ? 73 : 92), inner, Math.max(1, h - (dense ? 163 : 198))), scenarios,
            new Box(x + 12, footer, quarter, 23), new Box(x + 12 + quarter + gap, footer, quarter, 23),
            new Box(x + 12 + (quarter + gap) * 2, footer, quarter, 23),
            new Box(x + 12 + (quarter + gap) * 3, footer, quarter, 23));
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float partialTick) {
        if (this.minecraft.level == null) { this.extractPanorama(g, partialTick); g.nextStratum(); }
        GuiVisuals.blurBackground(g);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partialTick) {
        Layout l = this.layout();
        Box p = l.panel();
        if (!this.minecraft.isWindowActive()) this.pause();
        boolean compact = this.width < 300 || this.height < 240;
        boolean dense = this.height < 340;
        if (compact) this.pause();
        this.advance(l.arena());
        ModernStyle.dropShadow(g, p.x(), p.y(), p.width(), p.height(), 11, 0.85F);
        ModernStyle.rounded(g, p.x(), p.y(), p.width(), p.height(), 11, 0xF5E8F3FA);
        label(g, tr("title"), p.x() + 12, p.y() + 11, p.width() - 24, ModernStyle.INK);
        if (!dense) label(g, tr("subtitle"), p.x() + 12, p.y() + 29, p.width() - 24, ModernStyle.INK_MUTED);
        if (compact) {
            label(g, tr("small_window"), p.x() + 12, p.y() + 57, p.width() - 24, ModernStyle.INK_MUTED);
            button(g, l.back(), tr("back"), x, y, false);
            return;
        }
        for (int i = 0; i < 4; i++) {
            Box s = l.scenarios()[i];
            boolean selected = this.session.scenario() == i;
            ModernStyle.rounded(g, s.x(), s.y(), s.width(), s.height(), 5,
                selected ? 0x553BAEE6 : s.contains(x, y) ? 0x303BAEE6 : 0x17243746);
            centered(g, tr("scenario." + i), s, s.y() + 4, selected ? ModernStyle.BLUE : ModernStyle.INK);
            centered(g, tr("scenario_samples", this.model.scenarioSamples(i), AimStyleModel.MIN_SAMPLES_PER_SCENARIO),
                s, s.y() + 19, ModernStyle.INK_MUTED);
        }
        this.drawArena(g, l.arena());
        int below = l.arena().y() + l.arena().height();
        label(g, dense && !this.message.isEmpty() ? this.message : tr("controls"),
            p.x() + 12, below + 7, p.width() - 24, dense && this.error ? 0xFFB34545 : ModernStyle.INK_MUTED);
        label(g, tr(this.model.isReady() ? "ready" : "collecting", this.model.sampleCount(), AimStyleModel.MIN_SAMPLES),
            p.x() + 12, below + 24, p.width() - 24, this.model.isReady() ? 0xFF287F64 : ModernStyle.BLUE);
        int trackY = below + 41;
        ModernStyle.rounded(g, p.x() + 12, trackY, p.width() - 24, 3, 1, 0x30243746);
        double total = Math.min(1.0, this.model.sampleCount() / (double) AimStyleModel.MIN_SAMPLES);
        for (int i = 0; i < 4; i++) total = Math.min(total,
            this.model.scenarioSamples(i) / (double) AimStyleModel.MIN_SAMPLES_PER_SCENARIO);
        if (total > 0) ModernStyle.rounded(g, p.x() + 12, trackY, Math.max(1, (int) ((p.width() - 24) * total)), 3, 1, ModernStyle.BLUE);
        if (!dense) label(g, this.message.isEmpty() ? tr("local_note") : this.message,
            p.x() + 12, below + 50, p.width() - 24, this.error ? 0xFFB34545 : ModernStyle.INK_MUTED);
        button(g, l.start(), tr(this.session.active() ? "pause" : "start"), x, y, this.session.active());
        button(g, l.save(), tr(this.dirty ? "save_dirty" : "save"), x, y, false);
        button(g, l.reset(), tr("reset"), x, y, false);
        button(g, l.back(), tr(this.closeAfterFailure ? "leave_unsaved" : "back"), x, y, false);
    }

    private void advance(Box arena) {
        long now = System.nanoTime();
        if (this.lastFrame != 0L && this.session.active()) {
            long before = this.model.sampleCount();
            this.session.update((now - this.lastFrame) / 1_000_000_000.0,
                this.yaw(arena, this.mouseX), this.pitch(arena, this.mouseY), arena.contains(this.mouseX, this.mouseY));
            if (this.model.sampleCount() != before) this.markDirty();
        }
        this.lastFrame = now;
    }

    private void drawArena(GuiGraphicsExtractor g, Box arena) {
        ModernStyle.rounded(g, arena.x(), arena.y(), arena.width(), arena.height(), 7, 0xFF122D3E);
        g.enableScissor(arena.x(), arena.y(), arena.x() + arena.width(), arena.y() + arena.height());
        try {
            int centerX = arena.x() + arena.width() / 2, centerY = arena.y() + arena.height() / 2;
            double scale = scale(arena);
            for (int angle = -40; angle <= 40; angle += 10) {
                int lineX = centerX + (int) (angle * scale), lineY = centerY + (int) (angle * scale);
                g.fill(lineX, arena.y(), lineX + 1, arena.y() + arena.height(), 0x194F91B1);
                g.fill(arena.x(), lineY, arena.x() + arena.width(), lineY + 1, 0x194F91B1);
            }
            if (this.session.scenario() == 3) {
                for (int i = 0; i < 3; i++) if (i != this.session.targetIndex()) {
                    this.target(g, arena, AimAssistTrainingSession.switchYaw(i), AimAssistTrainingSession.switchPitch(i), false);
                }
            }
            this.target(g, arena, this.session.targetYaw(), this.session.targetPitch(), true);
            label(g, tr("arena"), arena.x() + 8, arena.y() + 7, arena.width() / 2, ModernStyle.MUTED);
            String score = tr("score", this.session.hits(), this.session.misses());
            label(g, score, arena.x() + arena.width() / 2, arena.y() + 7, arena.width() / 2 - 8, ModernStyle.ACCENT);
            if (arena.contains(this.mouseX, this.mouseY)) {
                int mx = (int) this.mouseX, my = (int) this.mouseY;
                g.fill(mx - 7, my, mx - 2, my + 1, 0xFFFFFFFF);
                g.fill(mx + 3, my, mx + 8, my + 1, 0xFFFFFFFF);
                g.fill(mx, my - 7, mx + 1, my - 2, 0xFFFFFFFF);
                g.fill(mx, my + 3, mx + 1, my + 8, 0xFFFFFFFF);
                g.fill(mx, my, mx + 1, my + 1, 0xFFFFFFFF);
            }
            if (!this.session.active()) {
                g.fill(arena.x(), arena.y() + 25, arena.x() + arena.width(), arena.y() + arena.height(), 0x78122D3E);
                centered(g, tr("paused"), arena, centerY - 5, ModernStyle.TEXT);
            }
        } finally { g.disableScissor(); }
    }

    private void target(GuiGraphicsExtractor g, Box arena, double yaw, double pitch, boolean active) {
        double scale = scale(arena);
        int x = (int) (arena.x() + arena.width() / 2.0 + yaw * scale);
        int y = (int) (arena.y() + arena.height() / 2.0 + pitch * scale);
        int w = Math.max(3, (int) (4.0 * scale)), h = Math.max(5, (int) (6.0 * scale));
        if (active) ModernStyle.rounded(g, x - w / 2 - 3, y - h / 2 - 3, w + 6, h + 6, 4, 0x405FDAF5);
        ModernStyle.rounded(g, x - w / 2, y - h / 2, w, h, 3, active ? 0xFF79DCF4 : 0x405F94AB);
        if (active) g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF173747);
    }

    @Override public void mouseMoved(double x, double y) {
        this.mouseX = x;
        this.mouseY = y;
        if (!this.layout().arena().contains(x, y)) this.session.leaveArena();
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        this.mouseMoved(event.x(), event.y());
        return event.button() == 0;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (event.button() != 0) return false;
        this.mouseMoved(event.x(), event.y());
        Layout l = this.layout();
        if (l.back().contains(event.x(), event.y())) { this.onClose(); return true; }
        if (this.width < 300 || this.height < 240) return true;
        for (int i = 0; i < 4; i++) if (l.scenarios()[i].contains(event.x(), event.y())) {
            this.session.selectScenario(i);
            this.lastFrame = 0;
            return true;
        }
        if (l.start().contains(event.x(), event.y())) {
            if (this.session.active()) this.pause();
            else { this.session.start(); this.lastFrame = 0; }
        } else if (l.save().contains(event.x(), event.y())) { this.pause(); this.save(); }
        else if (l.reset().contains(event.x(), event.y())) {
            this.session.reset();
            this.markDirty();
            this.error = false;
            this.message = tr("reset_done");
        } else if (l.arena().contains(event.x(), event.y()) && this.minecraft.isWindowActive()) {
            this.advance(l.arena());
            long before = this.model.sampleCount();
            this.session.pressAttack(this.yaw(l.arena(), event.x()), this.pitch(l.arena(), event.y()));
            if (this.model.sampleCount() != before) this.markDirty();
        }
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) { this.session.releaseAttack(); return true; }
        return false;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) { this.onClose(); return true; }
        if (event.key() == GLFW.GLFW_KEY_SPACE && this.width >= 300 && this.height >= 240) {
            if (this.session.active()) this.pause();
            else { this.session.start(); this.lastFrame = 0; }
            return true;
        }
        return super.keyPressed(event);
    }

    private void markDirty() { this.dirty = true; this.closeAfterFailure = false; }
    private void pause() { this.session.pause(); this.lastFrame = 0; }

    private boolean save() {
        try {
            this.model.save(this.module.getProfilePath());
            this.module.profileSaved();
            this.dirty = false;
            this.error = false;
            this.closeAfterFailure = false;
            this.message = tr("saved");
            return true;
        } catch (IOException | SecurityException failure) {
            this.error = true;
            this.message = tr("save_failed");
            return false;
        }
    }

    @Override public void onClose() {
        this.pause();
        if (this.dirty && !this.closeAfterFailure && !this.save()) {
            this.closeAfterFailure = true;
            return;
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override public void removed() { this.pause(); super.removed(); }

    private static double scale(Box arena) { return Math.max(0.01, Math.min(arena.width() / 84.0, arena.height() / 44.0)); }
    private double yaw(Box arena, double x) { return (x - arena.x() - arena.width() / 2.0) / scale(arena); }
    private double pitch(Box arena, double y) { return (y - arena.y() - arena.height() / 2.0) / scale(arena); }
    private static String tr(String key, Object... args) { return Client.getInstance().getTranslations().get("aimassist.training." + key, args); }

    private static void label(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
        ModernTypography.draw(g, ModernTypography.ellipsize(text, Math.max(1, width)), x, y, color, false);
    }

    private static void centered(GuiGraphicsExtractor g, String text, Box box, int y, int color) {
        String shown = ModernTypography.fit(text, Math.max(1, box.width() - 8));
        ModernTypography.draw(g, shown, box.x() + (box.width() - ModernTypography.width(shown)) / 2, y, color, false);
    }

    private static void button(GuiGraphicsExtractor g, Box box, String text, int x, int y, boolean selected) {
        ModernStyle.rounded(g, box.x(), box.y(), box.width(), box.height(), 5,
            selected ? 0x603BAEE6 : box.contains(x, y) ? 0x453BAEE6 : 0x253BAEE6);
        centered(g, text, box, box.y() + Math.round((box.height() - ModernTypography.size()) / 2), ModernStyle.INK);
    }
}
