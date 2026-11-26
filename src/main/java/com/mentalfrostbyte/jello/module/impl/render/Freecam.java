package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.game.render.EventRender2D;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Detaches the camera to fly freely around the world while leaving the real player stationary
 * with normal server movement ticks intact.
 */
public final class Freecam extends Module {
    public enum ViewMode {
        NORMAL("Normal"), FPV("FPV");
        private final String label;
        ViewMode(final String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    public enum FpvTheme {
        MODERN("Modern"), TACTICAL("Tactical"), MONO("Mono");
        private final String label;
        FpvTheme(final String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    private static final double CAMERA_BOX_SIZE = 0.6;
    private static final float SMOOTH_ALPHA = 0.55F;

    private final EnumSetting<ViewMode> viewMode = this.register(new EnumSetting<>(
            "Mode", "Normal clean camera or FPV drone telemetry HUD.", ViewMode.NORMAL));
    private final EnumSetting<FpvTheme> fpvTheme = this.register(new EnumSetting<>(
            "FPV Theme", "Color palette for the FPV telemetry overlay.", FpvTheme.MODERN));
    private final NumberSetting speed = this.register(new NumberSetting(
            "Speed", "Horizontal flight speed in blocks per tick.", 1.0F, 0.1F, 10.0F, 0.1F));
    private final NumberSetting verticalSpeed = this.register(new NumberSetting(
            "Vertical Speed", "Vertical ascent and descent speed in blocks per tick.", 0.8F, 0.1F, 10.0F, 0.1F));
    private final NumberSetting sprintMultiplier = this.register(new NumberSetting(
            "Sprint Multiplier", "Speed multiplier while holding sprint.", 1.8F, 1.0F, 4.0F, 0.1F));
    private final BooleanSetting smooth = this.register(new BooleanSetting(
            "Smooth", "Glide smoothly when starting and stopping camera movement.", true));
    private final BooleanSetting noClip = this.register(new BooleanSetting(
            "NoClip", "Fly freely through solid blocks.", true));
    private final BooleanSetting scrollSpeed = this.register(new BooleanSetting(
            "Scroll Speed", "Adjust flight speed with the mouse scroll wheel.", true));
    private final BooleanSetting playerControl = this.register(new BooleanSetting(
            "Player Control", "Control your real body while keeping the freecam parked in place.", false));
    private final BooleanSetting preventInteractions = this.register(new BooleanSetting(
            "Prevent Interactions", "Block attacks, mining and item use while piloting the freecam.", true));
    private final BooleanSetting caveCulling = this.register(new BooleanSetting(
            "Cave Culling", "Keep vanilla cave occlusion culling active during Freecam.", false));
    private final BooleanSetting disableOnDamage = this.register(new BooleanSetting(
            "Disable On Damage", "Exit Freecam automatically if your body takes damage.", false));

    private boolean active;
    private Vec3 prevPos = Vec3.ZERO;
    private Vec3 pos = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private boolean latchedSneak;
    private int lastHurtTime;
    private Input capturedInput = Input.EMPTY;
    private boolean inputCapturedThisTick;

    public Freecam() {
        super(ModuleCategory.RENDER, "Freecam", "Detaches the camera to fly freely while keeping your real player stationary.");
        this.fpvTheme.visibleWhen(() -> this.viewMode.is(ViewMode.FPV));
        this.preventInteractions.visibleWhen(() -> !this.playerControl.get());
    }

    @Override
    protected void onEnable() {
        resetState();
        if (mc != null && mc.player != null && mc.level != null) {
            initializeFromPlayer(mc.player);
            if (mc.gameMode != null && !this.playerControl.get()) {
                mc.gameMode.stopDestroyBlock();
            }
        }
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private void resetState() {
        this.active = false;
        this.prevPos = Vec3.ZERO;
        this.pos = Vec3.ZERO;
        this.velocity = Vec3.ZERO;
        this.yaw = 0.0F;
        this.pitch = 0.0F;
        this.latchedSneak = false;
        this.lastHurtTime = 0;
        this.capturedInput = Input.EMPTY;
        this.inputCapturedThisTick = false;
    }

    private void initializeFromPlayer(final LocalPlayer player) {
        Camera cam = mc.gameRenderer != null && mc.gameRenderer.mainCamera().isInitialized()
                ? mc.gameRenderer.mainCamera() : null;
        this.pos = cam != null ? cam.position() : player.getEyePosition();
        this.prevPos = this.pos;
        this.yaw = cam != null ? cam.yRot() : player.getYRot();
        this.pitch = Mth.clamp(cam != null ? cam.xRot() : player.getXRot(), -90.0F, 90.0F);
        this.velocity = Vec3.ZERO;
        this.latchedSneak = (player.input != null && player.input.keyPresses.shift()) || player.isShiftKeyDown();
        this.lastHurtTime = player.hurtTime;
        this.capturedInput = Input.EMPTY;
        this.inputCapturedThisTick = false;
        this.active = true;
    }

    public boolean isActive() {
        return this.isEnabled() && this.active && mc != null && mc.player != null && mc.level != null;
    }

    public boolean capturesInput() {
        return this.isActive() && !this.playerControl.get();
    }

    public boolean usesCaveCulling() {
        return this.caveCulling.get();
    }

    public boolean blocksInteractionClicks() {
        return this.capturesInput() && this.preventInteractions.get();
    }

    public static boolean blocksInteractions() {
        if (mc == null) return false;
        Freecam freecam = Modules.enabled(Freecam.class);
        return freecam != null && freecam.blocksInteractionClicks();
    }

    public boolean isFpvMode() {
        return this.viewMode.is(ViewMode.FPV);
    }

    public ViewMode getViewMode() {
        return this.viewMode.get();
    }

    public FpvTheme getFpvTheme() {
        return this.fpvTheme.get();
    }

    public boolean isNoClip() {
        return this.noClip.get();
    }

    public boolean isPlayerControl() {
        return this.playerControl.get();
    }

    public float getConfiguredSpeed() {
        return this.speed.get();
    }

    public Vec3 getVelocity() {
        return this.velocity;
    }

    public double getSpeedBlocksPerSecond() {
        return this.pos.subtract(this.prevPos).length() * 20.0;
    }

    public double getVerticalSpeedBlocksPerSecond() {
        return (this.pos.y - this.prevPos.y) * 20.0;
    }

    public static boolean replacesCrosshair() {
        if (mc == null) return false;
        Freecam freecam = Modules.enabled(Freecam.class);
        return freecam != null && freecam.isActive() && freecam.isFpvMode();
    }

    public Vec3 getPosition(final float partialTicks) {
        return this.prevPos.lerp(this.pos, partialTicks);
    }

    public float getYaw(final float partialTicks) {
        return this.yaw;
    }

    public float getPitch(final float partialTicks) {
        return this.pitch;
    }

    public void turn(final double xo, final double yo) {
        this.yaw = advanceYaw(this.yaw, xo);
        this.pitch = advancePitch(this.pitch, yo);
    }

    public boolean onScroll(final int wheelDelta) {
        if (!this.capturesInput() || !this.scrollSpeed.get() || wheelDelta == 0) {
            return false;
        }
        this.speed.set(stepScrolledSpeed(this.speed.get(), wheelDelta,
                this.speed.getStep(), this.speed.getMin(), this.speed.getMax()));
        this.verticalSpeed.set(stepScrolledSpeed(this.verticalSpeed.get(), wheelDelta,
                this.verticalSpeed.getStep(), this.verticalSpeed.getMin(), this.verticalSpeed.getMax()));
        return true;
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.setEnabled(false);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onMovementInput(final EventMovementInput event) {
        if (!this.isActive()) return;
        if (this.playerControl.get()) {
            this.capturedInput = Input.EMPTY;
            this.inputCapturedThisTick = true;
            return;
        }
        this.capturedInput = mc.gui.screen() == null ? readOptionsKeys() : event.getInput();
        this.inputCapturedThisTick = true;
        event.setInput(new Input(false, false, false, false, false, this.latchedSneak, false));
        mc.player.setSprinting(false);
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (!event.isPre()) return;
        if (mc == null || mc.player == null || mc.level == null) {
            this.active = false;
            return;
        }
        if (!mc.player.isAlive()) {
            this.setEnabled(false);
            return;
        }
        if (!this.active) {
            initializeFromPlayer(mc.player);
        }

        int hurt = mc.player.hurtTime;
        if (this.disableOnDamage.get() && hurt > this.lastHurtTime && hurt > 0) {
            this.setEnabled(false);
            return;
        }
        this.lastHurtTime = hurt;

        this.prevPos = this.pos;
        Input keys = this.playerControl.get()
                ? Input.EMPTY
                : (this.inputCapturedThisTick ? this.capturedInput : (mc.gui.screen() == null ? readOptionsKeys() : Input.EMPTY));
        this.inputCapturedThisTick = false;

        Vec3 targetVel = computeTargetVelocity(
                keys, this.yaw, this.speed.get(), this.verticalSpeed.get(), this.sprintMultiplier.get());
        this.velocity = stepVelocity(this.velocity, targetVel, this.smooth.get());
        Vec3 step = this.velocity;

        if (!this.noClip.get() && step.lengthSqr() > 1.0E-8) {
            Vec3 collided = Entity.collideBoundingBox(
                    CollisionContext.empty(), step, AABB.ofSize(this.pos, CAMERA_BOX_SIZE, CAMERA_BOX_SIZE, CAMERA_BOX_SIZE),
                    mc.level, List.of());
            this.velocity = new Vec3(
                    Math.abs(collided.x - step.x) > 1.0E-5 ? 0.0 : this.velocity.x,
                    Math.abs(collided.y - step.y) > 1.0E-5 ? 0.0 : this.velocity.y,
                    Math.abs(collided.z - step.z) > 1.0E-5 ? 0.0 : this.velocity.z
            );
            step = collided;
        }
        this.pos = this.pos.add(step);
    }

    @EventTarget
    public void onClick(final EventClick event) {
        if (this.blocksInteractionClicks()
                && (event.getButton() == EventClick.Button.LEFT || event.getButton() == EventClick.Button.RIGHT)) {
            event.cancel();
        }
    }

    @EventTarget
    public void onRender2D(final EventRender2D event) {
        if (!event.isPost() || !this.isActive() || !this.isFpvMode() || mc.gui.hud.isHidden()) {
            return;
        }
        com.mentalfrostbyte.jello.gui.modern.ModernFreecamFpv.render(event.getGraphics(), this, event.getPartialTick());
    }

    /** Computes crosshair block/entity picking from the detached freecam viewpoint for outlines and inspection. */
    public HitResult raycast(final Minecraft client, final float partialTicks) {
        Vec3 from = this.getPosition(partialTicks);
        Vec3 dir = Vec3.directionFromRotation(this.pitch, this.yaw);
        double blockRange = client.player.blockInteractionRange();
        double entityRange = client.player.entityInteractionRange();
        double maxDist = Math.max(blockRange, entityRange);
        Vec3 blockEnd = from.add(dir.scale(maxDist));
        BlockHitResult blockHit = client.level.clip(
                new ClipContext(from, blockEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));

        double blockDistSq = blockHit.getLocation().distanceToSqr(from);
        double entityLimit = blockHit.getType() != HitResult.Type.MISS ? Math.sqrt(blockDistSq) : maxDist;
        double entityLimitSq = entityLimit * entityLimit;
        Vec3 entityEnd = from.add(dir.scale(entityLimit));
        AABB box = AABB.ofSize(from, CAMERA_BOX_SIZE, CAMERA_BOX_SIZE, CAMERA_BOX_SIZE)
                .expandTowards(dir.scale(entityLimit)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                client.player, from, entityEnd, box, EntitySelector.CAN_BE_PICKED, entityLimitSq);

        if (entityHit != null && entityHit.getLocation().distanceToSqr(from) < blockDistSq) {
            return filterHitResult(entityHit, from, entityRange);
        }
        return filterHitResult(blockHit, from, blockRange);
    }

    private static HitResult filterHitResult(final HitResult hitResult, final Vec3 from, final double maxRange) {
        Vec3 hitLocation = hitResult.getLocation();
        if (!hitLocation.closerThan(from, maxRange)) {
            Direction direction = Direction.getApproximateNearest(
                    hitLocation.x - from.x, hitLocation.y - from.y, hitLocation.z - from.z);
            return BlockHitResult.miss(hitLocation, direction, BlockPos.containing(hitLocation));
        }
        return hitResult;
    }

    private Input readOptionsKeys() {
        return new Input(
                mc.options.keyUp.isDown(),
                mc.options.keyDown.isDown(),
                mc.options.keyLeft.isDown(),
                mc.options.keyRight.isDown(),
                mc.options.keyJump.isDown(),
                mc.options.keyShift.isDown(),
                mc.options.keySprint.isDown()
        );
    }

    static Vec3 computeTargetVelocity(
            final Input keys, final float yaw, final double hSpeed, final double vSpeed, final double sprintMultiplier) {
        float forward = (keys.forward() ? 1.0F : 0.0F) - (keys.backward() ? 1.0F : 0.0F);
        float strafe = (keys.left() ? 1.0F : 0.0F) - (keys.right() ? 1.0F : 0.0F);
        float horizSq = strafe * strafe + forward * forward;
        if (horizSq > 1.0F) {
            float inv = 1.0F / Mth.sqrt(horizSq);
            strafe *= inv;
            forward *= inv;
        }
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double dx = strafe * cos - forward * sin;
        double dz = forward * cos + strafe * sin;
        double up = (keys.jump() ? 1.0 : 0.0) - (keys.shift() ? 1.0 : 0.0);
        double boost = keys.sprint() && (forward != 0.0F || strafe != 0.0F || up != 0.0) ? sprintMultiplier : 1.0;
        return new Vec3(dx * hSpeed * boost, up * vSpeed * boost, dz * hSpeed * boost);
    }

    static Vec3 stepVelocity(final Vec3 current, final Vec3 target, final boolean smooth) {
        if (!smooth) return target;
        Vec3 next = current.lerp(target, SMOOTH_ALPHA);
        return target.lengthSqr() == 0.0 && next.lengthSqr() < 1.0E-6 ? Vec3.ZERO : next;
    }

    static float advanceYaw(final float yaw, final double xo) {
        return yaw + (float) (xo * 0.15);
    }

    static float advancePitch(final float pitch, final double yo) {
        return Mth.clamp(pitch + (float) (yo * 0.15), -90.0F, 90.0F);
    }

    static float stepScrolledSpeed(
            final float current, final int wheelDelta, final float step, final float min, final float max) {
        return Mth.clamp(Math.round((current + wheelDelta * step) * 10.0F) / 10.0F, min, max);
    }
}
