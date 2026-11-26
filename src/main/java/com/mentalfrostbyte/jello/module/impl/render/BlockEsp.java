package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.ItemListSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Highlights selected storage, objective, ore and custom blocks through walls using section-palette filtering. */
public final class BlockEsp extends Module {
    public enum Style {
        WIREFRAME("3D Box"), BOX("2D Box"), CORNERS("Corners");
        private final String label;
        Style(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    public record Entry(BlockPos pos, @Nullable BlockPos partnerPos, Block block, AABB box, int color, double distanceSq) {}

    private static final int SCAN_INTERVAL_TICKS = 5;

    private final EnumSetting<Style> style = this.register(new EnumSetting<>(
            "Style", "3D world wireframe, 2D bounding rectangle or corner brackets.", Style.WIREFRAME));
    private final BooleanSetting chests = this.register(new BooleanSetting(
            "Chests", "Highlight chests, trapped chests, ender chests and barrels.", true));
    private final BooleanSetting shulkers = this.register(new BooleanSetting(
            "Shulkers", "Highlight all shulker boxes.", true));
    private final BooleanSetting beds = this.register(new BooleanSetting(
            "Beds", "Highlight beds across all colors and merge both halves.", true));
    private final BooleanSetting ores = this.register(new BooleanSetting(
            "Ores", "Highlight diamond, emerald, gold ores and ancient debris.", false));
    private final BooleanSetting spawners = this.register(new BooleanSetting(
            "Spawners", "Highlight mob spawners, trial spawners and vaults.", true));
    private final BooleanSetting portals = this.register(new BooleanSetting(
            "Portals", "Highlight nether portals, end portals and end gateways.", false));
    private final ItemListSetting customBlocks = this.register(ItemListSetting.blocks(
            "Custom Blocks", "Additional blocks from the registry to highlight."));
    private final BooleanSetting blockColor = this.register(new BooleanSetting(
            "Block Color", "Color each highlight according to the block type or dye color.", true));
    private final ColorSetting color = this.register(new ColorSetting(
            "Color", "Fallback or uniform highlight color.", 0xFF8FDDF2));
    private final NumberSetting range = this.register(new NumberSetting(
            "Range", "Maximum search distance in blocks.", 64F, 8F, 160F, 8F));
    private final NumberSetting maxBlocks = this.register(new NumberSetting(
            "Max Blocks", "Maximum nearest matching blocks rendered at once.", 128F, 16F, 512F, 16F));
    private final NumberSetting thickness = this.register(new NumberSetting(
            "Thickness", "Stroke width for 3D outlines and tracers.", 1.25F, 0.75F, 3.0F, 0.25F));
    private final BooleanSetting tracers = this.register(new BooleanSetting(
            "Tracers", "Draw lines from the crosshair to highlighted blocks.", false));
    private final BooleanSetting labels = this.register(new BooleanSetting(
            "Labels", "Show block name and distance under nearby highlights.", false));

    private volatile List<Entry> cachedEntries = List.of();
    private @Nullable Set<Block> cachedTargetBlocks;
    private int lastFilterMask = -1;
    private @Nullable Set<String> lastCustomSnapshot;
    private boolean lastBlockColor;
    private int lastColorValue;
    private float lastRange = -1F;
    private int lastMaxBlocks = -1;
    private int scanCooldown;

    public BlockEsp() {
        super(ModuleCategory.RENDER, "BlockESP", "Highlights chests, beds, ores and custom blocks through walls.");
        this.thickness.visibleWhen(() -> this.style.get() == Style.WIREFRAME || this.tracers.get());
    }

    public Style getStyle() { return this.style.get(); }
    public float getRange() { return this.range.get(); }
    public float getThickness() { return this.thickness.get(); }
    public boolean showsTracers() { return this.tracers.get(); }
    public boolean showsLabels() { return this.labels.get(); }
    public List<Entry> entries() { return this.cachedEntries; }

    @Override
    protected void onEnable() {
        this.scanCooldown = 0;
    }

    @Override
    protected void onDisable() {
        this.cachedEntries = List.of();
        this.scanCooldown = 0;
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.cachedEntries = List.of();
        this.scanCooldown = 0;
    }

    @EventTarget
    public void onTick(final EventTick event) {
        if (!event.isPre()) return;
        ClientLevel level = mc.level;
        var player = mc.player;
        if (level == null || player == null) {
            this.cachedEntries = List.of();
            return;
        }
        if (this.settingsChanged()) {
            this.scanCooldown = 0;
        }
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return;
        }
        this.scanCooldown = SCAN_INTERVAL_TICKS - 1;
        Set<Block> targets = this.currentTargetBlocks();
        if (targets.isEmpty()) {
            this.cachedEntries = List.of();
            return;
        }
        this.cachedEntries = scanLevel(level, player.getEyePosition(), this.range.get(),
                this.maxBlocks.getInt(), targets, this.blockColor.get(), this.color.get() | 0xFF000000);
    }

    private int filterMask() {
        return (this.chests.get() ? 1 : 0)
                | (this.shulkers.get() ? 2 : 0)
                | (this.beds.get() ? 4 : 0)
                | (this.ores.get() ? 8 : 0)
                | (this.spawners.get() ? 16 : 0)
                | (this.portals.get() ? 32 : 0);
    }

    private boolean settingsChanged() {
        int mask = this.filterMask();
        Set<String> custom = this.customBlocks.get();
        boolean useBlockColors = this.blockColor.get();
        int fallback = this.color.get();
        float r = this.range.get();
        int max = this.maxBlocks.getInt();
        if (mask != this.lastFilterMask || custom != this.lastCustomSnapshot) {
            this.cachedTargetBlocks = null;
        }
        if (mask != this.lastFilterMask || custom != this.lastCustomSnapshot
                || useBlockColors != this.lastBlockColor || fallback != this.lastColorValue
                || Float.compare(r, this.lastRange) != 0 || max != this.lastMaxBlocks) {
            this.lastFilterMask = mask;
            this.lastCustomSnapshot = custom;
            this.lastBlockColor = useBlockColors;
            this.lastColorValue = fallback;
            this.lastRange = r;
            this.lastMaxBlocks = max;
            return true;
        }
        return false;
    }

    private Set<Block> currentTargetBlocks() {
        Set<Block> targets = this.cachedTargetBlocks;
        if (targets == null) {
            targets = buildTargetSet(this.chests.get(), this.shulkers.get(), this.beds.get(),
                    this.ores.get(), this.spawners.get(), this.portals.get(), this.customBlocks.get());
            this.cachedTargetBlocks = targets;
        }
        return targets;
    }

    public static Set<Block> buildTargetSet(boolean chests, boolean shulkers, boolean beds,
                                            boolean ores, boolean spawners, boolean portals,
                                            Set<String> customIds) {
        Set<Block> targets = new ReferenceOpenHashSet<>();
        if (chests || shulkers || beds) {
            for (Block block : BuiltInRegistries.BLOCK) {
                if (chests && (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof BarrelBlock)) {
                    targets.add(block);
                } else if (shulkers && block instanceof ShulkerBoxBlock) {
                    targets.add(block);
                } else if (beds && block instanceof BedBlock) {
                    targets.add(block);
                }
            }
        }
        if (ores) {
            targets.add(Blocks.DIAMOND_ORE);
            targets.add(Blocks.DEEPSLATE_DIAMOND_ORE);
            targets.add(Blocks.ANCIENT_DEBRIS);
            targets.add(Blocks.EMERALD_ORE);
            targets.add(Blocks.DEEPSLATE_EMERALD_ORE);
            targets.add(Blocks.GOLD_ORE);
            targets.add(Blocks.DEEPSLATE_GOLD_ORE);
            targets.add(Blocks.NETHER_GOLD_ORE);
        }
        if (spawners) {
            targets.add(Blocks.SPAWNER);
            targets.add(Blocks.TRIAL_SPAWNER);
            targets.add(Blocks.VAULT);
        }
        if (portals) {
            targets.add(Blocks.NETHER_PORTAL);
            targets.add(Blocks.END_PORTAL);
            targets.add(Blocks.END_GATEWAY);
        }
        for (String id : customIds) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) {
                BuiltInRegistries.BLOCK.getOptional(parsed).ifPresent(block -> {
                    if (block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR) {
                        targets.add(block);
                    }
                });
            }
        }
        return targets;
    }

    public static List<Entry> scanLevel(ClientLevel level, Vec3 origin, float range, int maxBlocks,
                                        Set<Block> targets, boolean useBlockColors, int fallbackColor) {
        if (targets.isEmpty() || !Float.isFinite(range) || range <= 0F || maxBlocks <= 0) return List.of();
        double rangeSq = (double) range * range;
        int minChunkX = SectionPos.blockToSectionCoord((int) Math.floor(origin.x - range));
        int maxChunkX = SectionPos.blockToSectionCoord((int) Math.floor(origin.x + range));
        int minChunkZ = SectionPos.blockToSectionCoord((int) Math.floor(origin.z - range));
        int maxChunkZ = SectionPos.blockToSectionCoord((int) Math.floor(origin.z + range));
        int minY = Math.max(level.getMinY(), (int) Math.floor(origin.y - range));
        int maxY = Math.min(level.getMaxY() - 1, (int) Math.floor(origin.y + range));
        if (minY > maxY) return List.of();

        int minSecIdx = level.getSectionIndex(minY);
        int maxSecIdx = level.getSectionIndex(maxY);
        List<Entry> found = new ArrayList<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            double chunkMinX = cx << 4;
            double dx = axisGap(origin.x, chunkMinX, chunkMinX + 16.0);
            if (dx * dx > rangeSq) continue;

            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                double chunkMinZ = cz << 4;
                double dz = axisGap(origin.z, chunkMinZ, chunkMinZ + 16.0);
                double horizSq = dx * dx + dz * dz;
                if (horizSq > rangeSq) continue;

                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null || chunk.isEmpty()) continue;
                LevelChunkSection[] sections = chunk.getSections();
                int endSec = Math.min(maxSecIdx, sections.length - 1);
                for (int secIdx = Math.max(0, minSecIdx); secIdx <= endSec; secIdx++) {
                    LevelChunkSection section = sections[secIdx];
                    if (section == null || section.hasOnlyAir()) continue;
                    int secMinY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(secIdx));
                    double dy = axisGap(origin.y, secMinY, secMinY + 16.0);
                    if (horizSq + dy * dy > rangeSq) continue;
                    scanSection(section, cx << 4, secMinY, cz << 4, origin, rangeSq,
                            targets, level, useBlockColors, fallbackColor, found);
                }
            }
        }
        return finalizeEntries(found, maxBlocks);
    }

    public static void scanSection(LevelChunkSection section, int chunkMinX, int secMinY, int chunkMinZ,
                                   Vec3 origin, double rangeSq, Set<Block> targets, BlockGetter world,
                                   boolean useBlockColors, int fallbackColor, List<Entry> out) {
        if (section == null || section.hasOnlyAir()) return;
        if (!section.maybeHas(state -> targets.contains(state.getBlock()))) return;
        for (int ly = 0; ly < 16; ly++) {
            int by = secMinY + ly;
            double dy = by + 0.5 - origin.y;
            double dySq = dy * dy;
            if (dySq > rangeSq) continue;
            for (int lz = 0; lz < 16; lz++) {
                int bz = chunkMinZ + lz;
                double dz = bz + 0.5 - origin.z;
                double dyzSq = dySq + dz * dz;
                if (dyzSq > rangeSq) continue;
                for (int lx = 0; lx < 16; lx++) {
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (!targets.contains(state.getBlock())) continue;
                    int bx = chunkMinX + lx;
                    double dx = bx + 0.5 - origin.x;
                    double distSq = dyzSq + dx * dx;
                    if (distSq > rangeSq) continue;
                    Entry entry = resolveEntry(world, new BlockPos(bx, by, bz), state,
                            origin, rangeSq, useBlockColors, fallbackColor);
                    if (entry != null) out.add(entry);
                }
            }
        }
    }

    public static @Nullable Entry resolveEntry(BlockGetter world, BlockPos pos, BlockState state,
                                               Vec3 origin, double rangeSq,
                                               boolean useBlockColors, int fallbackColor) {
        Block block = state.getBlock();
        BlockPos partnerPos = null;
        AABB box = shapeBounds(world, pos, state);

        if (block instanceof BedBlock && state.hasProperty(BedBlock.PART)) {
            Direction connected = BedBlock.getConnectedDirection(state);
            BlockPos other = pos.relative(connected);
            BlockState otherState = world.getBlockState(other);
            if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
                if (otherState.is(block) && origin.distanceToSqr(Vec3.atCenterOf(other)) <= rangeSq) {
                    return null;
                }
            } else if (otherState.is(block)) {
                partnerPos = other;
                box = box.minmax(shapeBounds(world, other, otherState));
            }
        } else if (block instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                Direction connected = ChestBlock.getConnectedDirection(state);
                BlockPos other = pos.relative(connected);
                BlockState otherState = world.getBlockState(other);
                if (type == ChestType.RIGHT) {
                    if (otherState.is(block) && origin.distanceToSqr(Vec3.atCenterOf(other)) <= rangeSq) {
                        return null;
                    }
                } else if (otherState.is(block)) {
                    partnerPos = other;
                    box = box.minmax(shapeBounds(world, other, otherState));
                }
            }
        }

        double distSq = origin.distanceToSqr(box.getCenter());
        int resolvedColor = colorFor(block, useBlockColors, fallbackColor);
        return new Entry(pos, partnerPos, block, box, resolvedColor, distSq);
    }

    public static List<Entry> finalizeEntries(List<Entry> entries, int maxBlocks) {
        if (entries.isEmpty() || maxBlocks <= 0) return List.of();
        entries.sort(Comparator.comparingDouble(Entry::distanceSq));
        return entries.size() <= maxBlocks ? List.copyOf(entries) : List.copyOf(entries.subList(0, maxBlocks));
    }

    public static int colorFor(Block block, boolean useBlockColors, int fallbackColor) {
        if (!useBlockColors) return fallbackColor | 0xFF000000;
        if (block instanceof TrappedChestBlock) return 0xFFFF5C4D;
        if (block instanceof ChestBlock || block instanceof BarrelBlock) return 0xFFFFB84D;
        if (block instanceof EnderChestBlock) return 0xFFB468FF;
        if (block instanceof ShulkerBoxBlock shulker) {
            DyeColor dye = shulker.getColor();
            return dye == null ? 0xFFB878D8 : visibleDyeColor(dye);
        }
        if (block instanceof BedBlock bed) {
            return visibleDyeColor(bed.getColor());
        }
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return 0xFF4EE8FF;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return 0xFF3EE878;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE) return 0xFFFFD84A;
        if (block == Blocks.ANCIENT_DEBRIS) return 0xFFD27D46;
        if (block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT) return 0xFFFF52A8;
        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) return 0xFF9E5BFF;
        int mapCol = block.defaultMapColor().col;
        return mapCol != 0 ? liftLuminance(mapCol) : (fallbackColor | 0xFF000000);
    }

    private static int visibleDyeColor(DyeColor dye) {
        return liftLuminance(dye.getFireworkColor());
    }

    private static int liftLuminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (r < 48 && g < 48 && b < 48) {
            r = Math.min(255, r + 64);
            g = Math.min(255, g + 64);
            b = Math.min(255, b + 64);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static AABB shapeBounds(BlockGetter world, BlockPos pos, BlockState state) {
        try {
            VoxelShape shape = state.getShape(world, pos);
            if (!shape.isEmpty()) return shape.bounds().move(pos);
        } catch (RuntimeException ignored) {
        }
        return new AABB(pos);
    }

    private static double axisGap(double coord, double min, double max) {
        if (coord < min) return min - coord;
        if (coord > max) return coord - max;
        return 0.0;
    }
}
