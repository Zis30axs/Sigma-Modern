package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.brigadier.suggestion.Suggestion;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * The ModernChat module's drawing: the input as a rounded ice-glass bar that unfurls from its left end when chat
 * opens and folds back when it closes, and the messages on a rounded glass panel that grows up to the full chat
 * height with it, all set in Anthropic Serif at the module's font size.
 *
 * <p>Vanilla still owns the chat's behavior - which lines exist, scrolling, history, drafts, command parsing,
 * click and hover events. It lays out through {@link ModernChatFont}, whose advances are the ones this class
 * draws with, so wrapping, the caret, the suggestion list and click targets match what is on screen. Every
 * width here comes from the {@code ModernChatFont} the text was laid out in, never from a bare scale, so a
 * drawn run can't disagree with its measurement. The marked hooks in {@code ChatComponent}, {@code ChatScreen},
 * {@code EditBox} and {@code CommandSuggestions} hand their values here while the module is on.</p>
 *
 * <p>The same {@link #layout} places lines for drawing ({@link #painter}) and for clicks ({@link ClickFinder}),
 * so a click lands on exactly what was drawn; only drawing advances the animations.</p>
 */
public final class ModernChat {
    /** Line pitch at the default size, before vanilla's line-spacing option: room for the serif's descenders. */
    public static final int LINE_HEIGHT = 12;

    private static final int BAR_MIN_HEIGHT = 16;
    private static final int BAR_MARGIN = 4;
    private static final int BAR_BOTTOM = 3;
    /** Where the text starts inside the bar, past the chat/command glyph. */
    private static final int BAR_TEXT_INSET = 21;
    /** Kept free at the bar's right end for the length counter. */
    private static final int BAR_COUNTER_SPACE = 38;
    private static final int PANEL_PAD_X = 7;
    private static final int PANEL_PAD_Y = 4;
    private static final int PANEL_SCROLLBAR_SPACE = 5;
    private static final float OPEN_SECONDS = 0.32F;
    private static final float CLOSE_SECONDS = 0.22F;
    private static final int SURFACE = 0x0A1824;
    private static final int RIM = 0xBFE8FF;

    private static final ModernFontRenderer[] FACES = new ModernFontRenderer[4];
    private static @Nullable ModernChatFont font;
    private static @Nullable ModernChatFont inputFont;
    private static @Nullable ModernChatFont popupFont;

    // Render-thread animation state, advanced once per frame by whichever of the bar or the panel draws first.
    private static @Nullable GuiGraphicsExtractor frame;
    private static long lastFrameNanos;
    private static float dt;
    private static float openProgress;
    private static float open;
    private static float hudHeight;
    private static boolean snapHud;
    private static float slide;
    private static GuiMessage.@Nullable Line newest;
    private static float caretX = Float.NaN;
    private static long caretMovedNanos;
    private static int lastCursor = -1;
    private static String lastValue = "";
    private static float command;
    private static String closingValue = "";
    private static int closingMaxLength = 256;
    // Where lines were revealed on the last drawn frame, in screen space: clicks outside it miss.
    private static float revealTop, revealBottom;

    private ModernChat() {}

    // ---------------------------------------------------------------------------------------------------------
    // The module's settings
    // ---------------------------------------------------------------------------------------------------------

    private static com.mentalfrostbyte.jello.module.impl.gui.@Nullable ModernChat settings() {
        return Modules.enabled(com.mentalfrostbyte.jello.module.impl.gui.ModernChat.class);
    }

    /** True while the ModernChat module is on; every hook asks this. */
    public static boolean active() {
        return settings() != null;
    }

    /**
     * What {@code ChatComponent}'s lines have to be wrapped for: 0 while vanilla draws the chat, otherwise the
     * text scale. A change - the module toggled, the font size moved - re-wraps them.
     */
    public static float wrapScale() {
        var settings = settings();
        return settings == null ? 0F : settings.getFontSize() / ModernFontRenderer.SIZE;
    }

    /** How much larger than Modern's 11px text the chat is set. */
    static float textScale() {
        var settings = settings();
        return settings == null ? 1F : settings.getFontSize() / ModernFontRenderer.SIZE;
    }

    private static int lineBox(float scale) {
        return Math.round(LINE_HEIGHT * scale);
    }

    /** The chat's line pitch with vanilla's line-spacing option applied; {@code ChatComponent.getLineHeight} in Modern. */
    public static int lineHeight(double spacing) {
        return (int)(lineBox(textScale()) * (spacing + 1.0));
    }

    private static int barHeight() {
        return Math.max(BAR_MIN_HEIGHT, lineBox(textScale()) + 4);
    }

    /** How far above the bottom of the screen the command suggestion list sits: just clear of the input bar. */
    public static int suggestionsAnchor() {
        return BAR_BOTTOM + barHeight() + 5;
    }

    /** The corner radius for a shape designed with {@code designed}; the setting can only round it less. */
    private static int radius(int designed) {
        var settings = settings();
        return settings == null ? designed : Math.min(designed, settings.getCornerRadius());
    }

    private static boolean animated() {
        var settings = settings();
        return settings == null || settings.isAnimated();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fonts
    // ---------------------------------------------------------------------------------------------------------

    /** The metrics every chat line is wrapped and hit-tested in, at the module's font size. */
    public static ModernChatFont font() {
        Font vanilla = Minecraft.getInstance().font;
        if (font == null || font.vanilla() != vanilla) font = new ModernChatFont(vanilla, ModernChat::textScale);
        return font;
    }

    /** The input box's metrics: like {@link #font()}, over vanilla's font that filters confusable glyphs. */
    public static ModernChatFont inputFont() {
        Font vanilla = Minecraft.getInstance().fontFilterFishy;
        if (inputFont == null || inputFont.vanilla() != vanilla) inputFont = new ModernChatFont(vanilla, ModernChat::textScale);
        return inputFont;
    }

    /**
     * The command suggestion list's metrics, always at Modern's own 11px: its rows keep vanilla's fixed 12px pitch
     * and click math, which a larger font wouldn't fit. Its x still comes from the (scaled) input box.
     */
    public static ModernChatFont popupFont() {
        Font vanilla = Minecraft.getInstance().font;
        if (popupFont == null || popupFont.vanilla() != vanilla) popupFont = new ModernChatFont(vanilla, () -> 1.0);
        return popupFont;
    }

    static ModernFontRenderer face(boolean bold, boolean italic) {
        int index = (bold ? 1 : 0) | (italic ? 2 : 0);
        ModernFontRenderer face = FACES[index];
        if (face == null) FACES[index] = face = new ModernFontRenderer(ModernFontRenderer.SERIF_TEXT, bold, italic, ModernFontRenderer.PLAIN);
        return face;
    }

    private static void tick(GuiGraphicsExtractor g) {
        if (frame == g) return;
        frame = g;
        long now = System.nanoTime();
        float gap = lastFrameNanos == 0L ? Float.MAX_VALUE : (now - lastFrameNanos) / 1_000_000_000F;
        dt = Math.min(0.1F, gap);
        lastFrameNanos = now;
        boolean focused = Minecraft.getInstance().gui.screen() instanceof ChatScreen;
        if (gap > 0.25F) {
            // Nothing drew the chat for a while (left the world with chat open, F1, the module off, a long pause):
            // drop what is stale - no collapsing bar or leftover panel on rejoin - and start this frame's animation
            // from rest, so opening the chat with the HUD hidden still unfurls it.
            dt = 0F;
            if (!focused) openProgress = 0F;
            slide = 0F;
            snapHud = true;
            caretX = Float.NaN;
        }
        var settings = settings();
        if (settings != null && !settings.isAnimated()) {
            openProgress = focused ? 1F : 0F;
            slide = 0F;
        } else {
            if (settings != null) dt *= settings.getAnimationSpeed();
            openProgress = focused ? Math.min(1F, openProgress + dt / OPEN_SECONDS) : Math.max(0F, openProgress - dt / CLOSE_SECONDS);
        }
        open = ModernStyle.easeOut(openProgress);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The input bar
    // ---------------------------------------------------------------------------------------------------------

    /** Places the chat's input box inside the bar; {@code ChatScreen.init} while the module is on. */
    public static void placeInput(EditBox box, int screenWidth, int screenHeight) {
        int height = barHeight();
        box.setWidth(Math.max(20, screenWidth - 2 * BAR_MARGIN - BAR_TEXT_INSET - BAR_COUNTER_SPACE));
        box.setHeight(height);
        box.setX(BAR_MARGIN + BAR_TEXT_INSET);
        box.setY(screenHeight - BAR_BOTTOM - height);
    }

    private static int barWidth(int screenWidth, int height) {
        int full = screenWidth - 2 * BAR_MARGIN;
        return Math.round(height + (full - height) * open);
    }

    private static int barY(int screenHeight, int height) {
        return screenHeight - BAR_BOTTOM - height + Math.round((1F - open) * 5F);
    }

    /** How much of the bar's content shows: it follows the bar out once it has room. */
    private static float contentAlpha() {
        return Mth.clamp((open - 0.3F) / 0.6F, 0F, 1F);
    }

    /** Draws the bar behind the chat's input box; {@code ChatScreen.extractRenderState} while the module is on. */
    public static void inputBar(GuiGraphicsExtractor g, EditBox box) {
        tick(g);
        closingValue = box.getValue();
        closingMaxLength = box.getMaxLength();
        float target = closingValue.startsWith("/") ? 1F : 0F;
        command = animated() ? ModernStyle.smooth(command, target, dt, 16F) : target;
        bar(g, g.guiWidth(), g.guiHeight(), box.getValue().length(), box.getMaxLength());
    }

    private static void bar(GuiGraphicsExtractor g, int screenWidth, int screenHeight, int length, int maxLength) {
        float alpha = Math.min(1F, open * 1.8F);
        if (alpha <= 0.01F) return;
        int h = barHeight();
        int x = BAR_MARGIN, y = barY(screenHeight, h), w = barWidth(screenWidth, h), r = radius(h / 2);
        try (var fade = ModernStyle.alphaScope(alpha)) {
            ModernStyle.dropShadow(g, x, y + 2, w, h, r, 0.7F);
            ModernStyle.halo(g, x, y, w, h, r, ModernStyle.GLOW, 0.35F * open);
            ModernStyle.rounded(g, x, y, w, h, r, 0x66000000 | RIM);
            ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), 0xEB000000 | SURFACE);
            ModernStyle.fillGradient(g, x + r, y + 1, x + w - r, y + h / 2, 0x1CFFFFFF, 0x00FFFFFF);
            // The glyph at the left end: a speech bubble for messages, a prompt mark once the line is a command.
            float iconX = x + 5.5F, iconY = y + (h - 9) / 2F;
            ModernIcons.draw(g, ModernIcons.Icon.CHAT, iconX, iconY, 9F, ModernTypography.fade(ModernStyle.MUTED, 1F - command));
            if (command > 0.01F) {
                face(true, false).draw(g, "›", iconX + 2F, iconY - 1.5F, ModernTypography.fade(ModernStyle.ACCENT, command), false);
            }
            // How much of the server's limit is used, once it starts to matter (ViaFabricPlus lowers it on old servers).
            float content = contentAlpha();
            if (content > 0.01F && maxLength > 0 && length >= maxLength * 0.7F) {
                String counter = length + "/" + maxLength;
                int color = length >= maxLength ? 0xFFFF9C9C : ModernStyle.MUTED;
                ModernFontRenderer face = face(false, false);
                float cx = x + w - 9 - face.measure(counter);
                try (var inner = ModernStyle.alphaScope(content * 0.85F)) {
                    face.draw(g, counter, cx, y + (h - LINE_HEIGHT) / 2F + 1F, color, false);
                }
            }
        }
    }

    /**
     * Draws the chat's input text, caret and selection; {@code EditBox.extractWidgetRenderState} for a box laid
     * out in {@link ModernChatFont}. Mirrors vanilla's own drawing (the visible window starting at
     * {@code displayPos}, the suggestion shown past the end), only in Anthropic Serif and with a gliding caret.
     */
    public static void input(
        GuiGraphicsExtractor g, EditBox box, ModernChatFont metrics, String value, int displayPos, int cursorPos, int highlightPos,
        @Nullable String suggestion, boolean editable, EditBox.TextFormatter format, @Nullable IMEPreeditOverlay preedit
    ) {
        tick(g);
        int lineBox = lineBox(metrics.scale());
        int innerWidth = box.getInnerWidth();
        String displayed = metrics.plainSubstrByWidth(value.substring(displayPos), innerWidth);
        int relCursor = cursorPos - displayPos;
        boolean cursorOnScreen = relCursor >= 0 && relCursor <= displayed.length();
        float textX = box.getX();
        int textY = box.getY() + (box.getHeight() - lineBox) / 2 + 1;
        float caretTarget = cursorOnScreen ? textX + metrics.getSplitter().stringWidth(displayed.substring(0, relCursor))
            : relCursor > 0 ? textX + innerWidth : textX;

        long now = System.nanoTime();
        if (cursorPos != lastCursor || !value.equals(lastValue)) {
            caretMovedNanos = now;
            lastCursor = cursorPos;
            lastValue = value;
        }
        caretX = !animated() || Float.isNaN(caretX) || Math.abs(caretX - caretTarget) > 48F * metrics.scale()
            ? caretTarget : ModernStyle.smooth(caretX, caretTarget, dt, 30F);

        int barX = BAR_MARGIN, barW = barWidth(g.guiWidth(), box.getHeight());
        // The text rides the bar as it rises into place.
        int lift = barY(g.guiHeight(), box.getHeight()) - box.getY();
        g.pose().pushMatrix();
        g.pose().translate(0F, lift);
        g.enableScissor(barX + BAR_TEXT_INSET - 2, box.getY(), barX + barW - 6, box.getY() + box.getHeight());
        try (var fade = ModernStyle.alphaScope(contentAlpha())) {
            int relHighlight = Mth.clamp(highlightPos - displayPos, 0, displayed.length());
            if (relHighlight != relCursor && cursorOnScreen) {
                float a = textX + metrics.getSplitter().stringWidth(displayed.substring(0, Math.min(relCursor, relHighlight)));
                float b = textX + metrics.getSplitter().stringWidth(displayed.substring(0, Math.max(relCursor, relHighlight)));
                ModernStyle.rounded(g, Math.round(a) - 1, textY - 1, Math.max(1, Math.round(b - a) + 2), lineBox, radius(3), 0x662E9BD6);
            }

            int color = editable ? ModernStyle.TEXT : ModernStyle.MUTED;
            if (!displayed.isEmpty()) {
                drawStyled(g, format.format(displayed, displayPos), textX, textY, color, 1F, false, metrics);
            } else if (value.isEmpty()) {
                drawScaled(g, face(false, true), ModernText.t("Message, or / for a command", "发送消息，或以 / 开始输入命令"), textX, textY,
                    ModernTypography.fade(ModernStyle.MUTED, 0.6F), metrics.scale());
            }
            // Vanilla shows the suggested completion only when typing at the end of an unfilled line.
            boolean insert = cursorPos < value.length() || value.length() >= box.getMaxLength();
            if (!insert && suggestion != null) {
                drawStyled(g, FormattedCharSequence.forward(suggestion, Style.EMPTY), caretTarget + 1F, textY,
                    ModernTypography.fade(ModernStyle.MUTED, 0.75F), 1F, false, metrics);
            }

            if (box.isFocused()) {
                float since = (now - caretMovedNanos) / 1_000_000_000F;
                float blink = since < 0.6F ? 1F : 0.5F + 0.5F * (float)Math.cos((since - 0.6F) * Math.PI * 2 / 1.1F);
                g.pose().pushMatrix();
                g.pose().translate(caretX, textY);
                ModernStyle.fill(g, 0, 0, 1, lineBox - 1, ModernTypography.fade(ModernStyle.ACCENT, 0.25F + 0.75F * blink));
                g.pose().popMatrix();
            }
        } finally {
            g.disableScissor();
            g.pose().popMatrix();
        }

        if (box.isHovered()) g.requestCursor(editable ? CursorTypes.IBEAM : CursorTypes.NOT_ALLOWED);
        if (preedit != null) {
            preedit.updateInputPosition(Math.round(caretTarget), textY);
            g.setPreeditOverlay(preedit);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // The message panel
    // ---------------------------------------------------------------------------------------------------------

    /** The access that draws; {@code ChatComponent.extractRenderState} hands it to its layout while the module is on. */
    public static ChatComponent.ChatGraphicsAccess painter(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean foreground, boolean insertionCursor) {
        return new Painter(g, mouseX, mouseY, foreground, insertionCursor);
    }

    private static float timeAlpha(GuiMessage.Line line, int ticks) {
        double t = (ticks - line.addedTime()) / 200.0;
        t = Mth.clamp((1.0 - t) * 10.0, 0.0, 1.0);
        return (float)(t * t);
    }

    /**
     * Lays the chat out for drawing or for hit-testing, depending on {@code access}; replaces the body of
     * {@code ChatComponent}'s private {@code extractRenderState} while the module is on. {@code lines} is newest
     * first.
     *
     * <p>Unfocused, the panel holds only the lines still fading out after arriving (and under SigmaModern's HUD sits
     * above its keystroke display); focused, it holds a full page and rests on the input bar. Both its height and
     * its bottom edge follow the bar's open animation, so opening chat grows the panel upward as the bar unfurls and
     * closing it folds both away. New lines slide in from the bottom edge.</p>
     */
    public static void layout(
        ChatComponent.ChatGraphicsAccess access, List<GuiMessage.Line> lines, int scroll, boolean newMessageSinceScroll, int screenHeight, int ticks,
        ChatComponent.DisplayMode mode, Style queueStyle, Component restricted, Component restrictedWithHover
    ) {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        Painter painter = access instanceof Painter p ? p : null;
        if (painter != null) tick(painter.g);
        var settings = settings();
        boolean animated = settings == null || settings.isAnimated();

        ModernChatFont metrics = font();
        int lineBox = lineBox(metrics.scale());
        boolean foreground = mode.foreground;
        int entry = (int)(lineBox * (options.chatLineSpacing().get() + 1.0));
        int textOffset = (entry - lineBox) / 2 + 1;
        float scale = options.chatScale().get().floatValue();
        int maxWidth = Mth.ceil(ChatComponent.getWidth(options.chatWidth().get()) / scale);
        int focusedLines = ChatComponent.getHeight(options.chatHeightFocused().get()) / entry;
        int hudLines = ChatComponent.getHeight(options.chatHeightUnfocused().get()) / entry;
        float textOpacity = options.chatOpacity().get().floatValue() * 0.9F + 0.1F;
        float background = settings == null ? 0.5F : settings.getBackground();
        long queueSize = mc.gui.chatListener().queueSize();
        boolean restrictedPrompt = mode.showRestrictedPrompt;
        int available = Math.max(0, lines.size() - scroll);

        if (painter != null) {
            // Lines added since the last frame: everything shifts up by them, so start that far down and slide.
            GuiMessage.Line head = lines.isEmpty() ? null : lines.getFirst();
            if (head != newest) {
                int added = -1;
                if (newest != null && scroll == 0 && animated) {
                    for (int i = 0; i < lines.size() && i < 100; i++) {
                        if (lines.get(i) == newest) {
                            added = i;
                            break;
                        }
                    }
                }
                slide = added > 0 ? Math.min(slide + added * entry, focusedLines * entry) : 0F;
                newest = head;
            }
            slide = slide < 0.05F || !animated ? 0F : ModernStyle.smooth(slide, 0F, dt, 13F);
        }

        // What the unfocused chat shows: the newest lines until they fade, and the panel fades with the newest.
        int hudVisible = 0;
        float newestAlpha = 0F;
        for (int i = 0; i < Math.min(available, hudLines); i++) {
            float alpha = timeAlpha(lines.get(i + scroll), ticks);
            if (alpha > 1.0E-5F) {
                hudVisible = i + 1;
                newestAlpha = Math.max(newestAlpha, alpha);
            }
        }
        if (painter != null) {
            hudHeight = snapHud || !animated ? hudVisible * entry : ModernStyle.smooth(hudHeight, hudVisible * entry, dt, 11F);
            snapHud = false;
        }
        float o = open;
        int shown = Math.min(available, o > 0F || foreground ? focusedLines : hudLines);
        float fullHeight = shown * entry + (restrictedPrompt ? entry : 0);
        float linesHeight = hudHeight + (fullHeight - hudHeight) * o;
        float presence = newestAlpha + (1F - newestAlpha) * o;
        if (restrictedPrompt) presence = 1F;

        // Open, the panel rests on the input bar - unless health and hunger show (survival), which it stays above
        // like vanilla's chat does, or the bed's "leave" button sits there. Unfocused it sits where vanilla's does,
        // or above the keystroke display SigmaModern's HUD draws in that corner.
        boolean statusBars = mc.gameMode != null && mc.gameMode.canHurtPlayer();
        boolean inBed = mc.gui.screen() instanceof InBedChatScreen;
        float hudBottom = screenHeight - (ModernHud.isActive() ? 62F : 40F + PANEL_PAD_Y);
        float openBottom = screenHeight - (inBed ? 48F : statusBars ? 40F + PANEL_PAD_Y : BAR_BOTTOM + barHeight() + PANEL_PAD_Y + 5F);
        float bottom = hudBottom + (openBottom - hudBottom) * o;
        float queueRow = queueSize > 0L ? entry * scale : 0F;
        bottom -= queueRow;

        int panelX = BAR_MARGIN;
        int panelW = Math.round(maxWidth * scale) + 2 * PANEL_PAD_X + PANEL_SCROLLBAR_SPACE;
        float linesTop = bottom - linesHeight * scale;
        int panelY = Math.round(linesTop) - PANEL_PAD_Y;
        int panelBottom = Math.round(bottom + queueRow) + PANEL_PAD_Y;
        revealTop = linesTop - 1F;
        revealBottom = bottom + queueRow + PANEL_PAD_Y;

        if (painter != null) {
            if (!foreground && o > 0.01F) bar(painter.g, painter.g.guiWidth(), screenHeight, closingValue.length(), closingMaxLength);
            if (panelBottom - panelY > 2 * PANEL_PAD_Y && presence > 0.01F) {
                panel(painter.g, panelX, panelY, panelW, panelBottom - panelY, presence, o, background);
                if (foreground && lines.size() > shown && shown > 0) {
                    scrollbar(painter.g, panelX + panelW - PANEL_PAD_X + 1, linesTop, bottom, shown, lines.size(), scroll, newMessageSinceScroll, scale, entry);
                }
            } else if (queueSize == 0L && !restrictedPrompt) {
                return;
            }
            painter.beginLines(panelX, panelY + 1, panelX + panelW, panelBottom - 1, metrics, lineBox);
        }

        int chatBottom = Mth.floor(bottom / scale);
        float yShift = bottom / scale - chatBottom + slide;
        float left = (panelX + PANEL_PAD_X) / scale;
        access.updatePose(pose -> {
            pose.scale(scale, scale);
            pose.translate(left, yShift);
        });
        try {
            boolean hoveredOverMessage = false;
            for (int i = shown - 1; i >= 0; i--) {
                GuiMessage.Line line = lines.get(i + scroll);
                float faded = foreground ? 1F : timeAlpha(line, ticks);
                float alpha = faded + (1F - faded) * o;
                int entryBottom = chatBottom - i * entry;
                int entryTop = entryBottom - entry;
                // Lines still above the growing panel's top edge fade in as it reaches them.
                float lineTop = (entryTop + yShift) * scale;
                float reveal = Mth.clamp(1F + (lineTop - linesTop) / (entry * scale), 0F, 1F);
                alpha *= reveal;
                if (alpha <= 1.0E-5F) continue;
                int textTop = entryTop + textOffset;
                boolean hovered = access.handleMessage(textTop, alpha * textOpacity, line.content());
                hoveredOverMessage |= hovered;
                boolean forceIcon = false;
                if (line.endOfEntry()) {
                    forceIcon = hoveredOverMessage;
                    hoveredOverMessage = false;
                }
                GuiMessageTag tag = line.tag();
                if (tag != null) {
                    access.handleTag(-5, entryTop + 1, -3, entryBottom - 1, alpha * textOpacity, tag);
                    if (tag.icon() != null) {
                        access.handleTagIcon(line.getTagIconLeft(metrics), textTop + lineBox - 2, forceIcon, tag, tag.icon());
                    }
                }
            }

            if (queueSize > 0L) {
                Component queued = Component.translatable("chat.queue", queueSize).setStyle(queueStyle);
                access.handleMessage(chatBottom + textOffset, 0.55F * textOpacity, queued.getVisualOrderText());
            }
            if (restrictedPrompt) {
                int top = chatBottom - (shown + 1) * entry + textOffset;
                FormattedCharSequence prompt = metrics.width(restricted) > maxWidth
                    ? ComponentRenderUtils.clipText(restrictedWithHover, metrics, maxWidth)
                    : restricted.getVisualOrderText();
                access.handleMessage(top, textOpacity, prompt);
            }
        } finally {
            if (painter != null) painter.endLines();
        }
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, float presence, float focus, float background) {
        int r = Math.min(radius(10), h / 2);
        // Unfocused it stays light over the world; open, it firms up into a card. The Background setting scales both.
        float surface = Math.min(0.9F, background * (1.15F + 0.6F * focus));
        try (var fade = ModernStyle.alphaScope(presence)) {
            if (focus > 0.01F) ModernStyle.dropShadow(g, x, y + 4, w, h, r, focus * 0.9F);
            ModernStyle.rounded(g, x, y, w, h, r, Math.round(0x30 + 0x28 * focus) << 24 | RIM);
            ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), Math.round(255 * surface) << 24 | SURFACE);
            ModernStyle.fillGradient(g, x + r, y + 1, x + w - r, y + 1 + Math.min(20, h / 3), Math.round(0x16 * focus + 0x08) << 24 | 0xFFFFFF, 0x00FFFFFF);
        }
    }

    private static void scrollbar(GuiGraphicsExtractor g, int x, float top, float bottom, int shown, int total, int scroll, boolean fresh, float scale, int entry) {
        float track = shown * entry * scale;
        float thumb = Math.max(6F, track * shown / total);
        float thumbBottom = bottom - (track - thumb) * scroll / Math.max(1, total - shown);
        ModernStyle.rounded(g, x, Math.round(top), 2, Math.round(bottom - top), 1, 0x14FFFFFF);
        int color = fresh ? ModernStyle.GLOW : 0x8CDDF3FF;
        if (fresh) ModernStyle.halo(g, x, Math.round(thumbBottom - thumb), 2, Math.round(thumb), 1, ModernStyle.GLOW, 0.5F);
        ModernStyle.rounded(g, x, Math.round(thumbBottom - thumb), 2, Math.round(thumb), 1, color);
    }

    /** Draws chat lines and reports what the mouse is over, like vanilla's focused and background accesses. */
    private static final class Painter implements ChatComponent.ChatGraphicsAccess {
        final GuiGraphicsExtractor g;
        private final int mouseX;
        private final int mouseY;
        private final boolean foreground;
        private final boolean insertionCursor;
        private final Vector2f localMouse = new Vector2f();
        private boolean scissored;
        private boolean shadow;
        private ModernChatFont metrics = font();
        private int lineBox = LINE_HEIGHT;

        Painter(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean foreground, boolean insertionCursor) {
            this.g = g;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.foreground = foreground;
            this.insertionCursor = insertionCursor;
        }

        void beginLines(int x0, int y0, int x1, int y1, ModernChatFont metrics, int lineBox) {
            this.g.enableScissor(x0, y0, x1, y1);
            this.scissored = true;
            this.metrics = metrics;
            this.lineBox = lineBox;
            var settings = settings();
            this.shadow = settings != null && settings.hasTextShadow();
        }

        void endLines() {
            if (this.scissored) this.g.disableScissor();
            this.scissored = false;
        }

        private boolean mouseInReveal() {
            return this.mouseY >= revealTop && this.mouseY < revealBottom;
        }

        @Override
        public void updatePose(Consumer<Matrix3x2f> updater) {
            updater.accept(this.g.pose());
            this.g.pose().invert(new Matrix3x2f()).transformPosition(this.mouseX, this.mouseY, this.localMouse);
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int color) {
            ModernStyle.fill(this.g, x0, y0, x1, y1, color);
        }

        @Override
        public boolean handleMessage(int textTop, float opacity, FormattedCharSequence message) {
            drawStyled(this.g, message, 0F, textTop, ModernStyle.TEXT, opacity, this.shadow, this.metrics);
            if (!this.foreground || !mouseInReveal()) return false;
            if (this.localMouse.y < textTop - 1 || this.localMouse.y >= textTop + this.lineBox - 1) return false;
            Style style = styleAt(message, this.localMouse.x, this.metrics);
            if (style == null) return false;
            this.g.hoveredTextStyle(style, true);
            if (this.insertionCursor && style.getInsertion() != null) this.g.requestCursor(CursorTypes.POINTING_HAND);
            return true;
        }

        @Override
        public void handleTag(int x0, int y0, int x1, int y1, float opacity, GuiMessageTag tag) {
            // A quiet mark, not a caret: vanilla's indicator colors at half strength, inset from the line's ends.
            ModernStyle.rounded(this.g, x0, y0 + 2, x1 - x0, y1 - y0 - 4, 1, ARGB.color(opacity * 0.5F, tag.indicatorColor()));
            if (this.foreground && mouseInReveal() && inside(x0 - 1, y0, x1 + 1, y1)) tooltip(tag);
        }

        @Override
        public void handleTagIcon(int left, int bottom, boolean forceVisible, GuiMessageTag tag, GuiMessageTag.Icon icon) {
            int top = bottom - icon.height - 1;
            boolean over = this.foreground && mouseInReveal() && inside(left, top, left + icon.width, bottom);
            if (over) tooltip(tag);
            if (forceVisible || over) icon.extractRenderState(this.g, left, top);
        }

        private boolean inside(float x0, float y0, float x1, float y1) {
            return this.localMouse.x >= x0 && this.localMouse.x < x1 && this.localMouse.y >= y0 && this.localMouse.y < y1;
        }

        private void tooltip(GuiMessageTag tag) {
            if (tag.text() == null) return;
            Font vanilla = Minecraft.getInstance().font;
            this.g.setTooltipForNextFrame(vanilla, vanilla.split(tag.text(), 210), this.mouseX, this.mouseY);
        }
    }

    /**
     * Finds the clickable style under a point, over the same {@link #layout} the lines were drawn with -
     * {@code ChatScreen.mouseClicked} uses it in place of vanilla's {@code ClickableStyleFinder}, which measures
     * glyphs in vanilla's font.
     */
    public static final class ClickFinder implements ActiveTextCollector {
        private final int testX;
        private final int testY;
        private final boolean includeInsertions;
        private ActiveTextCollector.Parameters parameters = new ActiveTextCollector.Parameters(new Matrix3x2f());
        private @Nullable Style result;

        public ClickFinder(int testX, int testY, boolean includeInsertions) {
            this.testX = testX;
            this.testY = testY;
            this.includeInsertions = includeInsertions;
        }

        public @Nullable Style result() {
            return this.result;
        }

        @Override
        public ActiveTextCollector.Parameters defaultParameters() {
            return this.parameters;
        }

        @Override
        public void defaultParameters(ActiveTextCollector.Parameters parameters) {
            this.parameters = parameters;
        }

        @Override
        public void accept(TextAlignment alignment, int anchorX, int y, ActiveTextCollector.Parameters parameters, FormattedCharSequence text) {
            if (this.testY < revealTop || this.testY >= revealBottom) return;
            ModernChatFont metrics = font();
            Vector2f local = parameters.pose().invert(new Matrix3x2f()).transformPosition(this.testX, this.testY, new Vector2f());
            if (local.y < y - 1 || local.y >= y + lineBox(metrics.scale()) - 1) return;
            int left = alignment.calculateLeft(anchorX, metrics, text);
            Style style = styleAt(text, local.x - left, metrics);
            if (style != null && (style.getClickEvent() != null || this.includeInsertions && style.getInsertion() != null)) {
                this.result = style;
            }
        }

        @Override
        public void acceptScrolling(Component message, int centerX, int left, int right, int top, int bottom, ActiveTextCollector.Parameters parameters) {
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Styled text in Anthropic Serif
    // ---------------------------------------------------------------------------------------------------------

    /** The style of the character at {@code x} along {@code text} as {@code metrics} lays it out, or null past either end. */
    static @Nullable Style styleAt(FormattedCharSequence text, float x, ModernChatFont metrics) {
        if (x < 0F) return null;
        float[] position = {0F};
        Style[] found = {null};
        text.accept((index, style, codepoint) -> {
            float next = position[0] + metrics.advance(codepoint, style);
            if (x < next) {
                found[0] = style;
                return false;
            }
            position[0] = next;
            return true;
        });
        return found[0];
    }

    /** Plain text in {@code face}, at {@code scale} times Modern's 11px. */
    private static void drawScaled(GuiGraphicsExtractor g, ModernFontRenderer face, String text, float x, float y, int color, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        face.draw(g, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /**
     * Draws {@code text} as {@code metrics} lays it out, in runs of one style (and one font) each: serif runs
     * through Skia (bold and italic synthesized), the rest - resource-pack glyphs, other fonts, obfuscated text -
     * through vanilla, on the same baseline, all at the metrics' scale. Returns the end x.
     */
    static float drawStyled(GuiGraphicsExtractor g, FormattedCharSequence text, float x, float y, int defaultColor, float opacity, boolean shadow,
        ModernChatFont metrics) {
        Runs runs = new Runs(g, x, y, defaultColor, opacity, shadow, metrics);
        text.accept(runs);
        runs.flush();
        return runs.x;
    }

    private static final class Runs implements FormattedCharSink {
        private final GuiGraphicsExtractor g;
        private final float y;
        private final int defaultColor;
        private final float opacity;
        private final boolean shadow;
        private final ModernChatFont metrics;
        private final float scale;
        private final StringBuilder text = new StringBuilder();
        private Style style = Style.EMPTY;
        private boolean serif;
        private boolean covered;
        private float start;
        float x;

        Runs(GuiGraphicsExtractor g, float x, float y, int defaultColor, float opacity, boolean shadow, ModernChatFont metrics) {
            this.g = g;
            this.x = x;
            this.y = y;
            this.defaultColor = defaultColor;
            this.opacity = opacity;
            this.shadow = shadow;
            this.metrics = metrics;
            this.scale = metrics.scale();
        }

        @Override
        public boolean accept(int index, Style style, int codepoint) {
            boolean serif = ModernChatFont.drawsSerif(codepoint, style);
            // Split where Anthropic Serif hands over to a system font, so every run shapes as its characters measure.
            boolean covered = serif && face(false, false).covers(codepoint);
            if (!this.text.isEmpty() && (serif != this.serif || covered != this.covered || !style.equals(this.style))) flush();
            if (this.text.isEmpty()) {
                this.style = style;
                this.serif = serif;
                this.covered = covered;
                this.start = this.x;
            }
            this.text.appendCodePoint(codepoint);
            this.x += this.metrics.advance(codepoint, style);
            return true;
        }

        void flush() {
            if (this.text.isEmpty()) return;
            String run = this.text.toString();
            this.text.setLength(0);
            TextColor styleColor = this.style.getColor();
            int alpha = Math.round((this.defaultColor >>> 24) * Mth.clamp(this.opacity, 0F, 1F));
            // Vanilla doesn't draw text this faint either.
            if (alpha < 4) return;
            int color = alpha << 24 | (styleColor != null ? styleColor.getValue() : this.defaultColor) & 0xFFFFFF;
            this.g.pose().pushMatrix();
            if (this.serif) {
                this.g.pose().translate(this.start, this.y);
                this.g.pose().scale(this.scale, this.scale);
                face(this.style.isBold(), this.style.isItalic()).draw(this.g, run, 0, 0, color, this.shadow);
            } else {
                // Vanilla's baseline sits 7px down its line, the serif's 9px.
                this.g.pose().translate(this.start, this.y + 2F * this.scale);
                this.g.pose().scale(this.scale, this.scale);
                this.g.text(this.metrics.vanilla(), FormattedCharSequence.forward(run, this.style), 0, 0, ModernStyle.a(color), this.shadow);
            }
            this.g.pose().popMatrix();
            if (this.serif && (this.style.isUnderlined() || this.style.isStrikethrough())) {
                this.g.pose().pushMatrix();
                this.g.pose().translate(this.start, this.y);
                int width = Math.max(1, Math.round(this.x - this.start));
                int underline = Math.round(10F * this.scale), strike = Math.round(5F * this.scale);
                if (this.style.isUnderlined()) ModernStyle.fill(this.g, 0, underline, width, underline + 1, color);
                if (this.style.isStrikethrough()) ModernStyle.fill(this.g, 0, strike, width, strike + 1, color);
                this.g.pose().popMatrix();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Command suggestions
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The glass behind the suggestion list; {@code CommandSuggestions.SuggestionsList} while the module is on. The
     * rows themselves keep vanilla's 12px pitch and hit rectangle, so they stay at Modern's own text size.
     */
    public static void suggestionsBackground(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean more, boolean earlier) {
        int px = x - 4, py = y - 3, pw = w + 8, ph = h + 6, r = radius(7);
        ModernStyle.dropShadow(g, px, py + 3, pw, ph, r, 0.9F);
        ModernStyle.rounded(g, px, py, pw, ph, r, 0x59000000 | RIM);
        ModernStyle.rounded(g, px + 1, py + 1, pw - 2, ph - 2, Math.max(0, r - 1), 0xF2000000 | SURFACE);
        if (earlier) ModernIcons.draw(g, ModernIcons.Icon.CHEVRON_UP, px + pw / 2F - 3F, py - 6F, 6F, ModernStyle.MUTED);
        if (more) ModernIcons.draw(g, ModernIcons.Icon.CHEVRON_DOWN, px + pw / 2F - 3F, py + ph, 6F, ModernStyle.MUTED);
    }

    /** One suggestion row: the selected one on an ice pill with the accent bar Modern marks selections with. */
    public static void suggestion(GuiGraphicsExtractor g, Suggestion suggestion, int x, int y, int w, boolean selected) {
        if (selected) {
            ModernStyle.rounded(g, x - 2, y, w + 4, 12, radius(4), 0x3DCDEBFF);
            ModernStyle.rounded(g, x - 3, y + 3, 2, 6, 1, ModernStyle.GLOW);
        }
        drawStyled(g, FormattedCharSequence.forward(suggestion.getText(), Style.EMPTY), x + 1, y, selected ? ModernStyle.ACCENT : ModernStyle.MUTED,
            1F, false, popupFont());
    }

    /** The command usage hints above the input bar; {@code CommandSuggestions.extractUsage} while the module is on. */
    public static void usage(GuiGraphicsExtractor g, List<FormattedCharSequence> lines, int x, int width, int screenHeight) {
        if (lines.isEmpty()) return;
        int h = lines.size() * 12;
        int bottom = screenHeight - suggestionsAnchor() - 3;
        int top = bottom - h;
        int px = x - 5, py = top - 3, pw = width + 10, ph = h + 6, r = radius(7);
        ModernStyle.dropShadow(g, px, py + 3, pw, ph, r, 0.8F);
        ModernStyle.rounded(g, px, py, pw, ph, r, 0x4D000000 | RIM);
        ModernStyle.rounded(g, px + 1, py + 1, pw - 2, ph - 2, Math.max(0, r - 1), 0xEB000000 | SURFACE);
        // Vanilla stacks the first line lowest.
        for (int i = 0; i < lines.size(); i++) {
            drawStyled(g, lines.get(i), x, bottom - 12 * (i + 1), ModernStyle.TEXT, 1F, false, popupFont());
        }
    }
}
