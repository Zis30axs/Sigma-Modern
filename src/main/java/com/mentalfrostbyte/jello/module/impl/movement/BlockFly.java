package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventClick;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mentalfrostbyte.jello.event.impl.game.network.EventReceivePacket;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.game.render.EventRender2D;
import com.mentalfrostbyte.jello.event.impl.game.render.EventRender3D;
import com.mentalfrostbyte.jello.event.impl.player.EventGetFovModifier;
import com.mentalfrostbyte.jello.event.impl.player.EventLivingUpdate;
import com.mentalfrostbyte.jello.event.impl.player.EventUpdate;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMove;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventSafeWalk;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.impl.player.AutoPearl;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.movement.blockfly.*;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;
import java.util.EnumMap;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.Vec3;

/** SigmaClient's eight bridge policies, with one owner for inventory and placement lifecycle. */
public final class BlockFly extends Module {
    public enum Mode {
        NCP, SMOOTH, HYPIXEL, CUSTOM, L_CUSTOM, SOUTH_SIDE, LEGIT, LEGIT_TELLY;
        @Override public String toString() {
            return switch (this) {
                case NCP -> "NCP"; case SMOOTH -> "Smooth"; case HYPIXEL -> "Hypixel";
                case CUSTOM -> "Custom"; case L_CUSTOM -> "LCustom"; case SOUTH_SIDE -> "SouthSide";
                case LEGIT -> "Legit"; case LEGIT_TELLY -> "LegitTelly";
            };
        }
    }
    public enum ItemSpoof { NONE, SWITCH, SPOOF, LITE_SPOOF }
    public enum TowerMode { NONE, NCP, AAC, VANILLA }
    public enum PickingMode { BASIC, FAKE_INV, OPEN_INV }

    private final EnumSetting<Mode> mode = register(new EnumSetting<>("Mode", "Bridge policy from SigmaClient.", Mode.NCP));
    private final EnumSetting<ItemSpoof> itemSpoof = register(new EnumSetting<>("ItemSpoof", "How the block slot is selected.", ItemSpoof.SPOOF));
    private final EnumSetting<TowerMode> tower = register(new EnumSetting<>("Tower Mode", "Vertical bridging for the legacy modes.", TowerMode.NCP));
    private final EnumSetting<PickingMode> picking = register(new EnumSetting<>("Picking mode", "Basic/FakeInv use the player inventory; OpenInv requires the inventory screen. Modern protocols do not send inventory-open packets.", PickingMode.BASIC));
    private final BooleanSetting towerMoving = register(new BooleanSetting("Tower while moving", "Allow tower movement while walking.", false));
    private final BooleanSetting showAmount = register(new BooleanSetting("Show Block Amount", "Show the number of usable blocks.", true));
    private final BooleanSetting noSwing = register(new BooleanSetting("NoSwing", "Send the swing without playing the local animation.", true));
    private final BooleanSetting intelligentPicker = register(new BooleanSetting("Intelligent Block Picker", "Prefer the largest stack.", true));
    private final BooleanSetting noSprint = register(new BooleanSetting("No Sprint", "Disable sprint in the legacy bridge modes.", false));
    private final BooleanSetting gameSprint = register(new BooleanSetting("UesGameSprint", "Use the normal sprint key and sprint rules.", true));
    private final BooleanSetting keepFov = register(new BooleanSetting("KeepFOV", "Keep a stable movement FOV while bridging.", true));
    private final NumberSetting fovModifier = register(new NumberSetting("FOV Modifier", "Movement FOV multiplier.", 1.15F, 1, 2, 0.05F));
    private final EnumMap<Mode, BlockFlyMode> modes = new EnumMap<>(Mode.class);

    private BlockFlyMode active;
    private LocalPlayer sessionPlayer;
    private Object sessionLevel;
    private BlockFlyPlacement.Target pending;
    private Rotation requestedRotation;
    private MovementCorrection correction = MovementCorrection.OFF;
    private boolean silentRotation = true;
    private int lastPlaceTick = Integer.MIN_VALUE;
    private int originalSlot = -1;
    private int ownedSlot = -1;
    private int liteSlot = -1;
    private boolean internalSlotChange;
    private boolean actedThisTick;
    private float timerMultiplier = 1;
    private EventMotion motionEvent;

    public BlockFly() {
        super(ModuleCategory.MOVEMENT, "BlockFly", "Automatically bridges using the eight SigmaClient modes.");
        this.modes.put(Mode.NCP, new BlockFlyNCPMode(this));
        this.modes.put(Mode.SMOOTH, new BlockFlySmoothMode(this));
        this.modes.put(Mode.HYPIXEL, new BlockFlyHypixelMode(this));
        this.modes.put(Mode.CUSTOM, new BlockFlyCustomMode(this));
        this.modes.put(Mode.L_CUSTOM, new BlockFlyLCustomMode(this));
        this.modes.put(Mode.SOUTH_SIDE, new BlockFlySouthSideMode(this));
        this.modes.put(Mode.LEGIT, new BlockFlyLegitMode(this));
        this.modes.put(Mode.LEGIT_TELLY, new BlockFlyTellyMode(this));
        this.gameSprint.visibleWhen(() -> !this.noSprint.get());
        this.fovModifier.visibleWhen(this.keepFov::get);
        this.mode.onChange(() -> { if (isEnabled()) restart(); });
        this.itemSpoof.onChange(this::restoreSlot);
    }

    public <S extends Setting<?>> S modeSetting(final Mode selected, final S setting) {
        return modeSetting(selected, setting, () -> true);
    }

    public <S extends Setting<?>> S modeSetting(final Mode selected, final S setting, final BooleanSupplier condition) {
        setting.visibleWhen(() -> isMode(selected) && condition.getAsBoolean());
        return register(setting);
    }

    public boolean isMode(final Mode selected) { return this.mode.is(selected); }
    public boolean noSwing() { return this.noSwing.get(); }
    public ItemSpoof itemSpoof() { return this.itemSpoof.get(); }

    /**
     * Actual rotation/placement or inventory ownership, not the module's enabled flag. Retained
     * rotations still write movement reports; a LiteSpoof slot still rewrites carried-item packets,
     * so neither can safely overlap another automated item use. Game thread only.
     */
    public boolean isActivelyWorking() {
        return isEnabled() && ready() && this.active != null
                && this.sessionPlayer == mc.player && this.sessionLevel == mc.level
                && (this.requestedRotation != null || this.pending != null || this.actedThisTick
                    || this.internalSlotChange || this.liteSlot >= 0);
    }

    @Override protected void onEnable() { restart(); }
    @Override protected void onDisable() { stop(); }

    private void restart() {
        stop();
        if (!ready()) return;
        this.sessionPlayer = mc.player;
        this.sessionLevel = mc.level;
        this.active = this.modes.get(this.mode.get());
        this.active.enable();
    }

    private void stop() {
        try {
            if (this.active != null) this.active.disable();
        } finally {
            try {
                restoreSlot();
            } finally {
                clearRotation();
                this.pending = null;
                this.motionEvent = null;
                this.lastPlaceTick = Integer.MIN_VALUE;
                this.actedThisTick = false;
                this.timerMultiplier = 1;
                this.active = null;
                this.sessionPlayer = null;
                this.sessionLevel = null;
            }
        }
    }

    private boolean ready() {
        return !AutoPearl.isRescuing() && mc.player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null
                && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isPassenger();
    }

    @EventTarget(EventPriority.HIGH)
    public void onTick(final EventTick event) {
        if (event.isPre()) this.actedThisTick = false;
        if (event.isPre() && ready() && (this.active == null || this.sessionPlayer != mc.player || this.sessionLevel != mc.level)) restart();
        if (this.active == null) return;
        if (!ready()) {
            stop();
            return;
        }
        if (event.isPre()) {
            this.pending = null;
        }
        this.active.tick(event);
        if (event.isPre() && this.requestedRotation != null) requestCorrection();
    }

    @EventTarget public void onUpdate(final EventUpdate event) {
        if (ready() && this.active != null) this.active.update(event);
    }

    @EventTarget public void onLivingUpdate(final EventLivingUpdate event) {
        if (ready() && this.active != null) this.active.livingUpdate(event);
    }

    @EventTarget(EventPriority.LOWEST)
    public void onMotion(final EventMotion event) {
        if (!ready() || this.active == null) return;
        this.motionEvent = event;
        try {
            if (event.isPre() && this.requestedRotation != null) {
                aim(event, this.requestedRotation, this.silentRotation, this.correction);
            }
            this.active.motion(event);
            if (event.isPost()) {
                BlockFlyPlacement.Target target = this.pending;
                this.pending = null;
                if (!event.isCancelled() && target != null) place(target);
            }
        } finally {
            this.motionEvent = null;
        }
    }

    @EventTarget public void onInput(final EventMovementInput event) {
        if (!ready() || this.active == null) return;
        this.active.input(event);
        if (this.active.usesSharedMovement()) {
            if (this.noSprint.get()) {
                event.setSprint(false);
                mc.player.setSprinting(false);
            } else if (!this.gameSprint.get() && event.isMoving() && !event.getInput().shift()) {
                event.setSprint(true);
                mc.player.setSprinting(true);
            }
        }
    }

    @EventTarget public void onMove(final EventMove event) {
        if (!ready() || this.active == null || event.getMoverType() != MoverType.SELF) return;
        this.active.move(event);
        if (this.active.usesSharedMovement()) tower(event);
    }

    @EventTarget public void onSafeWalk(final EventSafeWalk event) {
        if (ready() && this.active != null) this.active.safeWalk(event);
    }

    @EventTarget public void onFov(final EventGetFovModifier event) {
        if (!ready() || this.active == null) return;
        if (!isMode(Mode.SOUTH_SIDE) && this.keepFov.get() && Speed.isMoving(mc.player.input.keyPresses)) {
            var speed = mc.player.getEffect(MobEffects.SPEED);
            event.setFovModifier(this.fovModifier.get() + (speed == null ? 0 : (speed.getAmplifier() + 1) * 0.13F));
        }
        this.active.fov(event);
    }

    @EventTarget public void onRender(final EventRender2D event) {
        if (!ready() || this.active == null) return;
        this.active.render(event);
        if (event.isPost() && this.showAmount.get() && !isMode(Mode.SOUTH_SIDE) && !mc.gui.hud.isHidden()) {
            String text = blockCount() + " Blocks";
            int x = (event.getWidth() - mc.font.width(text)) / 2;
            int y = event.getHeight() - 70;
            event.getGraphics().fill(x - 6, y - 4, x + mc.font.width(text) + 6, y + 13, 0xB0202228);
            event.getGraphics().text(mc.font, text, x, y, 0xFFFFFFFF, true);
        }
    }

    @EventTarget public void onRender3D(final EventRender3D event) {
        if (ready() && this.active != null) this.active.render3D(event);
    }

    @EventTarget public void onClick(final EventClick event) {
        releaseLiteSlot();
        if (ready() && this.active != null) this.active.click(event);
    }

    @EventTarget public void onKey(final EventKeyPress event) {
        if (ready() && this.active != null) this.active.keyPress(event);
    }

    @EventTarget public void onLoadWorld(final EventLoadWorld event) {
        if (this.active != null) this.active.worldLoad(event);
        stop();
    }

    @EventTarget public void onSendPacket(final EventSendPacket event) {
        // Packet hooks can run on Netty. Mutable bridge state belongs to the client thread.
        if (!mc.isSameThread()) return;
        if (this.liteSlot >= 0 && !this.internalSlotChange && event.getPacket() instanceof ServerboundSetCarriedItemPacket) {
            event.setPacket(new ServerboundSetCarriedItemPacket(this.liteSlot));
        }
        if (this.active != null) this.active.sendPacket(event);
    }

    @EventTarget public void onReceivePacket(final EventReceivePacket event) {
        // Modes may inspect immutable packet data; they must schedule world changes on the client thread.
        if (this.active != null) this.active.receivePacket(event);
    }

    public void requestRotation(final Rotation rotation, final boolean silent, final MovementCorrection correction) {
        if (rotation == null || !Float.isFinite(rotation.yaw()) || !Float.isFinite(rotation.pitch())) return;
        this.requestedRotation = new Rotation(rotation.yaw(), Mth.clamp(rotation.pitch(), -90, 90));
        this.silentRotation = silent;
        this.correction = correction;
        requestCorrection();
        if (!silent && mc.player != null) {
            mc.player.setYRot(this.requestedRotation.yaw());
            mc.player.setXRot(this.requestedRotation.pitch());
        }
    }

    private void requestCorrection() {
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null && this.requestedRotation != null) corrector.request(this, this.requestedRotation, this.correction, 100);
    }

    public void aim(final EventMotion event, final Rotation rotation, final boolean silent, final MovementCorrection correction) {
        requestRotation(rotation, silent, correction);
        if (event.isPre() && this.requestedRotation != null) {
            event.setYaw(this.requestedRotation.yaw());
            event.setPitch(this.requestedRotation.pitch());
            event.forceRotation();
        }
    }

    public void clearRotation() {
        this.requestedRotation = null;
        MovementCorrector corrector = MovementCorrector.current();
        if (corrector != null) corrector.release(this);
    }

    public Rotation rotation() {
        return this.requestedRotation != null ? this.requestedRotation
                : mc.player == null ? new Rotation(0, 0) : new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    public void queuePlacement(final BlockFlyPlacement.Target target) { this.pending = target; }

    /** Called only after the movement packet. The actual sent facing wins over the earlier plan. */
    public boolean place(final BlockFlyPlacement.Target target) {
        if (!ready() || target == null || this.motionEvent == null || !this.motionEvent.isPost()
                || this.motionEvent.isCancelled() || this.lastPlaceTick == mc.player.tickCount) return false;
        Vec3 eyes = new Vec3(this.motionEvent.getX(), this.motionEvent.getY() + mc.player.getEyeHeight(), this.motionEvent.getZ());
        Rotation sent = new Rotation(this.motionEvent.getYaw(), this.motionEvent.getPitch());
        if (!BlockFlyPlacement.valid(mc.player, eyes, target, sent)) return false;
        var hit = BlockFlyPlacement.raycast(mc.player, eyes, sent);
        if (hit == null) return false;
        Inventory inventory = mc.player.getInventory();
        int previous = inventory.getSelectedSlot();
        boolean modeOwnsSlot = isMode(Mode.CUSTOM) || isMode(Mode.L_CUSTOM);
        ItemSpoof policy = modeOwnsSlot ? ItemSpoof.NONE : this.itemSpoof.get();
        int slot = policy == ItemSpoof.NONE ? previous : hotbarSlot();
        InteractionHand hand = slot >= 0 && BlockFlyPlacement.isPlaceable(inventory.getItem(slot))
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (hand == InteractionHand.OFF_HAND && !BlockFlyPlacement.isPlaceable(mc.player.getOffhandItem())) return false;
        this.actedThisTick = true;
        this.internalSlotChange = true;
        int previousLiteSlot = this.liteSlot;
        boolean interacted = false;
        float cameraYaw = mc.player.getYRot(), cameraPitch = mc.player.getXRot();
        try {
            if (hand == InteractionHand.MAIN_HAND && slot != previous) {
                if (policy == ItemSpoof.SWITCH && (this.originalSlot < 0 || previous != this.ownedSlot)) this.originalSlot = previous;
                inventory.setSelectedSlot(slot);
                this.ownedSlot = slot;
            }
            if (policy == ItemSpoof.LITE_SPOOF && hand == InteractionHand.MAIN_HAND) this.liteSlot = slot;
            // Native block-state prediction (stairs, observers, etc.) must use the reported facing.
            mc.player.setYRot(sent.yaw());
            mc.player.setXRot(sent.pitch());
            BlockPlaceContext context = new BlockPlaceContext(mc.player, hand, mc.player.getItemInHand(hand), hit);
            if (!context.canPlace() || !context.getClickedPos().equals(target.placedPos())) return false;
            this.lastPlaceTick = mc.player.tickCount;
            boolean success = mc.gameMode.useItemOn(mc.player, hand, hit).consumesAction();
            interacted = true;
            if (success) swing(hand);
            return success;
        } finally {
            mc.player.setYRot(cameraYaw);
            mc.player.setXRot(cameraPitch);
            if (!interacted) this.liteSlot = previousLiteSlot;
            if (policy == ItemSpoof.SPOOF || policy == ItemSpoof.LITE_SPOOF) {
                inventory.setSelectedSlot(previous);
                if (policy == ItemSpoof.SPOOF) mc.gameMode.syncCarriedItem();
            }
            this.internalSlotChange = false;
        }
    }

    public void swing() { swing(InteractionHand.MAIN_HAND); }
    public void swing(final InteractionHand hand) {
        if (mc.player == null) return;
        if (this.noSwing.get()) mc.player.connection.send(new ServerboundSwingPacket(hand));
        else mc.player.swing(hand);
    }

    public int hotbarSlot() {
        if (mc.player == null) return -1;
        Inventory inventory = mc.player.getInventory();
        int result = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (BlockFlyPlacement.isPlaceable(stack)
                    && (result < 0 || this.intelligentPicker.get() && stack.getCount() > inventory.getItem(result).getCount())) result = i;
        }
        return result;
    }

    public int blockCount() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (BlockFlyPlacement.isPlaceable(stack)) count += stack.getCount();
        }
        if (BlockFlyPlacement.isPlaceable(mc.player.getOffhandItem())) count += mc.player.getOffhandItem().getCount();
        return count;
    }

    public boolean hasBlocks() {
        if (mc.player == null) return false;
        return this.itemSpoof.is(ItemSpoof.NONE)
                ? BlockFlyPlacement.isPlaceable(mc.player.getMainHandItem()) || BlockFlyPlacement.isPlaceable(mc.player.getOffhandItem())
                : blockCount() > 0;
    }

    public void replenish() {
        if (!ready() || this.itemSpoof.is(ItemSpoof.NONE) || mc.player.containerMenu != mc.player.inventoryMenu
                || (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen))
                || this.picking.is(PickingMode.OPEN_INV) && !(mc.gui.screen() instanceof InventoryScreen)) return;
        Inventory inventory = mc.player.getInventory();
        int hotbar = hotbarSlot();
        if (hotbar >= 0 && !this.intelligentPicker.get()) return;
        int best = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (BlockFlyPlacement.isPlaceable(stack) && (best < 0 || stack.getCount() > inventory.getItem(best).getCount())) best = i;
        }
        if (best < 0 || hotbar >= 0 && inventory.getItem(best).getCount() <= inventory.getItem(hotbar).getCount()) return;
        if (hotbar < 0) {
            hotbar = 7;
            for (int i = 0; i < 9; i++) if (inventory.getItem(i).isEmpty()) { hotbar = i; break; }
        }
        this.actedThisTick = true;
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, best, hotbar, ContainerInput.SWAP, mc.player);
    }

    private void releaseLiteSlot() {
        if (this.liteSlot < 0) return;
        this.liteSlot = -1;
        if (mc.player != null && mc.player == this.sessionPlayer && mc.getConnection() != null) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
            if (mc.gameMode != null) mc.gameMode.syncCarriedItem();
        }
    }

    private void restoreSlot() {
        if (mc.player != null && mc.player == this.sessionPlayer && mc.getConnection() != null && mc.gameMode != null) {
            this.internalSlotChange = true;
            try {
                Inventory inventory = mc.player.getInventory();
                if (this.originalSlot >= 0 && inventory.getSelectedSlot() == this.ownedSlot) inventory.setSelectedSlot(this.originalSlot);
                if (this.liteSlot >= 0) mc.player.connection.send(new ServerboundSetCarriedItemPacket(inventory.getSelectedSlot()));
                mc.gameMode.syncCarriedItem();
            } finally { this.internalSlotChange = false; }
        }
        this.originalSlot = this.ownedSlot = this.liteSlot = -1;
    }

    public ItemStack renderedItem(final InteractionHand hand, final ItemStack original) {
        return this.active == null ? original : this.active.renderedItem(hand, original);
    }

    public Rotation cameraRotation() {
        return ready() && this.active != null ? this.active.cameraRotation() : null;
    }

    public void timer(final float multiplier) {
        this.timerMultiplier = Float.isFinite(multiplier) ? Mth.clamp(multiplier, 0.1F, 5.0F) : 1.0F;
    }

    public float timerMultiplier() {
        return ready() && this.sessionPlayer == mc.player && mc.gui.screen() == null ? this.timerMultiplier : 1;
    }

    private void tower(final EventMove event) {
        if (this.timerMultiplier == 0.8038576F) timer(1);
        LocalPlayer player = mc.player;
        if (this.tower.is(TowerMode.NONE) || !hasBlocks() || !mc.options.keyJump.isDown()
                || !this.towerMoving.get() && Speed.isMoving(player.input.keyPresses)
                || player.getAbilities().flying || player.isInWater() || player.isInLava()) return;
        boolean supported = !mc.level.noCollision(player, player.getBoundingBox().move(0, -0.01, 0));
        switch (this.tower.get()) {
            case NCP, AAC -> {
                if (this.tower.is(TowerMode.NCP) && player.hasEffect(MobEffects.JUMP_BOOST) && event.getY() > 0) {
                    double next = player.getY() + event.getY();
                    if (Mth.floor(next) != Mth.floor(player.getY()) && next - Mth.floor(next) < 0.15) event.setY(Mth.floor(next) - player.getY());
                } else if (event.getY() > 0.247 && event.getY() < 0.249) {
                    event.setY(Mth.floor(player.getY() + event.getY()) - player.getY());
                }
                if (supported && event.getY() <= 0 && Math.abs(player.getY() - Math.rint(player.getY())) < 1.0E-5) {
                    player.jumpFromGround();
                    event.setY(player.getDeltaMovement().y);
                    if (!Speed.isMoving(player.input.keyPresses)) { event.setX(0); event.setZ(0); }
                }
                player.setDeltaMovement(player.getDeltaMovement().x, event.getY(), player.getDeltaMovement().z);
            }
            case VANILLA -> {
                if (supported && mc.level.noCollision(player, player.getBoundingBox().move(0, 1, 0))) {
                    player.setPos(player.getX(), player.getY() + 1, player.getZ());
                    event.setDelta(Vec3.ZERO);
                    player.setDeltaMovement(Vec3.ZERO);
                    timer(0.8038576F);
                }
            }
            case NONE -> { }
        }
    }
}
