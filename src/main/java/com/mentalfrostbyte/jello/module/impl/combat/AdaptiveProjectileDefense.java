package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.player.EventStopUsingItem;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMovementInput;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.module.impl.player.AutoPearl;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.game.IncomingProjectiles;
import com.mentalfrostbyte.jello.util.game.ProjectileDefensePlanner.Action;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import org.jspecify.annotations.Nullable;

public final class AdaptiveProjectileDefense extends Module {
    private final NumberSetting lookahead = register(new NumberSetting("Lookahead",
            "Ticks of incoming arrow and trident flight to predict.", 16, 4, 30, 1));
    private final BooleanSetting useShield = register(new BooleanSetting("Use Shield",
            "Block with an equipped shield when already facing the incoming shot.", true));

    private @Nullable LocalPlayer owner;
    private @Nullable InteractionHand ownedHand;
    private ItemStack ownedStack = ItemStack.EMPTY;

    public AdaptiveProjectileDefense() {
        super(ModuleCategory.COMBAT, "AdaptiveProjectileDefense",
                "Predicts incoming arrows and tridents, then blocks or sidesteps an expected hit.");
    }

    @EventTarget(EventPriority.LOW)
    public void onInput(EventMovementInput event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level != player.level() || mc.gui.screen() != null
                || !player.isAlive() || player.isSpectator() || player.getAbilities().invulnerable
                || player.getAbilities().flying || player.isPassenger() || player.isFallFlying() || player.isSleeping()
                || player.onClimbable() || player.isInLiquid() || mc.options.keyUse.isDown() || otherModuleBusy()) {
            releaseShield();
            return;
        }
        if (!ownsShield(player)) forgetShield();
        if (player.isUsingItem() && owner == null) return;

        InteractionHand hand = shieldHand(player);
        var plan = IncomingProjectiles.plan(player, event.getInput(), event.getMoveVector(),
                hand == null ? ItemStack.EMPTY : player.getItemInHand(hand), lookahead.getInt());
        if (plan.action() == Action.BLOCK && hand != null) {
            if (ownsShield(player)) return;
            ItemStack stack = player.getItemInHand(hand);
            mc.gameMode.useItem(player, hand);
            if (player.isUsingItem() && player.getUsedItemHand() == hand && player.getUseItem() == stack) {
                owner = player;
                ownedHand = hand;
                ownedStack = stack;
                return;
            }
            // An interaction listener, protocol or item restriction can still reject an equipped shield.
            plan = IncomingProjectiles.plan(player, event.getInput(), event.getMoveVector(), ItemStack.EMPTY, lookahead.getInt());
        }
        releaseShield();
        if (plan.action() == Action.DODGE) {
            event.setStrafe(plan.input().x);
            event.setForward(plan.input().y);
        }
    }

    private @Nullable InteractionHand shieldHand(LocalPlayer player) {
        if (!useShield.get() || ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) return null;
        if (ownsShield(player)) return ownedHand;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ShieldItem && stack.has(DataComponents.BLOCKS_ATTACKS)
                    && !player.getCooldowns().isOnCooldown(stack)) return hand;
        }
        return null;
    }

    private static boolean otherModuleBusy() {
        if (AutoPearl.isRescuing()) return true;
        BlockFly scaffold = Modules.enabled(BlockFly.class);
        KillAura aura = Modules.enabled(KillAura.class);
        AutoProjectile thrower = Modules.enabled(AutoProjectile.class);
        return (scaffold != null && scaffold.isActivelyWorking()) || (aura != null && aura.isActivelyWorking())
                || (thrower != null && thrower.isActivelyWorking());
    }

    @EventTarget
    public void onStopUsingItem(EventStopUsingItem event) {
        if (mc.gui.screen() == null && ownsShield(mc.player)) event.setCancelled(true);
    }

    private boolean ownsShield(@Nullable LocalPlayer player) {
        return player != null && player == owner && player.isUsingItem() && player.getUsedItemHand() == ownedHand
                && player.getUseItem() == ownedStack && player.getItemInHand(ownedHand) == ownedStack;
    }

    private void releaseShield() {
        if (ownsShield(mc.player) && mc.gameMode != null && !mc.options.keyUse.isDown()
                && mc.player.connection.getConnection().isConnected()) mc.gameMode.releaseUsingItem(mc.player);
        forgetShield();
    }

    private void forgetShield() {
        owner = null;
        ownedHand = null;
        ownedStack = ItemStack.EMPTY;
    }

    @EventTarget
    public void onLoadWorld(EventLoadWorld event) { forgetShield(); }

    @Override
    protected void onDisable() { releaseShield(); }
}
