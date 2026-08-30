package com.mentalfrostbyte.jello.gui.click;

import com.mentalfrostbyte.jello.module.BindMode;
import com.mentalfrostbyte.jello.module.Keybind;
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
import org.lwjgl.glfw.GLFW;

/**
 * A deliberately small ClickGUI vertical slice.
 *
 * <p>It is a plain {@link Screen} that reads the existing {@link ModuleManager} and the modules'
 * {@link SettingHolder} views. There is no second module registry here: categories, modules, settings and
 * keybinds are all the real objects the client already uses.</p>
 *
 * <p>The layout is three panels - categories, modules, settings - so it is easy to grow into a full
 * client GUI later without changing the data flow.</p>
 */
public class ClickGuiScreen extends Screen {

    private static final int PANEL_BG = 0xC0101010;
    private static final int PANEL_BORDER = 0xFF3A3A3A;
    private static final int HEADER_BG = 0xD0202020;
    private static final int SELECTED_BG = 0xFF2D4A6E;
    private static final int HOVER_BG = 0x30FFFFFF;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF9A9A9A;
    private static final int TEXT_ENABLED = 0xFF6EE76E;
    private static final int TEXT_DISABLED = 0xFFD0D0D0;

    private static final int ROW_HEIGHT = 18;
    private static final int PANEL_TOP = 26;
    private static final int PANEL_BOTTOM_MARGIN = 6;
    private static final int CATEGORY_X = 8;
    private static final int CATEGORY_WIDTH = 110;
    private static final int MODULE_X = 126;
    private static final int MODULE_WIDTH = 140;
    private static final int SETTINGS_X = 274;
    private static final int SETTINGS_WIDTH = 230;

    private final ModuleManager modules;

    private ModuleCategory selectedCategory;
    private Module selectedModule;

    private int categoryScroll;
    private int moduleScroll;
    private int settingScroll;

    private @Nullable Module bindingModule;
    private @Nullable Setting<?> editingSetting;
    private String editingBuffer = "";

    private @Nullable NumberSetting draggingNumber;
    private int draggingTrackLeft;
    private int draggingTrackRight;

    public ClickGuiScreen(final ModuleManager modules) {
        super(Component.literal("Sigma ClickGUI"));
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

        if (this.selectedCategory == null) {
            this.selectedCategory = ModuleCategory.MISC;
        }

        if (this.selectedModule == null || this.selectedModule.getCategory() != this.selectedCategory) {
            List<Module> categoryModules = this.modules.byCategory(this.selectedCategory);
            this.selectedModule = categoryModules.isEmpty() ? null : categoryModules.get(0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x90000000);
        this.drawCategoryPanel(graphics, mouseX, mouseY);
        this.drawModulePanel(graphics, mouseX, mouseY);
        this.drawSettingsPanel(graphics, mouseX, mouseY);
        graphics.text(this.font, "RShift: close | Left click: toggle/select | Right click keybind: cycle mode", 8, 8, TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (this.bindingModule != null) {
            InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(event.button());
            this.bindingModule.setKeybind(Keybind.of(key));
            this.bindingModule = null;
            return true;
        }

        int button = event.button();
        int x = (int) event.x();
        int y = (int) event.y();

        Integer categoryIndex = this.hitCategory(x, y);
        if (categoryIndex != null) {
            this.selectedCategory = ModuleCategory.values()[categoryIndex];
            this.moduleScroll = 0;
            this.settingScroll = 0;
            List<Module> categoryModules = this.modules.byCategory(this.selectedCategory);
            this.selectedModule = categoryModules.isEmpty() ? null : categoryModules.get(0);
            return true;
        }

        Integer moduleIndex = this.hitModule(x, y);
        if (moduleIndex != null) {
            Module module = this.modules.byCategory(this.selectedCategory).get(moduleIndex);
            this.selectedModule = module;
            this.settingScroll = 0;
            module.toggle();
            return true;
        }

        if (this.selectedModule != null && this.isInsideSettingsPanel(x, y)) {
            if (this.hitKeybindRow(y)) {
                if (button == 0) {
                    this.bindingModule = this.selectedModule;
                } else if (button == 1) {
                    this.cycleKeybindMode(this.selectedModule);
                }
                return true;
            }

            Setting<?> setting = this.hitSetting(x, y);
            if (setting != null) {
                if (button == 0) {
                    this.handleSettingClick(setting, x);
                }
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (this.draggingNumber != null) {
            this.updateDraggedNumber((int) event.x());
            return true;
        }

        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        this.draggingNumber = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        int direction = (int) Math.signum(scrollY);
        if (this.isInsideCategoryPanel((int) x, (int) y)) {
            this.categoryScroll -= direction;
            this.categoryScroll = Math.max(0, Math.min(this.categoryScroll, this.maxCategoryScroll()));
            return true;
        }

        if (this.isInsideModulePanel((int) x, (int) y)) {
            this.moduleScroll -= direction;
            this.moduleScroll = Math.max(0, Math.min(this.moduleScroll, this.maxModuleScroll()));
            return true;
        }

        if (this.isInsideSettingsPanel((int) x, (int) y)) {
            this.settingScroll -= direction;
            this.settingScroll = Math.max(0, Math.min(this.settingScroll, this.maxSettingScroll()));
            return true;
        }

        return super.mouseScrolled(x, y, scrollX, scrollY);
    }


    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (this.bindingModule != null) {
            if (event.isEscape()) {
                this.bindingModule = null;
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                this.bindingModule.setKeybind(Keybind.UNBOUND);
                this.bindingModule = null;
                return true;
            }

            this.bindingModule.setKeybind(Keybind.of(InputConstants.getKey(event)));
            this.bindingModule = null;
            return true;
        }

        if (this.editingSetting != null) {
            if (event.isEscape()) {
                this.editingSetting = null;
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                this.commitEdit();
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!this.editingBuffer.isEmpty()) {
                    this.editingBuffer = this.editingBuffer.substring(0, this.editingBuffer.length() - 1);
                }
                return true;
            }

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
        if (this.editingSetting != null) {
            if (event.isAllowedChatCharacter()) {
                this.editingBuffer = this.editingBuffer + event.codepointAsString();
            }
            return true;
        }

        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        ClickGuiHandler.close();
    }

    private void drawCategoryPanel(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        int x = CATEGORY_X;
        int y = PANEL_TOP;
        int w = CATEGORY_WIDTH;
        int h = this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN;
        this.drawPanel(graphics, x, y, w, h, "Categories");

        int listTop = y + 16;
        int visibleRows = this.visibleRows(h);
        ModuleCategory[] categories = ModuleCategory.values();
        for (int row = 0; row < visibleRows; row++) {
            int index = row + this.categoryScroll;
            if (index >= categories.length) {
                break;
            }

            int rowY = listTop + row * ROW_HEIGHT;
            ModuleCategory category = categories[index];
            boolean selected = category == this.selectedCategory;
            boolean hovered = mouseX >= x + 1 && mouseX < x + w - 1 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected) {
                graphics.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT - 1, SELECTED_BG);
            } else if (hovered) {
                graphics.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT - 1, HOVER_BG);
            }

            graphics.text(this.font, category.getDisplayName(), x + 4, rowY + 5, selected ? TEXT : TEXT_DIM);
        }
    }

    private void drawModulePanel(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        int x = MODULE_X;
        int y = PANEL_TOP;
        int w = MODULE_WIDTH;
        int h = this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN;
        this.drawPanel(graphics, x, y, w, h, "Modules");

        if (this.selectedCategory == null) {
            return;
        }

        List<Module> categoryModules = this.modules.byCategory(this.selectedCategory);
        int listTop = y + 16;
        int visibleRows = this.visibleRows(h);
        for (int row = 0; row < visibleRows; row++) {
            int index = row + this.moduleScroll;
            if (index >= categoryModules.size()) {
                break;
            }

            int rowY = listTop + row * ROW_HEIGHT;
            Module module = categoryModules.get(index);
            boolean selected = module == this.selectedModule;
            boolean hovered = mouseX >= x + 1 && mouseX < x + w - 1 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected) {
                graphics.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT - 1, SELECTED_BG);
            } else if (hovered) {
                graphics.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT - 1, HOVER_BG);
            }

            int nameColor = module.isEnabled() ? TEXT_ENABLED : TEXT_DISABLED;
            graphics.text(this.font, module.getName(), x + 4, rowY + 5, nameColor);
            if (module.isEnabled()) {
                graphics.text(this.font, "ON", x + w - this.font.width("ON") - 4, rowY + 5, TEXT_ENABLED);
            }
        }
    }

    private void drawSettingsPanel(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        int x = SETTINGS_X;
        int y = PANEL_TOP;
        int w = SETTINGS_WIDTH;
        int h = this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN;
        this.drawPanel(graphics, x, y, w, h, "Settings");

        if (this.selectedModule == null) {
            graphics.text(this.font, "Select a module", x + 4, y + 24, TEXT_DIM);
            return;
        }

        Module module = this.selectedModule;
        int keybindY = y + 20;
        String keybindText = this.bindingModule == module
            ? "Press a key... (Esc cancel, Del unbind)"
            : "Bind: " + this.keybindDisplay(module.getKeybind());
        graphics.text(this.font, keybindText, x + 4, keybindY + 5, this.bindingModule == module ? 0xFFFFFF55 : TEXT);
        String modeText = module.getKeybind().mode().name();
        graphics.text(this.font, modeText, x + w - this.font.width(modeText) - 4, keybindY + 5, TEXT_DIM);

        List<Setting<?>> visibleSettings = module.settings().stream().filter(Setting::isVisible).toList();
        int listTop = keybindY + ROW_HEIGHT + 2;
        int visibleRows = this.visibleRows(h - (listTop - y));
        for (int row = 0; row < visibleRows; row++) {
            int index = row + this.settingScroll;
            if (index >= visibleSettings.size()) {
                break;
            }

            int rowY = listTop + row * ROW_HEIGHT;
            Setting<?> setting = visibleSettings.get(index);
            if (rowY + ROW_HEIGHT > y + h) {
                break;
            }

            boolean hovered = mouseX >= x + 1 && mouseX < x + w - 1 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT - 1, HOVER_BG);
            }

            this.drawSetting(graphics, setting, x, rowY, w, mouseX);
        }
    }

    private void drawSetting(final GuiGraphicsExtractor graphics, final Setting<?> setting, final int panelX, final int y, final int panelWidth, final int mouseX) {
        if (setting instanceof BooleanSetting bool) {
            String label = bool.getName() + ": " + (bool.get() ? "ON" : "OFF");
            graphics.text(this.font, label, panelX + 4, y + 5, bool.get() ? TEXT_ENABLED : TEXT_DIM);
            int boxX = panelX + panelWidth - 16;
            graphics.fill(boxX, y + 3, boxX + 10, y + 13, bool.get() ? 0xFF2E7D32 : 0xFF555555);
            graphics.outline(boxX, y + 3, 10, 10, PANEL_BORDER);
            return;
        }

        if (setting instanceof NumberSetting number) {
            String label = number.getName() + ": " + String.format(Locale.ROOT, "%." + number.getDecimalPlaces() + "f", number.get());
            graphics.text(this.font, label, panelX + 4, y + 1, TEXT);
            int trackX = panelX + 4;
            int trackY = y + 12;
            int trackWidth = panelWidth - 8;
            float numberRange = number.getMax() - number.getMin();
            int fillWidth = numberRange <= 0.0F ? trackWidth
                : (int) ((number.get() - number.getMin()) / numberRange * trackWidth);
            graphics.fill(trackX, trackY, trackX + trackWidth, trackY + 3, 0xFF333333);
            graphics.fill(trackX, trackY, trackX + fillWidth, trackY + 3, 0xFF4A6FA5);
            return;
        }

        if (setting instanceof EnumSetting<?> enumSetting) {
            String label = setting.getName() + ": " + enumSetting.get().name();
            graphics.text(this.font, label, panelX + 4, y + 5, TEXT);
            graphics.text(this.font, ">", panelX + panelWidth - 10, y + 5, TEXT_DIM);
            return;
        }

        if (setting instanceof ColorSetting color) {
            String value = this.editingSetting == setting ? this.editingBuffer : this.colorDisplay(color.get());
            graphics.text(this.font, setting.getName() + ": " + value, panelX + 4, y + 5, TEXT);
            int swatchX = panelX + panelWidth - 16;
            graphics.fill(swatchX, y + 3, swatchX + 10, y + 13, color.get());
            graphics.outline(swatchX, y + 3, 10, 10, PANEL_BORDER);
            return;
        }

        if (setting instanceof TextSetting) {
            String value = this.editingSetting == setting ? this.editingBuffer : setting.get().toString();
            graphics.text(this.font, setting.getName() + ": " + value, panelX + 4, y + 5, TEXT);
        }
    }

    private void handleSettingClick(final Setting<?> setting, final int mouseX) {
        if (setting instanceof BooleanSetting bool) {
            bool.toggle();
            return;
        }

        if (setting instanceof NumberSetting number) {
            this.draggingNumber = number;
            int panelX = SETTINGS_X;
            this.draggingTrackLeft = panelX + 4;
            this.draggingTrackRight = panelX + SETTINGS_WIDTH - 4;
            this.updateDraggedNumber(mouseX);
            return;
        }

        if (setting instanceof EnumSetting<?> enumSetting) {
            enumSetting.cycle();
            return;
        }

        if (setting instanceof ColorSetting color) {
            this.editingSetting = setting;
            this.editingBuffer = this.colorDisplay(color.get());
            return;
        }

        if (setting instanceof TextSetting) {
            this.editingSetting = setting;
            this.editingBuffer = setting.get().toString();
        }
    }

    private void updateDraggedNumber(final int mouseX) {
        NumberSetting number = this.draggingNumber;
        if (number == null) {
            return;
        }

        float range = number.getMax() - number.getMin();
        if (range <= 0.0F) {
            return;
        }

        float fraction = (mouseX - this.draggingTrackLeft) / (float) (this.draggingTrackRight - this.draggingTrackLeft);
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        float raw = number.getMin() + fraction * range;
        float stepped = Math.round(raw / number.getStep()) * number.getStep();
        number.set(stepped);
    }

    private void commitEdit() {
        Setting<?> setting = this.editingSetting;
        if (setting == null) {
            return;
        }

        if (setting instanceof TextSetting text) {
            text.set(this.editingBuffer);
        } else if (setting instanceof ColorSetting color) {
            this.parseColor(this.editingBuffer).ifPresent(color::set);
        }

        this.editingSetting = null;
    }

    private Optional<Integer> parseColor(final String input) {
        String text = input.trim();
        try {
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 6) {
                    return Optional.of(0xFF000000 | Integer.parseInt(hex, 16));
                }
                if (hex.length() == 8) {
                    return Optional.of((int) Long.parseLong(hex, 16));
                }
                return Optional.empty();
            }

            return Optional.of(Long.decode(text).intValue());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private String colorDisplay(final int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    private String keybindDisplay(final Keybind keybind) {
        if (!keybind.isBound()) {
            return "UNBOUND";
        }

        return keybind.key().getName();
    }

    private void cycleKeybindMode(final Module module) {
        BindMode[] modes = BindMode.values();
        BindMode current = module.getKeybind().mode();
        module.setKeybind(new Keybind(module.getKeybind().key(), modes[(current.ordinal() + 1) % modes.length]));
    }

    private void drawPanel(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h, final String title) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        graphics.outline(x, y, w, h, PANEL_BORDER);
        graphics.fill(x, y, x + w, y + 14, HEADER_BG);
        graphics.text(this.font, title, x + 4, y + 3, TEXT);
    }

    private int visibleRows(final int panelHeight) {
        return Math.max(0, (panelHeight - 16) / ROW_HEIGHT);
    }

    private int maxCategoryScroll() {
        return Math.max(0, ModuleCategory.values().length - this.visibleRows(this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN));
    }

    private int maxModuleScroll() {
        if (this.selectedCategory == null) {
            return 0;
        }

        int panelHeight = this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN;
        return Math.max(0, this.modules.byCategory(this.selectedCategory).size() - this.visibleRows(panelHeight));
    }

    private int maxSettingScroll() {
        if (this.selectedModule == null) {
            return 0;
        }

        int panelHeight = this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN;
        int listTopOffset = 20 + ROW_HEIGHT + 2;
        int visibleRows = this.visibleRows(panelHeight - listTopOffset);
        long settingCount = this.selectedModule.settings().stream().filter(Setting::isVisible).count();
        return Math.max(0, (int) settingCount - visibleRows);
    }

    private boolean isInsideCategoryPanel(final int x, final int y) {
        return x >= CATEGORY_X && x < CATEGORY_X + CATEGORY_WIDTH && y >= PANEL_TOP && y < this.height - PANEL_BOTTOM_MARGIN;
    }

    private boolean isInsideModulePanel(final int x, final int y) {
        return x >= MODULE_X && x < MODULE_X + MODULE_WIDTH && y >= PANEL_TOP && y < this.height - PANEL_BOTTOM_MARGIN;
    }

    private boolean isInsideSettingsPanel(final int x, final int y) {
        return x >= SETTINGS_X && x < SETTINGS_X + SETTINGS_WIDTH && y >= PANEL_TOP && y < this.height - PANEL_BOTTOM_MARGIN;
    }

    private @Nullable Integer hitCategory(final int x, final int y) {
        if (!this.isInsideCategoryPanel(x, y)) {
            return null;
        }

        int listTop = PANEL_TOP + 16;
        int row = (y - listTop) / ROW_HEIGHT + this.categoryScroll;
        if (row < 0 || row >= ModuleCategory.values().length) {
            return null;
        }

        int rowY = listTop + (row - this.categoryScroll) * ROW_HEIGHT;
        return y >= rowY && y < rowY + ROW_HEIGHT ? row : null;
    }

    private @Nullable Integer hitModule(final int x, final int y) {
        if (!this.isInsideModulePanel(x, y) || this.selectedCategory == null) {
            return null;
        }

        int listTop = PANEL_TOP + 16;
        int row = (y - listTop) / ROW_HEIGHT + this.moduleScroll;
        List<Module> categoryModules = this.modules.byCategory(this.selectedCategory);
        if (row < 0 || row >= categoryModules.size()) {
            return null;
        }

        int rowY = listTop + (row - this.moduleScroll) * ROW_HEIGHT;
        return y >= rowY && y < rowY + ROW_HEIGHT ? row : null;
    }

    private boolean hitKeybindRow(final int y) {
        int keybindY = PANEL_TOP + 20;
        return y >= keybindY && y < keybindY + ROW_HEIGHT;
    }

    private @Nullable Setting<?> hitSetting(final int x, final int y) {
        if (this.selectedModule == null || !this.isInsideSettingsPanel(x, y)) {
            return null;
        }

        int keybindY = PANEL_TOP + 20;
        int listTop = keybindY + ROW_HEIGHT + 2;
        int row = (y - listTop) / ROW_HEIGHT + this.settingScroll;
        List<Setting<?>> visibleSettings = this.selectedModule.settings().stream().filter(Setting::isVisible).toList();
        if (row < 0 || row >= visibleSettings.size()) {
            return null;
        }

        int rowY = listTop + (row - this.settingScroll) * ROW_HEIGHT;
        return y >= rowY && y < rowY + ROW_HEIGHT ? visibleSettings.get(row) : null;
    }
}
