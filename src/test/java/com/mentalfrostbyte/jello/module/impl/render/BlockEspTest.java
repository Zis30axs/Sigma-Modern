package com.mentalfrostbyte.jello.module.impl.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockEspTest {
    private static Holder<Biome> plainsBiome;
    private static Strategy<Holder<Biome>> biomeStrategy;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = VanillaRegistries.createLookup();
        var biomes = lookup.lookupOrThrow(Registries.BIOME);
        plainsBiome = biomes.getOrThrow(Biomes.PLAINS);
        IdMap<Holder<Biome>> biomeIdMap = new IdMap<>() {
            @Override public int getId(Holder<Biome> thing) { return 0; }
            @Override public @Nullable Holder<Biome> byId(int id) { return id == 0 ? plainsBiome : null; }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<Holder<Biome>> iterator() { return List.of(plainsBiome).iterator(); }
        };
        biomeStrategy = Strategy.createForBiomes(biomeIdMap);
    }

    private static LevelChunkSection emptySection() {
        var states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        var biomes = new PalettedContainer<>(plainsBiome, biomeStrategy);
        return new LevelChunkSection(states, biomes);
    }

    private static final class MapWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }

    @Test
    void registersInRenderCategoryAndPersistsCustomBlockList() {
        var manager = new ModuleManager();
        manager.registerAll();
        var module = manager.find("blockesp").orElseThrow();
        assertEquals(ModuleCategory.RENDER, module.getCategory());
        assertFalse(module.isEnabled());

        ItemListSetting blocks = ItemListSetting.blocks("Custom Blocks", "test");
        assertTrue(ItemListSetting.registeredBlock("minecraft:nether_portal"));
        assertFalse(ItemListSetting.registeredBlock("minecraft:air"));
        assertTrue(blocks.fromJson(JsonParser.parseString(
                "[\"minecraft:obsidian\",\"minecraft:nether_portal\",\"minecraft:air\",\"minecraft:unknown\"]")));
        assertEquals(Set.of("minecraft:obsidian", "minecraft:nether_portal"), blocks.get());
    }

    @Test
    void sectionScanUsesPaletteFilterAndCapsNearestFirst() {
        var targets = BlockEsp.buildTargetSet(false, false, false, true, false, false, Set.of("minecraft:beacon"));
        assertTrue(targets.contains(Blocks.DIAMOND_ORE));
        assertTrue(targets.contains(Blocks.ANCIENT_DEBRIS));
        assertTrue(targets.contains(Blocks.BEACON));
        assertFalse(targets.contains(Blocks.STONE));

        // Non-matching stone section skips cleanly with zero entries.
        var stoneOnly = emptySection();
        stoneOnly.setBlockState(2, 2, 2, Blocks.STONE.defaultBlockState());
        var world = new MapWorld();
        var results = new ArrayList<BlockEsp.Entry>();
        BlockEsp.scanSection(stoneOnly, 0, 0, 0, new Vec3(0.5, 0.5, 0.5), 64 * 64,
                targets, world, true, 0xFFFFFFFF, results);
        assertTrue(results.isEmpty());

        // Matching section finds ores/custom blocks and finalizeEntries keeps the nearest up to maxBlocks.
        var oreSection = emptySection();
        oreSection.setBlockState(12, 0, 0, Blocks.DIAMOND_ORE.defaultBlockState());
        oreSection.setBlockState(2, 0, 0, Blocks.BEACON.defaultBlockState());
        oreSection.setBlockState(6, 0, 0, Blocks.ANCIENT_DEBRIS.defaultBlockState());
        BlockEsp.scanSection(oreSection, 0, 0, 0, new Vec3(0.5, 0.5, 0.5), 64 * 64,
                targets, world, true, 0xFFFFFFFF, results);
        assertEquals(3, results.size());

        List<BlockEsp.Entry> capped = BlockEsp.finalizeEntries(results, 2);
        assertEquals(2, capped.size());
        assertEquals(new BlockPos(2, 0, 0), capped.get(0).pos());
        assertEquals(new BlockPos(6, 0, 0), capped.get(1).pos());
    }

    @Test
    void mergesConnectedBedsAndDoubleChestsIntoSingleBoxes() {
        var targets = BlockEsp.buildTargetSet(true, false, true, false, false, false, Set.of());
        var world = new MapWorld();
        var section = emptySection();

        Block redBed = Blocks.BED.red();
        // East-facing Red Bed: FOOT at (4, 1, 4), HEAD at (5, 1, 4).
        BlockState bedFoot = redBed.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT);
        BlockState bedHead = redBed.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD);
        BlockPos footPos = new BlockPos(4, 1, 4);
        BlockPos headPos = new BlockPos(5, 1, 4);
        world.put(footPos, bedFoot);
        world.put(headPos, bedHead);
        section.setBlockState(4, 1, 4, bedFoot);
        section.setBlockState(5, 1, 4, bedHead);

        // North-facing Double Chest: LEFT at (4, 2, 8), connected direction = EAST -> RIGHT at (5, 2, 8).
        BlockState chestLeft = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState chestRight = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockPos leftPos = new BlockPos(4, 2, 8);
        BlockPos rightPos = new BlockPos(5, 2, 8);
        world.put(leftPos, chestLeft);
        world.put(rightPos, chestRight);
        section.setBlockState(4, 2, 8, chestLeft);
        section.setBlockState(5, 2, 8, chestRight);

        var found = new ArrayList<BlockEsp.Entry>();
        BlockEsp.scanSection(section, 0, 0, 0, new Vec3(4.5, 1.5, 4.5), 64 * 64,
                targets, world, true, 0xFF8FDDF2, found);

        // 4 raw blocks -> exactly 2 merged entries (one bed, one double chest)!
        assertEquals(2, found.size());
        BlockEsp.Entry bedEntry = found.stream().filter(e -> e.block() == redBed).findFirst().orElseThrow();
        assertEquals(headPos, bedEntry.pos());
        assertEquals(footPos, bedEntry.partnerPos());
        assertTrue(bedEntry.box().getXsize() > 1.5, "Merged bed AABB should span both X blocks");

        BlockEsp.Entry chestEntry = found.stream().filter(e -> e.block() == Blocks.CHEST).findFirst().orElseThrow();
        assertEquals(leftPos, chestEntry.pos());
        assertEquals(rightPos, chestEntry.partnerPos());
        assertTrue(chestEntry.box().getXsize() > 1.5, "Merged double chest AABB should span both X blocks");
    }

    @Test
    void distinguishesTrappedChestEnderChestAndUniformOverrideColors() {
        int normalChest = BlockEsp.colorFor(Blocks.CHEST, true, 0xFF123456);
        int trappedChest = BlockEsp.colorFor(Blocks.TRAPPED_CHEST, true, 0xFF123456);
        int enderChest = BlockEsp.colorFor(Blocks.ENDER_CHEST, true, 0xFF123456);
        assertNotEquals(normalChest, trappedChest);
        assertNotEquals(normalChest, enderChest);
        assertEquals(0xFF123456, BlockEsp.colorFor(Blocks.DIAMOND_ORE, false, 0x123456));
    }
}
