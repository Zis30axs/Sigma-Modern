package com.mentalfrostbyte.jello.module.impl.combat;

import com.google.common.net.HostAndPort;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.module.impl.combat.VisiblePointScan.Kind;
import com.mentalfrostbyte.jello.module.impl.combat.VisiblePointScan.Pick;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A visible camera experiment with native practice clicks and independent manual comparisons. */
final class VisibleRotationTest {
    private final VisiblePointScan scan = new VisiblePointScan();
    private final RotationTestLog log = new RotationTestLog();
    private @Nullable LocalPlayer owner;
    private @Nullable ClientLevel world;
    private @Nullable LivingEntity target;
    private long tick;
    private int clickId;
    private @Nullable String clickOrigin;
    private String clickOutcome = "native_guard";
    private long manualTick = -1;
    private long inputTick = -1;
    private double range;
    private String lastState = "";

    boolean started() { return owner != null; }
    boolean tracking() { return target != null; }

    void tick(Minecraft mc, LocalPlayer player, ClientLevel level, @Nullable LivingEntity selected, double configuredRange,
              boolean gameplay) {
        if (owner != player || world != level) {
            close();
            owner = player;
            world = level;
            try {
                Path dir = mc.gameDirectory.toPath();
                Path path = log.open(dir);
                player.sendSystemMessage(Component.literal("Visible Test · " + dir.relativize(path)));
            } catch (IOException e) {
                LogUtils.getLogger().warn("Could not start the visible rotation test log", e);
                player.sendSystemMessage(Component.literal("Visible Test · log unavailable (see latest.log)"));
            }
        }
        tick++;
        range = Math.min(configuredRange, player.entityInteractionRange());
        if (selected != target) {
            scan.reset();
            target = selected;
        }
        if (selected == null) {
            idle(player, gameplay);
            return;
        }
        lastState = "tracking";
        Vec3 eye = player.getEyePosition();
        AABB box = selected.getBoundingBox();
        Rotation beforeLook = new Rotation(player.getYRot(), player.getXRot());
        HitResult nativeBefore = player.raycastHitResult(1.0F, player);
        Pick before = describe(nativeBefore, player);
        JsonObject row = row("tick");
        row.addProperty("targetName", selected.getName().getString());
        row.addProperty("targetHealth", selected.getHealth());
        row.addProperty("hurtTime", selected.hurtTime);
        row.addProperty("configuredRange", configuredRange);
        row.addProperty("range", range);
        row.addProperty("boxDistance", Math.sqrt(box.distanceToSqr(eye)));
        row.addProperty("cooldown", player.getAttackStrengthScale(0.5F));
        row.addProperty("usingItem", player.isUsingItem());
        putPick(row, "before", before);
        if (box.inflate(selected.getPickRadius()).distanceToSqr(eye) > range * range && before.kind() != Kind.TARGET) {
            row.addProperty("source", "out_of_range");
            row.addProperty("samples", 0);
            putPick(row, "after", before);
            log.write(row, false);
            return;
        }

        VisiblePointScan.Result result = scan.find(eye, box, before, point -> {
            Rotation angle = Rotations.towards(eye, point);
            Vec3 direction = Vec3.directionFromRotation(angle.pitch(), angle.yaw());
            return describe(pickAlong(player, eye, direction, range), player);
        });
        if (result.point() != null) {
            Rotation wanted = Rotations.continuous(beforeLook, Rotations.towards(eye, result.point()));
            player.setYRot(wanted.yaw());
            player.setXRot(wanted.pitch());
        }
        HitResult actual = result.point() == null ? nativeBefore : player.raycastHitResult(1.0F, player);
        Pick after = describe(actual, player);
        if (after.kind() == Kind.TARGET) {
            scan.remember(box, actual.getLocation());
        } else if (result.point() != null) {
            putPick(row, "rejectedNativePick", after);
            player.setYRot(beforeLook.yaw());
            player.setXRot(beforeLook.pitch());
            scan.rejected();
            after = before;
        }
        row.addProperty("source", result.source());
        row.addProperty("samples", result.samples());
        row.addProperty("nextSample", result.nextSample());
        row.addProperty("yawChange", player.getYRot() - beforeLook.yaw());
        row.addProperty("pitchChange", player.getXRot() - beforeLook.pitch());
        putPick(row, "center", result.center());
        putPick(row, "after", after);
        log.write(row, false);
    }

    /** Probe an ordinary interaction ray without changing the camera while evaluating candidates. */
    private static HitResult pickAlong(LocalPlayer player, Vec3 eye, Vec3 direction, double range) {
        Vec3 delta = direction.scale(range);
        HitResult block = player.level().clip(new ClipContext(eye, eye.add(delta), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        Vec3 end = block.getLocation();
        double limit = eye.distanceToSqr(end);
        AABB search = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, search, EntitySelector.CAN_BE_PICKED, limit);
        return entity != null && eye.distanceToSqr(entity.getLocation()) < limit ? entity : block;
    }

    private Pick describe(@Nullable HitResult hit, LocalPlayer player) {
        if (hit == null) return new Pick(Kind.MISS, "none", 0);
        double distance = player.getEyePosition().distanceTo(hit.getLocation());
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            Kind kind = entity != target ? Kind.OTHER_ENTITY : distance > range ? Kind.OUT_OF_RANGE : Kind.TARGET;
            return new Pick(kind, Integer.toString(entity.getId()), distance);
        }
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            String name = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(block.getBlockPos()).getBlock()).toString();
            return new Pick(Kind.BLOCK, name, distance);
        }
        return new Pick(Kind.MISS, "none", distance);
    }

    private static void putPick(JsonObject row, String key, Pick pick) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", pick.kind().name());
        value.addProperty("first", pick.first());
        value.addProperty("distance", pick.distance());
        row.add(key, value);
    }

    private JsonObject row(String event) {
        JsonObject row = RotationTestLog.event(event, tick);
        if (target != null) row.addProperty("targetId", target.getId());
        return row;
    }

    private void idle(LocalPlayer player, boolean gameplay) {
        String state = gameplay ? "no_target" : "paused";
        if (!state.equals(lastState) || tick % 20 == 0) {
            JsonObject row = row("tick");
            row.addProperty("source", state);
            if (gameplay) {
                row.addProperty("range", range);
                putPick(row, "crosshair", describe(player.raycastHitResult(1.0F, player), player));
            }
            log.write(row, false);
        }
        lastState = state;
    }

    /** The remote practice subject for this capture is Aim2 on CCBlueX's test arena. */
    static boolean namedDummy(String name, boolean singleplayer, @Nullable String address) {
        if (!"Aim2".equalsIgnoreCase(name)) return false;
        if (singleplayer) return true;
        if (address == null) return false;
        try {
            String host = HostAndPort.fromString(address).getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            return host.equals("test.ccbluex.net") || host.endsWith(".test.ccbluex.net");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    boolean claimInput(LocalPlayer player, @Nullable ClientLevel level) {
        if (player != owner || world != level || target == null || inputTick == tick) return false;
        inputTick = tick;
        return true;
    }

    boolean autoClick(LocalPlayer player, @Nullable HitResult crosshair, boolean practiceDummy, int missTime,
                      String timing, Runnable nativeClick) {
        if (target == null || player != owner || player.level() != world || clickOrigin != null) return false;
        Pick pick = describe(crosshair, player);
        String decision = autoDecision(player, practiceDummy, pick, missTime, timing);
        JsonObject row = row("auto_decision");
        row.addProperty("reason", decision);
        row.addProperty("timing", timing);
        putPick(row, "crosshair", pick);
        log.write(row, false);
        if (!decision.equals("ready")) return false;

        startClick("auto", pick);
        try {
            nativeClick.run();
            return true;
        } catch (RuntimeException | Error failure) {
            clickOutcome = "client_exception";
            throw failure;
        } finally {
            if ("auto".equals(clickOrigin)) {
                JsonObject result = row("auto_result");
                result.addProperty("click", clickId);
                result.addProperty("status", clickOutcome);
                log.write(result, true);
                endClick();
            }
        }
    }

    private String autoDecision(LocalPlayer player, boolean practiceDummy, Pick pick, int missTime, String timing) {
        if (!practiceDummy) return "other_subject";
        if (manualTick == tick) return "manual_input";
        if (target == null || target.isRemoved() || !target.isAlive() || target.level() != world) return "target_lost";
        if (pick.kind() != Kind.TARGET) return pick.kind().name().toLowerCase(Locale.ROOT);
        if (player.isUsingItem() || player.isHandsBusy()) return "item_busy";
        if (missTime > 0) return "miss_delay";
        if (player.getMainHandItem().has(DataComponents.PIERCING_WEAPON)) return "area_weapon";
        return timing;
    }

    boolean beginClick(LocalPlayer player, @Nullable HitResult crosshair) {
        if (player != owner || player.level() != world || clickOrigin != null) return false;
        manualTick = tick;
        startClick("manual", describe(crosshair, player));
        return true;
    }

    private void startClick(String origin, Pick crosshair) {
        clickOrigin = origin;
        clickOutcome = "native_guard";
        JsonObject row = row(origin + "_click");
        row.addProperty("click", ++clickId);
        putPick(row, "crosshair", crosshair);
        log.write(row, true);
    }

    void attackEvent(Entity entity, boolean cancelled) {
        if (clickOrigin == null) return;
        clickOutcome = entity != target ? "different_entity_event" : cancelled ? "attack_event_cancelled" : "attack_event";
        JsonObject row = row("local_attack_event");
        row.addProperty("click", clickId);
        row.addProperty("origin", clickOrigin);
        row.addProperty("entityId", entity.getId());
        row.addProperty("cancelledAtListener", cancelled);
        log.write(row, true);
    }

    void endClick() { clickOrigin = null; }

    void close() {
        if (owner != null) log.write(row("end"), true);
        log.close();
        scan.reset();
        owner = null;
        world = null;
        target = null;
        tick = 0;
        clickId = 0;
        endClick();
        manualTick = -1;
        inputTick = -1;
        lastState = "";
    }
}
