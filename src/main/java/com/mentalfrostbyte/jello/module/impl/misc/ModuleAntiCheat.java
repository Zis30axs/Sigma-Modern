package com.mentalfrostbyte.jello.module.impl.misc;

import com.mentalfrostbyte.jello.anticheat.alert.Suspects;
import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.Detector;
import com.mentalfrostbyte.jello.anticheat.client.ClientObserver;
import com.mentalfrostbyte.jello.anticheat.client.ClientWorldProbe;
import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.util.text.ChatUtil;
import com.mojang.logging.LogUtils;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Watches the other players on the server and says so when one moves in a way no unmodified client can.
 *
 * <p>This is Grim's idea - judge movement against what the game's rules allow, keep a violation level per
 * check, announce when it climbs - moved to the client, which can only look on: it sees where a player was
 * about every second tick, rounded, not the input that moved them. So it never fits a single report; it
 * compares whole windows of movement against a ceiling worked out from the vanilla rules, and it treats every
 * moment it cannot explain (a teleport, a push, liquid, an elytra) as no evidence at all.</p>
 *
 * <p>Everything here is advisory and local. Nothing is sent to the server and nobody is kicked or reported;
 * a server can also make a player look like a cheater to this module, so a flag is a reason to look, not a
 * verdict. The reasoning and the mapping to Grim's checks is in {@code GRIM_PORTING.md}.</p>
 *
 * <p>The module holds the switches and the numbers. The packet handlers in {@code ClientPacketListener} feed
 * {@link #observer()} through marked hooks that ask {@code Modules.enabled} for this module, and the name tag
 * and the {@code SuspectList} module read {@link #suspects()}.</p>
 */
public class ModuleAntiCheat extends Module {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How much the checks give the benefit of the doubt. */
    public enum Sensitivity {
        /** Fewest false alarms; only blatant cheating gets through the ceiling. */
        LENIENT(1.30, 30, 3),
        BALANCED(1.15, 20, 2),
        /** Tightest ceiling and a single offending window is enough; expect the odd false alarm. */
        STRICT(1.05, 12, 1);

        private final double toleranceScale;
        private final int burstTicks;
        private final int strikesRequired;

        Sensitivity(final double toleranceScale, final int burstTicks, final int strikesRequired) {
            this.toleranceScale = toleranceScale;
            this.burstTicks = burstTicks;
            this.strikesRequired = strikesRequired;
        }
    }

    private static final double QUANT_MODERN = 1.0 / 4096.0;
    /** Protocol 1.8 encodes entity positions in 1/32 of a block; ViaVersion only converts the units. */
    private static final double QUANT_LEGACY = 1.0 / 32.0;

    private final EnumSetting<Sensitivity> sensitivity = this.register(new EnumSetting<>("Sensitivity",
            "How much benefit of the doubt the checks give. Lenient misses subtle cheating, Strict false-alarms more.",
            Sensitivity.BALANCED));

    private final BooleanSetting speed = this.register(new BooleanSetting("Speed",
            "Flags sustained horizontal speed above what a sprint-hopper can reach.", true));

    private final BooleanSetting flight = this.register(new BooleanSetting("Flight",
            "Flags jumping far too high, and hovering in the air without falling.", true));

    private final BooleanSetting groundSpoof = this.register(new BooleanSetting("GroundSpoof",
            "Flags a forged on-ground flag: claimed with nothing underfoot, or denied while standing on solid ground.", true));

    private final BooleanSetting noSlow = this.register(new BooleanSetting("NoSlow",
            "Flags moving at full speed while eating, drinking, drawing a bow or blocking.", true));

    private final NumberSetting alertLevel = this.register(new NumberSetting("Alert Level",
            "How high a check's violation level must get before the player is announced. Each flag adds about 1; clean play drains it.",
            3.0F, 1.0F, 20.0F, 0.5F));

    private final BooleanSetting notify = this.register(new BooleanSetting("Notify",
            "Prints a chat line when a player is flagged. Off, flags are still logged and listed.", true));

    private final BooleanSetting nameTag = this.register(new BooleanSetting("NameTag",
            "Adds a red violation level to the name tag of players who have reached the alert level.", true));

    private final Suspects suspects = new Suspects();
    private final ObservedPlayers players = new ObservedPlayers(new Detector(new Announcer()), new ClientWorldProbe());
    private final ClientObserver observer = new ClientObserver(this.players, this::checkSettings);

    public ModuleAntiCheat() {
        super(ModuleCategory.MISC, "AntiCheat", "Watches other players and flags movement no unmodified client can produce.");
    }

    /** The bridge the packet handlers feed. */
    public ClientObserver observer() {
        return this.observer;
    }

    /** Everyone flagged so far in this world. */
    public Suspects suspects() {
        return this.suspects;
    }

    @Override
    protected void onEnable() {
        this.reset();
    }

    @Override
    protected void onDisable() {
        this.reset();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.reset();
    }

    private void reset() {
        this.observer.clear();
        this.suspects.clear();
    }

    /** Forgets one suspect: their listing, and the levels the checks were holding for them. */
    public void forget(final UUID uuid) {
        this.suspects.remove(uuid);
        this.players.clearLevels(uuid);
    }

    /** Forgets every suspect. */
    public void forgetAll() {
        this.suspects.clear();
        this.players.clearLevels();
    }

    /**
     * The name tag for {@code uuid}: the name as given, with a red violation level after it once the player has
     * reached the alert level and the tag is switched on.
     */
    public Component tagged(final UUID uuid, final Component name) {
        if (!this.nameTag.get()) {
            return name;
        }

        double level = this.suspects.totalLevel(uuid);
        if (level < this.alertLevel.get()) {
            return name;
        }

        return name.copy().append(Component.literal(String.format(Locale.ROOT, " ⚠ VL %.0f", level)).withStyle(ChatFormatting.RED));
    }

    private CheckSettings checkSettings() {
        Sensitivity current = this.sensitivity.get();
        double quant = ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8) ? QUANT_LEGACY : QUANT_MODERN;
        return new CheckSettings(this.speed.get(), this.flight.get(), this.groundSpoof.get(), this.noSlow.get(),
                current.toleranceScale, current.burstTicks, current.strikesRequired, this.alertLevel.get(), quant);
    }

    /** Where the detector's verdicts go: the suspect list, the log, the chat. */
    private final class Announcer implements Detector.AlertSink {

        @Override
        public void levelChanged(final TrackedPlayer player, final String check, final double level) {
            ModuleAntiCheat.this.suspects.update(player.uuid(), player.name(), check, level);
        }

        @Override
        public void alert(final TrackedPlayer player, final String check, final double level, final String detail) {
            ModuleAntiCheat.this.suspects.alerted(player.uuid(), check, detail, System.nanoTime());
            LOGGER.info("[Sigma/AntiCheat] {} failed {} (x{}): {}", player.name(), check,
                    String.format(Locale.ROOT, "%.1f", level), detail);

            if (ModuleAntiCheat.this.notify.get()) {
                String line = String.format(Locale.ROOT, "§b[AntiCheat] §f%s §7failed §f%s §7(x%.1f) §8%s",
                        player.name(), check, level, detail);
                Minecraft client = mc;
                if (client.isSameThread()) {
                    ChatUtil.print(line);
                } else {
                    client.execute(() -> ChatUtil.print(line));
                }
            }
        }
    }
}
