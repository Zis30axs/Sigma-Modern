package net.minecraft.world.item;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.player.Player;

public class NameTagItem extends Item {
    public NameTagItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(final ItemStack itemStack, final Player player, final LivingEntity target, final InteractionHand type) {
        // MODIFIED for porting: was VFP item.interaction MixinNameTagItem#dontAllowNameTagsOnCreaking (@Inject HEAD cancellable)
        // Targets <=1.21.4 reject name tags on creakings, so the interaction must pass through untouched.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21_4) && target instanceof Creaking) {
            return InteractionResult.PASS;
        }

        Component customName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (customName != null && target.getType().canSerialize()) {
            if (!player.level().isClientSide() && target.isAlive()) {
                target.setCustomName(customName);
                if (target instanceof Mob mob) {
                    mob.setPersistenceRequired();
                }

                itemStack.shrink(1);
            }

            return InteractionResult.SUCCESS;
        } else {
            return InteractionResult.PASS;
        }
    }
}