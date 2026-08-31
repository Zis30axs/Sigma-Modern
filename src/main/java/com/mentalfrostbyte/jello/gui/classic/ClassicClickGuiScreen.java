package com.mentalfrostbyte.jello.gui.classic;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClickGuiInteractions;
import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mentalfrostbyte.jello.gui.SigmaClickGui;
import com.mentalfrostbyte.jello.gui.click.ClickGuiHandler;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Classic ClickGUI functional implementation.
 *
 * <p>Unlike Jello's always-visible category rail, Classic uses a navigation flow:
 * category grid -> module grid -> settings. It still reads the same {@link ModuleManager} and
 * {@link com.mentalfrostbyte.jello.setting.SettingHolder} views.</p>
 */
public class ClassicClickGuiScreen extends Screen implements SigmaClickGui {

    private static final int BG = 0xB0101010;
    private static final int PANEL = 0xD0181818;
    private static final int BORDER = 0xFF555555;
    private static final int HEADER = 0xD0252525;
    private static final int SELECTED = 0xFF4A4A6A;
    private static final int HOVER = 0x30FFFFFF;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFFA0A0A0;
    private static final int TEXT_ENABLED = 0xFF55FF55;

    private static final int ROW_HEIGHT = 18;
    private static final int CARD_WIDTH = 170;
    private static final int CARD_HEIGHT = 52;
    private static final int CARD_GAP = 8;

    private final ModuleManager modules;

    private final ClickGuiInteractions interactions = new ClickGuiInteractions();

    private ModuleCategory selectedCategory;
    private Module selectedModule;

    private int moduleScroll;
    private int settingScroll;

    public ClassicClickGuiScreen(final ModuleManager modules) {
        super(Component.literal("Classic ClickGUI"));
        this.modules = modules;
    }

    @Override
    protected void init() {
        if (this.selectedCategory == null) {
            for (ModuleCategory category : ModuleCategory.values()) {
                if (!this.modules.byCategory(category).isEmpty()) {
                    this.selectedCategory = category;
                    break;
                }
            }
        }
        if (this.selectedModule == null && this.selectedCategory != null) {
            List<Module> list = this.modules.byCategory(this.selectedCategory);
            this.selectedModule = list.isEmpty() ? null : list.get(0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.fill(0, 0, this.width, this.height, BG);
        String modeText = "Mode: " + Client.getInstance().getClientModeManager().get().name();
        graphics.text(this.font, modeText, this.width - this.font.width(modeText) - 8, 8, TEXT_ENABLED);

        if (this.selectedCategory == null) {
            this.drawCategoryGrid(graphics, mouseX, mouseY);
        } else if (this.selectedModule == null) {
            this.drawModuleGrid(graphics, mouseX, mouseY);
        } else {
            this.drawSettings(graphics, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (this.interactions.mouseClickedBinding(event)) {
            return true;
        }

        int x = (int) event.x();
        int y = (int) event.y();
        int button = event.button();

        if (this.isOverModeButton(x, y)) {
            this.cycleMode();
            return true;
        }

        if (this.selectedCategory == null) {
            ModuleCategory category = this.hitCategoryCard(x, y);
            if (category != null) {
                this.selectedCategory = category;
                this.moduleScroll = 0;
                List<Module> list = this.modules.byCategory(category);
                this.selectedModule = list.isEmpty() ? null : list.get(0);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        if (this.selectedModule == null) {
            if (this.isOverBack(x, y)) {
                this.selectedCategory = null;
                return true;
            }
            Module module = this.hitModuleCard(x, y);
            if (module != null) {
                this.selectedModule = module;
                this.settingScroll = 0;
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        // Settings view
        if (this.isOverBack(x, y)) {
            this.selectedModule = null;
            this.settingScroll = 0;
            return true;
        }

        if (this.hitKeybindRow(y)) {
            this.interactions.handleKeybindClick(this.selectedModule, button);
            return true;
        }

        Setting<?> setting = this.hitSetting(x, y);
        if (setting != null && button == 0) {
            this.interactions.handleSettingClick(setting, x, 8, this.width - 8);
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (this.interactions.mouseDragged(event)) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.interactions.mouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        int direction = (int) Math.signum(scrollY);
        if (this.selectedCategory != null && this.selectedModule == null) {
            this.moduleScroll -= direction;
            this.moduleScroll = Math.max(0, Math.min(this.moduleScroll, this.maxModuleScroll()));
            return true;
        }
        if (this.selectedModule != null) {
            this.settingScroll -= direction;
            this.settingScroll = Math.max(0, Math.min(this.settingScroll, this.maxSettingScroll()));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.interactions.keyPressed(event)) {
            return true;
        }

        if (InputConstants.getKey(event).equals(ClickGuiHandler.OPEN_KEY)) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(final CharacterEvent event) {
        if (this.interactions.charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        ClickGuiHandler.close();
    }

    private void drawCategoryGrid(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        graphics.text(this.font, "Classic ClickGUI - Select Category", 8, 8, TEXT);
        List<ModuleCategory> categories = List.of(ModuleCategory.values());
        int cols = 2;
        int startX = (this.width - cols * CARD_WIDTH - (cols - 1) * CARD_GAP) / 2;
        int startY = 60;
        int row = 0;
        int col = 0;
        for (ModuleCategory category : categories) {
            if (this.modules.byCategory(category).isEmpty()) {
                continue;
            }
            int x = startX + col * (CARD_WIDTH + CARD_GAP);
            int y = startY + row * (CARD_HEIGHT + CARD_GAP);
            boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;
            graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, hovered ? SELECTED : PANEL);
            graphics.outline(x, y, CARD_WIDTH, CARD_HEIGHT, BORDER);
            graphics.text(this.font, category.getDisplayName(), x + 8, y + CARD_HEIGHT / 2 - 4, TEXT);
            col++;
            if (col >= cols) {
                col = 0;
                row++;
            }
        }
    }

    private void drawModuleGrid(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        this.drawBack(graphics);
        graphics.text(this.font, this.selectedCategory.getDisplayName(), 40, 8, TEXT);
        List<Module> list = this.modules.byCategory(this.selectedCategory);
        int cols = 2;
        int startX = (this.width - cols * CARD_WIDTH - (cols - 1) * CARD_GAP) / 2;
        int startY = 50;
        int visibleRows = Math.max(1, (this.height - startY - 10) / (CARD_HEIGHT + CARD_GAP));
        for (int i = 0; i < list.size(); i++) {
            int index = i;
            int row = index / cols - this.moduleScroll;
            int col = index % cols;
            if (row < 0 || row >= visibleRows) {
                continue;
            }
            Module module = list.get(index);
            int x = startX + col * (CARD_WIDTH + CARD_GAP);
            int y = startY + row * (CARD_HEIGHT + CARD_GAP);
            boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;
            graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, hovered ? SELECTED : PANEL);
            graphics.outline(x, y, CARD_WIDTH, CARD_HEIGHT, BORDER);
            int nameColor = module.isEnabled() ? TEXT_ENABLED : TEXT;
            graphics.text(this.font, module.getName(), x + 8, y + 8, nameColor);
            if (module.isEnabled()) {
                graphics.text(this.font, "ON", x + CARD_WIDTH - this.font.width("ON") - 6, y + 8, TEXT_ENABLED);
            }
        }
    }

    private void drawSettings(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        this.drawBack(graphics);
        Module module = this.selectedModule;
        graphics.text(this.font, module.getName(), 40, 8, TEXT);

        int keybindY = 28;
        String keybindText = this.interactions.isBinding(module)
            ? "Press a key... (Esc cancel, Del unbind)"
            : "Bind: " + this.interactions.keybindDisplay(module.getKeybind());
        graphics.text(this.font, keybindText, 8, keybindY + 5, this.interactions.isBinding(module) ? 0xFFFFFF55 : TEXT);
        String modeText = module.getKeybind().mode().name();
        graphics.text(this.font, modeText, this.width - this.font.width(modeText) - 12, keybindY + 5, TEXT_DIM);

        List<Setting<?>> visibleSettings = module.settings().stream().filter(Setting::isVisible).toList();
        int listTop = keybindY + ROW_HEIGHT + 4;
        int row = 0;
        for (int i = 0; i < visibleSettings.size(); i++) {
            int index = i;
            int y = listTop + (index - this.settingScroll) * ROW_HEIGHT;
            if (y + ROW_HEIGHT > this.height - 4 || y < listTop) {
                continue;
            }
            Setting<?> setting = visibleSettings.get(index);
            boolean hovered = mouseX >= 4 && mouseX < this.width - 4 && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(4, y, this.width - 4, y + ROW_HEIGHT, HOVER);
            }
            this.drawSetting(graphics, setting, 8, y, this.width - 16);
            row++;
        }
    }

    private void drawSetting(final GuiGraphicsExtractor graphics, final Setting<?> setting, final int x, final int y, final int width) {
        if (setting instanceof BooleanSetting bool) {
            String label = bool.getName() + ": " + (bool.get() ? "ON" : "OFF");
            graphics.text(this.font, label, x, y + 5, bool.get() ? TEXT_ENABLED : TEXT_DIM);
            int boxX = x + width - 16;
            graphics.fill(boxX, y + 3, boxX + 10, y + 13, bool.get() ? 0xFF2E7D32 : 0xFF555555);
            graphics.outline(boxX, y + 3, 10, 10, BORDER);
            return;
        }
        if (setting instanceof NumberSetting number) {
            String label = number.getName() + ": " + String.format(Locale.ROOT, "%." + number.getDecimalPlaces() + "f", number.get());
            graphics.text(this.font, label, x, y + 1, TEXT);
            int trackX = x;
            int trackY = y + 12;
            int trackWidth = width - 8;
            float range = number.getMax() - number.getMin();
            int fillWidth = range <= 0.0F ? trackWidth : (int) ((number.get() - number.getMin()) / range * trackWidth);
            graphics.fill(trackX, trackY, trackX + trackWidth, trackY + 3, 0xFF333333);
            graphics.fill(trackX, trackY, trackX + fillWidth, trackY + 3, 0xFF4A6FA5);
            return;
        }
        if (setting instanceof EnumSetting<?> enumSetting) {
            graphics.text(this.font, setting.getName() + ": " + enumSetting.get().name(), x, y + 5, TEXT);
            return;
        }
        if (setting instanceof ColorSetting color) {
            String value = this.interactions.displayValue(setting);
            graphics.text(this.font, setting.getName() + ": " + value, x, y + 5, TEXT);
            int swatchX = x + width - 16;
            graphics.fill(swatchX, y + 3, swatchX + 10, y + 13, color.get());
            graphics.outline(swatchX, y + 3, 10, 10, BORDER);
            return;
        }
        if (setting instanceof TextSetting) {
            String value = this.interactions.displayValue(setting);
            graphics.text(this.font, setting.getName() + ": " + value, x, y + 5, TEXT);
        }
    }

    private void drawBack(final GuiGraphicsExtractor graphics) {
        graphics.text(this.font, "< Back", 8, 8, TEXT_DIM);
    }

    private @Nullable ModuleCategory hitCategoryCard(final int x, final int y) {
        int cols = 2;
        int startX = (this.width - cols * CARD_WIDTH - (cols - 1) * CARD_GAP) / 2;
        int startY = 60;
        int row = 0;
        int col = 0;
        for (ModuleCategory category : ModuleCategory.values()) {
            if (this.modules.byCategory(category).isEmpty()) {
                continue;
            }
            int cx = startX + col * (CARD_WIDTH + CARD_GAP);
            int cy = startY + row * (CARD_HEIGHT + CARD_GAP);
            if (x >= cx && x < cx + CARD_WIDTH && y >= cy && y < cy + CARD_HEIGHT) {
                return category;
            }
            col++;
            if (col >= cols) {
                col = 0;
                row++;
            }
        }
        return null;
    }

    private @Nullable Module hitModuleCard(final int x, final int y) {
        if (this.selectedCategory == null) {
            return null;
        }
        List<Module> list = this.modules.byCategory(this.selectedCategory);
        int cols = 2;
        int startX = (this.width - cols * CARD_WIDTH - (cols - 1) * CARD_GAP) / 2;
        int startY = 50;
        for (int i = 0; i < list.size(); i++) {
            int row = i / cols - this.moduleScroll;
            int col = i % cols;
            if (row < 0) {
                continue;
            }
            int cx = startX + col * (CARD_WIDTH + CARD_GAP);
            int cy = startY + row * (CARD_HEIGHT + CARD_GAP);
            if (x >= cx && x < cx + CARD_WIDTH && y >= cy && y < cy + CARD_HEIGHT) {
                return list.get(i);
            }
        }
        return null;
    }

    private boolean hitKeybindRow(final int y) {
        return y >= 28 && y < 28 + ROW_HEIGHT;
    }

    private @Nullable Setting<?> hitSetting(final int x, final int y) {
        if (this.selectedModule == null) {
            return null;
        }
        List<Setting<?>> visibleSettings = this.selectedModule.settings().stream().filter(Setting::isVisible).toList();
        int listTop = 28 + ROW_HEIGHT + 4;
        int row = (y - listTop) / ROW_HEIGHT + this.settingScroll;
        if (row < 0 || row >= visibleSettings.size()) {
            return null;
        }
        int rowY = listTop + (row - this.settingScroll) * ROW_HEIGHT;
        return y >= rowY && y < rowY + ROW_HEIGHT ? visibleSettings.get(row) : null;
    }

    private boolean isOverBack(final int x, final int y) {
        return x >= 8 && x < 60 && y >= 4 && y < 20;
    }

    private boolean isOverModeButton(final int x, final int y) {
        String modeText = "Mode: " + Client.getInstance().getClientModeManager().get().name();
        int right = this.width - 8;
        int left = right - this.font.width(modeText) - 6;
        return x >= left && x < right && y >= 4 && y < 18;
    }

    private void cycleMode() {
        this.minecraft.gui.setScreen(new ModeSelectScreen(this));
    }

    private int maxModuleScroll() {
        if (this.selectedCategory == null) {
            return 0;
        }
        int cols = 2;
        int rows = (this.modules.byCategory(this.selectedCategory).size() + cols - 1) / cols;
        int visibleRows = Math.max(1, (this.height - 50 - 10) / (CARD_HEIGHT + CARD_GAP));
        return Math.max(0, rows - visibleRows);
    }

    private int maxSettingScroll() {
        if (this.selectedModule == null) {
            return 0;
        }
        long count = this.selectedModule.settings().stream().filter(Setting::isVisible).count();
        int visibleRows = Math.max(1, (this.height - 28 - ROW_HEIGHT - 8) / ROW_HEIGHT);
        return Math.max(0, (int) count - visibleRows);
    }
}
