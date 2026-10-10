package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

public final class BlockFlyHypixelMode extends BlockFlyLegacyMode {
    private final EnumSetting<SpeedMode> speed;
    private final BooleanSetting keep;
    private final BooleanSetting down;
    private final NumberSetting constant;

    public BlockFlyHypixelMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.HYPIXEL);
        this.speed = parent.modeSetting(BlockFly.Mode.HYPIXEL, new EnumSetting<>("Hypixel Speed Mode", "Movement while bridging.", SpeedMode.NONE));
        this.keep = parent.modeSetting(BlockFly.Mode.HYPIXEL, new BooleanSetting("Hypixel KeepRotations", "Keep looking down between placements.", true));
        this.down = parent.modeSetting(BlockFly.Mode.HYPIXEL, new BooleanSetting("Hypixel Downwards", "Hold sneak to bridge one block lower.", true));
        this.constant = parent.modeSetting(BlockFly.Mode.HYPIXEL, new NumberSetting("Hypixel Constant Speed", "Blocks per tick in Constant mode.", 0.0F, 0.0F, 6.0F, 0.1F), () -> this.speed.is(SpeedMode.CONSTANT));
    }

    @Override SpeedMode speedMode() { return this.speed.get(); }
    @Override boolean keepRotations() { return this.keep.get(); }
    @Override boolean downwards() { return this.down.get(); }
    @Override float constantSpeed() { return this.constant.get(); }
}
