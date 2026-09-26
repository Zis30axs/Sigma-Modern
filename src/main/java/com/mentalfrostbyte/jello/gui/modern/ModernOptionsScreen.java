package com.mentalfrostbyte.jello.gui.modern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.ChatOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.WorldOptionsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.telemetry.TelemetryInfoScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * SigmaModern's settings hub: the option categories as a grid of glass tiles, each with its own vector
 * glyph, under the field-of-view slider and the Online (or, in a world, World Options) shortcut. The
 * fine print - telemetry, credits - sits in a quiet footer next to Done.
 *
 * <p>Extends {@link OptionsScreen} and opens exactly what it opens (Sodium's video settings included), so
 * sub-screens return here, the gamemaster-permission rebuild still happens, and leaving still saves the
 * options. Only the layout and drawing are new.</p>
 */
public final class ModernOptionsScreen extends OptionsScreen implements ModernBlurredBackdrop {
    private static final int BODY_TOP = ModernPage.TOP + 42;

    private final Options options;
    private final boolean inWorld;
    private final ModernBackdrop backdrop = new ModernBackdrop(150, 4200F);
    private final long openStart = System.nanoTime();
    private final List<Tile> tiles = new ArrayList<>();

    public ModernOptionsScreen(Screen lastScreen, Options options, boolean inWorld) {
        super(lastScreen, options, inWorld);
        this.options = options;
        this.inWorld = inWorld;
    }

    private record Category(String key, ModernIcons.Icon icon, Supplier<Screen> screen) {}

    private List<Category> categories() {
        return List.of(
            // Sodium replaces the video settings screen, exactly as vanilla's hub does after porting.
            new Category("options.video", ModernIcons.Icon.MONITOR, () -> net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(this)),
            new Category("options.sounds", ModernIcons.Icon.SPEAKER, () -> new SoundOptionsScreen(this, this.options)),
            new Category("options.controls", ModernIcons.Icon.KEYBOARD, () -> new ControlsScreen(this, this.options)),
            new Category("options.language", ModernIcons.Icon.GLOBE,
                () -> new LanguageSelectScreen(this, this.options, this.minecraft.getLanguageManager())),
            new Category("options.chat", ModernIcons.Icon.CHAT, () -> new ChatOptionsScreen(this, this.options)),
            new Category("options.resourcepack", ModernIcons.Icon.LAYERS, () -> new PackSelectionScreen(
                this.minecraft.getResourcePackRepository(), this::applyPacks, this.minecraft.getResourcePackDirectory(),
                Component.translatable("resourcePack.title"))),
            new Category("options.accessibility", ModernIcons.Icon.ACCESSIBILITY, () -> new AccessibilityOptionsScreen(this, this.options)),
            new Category("options.skinCustomisation", ModernIcons.Icon.SHIRT, () -> new SkinCustomizationScreen(this, this.options))
        );
    }

    private void applyPacks(PackRepository repository) {
        this.options.updateResourcePacks(repository);
        this.minecraft.gui.setScreen(this);
    }

    private record Layout(int margin, int contentX, int contentW, int rowY, int gridY, int cols, int tileW, int tileH, int footerY) {}

    private Layout layout() {
        int margin = ModernPage.margin(this.width);
        int contentW = Math.min(this.width - 2 * margin, 620);
        int contentX = (this.width - contentW) / 2;
        int cols = contentW >= 380 ? 4 : 2;
        int gap = 10;
        int tileW = (contentW - (cols - 1) * gap) / cols;
        int rows = (8 + cols - 1) / cols;
        int rowY = BODY_TOP;
        int gridY = rowY + 36;
        int footerY = this.height - 12 - 22;
        int room = footerY - 14 - gridY;
        int tileH = Math.max(30, Math.min(92, (room - (rows - 1) * gap) / rows));
        return new Layout(margin, contentX, contentW, rowY, gridY, cols, tileW, tileH, footerY);
    }

    @Override
    protected void init() {
        Layout l = layout();
        this.tiles.clear();
        this.addRenderableWidget(new ModernButton(l.margin(), ModernPage.TOP, 22, 22, CommonComponents.GUI_BACK, ModernIcons.Icon.BACK,
            ModernButton.Kind.ICON, this::onClose));

        // Field of view, and the one shortcut that depends on where the hub was opened from.
        int shortcutW = 150;
        int sliderW = Math.min(260, l.contentW() - shortcutW - 10);
        AbstractWidget fov = this.options.fov().createButton(this.options, l.contentX(), l.rowY(), sliderW);
        this.addRenderableWidget(fov);
        int shortcutX = l.contentX() + l.contentW() - shortcutW;
        if (this.inWorld) {
            this.addRenderableWidget(new ModernButton(shortcutX, l.rowY(), shortcutW, 20,
                Component.literal(strip(Component.translatable("options.worldOptions.button").getString())),
                ModernIcons.Icon.MOUNTAIN, ModernButton.Kind.SECONDARY,
                () -> this.minecraft.gui.setScreen(new WorldOptionsScreen(this, Objects.requireNonNull(this.minecraft.level)))));
        } else {
            this.addRenderableWidget(new ModernButton(shortcutX, l.rowY(), shortcutW, 20, Component.literal(strip(Component.translatable("options.online").getString())),
                ModernIcons.Icon.CLOUD, ModernButton.Kind.SECONDARY, () -> this.minecraft.gui.setScreen(new OnlineOptionsScreen(this, this.options))));
        }

        List<Category> categories = categories();
        for (int i = 0; i < categories.size(); i++) {
            Category category = categories.get(i);
            int col = i % l.cols(), row = i / l.cols();
            int x = l.contentX() + col * (l.tileW() + 10), y = l.gridY() + row * (l.tileH() + 10);
            this.tiles.add(this.addRenderableWidget(new Tile(x, y, l.tileW(), l.tileH(), i, category)));
        }

        int fx = l.contentX();
        ModernButton telemetry = new ModernButton(fx, l.footerY(), 0, 22, Component.translatable("options.telemetry"), ModernIcons.Icon.CHART,
            ModernButton.Kind.GHOST, () -> this.minecraft.gui.setScreen(new TelemetryInfoScreen(this, this.options)));
        telemetry.setWidth(footerWidth(telemetry.getMessage()));
        if (!this.minecraft.allowsTelemetry()) {
            telemetry.active = false;
            telemetry.setTooltip(Tooltip.create(Component.translatable("options.telemetry.disabled")));
        }
        this.addRenderableWidget(telemetry);
        fx += telemetry.getWidth() + 4;
        ModernButton credits = new ModernButton(fx, l.footerY(), 0, 22, Component.translatable("options.credits_and_attribution"), ModernIcons.Icon.HEART,
            ModernButton.Kind.GHOST, () -> this.minecraft.gui.setScreen(new CreditsAndAttributionScreen(this)));
        credits.setWidth(footerWidth(credits.getMessage()));
        this.addRenderableWidget(credits);
        int doneW = 120;
        this.addRenderableWidget(new ModernButton(l.contentX() + l.contentW() - doneW, l.footerY(), doneW, 22, CommonComponents.GUI_DONE,
            ModernIcons.Icon.CHECK, ModernButton.Kind.PRIMARY, this::onClose));
    }

    private static int footerWidth(Component label) {
        return ModernButton.widthFor(Component.literal(strip(label.getString())), true);
    }

    /** Vanilla's hub labels end in "..." to signal a sub-screen; the tiles make that obvious already. */
    private static String strip(String label) {
        return label.replaceAll("(\\.\\.\\.|…)$", "");
    }

    @Override
    protected void repositionElements() {
        this.rebuildWidgets();
    }

    // --- rendering ------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.backdrop.render(g, this.minecraft, this.width, this.height, mouseX, mouseY);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Layout l = layout();
        float open = ModernStyle.easeOut((System.nanoTime() - this.openStart) / 1_000_000_000F / 0.4F);
        try (var fade = ModernStyle.alphaScope(open)) {
            ModernPage.header(g, l.margin() + 32, ModernPage.TOP + 1, "SETTINGS", this.title.getString(), 1.55F);
            ModernStyle.fill(g, l.contentX(), l.footerY() - 10, l.contentX() + l.contentW(), l.footerY() - 9, 0x1AFFFFFF);
            super.extractRenderState(g, mouseX, mouseY, a);
        }
    }

    /** One category: a glass tile with its glyph and name, lifting and lighting up under the pointer. */
    private final class Tile extends Button {
        private final int index;
        private final ModernIcons.Icon icon;
        private float hover;
        private long lastFrame;

        private Tile(int x, int y, int w, int h, int index, Category category) {
            super(x, y, w, h, Component.translatable(category.key()), ignored -> ModernOptionsScreen.this.minecraft.gui.setScreen(category.screen().get()),
                DEFAULT_NARRATION);
            this.index = index;
            this.icon = category.icon();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
            long now = System.nanoTime();
            float dt = this.lastFrame == 0L ? 0F : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
            this.lastFrame = now;
            this.hover = ModernStyle.smooth(this.hover, this.active && (this.isHovered() || this.isFocused()) ? 1F : 0F, dt, 13F);
            // Tiles arrive one after another, left to right, top to bottom.
            float arrive = ModernStyle.easeOut(((now - ModernOptionsScreen.this.openStart) / 1_000_000_000F - 0.06F * this.index) / 0.45F);
            int x = this.getX(), w = this.getWidth(), h = this.getHeight();
            float lift = this.hover * 2F + (1F - arrive) * 10F;
            int y = Math.round(this.getY() - lift);
            try (var fade = ModernStyle.alphaScope(arrive)) {
                if (this.hover > 0.01F) ModernStyle.halo(g, x, y, w, h, 12, ModernStyle.GLOW, 0.4F * this.hover);
                ModernStyle.darkGlass(g, x, y, w, h, 12, ModernStyle.mix(0xC00A1B29, 0xD8123049, this.hover));
                ModernStyle.fill(g, x + 12, y + 1, x + w - 12, y + 2, Math.round(0x2E + 0x40 * this.hover) << 24 | 0xDDF3FF);
                int iconColor = ModernStyle.mix(0xFF9FD0EA, 0xFFFFFFFF, this.hover);
                int textColor = ModernStyle.mix(0xFFD9E8F1, 0xFFFFFFFF, this.hover);
                String label = strip(this.getMessage().getString());
                if (h >= 50) {
                    float size = h >= 80 ? 26F : 20F;
                    ModernIcons.draw(g, this.icon, x + 12F, y + 11F, size, iconColor);
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(label, Math.round((w - 24) / 1.1F)), x + 12F, y + h - 19F, 1.1F, textColor);
                    try (var arrow = ModernStyle.alphaScope(this.hover)) {
                        ModernIcons.draw(g, ModernIcons.Icon.ARROW, x + w - 22F - (1F - this.hover) * 5F, y + 12F, 10F, 0xFFBFE8FF);
                    }
                } else {
                    float size = 14F;
                    ModernIcons.draw(g, this.icon, x + 10F, y + (h - size) / 2F, size, iconColor);
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(label, w - 36), x + 30F, y + h / 2F - 5F, 1F, textColor);
                }
            }
        }
    }
}
