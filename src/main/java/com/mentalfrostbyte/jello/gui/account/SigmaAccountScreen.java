package com.mentalfrostbyte.jello.gui.account;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.account.SigmaAccountManager;
import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountEntry;
import com.mentalfrostbyte.jello.account.SigmaAccountManager.AccountType;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * 26.2 account manager that keeps the old Sigma Jello/Classic account-management workflow without
 * carrying forward the legacy password/session implementation.
 */
public final class SigmaAccountScreen extends Screen {

    public enum Style {
        JELLO,
        CLASSIC
    }

    private static final Identifier JELLO_BACKGROUND = legacy("jello/background/background.png");
    private static final Identifier JELLO_MIDDLE = legacy("jello/background/middle.png");
    private static final Identifier JELLO_FOREGROUND = legacy("jello/background/foreground.png");
    private static final Identifier CLASSIC_BACKGROUND = legacy("classic/mainmenubackground.png");

    private static final int JELLO_LIGHT = 0xFFEAF7FA;
    private static final int JELLO_PANEL = 0xD9152E35;
    private static final int JELLO_PANEL_ALT = 0xC8213C43;
    private static final int JELLO_SELECTED = 0xC9447180;
    private static final int CLASSIC_PANEL = 0xD0121518;
    private static final int CLASSIC_PANEL_ALT = 0xD421252A;
    private static final int CLASSIC_SELECTED = 0xD33A4650;
    private static final int TEXT_DIM = 0xFFAFBBC0;
    private static final int TEXT_GOOD = 0xFF7DDA9B;
    private static final int TEXT_WARN = 0xFFFFD36A;

    private final Screen parent;
    private final Style style;
    private final SigmaAccountManager accounts = Client.getInstance().getAccountManager();

    private EditBox searchBox;
    private EditBox offlineNameBox;
    private Button useButton;
    private Button deleteButton;
    private Button microsoftButton;

    private String selectedRowId;
    private int scroll;
    private volatile String status = "Select an account, or add one.";
    private volatile String deviceCode = "";
    private volatile boolean loginInProgress;

    public SigmaAccountScreen(final Screen parent, final Style style) {
        super(Component.literal(style == Style.JELLO ? "Alt Manager" : "Account Manager"));
        this.parent = parent;
        this.style = style;
        this.selectedRowId = this.accounts.selectedId();
    }

    @Override
    protected void init() {
        Layout layout = this.layout();

        this.searchBox = new EditBox(this.font, layout.listX + 10, layout.top + 34, layout.listWidth - 20, 20, Component.literal("Search accounts"));
        this.searchBox.setHint(Component.literal("Search..."));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(value -> {
            this.scroll = 0;
            this.clampScroll();
        });
        this.addRenderableWidget(this.searchBox);

        int detailX = layout.detailX + 12;
        int detailWidth = layout.detailWidth - 24;
        this.microsoftButton = this.addRenderableWidget(
            Button.builder(Component.literal("Microsoft Login"), button -> this.beginMicrosoftLogin())
                .bounds(detailX, layout.top + 36, detailWidth, 20)
                .build()
        );

        this.offlineNameBox = new EditBox(this.font, detailX, layout.top + 72, detailWidth, 20, Component.literal("Offline username"));
        this.offlineNameBox.setHint(Component.literal("Offline username..."));
        this.offlineNameBox.setMaxLength(16);
        this.addRenderableWidget(this.offlineNameBox);

        this.addRenderableWidget(
            Button.builder(Component.literal("Add Offline"), button -> this.addOffline())
                .bounds(detailX, layout.top + 96, detailWidth, 20)
                .build()
        );

        this.useButton = this.addRenderableWidget(
            Button.builder(Component.literal("Use Next Launch"), button -> this.useSelected())
                .bounds(detailX, layout.top + 144, detailWidth, 20)
                .build()
        );
        this.deleteButton = this.addRenderableWidget(
            Button.builder(Component.literal("Delete"), button -> this.deleteSelected())
                .bounds(detailX, layout.top + 168, detailWidth, 20)
                .build()
        );
        this.addRenderableWidget(
            Button.builder(Component.literal("Use Launcher Account"), button -> this.useLauncherAccount())
                .bounds(detailX, layout.top + 204, detailWidth, 20)
                .build()
        );
        this.addRenderableWidget(
            Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(layout.right - 88, layout.bottom + 9, 78, 20)
                .build()
        );

        this.updateControls();
        this.clampScroll();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        this.drawBackground(graphics);
        Layout layout = this.layout();
        int panel = this.style == Style.JELLO ? JELLO_PANEL : CLASSIC_PANEL;
        int panelAlt = this.style == Style.JELLO ? JELLO_PANEL_ALT : CLASSIC_PANEL_ALT;
        int selected = this.style == Style.JELLO ? JELLO_SELECTED : CLASSIC_SELECTED;
        int text = this.style == Style.JELLO ? JELLO_LIGHT : 0xFFF0F0F0;

        graphics.fill(layout.left, layout.top, layout.right, layout.bottom, panel);
        graphics.fill(layout.listX, layout.top, layout.listX + layout.listWidth, layout.bottom, panelAlt);
        graphics.fill(layout.detailX, layout.top, layout.right, layout.bottom, panel);
        graphics.outline(layout.left, layout.top, layout.right - layout.left, layout.bottom - layout.top, 0x507EA1AA);

        String title = this.style == Style.JELLO ? "Alt Manager" : "Account Manager";
        graphics.text(this.font, title, layout.left + 12, layout.top + 11, text, false);
        graphics.text(this.font, this.accounts.accounts().size() + " accounts", layout.listX + layout.listWidth - this.font.width(this.accounts.accounts().size() + " accounts") - 10,
            layout.top + 11, TEXT_DIM, false);

        this.drawAccountRows(graphics, layout, mouseX, mouseY, selected, text);
        this.drawDetail(graphics, layout, text);

        graphics.text(this.font, "Current session: " + this.minecraft.getUser().getName(), layout.left + 10, layout.bottom + 15, TEXT_DIM, false);
        graphics.text(this.font, this.status, layout.left + 10, layout.bottom - 18, statusColor(), false);
        if (!this.deviceCode.isBlank()) {
            String codeText = "Microsoft code: " + this.deviceCode + " (copied to clipboard)";
            graphics.text(this.font, codeText, layout.detailX + 12, layout.top + 120, TEXT_WARN, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() == 0) {
            Layout layout = this.layout();
            int x = (int) event.x();
            int y = (int) event.y();
            if (x >= layout.listX && x < layout.listX + layout.listWidth && y >= layout.rowsTop && y < layout.rowsBottom) {
                int row = (y - layout.rowsTop) / layout.rowHeight;
                List<AccountEntry> visible = this.filteredAccounts();
                int index = this.scroll + row;
                if (index >= 0 && index < visible.size()) {
                    AccountEntry account = visible.get(index);
                    boolean wasSelected = account.getId().equals(this.selectedRowId);
                    this.selectedRowId = account.getId();
                    this.status = account.getName() + " selected.";
                    this.updateControls();
                    if (doubleClick || wasSelected && event.hasShiftDown()) {
                        this.useSelected();
                    }
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        Layout layout = this.layout();
        if (x >= layout.listX && x < layout.listX + layout.listWidth && y >= layout.rowsTop && y < layout.rowsBottom) {
            this.scroll -= (int) Math.signum(scrollY);
            this.clampScroll();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private void beginMicrosoftLogin() {
        if (this.loginInProgress) {
            return;
        }

        this.loginInProgress = true;
        this.microsoftButton.active = false;
        this.deviceCode = "";
        this.status = "Starting Microsoft device login...";

        Thread thread = new Thread(() -> {
            try {
                AccountEntry account = this.accounts.loginMicrosoft(code -> {
                    this.deviceCode = code.getUserCode();
                    this.status = "Finish Microsoft login in your browser.";
                    this.minecraft.execute(() -> {
                        this.minecraft.keyboardHandler.setClipboard(code.getUserCode());
                        Util.getPlatform().openUri(code.getDirectVerificationUri());
                    });
                });
                this.minecraft.execute(() -> {
                    this.selectedRowId = account.getId();
                    this.status = "Added Microsoft account " + account.getName() + ". Select Use Next Launch to activate it.";
                    this.deviceCode = "";
                    this.loginInProgress = false;
                    this.updateControls();
                    this.clampScroll();
                });
            } catch (Exception failure) {
                Client.logger.error("Microsoft account login failed", failure);
                this.minecraft.execute(() -> {
                    this.status = "Microsoft login failed: " + concise(failure);
                    this.deviceCode = "";
                    this.loginInProgress = false;
                    this.updateControls();
                });
            }
        }, "Sigma-Microsoft-Login");
        thread.setDaemon(true);
        thread.start();
    }

    private void addOffline() {
        try {
            AccountEntry account = this.accounts.addOffline(this.offlineNameBox.getValue());
            this.selectedRowId = account.getId();
            this.offlineNameBox.setValue("");
            this.status = "Added offline account " + account.getName() + ".";
            this.updateControls();
            this.clampScroll();
        } catch (IllegalArgumentException failure) {
            this.status = failure.getMessage();
        }
    }

    private void useSelected() {
        AccountEntry account = this.selectedRow();
        if (account == null) {
            this.status = "Select an account first.";
            return;
        }

        if (this.accounts.selectForNextLaunch(account.getId())) {
            this.status = account.getName() + " will be used after restarting the client.";
            this.updateControls();
        }
    }

    private void useLauncherAccount() {
        this.accounts.useLauncherIdentity();
        this.status = "Launcher identity will be used on the next launch.";
        this.updateControls();
    }

    private void deleteSelected() {
        AccountEntry account = this.selectedRow();
        if (account == null) {
            return;
        }

        String name = account.getName();
        if (this.accounts.remove(account.getId())) {
            this.selectedRowId = null;
            this.status = "Deleted " + name + ".";
            this.updateControls();
            this.clampScroll();
        }
    }

    private void updateControls() {
        if (this.useButton != null) {
            this.useButton.active = this.selectedRow() != null;
        }
        if (this.deleteButton != null) {
            this.deleteButton.active = this.selectedRow() != null;
        }
        if (this.microsoftButton != null) {
            this.microsoftButton.active = !this.loginInProgress;
        }
    }

    private void drawAccountRows(
        final GuiGraphicsExtractor graphics,
        final Layout layout,
        final int mouseX,
        final int mouseY,
        final int selectedColor,
        final int textColor
    ) {
        List<AccountEntry> visible = this.filteredAccounts();
        int rows = layout.visibleRows();
        String nextId = this.accounts.selectedId();

        for (int row = 0; row < rows; row++) {
            int index = this.scroll + row;
            if (index >= visible.size()) {
                break;
            }

            AccountEntry account = visible.get(index);
            int y = layout.rowsTop + row * layout.rowHeight;
            boolean selected = account.getId().equals(this.selectedRowId);
            boolean hovered = mouseX >= layout.listX + 6 && mouseX < layout.listX + layout.listWidth - 6
                && mouseY >= y && mouseY < y + layout.rowHeight - 2;
            if (selected) {
                graphics.fill(layout.listX + 6, y, layout.listX + layout.listWidth - 6, y + layout.rowHeight - 2, selectedColor);
            } else if (hovered) {
                graphics.fill(layout.listX + 6, y, layout.listX + layout.listWidth - 6, y + layout.rowHeight - 2, 0x25FFFFFF);
            }

            String type = account.getType() == AccountType.MICROSOFT ? "Microsoft" : "Offline";
            graphics.text(this.font, account.getName(), layout.listX + 14, y + 7, textColor, false);
            graphics.text(this.font, type, layout.listX + 14, y + 19, TEXT_DIM, false);
            if (account.getId().equals(nextId)) {
                String next = "NEXT";
                graphics.text(this.font, next, layout.listX + layout.listWidth - this.font.width(next) - 14, y + 7, TEXT_GOOD, false);
            }
        }

        if (visible.isEmpty()) {
            graphics.text(this.font, "No accounts", layout.listX + 14, layout.rowsTop + 10, TEXT_DIM, false);
        }
    }

    private void drawDetail(final GuiGraphicsExtractor graphics, final Layout layout, final int textColor) {
        AccountEntry account = this.selectedRow();
        int x = layout.detailX + 12;
        int y = layout.top + 244;
        graphics.text(this.font, "Selected account", x, y, TEXT_DIM, false);
        if (account == null) {
            graphics.text(this.font, "None", x, y + 16, textColor, false);
            return;
        }

        graphics.text(this.font, account.getName(), x, y + 16, textColor, false);
        graphics.text(this.font, account.getType() == AccountType.MICROSOFT ? "Microsoft OAuth" : "Offline profile", x, y + 30, TEXT_DIM, false);
        graphics.text(this.font, account.getProfileId().toString(), x, y + 44, TEXT_DIM, false);
        graphics.text(this.font, "Use count: " + account.getUseCount(), x, y + 58, TEXT_DIM, false);
    }

    private void drawBackground(final GuiGraphicsExtractor graphics) {
        if (this.style == Style.JELLO) {
            blitFull(graphics, JELLO_BACKGROUND);
            blitFull(graphics, JELLO_MIDDLE);
            blitFull(graphics, JELLO_FOREGROUND);
            graphics.fill(0, 0, this.width, this.height, 0x4012242A);
        } else {
            int overscan = 8;
            graphics.blit(RenderPipelines.GUI_TEXTURED, CLASSIC_BACKGROUND, -overscan, -overscan, 0.0F, 0.0F,
                this.width + overscan * 2, this.height + overscan * 2,
                this.width + overscan * 2, this.height + overscan * 2);
            graphics.fill(0, 0, this.width, this.height, 0x52000000);
        }
    }

    private void clampScroll() {
        Layout layout = this.layout();
        int max = Math.max(0, this.filteredAccounts().size() - layout.visibleRows());
        this.scroll = Math.max(0, Math.min(this.scroll, max));
    }

    private List<AccountEntry> filteredAccounts() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return this.accounts.accounts();
        }
        return this.accounts.accounts().stream()
            .filter(account -> account.getName().toLowerCase(Locale.ROOT).contains(query)
                || account.getType().name().toLowerCase(Locale.ROOT).contains(query))
            .toList();
    }

    private AccountEntry selectedRow() {
        if (this.selectedRowId == null) {
            return null;
        }
        for (AccountEntry account : this.accounts.accounts()) {
            if (account.getId().equals(this.selectedRowId)) {
                return account;
            }
        }
        return null;
    }

    private Layout layout() {
        int width = Math.min(720, Math.max(520, this.width - 36));
        int left = (this.width - width) / 2;
        int right = left + width;
        int top = Math.max(30, (this.height - 430) / 2);
        int bottom = Math.min(this.height - 42, top + 390);
        int listWidth = Math.round(width * 0.62F);
        int detailX = left + listWidth;
        int rowHeight = 36;
        int rowsTop = top + 62;
        int rowsBottom = bottom - 8;
        return new Layout(left, right, top, bottom, left, listWidth, detailX, right - detailX, rowsTop, rowsBottom, rowHeight);
    }

    private int statusColor() {
        String lower = this.status.toLowerCase(Locale.ROOT);
        if (lower.contains("failed") || lower.contains("must be") || lower.contains("select an account first")) {
            return 0xFFFF8383;
        }
        if (lower.contains("added") || lower.contains("will be used") || lower.contains("launcher identity")) {
            return TEXT_GOOD;
        }
        return TEXT_DIM;
    }

    private static String concise(final Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() > 96 ? message.substring(0, 93) + "..." : message;
    }

    private static void blitFull(final GuiGraphicsExtractor graphics, final Identifier texture) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F,
            graphics.guiWidth(), graphics.guiHeight(), graphics.guiWidth(), graphics.guiHeight());
    }

    private static Identifier legacy(final String path) {
        return Identifier.withDefaultNamespace("textures/gui/sigma/legacy/" + path);
    }

    private record Layout(
        int left,
        int right,
        int top,
        int bottom,
        int listX,
        int listWidth,
        int detailX,
        int detailWidth,
        int rowsTop,
        int rowsBottom,
        int rowHeight
    ) {
        private int visibleRows() {
            return Math.max(1, (this.rowsBottom - this.rowsTop) / this.rowHeight);
        }
    }
}
