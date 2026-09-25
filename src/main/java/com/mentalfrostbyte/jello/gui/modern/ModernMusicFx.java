package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.music.AudioAnalyzer;
import com.mentalfrostbyte.jello.music.MusicEffects;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Spectrum;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;

/**
 * SigmaModern's in-game music visuals, drawn under the rest of the HUD (the hotbar and hearts stay on top):
 * <ul>
 *   <li>a <b>spectrum</b> across the whole bottom edge - bars rising with each frequency band, quick to jump and
 *       slow to fall, with thin caps that drop behind them;</li>
 *   <li><b>beat particles</b> - soft sparks thrown up from the bottom on every beat, more and higher the harder it
 *       hits (strong mode adds a glow along the bottom edge on the beat).</li>
 * </ul>
 * Both follow the audio as it is heard (not as decoded ahead), fade away while paused or buffering, and take the
 * cover's colour when the island does. Settings: {@link MusicEffects}.
 */
public final class ModernMusicFx {
    private static final int MAX_BARS = 128, MAX_PARTICLES = 180;
    private static final float[] bars = new float[MAX_BARS], caps = new float[MAX_BARS], capSpeed = new float[MAX_BARS];
    private static final float[] px = new float[MAX_PARTICLES], py = new float[MAX_PARTICLES], vx = new float[MAX_PARTICLES],
        vy = new float[MAX_PARTICLES], age = new float[MAX_PARTICLES], life = new float[MAX_PARTICLES], size = new float[MAX_PARTICLES],
        whiten = new float[MAX_PARTICLES];
    private static final Random RANDOM = new Random();
    private static long lastDraw, lastBeats = -1L;
    private static float presence, pulse;
    private static int live;

    private ModernMusicFx() {}

    /** Tells the music whether the player's head is in water or lava, every tick (and "no" without a player). */
    public static final class SubmergedListener {
        private final MusicEffects effects;

        public SubmergedListener(MusicEffects effects) {
            this.effects = effects;
        }

        @EventTarget
        public void onTick(EventTick event) {
            if (!event.isPre()) return;
            LocalPlayer player = Minecraft.getInstance().player;
            this.effects.setSubmerged(player != null && (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)));
        }
    }

    static void render(GuiGraphicsExtractor g) {
        Client client = Client.getInstance();
        MusicEffects fx = client.getMusicEffects();
        MusicPlayer player = client.getMusicPlayer();
        long now = System.nanoTime();
        // Clamped: the first frame back from a pause or a hitch must not release a burst.
        float dt = lastDraw == 0L ? 0F : Math.min(0.05F, (now - lastDraw) / 1_000_000_000F);
        lastDraw = now;

        Spectrum spectrum = player.isPlaying() && !player.isBuffering() ? player.spectrum() : null;
        presence = ModernStyle.smooth(presence, spectrum != null ? 1F : 0F, dt, 4F);
        ModernCoverColors.update(player.current(), fx.islandColor());
        int accent = ModernCoverColors.accent();

        if (spectrum != null && spectrum.beats() != lastBeats) {
            if (lastBeats >= 0L && spectrum.beats() > lastBeats) beat(g, spectrum.beatStrength(), fx.particles());
            lastBeats = spectrum.beats();
        }
        if (spectrum == null && presence < 0.01F) lastBeats = -1L;
        pulse *= (float)Math.exp(-dt * 5F);

        if (fx.spectrum() && presence > 0.01F) drawSpectrum(g, spectrum, fx, accent, dt);
        if (fx.particles() == MusicEffects.Particles.STRONG && pulse > 0.02F) {
            // The beat felt along the bottom edge.
            int h = g.guiHeight(), glow = Math.round(34F * pulse);
            ModernStyle.fillGradient(g, 0, h - glow, g.guiWidth(), h, accent & 0xFFFFFF, ModernTypography.fade(accent, 0.28F * pulse * presence));
        }
        if (live > 0) drawParticles(g, accent, dt);
    }

    private static void drawSpectrum(GuiGraphicsExtractor g, Spectrum spectrum, MusicEffects fx, int accent, float dt) {
        int w = g.guiWidth(), h = g.guiHeight();
        int count = Math.max(24, Math.min(MAX_BARS, w / 6));
        float slot = w / (float)count, maxH = h * fx.spectrumHeight();
        float intensity = fx.spectrumIntensity(), opacity = fx.spectrumOpacity() * presence;
        float[] bands = spectrum == null ? null : spectrum.bands();
        // A dark foot under the colour keeps the bars readable over a bright sky or snow.
        int deep = ModernCoverColors.deep();
        int shadeTop = ModernTypography.fade(deep, 0.05F * opacity), shadeBottom = ModernTypography.fade(deep, 0.5F * opacity);
        int top = ModernTypography.fade(accent, 0.25F * opacity), bottom = ModernTypography.fade(accent, Math.min(1F, 0.8F + 0.2F * pulse) * opacity);
        int cap = ModernTypography.fade(ModernStyle.mix(accent, 0xFFFFFFFF, 0.55F), Math.min(1F, 1.1F * opacity));
        for (int i = 0; i < count; i++) {
            float target = 0F;
            if (bands != null) {
                // Across the bands, low on the left to high on the right.
                float at = i * (AudioAnalyzer.BANDS - 1) / (float)(count - 1);
                int b = (int)at;
                float frac = at - b;
                target = bands[b] * (1F - frac) + bands[Math.min(AudioAnalyzer.BANDS - 1, b + 1)] * frac;
                // Intensity: a curve that sets loud further apart from quiet, then a gain; a beat kicks them all up.
                target = Math.min(1F, (float)Math.pow(target, 1.5) * intensity * (1F + 0.3F * pulse * Math.min(1F, intensity)));
            }
            bars[i] = ModernStyle.smooth(bars[i], target, dt, target > bars[i] ? 28F : 7F);
            float barH = bars[i] * maxH * presence;
            if (barH >= caps[i]) {
                caps[i] = barH;
                capSpeed[i] = 0F;
            } else {
                capSpeed[i] += 90F * dt;
                caps[i] = Math.max(barH, caps[i] - capSpeed[i] * dt);
            }
            int x0 = Math.round(i * slot), x1 = Math.max(x0 + 1, Math.round((i + 1) * slot) - 1);
            int bh = Math.round(barH);
            if (bh > 0) {
                ModernStyle.fillGradient(g, x0, h - bh, x1, h, shadeTop, shadeBottom);
                ModernStyle.fillGradient(g, x0, h - bh, x1, h, top, bottom);
            }
            int cy = h - Math.round(caps[i]) - 2;
            if (caps[i] > 1.5F) ModernStyle.fill(g, x0, cy, x1, cy + 1, cap);
        }
    }

    /** A beat: the bottom glow flares and, as set, sparks fly. */
    private static void beat(GuiGraphicsExtractor g, float strength, MusicEffects.Particles setting) {
        pulse = Math.max(pulse, 0.35F + 0.65F * strength);
        if (setting == MusicEffects.Particles.OFF) return;
        boolean strong = setting == MusicEffects.Particles.STRONG;
        int count = Math.round((strong ? 10F + 18F * strength : 4F + 7F * strength) * presence);
        int w = g.guiWidth(), h = g.guiHeight();
        for (int n = 0; n < count && live < MAX_PARTICLES; n++) {
            int i = live++;
            px[i] = RANDOM.nextFloat() * w;
            py[i] = h - RANDOM.nextFloat() * h * 0.06F;
            vx[i] = (RANDOM.nextFloat() - 0.5F) * 40F;
            vy[i] = -(40F + RANDOM.nextFloat() * 90F) * (0.6F + strength) * (strong ? 1.35F : 1F);
            age[i] = 0F;
            life[i] = 1.1F + RANDOM.nextFloat() * 1.3F;
            size[i] = (strong ? 3F : 2.5F) + RANDOM.nextFloat() * (strong ? 5F : 3.5F);
            whiten[i] = RANDOM.nextFloat() * 0.6F;
        }
    }

    private static void drawParticles(GuiGraphicsExtractor g, int accent, float dt) {
        for (int i = 0; i < live; ) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                // Swap the last live particle into this slot.
                live--;
                px[i] = px[live];
                py[i] = py[live];
                vx[i] = vx[live];
                vy[i] = vy[live];
                age[i] = age[live];
                life[i] = life[live];
                size[i] = size[live];
                whiten[i] = whiten[live];
                continue;
            }
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            vy[i] += 30F * dt;
            vx[i] *= 1F - 0.8F * dt;
            float t = age[i] / life[i];
            float alpha = Math.min(1F, age[i] / 0.12F) * (float)Math.pow(1F - t, 1.4F);
            float s = size[i] * (1F + 0.4F * t);
            int color = ModernTypography.fade(ModernStyle.mix(accent, 0xFFFFFFFF, whiten[i]), alpha);
            ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, px[i] - s, py[i] - s, s * 2F, color);
            i++;
        }
    }
}
