package net.minecraft.client.gui.screens.options;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.shaderpack.ShaderPackManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class ShaderPackScreen extends Screen {
    private static final Component TITLE = Component.literal("Shader Packs");
    private static final Component DISABLED = Component.literal("Shaders: Off");
    private final Screen lastScreen;
    private final ShaderPackManager manager;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 57, 33);
    private ShaderPackScreen.PackList packList;

    public ShaderPackScreen(final Screen lastScreen, final ShaderPackManager manager) {
        super(TITLE);
        this.lastScreen = lastScreen;
        this.manager = manager;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(5));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(TITLE, this.font));
        header.addChild(new StringWidget(this.backendStatus(), this.font));
        header.addChild(new StringWidget(Component.literal("Shader pack loading and parsing are ready; world shader rendering is not active yet."), this.font));

        this.packList = this.layout.addToContents(new ShaderPackScreen.PackList());

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.literal("Open Shader Pack Folder"), button -> {
            this.manager.ensureDirectory();
            Util.getPlatform().openPath(this.manager.shaderPackDirectory());
        }).width(150).build());
        footer.addChild(Button.builder(Component.literal("Refresh"), button -> this.packList.refreshEntries()).width(70).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(80).build());

        this.layout.visitWidgets(widget -> this.addRenderableWidget(widget));
        this.repositionElements();
    }

    private Component backendStatus() {
        if (this.manager.canUseShaders()) {
            return Component.literal("Graphics backend: " + this.manager.backendName() + " - shader pack loading available");
        }

        return Component.literal("Graphics backend: " + this.manager.backendName() + " - switch to OpenGL to load shader packs");
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.packList != null) {
            this.packList.updateSize(this.width, this.layout);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.lastScreen);
    }

    private class PackList extends ContainerObjectSelectionList<ShaderPackScreen.PackEntry> {
        private PackList() {
            super(Minecraft.getInstance(), ShaderPackScreen.this.width, ShaderPackScreen.this.layout.getContentHeight(), ShaderPackScreen.this.layout.getHeaderHeight(), 24);
            this.centerListVertically = false;
            this.refreshEntries();
        }

        private void refreshEntries() {
            this.clearEntries();
            this.addEntry(new ShaderPackScreen.PackEntry(null));
            for (String pack : ShaderPackScreen.this.manager.discoverPacks()) {
                this.addEntry(new ShaderPackScreen.PackEntry(pack));
            }
            this.refreshScrollAmount();
        }

        @Override
        public int getRowWidth() {
            return 310;
        }
    }

    private class PackEntry extends ContainerObjectSelectionList.Entry<ShaderPackScreen.PackEntry> {
        private final @Nullable String packName;
        private final boolean valid;
        private final long renderablePrograms;
        private final Button selectButton;
        private final List<GuiEventListener> children = new ArrayList<>();

        private PackEntry(final @Nullable String packName) {
            this.packName = packName;
            this.valid = packName == null || ShaderPackScreen.this.manager.isValidShaderPack(packName);
            this.renderablePrograms = packName != null && this.valid
                ? ShaderPackScreen.this.manager.inspectPrograms(packName).map(programs -> programs.renderableProgramCount()).orElse(0L)
                : 0L;
            this.selectButton = Button.builder(this.label(), button -> {
                if (ShaderPackScreen.this.manager.select(this.packName)) {
                    ShaderPackScreen.this.packList.refreshEntries();
                }
            }).width(310).build();
            this.selectButton.active = this.canSelect();
            this.children.add(this.selectButton);
        }

        private Component label() {
            if (this.packName == null) {
                return DISABLED;
            }

            if (!this.valid) {
                return Component.literal("Invalid shader pack: " + this.packName);
            }

            String programInfo = " [" + this.renderablePrograms + " programs]";
            if (this.isSelected()) {
                return Component.literal("Selected: " + this.packName + programInfo);
            }

            return Component.literal(this.packName + programInfo);
        }

        private boolean canSelect() {
            if (this.packName == null) {
                return ShaderPackScreen.this.manager.selectedPack().isPresent();
            }

            return this.valid && ShaderPackScreen.this.manager.canUseShaders() && !this.isSelected();
        }

        private boolean isSelected() {
            return this.packName == null ? ShaderPackScreen.this.manager.selectedPack().isEmpty() : ShaderPackScreen.this.manager.isSelected(this.packName);
        }

        @Override
        public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
            this.selectButton.setPosition(this.getContentX(), this.getContentY());
            this.selectButton.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.selectButton);
        }
    }
}
