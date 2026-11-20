package com.mentalfrostbyte.jello.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandManagerTest {

    private enum Mode { ALPHA, BETA }

    private static final class DummyModule extends Module {
        final BooleanSetting flag = this.register(new BooleanSetting("Flag", "A flag", false));
        final NumberSetting range = this.register(new NumberSetting("Alert Level", "A range", 3.0F, 1.0F, 10.0F, 0.5F));
        final EnumSetting<Mode> mode = this.register(new EnumSetting<>("Mode", "A mode", Mode.ALPHA));

        DummyModule() {
            super(ModuleCategory.MISC, "Dummy", "Test module");
        }
    }

    private record Env(CommandManager commands, ModuleManager modules, DummyModule dummy,
                       ModuleAntiCheat antiCheat, List<String> lines, int[] saves, CommandContext ctx) {
    }

    private static Env setup(final Path tempDir) {
        CommandManager commands = new CommandManager();
        commands.registerDefaults();
        ModuleManager modules = new ModuleManager();
        DummyModule dummy = new DummyModule();
        ModuleAntiCheat antiCheat = new ModuleAntiCheat();
        modules.register(dummy);
        modules.register(antiCheat);
        List<String> lines = new ArrayList<>();
        int[] saves = {0};
        CommandContext ctx = new CommandContext(modules, commands, tempDir.resolve("profiles"),
                lines::add, () -> saves[0]++);
        return new Env(commands, modules, dummy, antiCheat, lines, saves, ctx);
    }

    @Test
    void ordinaryChatPassesThroughWhileCommandsAreIntercepted(@TempDir final Path tempDir) {
        Env env = setup(tempDir);
        assertFalse(env.commands.interceptChat("hello world", env.ctx));
        assertFalse(env.commands.interceptChat(".", env.ctx));
        assertTrue(env.lines.isEmpty());

        assertTrue(env.commands.interceptChat(".unknowncmd", env.ctx));
        assertTrue(env.lines.get(0).contains("Unknown command"));
    }

    @Test
    void toggleCommandSwitchesModuleStateAndSaves(@TempDir final Path tempDir) {
        Env env = setup(tempDir);
        assertFalse(env.dummy.isEnabled());

        assertTrue(env.commands.interceptChat(".t dummy", env.ctx));
        assertTrue(env.dummy.isEnabled());
        assertEquals(1, env.saves[0]);

        assertTrue(env.commands.interceptChat(".toggle dummy off", env.ctx));
        assertFalse(env.dummy.isEnabled());
        assertEquals(2, env.saves[0]);
    }

    @Test
    void settingCommandEditsQuotedNamesNumbersEnumsAndResets(@TempDir final Path tempDir) {
        Env env = setup(tempDir);
        assertTrue(env.commands.interceptChat(".set dummy flag on", env.ctx));
        assertTrue(env.dummy.flag.get());

        assertTrue(env.commands.interceptChat(".setting dummy \"Alert Level\" 6.5", env.ctx));
        assertEquals(6.5F, env.dummy.range.get(), 1.0E-6F);

        assertTrue(env.commands.interceptChat(".set dummy mode beta", env.ctx));
        assertEquals(Mode.BETA, env.dummy.mode.get());

        assertTrue(env.commands.interceptChat(".set dummy mode reset", env.ctx));
        assertEquals(Mode.ALPHA, env.dummy.mode.get());
    }

    @Test
    void configCommandSavesListsLoadsAndDeletesProfiles(@TempDir final Path tempDir) {
        Env env = setup(tempDir);
        env.dummy.setEnabled(true);
        env.dummy.flag.set(true);
        assertTrue(env.commands.interceptChat(".config save grim", env.ctx));

        env.dummy.setEnabled(false);
        env.dummy.flag.set(false);
        assertTrue(env.commands.interceptChat(".config load grim", env.ctx));
        assertTrue(env.dummy.isEnabled());
        assertTrue(env.dummy.flag.get());

        env.lines.clear();
        assertTrue(env.commands.interceptChat(".cfg list", env.ctx));
        assertTrue(env.lines.stream().anyMatch(line -> line.contains("grim")));

        assertTrue(env.commands.interceptChat(".cfg delete grim", env.ctx));
        env.lines.clear();
        assertTrue(env.commands.interceptChat(".cfg list", env.ctx));
        assertTrue(env.lines.stream().anyMatch(line -> line.contains("No saved profiles")));
    }

    @Test
    void prefixCommandUpdatesAndPersistsPrefix(@TempDir final Path tempDir) {
        Env env = setup(tempDir);
        assertTrue(env.commands.interceptChat(".prefix !", env.ctx));
        assertEquals("!", env.commands.prefix());
        assertFalse(env.commands.interceptChat(".help", env.ctx));
        assertTrue(env.commands.interceptChat("!help", env.ctx));

        JsonObject json = new JsonObject();
        env.commands.write(json);
        CommandManager reloaded = new CommandManager();
        reloaded.read(json);
        assertEquals("!", reloaded.prefix());
    }

    @Test
    void suspectsCommandListsAndForgetsAntiCheatSuspects(@TempDir final Path tempDir) {
        Env env = setup(tempDir);
        UUID id = UUID.randomUUID();
        env.antiCheat.suspects().update(id, "Cheater", "Speed", 4.5);

        assertTrue(env.commands.interceptChat(".ac list", env.ctx));
        assertTrue(env.lines.stream().anyMatch(line -> line.contains("Cheater") && line.contains("Speed")));

        assertTrue(env.commands.interceptChat(".ac forget Cheater", env.ctx));
        assertTrue(env.antiCheat.suspects().ranked().isEmpty());
    }

    @Test
    void helpAndRepliesSwitchToChineseWhenClientLanguageIsZhCn(@TempDir final Path tempDir) {
        CommandManager commands = new CommandManager();
        commands.registerDefaults();
        ModuleManager modules = new ModuleManager();
        DummyModule dummy = new DummyModule();
        modules.register(dummy);
        List<String> lines = new ArrayList<>();
        CommandContext zhCtx = new CommandContext(modules, commands, tempDir.resolve("profiles"),
                lines::add, () -> {}, () -> true);

        assertTrue(commands.interceptChat(".help", zhCtx));
        assertTrue(lines.get(0).contains("可用命令"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("开启、关闭或切换指定模块")));

        lines.clear();
        assertTrue(commands.interceptChat(".t dummy", zhCtx));
        assertTrue(lines.get(0).contains("已") && lines.get(0).contains("开启"));
    }
}
