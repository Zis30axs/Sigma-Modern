package com.mentalfrostbyte.jello.module.impl.player;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.movement.Stuck;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.game.PearlLandingPlanner;
import com.mentalfrostbyte.jello.util.game.VoidFallDetector;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Conservative void rescue: borrow Stuck, visibly aim at verified solid land, throw once, await the teleport. */
public final class AutoPearl extends Module {
    private final NumberSetting fall = register(new NumberSetting("Fall Distance",
            "Actual downward travel before a void rescue; independent of NoFall.", 3.75F, 2, 12, 0.25F));
    private final NumberSetting airTicks = register(new NumberSetting("Air Ticks",
            "Minimum time off solid ground before a rescue can start.", 10, 6, 40, 1));
    private final NumberSetting descendingTicks = register(new NumberSetting("Descending Ticks",
            "Consecutive descending ticks required, together with the distance and void checks.", 6, 3, 20, 1));
    private final NumberSetting searchRange = register(new NumberSetting("Search Range",
            "Maximum horizontal search distance for loaded, safe landing platforms.", 24, 8, 48, 1));
    private final NumberSetting turnSpeed = register(new NumberSetting("Turn Speed",
            "Visible camera turn in degrees per tick. The pearl is thrown only after aiming.", 60, 10, 180, 5));
    private final NumberSetting holdSeconds = register(new NumberSetting("Max Hold",
            "Maximum rescue suspension in seconds, including aiming and waiting for the pearl.", 6, 2, 10, 0.5F));
    private final BooleanSetting notices = register(new BooleanSetting("Status",
            "Show rescue progress and failure reasons above the hotbar.", true));

    private final VoidFallDetector detector = new VoidFallDetector();
    private final PearlRescueCycle cycle = new PearlRescueCycle();
    private @Nullable LocalPlayer owner;
    private @Nullable ClientLevel level;
    private @Nullable Stuck stuck;
    private @Nullable Vec3 anchor;
    private @Nullable Vec3 preferredLanding;
    private PearlLandingPlanner.@Nullable Plan plan;
    private @Nullable EventMotion motion;
    private @Nullable EventSendPacket movementPacket;
    private @Nullable EventSendPacket usePacket;
    private boolean issuingUse;
    private long tick;

    private record Pearl(InteractionHand hand, int slot) { }

    public AutoPearl() {
        super(ModuleCategory.PLAYER, "AutoPearl",
                "Rescues confirmed void falls by suspending movement, visibly aiming and throwing a pearl toward safe land.");
    }

    /** Other automated actions yield only during the rescue, without changing their enabled settings. */
    public static boolean isRescuing() {
        AutoPearl module = Modules.enabled(AutoPearl.class);
        return module != null && module.cycle.stage() != PearlRescueCycle.Stage.IDLE
                && module.owner == mc.player && module.level == mc.level;
    }

    @EventTarget(EventPriority.HIGHEST)
    public void onTick(EventTick event) {
        if (!event.isPre()) return;
        tick++;
        motion = null;
        movementPacket = null;
        LocalPlayer player = mc.player;
        if (owner != player || level != mc.level) {
            finish(null);
            owner = player;
            level = mc.level;
        }
        if (!available(player)) {
            finish(null);
            return;
        }
        if (cycle.stage() == PearlRescueCycle.Stage.IDLE) {
            VoidFallDetector.Result observation = detector.tick(player,
                    new VoidFallDetector.Limits(fall.get(), airTicks.getInt(), descendingTicks.getInt()));
            if (observation.dangerous()) begin(player, observation.lastSafePosition());
            return;
        }
        if (stuck == null || !stuck.isHeldBy(this)) {
            finish("Rescue interrupted: Stuck released.");
            return;
        }
        if (cycle.expired(tick)) {
            finish("Rescue timed out; movement released.");
            return;
        }
        if (anchor != null && player.position().distanceToSqr(anchor) > 0.25) {
            boolean landed = cycle.stage() == PearlRescueCycle.Stage.WAITING && plan != null
                    && PearlLandingPlanner.isSafeLanding(player, player.position());
            finish(landed ? "Returned to safe land." : "Position changed; rescue released.");
            return;
        }
        if (cycle.stage() == PearlRescueCycle.Stage.AIMING && plan != null) turn(player, plan);
    }

    private void begin(LocalPlayer player, @Nullable Vec3 safe) {
        if (!cycle.begin(tick, Math.round(holdSeconds.get() * 20))) return;
        anchor = player.position();
        preferredLanding = safe;
        stuck = Client.getInstance().getModuleManager().get(Stuck.class);
        if (!stuck.hold(this, cycle.remainingTicks(tick))) {
            finish("Could not suspend movement.");
            return;
        }
        notice("Void confirmed: looking for a safe pearl landing.");
    }

    /** Normal motion reports continue, and their look always follows the actual, visible camera. */
    @EventTarget(EventPriority.LOWEST)
    public void onMotion(EventMotion event) {
        if (!event.isPre() || cycle.stage() == PearlRescueCycle.Stage.IDLE || !available(mc.player)) return;
        event.setYaw(mc.player.getYRot());
        event.setPitch(mc.player.getXRot());
        event.forceRotation();
        motion = event;
    }

    @EventTarget(EventPriority.LOW)
    public void onUpdate(EventUpdate event) {
        if (!event.isPost() || cycle.stage() == PearlRescueCycle.Stage.IDLE) return;
        LocalPlayer player = mc.player;
        if (!available(player) || stuck == null || !stuck.isHeldBy(this) || cycle.expired(tick)) {
            finish(null);
            return;
        }
        if (cycle.stage() == PearlRescueCycle.Stage.SEARCHING) {
            if (findPearl(player) == null) {
                finish("No ready ender pearl in the hotbar or offhand.");
                return;
            }
            // Tick listeners have now relinquished combat/scaffold rotation and any held hotbar slot.
            plan = PearlLandingPlanner.find(player, preferredLanding, searchRange.get());
            if (plan == null) {
                finish("No clear pearl path to safe land was found.");
                return;
            }
            if (plan.flightTicks() + 4 >= cycle.remainingTicks(tick)) {
                finish("Safe landing is beyond the suspension time limit.");
                return;
            }
            cycle.planned();
            notice("Safe landing found: turning to throw.");
            return;
        }
        if (cycle.stage() != PearlRescueCycle.Stage.AIMING || plan == null || !aligned(player, plan)) return;
        if (plan.flightTicks() + 4 >= cycle.remainingTicks(tick)) {
            finish("Not enough suspension time remains for this landing.");
            return;
        }
        if (motion == null || motion.isCancelled() || movementPacket == null || movementPacket.isCancelled()
                || !(movementPacket.getPacket() instanceof ServerboundMovePlayerPacket sent) || !sent.hasRotation()
                || !sameLook(sent.getYRot(0), sent.getXRot(0), player.getYRot(), player.getXRot())) return;
        if (!PearlLandingPlanner.revalidate(player, plan)) {
            finish("The landing path changed; no pearl thrown.");
            return;
        }
        Pearl pearl = findPearl(player);
        if (pearl == null) {
            finish("The selected pearl is unavailable or cooling down.");
            return;
        }
        if (throwPearl(player, pearl) && cycle.shot(tick, plan.flightTicks())) {
            notice("Pearl thrown; waiting to return.");
        } else {
            finish("Pearl use was interrupted; movement released.");
        }
    }

    @EventTarget(EventPriority.LOWEST)
    public void onSend(EventSendPacket event) {
        if (!mc.isSameThread() || cycle.stage() == PearlRescueCycle.Stage.IDLE) return;
        // Keep the event, not a snapshot: later listeners can still cancel or replace a packet.
        if (issuingUse && event.getPacket() instanceof ServerboundUseItemPacket) usePacket = event;
        else if (!issuingUse && motion != null && event.getPacket() instanceof ServerboundMovePlayerPacket) movementPacket = event;
    }

    private boolean throwPearl(LocalPlayer player, Pearl pearl) {
        int originalSlot = player.getInventory().getSelectedSlot();
        usePacket = null;
        issuingUse = true;
        try {
            if (pearl.hand() == InteractionHand.MAIN_HAND) {
                player.getInventory().setSelectedSlot(pearl.slot());
                mc.gameMode.syncCarriedItem();
                // Selecting a different main-hand item stops its charge server-side without firing it.
                if (originalSlot != pearl.slot() && player.isUsingItem()
                        && player.getUsedItemHand() == InteractionHand.MAIN_HAND) player.stopUsingItem();
            }
            ItemStack stack = player.getItemInHand(pearl.hand());
            if (!stack.is(Items.ENDER_PEARL) || player.getCooldowns().isOnCooldown(stack)) return false;
            InteractionResult result = mc.gameMode.useItem(player, pearl.hand());
            return result.consumesAction() && usePacket != null && !usePacket.isCancelled()
                    && usePacket.getPacket() instanceof ServerboundUseItemPacket sent && sent.getHand() == pearl.hand()
                    && sameLook(sent.getYRot(), sent.getXRot(), player.getYRot(), player.getXRot());
        } finally {
            issuingUse = false;
            if (pearl.hand() == InteractionHand.MAIN_HAND && player.getInventory().getSelectedSlot() == pearl.slot()) {
                player.getInventory().setSelectedSlot(originalSlot);
                mc.gameMode.syncCarriedItem();
            }
        }
    }

    private @Nullable Pearl findPearl(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        if (readyPearl(player, player.getMainHandItem())) return new Pearl(InteractionHand.MAIN_HAND, selected);
        for (int slot = 0; slot < 9; slot++) {
            if (readyPearl(player, player.getInventory().getItem(slot))) return new Pearl(InteractionHand.MAIN_HAND, slot);
        }
        if (ProtocolTranslator.getTargetVersion().newerThan(ProtocolVersion.v1_8)
                && readyPearl(player, player.getOffhandItem())) return new Pearl(InteractionHand.OFF_HAND, -1);
        return null;
    }

    private static boolean readyPearl(LocalPlayer player, ItemStack stack) {
        return stack.is(Items.ENDER_PEARL) && !player.getCooldowns().isOnCooldown(stack);
    }

    private void turn(LocalPlayer player, PearlLandingPlanner.Plan destination) {
        float speed = turnSpeed.get();
        player.setYRot(player.getYRot() + Mth.clamp(Mth.wrapDegrees(destination.yaw() - player.getYRot()), -speed, speed));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(destination.pitch() - player.getXRot(), -speed, speed), -90, 90));
    }

    private static boolean aligned(LocalPlayer player, PearlLandingPlanner.Plan destination) {
        return sameLook(player.getYRot(), player.getXRot(), destination.yaw(), destination.pitch());
    }

    private static boolean sameLook(float yaw, float pitch, float wantedYaw, float wantedPitch) {
        return Math.abs(Mth.wrapDegrees(yaw - wantedYaw)) < 0.01F && Math.abs(pitch - wantedPitch) < 0.01F;
    }

    private boolean available(@Nullable LocalPlayer player) {
        return player != null && mc.level != null && mc.gameMode != null && player.level() == mc.level
                && player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.isPassenger()
                && !player.getAbilities().flying && !player.isFallFlying() && !player.isSleeping()
                && !player.isInWater() && !player.isInLava() && !player.onClimbable()
                && mc.gui.screen() == null && !mc.isPaused() && player.connection.getConnection().isConnected();
    }

    private void notice(String text) {
        if (notices.get() && mc.player != null) mc.gui.hud.setOverlayMessage(Component.literal("[AutoPearl] " + text), false);
    }

    private void finish(@Nullable String message) {
        try {
            if (stuck != null) stuck.release(this);
        } finally {
            stuck = null;
            cycle.reset();
            detector.reset(); // Re-arm only after a new stable landing; no repeated pearls during the same fall.
            plan = null;
            anchor = preferredLanding = null;
            motion = null;
            movementPacket = usePacket = null;
            issuingUse = false;
        }
        if (message != null) notice(message);
    }

    @Override
    protected void onDisable() {
        finish(null);
        owner = null;
        level = null;
    }

    @EventTarget
    public void onLoadWorld(EventLoadWorld event) {
        finish(null);
        owner = null;
        level = null;
    }
}
