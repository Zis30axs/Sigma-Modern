package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

public final class BlockFlySmoothMode extends BlockFlyLegacyMode {
    private final EnumSetting<SpeedMode> speed;
    private final NumberSetting constant;

    public BlockFlySmoothMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.SMOOTH);
        this.speed = parent.modeSetting(BlockFly.Mode.SMOOTH, new EnumSetting<>("Smooth Speed Mode", "Movement while bridging.", SpeedMode.NONE));
        this.constant = parent.modeSetting(BlockFly.Mode.SMOOTH, new NumberSetting("Smooth Constant Speed", "Blocks per tick in Constant mode.", 0.0F, 0.0F, 6.0F, 0.1F), () -> this.speed.is(SpeedMode.CONSTANT));
    }

    @Override SpeedMode speedMode() { return this.speed.get(); }
    @Override float constantSpeed() { return this.constant.get(); }
}
