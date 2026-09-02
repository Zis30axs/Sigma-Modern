package net.minecraft.client.gui.screens.options;

import com.mojang.blaze3d.platform.InputConstants;
import com.viaversion.viafabricplus.features.mouse_sensitivity.MouseSensitivity1_13_2;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.Arrays;
import java.util.stream.Stream;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class MouseSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.translatable("options.mouse_settings.title");

    private static OptionInstance<?>[] options(final Options options) {
        return new OptionInstance[]{
            options.sensitivity(),
            options.mouseWheelSensitivity(),
            options.discreteMouseScroll(),
            options.invertMouseX(),
            options.invertMouseY(),
            options.allowCursorChanges()
        };
    }

    public MouseSettingsScreen(final Screen lastScreen, final Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        if (InputConstants.isRawMouseInputSupported()) {
            this.list.addSmall(Stream.concat(Arrays.stream(options(this.options)), Stream.of(this.options.rawMouseInput())).toArray(OptionInstance[]::new));
        } else {
            this.list.addSmall(options(this.options));
        }
    }

    // MODIFIED for porting: was VFP mouse_sensitivity MixinMouseSettingsScreen#extractRenderState (mixin declared as
    // a subclass of OptionsSubScreen, so the override itself is the hook). <= 1.13.2 quantised the sensitivity slider,
    // so the hovered row shows the value that is actually sent. Sigma's list / findOption are nullable, upstream's are
    // not, hence the two extra null checks.
    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_13_2) && this.list != null) {
            final AbstractWidget sensitivityRow = this.list.findOption(this.options.sensitivity());
            if (sensitivityRow != null && sensitivityRow.isHovered()) {
                final int displayValue = MouseSensitivity1_13_2.get1_13SliderValue(this.options.sensitivity().get().floatValue()).valueInt();
                graphics.setTooltipForNextFrame(this.font, Component.nullToEmpty("<=1.13.2 Sensitivity: " + displayValue + "%"), mouseX, mouseY);
            }
        }
    }
}