package com.mentalfrostbyte.jello.gui.modern;

import com.viaversion.viaaprilfools.api.AprilFoolsProtocolVersion;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;

/**
 * How SigmaModern's protocol picker groups and describes the versions ViaFabricPlus can translate to.
 *
 * <p>Four eras, each with the block that stands for it: <b>Modern</b> (grass block) from Caves &amp; Cliffs
 * onwards, <b>Middle</b> (dirt path - a block the Combat Update itself introduced) from 1.9 to the Nether
 * Update, <b>Legacy</b> (cobblestone - the game's first block) for 1.8 and everything before it, down to
 * Classic, and <b>Bedrock</b> on its own. Auto Detect is pinned above them all.</p>
 *
 * <p>Versions are read from ViaVersion's live registry every time, never from a fixed list - the registry
 * grows during startup (Auto Detect and the Bedrock name are added at the end of ViaFabricPlus's
 * background init) and between Via releases. Special versions (April Fools, combat tests, CPE) carry
 * meaningless protocol numbers, so they are never compared: they take the era of the release listed just
 * above them. Notes are keyed by the version constants, not by display names, which change.</p>
 */
final class ModernVersions {
    enum Era {
        MODERN("Modern", "现代", ModernBlocks.Block.GRASS, 0xFF9BD67F,
            "Caves & Cliffs onwards - the game as it is today", "从洞穴与山崖至今，现在的游戏"),
        MIDDLE("Middle", "中期", ModernBlocks.Block.DIRT_PATH, 0xFFE0C084,
            "From the Combat Update to the Nether Update", "从战斗更新到下界更新"),
        LEGACY("Legacy", "旧版", ModernBlocks.Block.COBBLESTONE, 0xFFBFC6CC,
            "Before the Combat Update: releases, Beta, Alpha and Classic", "战斗更新之前：正式版、Beta、Alpha 与经典版"),
        BEDROCK("Bedrock", "基岩版", ModernBlocks.Block.BEDROCK, 0xFFA7B1BC,
            "Bedrock Edition servers, translated by ViaBedrock", "经由 ViaBedrock 连接基岩版服务器");

        final String en, zh;
        final ModernBlocks.Block block;
        final int accent;
        final String blurbEn, blurbZh;

        Era(String en, String zh, ModernBlocks.Block block, int accent, String blurbEn, String blurbZh) {
            this.en = en;
            this.zh = zh;
            this.block = block;
            this.accent = accent;
            this.blurbEn = blurbEn;
            this.blurbZh = blurbZh;
        }

        String label() {
            return text(this.en, this.zh);
        }

        String blurb() {
            return text(this.blurbEn, this.blurbZh);
        }
    }

    /** A sub-division of the Legacy era, shown as a small divider. */
    enum Stage { RELEASE, BETA, ALPHA, CLASSIC }

    record Entry(ProtocolVersion version, Era era, Stage stage, boolean auto, boolean special) {
        ModernBlocks.Block block() {
            return this.auto ? ModernBlocks.Block.OBSERVER : this.era.block;
        }
    }

    record Note(String en, String zh) {
        String text() {
            return ModernVersions.text(this.en, this.zh);
        }
    }

    private static Map<ProtocolVersion, Note> notes;

    private ModernVersions() {}

    static boolean chinese() {
        return ModernText.chinese();
    }

    static String text(String en, String zh) {
        return ModernText.t(en, zh);
    }

    /** The registry's versions, newest first, classified. Cheap enough to rebuild when the registry grows. */
    static List<Entry> build() {
        List<Entry> entries = new ArrayList<>();
        Era previous = Era.MODERN;
        for (ProtocolVersion version : ProtocolVersion.getReversedProtocols()) {
            if (version == ProtocolTranslator.AUTO_DETECT_PROTOCOL) {
                entries.add(new Entry(version, Era.MODERN, null, true, true));
                continue;
            }
            if (version == BedrockProtocolVersion.bedrockLatest) {
                entries.add(new Entry(version, Era.BEDROCK, null, false, true));
                continue;
            }
            boolean special = version.getVersionType() == VersionType.SPECIAL;
            Era era;
            if (version == LegacyProtocolVersion.c0_30cpe) {
                era = Era.LEGACY;
            } else if (special) {
                era = previous;
            } else {
                era = version.newerThanOrEqualTo(ProtocolVersion.v1_17) ? Era.MODERN
                    : version.newerThanOrEqualTo(ProtocolVersion.v1_9) ? Era.MIDDLE : Era.LEGACY;
                previous = era;
            }
            entries.add(new Entry(version, era, era == Era.LEGACY ? stage(version) : null, false, special));
        }
        return entries;
    }

    private static Stage stage(ProtocolVersion version) {
        // c0.30 CPE is a SPECIAL type listed right under a1.0.15; it belongs with Classic, not Alpha.
        if (version == LegacyProtocolVersion.c0_30cpe) return Stage.CLASSIC;
        return switch (version.getVersionType()) {
            case CLASSIC -> Stage.CLASSIC;
            case ALPHA_INITIAL, ALPHA_LATER -> Stage.ALPHA;
            case BETA_INITIAL, BETA_LATER -> Stage.BETA;
            default -> Stage.RELEASE;
        };
    }

    static String stageLabel(Stage stage) {
        return switch (stage) {
            case RELEASE -> text("RELEASE", "正式版");
            case BETA -> "BETA";
            case ALPHA -> "ALPHA";
            case CLASSIC -> text("CLASSIC", "经典版");
        };
    }

    /** A one-line note for the versions that mark something; null for the rest. */
    static Note note(ProtocolVersion version) {
        if (notes == null) notes = buildNotes();
        return notes.get(version);
    }

    private static Map<ProtocolVersion, Note> buildNotes() {
        Map<ProtocolVersion, Note> n = new IdentityHashMap<>();
        n.put(ProtocolTranslator.AUTO_DETECT_PROTOCOL, new Note("Asks the server its version first, then connects (1.7+)", "先探测服务器版本再连接（1.7+）"));
        n.put(BedrockProtocolVersion.bedrockLatest, new Note("Bedrock Edition through ViaBedrock - still a work in progress", "经由 ViaBedrock 连接基岩版，仍在开发中"));

        n.put(ProtocolVersion.v26_2, new Note("Native - what this client speaks, no translation", "原生版本，不经过任何转换"));
        n.put(ProtocolVersion.v26_1, new Note("The first year-numbered release", "第一个以年份编号的版本"));
        n.put(ProtocolVersion.v1_21_11, new Note("The last 1.x version", "最后一个 1.x 版本"));
        n.put(ProtocolVersion.v1_21_9, new Note("The Copper Age - copper golems", "The Copper Age：铜傀儡"));
        n.put(ProtocolVersion.v1_21_6, new Note("Chase the Skies - the happy ghast", "Chase the Skies：快乐恶魂"));
        n.put(ProtocolVersion.v1_21_5, new Note("Spring to Life - farm animal variants", "Spring to Life：农场动物变种"));
        n.put(ProtocolVersion.v1_21_4, new Note("The Garden Awakens - the pale garden", "The Garden Awakens：苍白之园"));
        n.put(ProtocolVersion.v1_21_2, new Note("Bundles of Bravery - bundles", "Bundles of Bravery：收纳袋"));
        n.put(ProtocolVersion.v1_21, new Note("Tricky Trials - trial chambers and the mace", "棘巧试炼：试炼密室与重锤"));
        n.put(ProtocolVersion.v1_20_5, new Note("Item data components replace item NBT", "物品数据组件取代物品 NBT"));
        n.put(ProtocolVersion.v1_20_2, new Note("The protocol gains a configuration phase", "协议新增配置阶段"));
        n.put(ProtocolVersion.v1_20, new Note("Trails & Tales", "足迹与故事"));
        n.put(ProtocolVersion.v1_19_1, new Note("Player chat reporting", "玩家聊天举报"));
        n.put(ProtocolVersion.v1_19, new Note("The Wild Update - the Deep Dark", "荒野更新：深暗之域"));
        n.put(ProtocolVersion.v1_18, new Note("Caves & Cliffs II - the world spans -64 to 320", "洞穴与山崖 II：世界高度 -64 至 320"));
        n.put(ProtocolVersion.v1_17, new Note("Caves & Cliffs I - where the Modern era begins", "洞穴与山崖 I：现代时期的开端"));

        n.put(ProtocolVersion.v1_16_4, new Note("The Nether Update's final release", "下界更新的最终版本"));
        n.put(ProtocolVersion.v1_16, new Note("The Nether Update", "下界更新"));
        n.put(ProtocolVersion.v1_15, new Note("Buzzy Bees", "嗡嗡蜂群"));
        n.put(ProtocolVersion.v1_14, new Note("Village & Pillage", "村庄与掠夺"));
        n.put(ProtocolVersion.v1_13, new Note("Update Aquatic - and The Flattening", "海洋更新，以及扁平化"));
        n.put(ProtocolVersion.v1_12_2, new Note("The last release before The Flattening", "扁平化之前的最后一个版本"));
        n.put(ProtocolVersion.v1_9, new Note("Combat Update - attack cooldown, off-hand", "战斗更新：攻击冷却与副手"));

        n.put(ProtocolVersion.v1_8, new Note("The PvP classic - no attack cooldown", "PvP 经典版本，没有攻击冷却"));
        n.put(ProtocolVersion.v1_7_6, new Note("Home of 1.7.10, the long-lived modding version", "包含 1.7.10，长寿的模组版本"));
        n.put(ProtocolVersion.v1_7_2, new Note("The networking rewritten on Netty", "网络层基于 Netty 重写"));
        n.put(LegacyProtocolVersion.r1_6_4, new Note("The last release before Netty", "Netty 之前的最后一个版本"));
        n.put(LegacyProtocolVersion.r1_3_1tor1_3_2, new Note("Singleplayer moves onto an integrated server", "单人游戏改用内置服务器"));
        n.put(LegacyProtocolVersion.r1_0_0tor1_0_1, new Note("The official release", "正式版发布"));
        n.put(LegacyProtocolVersion.b1_8tob1_8_1, new Note("Adventure Update - hunger and sprinting", "冒险更新：饥饿值与疾跑"));
        n.put(LegacyProtocolVersion.b1_7tob1_7_3, new Note("The last Beta before the Adventure Update", "冒险更新之前的最后一个 Beta"));
        n.put(LegacyProtocolVersion.b1_0tob1_1_1, new Note("Beta begins", "Beta 阶段开始"));
        n.put(LegacyProtocolVersion.a1_0_15, new Note("The first Alpha with survival multiplayer", "第一个支持生存多人的 Alpha"));
        n.put(LegacyProtocolVersion.c0_30cpe, new Note("Classic Protocol Extension, as used by ClassiCube", "经典版协议扩展（ClassiCube 使用）"));
        n.put(LegacyProtocolVersion.c0_28toc0_30, new Note("The final Classic", "经典版的最终版本"));
        n.put(LegacyProtocolVersion.c0_0_15a_1, new Note("The first Classic with multiplayer", "第一个支持多人游戏的经典版"));

        n.put(AprilFoolsProtocolVersion.s25w14craftMine, new Note("April Fools 2025 - Craftmine", "2025 年愚人节版本 Craftmine"));
        n.put(AprilFoolsProtocolVersion.sCombatTest8c, new Note("An experimental combat snapshot", "实验性的战斗测试快照"));
        n.put(AprilFoolsProtocolVersion.s20w14infinite, new Note("April Fools 2020 - endless dimensions", "2020 年愚人节：无尽维度"));
        n.put(AprilFoolsProtocolVersion.s3d_shareware, new Note("April Fools 2019 - 3D Shareware v1.34", "2019 年愚人节：3D Shareware v1.34"));
        return n;
    }

    /** "1.8, 1.8.0, ... 1.8.9" for a range; null when the entry is a single version. */
    static String included(ProtocolVersion version) {
        if (version.getIncludedVersions().size() <= 1) return null;
        return String.join(", ", version.getIncludedVersions());
    }

    /** The protocol number, or -1 where it means nothing (special and synthetic versions). */
    static int protocolNumber(Entry entry) {
        return entry.special() || entry.version().getVersion() < 0 ? -1 : entry.version().getVersion();
    }

    static boolean matches(Entry entry, String query) {
        if (query.isEmpty()) return true;
        String q = query.toLowerCase(Locale.ROOT);
        if (entry.version().getName().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String v : entry.version().getIncludedVersions()) if (v.toLowerCase(Locale.ROOT).contains(q)) return true;
        Note note = note(entry.version());
        return note != null && (note.en().toLowerCase(Locale.ROOT).contains(q) || note.zh().contains(query));
    }
}
