package net.minecraft.client.gui.screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.misc.AutoReconnect;
import java.util.Locale;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class DisconnectedScreen extends Screen {
    private static final Component TO_SERVER_LIST = Component.translatable("gui.toMenu");
    private static final Component TO_TITLE = Component.translatable("gui.toTitle");
    private static final Component REPORT_TO_SERVER_TITLE = Component.translatable("gui.report_to_server");
    private static final Component OPEN_REPORT_DIR_TITLE = Component.translatable("gui.open_report_dir");
    private final Screen parent;
    private final DisconnectionDetails details;
    private final Component buttonText;
    private final LinearLayout layout = LinearLayout.vertical();
    private @Nullable Button sigmaReconnectButton;
    private @Nullable Button sigmaPauseButton;
    private long sigmaReconnectAtNanos = Long.MIN_VALUE;
    private boolean sigmaReconnectPaused;

    public DisconnectedScreen(final Screen parent, final Component title, final Component reason) {
        this(parent, title, new DisconnectionDetails(reason));
    }

    public DisconnectedScreen(final Screen parent, final Component title, final Component reason, final Component buttonText) {
        this(parent, title, new DisconnectionDetails(reason), buttonText);
    }

    public DisconnectedScreen(final Screen parent, final Component title, final DisconnectionDetails details) {
        this(parent, title, details, TO_SERVER_LIST);
    }

    public DisconnectedScreen(final Screen parent, final Component title, final DisconnectionDetails details, final Component buttonText) {
        super(title);
        this.parent = parent;
        this.details = details;
        this.buttonText = buttonText;
    }

    @Override
    protected void init() {
        this.layout.defaultCellSetting().alignHorizontallyCenter().padding(10);
        this.layout.addChild(new StringWidget(this.title, this.font));
        this.layout.addChild(new MultiLineTextWidget(this.details.reason(), this.font).setMaxWidth(this.width - 50).setCentered(true));
        this.layout.defaultCellSetting().padding(2);
        this.details
            .bugReportLink()
            .ifPresent(
                bugReportLink -> this.layout
                    .addChild(Button.builder(REPORT_TO_SERVER_TITLE, ConfirmLinkScreen.confirmLink(this, bugReportLink, false)).width(200).build())
            );
        this.details
            .report()
            .ifPresent(
                report -> this.layout
                    .addChild(Button.builder(OPEN_REPORT_DIR_TITLE, button -> Util.getPlatform().openPath(report.getParent())).width(200).build())
            );
        Button backButton;
        if (this.minecraft.allowsMultiplayer()) {
            backButton = Button.builder(this.buttonText, var1x -> this.minecraft.gui.setScreen(this.parent)).width(200).build();
        } else {
            backButton = Button.builder(TO_TITLE, var1x -> this.minecraft.gui.setScreen(new TitleScreen())).width(200).build();
        }

        AutoReconnect.Target lastTarget = AutoReconnect.lastTarget();
        if (lastTarget != null && this.minecraft.allowsMultiplayer()) {
            AutoReconnect autoReconnect = Modules.enabled(AutoReconnect.class);
            if (autoReconnect != null && autoReconnect.canAttemptRetry()) {
                this.sigmaReconnectAtNanos = System.nanoTime() + autoReconnect.delayNanos();
                this.sigmaReconnectPaused = false;
            } else {
                this.sigmaReconnectAtNanos = Long.MIN_VALUE;
            }
            this.sigmaReconnectButton = Button.builder(this.sigmaReconnectLabel(), b -> this.sigmaReconnectNow()).width(200).build();
            this.layout.addChild(this.sigmaReconnectButton);
            if (this.sigmaReconnectAtNanos != Long.MIN_VALUE) {
                this.sigmaPauseButton = Button.builder(Component.literal("Pause AutoReconnect"), b -> {
                    this.sigmaReconnectPaused = !this.sigmaReconnectPaused;
                    b.setMessage(Component.literal(this.sigmaReconnectPaused ? "Resume AutoReconnect" : "Pause AutoReconnect"));
                    if (!this.sigmaReconnectPaused) {
                        AutoReconnect active = Modules.enabled(AutoReconnect.class);
                        if (active != null) {
                            this.sigmaReconnectAtNanos = System.nanoTime() + active.delayNanos();
                        }
                    }
                    if (this.sigmaReconnectButton != null) {
                        this.sigmaReconnectButton.setMessage(this.sigmaReconnectLabel());
                    }
                }).width(200).build();
                this.layout.addChild(this.sigmaPauseButton);
            }
        }
        this.layout.addChild(backButton);
        this.layout.arrangeElements();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());
    }

    @Override
    public Component getNarrationMessage() {
        return CommonComponents.joinForNarration(this.title, this.details.reason());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.sigmaReconnectAtNanos == Long.MIN_VALUE || this.sigmaReconnectPaused) {
            return;
        }
        AutoReconnect autoReconnect = Modules.enabled(AutoReconnect.class);
        if (autoReconnect == null || !autoReconnect.canAttemptRetry() || AutoReconnect.lastTarget() == null) {
            this.sigmaReconnectAtNanos = Long.MIN_VALUE;
            if (this.sigmaReconnectButton != null) {
                this.sigmaReconnectButton.setMessage(Component.literal("Reconnect"));
            }
            return;
        }
        if (System.nanoTime() >= this.sigmaReconnectAtNanos) {
            this.sigmaReconnectNow();
            return;
        }
        if (this.sigmaReconnectButton != null) {
            this.sigmaReconnectButton.setMessage(this.sigmaReconnectLabel());
        }
    }

    private Component sigmaReconnectLabel() {
        if (this.sigmaReconnectAtNanos == Long.MIN_VALUE || this.sigmaReconnectPaused) {
            return Component.literal("Reconnect");
        }
        double remaining = Math.max(0.0, (this.sigmaReconnectAtNanos - System.nanoTime()) / 1_000_000_000.0);
        return Component.literal(String.format(Locale.ROOT, "Reconnect (%.1fs)", remaining));
    }

    private void sigmaReconnectNow() {
        AutoReconnect.Target target = AutoReconnect.lastTarget();
        if (target == null) {
            return;
        }
        AutoReconnect autoReconnect = Modules.enabled(AutoReconnect.class);
        if (autoReconnect != null) {
            autoReconnect.recordAttempt();
        }
        this.sigmaReconnectAtNanos = Long.MIN_VALUE;
        ConnectScreen.startConnecting(this.parent, this.minecraft, target.address(), target.data(), false, null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}