package net.minecraft.client.gui.screens.options;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.GpuWarnlistManager;
import net.minecraft.client.renderer.shaderpack.ShaderPackManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class VideoSettingsScreen extends Screen {
    private static final Component TITLE = Component.literal("Sigma Video Settings");
    private static final Component IMPROVED_TRANSPARENCY = Component.translatable("options.improvedTransparency").withStyle(ChatFormatting.ITALIC);
    private static final Component WARNING_MESSAGE = Component.translatable("options.graphics.warning.message", IMPROVED_TRANSPARENCY, IMPROVED_TRANSPARENCY);
    private static final Component WARNING_TITLE = Component.translatable("options.graphics.warning.title").withStyle(ChatFormatting.RED);
    private static final Component BUTTON_ACCEPT = Component.translatable("options.graphics.warning.accept");
    private static final Component BUTTON_CANCEL = Component.translatable("options.graphics.warning.cancel");
    private static final Component RESTART_REQUIRED = Component.translatable("options.restartRequired").withColor(-2142128);
    private static final Component SEARCH_HINT = Component.literal("Search settings...").withStyle(EditBox.SEARCH_HINT_STYLE);
    private static final int OUTER_MARGIN = 12;
    private static final int GAP = 6;
    private static final int TOP_ROW_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int ACTION_BUTTON_WIDTH = 76;
    private static final int SHADER_BUTTON_WIDTH = 116;
    private static final int PANEL_BACKGROUND = 0xC0101010;
    private static final int RAIL_BACKGROUND = 0xA0181818;
    private static final int PAGE_BACKGROUND = 0x900B0B0B;
    private static final int ROW_BACKGROUND = 0xA0141414;
    private static final int ROW_HOVER = 0xC0252525;
    private static final int SELECTED_BACKGROUND = 0xD02A2A2A;
    private static final int ACCENT = 0xFF62A6D8;
    private static final int TEXT = 0xFFF0F0F0;
    private static final int MUTED_TEXT = 0xFFAAAAAA;
    private static final int SEARCH_MATCH = 0xFFFFFF55;

    private final Screen lastScreen;
    private final Options options;
    private final GpuWarnlistManager gpuWarnlistManager;
    private final ShaderPackManager shaderPackManager;
    private final Map<OptionInstance<?>, Object> baseline = new IdentityHashMap<>();
    private final EnumMap<VideoSettingsScreen.Category, Double> scrollOffsets = new EnumMap<>(VideoSettingsScreen.Category.class);
    private int appliedMipmaps;
    private int appliedAnisotropyBit;
    private TextureFilteringMethod appliedTextureFiltering;
    private boolean baselineCaptured;
    private VideoSettingsScreen.Category selectedCategory = VideoSettingsScreen.Category.GENERAL;
    private String searchQuery = "";
    private @Nullable EditBox searchBox;
    private @Nullable VideoSettingsScreen.OptionPageList optionList;
    private @Nullable VideoSettingsScreen.FlatButton applyButton;
    private @Nullable VideoSettingsScreen.FlatButton doneButton;
    private @Nullable OptionInstance<Integer> fullscreenResolutionOption;
    private int panelX;
    private int panelWidth;
    private int topY;
    private int bodyY;
    private int bodyHeight;
    private int railWidth;
    private int listX;
    private int listWidth;
    private int footerY;

    public VideoSettingsScreen(final Screen lastScreen, final Minecraft minecraft, final Options options) {
        super(TITLE);
        this.lastScreen = lastScreen;
        this.options = options;
        this.gpuWarnlistManager = minecraft.getGpuWarnlistManager();
        this.gpuWarnlistManager.resetWarnings();
        if (options.improvedTransparency().get()) {
            this.gpuWarnlistManager.dismissWarning();
        }

        this.shaderPackManager = new ShaderPackManager(minecraft.gameDirectory.toPath());
        this.appliedMipmaps = options.mipmapLevels().get();
        this.appliedAnisotropyBit = options.maxAnisotropyBit().get();
        this.appliedTextureFiltering = options.textureFiltering().get();
    }

    @Override
    protected void init() {
        this.calculateLayout();
        this.ensureFullscreenResolutionOption();

        int searchWidth = Math.max(110, this.panelWidth - SHADER_BUTTON_WIDTH - GAP);
        this.searchBox = new EditBox(this.font, this.panelX, this.topY, searchWidth, TOP_ROW_HEIGHT, SEARCH_HINT);
        this.searchBox.setValue(this.searchQuery);
        this.searchBox.setHint(SEARCH_HINT);
        this.searchBox.setResponder(value -> {
            if (!Objects.equals(value, this.searchQuery)) {
                this.searchQuery = value;
                this.refreshOptionList();
            }
        });
        this.addRenderableWidget(this.searchBox);

        this.addRenderableWidget(
            new VideoSettingsScreen.FlatButton(
                this.panelX + searchWidth + GAP,
                this.topY,
                SHADER_BUTTON_WIDTH,
                TOP_ROW_HEIGHT,
                Component.literal("Shader Packs..."),
                () -> this.minecraft.gui.setScreen(new ShaderPackScreen(this, this.shaderPackManager)),
                () -> false,
                true
            )
        );

        int categoryY = this.bodyY + 4;
        for (VideoSettingsScreen.Category category : VideoSettingsScreen.Category.values()) {
            VideoSettingsScreen.FlatButton categoryButton = new VideoSettingsScreen.FlatButton(
                this.panelX + 4,
                categoryY,
                this.railWidth - 8,
                22,
                category.label,
                () -> this.selectCategory(category),
                () -> this.selectedCategory == category,
                false
            );
            this.addRenderableWidget(categoryButton);
            categoryY += 24;
        }

        this.optionList = new VideoSettingsScreen.OptionPageList(this.listX, this.bodyY, this.listWidth, this.bodyHeight);
        this.addRenderableWidget(this.optionList);
        this.refreshOptionList();

        int doneX = this.panelX + this.panelWidth - ACTION_BUTTON_WIDTH;
        int applyX = doneX - GAP - ACTION_BUTTON_WIDTH;
        this.applyButton = this.addRenderableWidget(
            new VideoSettingsScreen.FlatButton(
                applyX,
                this.footerY,
                ACTION_BUTTON_WIDTH,
                TOP_ROW_HEIGHT,
                Component.literal("Apply"),
                this::applyChanges,
                () -> false,
                true
            )
        );
        this.doneButton = this.addRenderableWidget(
            new VideoSettingsScreen.FlatButton(
                doneX,
                this.footerY,
                ACTION_BUTTON_WIDTH,
                TOP_ROW_HEIGHT,
                CommonComponents.GUI_DONE,
                this::finishAndClose,
                () -> false,
                true
            )
        );

        if (!this.baselineCaptured) {
            this.captureBaseline();
            this.baselineCaptured = true;
        }

        this.updateButtons();
    }

    private void calculateLayout() {
        int availableWidth = Math.max(260, this.width - OUTER_MARGIN * 2);
        int aspectLimitedWidth = Math.max(260, (int)(this.height * (16.0 / 9.0)));
        this.panelWidth = Math.min(availableWidth, aspectLimitedWidth);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.topY = 12;
        this.footerY = this.height - 28;
        this.bodyY = this.topY + TOP_ROW_HEIGHT + GAP;
        this.bodyHeight = Math.max(90, this.footerY - this.bodyY - GAP);
        this.railWidth = Mth.clamp(this.panelWidth / 5, 108, 150);
        this.listX = this.panelX + this.railWidth + GAP;
        this.listWidth = Math.max(120, this.panelWidth - this.railWidth - GAP);
    }

    private void ensureFullscreenResolutionOption() {
        if (this.fullscreenResolutionOption != null) {
            return;
        }

        Window window = this.minecraft.getWindow();
        Monitor monitor = window.findBestMonitor();
        int initialValue = monitor == null
            ? -1
            : window.getPreferredFullscreenVideoMode().map(monitor::indexOfMode).orElse(-1);
        this.fullscreenResolutionOption = new OptionInstance<>(
            "options.fullscreen.resolution",
            OptionInstance.noTooltip(),
            (caption, value) -> {
                if (monitor == null) {
                    return Component.translatable("options.fullscreen.unavailable");
                }
                if (value == -1) {
                    return Component.translatable("options.fullscreen.current");
                }

                VideoMode mode = monitor.mode(value);
                return Component.translatable(
                    "options.fullscreen.entry",
                    mode.getWidth(),
                    mode.getHeight(),
                    mode.getRefreshRate(),
                    mode.getRedBits() + mode.getGreenBits() + mode.getBlueBits()
                );
            },
            new OptionInstance.IntRange(-1, monitor != null ? monitor.modeCount() - 1 : -1),
            initialValue,
            value -> {
                if (monitor != null) {
                    window.setPreferredFullscreenVideoMode(value == -1 ? Optional.empty() : Optional.of(monitor.mode(value)));
                }
            }
        );
    }

    private void selectCategory(final VideoSettingsScreen.Category category) {
        if (this.optionList != null && this.searchQuery.isBlank()) {
            this.scrollOffsets.put(this.selectedCategory, this.optionList.scrollAmount());
        }
        if (this.selectedCategory != category || !this.searchQuery.isBlank()) {
            this.selectedCategory = category;
            if (this.searchBox != null && !this.searchQuery.isBlank()) {
                this.searchBox.setValue("");
            } else {
                this.refreshOptionList();
            }
        }
    }

    private void refreshOptionList() {
        if (this.optionList == null) {
            return;
        }

        this.optionList.clearOptions();
        for (OptionInstance<?> option : this.visibleOptions()) {
            this.optionList.addOption(option);
        }
        this.optionList.refreshScrollAmount();
        if (this.searchQuery.isBlank()) {
            this.optionList.setScrollAmount(this.scrollOffsets.getOrDefault(this.selectedCategory, 0.0));
        } else {
            this.optionList.setScrollAmount(0.0);
        }
        this.optionList.updateControlStates();
    }

    private List<OptionInstance<?>> visibleOptions() {
        String query = this.normalizedSearchQuery();
        if (query.isEmpty()) {
            return this.optionsFor(this.selectedCategory);
        }

        List<OptionInstance<?>> result = new ArrayList<>();
        IdentityHashMap<OptionInstance<?>, Boolean> seen = new IdentityHashMap<>();
        for (VideoSettingsScreen.Category category : VideoSettingsScreen.Category.values()) {
            for (OptionInstance<?> option : this.optionsFor(category)) {
                if (seen.put(option, Boolean.TRUE) == null && this.matches(option, query)) {
                    result.add(option);
                }
            }
        }
        return result;
    }

    private List<OptionInstance<?>> optionsFor(final VideoSettingsScreen.Category category) {
        OptionInstance<Integer> fullscreenResolution = Objects.requireNonNull(this.fullscreenResolutionOption);
        return switch (category) {
            case GENERAL -> VideoSettingsScreen.optionList(
                this.options.renderDistance(),
                this.options.simulationDistance(),
                this.options.gamma(),
                fullscreenResolution,
                this.options.guiScale(),
                this.options.fullscreen(),
                this.options.enableVsync(),
                this.options.framerateLimit()
            );
            case QUALITY -> VideoSettingsScreen.optionList(
                this.options.graphicsPreset(),
                this.options.biomeBlendRadius(),
                this.options.ambientOcclusion(),
                this.options.cloudStatus(),
                this.options.mipmapLevels(),
                this.options.textureFiltering(),
                this.options.maxAnisotropyBit(),
                this.options.cutoutLeaves()
            );
            case PERFORMANCE -> VideoSettingsScreen.optionList(
                this.options.prioritizeChunkUpdates(),
                this.options.entityDistanceScaling(),
                this.options.chunkSectionFadeInTime(),
                this.options.inactivityFpsLimit(),
                this.options.cloudRange(),
                this.options.weatherRadius()
            );
            case ADVANCED -> VideoSettingsScreen.optionList(
                this.options.preferredGraphicsBackend(),
                this.options.exclusiveFullscreen(),
                this.options.improvedTransparency(),
                this.options.menuBackgroundBlurriness()
            );
            case ANIMATIONS -> VideoSettingsScreen.optionList(
                this.options.bobView(),
                this.options.vignette(),
                this.options.screenEffectScale(),
                this.options.fovEffectScale(),
                this.options.darknessEffectScale(),
                this.options.glintSpeed(),
                this.options.glintStrength(),
                this.options.damageTiltStrength()
            );
            case PARTICLES -> VideoSettingsScreen.optionList(
                this.options.particles(),
                this.options.weatherRadius()
            );
            case DETAILS -> VideoSettingsScreen.optionList(
                this.options.entityShadows(),
                this.options.attackIndicator(),
                this.options.cloudRange(),
                this.options.highContrastBlockOutline()
            );
            case RENDER -> VideoSettingsScreen.optionList(
                this.options.cutoutLeaves(),
                this.options.improvedTransparency(),
                this.options.ambientOcclusion(),
                this.options.entityDistanceScaling(),
                this.options.cloudStatus()
            );
            case EXTRAS -> VideoSettingsScreen.optionList(
                this.options.showAutosaveIndicator(),
                this.options.panoramaSpeed(),
                this.options.darkMojangStudiosBackground(),
                this.options.hideLightningFlash(),
                this.options.hideSplashTexts()
            );
        };
    }

    private static List<OptionInstance<?>> optionList(final OptionInstance<?>... options) {
        return List.of(options);
    }

    private List<OptionInstance<?>> allOptions() {
        List<OptionInstance<?>> result = new ArrayList<>();
        IdentityHashMap<OptionInstance<?>, Boolean> seen = new IdentityHashMap<>();
        for (VideoSettingsScreen.Category category : VideoSettingsScreen.Category.values()) {
            for (OptionInstance<?> option : this.optionsFor(category)) {
                if (seen.put(option, Boolean.TRUE) == null) {
                    result.add(option);
                }
            }
        }
        return result;
    }

    private String normalizedSearchQuery() {
        return this.searchQuery.strip().toLowerCase(Locale.ROOT);
    }

    private boolean matches(final OptionInstance<?> option, final String query) {
        if (option.toString().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        String value = VideoSettingsScreen.probeValueText(option, this.options);
        return value.toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean captionMatchesSearch(final OptionInstance<?> option) {
        String query = this.normalizedSearchQuery();
        return !query.isEmpty() && option.toString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void captureBaseline() {
        this.baseline.clear();
        for (OptionInstance<?> option : this.allOptions()) {
            this.baseline.put(option, option.get());
        }
    }

    private boolean hasPendingChanges() {
        if (!this.baselineCaptured) {
            return false;
        }
        for (OptionInstance<?> option : this.allOptions()) {
            if (!Objects.equals(this.baseline.get(option), option.get())) {
                return true;
            }
        }
        return false;
    }

    private boolean isOptionChanged(final OptionInstance<?> option) {
        return this.baselineCaptured && !Objects.equals(this.baseline.get(option), option.get());
    }

    private void optionChanged() {
        this.updateButtons();
        if (this.optionList != null) {
            this.optionList.updateControlStates();
        }
    }

    private void applyChanges() {
        this.options.save();
        this.minecraft.getWindow().changeFullscreenVideoMode();
        if (this.options.mipmapLevels().get() != this.appliedMipmaps
            || this.options.maxAnisotropyBit().get() != this.appliedAnisotropyBit
            || this.options.textureFiltering().get() != this.appliedTextureFiltering) {
            this.minecraft.updateMaxMipLevel(this.options.mipmapLevels().get());
            this.minecraft.delayTextureReload();
            this.appliedMipmaps = this.options.mipmapLevels().get();
            this.appliedAnisotropyBit = this.options.maxAnisotropyBit().get();
            this.appliedTextureFiltering = this.options.textureFiltering().get();
        }

        this.captureBaseline();
        this.updateButtons();
    }

    private void finishAndClose() {
        if (this.hasPendingChanges()) {
            this.applyChanges();
        }
        this.minecraft.getWindow().changeFullscreenVideoMode();
        this.minecraft.gui.setScreen(this.lastScreen);
    }

    private void updateButtons() {
        boolean changed = this.hasPendingChanges();
        if (this.applyButton != null) {
            this.applyButton.active = changed;
        }
        if (this.doneButton != null) {
            this.doneButton.active = !changed;
        }
    }

    @Override
    public void tick() {
        if (this.optionList != null) {
            this.optionList.updateControlStates();
        }
        this.updateButtons();
        super.tick();
    }

    @Override
    protected void setInitialFocus() {
        if (this.searchBox != null) {
            this.setInitialFocus(this.searchBox);
        } else {
            super.setInitialFocus();
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        graphics.fill(this.panelX - 4, this.topY - 4, this.panelX + this.panelWidth + 4, this.footerY + TOP_ROW_HEIGHT + 4, PANEL_BACKGROUND);
        graphics.fill(this.panelX, this.bodyY, this.panelX + this.railWidth, this.bodyY + this.bodyHeight, RAIL_BACKGROUND);
        graphics.fill(this.listX, this.bodyY, this.listX + this.listWidth, this.bodyY + this.bodyHeight, PAGE_BACKGROUND);
        super.extractRenderState(graphics, mouseX, mouseY, a);

        if (this.optionList != null && this.optionList.isEmpty() && !this.searchQuery.isBlank()) {
            graphics.centeredText(this.font, Component.literal("No matching settings"), this.listX + this.listWidth / 2, this.bodyY + 12, MUTED_TEXT);
        }
        if (this.options.isRestartRequiredToApplyVideoSettings()) {
            graphics.text(this.font, RESTART_REQUIRED, this.panelX + 4, this.footerY + 6, 0xFFFFAA00, false);
        }
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDown() && this.searchBox != null) {
            this.setInitialFocus(this.searchBox);
            return true;
        }
        if (event.isEscape() && this.searchBox != null && !this.searchQuery.isBlank()) {
            this.searchBox.setValue("");
            this.setInitialFocus(this.searchBox);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.finishAndClose();
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            if (this.gpuWarnlistManager.isShowingWarning()) {
                List<Component> warningMessage = Lists.newArrayList(WARNING_MESSAGE, CommonComponents.NEW_LINE);
                String rendererWarnings = this.gpuWarnlistManager.getRendererWarnings();
                if (rendererWarnings != null) {
                    warningMessage.add(CommonComponents.NEW_LINE);
                    warningMessage.add(Component.translatable("options.graphics.warning.renderer", rendererWarnings).withStyle(ChatFormatting.GRAY));
                }

                String vendorWarnings = this.gpuWarnlistManager.getVendorWarnings();
                if (vendorWarnings != null) {
                    warningMessage.add(CommonComponents.NEW_LINE);
                    warningMessage.add(Component.translatable("options.graphics.warning.vendor", vendorWarnings).withStyle(ChatFormatting.GRAY));
                }

                String versionWarnings = this.gpuWarnlistManager.getVersionWarnings();
                if (versionWarnings != null) {
                    warningMessage.add(CommonComponents.NEW_LINE);
                    warningMessage.add(Component.translatable("options.graphics.warning.version", versionWarnings).withStyle(ChatFormatting.GRAY));
                }

                this.minecraft.gui.setScreen(
                    new UnsupportedGraphicsWarningScreen(
                        WARNING_TITLE,
                        warningMessage,
                        ImmutableList.of(
                            new UnsupportedGraphicsWarningScreen.ButtonOption(BUTTON_ACCEPT, btn -> {
                                this.options.improvedTransparency().set(true);
                                Minecraft.getInstance().levelExtractor.allChanged();
                                this.gpuWarnlistManager.dismissWarning();
                                this.optionChanged();
                                this.minecraft.gui.setScreen(this);
                            }),
                            new UnsupportedGraphicsWarningScreen.ButtonOption(BUTTON_CANCEL, btn -> {
                                this.gpuWarnlistManager.dismissWarning();
                                this.options.improvedTransparency().set(false);
                                this.updateTransparencyButton();
                                this.minecraft.gui.setScreen(this);
                            })
                        )
                    )
                );
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        if (this.minecraft.hasControlDown()) {
            OptionInstance<Integer> guiScale = this.options.guiScale();
            if (guiScale.values() instanceof OptionInstance.ClampingLazyMaxIntRange range) {
                int oldValue = guiScale.get();
                int adjustedOldValue = oldValue == 0 ? range.maxInclusive() + 1 : oldValue;
                int newValue = adjustedOldValue + (int)Math.signum(scrollY);
                if (newValue != 0 && newValue <= range.maxInclusive() && newValue >= range.minInclusive()) {
                    guiScale.set(newValue);
                    if (this.optionList != null) {
                        this.optionList.setScrollAmount(0.0);
                    }
                    this.optionChanged();
                    return true;
                }
            }
            return false;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    public void updateFullscreenButton(final boolean fullscreen) {
        this.optionChanged();
    }

    public void updateTransparencyButton() {
        this.optionChanged();
    }

    private static String probeValueText(final OptionInstance<?> option, final Options options) {
        AbstractWidget probe = option.createButton(options, 0, 0, 150);
        String formatted = probe.getMessage().getString();
        String caption = option.toString();
        if (formatted.startsWith(caption)) {
            String tail = formatted.substring(caption.length()).stripLeading();
            if (tail.startsWith(":") || tail.startsWith("：")) {
                tail = tail.substring(1).stripLeading();
            }
            if (!tail.isEmpty()) {
                return tail;
            }
        }
        return formatted.equals(caption) ? String.valueOf(option.get()) : formatted;
    }

    @OnlyIn(Dist.CLIENT)
    private enum Category {
        GENERAL("General"),
        QUALITY("Quality"),
        PERFORMANCE("Performance"),
        ADVANCED("Advanced"),
        ANIMATIONS("Animations"),
        PARTICLES("Particles"),
        DETAILS("Details"),
        RENDER("Render"),
        EXTRAS("Extras");

        private final Component label;

        Category(final String label) {
            this.label = Component.literal(label);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private final class OptionPageList extends ContainerObjectSelectionList<VideoSettingsScreen.OptionRow> {
        private OptionPageList(final int x, final int y, final int width, final int height) {
            super(Minecraft.getInstance(), width, height, y, ROW_HEIGHT);
            this.centerListVertically = false;
            this.updateSizeAndPosition(width, height, x, y);
        }

        private void clearOptions() {
            this.clearEntries();
        }

        private void addOption(final OptionInstance<?> option) {
            this.addEntry(new VideoSettingsScreen.OptionRow(option));
        }

        private boolean isEmpty() {
            return this.children().isEmpty();
        }

        private void updateControlStates() {
            for (VideoSettingsScreen.OptionRow row : this.children()) {
                row.control.active = row.option != VideoSettingsScreen.this.options.maxAnisotropyBit()
                    || VideoSettingsScreen.this.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(80, this.getWidth() - 10);
        }

        @Override
        protected void extractListBackground(final GuiGraphicsExtractor graphics) {
        }

        @Override
        protected void extractListSeparators(final GuiGraphicsExtractor graphics) {
        }

        @Override
        public int scrollbarWidth() {
            return 4;
        }

        @Override
        protected int scrollBarX() {
            return this.getRight() - 4;
        }

        @Override
        protected void extractScrollbar(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
            if (!this.scrollable()) {
                return;
            }
            int x = this.scrollBarX();
            graphics.fill(x, this.getY(), x + 2, this.getBottom(), 0x60353535);
            graphics.fill(x, this.scrollBarY(), x + 2, this.scrollBarY() + this.scrollerHeight(), 0xFFC0C0C0);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private final class OptionRow extends ContainerObjectSelectionList.Entry<VideoSettingsScreen.OptionRow> {
        private final OptionInstance<?> option;
        private final VideoSettingsScreen.FlatOptionControl<?> control;

        private OptionRow(final OptionInstance<?> option) {
            this.option = option;
            this.control = new VideoSettingsScreen.FlatOptionControl<>(option);
        }

        @Override
        public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
            this.control.setRectangle(this.getContentWidth(), this.getContentHeight(), this.getContentX(), this.getContentY());
            this.control.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.control);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.control);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private final class FlatOptionControl<T> extends AbstractWidget {
        private final OptionInstance<T> option;
        private final OptionInstance.ValueSet<T> values;
        private @Nullable Object cachedValue;
        private String cachedValueText = "";

        private FlatOptionControl(final OptionInstance<T> option) {
            super(0, 0, 100, 22, Component.literal(option.toString()));
            this.option = option;
            this.values = option.values();
        }

        @Override
        protected void extractWidgetRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
            int background = this.isHoveredOrFocused() ? ROW_HOVER : ROW_BACKGROUND;
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), background);
            if (VideoSettingsScreen.this.isOptionChanged(this.option)) {
                graphics.fill(this.getX(), this.getY(), this.getX() + 2, this.getBottom(), ACCENT);
            }

            int labelColor = VideoSettingsScreen.this.captionMatchesSearch(this.option) ? SEARCH_MATCH : (this.active ? TEXT : MUTED_TEXT);
            graphics.text(VideoSettingsScreen.this.font, this.option.toString(), this.getX() + 8, this.getY() + 7, labelColor, false);

            String value = this.valueText();
            int valueWidth = VideoSettingsScreen.this.font.width(value);
            graphics.text(VideoSettingsScreen.this.font, value, Math.max(this.getX() + 8, this.getRight() - valueWidth - 8), this.getY() + 7, this.active ? TEXT : MUTED_TEXT, false);

            if (this.isSliderMode()) {
                double sliderValue = this.sliderValues().toSliderValue(this.option.get());
                int left = this.getX() + 8;
                int right = this.getRight() - 8;
                int y = this.getBottom() - 3;
                int knob = left + (int)Math.round((right - left) * Mth.clamp(sliderValue, 0.0, 1.0));
                graphics.fill(left, y, right, y + 1, 0xFF3B3B3B);
                graphics.fill(left, y, knob, y + 1, this.active ? ACCENT : 0xFF666666);
                graphics.fill(knob - 1, y - 1, knob + 1, y + 2, this.active ? 0xFFE8E8E8 : 0xFF777777);
            }

            this.setMessage(Component.literal(this.option.toString() + ": " + value));
            this.handleCursor(graphics);
        }

        private String valueText() {
            T current = this.option.get();
            if (!Objects.equals(this.cachedValue, current)) {
                this.cachedValue = current;
                this.cachedValueText = VideoSettingsScreen.probeValueText(this.option, VideoSettingsScreen.this.options);
            }
            return this.cachedValueText;
        }

        private boolean isSliderMode() {
            if (this.values instanceof OptionInstance.SliderableOrCyclableValueSet<?> hybrid) {
                return !hybrid.createCycleButton();
            }
            return this.values instanceof OptionInstance.SliderableValueSet<?>;
        }

        private boolean isCycleMode() {
            if (this.values instanceof OptionInstance.SliderableOrCyclableValueSet<?> hybrid) {
                return hybrid.createCycleButton();
            }
            return this.values instanceof OptionInstance.CycleableValueSet<?>;
        }

        @SuppressWarnings("unchecked")
        private OptionInstance.SliderableValueSet<T> sliderValues() {
            return (OptionInstance.SliderableValueSet<T>)this.values;
        }

        @SuppressWarnings("unchecked")
        private OptionInstance.CycleableValueSet<T> cycleValues() {
            return (OptionInstance.CycleableValueSet<T>)this.values;
        }

        @Override
        public void onClick(final MouseButtonEvent event, final boolean doubleClick) {
            if (this.isSliderMode()) {
                this.setSliderFromMouse(event.x());
            } else if (this.isCycleMode()) {
                this.cycle(Minecraft.getInstance().hasShiftDown() ? -1 : 1);
            }
        }

        @Override
        protected void onDrag(final MouseButtonEvent event, final double dx, final double dy) {
            if (this.isSliderMode()) {
                this.setSliderFromMouse(event.x());
            }
        }

        private void setSliderFromMouse(final double mouseX) {
            OptionInstance.SliderableValueSet<T> slider = this.sliderValues();
            double progress = (mouseX - (this.getX() + 8.0)) / Math.max(1.0, this.getWidth() - 16.0);
            this.setOption(slider.fromSliderValue(Mth.clamp(progress, 0.0, 1.0)));
        }

        private void cycle(final int delta) {
            OptionInstance.CycleableValueSet<T> cycle = this.cycleValues();
            List<T> values = cycle.valueListSupplier().getSelectedList();
            if (values.isEmpty()) {
                return;
            }
            int current = values.indexOf(this.option.get());
            if (current < 0) {
                current = 0;
            }
            T next = values.get(Mth.positiveModulo(current + delta, values.size()));
            cycle.valueSetter().set(this.option, next);
            this.cachedValue = null;
            VideoSettingsScreen.this.optionChanged();
        }

        private void setOption(final T value) {
            if (!Objects.equals(this.option.get(), value)) {
                this.option.set(value);
                this.cachedValue = null;
                VideoSettingsScreen.this.optionChanged();
            }
        }

        @Override
        public boolean keyPressed(final KeyEvent event) {
            if (this.isCycleMode() && event.isSelection()) {
                this.cycle(event.hasShiftDown() ? -1 : 1);
                return true;
            }
            if (this.isSliderMode() && (event.isLeft() || event.isRight())) {
                OptionInstance.SliderableValueSet<T> slider = this.sliderValues();
                Optional<T> discrete = event.isLeft() ? slider.previous(this.option.get()) : slider.next(this.option.get());
                if (discrete.isPresent()) {
                    this.setOption(discrete.get());
                    return true;
                }

                double progress = slider.toSliderValue(this.option.get());
                double direction = event.isLeft() ? -1.0 : 1.0;
                this.setOption(slider.fromSliderValue(Mth.clamp(progress + direction / Math.max(20.0, this.getWidth() - 16.0), 0.0, 1.0)));
                return true;
            }
            return false;
        }

        @Override
        public void updateWidgetNarration(final NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, this.createNarrationMessage());
        }
    }

    @OnlyIn(Dist.CLIENT)
    private final class FlatButton extends AbstractButton {
        private final Runnable action;
        private final BooleanSupplier selected;
        private final boolean centered;

        private FlatButton(
            final int x,
            final int y,
            final int width,
            final int height,
            final Component message,
            final Runnable action,
            final BooleanSupplier selected,
            final boolean centered
        ) {
            super(x, y, width, height, message);
            this.action = action;
            this.selected = selected;
            this.centered = centered;
        }

        @Override
        public void onPress(final InputWithModifiers input) {
            this.action.run();
        }

        @Override
        protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
            boolean selected = this.selected.getAsBoolean();
            int background = !this.active ? 0x80151515 : selected ? SELECTED_BACKGROUND : this.isHoveredOrFocused() ? ROW_HOVER : ROW_BACKGROUND;
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), background);
            if (selected) {
                graphics.fill(this.getX(), this.getY(), this.getX() + 2, this.getBottom(), ACCENT);
            }

            int color = this.active ? TEXT : MUTED_TEXT;
            int textY = this.getY() + (this.getHeight() - 9) / 2;
            if (this.centered) {
                graphics.centeredText(VideoSettingsScreen.this.font, this.getMessage(), this.getX() + this.getWidth() / 2, textY, color);
            } else {
                graphics.text(VideoSettingsScreen.this.font, this.getMessage(), this.getX() + 8, textY, color, false);
            }
        }

        @Override
        public void updateWidgetNarration(final NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
