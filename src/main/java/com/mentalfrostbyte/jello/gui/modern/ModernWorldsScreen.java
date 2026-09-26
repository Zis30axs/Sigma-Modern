package com.mentalfrostbyte.jello.gui.modern;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's singleplayer page: the saved worlds as a list of glass rows on the left and, on wide
 * windows, a detail card on the right - the selected world's icon, name, mode, version and any warning, with
 * Play as the page's one lit action and Edit / Re-create / Delete beneath it. Narrow windows keep the list
 * and move the actions into a bar along the bottom.
 *
 * <p>The list itself is vanilla's {@link WorldSelectionList} (loading, filtering, selection, double-click and
 * Enter to play, the no-worlds route into world creation, narration), drawn through {@link ModernRows}; every
 * action is the vanilla entry's own method, so confirmations, backups and error screens are unchanged and
 * return here.</p>
 */
public final class ModernWorldsScreen extends Screen implements ModernBlurredBackdrop {
    private static final int ROW_W = 270;   // WorldSelectionList#getRowWidth
    private static final int CARD_W = ROW_W + 30;
    private static final int BODY_TOP = ModernPage.TOP + 42;
    // Debug seam for screenshots: select the first world once the list has loaded.
    private static final boolean DEBUG_SELECT_FIRST = Boolean.getBoolean("sigma.debug.selectFirst");

    private final Screen lastScreen;
    private final ModernBackdrop backdrop = new ModernBackdrop(150, 4200F);
    private final long openStart = System.nanoTime();
    private @Nullable WorldSelectionList list;
    private @Nullable EditBox search;
    private @Nullable ModernButton play, edit, recreate, delete, create;
    private @Nullable String shownId;
    private long detailStart;

    public ModernWorldsScreen(Screen lastScreen) {
        super(Component.translatable("selectWorld.title"));
        this.lastScreen = lastScreen;
    }

    private record Layout(boolean wide, int margin, int cardX, int cardY, int cardW, int cardH,
                          int detailX, int detailW, int searchX, int searchW) {}

    private Layout layout() {
        int margin = ModernPage.margin(this.width);
        boolean wide = this.width - 2 * margin - CARD_W - 12 >= 210 && this.height >= 250;
        int searchW = Math.max(110, Math.min(200, Math.round(this.width * 0.28F)));
        int searchX = this.width - margin - searchW;
        if (wide) {
            int cardH = this.height - BODY_TOP - 12;
            int detailX = margin + CARD_W + 12;
            return new Layout(true, margin, margin, BODY_TOP, CARD_W, cardH, detailX, this.width - margin - detailX, searchX, searchW);
        }
        int cardW = Math.min(this.width - 2 * margin, CARD_W);
        int cardH = this.height - BODY_TOP - 12 - 34;
        return new Layout(false, margin, (this.width - cardW) / 2, BODY_TOP, cardW, cardH, 0, 0, searchX, searchW);
    }

    @Override
    protected void init() {
        Layout l = layout();
        this.list = new WorldSelectionList.Builder(this.minecraft, this)
            .width(l.cardW() - 4)
            .height(listHeight(l))
            .filter(this.search == null ? "" : this.search.getValue())
            .oldList(this.list)
            .onEntrySelect(summary -> this.updateButtons())
            .onEntryInteract(WorldSelectionList.WorldListEntry::joinWorld)
            .build();
        this.buildWidgets();
    }

    /**
     * A screen is only {@link #init()}ed once; coming back from a sub-screen (edit, delete, create) or a resize
     * lands here. Rebuild the page's widgets around the same list - which, like vanilla's, reloads itself after
     * {@link WorldSelectionList#returnToScreen()} - keeping keyboard focus where it was.
     */
    @Override
    protected void repositionElements() {
        net.minecraft.client.gui.components.events.GuiEventListener focused = this.getFocused();
        EditBox oldSearch = this.search;
        this.clearWidgets();
        this.buildWidgets();
        if (focused != null && !this.children().contains(focused)) this.setFocused(focused == oldSearch ? this.search : null);
    }

    private int listHeight(Layout l) {
        return l.cardH() - 8 - (l.wide() ? 32 : 0);
    }

    private void buildWidgets() {
        Layout l = layout();
        this.addRenderableWidget(new ModernButton(l.margin(), ModernPage.TOP, 22, 22, CommonComponents.GUI_BACK, ModernIcons.Icon.BACK,
            ModernButton.Kind.ICON, this::onClose));

        this.search = new EditBox(this.font, l.searchX() + 22, ModernPage.TOP + 7, l.searchW() - 30, 10, this.search, Component.translatable("selectWorld.search"));
        this.search.setBordered(false);
        this.search.setResponder(value -> {
            if (this.list != null) this.list.updateFilter(value);
        });
        this.addRenderableWidget(this.search);

        this.list.updateSizeAndPosition(l.cardW() - 4, listHeight(l), l.cardX() + 2, l.cardY() + 6);
        this.addRenderableWidget(this.list);

        Component createLabel = Component.translatable("selectWorld.create");
        Runnable createWorld = () -> CreateWorldScreen.openFresh(this.minecraft, this.list::returnToScreen);
        if (l.wide()) {
            this.create = new ModernButton(l.cardX() + 8, l.cardY() + l.cardH() - 28, l.cardW() - 16, 22, createLabel, ModernIcons.Icon.PLUS,
                ModernButton.Kind.GHOST, createWorld);
            int x = l.detailX() + 18, w = l.detailW() - 36, bottom = l.cardY() + l.cardH() - 16;
            this.play = new ModernButton(x, bottom - 52, w, 24, LevelSummary.PLAY_WORLD, ModernIcons.Icon.PLAY, ModernButton.Kind.PRIMARY, this::playSelected);
            int third = (w - 12) / 3;
            this.edit = new ModernButton(x, bottom - 22, third, 22, Component.translatable("selectWorld.edit"), ModernIcons.Icon.EDIT,
                ModernButton.Kind.SECONDARY, () -> this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::editWorld));
            this.recreate = new ModernButton(x + third + 6, bottom - 22, third, 22, Component.translatable("selectWorld.recreate"), ModernIcons.Icon.DUPLICATE,
                ModernButton.Kind.SECONDARY, () -> this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::recreateWorld));
            this.delete = new ModernButton(x + 2 * (third + 6), bottom - 22, w - 2 * (third + 6), 22, Component.translatable("selectWorld.delete"),
                ModernIcons.Icon.TRASH, ModernButton.Kind.DANGER, () -> this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::deleteWorld));
        } else {
            int size = 24, gap = 6;
            int playW = Math.max(104, Math.max(ModernButton.widthFor(LevelSummary.PLAY_WORLD, true), ModernButton.widthFor(LevelSummary.UPGRADE_AND_PLAY_WORLD, true)));
            int total = playW + 4 * (size + gap);
            int x = (this.width - total) / 2, y = this.height - 12 - 28;
            this.play = new ModernButton(x, y, playW, size, LevelSummary.PLAY_WORLD, ModernIcons.Icon.PLAY, ModernButton.Kind.PRIMARY, this::playSelected);
            x += playW + gap;
            this.create = new ModernButton(x, y, size, size, createLabel, ModernIcons.Icon.PLUS, ModernButton.Kind.ICON, createWorld);
            x += size + gap;
            this.edit = new ModernButton(x, y, size, size, Component.translatable("selectWorld.edit"), ModernIcons.Icon.EDIT, ModernButton.Kind.ICON,
                () -> this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::editWorld));
            x += size + gap;
            this.recreate = new ModernButton(x, y, size, size, Component.translatable("selectWorld.recreate"), ModernIcons.Icon.DUPLICATE,
                ModernButton.Kind.ICON, () -> this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::recreateWorld));
            x += size + gap;
            this.delete = new ModernButton(x, y, size, size, Component.translatable("selectWorld.delete"), ModernIcons.Icon.TRASH, ModernButton.Kind.ICON,
                () -> this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::deleteWorld));
        }
        this.addRenderableWidget(this.create);
        this.addRenderableWidget(this.play);
        this.addRenderableWidget(this.edit);
        this.addRenderableWidget(this.recreate);
        this.addRenderableWidget(this.delete);
        this.updateButtons();
    }

    private Optional<WorldSelectionList.WorldListEntry> selectedEntry() {
        return this.list == null ? Optional.empty() : this.list.getSelectedOpt();
    }

    private void playSelected() {
        this.selectedEntry().ifPresent(WorldSelectionList.WorldListEntry::joinWorld);
    }

    /** Same enablement and tooltips as vanilla's footer buttons. */
    private void updateButtons() {
        if (this.play == null || this.edit == null || this.recreate == null || this.delete == null) return;
        LevelSummary summary = this.selectedEntry().map(WorldSelectionList.WorldListEntry::getLevelSummary).orElse(null);
        if (summary == null) {
            this.play.setMessage(LevelSummary.PLAY_WORLD);
            this.play.active = this.edit.active = this.recreate.active = this.delete.active = false;
            return;
        }
        this.play.setMessage(summary.primaryActionMessage());
        this.play.active = summary.primaryActionActive();
        this.edit.active = summary.canEdit();
        this.recreate.active = summary.canRecreate();
        this.delete.active = summary.canDelete();
        if (summary.requiresFileFixing()) {
            this.edit.setTooltip(Tooltip.create(Component.translatable("selectWorld.requiresFileFixingTooltip.edit")));
            this.play.setTooltip(Tooltip.create(Component.translatable("selectWorld.requiresFileFixingTooltip.play")));
            this.recreate.setTooltip(Tooltip.create(Component.translatable("selectWorld.requiresFileFixingTooltip.recreate")));
        } else {
            this.edit.resetTooltip();
            this.play.resetTooltip();
            this.recreate.resetTooltip();
        }
    }

    @Override
    protected void setInitialFocus() {
        if (this.search != null) this.setInitialFocus(this.search);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.lastScreen);
    }

    @Override
    public void removed() {
        if (this.list != null) this.list.children().forEach(WorldSelectionList.Entry::close);
    }

    // --- rendering ------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.backdrop.render(g, this.minecraft, this.width, this.height, mouseX, mouseY);
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (DEBUG_SELECT_FIRST && this.list != null && this.list.getSelected() == null) {
            this.list.children().stream().filter(e -> e instanceof WorldSelectionList.WorldListEntry).findFirst().ifPresent(this.list::setSelected);
        }
        Layout l = layout();
        float open = ModernStyle.easeOut((System.nanoTime() - this.openStart) / 1_000_000_000F / 0.4F);
        LevelSummary summary = this.selectedEntry().map(WorldSelectionList.WorldListEntry::getLevelSummary).orElse(null);
        // In the detail card the actions only appear once a world is chosen - the empty card explains instead.
        // The narrow layout's action shelf always shows them (disabled until a world is chosen).
        boolean show = !l.wide() || summary != null;
        for (ModernButton button : new ModernButton[]{this.play, this.edit, this.recreate, this.delete}) {
            if (button != null) button.visible = show;
        }

        try (var fade = ModernStyle.alphaScope(open)) {
            ModernPage.header(g, l.margin() + 32, ModernPage.TOP + 1, "SINGLEPLAYER", this.title.getString(), 1.55F);
            ModernPage.fieldPill(g, l.searchX(), ModernPage.TOP + 1, l.searchW(), 20, this.search != null && this.search.isFocused(),
                Component.translatable("gui.selectWorld.search").getString(), this.search == null || this.search.getValue().isEmpty());

            g.pose().pushMatrix();
            g.pose().translate(0F, (1F - open) * 10F);
            ModernPage.card(g, l.cardX(), l.cardY(), l.cardW(), l.cardH());
            if (l.wide()) {
                ModernStyle.fill(g, l.cardX() + 12, l.cardY() + l.cardH() - 34, l.cardX() + l.cardW() - 12, l.cardY() + l.cardH() - 33, 0x1AFFFFFF);
                ModernPage.card(g, l.detailX(), l.cardY(), l.detailW(), l.cardH());
                this.drawDetail(g, l, summary);
            } else {
                // No room for a detail card: a slim glass shelf carries the actions instead.
                int shelfW = this.play == null ? 0 : this.play.getWidth() + 4 * 30 + 16;
                ModernStyle.darkGlass(g, (this.width - shelfW) / 2, this.height - 12 - 32, shelfW, 32, 16, 0xB00A1B29);
            }
            g.pose().popMatrix();

            super.extractRenderState(g, mouseX, mouseY, a);
        }
    }

    private void drawDetail(GuiGraphicsExtractor g, Layout l, @Nullable LevelSummary summary) {
        int x = l.detailX(), y = l.cardY(), w = l.detailW(), h = l.cardH();
        String id = summary == null ? null : summary.getLevelId();
        if (!java.util.Objects.equals(id, this.shownId)) {
            this.shownId = id;
            this.detailStart = System.nanoTime();
        }
        float change = ModernStyle.easeOut((System.nanoTime() - this.detailStart) / 1_000_000_000F / 0.25F);
        try (var fade = ModernStyle.alphaScope(change)) {
            g.pose().pushMatrix();
            g.pose().translate(0F, (1F - change) * 6F);
            if (summary == null) {
                this.drawEmptyDetail(g, x, y, w, h);
            } else {
                this.drawWorldDetail(g, x, y, w, h, summary);
            }
            g.pose().popMatrix();
        }
    }

    private void drawEmptyDetail(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        float cx = x + w / 2F, cy = y + h / 2F - 30F;
        float breathe = 0.2F + 0.06F * (float)Math.sin(this.backdrop.time() * 1.3F);
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - 42F, cy - 42F, 84F, Math.round(255 * breathe) << 24 | 0xBFE8FF);
        ModernIcons.draw(g, ModernIcons.Icon.MOUNTAIN, cx - 20F, cy - 20F, 40F, 0xFFBFE3F5);
        String heading = "Choose a world";
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, heading,
            cx - ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, heading, 1.5F) / 2F, cy + 28F, 1.5F, 0xFFE3F2FA);
        int count = this.list == null ? 0 : (int)this.list.children().stream().filter(e -> e instanceof WorldSelectionList.WorldListEntry).count();
        String sub = count == 1 ? "1 world in your saves" : count + " worlds in your saves";
        ModernTypography.draw(g, ModernTypography.Face.TEXT, sub, cx - ModernTypography.width(ModernTypography.Face.TEXT, sub, 1F) / 2F, cy + 50F, 1F, 0xFF8FB0C4);
        String hint = "Double-click a world to play";
        ModernTypography.draw(g, ModernTypography.Face.TEXT, hint, cx - ModernTypography.width(ModernTypography.Face.TEXT, hint, 0.9F) / 2F,
            cy + 64F, 0.9F, 0xFF6F8FA4);
    }

    private void drawWorldDetail(GuiGraphicsExtractor g, int x, int y, int w, int h, LevelSummary summary) {
        WorldSelectionList.WorldListEntry entry = this.selectedEntry().orElse(null);
        if (entry == null) return;
        int pad = 18, iconSize = 64;
        int ix = x + pad, iy = y + pad;
        ModernStyle.halo(g, ix, iy, iconSize, iconSize, 8, ModernStyle.GLOW, 0.35F);
        ModernRows.worldIcon(g, entry.iconTexture(), entry.hasIcon(), ix, iy, iconSize);

        int tx = ix + iconSize + 14, tw = x + w - pad - tx;
        float nameScale = 1.45F;
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, ModernTypography.ellipsize(summary.getLevelName(), Math.round(tw / nameScale)),
            tx, iy + 2F, nameScale, 0xFFF2F8FC);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(summary.getLevelId(), tw), tx, iy + 22F, 1F, 0xFF7F9FB4);

        int chipX = tx, chipY = iy + 40;
        if (summary.isHardcore()) {
            chipX += ModernPage.chip(g, chipX, chipY, Component.translatable("gameMode.hardcore").getString(), 0xFFFFD0D5, 0x59E24556) + 5;
        } else {
            chipX += ModernPage.chip(g, chipX, chipY, summary.getGameMode().getLongDisplayName().getString(), 0xFFD6F1FF, 0x3D2E9BD6) + 5;
        }
        if (summary.hasCommands() && chipX < x + w - 60) {
            chipX += ModernPage.chip(g, chipX, chipY, Component.translatable("selectWorld.commands").getString(), 0xFFD6F1FF, 0x26FFFFFF) + 5;
        }
        if (summary.isExperimental() && chipX < x + w - 60) {
            ModernPage.chip(g, chipX, chipY, Component.translatable("selectWorld.experimental").getString(), 0xFFFFE6A8, 0x40F2C46B);
        }

        int fy = iy + iconSize + 18;
        ModernStyle.fill(g, x + pad, fy - 8, x + w - pad, fy - 7, 0x1AFFFFFF);
        int col = (w - 2 * pad) / 2;
        long lastPlayed = summary.getLastPlayed();
        ModernPage.label(g, x + pad, fy, "LAST PLAYED");
        ModernTypography.draw(g, ModernTypography.Face.TEXT, lastPlayed == -1L ? "-"
                : WorldSelectionList.DATE_FORMAT.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastPlayed), ZoneId.systemDefault())),
            x + pad, fy + 10F, 1F, 0xFFD9E8F1);
        ModernPage.label(g, x + pad + col, fy, "VERSION");
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(summary.getWorldVersionName().getString(), col - 6),
            x + pad + col, fy + 10F, 1F, summary.isDowngrade() ? ModernRows.BAD : 0xFFD9E8F1);
        net.minecraft.world.level.LevelSettings.DifficultySettings difficulty = summary.getSettings().difficultySettings();
        ModernPage.label(g, x + pad, fy + 30, "DIFFICULTY");
        float dx = x + pad;
        if (difficulty.locked()) {
            ModernIcons.draw(g, ModernIcons.Icon.LOCK, dx, fy + 40.5F, 8F, 0xFF8FB0C4);
            dx += 12F;
        }
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(difficulty.difficulty().getDisplayName().getString(), col - 18),
            dx, fy + 40F, 1F, 0xFFD9E8F1);
        ModernPage.label(g, x + pad + col, fy + 30, "CHEATS");
        ModernTypography.draw(g, ModernTypography.Face.TEXT,
            (summary.hasCommands() ? net.minecraft.network.chat.CommonComponents.OPTION_ON : net.minecraft.network.chat.CommonComponents.OPTION_OFF).getString(),
            x + pad + col, fy + 40F, 1F, 0xFFD9E8F1);

        ModernRows.Warning warning = ModernRows.warning(summary);
        if (warning != null) {
            int wy = fy + 62, ww = w - 2 * pad;
            int lines = Math.min(2, warning.lines().size());
            int wh = 12 + lines * 11;
            ModernStyle.rounded(g, x + pad, wy, ww, wh, 7, ModernTypography.fade(warning.color(), 0.14F));
            ModernStyle.fill(g, x + pad, wy + 4, x + pad + 2, wy + wh - 4, warning.color());
            ModernIcons.draw(g, ModernIcons.Icon.WARNING, x + pad + 8F, wy + 6F, 10F, warning.color());
            for (int i = 0; i < lines; i++) {
                ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(warning.lines().get(i).getString(), ww - 30),
                    x + pad + 22F, wy + 5F + i * 11F, 0.95F, 0xFFE9F2F7);
            }
        }
    }
}
