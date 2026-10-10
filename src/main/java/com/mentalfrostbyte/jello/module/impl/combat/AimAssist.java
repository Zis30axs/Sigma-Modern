package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimSteering;
import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimSteering.Turn;
import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimStyleModel;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.player.AutoPearl;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Visible, mouse-path aiming only. Attacks still belong to the user's ordinary attack binding. */
public final class AimAssist extends Module {
    public enum Mode {
        LOCK, ASSIST, LORA;
        @Override public String toString() {
            return switch (this) { case LOCK -> "Lock"; case ASSIST -> "Assist"; case LORA -> "LoRA"; };
        }
    }

    private final EnumSetting<Mode> mode = register(new EnumSetting<>("Mode",
            "Lock slides firmly on attack, Assist slows near targets, LoRA imitates local practice.", Mode.ASSIST));
    private final NumberSetting range = register(new NumberSetting("Range",
            "Maximum distance to the target in blocks.", 4.5F, 1, 8, 0.1F));
    private final NumberSetting fov = register(new NumberSetting("FOV",
            "Full angle in degrees within which a target can be selected.", 90, 5, 180, 5));
    private final NumberSetting speed = register(new NumberSetting("Turn Speed",
            "Maximum assisted camera speed in degrees per second.", 240, 15, 900, 5));
    private final NumberSetting strength = register(new NumberSetting("Strength",
            "Assist and LoRA correction strength, including Assist mouse slowdown, in percent.", 70, 0, 100, 5));
    private final BooleanSetting onlyOnClick = register(new BooleanSetting("Only On Click",
            "Assist and LoRA work only while attacking. Lock always requires an attack press.", true));
    private final NumberSetting randomness = register(new NumberSetting("Randomness",
            "Amount of smooth variation based on the learned movement residual, in percent.", 15, 0, 100, 5));
    private final TextSetting training = register(new TextSetting("Training",
            "Open local practice for LoRA. Untrained LoRA uses Assist until all scenarios have enough samples.", "Open training"));

    private final Random random = new Random();
    private List<LivingEntity> candidates = List.of();
    private @Nullable LivingEntity target;
    private @Nullable LocalPlayer owner;
    private @Nullable ClientLevel level;
    private @Nullable Rotation lastAim;
    private Mode activeMode = Mode.ASSIST;
    private long clickUntil;
    private double previousYawVelocity, previousPitchVelocity, noiseYaw, noisePitch;
    private @Nullable AimStyleModel styleModel;
    private String profileError = "";

    public AimAssist() {
        super(ModuleCategory.COMBAT, "AimAssist", "Smooth visible aiming with Lock, adaptive Assist and locally trained LoRA.");
        strength.visibleWhen(() -> !mode.is(Mode.LOCK));
        onlyOnClick.visibleWhen(() -> !mode.is(Mode.LOCK));
        randomness.visibleWhen(() -> mode.is(Mode.LORA));
        training.visibleWhen(() -> mode.is(Mode.LORA));
    }

    @Override protected void onEnable() { reset(); getStyleModel(); }
    @Override protected void onDisable() { reset(); }
    @EventTarget public void onWorld(EventLoadWorld event) { reset(); }

    @EventTarget(EventPriority.LOWEST)
    public void onKey(EventKeyPress event) {
        syncSession();
        if (!event.isCancelled() && event.getAction() == EventKeyPress.Action.PRESS
                && mc.options.keyAttack.matches(event.getKey()) && available()) {
            // A short tap still has time to slide; never consume the vanilla click or synthesize attacks.
            clickUntil = Util.getMillis() + 200L;
        }
    }

    @EventTarget(EventPriority.LOWEST)
    public void onTick(EventTick event) {
        if (!event.isPre()) return;
        syncSession();
        if (!available() || busy()) { clearTracking(); clickUntil = 0; return; }
        Target filter = Target.current();
        if (filter == null) { clearTracking(); return; }
        LocalPlayer player = mc.player;
        candidates = player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range.get()), entity -> filter.accepts(player, entity));
    }

    /** Called once per mouse frame with final, sensitivity-adjusted camera deltas in degrees. */
    public Turn applyMouseTurn(double mouseYaw, double mousePitch, double seconds) {
        syncSession();
        double dt = AimSteering.frameSeconds(seconds);
        if (!available() || busy() || dt == 0.0) {
            clearTracking(); clickUntil = 0;
            return new Turn(mouseYaw, mousePitch);
        }
        boolean attacking = mc.options.keyAttack.isDown() || Util.getMillis() < clickUntil;
        if ((mode.is(Mode.LOCK) || onlyOnClick.get()) && !attacking) {
            forgetTarget();
            return new Turn(mouseYaw, mousePitch);
        }

        LocalPlayer player = mc.player;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 eye = player.getEyePosition(partial);
        LivingEntity selected = valid(target, eye, partial) ? target : choose(eye, partial);
        if (selected != target) { forgetTarget(); target = selected; }
        if (target == null) return new Turn(mouseYaw, mousePitch);

        Rotation aim = Rotations.towards(eye, aimPoint(target, partial));
        double yawError = AimSteering.wrap(aim.yaw() - player.getYRot());
        double pitchError = aim.pitch() - player.getXRot();
        double targetYawVelocity = lastAim == null ? 0 : AimSteering.wrap(aim.yaw() - lastAim.yaw()) / dt;
        double targetPitchVelocity = lastAim == null ? 0 : (aim.pitch() - lastAim.pitch()) / dt;
        lastAim = aim;

        Turn turn;
        if (mode.is(Mode.LOCK)) {
            turn = AimSteering.lock(yawError, pitchError, speed.get(), dt);
        } else if (mode.is(Mode.LORA) && getStyleModel().isReady()) {
            AimStyleModel.Velocity prediction = getStyleModel().predict(new AimStyleModel.Input(
                    yawError, pitchError, targetYawVelocity, targetPitchVelocity, previousYawVelocity, previousPitchVelocity));
            AimStyleModel.Velocity deviation = getStyleModel().residualDeviation();
            // Ornstein-Uhlenbeck-style correlated residual; bounded, time-scaled and cleared on target changes.
            double retention = Math.exp(-dt / 0.12);
            double innovation = Math.sqrt(1.0 - retention * retention);
            noiseYaw = AimSteering.clamp(retention * noiseYaw + innovation * random.nextGaussian(), -2, 2);
            noisePitch = AimSteering.clamp(retention * noisePitch + innovation * random.nextGaussian(), -2, 2);
            double variation = randomness.get() / 100.0;
            double amount = strength.get() / 100.0;
            Turn learned = AimSteering.learned(yawError - mouseYaw, pitchError - mousePitch,
                    (prediction.yaw() + noiseYaw * deviation.yaw() * variation) * amount,
                    (prediction.pitch() + noisePitch * deviation.pitch() * variation) * amount, speed.get() * amount, dt);
            turn = new Turn(mouseYaw + learned.yaw(), mousePitch + learned.pitch());
        } else {
            turn = AimSteering.assist(yawError, pitchError, mouseYaw, mousePitch,
                    speed.get(), strength.get() / 100.0, dt);
        }
        turn = new Turn(turn.yaw(), AimSteering.clamp(player.getXRot() + turn.pitch(), -90, 90) - player.getXRot());
        previousYawVelocity = turn.yaw() / dt;
        previousPitchVelocity = turn.pitch() / dt;
        return turn;
    }

    private @Nullable LivingEntity choose(Vec3 eye, float partial) {
        LivingEntity best = null;
        double bestAngle = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            if (!valid(candidate, eye, partial)) continue;
            Rotation aim = Rotations.towards(eye, aimPoint(candidate, partial));
            double angle = Math.hypot(AimSteering.wrap(aim.yaw() - mc.player.getYRot()), aim.pitch() - mc.player.getXRot());
            if (angle < bestAngle) { bestAngle = angle; best = candidate; }
        }
        return best;
    }

    private boolean valid(@Nullable LivingEntity entity, Vec3 eye, float partial) {
        Target filter = Target.current();
        if (entity == null || entity.level() != mc.level || filter == null || !filter.accepts(mc.player, entity)
                || mc.player.isPassengerOfSameVehicle(entity)) return false;
        Vec3 point = aimPoint(entity, partial);
        if (eye.distanceToSqr(point) > range.get() * range.get()) return false;
        Rotation aim = Rotations.towards(eye, point);
        if (Math.hypot(AimSteering.wrap(aim.yaw() - mc.player.getYRot()), aim.pitch() - mc.player.getXRot()) > fov.get() / 2) return false;
        return mc.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player))
                .getType() == HitResult.Type.MISS;
    }

    private static Vec3 aimPoint(LivingEntity entity, float partial) {
        return entity.getBoundingBox().getCenter().add(entity.getPosition(partial).subtract(entity.position()));
    }

    private boolean available() {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.gui.screen() == null && mc.gui.overlay() == null && !mc.isPaused()
                && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed() && mc.getCameraEntity() == mc.player
                && !mc.player.isUsingItem() && mc.player.connection.getConnection().isConnected();
    }

    private static boolean busy() {
        KillAura aura = Modules.enabled(KillAura.class);
        BlockFly scaffold = Modules.enabled(BlockFly.class);
        AutoProjectile projectile = Modules.enabled(AutoProjectile.class);
        return AutoPearl.isRescuing() || (aura != null && aura.isActivelyWorking())
                || (scaffold != null && scaffold.isActivelyWorking())
                || (projectile != null && projectile.isActivelyWorking());
    }

    private void syncSession() {
        if (owner != mc.player || level != mc.level || activeMode != mode.get()) {
            reset(); owner = mc.player; level = mc.level; activeMode = mode.get();
        }
    }

    private void forgetTarget() {
        target = null; lastAim = null;
        previousYawVelocity = previousPitchVelocity = noiseYaw = noisePitch = 0;
    }

    private void clearTracking() { candidates = List.of(); forgetTarget(); }
    private void reset() { clearTracking(); clickUntil = 0; owner = null; level = null; }

    public TextSetting getTrainingSetting() { return training; }
    public Path getProfilePath() { return mc.gameDirectory.toPath().resolve("sigma5").resolve("aimassist-lora.json"); }
    public String getProfileError() { return profileError; }
    public void profileSaved() { profileError = ""; }

    public AimStyleModel getStyleModel() {
        if (styleModel == null) {
            try { styleModel = AimStyleModel.load(getProfilePath()); }
            catch (IOException error) {
                styleModel = new AimStyleModel();
                profileError = error.getMessage() == null ? "Cannot read training profile" : error.getMessage();
            }
        }
        return styleModel;
    }
}
