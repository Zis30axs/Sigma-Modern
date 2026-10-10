package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.module.impl.movement.BlockFly;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

public final class BlockFlyNCPMode extends BlockFlyLegacyMode {
    public enum NcpSpeed { NONE, JUMP, AAC, SLOW, SNEAK }
    private final EnumSetting<NcpSpeed> speed;
    private final BooleanSetting keep;
    private final NumberSetting extension;
    private final BooleanSetting down;

    public BlockFlyNCPMode(final BlockFly parent) {
        super(parent, BlockFly.Mode.NCP);
        this.speed = parent.modeSetting(BlockFly.Mode.NCP, new EnumSetting<>("NCP Speed Mode", "Movement while bridging.", NcpSpeed.NONE));
        this.keep = parent.modeSetting(BlockFly.Mode.NCP, new BooleanSetting("NCP KeepRotations", "Keep the last placement rotation.", true));
        this.extension = parent.modeSetting(BlockFly.Mode.NCP, new NumberSetting("NCP Extend", "How far ahead to search for the bridge edge.", 0.0F, 0.0F, 6.0F, 0.1F));
        this.down = parent.modeSetting(BlockFly.Mode.NCP, new BooleanSetting("NCP Downwards", "Hold sneak to bridge one block lower.", true));
    }

    @Override SpeedMode speedMode() {
        return switch (this.speed.get()) {
            case NONE -> SpeedMode.NONE;
            case JUMP -> SpeedMode.JUMP;
            case AAC -> SpeedMode.AAC;
            case SLOW -> SpeedMode.SLOW;
            case SNEAK -> SpeedMode.SNEAK;
        };
    }
    @Override boolean keepRotations() { return this.keep.get(); }
    @Override boolean downwards() { return this.down.get(); }
    @Override float extend() { return this.extension.get(); }
}
