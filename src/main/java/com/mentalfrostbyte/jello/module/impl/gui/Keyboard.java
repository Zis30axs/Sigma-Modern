package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/** Modern's movable input display; Jello's existing KeyStrokes module keeps its own presentation. */
public final class Keyboard extends Module {
    private final BooleanSetting mouseButtons = this.register(new BooleanSetting("Mouse Buttons",
            "Shows LMB and RMB below the movement keys.", true));
    private final BooleanSetting cps = this.register(new BooleanSetting("CPS",
            "Shows physical left and right mouse clicks in the last second, excluding menu clicks.", true));
    private final NumberSetting scale = this.register(new NumberSetting("Scale",
            "Size of the Modern keyboard and mouse display.", 1F, 0.65F, 1.5F, 0.05F));
    private final NumberSetting background = this.register(new NumberSetting("Background",
            "Opacity of the unpressed key backgrounds.", 0.3F, 0F, 1F, 0.05F));

    public Keyboard() {
        super(ModuleCategory.INTERFACE, "Keyboard", "Modern: displays movement keys, mouse buttons and CPS. Hold Alt to move it.");
        this.cps.visibleWhen(this.mouseButtons::get);
    }

    @Override public boolean isEnabledByDefault() { return true; }
    public boolean showsMouseButtons() { return this.mouseButtons.get(); }
    public boolean showsCps() { return this.mouseButtons.get() && this.cps.get(); }
    public float getScale() { return this.scale.get(); }
    public float getBackground() { return this.background.get(); }
}
