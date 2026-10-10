package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.EventAttack;
import com.mentalfrostbyte.jello.event.impl.player.EventStopUsingItem;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.player.AutoPearl;
import com.mentalfrostbyte.jello.module.impl.world.Disabler;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.game.ProjectileAim;
import com.mentalfrostbyte.jello.util.game.ProjectileAimValidator;
import com.mentalfrostbyte.jello.util.game.ProjectileLaunch;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.Util;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Hotbar-only throwing, using the same rotation report and item-use path as normal client actions. */
public final class AutoProjectile extends Module {
    private static final List<String> CATALOG = List.of("minecraft:snowball", "minecraft:egg", "minecraft:blue_egg", "minecraft:brown_egg",
            "minecraft:ender_pearl", "minecraft:experience_bottle", "minecraft:splash_potion",
            "minecraft:lingering_potion", "minecraft:wind_charge", "minecraft:bow",
            "minecraft:crossbow", "minecraft:trident");

    private final NumberSetting range = register(new NumberSetting("Range",
            "Maximum target distance in blocks.", 16, 2, 64, 1));
    private final NumberSetting delay = register(new NumberSetting("Delay",
            "Milliseconds before a new target or burst, and after combat/scaffold releases control.", 500, 0, 5000, 50));
    private final NumberSetting amount = register(new NumberSetting("Amount",
            "Throws per burst. A charged weapon counts as one shot.", 1, 1, 16, 1));
    private final NumberSetting frequency = register(new NumberSetting("Frequency",
            "Maximum throws per second within a burst; item cooldowns and charge time still apply.", 4, 1, 20, 1));
    private final ItemListSetting whitelist = register(new ItemListSetting("Projectile Whitelist",
            "Click to select items or drag them into the whitelist. Only hotbar items are used.",
            List.of("minecraft:snowball", "minecraft:egg"), CATALOG));

    private final ProjectileCadence cadence = new ProjectileCadence();
    private @Nullable LocalPlayer owner;
    private @Nullable LivingEntity target;
    private @Nullable Plan plan;
    private @Nullable EventMotion reportedMotion;
    private @Nullable EventSendPacket reportedPacket;
    private @Nullable EventSendPacket usedPacket;
    private @Nullable Rotation useRotation;
    private boolean useAccepted;
    private int heldSlot = -1;
    private int savedSlot = -1;
    private @Nullable Item heldItem;
    private boolean charging;
    private boolean ownsLook;
    private int chargeStarted;
    private int spentCrossbowSlots;

    private record Plan(int slot, Item item, ProjectileAim.Aim aim) { }

    public AutoProjectile() {
        super(ModuleCategory.COMBAT, "AutoProjectile",
                "Predicts moving targets and silently throws whitelisted hotbar projectiles. Yields to active Aura and Scaffold.");
    }

    @EventTarget(EventPriority.LOW)
    public void onTick(EventTick event) {
        if (!event.isPre()) return;
        reportedMotion = null;
        reportedPacket = null;
        plan = null;
        LocalPlayer player = mc.player;
        if (owner != player) {
            clear(false);
            owner = player;
        }
        if (player != null) {
            for (int slot = 0; slot < 9; slot++) {
                if (!CrossbowItem.isCharged(player.getInventory().getItem(slot))) spentCrossbowSlots &= ~(1 << slot);
            }
        }
        if (!available(player) || busy() || manualInput()
                || (heldSlot < 0 && player.isUsingItem())) {
            pause();
            return;
        }
        if (heldSlot >= 0 && (player.getInventory().getSelectedSlot() != heldSlot
                || player.getMainHandItem().getItem() != heldItem
                || !allowed(player.getMainHandItem()) || player.tickCount - chargeStarted > 200)) {
            pause();
            return;
        }
        if (charging && !player.isUsingItem()) {
            pause();
            return;
        }
        if (!validTarget(player, target)) {
            // Finish an owned use before choosing a different enemy; never release it as an attack.
            releaseHeld(true);
            target = null;
            cadence.reset();
        }
        if (target != null && heldSlot < 0 && !cadence.ready(Util.getMillis(), delay.getInt())) {
            releaseLook();
            return;
        }
        if (target == null) {
            Target filter = Target.current();
            if (filter != null) {
                List<LivingEntity> candidates = player.level().getEntitiesOfClass(LivingEntity.class,
                                player.getBoundingBox().inflate(range.get()), entity -> validTarget(player, entity))
                        .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).limit(8).toList();
                for (LivingEntity candidate : candidates) {
                    Plan candidatePlan = choose(player, candidate);
                    if (candidatePlan != null) {
                        target = candidate;
                        plan = candidatePlan;
                        break;
                    }
                }
            }
        } else {
            plan = choose(player, target);
        }
        if (plan == null) {
            pause();
            return;
        }
        if (!cadence.ready(Util.getMillis(), delay.getInt()) && heldSlot < 0) {
            plan = null;
            releaseLook();
            return;
        }
        requestCorrection(plan.aim());
    }

    /** Runs before BlockFly's LOWEST motion listener; a later placement can still take the look. */
    @EventTarget(EventPriority.LOW)
    public void onMotion(EventMotion event) {
        if (event.isPost()) return;
        LocalPlayer player = mc.player;
        if (plan == null || !available(player) || busy() || !validTarget(player, target)) return;
        // Keep the look used by this tick's movement correction. Rebuild its path from the new position;
        // if that fixed look no longer reaches the target, keep aiming but do not fire this tick.
        Plan previous = plan;
        event.setYaw(previous.aim().yaw());
        event.setPitch(previous.aim().pitch());
        event.forceRotation();
        Plan fresh = aim(player, target, previous.slot(), previous.aim());
        if (fresh == null) {
            plan = null;
            ownsLook = true;
            return;
        }
        plan = fresh;
        reportedMotion = event;
    }

    /** All scaffold PRE/POST placement work has finished before this stage. */
    @EventTarget(EventPriority.LOW)
    public void onUpdate(EventUpdate event) {
        if (!event.isPost() || plan == null) return;
        if (reportedMotion == null) {
            pause();
            return;
        }
        LocalPlayer player = mc.player;
        Plan action = plan;
        EventMotion motion = reportedMotion;
        EventSendPacket sent = reportedPacket;
        reportedMotion = null;
        if (!available(player) || busy() || manualInput() || motion.isCancelled()
                || sent == null || sent.isCancelled()
                || !(sent.getPacket() instanceof ServerboundMovePlayerPacket move) || !move.hasRotation()
                || !sameLook(move.getYRot(0), move.getXRot(0), action.aim())
                || !sameLook(motion.getYaw(), motion.getPitch(), action.aim())
                || !validTarget(player, target) || !ProjectileAimValidator.clear(player, target, action.aim())) {
            pause();
            return;
        }
        ownsLook = true;
        if (heldSlot < 0) {
            ItemStack stack = player.getInventory().getItem(action.slot());
            if (stack.getItem() != action.item() || !usable(player, stack)) {
                pause();
                return;
            }
            savedSlot = player.getInventory().getSelectedSlot();
            heldSlot = action.slot();
            heldItem = stack.getItem();
            chargeStarted = player.tickCount;
            player.getInventory().setSelectedSlot(heldSlot);
            mc.gameMode.syncCarriedItem();
            boolean charge = needsCharge(stack);
            InteractionResult result = use(player, action.aim());
            charging = charge && player.isUsingItem();
            if (charging && useAccepted) {
                return;
            }
            if (!charge && useAccepted && result.consumesAction()) shot();
            else pause();
            return;
        }
        if (player.getInventory().getSelectedSlot() != heldSlot || player.getMainHandItem().getItem() != heldItem) {
            pause();
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() instanceof CrossbowItem) {
            // Loading runs on the server. A local charge timer alone can release too early at low TPS.
            if (charging && CrossbowItem.isCharged(stack)) {
                mc.gameMode.releaseUsingItem(player); // crossbow release stops use, never fires
                charging = false;
                if (use(player, action.aim()).consumesAction() && useAccepted) shot();
                else pause();
            }
        } else if (charging && player.getTicksUsingItem() >= (stack.getItem() instanceof BowItem ? 20 : 10)) {
            mc.gameMode.releaseUsingItem(player);
            charging = false;
            shot();
        }
    }

    @EventTarget
    public void onStopUsing(EventStopUsingItem event) {
        if (charging && owner == mc.player && owner != null && owner.getInventory().getSelectedSlot() == heldSlot
                && owner.getUsedItemHand() == InteractionHand.MAIN_HAND) event.cancel();
    }

    @EventTarget(EventPriority.HIGHEST)
    public void onAttack(EventAttack event) {
        // Aura can acquire an enemy before our LOW tick listener. Give back the weapon before the
        // attack's carried-item sync, so that first attack does not accidentally use our charged bow.
        if (heldSlot >= 0 || plan != null) pause();
    }

    /** Only our synchronous use call is rewritten, including VFP's extra pre-use position report. */
    @EventTarget(EventPriority.LOWEST)
    public void onSend(EventSendPacket event) {
        if (!mc.isSameThread()) return;
        if (useRotation == null && reportedMotion != null && event.getPacket() instanceof ServerboundMovePlayerPacket) {
            // Retain the event itself: later listeners may still replace/cancel its packet.
            reportedPacket = event;
        }
        Rotation look = useRotation;
        if (look == null || event.isCancelled()) return;
        if (event.getPacket() instanceof ServerboundUseItemPacket use) {
            usedPacket = event;
            event.setPacket(new ServerboundUseItemPacket(use.getHand(), use.getSequence(), look.yaw(), look.pitch()));
        } else if (event.getPacket() instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            if (move.hasPosition()) {
                event.setPacket(new ServerboundMovePlayerPacket.PosRot(move.getX(0), move.getY(0), move.getZ(0),
                        look.yaw(), look.pitch(), move.isOnGround(), move.horizontalCollision()));
            } else {
                event.setPacket(new ServerboundMovePlayerPacket.Rot(look.yaw(), look.pitch(),
                        move.isOnGround(), move.horizontalCollision()));
            }
        }
    }

    private InteractionResult use(LocalPlayer player, ProjectileAim.Aim aim) {
        usedPacket = null;
        useAccepted = false;
        useRotation = new Rotation(aim.yaw(), aim.pitch());
        try {
            InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            useAccepted = usedPacket != null && !usedPacket.isCancelled()
                    && usedPacket.getPacket() instanceof ServerboundUseItemPacket;
            return result;
        } finally {
            useRotation = null;
        }
    }

    private @Nullable Plan choose(LocalPlayer player, LivingEntity candidate) {
        if (heldSlot >= 0) return aim(player, candidate, heldSlot);
        int selected = player.getInventory().getSelectedSlot();
        Plan preferred = aim(player, candidate, selected);
        if (preferred != null) return preferred;
        List<ItemStack> tried = new ArrayList<>(9);
        if (!awaitingCrossbow(selected)) tried.add(player.getInventory().getItem(selected));
        for (int slot = 0; slot < 9; slot++) {
            if (slot == selected || awaitingCrossbow(slot)) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            if (tried.stream().anyMatch(previous -> ItemStack.isSameItemSameComponents(previous, stack))) continue;
            tried.add(stack);
            Plan alternative = aim(player, candidate, slot);
            if (alternative != null) return alternative;
        }
        return null;
    }

    private @Nullable Plan aim(LocalPlayer player, LivingEntity candidate, int slot) {
        return aim(player, candidate, slot, null);
    }

    private @Nullable Plan aim(LocalPlayer player, LivingEntity candidate, int slot, ProjectileAim.@Nullable Aim fixedLook) {
        if (awaitingCrossbow(slot)) return null;
        ItemStack stack = player.getInventory().getItem(slot);
        if (!usable(player, stack)) return null;
        ItemStack modeled = stack;
        if (stack.getItem() instanceof CrossbowItem && !CrossbowItem.isCharged(stack)) {
            ItemStack ammo = player.getProjectile(stack);
            if (ammo.isEmpty()) return null;
            modeled = stack.copy();
            modeled.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(ammo.copyWithCount(1))));
        }
        ProjectileLaunch.Template template = ProjectileLaunch.template(modeled, 20);
        if (template == null) return null;
        ProjectileAim.Input input = new ProjectileAim.Input(template, player.getEyePosition(),
                ProjectileLaunch.inheritedMovement(player.getKnownMovement(), player.onGround()),
                candidate.getBoundingBox().getCenter(),
                candidate.position().subtract(new Vec3(candidate.xo, candidate.yo, candidate.zo)));
        ProjectileAim.Aim solution = fixedLook == null ? ProjectileAim.solve(input)
                : ProjectileAim.withRotation(input, fixedLook.yaw(), fixedLook.pitch());
        return solution != null && ProjectileAimValidator.clear(player, candidate, solution)
                ? new Plan(slot, stack.getItem(), solution) : null;
    }

    private boolean usable(LocalPlayer player, ItemStack stack) {
        if (!allowed(stack) || player.getCooldowns().isOnCooldown(stack)) return false;
        return !(stack.getItem() instanceof BowItem) || player.hasInfiniteMaterials() || !player.getProjectile(stack).isEmpty();
    }

    private boolean awaitingCrossbow(int slot) {
        return (spentCrossbowSlots & (1 << slot)) != 0;
    }

    private boolean allowed(ItemStack stack) {
        return !stack.isEmpty() && whitelist.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private boolean validTarget(LocalPlayer player, @Nullable LivingEntity candidate) {
        Target filter = Target.current();
        return candidate != null && candidate.level() == player.level() && filter != null
                && filter.accepts(player, candidate) && player.distanceToSqr(candidate) <= range.get() * range.get()
                && !player.isPassengerOfSameVehicle(candidate);
    }

    private static boolean needsCharge(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof TridentItem
                || (stack.getItem() instanceof CrossbowItem && !CrossbowItem.isCharged(stack));
    }

    private boolean available(@Nullable LocalPlayer player) {
        return player != null && mc.level != null && mc.gameMode != null && player.isAlive()
                && !player.isSpectator() && !player.isPassenger() && mc.gui.screen() == null && !mc.isPaused()
                && player.connection.getConnection().isConnected();
    }

    private boolean manualInput() {
        return mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();
    }

    private static boolean busy() {
        if (AutoPearl.isRescuing()) return true;
        KillAura aura = Modules.enabled(KillAura.class);
        BlockFly scaffold = Modules.enabled(BlockFly.class);
        return (aura != null && aura.isActivelyWorking()) || (scaffold != null && scaffold.isActivelyWorking());
    }

    private void requestCorrection(ProjectileAim.Aim aim) {
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null) corrector.request(this, new Rotation(aim.yaw(), aim.pitch()), corrector.mode(), -100);
    }

    private static boolean sameLook(float yaw, float pitch, ProjectileAim.Aim aim) {
        return Math.abs(Mth.wrapDegrees(yaw - aim.yaw())) < 0.001F && Math.abs(pitch - aim.pitch()) < 0.001F
                || Disabler.matchesTransformedRotation(yaw, pitch, aim.yaw(), aim.pitch());
    }

    private void shot() {
        cadence.fired(Util.getMillis(), amount.getInt(), frequency.get(), delay.getInt());
        // The loaded component is cleared only by a server inventory update, not by client prediction.
        if (heldItem instanceof CrossbowItem) spentCrossbowSlots |= 1 << heldSlot;
        releaseHeld(false);
        plan = null;
        releaseLook();
    }

    private void pause() {
        clear(true);
    }

    private void releaseHeld(boolean cancel) {
        LocalPlayer player = owner;
        if (heldSlot >= 0 && player != null && player == mc.player && mc.gameMode != null) {
            boolean stillSelected = player.getInventory().getSelectedSlot() == heldSlot;
            if (cancel && charging && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                // A slot change cancels use server-side. RELEASE_USE_ITEM here would accidentally shoot a bow/trident.
                if (stillSelected) {
                    int cancellationSlot = savedSlot == heldSlot ? (heldSlot + 1) % 9 : savedSlot;
                    player.getInventory().setSelectedSlot(cancellationSlot);
                    mc.gameMode.syncCarriedItem();
                } else {
                    mc.gameMode.syncCarriedItem();
                }
                player.stopUsingItem();
            }
            if (stillSelected && savedSlot >= 0) {
                player.getInventory().setSelectedSlot(savedSlot);
                mc.gameMode.syncCarriedItem();
            }
        }
        heldSlot = savedSlot = -1;
        heldItem = null;
        charging = false;
    }

    private void releaseLook() {
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null) corrector.release(this);
        if (ownsLook && owner != null && owner == mc.player && owner.connection.getConnection().isConnected() && !busy()) {
            owner.sendRotation(owner.getYRot(), owner.getXRot());
        }
        ownsLook = false;
    }

    private void clear(boolean restore) {
        if (restore) {
            releaseHeld(true);
            releaseLook();
        } else {
            MovementCorrector corrector = MovementCorrector.current();
            if (corrector != null) corrector.release(this);
            heldSlot = savedSlot = -1;
            heldItem = null;
            charging = ownsLook = false;
            spentCrossbowSlots = 0;
        }
        target = null;
        plan = null;
        reportedMotion = null;
        useRotation = null;
        reportedPacket = null;
        usedPacket = null;
        useAccepted = false;
        cadence.reset();
    }

    @Override
    protected void onDisable() {
        clear(true);
        owner = null;
        spentCrossbowSlots = 0;
    }

    @EventTarget
    public void onLoadWorld(EventLoadWorld event) {
        clear(false);
        owner = null;
    }
}
