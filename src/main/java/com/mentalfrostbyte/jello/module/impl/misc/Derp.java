package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Shows other players a head that spins, jitters or looks anywhere, while your own view stays where it is.
 *
 * <p>Ported from LiquidBounce ({@code nextgen}, {@code features/module/modules/fun/ModuleDerp.kt}), GPL-3.0,
 * Copyright (c) 2015 - 2026 CCBlueX. Only the rotation the server is told about changes, through the movement packet.
 * For testing {@code SelfDetection} with: with {@code Safe Pitch} off a pitch past straight up or down goes out, which
 * no vanilla client can send.</p>
 *
 * <p>Upstream hands the rotation to its rotation manager, which can also turn the player's movement to match. That is
 * not ported: the player keeps walking by their own facing, so moving while this is on is movement the reported
 * rotation does not explain, and a prediction anticheat will say so. Stand still to test the rotation alone.</p>
 */
public class Derp extends Module {

    public enum YawMode {
        STATIC,
        OFFSET,
        RANDOM,
        JITTER,
        SPIN
    }

    public enum PitchMode {
        STATIC,
        OFFSET,
        RANDOM
    }

    private final EnumSetting<YawMode> yawMode = this.register(new EnumSetting<>("Yaw",
            "Static faces one way, Offset turns from your facing, Random anywhere, Jitter back and forth, Spin round.",
            YawMode.RANDOM));

    private final NumberSetting yaw = this.register(new NumberSetting("Yaw Value",
            "Static: the yaw shown. Offset: added to yours.", 0.0F, -180.0F, 180.0F, 1.0F));

    private final NumberSetting forwardTicks = this.register(new NumberSetting("Forward Ticks",
            "Jitter: ticks facing your way.", 2.0F, 0.0F, 100.0F, 1.0F));

    private final NumberSetting backwardTicks = this.register(new NumberSetting("Backward Ticks",
            "Jitter: ticks facing the other way.", 2.0F, 0.0F, 100.0F, 1.0F));

    private final NumberSetting spinSpeed = this.register(new NumberSetting("Spin Speed",
            "Spin: degrees per tick.", 50.0F, -70.0F, 70.0F, 1.0F));

    private final EnumSetting<PitchMode> pitchMode = this.register(new EnumSetting<>("Pitch",
            "Static looks one way, Offset tilts from your pitch, Random anywhere.", PitchMode.RANDOM));

    private final NumberSetting pitch = this.register(new NumberSetting("Pitch Value",
            "Static: the pitch shown. Offset: added to yours. Past 90 needs Safe Pitch off.", -90.0F, -180.0F, 180.0F, 1.0F));

    private final BooleanSetting safePitch = this.register(new BooleanSetting("Safe Pitch",
            "Keeps the pitch between straight up and straight down, as a real head can.", true));

    private final BooleanSetting notDuringSprint = this.register(new BooleanSetting("Not During Sprint",
            "Leaves the rotation alone while sprinting.", true));

    // Spin's current yaw and Jitter's place in its cycle; game thread.
    private float spin;
    private int jitterTick;

    public Derp() {
        super(ModuleCategory.MISC, "Derp", "Shows other players a spinning or random head; your view stays put.");
        this.yaw.visibleWhen(() -> this.yawMode.is(YawMode.STATIC) || this.yawMode.is(YawMode.OFFSET));
        this.forwardTicks.visibleWhen(() -> this.yawMode.is(YawMode.JITTER));
        this.backwardTicks.visibleWhen(() -> this.yawMode.is(YawMode.JITTER));
        this.spinSpeed.visibleWhen(() -> this.yawMode.is(YawMode.SPIN));
        this.pitch.visibleWhen(() -> this.pitchMode.is(PitchMode.STATIC) || this.pitchMode.is(PitchMode.OFFSET));
    }

    @Override
    protected void onEnable() {
        LocalPlayer player = mc.player;
        this.spin = player == null ? 0.0F : player.getYRot();
        this.jitterTick = 0;
    }

    @EventTarget
    public void onMotion(final EventMotion event) {
        LocalPlayer player = mc.player;
        if (!event.isPre() || player == null) {
            return;
        }
        if (this.notDuringSprint.get() && (mc.options.keySprint.isDown() || player.isSprinting())) {
            return;
        }
        event.setYaw(this.nextYaw(player.getYRot()));
        float next = this.nextPitch(player.getXRot());
        event.setPitch(this.safePitch.get() ? Mth.clamp(next, -90.0F, 90.0F) : next);
    }

    private float nextYaw(final float own) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return switch (this.yawMode.get()) {
            case STATIC -> this.yaw.get();
            case OFFSET -> own + this.yaw.get();
            case RANDOM -> random.nextFloat() * 360.0F - 180.0F;
            case JITTER -> {
                boolean backward = jitterBackward(this.jitterTick++, this.forwardTicks.getInt(), this.backwardTicks.getInt());
                yield backward ? own + 180.0F : own;
            }
            case SPIN -> this.spin += this.spinSpeed.get();
        };
    }

    private float nextPitch(final float own) {
        return switch (this.pitchMode.get()) {
            case STATIC -> this.pitch.get();
            case OFFSET -> own + this.pitch.get();
            // Upstream draws from the whole -180..180 once Safe Pitch is off.
            case RANDOM -> this.safePitch.get()
                    ? ThreadLocalRandom.current().nextFloat() * 180.0F - 90.0F
                    : ThreadLocalRandom.current().nextFloat() * 360.0F - 180.0F;
        };
    }

    /** Jitter: whether tick {@code tick} of a {@code forward}-then-{@code backward} cycle faces backward. */
    static boolean jitterBackward(final int tick, final int forward, final int backward) {
        int cycle = forward + backward;
        return cycle > 0 && Math.floorMod(tick, cycle) >= forward;
    }
}
