package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.TextSetting;
import com.mentalfrostbyte.jello.util.game.WindowTitle;
import com.mentalfrostbyte.jello.util.text.TextTemplate;
import com.mentalfrostbyte.jello.util.time.Timer;
import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

/**
 * Writes the client's own text into the game window's title bar.
 *
 * <p>There is one mechanism underneath: a template with {@code {placeholder}} markers. The presets are
 * nothing more than templates that ship with the client, so a user who never wants to see a placeholder
 * picks one from a list, and a user who wants something else writes it out in full. Adding a new piece of
 * information means adding a placeholder, not another switch.</p>
 *
 * <p>The title is rebuilt a couple of times a second but only handed to the window when the text actually
 * changed, so a title with a clock in it costs one call a minute rather than one a frame.</p>
 */
public class CustomTitle extends Module {

    /** Long enough that {@code {fps}} and {@code {time}} keep up, short enough to cost nothing. */
    private static final long REFRESH_INTERVAL = 500L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String DETAILED_TEMPLATE =
            "{client_name} {client_version}  \u2502  MC {mc_version}  \u2502  Via {vfp_target}  \u2502  {time}";

    private final EnumSetting<Preset> preset = this.register(new EnumSetting<>(
            "Preset", "Which title to show. Custom uses the template below.", Preset.SIGMA));

    private final TextSetting template = this.register(new TextSetting(
            "Template", "Any text, with {placeholder} markers filled in.", DETAILED_TEMPLATE));

    private final Map<String, Supplier<String>> placeholders = new LinkedHashMap<>();

    private final Timer refreshTimer = new Timer();

    /** The last text handed to the window, so an unchanged title is not reapplied. */
    private String applied = "";

    public CustomTitle() {
        super(ModuleCategory.MISC, "CustomTitle", "Shows client, version and time information in the window title");
        this.template.visibleWhen(() -> this.preset.is(Preset.CUSTOM));
        this.preset.onChange(this::apply);
        this.template.onChange(this::apply);

        this.placeholders.put("client_name", () -> Client.NAME);
        this.placeholders.put("client_version", () -> Client.FULL_VERSION);
        this.placeholders.put("mc_version", () -> SharedConstants.getCurrentVersion().name());
        this.placeholders.put("vfp_version", CustomTitle::viaFabricPlusVersion);
        this.placeholders.put("vfp_target", () -> ProtocolTranslator.getTargetVersion().getName());
        this.placeholders.put("time", () -> LocalDateTime.now().format(TIME));
        this.placeholders.put("date", () -> LocalDateTime.now().format(DATE));
        this.placeholders.put("fps", () -> String.valueOf(mc.getFps()));
        this.placeholders.put("username", () -> mc.getUser().getName());
        this.placeholders.put("server", this::server);
    }

    /** Every placeholder name this module understands, for the interface to offer. */
    public Iterable<String> getPlaceholderNames() {
        return this.placeholders.keySet();
    }

    /** The title as it stands right now. */
    public String render() {
        return TextTemplate.render(this.templateInUse(), name -> {
            Supplier<String> value = this.placeholders.get(name);
            return value == null ? null : value.get();
        });
    }

    @Override
    protected void onEnable() {
        WindowTitle.provide(this::render);
        this.applied = this.render();
        WindowTitle.refresh();
    }

    @Override
    protected void onDisable() {
        WindowTitle.clear();
        this.applied = "";
        WindowTitle.refresh();
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (event.isPre() && this.refreshTimer.hasElapsed(REFRESH_INTERVAL, true)) {
            this.apply();
        }
    }

    private void apply() {
        if (!this.isEnabled()) {
            return;
        }

        String title = this.render();
        if (!title.equals(this.applied)) {
            this.applied = title;
            WindowTitle.refresh();
        }
    }

    private String templateInUse() {
        String builtIn = this.preset.get().getTemplate();
        if (builtIn != null) {
            return builtIn;
        }

        String custom = this.template.get();
        return custom.isBlank() ? Preset.SIGMA.getTemplate() : custom;
    }

    private String server() {
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            return server.ip;
        }

        IntegratedServer singleplayer = mc.getSingleplayerServer();
        return singleplayer != null ? singleplayer.getWorldData().getLevelName() : "Main Menu";
    }

    private static String viaFabricPlusVersion() {
        try {
            return ViaFabricPlus.getImpl().getVersion();
        } catch (IllegalStateException notLoadedYet) {
            return "?";
        }
    }

    /** A title to pick from a list. {@link #CUSTOM} is the one the user writes themselves. */
    public enum Preset {
        SIGMA("{client_name} {client_version}"),
        DETAILED(DETAILED_TEMPLATE),
        VANILLA("Minecraft {mc_version}"),
        CUSTOM(null);

        private final @Nullable String template;

        Preset(final @Nullable String template) {
            this.template = template;
        }

        /** The template this preset stands for, or null for {@link #CUSTOM}, which has no fixed one. */
        public @Nullable String getTemplate() {
            return this.template;
        }
    }
}
