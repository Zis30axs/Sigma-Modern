# PORTING.md — MCP 26.2 Mixin-free 移植记录

本文件记录 `sodium`、`FerriteCore`、`lithium`、`sodium-extra`、`Iris` 五个 Mod 向
`Sigma-Modern`（MCP 26.2，纯源码、无 Mixin/无 Loader）的移植过程。

## 通用约定

* 所有原 Mixin 行为都被转换为对 `net.minecraft` / `com.mojang` 源码的直接修改，修改点附近标注
  `// MODIFIED for porting`。
* Mod 自身的 package / 类名 / 目录结构保持原样（例如 `malte0811.ferritecore.*`）。
  原本是 `@Mixin` 接口的 accessor（`...mixin.accessors.*`）保留为普通接口，由对应的原版类直接
  `implements`，这样 Mod 内部的 `instanceof` / 强制转换逻辑不需要改动。
* Loader（Fabric / NeoForge）特有的部分——mod metadata、入口点、config 文件读写、Mixin 插件
  （`IMixinConfigPlugin`）、`MixinService` 探测——不移植；其承载的**必要逻辑**接到 MCP 26.2 中真实
  的调用点上。
* 编译命令（不修改 `pom.xml`；仓库 `pom.xml` 要求 `release 25`，需要 JDK ≥ 25）：
  `JAVA_HOME=<jdk25+> mvn -o -DskipTests compile`

## 状态图例

| 状态 | 含义 |
| --- | --- |
| DONE | 已按原语义转换为源码修改，编译通过 |
| SKIP | 经源码分析确认在本项目环境下不应生效（附原因） |
| TODO | 尚未移植 |

---

## FerriteCore 26.1 → MCP 26.2

FerriteCore 只包含内存占用优化，分 5 个 mixin config，每个 config 由 `FerriteMixinConfig`
（一个 `IMixinConfigPlugin`）按 `FerriteConfig` 中的开关决定是否装载。
`FerriteConfig` 本身在移植版中保留（`malte0811.ferritecore.mixin.config.FerriteConfig`），
但 `finish()` 不再读取 Loader 管理的配置文件，而是直接采用上游默认值：

| Option | 默认 | 移植后 |
| --- | --- | --- |
| `replaceNeighborLookup` | on | 无条件生效（该 module 是核心数据结构替换，无法在运行时切换） |
| `replacePropertyMap` | on | 运行时仍由 `FerriteConfig.PROPERTY_MAP` 控制 |
| `blockstateCacheDeduplication` | on | 运行时仍由 `FerriteConfig.DEDUP_BLOCKSTATE_CACHE` 控制 |
| `dataComponentPatch` | on | 运行时仍由 `FerriteConfig.DATACOMPONENTS` 控制 |
| `useSmallThreadingDetector` | **off**（opt-in）+ 与 Lithium 互斥 | SKIP，见下 |
| `compactFastMap` | **off**（opt-in） | 运行时仍由 `FerriteConfig.COMPACT_FAST_MAP` 控制 |

| Mod | 原实现 / Mixin | Minecraft Target | 迁移状态 | 备注 |
| --- | --- | --- | --- | --- |
| FerriteCore | `mixin.accessors.VoxelShapeAccess`（`@Accessor` `shape` / `faces`，含 `@Mutable` setter） | `world.phys.shapes.VoxelShape` | DONE | `VoxelShape implements VoxelShapeAccess`；`shape` 去掉 `final` |
| FerriteCore | `mixin.accessors.ArrayVSAccess`（`xs`/`ys`/`zs` get+set） | `world.phys.shapes.ArrayVoxelShape` | DONE | 三个 `DoubleList` 字段去掉 `final` |
| FerriteCore | `mixin.accessors.SliceShapeAccess`（`delegate`/`axis`） | `world.phys.shapes.SliceShape` | DONE | |
| FerriteCore | `mixin.accessors.DiscreteVSAccess`（`getXSize`/`getYSize`/`getZSize`） | `world.phys.shapes.DiscreteVoxelShape` | DONE | 原版已有同名 public 方法，只需 `implements` |
| FerriteCore | `mixin.accessors.SubShapeAccess`（`parent` + 6 个边界） | `world.phys.shapes.SubShape` | DONE | |
| FerriteCore | `mixin.accessors.BitSetDVSAccess`（`storage` + 6 个边界） | `world.phys.shapes.BitSetDiscreteVoxelShape` | DONE | |
| FerriteCore | `mixin.accessors.StateHolderAccess`（`propertyKeys`） | `world.level.block.state.StateHolder` | DONE | |
| FerriteCore | `mixin.fastmap.FastMapStateHolderMixin`（`@Overwrite setValueInternal`、`@Overwrite initializeNeighbors`、`@Redirect` `propertyValues` 数组读、`@Unique` 两个字段） | `world.level.block.state.StateHolder` | DONE | 删除 `private S[][] neighbors`，改为 `int ferritecore_globalTableIndex` + `FastMap<S> ferritecore_globalTable`；`propertyValues` 变为可空、非 final；`getNullableValue` 与 `getValues()` 的数组读改走 `FastMapStateHolderImpl.getPropertyValue` |
| FerriteCore | `mixin.fastmap.StateDefinitionMixin`（`@Redirect Map.forEach`、`@Redirect initializeNeighbors`、`@WrapOperation Builder.build`） | `world.level.block.state.StateDefinition` | DONE | 单属性路径不再建 `S[][]`，多属性路径不再建 `StateCollection`；两条路径都在 state 列表构造完成后调用 `FastMapStateHolderImpl.initializeFastMap` |
| FerriteCore | `impl.BlockStateCacheImpl` 中通过 `MethodHandle` + `IPlatformHooks` 反射读取 `BlockStateBase.cache` | `world.level.block.state.BlockBehaviour$BlockStateBase` | DONE | 反射被删除（本项目禁止反射绕过）；新增 `BlockStateBase#ferritecore_getCache()` 直接返回 `BlockStateCacheAccess` |
| FerriteCore | `mixin.blockstatecache.BlockStateBaseMixin`（`initCache` HEAD + TAIL） | `world.level.block.state.BlockBehaviour$BlockStateBase#initCache` | DONE | 在方法首尾直接调用 `BlockStateCacheImpl.deduplicateCachePre/Post`，受 `DEDUP_BLOCKSTATE_CACHE` 控制 |
| FerriteCore | `mixin.blockstatecache.BlockStateCacheMixin`（`implements BlockStateCacheAccess`，`@Shadow @Mutable` `collisionShape` / `faceSturdy`） | `BlockBehaviour$BlockStateBase$Cache` | DONE | `Cache implements BlockStateCacheAccess`；两个字段去掉 `final` |
| FerriteCore | `mixin.datacomponents.PatchedDataComponentMapMixin`（`applyPatch` / `restorePatch` / `set` / `remove` 的 RETURN 注入） | `core.component.PatchedDataComponentMap` | DONE | 新增 private `saveMemoryIfEmpty()`，在四个方法返回前调用；受 `DATACOMPONENTS` 控制 |
| FerriteCore | `mixin.threaddetec.PalettedContainerMixin` + `util.SmallThreadingDetector` + `ducks.SmallThreadDetectable` | `world.level.chunk.PalettedContainer` | SKIP | 上游 `mixin.threaddetec.Config` 声明 `THREADING_DETECTOR` 为 **opt-in（默认关）** 且 `LithiumSupportState.INCOMPATIBLE`（检测到 Lithium 即禁用）。本项目同时移植 Lithium，其 `mixin.chunk.no_locking.PalettedContainerMixin` 已把 `acquire()`/`release()` 变为空实现并把 `threadingDetector` 置空——即上游冲突判定成立的情形。因此忠实行为是不启用该 module；未引入任何空实现 |
| FerriteCore | `mixin.config.FerriteMixinConfig`、`mixin.config.IPlatformConfigHooks`、`Fabric/NeoForge` 的 `ConfigFileHandler`、`IPlatformHooks`、`PlatformHooks`、`ModMainForge` | — | SKIP | 纯 Mixin runtime / Loader 配置代码 |
| FerriteCore | `hash/*`、`fastmap/*`、`ducks/*`、`impl/*` | — | DONE | 原样移植（`BlockStateCacheImpl` 仅替换上述反射部分） |

### FerriteCore 已知副作用

* `StateDefinition.StateCollection` 及 `EMPTY_NEIGHBORS` 在多属性路径不再被使用（单例状态路径仍
  会把空数组传进 `initializeNeighbors`，与上游一致）。保留原版代码未删除。
* `StateHolder.initializeNeighbors` 现在只允许单例状态调用，非单例调用抛
  `UnsupportedOperationException`，与上游 `@Overwrite` 一致。

---

## Lithium 0.25.3 → MCP 26.2

### 通用处理

* Lithium 的 `common/`（非 mixin）代码与 `api/` 源码集原样移植到
  `src/main/java/net/caffeinemc/mods/lithium/{common,api}`。
* 原 `mixin/**` 下的 accessor / invoker / duck 接口保留在原 package（`net.caffeinemc.mods.lithium.mixin.**`），
  去掉 Mixin 注解后由对应的原版类直接 `implements`。上游在多个 module 里重复声明了同一个 accessor
  （例如 `EntitySectionAccessor` 同时存在于 `util.accessors` 和 `block.hopper`），这些重复声明全部保留，
  原版类同时实现它们（方法签名相同，一份实现即可满足）。
* `lithium.accesswidener` 中列出的可见性放宽全部直接改到原版源码上，并标注
  `// MODIFIED for porting: lithium.accesswidener widened access`。
  受影响：`ChunkMap$TrackedEntity`、`ChunkMap.level`、`ServerChunkCache$MainThreadExecutor`、
  `ServerLevel$EntityCallbacks`、`WorldBorder$BorderExtent`、`PalettedContainer$Data`、
  `FlowingFluid$BlockStatePairKey`、`LevelChunk$RebindableTickingBlockEntityWrapper`、
  `Strategy.LINEAR_PALETTE_FACTORY` / `SINGLE_VALUE_PALETTE_FACTORY`、`Mth.SIN`、`VoxelShape.shape`、
  `CompoundTag(Map)`、`SortedArraySet(int,Comparator)`、`Fluid.isEmpty`（连带 `EmptyFluid.isEmpty`）、
  `WalkNodeEvaluator.getPathTypeFromState`、`RedstoneWireEvaluator.getWireSignal`、
  `ExperimentalRedstoneWireEvaluator.getWireSignal`、`Shapes.findBits`、`SectionStorage.getOrLoad`、
  `PoiRecord.acquireTicket`、`AcquirePoi$JitteredLinearRetry`、`VoxelShape.findIndex`、
  `CubeVoxelShape.findIndex`（`PaletteResize` / `Configuration` / `IndexMerger` 在 26.2 已是 public）。
* `common/config/**`、`mixin/LithiumMixinPlugin`、`common/services/{Services,PlatformMixinOverrides,
  PlatformRuntimeInformation}` 属于 Mixin runtime / Loader 配置，不移植。Lithium 的 172 个 mixin 选项在
  移植版中按上游默认值固定，因此默认关闭的四个选项对应的 module 不移植（见下表 SKIP 行）。
* `common/services/Platform{EntityAccess,ModCompat}` 保留接口，`Services.load` 的 ServiceLoader 间接层
  换成直接引用新增的 `VanillaEntityAccess` / `VanillaModCompat`：
  * `VanillaEntityAccess` = 上游 Fabric 实现（只用 `Level.dragonParts()`，NeoForge 版额外处理的
    `PartEntity` 在本项目不存在）。
  * `VanillaModCompat.canHopperInteractWithApiBlockInventory` 恒返回 `false`——本项目没有 Fabric Transfer
    API / NeoForge Capability 之类的第三方 inventory API，漏斗只可能看到原版 `Container`。这与上游在
    「未安装 fabric-transfer-api-v1 的 Fabric 环境」下的返回值一致，不是为了编译而伪造的实现。
* `LithiumMixinPlugin.DEBUG`（来自默认关闭的 `mixin.debug`）在移植版中由 `LithiumMod.DEBUG` 代替。

### 默认关闭 / 冲突而不移植的 module

| Option | 默认 | 处理 |
| --- | --- | --- |
| `mixin.ai.pathing` | false | SKIP。仅保留 `mixin.ai.pathing.PathfindingContextAccessor` 接口（`common.ai.pathing.PathNodeCache` 引用它），原版类不实现它，`BlockStateFlags.PATH_NOT_OPEN` 的 `isAssignableFrom` 判断因此为 false，与上游关闭该选项时行为一致 |
| `mixin.debug`（含 `mixin.debug.palette`） | false | SKIP。父选项关闭会连带禁用子选项（`LithiumConfig.getEffectiveOptionForMixin` 在链上遇到 disabled 立即返回） |
| `mixin.experimental`（含 `entity.block_caching*`、`entity.item_entity_merging`） | false | SKIP，同上 |
| `mixin.compat.worldedit` | false | SKIP。上游仅在检测到 WorldEdit 时自动启用 |

### Module 迁移状态

| Mod | 原实现 / Mixin | Minecraft Target | 迁移状态 | 备注 |
| --- | --- | --- | --- | --- |
| Lithium | `util.accessors.*`（7 个 accessor/invoker） | `EntitySection`、`ItemEntity`、`Level`、`PersistentEntitySectionManager`、`ServerLevel`、`Strategy`、`TransientEntitySectionManager` | DONE | `Strategy` 的 `@Invoker` 转为转发方法 |
| Lithium | `util.data_storage.LevelMixin` | `world.level.Level` | DONE | `Level implements LithiumData`，`<init>` 尾部创建 `LithiumData.Data` |
| Lithium | `util.section_data_storage.LevelChunkSectionMixin` | `LevelChunkSection` | DONE | 懒创建 `SectionData` |
| Lithium | `util.entity_section_position.*` | `EntitySection`、`EntitySectionStorage#createSection` | DONE | |
| Lithium | `util.chunk_status_tracking.{ChunkHolderMixin,LevelChunkMixin}` | `ChunkHolder#updateFutures`、`LevelChunk#setFullStatus` | DONE | 注入点为 `updateFutures` 中第 7 个 `FullChunkStatus.isOrAfter` 调用之前 |
| Lithium | `util.chunk_access.{LevelReaderMixin,PathNavigationRegionMixin}` | `LevelReader`、`PathNavigationRegion` | DONE | `LevelReader` 直接 `extends ChunkView` 并提供 default 实现 |
| Lithium | `util.block_entity_retrieval.LevelMixin` | `Level` | DONE | |
| Lithium | `util.world_border_listener.WorldBorderMixin` | `WorldBorder` | DONE | `addListener` 对 `WorldBorderListenerOnce` 改走 multi-listener；`tick()` 在 extent 变化时通知 |
| Lithium | `util.in_world_tracking.entity.{EntityMixin,LivingEntityMixin}` | `Entity`、`LivingEntity` | DONE | |
| Lithium | `util.entity_collection_replacement.ClassInstanceMultiMapMixin` | `ClassInstanceMultiMap` | DONE | |
| Lithium | `util.entity_movement_tracking.*` | `EntitySection`、`PersistentEntitySectionManager$Callback`、`ServerLevel`、`PersistentEntitySectionManager` | DONE | `onMove` 中的两次通知顺序按原 mixin 保留（先旧 section、后新 section） |
| Lithium | `util.block_tracking.*` | `BlockBehaviour$BlockStateBase`、`LevelChunkSection`、`LevelChunkSection$1BlockCounter` | DONE | 局部类 `BlockCounter` 直接实现 `LithiumBlockCounter` |
| Lithium | `util.inventory_change_listening.*` + fabric 变体 | `BaseContainerBlockEntity`、`BlockEntity`、`Barrel/Chest/Dispenser/Hopper/ShulkerBox/AbstractFurnace/BrewingStand BlockEntity` | DONE | 采用 Fabric 变体（只用原版 API）：`BlockEntity#setBlockState` → `SetBlockStateHandlingBlockEntity`，`ChestBlockEntity` 覆写它处理单/双箱切换 |
| Lithium | `util.inventory_comparator_tracking.{BlockEntityMixin,DiodeBlockMixin}` | `BlockEntity`、`DiodeBlock#onPlace` | DONE | |
| Lithium | `util.item_component_and_count_tracking.*` | `ItemStack`、`ItemEntity`、`PatchedDataComponentMap` | DONE | 与 FerriteCore 对 `PatchedDataComponentMap` 的修改合并：FerriteCore 在 `set/remove/applyPatch/restorePatch` 返回前收缩空 patch，Lithium 在 `ensureMapOwnership` 开头发通知，互不影响 |
| Lithium | `util.initialization`（fabric `FuelValuesMixin`） | `FuelValues#vanillaBurnTimes` | DONE | 采用 Fabric 变体；NeoForge 变体挂在 `DataMapHooks` 上，本项目没有该类 |
| Lithium | `world.block_entity_ticking.sleeping`（核心 5 个） | `LevelChunk`（`RebindableTickingBlockEntityWrapper` + `updateBlockEntityTicker`）、`BlockEntity#setChanged`、`Level#tickBlockEntities`、`ServerLevel#dumpBlockEntityTickers` | DONE | 睡眠 ticker 的 `getPos()` 返回 null，两处 null 保护已加 |
| Lithium | `minimal_nonvanilla.collisions.empty_space` 的 accessor/invoker | `BitSetDiscreteVoxelShape`、`ArrayVoxelShape` | DONE | `ArrayVoxelShape(DiscreteVoxelShape,DoubleList×3)` 构造器改为 public |
| Lithium | `ai.useless_sensors.{BrainAccessor,SensorAccessor}` | `Brain`、`Sensor` | DONE | |
| Lithium | `block.hopper` 的 accessor / `InventoryAccessors` / `ContainerMixin` / `ChiseledBookShelfBlockEntityMixin` | `CompoundContainer`、`NonNullList`、`EntitySection`、`Entity`、`Container`、`ChiseledBookShelfBlockEntity`、7 个容器方块实体 + `AbstractMinecartContainer` | DONE | 容器方块实体实现 `LithiumInventory`（暴露 `items` / `itemStacks`） |
| Lithium | `math.fast_blockpos.{BlockPosMixin,DirectionMixin}` | `BlockPos`（12 个方向偏移方法）、`Direction`（`getStepX/Y/Z` + 提升出的 offset 字段） | DONE | |
| Lithium | `math.fast_util.{AABBMixin,AxisCycleDirectionMixin,DirectionMixin}` | `AABB#min/max(Axis)`、`AxisCycle.FORWARD/BACKWARD#cycle`、`Direction#getOpposite/getRandom` | DONE | 26.2 的 `Direction` 常量按 3d-data 顺序声明，故 `VALUES[oppositeIndex]` 与 `from3DDataValue(oppositeIndex)` 等价（已核对） |
| Lithium | `math.sine_lut.MthMixin` | `Mth.sin/cos`、`Mth.SIN`、`Mth.<clinit>` | DONE | `SIN` 去掉 `final` 以便初始化 `CompactSineLUT` 后置空 |
| Lithium | `cached_hashcode.FlowingFluid$BlockStatePairKeyMixin` | `FlowingFluid$BlockStatePairKey` | DONE | 26.2 中该类是 record；record 无法在源码里声明额外实例字段，故按同样的访问器/equals/hashCode 语义改写为普通 final 类以便缓存 hash |
| Lithium | `alloc.chunk_random.{LevelMixin,ServerLevelMixin}` | `Level`（`ChunkRandomSource`）、`ServerLevel#tickChunk` | DONE | 与 `random_block_ticking` 合并改写 `tickChunk` |
| Lithium | `alloc.composter.ComposterMixin`（3 个内部容器） | `ComposterBlock$EmptyContainer/InputContainer/OutputContainer#getSlotsForFace` | DONE | |
| Lithium | `alloc.deep_passengers.EntityMixin` | `Entity#getIndirectPassengers(Stream)/getSelfAndPassengers/getPassengersAndSelf` | DONE | |
| Lithium | `alloc.entity_iteration.*` | `ClassInstanceMultiMap`（accessor）、`EntitySection#getEntities(AABB,…)` | DONE | 上游 accessor 与原版 `getAllInstances()` 同名同签名；26.2 中原版该方法无任何调用者，因此由 accessor 语义（直接返回内部 list）接管 |
| Lithium | `alloc.entity_tracker.ChunkMap$TrackedEntityMixin` | `ChunkMap$TrackedEntity.seenBy` | DONE | |
| Lithium | `alloc.enum_values.*`（3 个） | `PistonBaseBlock#getNeighborSignal`、`PistonStructureResolver#addBranchingBlocks`、`RedStoneWireBlock#affectNeighborsAfterRemoval/checkCornerChangeAt` | DONE | |
| Lithium | `alloc.explosion_behavior.EntityBasedExplosionDamageCalculatorMixin` | `EntityBasedExplosionDamageCalculator#getBlockExplosionResistance` | DONE | |
| Lithium | `alloc.nbt.CompoundTagMixin`（含 `$Type`） | `CompoundTag()`、`CompoundTag#copy`、`CompoundTag$TYPE.loadCompound` | DONE | |
| Lithium | `collections.entity_filtering.ClassInstanceMultiMapMixin` | `ClassInstanceMultiMap#find` | DONE | 该 @Overwrite 移除了 `isAssignableFrom` 调用，因此 `block.hopper.ClassInstanceMultiMapMixin`（`require=0/expect=0`）无事可做，未移植该重定向 |
| Lithium | `world.chunk_ticking.random_block_ticking.*`（4 个） | `ServerLevel#tickChunk`、`LevelChunkSection`、`LevelChunkSection$1BlockCounter`、`PalettedContainer#count` | DONE | `@Local(ordinal=5)` 已核对为 `minYInSection`（block 坐标），不是 section index |
| Lithium | `world.chunk_ticking.precipitation.ServerLevelMixin` | `ServerLevel#tickPrecipitation` | DONE | |
| Lithium | `world.chunk_ticking.spread_ice.BiomeMixin` | `Biome#shouldFreeze` | DONE | 三个 `@Redirect` 合并为「从已取得的 BlockState 推导 FluidState」 |
| Lithium | `shapes.blockstate_cache.BlockMixin` | `Block#isShapeFullBlock` | DONE | |
| Lithium | `shapes.precompute_shape_arrays.{CubePointRangeMixin,CubeVoxelShapeMixin}` | `CubePointRange`、`CubeVoxelShape` | DONE | |
| Lithium | `shapes.shape_merging.ShapesMixin` | `Shapes#createIndexMerger` | DONE | `IndirectMerger` → `LithiumDoublePairList` |
| Lithium | `shapes.optimized_matching.ShapesMixin` | `Shapes#joinIsNotEmpty(VoxelShape,VoxelShape,BooleanOp)` | DONE | |
| Lithium | `shapes.specialized_shapes.{ShapesMixin,VoxelShapeMixin}` | `Shapes.BLOCK/EMPTY/INFINITY`、`Shapes#create`、`VoxelShape#collideX/findIndex` | DONE | 三个全局 shape 直接用特化实现初始化（上游用 clinit 追加覆盖，终态相同且避免了中途的临时对象） |
| Lithium | `block.hopper` 主体（`HopperBlockEntityMixin` 902 行、`HopperBlockMixin`、`AbstractContainerMenuMixin`、`BlockBehaviourMixin`、`ComposterMixin`、`AbstractChestBoatMixin`、`EntityDataAccessorMixin`、`OldMinecartBehaviorMixin`，以及 fabric 的 `block.hopper.LevelMixin`） | `HopperBlockEntity`、`HopperBlock`、`AbstractContainerMenu#getRedstoneSignalFromContainer`、`BlockBehaviour#updateShape`、`ComposterBlock` 的三个内部容器、`AbstractChestBoat#rideTick`、`EntityDataAccessor#setData`、`OldMinecartBehavior#moveAlongTrack`、`Level#setBlock` | DONE | 见下方「block.hopper 移植说明」 |
| Lithium | `world.combined_heightmap_update.{HeightmapAccessor,LevelChunkMixin}` | `Heightmap`、`LevelChunk#setBlockState` | DONE | 四张 heightmap 的更新合并为一次遍历 |
| Lithium | `world.inline_block_access.{LevelChunkMixin,LevelMixin}` | `LevelChunk#getBlockState/getFluidState`、`Level#getBlockState` | DONE | 上游同时去掉了 debug 世界分支与 crash-report try/catch；已核对 `DebugLevelSource#applyBiomeDecoration` 会把 y=60/70 的方块真正写进 section，且 debug 世界不允许玩家改方块，故行为一致（已在代码注释中说明） |
| Lithium | `world.inline_height.{LevelChunkMixin,LevelMixin}` | `Level`、`LevelChunk` 的 `LevelHeightAccessor` 方法 | DONE | `Level` 缓存 dimension 的高度上下限 |
| Lithium | `world.raycast.{BlockGetterMixin,ClipContextAccessor}` | `BlockGetter#clip`、`ClipContext` | DONE | 只有 `ServerLevel` / 客户端 `Level` 走缓存 chunk 的快路径，其它 Level 实现保留原版 lambda（上游为 Create/Ponder 兼容性所留） |
| Lithium | `world.temperature_cache.BiomeMixin` | `Biome#getTemperature` | DONE | 去掉 ThreadLocal 温度缓存；`temperatureCache` 字段与上游一样保留但不再被读取 |
| Lithium | `world.tick_scheduler.LevelChunkTicksMixin` | `world.ticks.LevelChunkTicks` | DONE | 整类替换；原版两个集合字段在源码移植中直接删除（上游只能置 null） |
| Lithium | `chunk.no_locking.{PalettedContainerMixin,LevelChunkSectionMixin}` | `PalettedContainer#acquire/release`、`LevelChunkSection#setBlockState(int,int,int,BlockState)` | DONE | 与 FerriteCore `threaddetec` 的互斥关系即源于此 |
| Lithium | `chunk.no_validation.{SimpleBitStorageMixin,ZeroBitStorageMixin}` | `SimpleBitStorage`、`ZeroBitStorage` 的 `get/set/getAndSet` | DONE | |
| Lithium | `chunk.palette.StrategyMixin`（含 `Strategy$1` / `Strategy$2`） | `Strategy.createForBlockStates/createForBiomes` 的 `getConfigurationForBitCount` | DONE | 3 bit 起改用 `LithiumHashPalette` |
| Lithium | `chunk.serialization.{PalettedContainerMixin,SimpleBitStorageMixin}` | `PalettedContainer#pack/#count`、`SimpleBitStorage` | DONE | `pack` 用两参 `PackedData`（`bitsPerEntry = -1`），只是跳过读取时的可选位数校验，序列化数据不变；`count` 与 `random_block_ticking` 的 minisection 统计合并（serialization 优先级 50，在内层） |
| Lithium | `chunk.entity_class_groups.{ClassInstanceMultiMapMixin,ClientLevelMixin}` | `ClassInstanceMultiMap`、`ClientLevel` | DONE | |
| Lithium | `collections.attributes.AttributeMapMixin` | `AttributeMap` | DONE | |
| Lithium | `collections.block_entity_tickers.LevelChunkMixin` | `LevelChunk.tickersInLevel` | DONE | |
| Lithium | `collections.brain.BrainMixin` | `Brain` 的 3 个集合 | DONE | 直接替换字段初始化器（上游在 5 参构造器 RETURN 处换掉；两者终态相同，且无参构造器也一并受益） |
| Lithium | `collections.chunk_tickets.SortedArraySetMixin` | `SortedArraySet#removeIf` | DONE | |
| Lithium | `collections.entity_by_type.ClassInstanceMultiMapMixin` | `ClassInstanceMultiMap.byClass` | DONE | |
| Lithium | `collections.entity_ticking.EntityTickListMixin` | `EntityTickList#ensureActiveIsNotIterated` | DONE | |
| Lithium | `collections.mob_spawning.{MobSpawnSettingsMixin,WeightedListMixin}` | `MobSpawnSettings`、`WeightedList` | DONE | |
| Lithium | `world.block_entity_ticking.chunk_tickable.LevelMixin` | `Level#tickBlockEntities` | DONE | 与 sleeping 模块的 null 位置保护合并 |
| Lithium | `world.block_entity_ticking.sleeping.*`（全部 12 个子 module） | `BrewingStand/Campfire/Chest/EnderChest/Crafter/AbstractFurnace/Hopper/ShulkerBox/SculkSensor/SculkShrieker/SculkCatalyst BlockEntity`、`SculkSensorBlock`、`SculkShriekerBlock`、`CalibratedSculkSensorBlock`、`SculkSpreader`、`VibrationSystem$Listener` | DONE | 每个方块实体实现 `SleepingBlockEntity`，闲置时把 tick wrapper 里的 ticker 换成空实现；漏斗额外覆写 `lithium$startSleeping` 以保持与原版一致的冷却观感 |
| Lithium | `world.chunk_access.{ChunkHolderMixin,GenerationChunkHolderAccessor,LevelMixin,ServerChunkCacheMixin}` | `ChunkHolder`、`Level` 的 chunk 查询、`ServerChunkCache#getChunk/#clearCache/#tick` | DONE | `ServerChunkCache` 增加自己的 4 项「位置+status 编码为 long」查询缓存；每 tick 每 chunk 只创建一次 ticket。26.2 的 `GenerationChunkHolder#getChunkIfPresent` 与上游 accessor（`futures` + `isStatusDisallowed`）语义完全一致，故不再需要该 accessor（已核对 `getChunkIfPresentUnchecked` 用 `getNow` 实现） |
| Lithium | `world.explosions.block_raycast{,.skip_air{,.no_air_counting}}`、`world.explosions.entity_raycast` | `ServerExplosion`、`ClipContext`、`ServerLevel#explode` | DONE | 方块缓存 + 跳过空气段；`explode()` 的方块计数把跳过的空气重新算回去 |
| Lithium | `world.game_events.dispatch.{GameEventDispatcherMixin,LevelChunkMixin}` | `GameEventDispatcher`、`LevelChunk` | DONE | `gameEventListenerRegistrySections` 变为懒创建 |
| Lithium | `world.block_entity_ticking.world_border.DirectBlockEntityTickInvokerMixin` | `LevelChunk$BoundTickingBlockEntity#tick` | DONE | 世界边界不移动时缓存边界判定，边界形状变化时失效 |
| Lithium | `entity.collisions.block_effects` | `InsideBlockEffectApplier$StepBasedCollector` | DONE | |
| Lithium | `entity.collisions.intersection.{EntityGetterMixin,LevelMixin}` | `EntityGetter`、`Level#noCollision/#findSupportingBlock` | DONE | |
| Lithium | `entity.collisions.movement.EntityMixin` | `Entity#collide` | DONE | 整体替换为 `lithium$collideMovement`（约 150 行） |
| Lithium | `entity.collisions.unpushable_cramming.*`（7 个） | `Entity`、`LivingEntity`、`EntitySection`、`EntitySelector#pushableBy`、`Level#getPushableEntities`、`AbstractBoat#tick`、`OldMinecartBehavior#pushAndPickupEntities` | DONE | `EntitySection` 维护「可能可推」实体的掩码列表；爬梯类实体缓存脚下方块 |
| Lithium | `entity.equipment_tracking{,.enchantment_ticking,.equipment_changes}` | `EntityEquipment`、`LivingEntity#baseTick/#collectEquipmentChanges/#detectEquipmentUpdates` | DONE | `EntityEquipment` 订阅每个 `ItemStack` 的变化，跳过无附魔时的 tick 与无变化时的装备比较 |
| Lithium | `entity.fast_elytra_check`、`entity.fast_hand_swing`、`entity.fast_powder_snow_check` | `LivingEntity` | DONE | |
| Lithium | `entity.fast_retrieval.EntitySectionStorageMixin` | `EntitySectionStorage#forEachAccessibleNonEmptySection` | DONE | |
| Lithium | `entity.framed_maps.MapItemSavedDataMixin` | `MapItemSavedData` | DONE | |
| Lithium | `entity.inactive_navigations.*`（6 个） | `Mob`、`LivingEntity#stopRiding`、`HappyGhast`、`PathNavigation`、`ServerLevel`（含 `EntityCallbacks`） | DONE | `ServerLevel#sendBlockUpdated` 只遍历「当前有 path 的 navigation」集合；`navigatingMobs` 换成 `ReferenceOpenHashSet` |
| Lithium | `entity.projectile_projectile_collisions.*`（8 个） | 7 个抛射物类的 `canHitEntity` 传参点 + `ProjectileUtil#getEntityHitResult` | DONE | 谓词包进 `ProjectileCanHitEntityPredicate`，实体查找据此只访问「可能被该抛射物命中」的类组 |
| Lithium | `entity.replace_entitytype_predicates.*`（4 个） | `ArmorStand`、`GolemRandomStrollInVillageGoal`、`LlamaFollowCaravanGoal`、`OldMinecartBehavior` | DONE | |
| Lithium | `entity.sprinting_particles.EntityMixin` | `Entity` | DONE | |
| Lithium | `ai.non_poi_block_search.*`（4 个） | `HoglinSpecificSensor`、`PiglinSpecificSensor`、`MoveToBlockGoal`、`RemoveBlockGoal` | DONE | `MoveToBlockGoal` 增加 chunk-aware 搜索：先用 `LevelChunkSection#maybeHas` 排除不含目标方块的 section |
| Lithium | `ai.poi.{PoiManagerMixin,PoiSectionMixin,SectionStorageMixin}` | `PoiManager`、`PoiSection`、`SectionStorage` | DONE | `SectionStorage` 记录每个 chunk column 有条目的 section（`BitSet`），POI 查询改为无 stream 且只访问球形半径内的 section；`PoiSection.byType` 仍是 `HashMap`/`HashSet`（迭代顺序游戏内可观测） |
| Lithium | `ai.poi.fast_portals.{PoiManagerMixin,PortalForcerMixin}` | `PoiManager#ensureLoadedAndValid`、`PortalForcer#findClosestPortalPosition` | DONE | 预载判定按 z 行内「最低 y section、其次 x」排序，保持与原版一致的 chunk 加载顺序 |
| Lithium | `ai.poi.tasks.{AcquirePoiMixin,LocateHidingPlaceMixin,RaiderEntityAttackHomeGoalMixin}` | `AcquirePoi`、`LocateHidingPlace`、`Raider$RaiderMoveThroughVillageGoal#hasSuitablePoi` | DONE | `AcquirePoi` 用「无副作用谓词驱动搜索 + 事后补齐副作用」拆分，保持与原版相同的重试标记更新范围 |
| Lithium | `ai.raid.{RaidMixin,RaiderMixin,Raider$ObtainRaidLeaderBannerGoalMixin}` | `Raid#tick/#updateBossbar`、`Raider`（`ALLOWED_ITEMS`、`isCaptain`、`pickUpItem`、`cannotPickUpBanner`） | DONE | boss 条更新延迟到下一 tick；不祥旗帜使用 `LithiumData` 里缓存的实例 |
| Lithium | `ai.sensor.replace_streams.tempting`、`ai.sensor.secondary_poi` | `TemptingSensor#doTick`、`SecondaryPoiSensor#doTick` | DONE | |
| Lithium | `ai.task.launch.BrainMixin` | `Brain`（`startEachNonRunningBehavior`、`getRunningBehaviors`、`addActivity`、`removeAllBehaviors`、`setActiveActivity`、`stopAll`、`tickEachRunningBehavior`） | DONE | 缓存「当前 activity 的 behavior 列表」与「正在运行的 behavior」掩码列表 |
| Lithium | `ai.task.memory_changes.{BehaviorMixin,BrainMixin,MemorySlotMixin}` | `Behavior`、`Brain`、`MemorySlot` | DONE | `Brain` 维护 memory「存在性」修改计数，`Behavior#hasRequiredMemories` 据此缓存结果 |
| Lithium | `ai.task.replace_streams.*`（4 个） | `GateBehavior`、`GateBehavior$RunningPolicy` 的两个常量、`ShufflingList` | DONE | 26.2 的 `ShufflingList` 已实现 `Iterable<U>`，因此只需追加 `WeightedListIterable` 接口，不需要再实现一个迭代器 |
| Lithium | `ai.task.run.long_jump_weighted_choice.LongJumpToRandomPosMixin` | `LongJumpToRandomPos#start/#getJumpCandidate` | DONE | |
| Lithium | `ai.useless_behaviors{,.nitwit_job_search}` | `Brain#addActivity`、`VillagerGoalPackages#getCorePackage` | DONE | 傻子村民的 job site `AcquirePoi` 换成 sentinel，`addActivity` 直接跳过 sentinel |
| Lithium | `block.flatten_states.FluidStateMixin` | `FluidState` | DONE | 缓存 `Fluid#isEmpty` |
| Lithium | `block.fluid.flow.FlowingFluidMixin` | `FlowingFluid#getSpread/#canPassThrough/#isWaterHole/#canHoldAnyFluid` | DONE | `getSpread` 增加快速路径（只有一个可流入方向时不做 BFS），搜索半径 > 5 时回落到原版实现 |
| Lithium | `block.moving_block_shapes.{PistonMovingBlockEntityMixin,VoxelShapeMixin}` | `PistonMovingBlockEntity#getCollisionShape`、`VoxelShape` | DONE | 预计算 18 种「活塞底座 + 移动活塞头」合并 shape；其余情况把 offset+simplify 结果缓存在 `VoxelShape` 上 |
| Lithium | `block.redstone_wire.*`（3 个） | `DefaultRedstoneWireEvaluator#calculateTargetStrength`、`RedStoneWireBlock#getBlockSignal`、`RedstoneWireEvaluator#getIncomingWireSignal` | DONE | 三处都整体替换为 `RedstoneWirePowerCalculations`；替换实现从不向红石线询问信号，因此原版为此设置的 `shouldSignal` 开关不再需要 |
| Lithium | `block_pattern_matching.{BlockPatternMixin,EnderDragonFightMixin}` | `BlockPattern`、`EnderDragonFight` | DONE | |
| Lithium | `client_tick.entity.base_tick.unused_ambient_sound` | `Mob#baseTick` | DONE | Breeze 例外（客户端确实会播放声音） |
| Lithium | `client_tick.entity.base_tick.unused_water_supply` | `AgeableWaterCreature#baseTick` | DONE | |
| Lithium | `client_tick.entity.unused_brain.*`（5 个） | `LivingEntity#<init>`、`Brain`、`MemorySlot#set`、`Allay#mobInteract`、`CopperGolem#<init>` | DONE | 客户端共用一个 dummy brain，其 memory 查询返回共享 dummy slot，写入被忽略 |
| Lithium | `client_tick.particle.biome_particles.{AmbientParticleSettingsMixin,ClientLevelMixin}` | `AmbientParticle`、`ClientLevel#doAnimateTick` | DONE | 先按「全局最大粒子概率」摇随机数，再查 biome；`canSpawn` 相应地除以该最大值，合成概率不变 |
| Lithium | `gen.cached_generator_settings.NoiseBasedChunkGeneratorMixin` | `NoiseBasedChunkGenerator` | DONE | |
| Lithium | `minimal_nonvanilla.ai.sensor.frog_attackables` | `FrogAttackablesSensor` | DONE | |
| Lithium | `minimal_nonvanilla.spawning.*` | `EntitySection`、`EntitySectionStorage`、`PersistentEntitySectionManager`、`ServerLevel`、`ServerChunkCache` | DONE | |
| Lithium | `minimal_nonvanilla.world.block_entity_ticking.support_cache.*` | `BlockEntity`、`LevelChunk`（`BoundTickingBlockEntity#tick`） | DONE | |
| Lithium | `minimal_nonvanilla.world.expiring_chunk_tickets.TicketStorageMixin` | `TicketStorage` | DONE | |
| Lithium | `profiler.ProfilerMixin` | `util.profiling.Profiler` | DONE | |
| Lithium | `shapes.lazy_shape_context.EntityCollisionContextMixin` | `EntityCollisionContext` | DONE | |
| Lithium | fabric `collections.poi_types.PoiTypesMixin` | `PoiTypes.TYPE_BY_STATE` | DONE | 换成 `Reference2ReferenceOpenHashMap`（BlockState 是 interned 的）；NeoForge 源码集没有这个 module |
| Lithium | `minimal_nonvanilla.collisions.empty_space.*` | `BitSetDiscreteVoxelShape`、`ArrayVoxelShape`、`Level#findFreePosition` | DONE | `ArrayVoxelShape(DiscreteVoxelShape,DoubleList×3)` 构造器改为 public |
| Lithium | `ai.useless_sensors.{baby_specific_sensors,goat_item_sensor}` | `AgeableMob`、`Goat` | DONE | 成年个体 / 非投喂状态直接跳过对应 sensor |



## Sodium 0.9.1 → MCP 26.2

### 源码集处理

| Sodium 源码集 | 文件数 | 处理 |
| --- | --- | --- |
| `common/src/api`（`net.caffeinemc.mods.sodium.api.**`） | 52 | DONE，原样移植 |
| `common/src/boot`（`client.{compatibility,console,platform}.**`） | 45 | DONE，原样移植（GPU 驱动检测 / 兼容性 workaround / 控制台消息） |
| `common/src/main` 的非 mixin 部分（`client.**`） | 401 | DONE，原样移植 |
| `common/src/main/java/.../mixin/**` | 80 | 已完成：accessor 转普通接口，其余 mixin 全部转为源码修改（见下） |
| `common/src/desktop`（`sodium.desktop.LaunchWarn` 等） | 4 | SKIP。这是「双击 jar 时弹出的提示窗口」，由 jar 的 `Main-Class` 触发，属于打包产物，`client`/`boot` 均未引用 |
| `fabric/src/main`、`neoforge/src/{main,mod}` | 17 + 23 | 服务实现按下表用 vanilla 实现替换；loader 入口点 / config 加载 / mod metadata 不移植 |
| `frapi/src/main`（Fabric Rendering API 集成） | 18 | SKIP。本项目没有 FRAPI；`FRAPIProvider` 上游本身就用 `Services.loadOr(..., () -> () -> {})` 退化为空实现 |

### 平台服务（`client.services.*`）

上游用 `ServiceLoader`（`Services.load`）在 Fabric / NeoForge 实现间选择。移植版删掉 `Services`，改为直接引用新增的
`client.services.vanilla.*` 实现（与 Lithium 的 `VanillaEntityAccess` 处理一致）：

| 服务 | 移植版实现 | 说明 |
| --- | --- | --- |
| `PlatformRuntimeInformation` | `VanillaRuntimeInformation` | 游戏目录取自 `Minecraft.gameDirectory`，config 目录为其下的 `config/`；无 mod 列表、无 early loading screen、无 refmap |
| `PlatformBlockAccess` | `VanillaBlockAccess` | `normalShade` 与 Fabric 实现逐行相同；`shouldShowFluidOverlay` 换成原版 `FluidRenderer` 自己的判断（`HalfTransparentBlock` / `LeavesBlock`，也正是 Fabric API 该方法的默认实现）；`platformHasBlockData()` = false |
| `PlatformLevelAccess` | `VanillaLevelAccess` | `BlockEntity#getRenderData` 是 Fabric API 的扩展，原版没有；原版也没有 auxiliary light manager，两者均返回 null（`platformHasBlockData()` 为 false，所以前者根本不会被调用） |
| `PlatformLevelRenderHooks` | `VanillaLevelRenderHooks` | 三个方法都只是派发 loader 渲染事件 / NeoForge chunk mesh appender，无 loader 时为空——与 Fabric 实现相同 |
| `PlatformModelAccess` | `VanillaModelAccess` | 与 Fabric 实现相同，唯一区别是 `createMutableColorProvider()` 返回 null：该 provider 只用于暴露 loader 的「按 BlockState 动态取色」工厂（Fabric `BlockColorRegistry` / NeoForge dynamic provider），原版方块染色全部来自 `BlockColors`，`ColorProviderRegistry` 已经读取；`BlockRenderer` 对 null 的处理就是「没有平台回退」 |
| `FluidRendererFactory` | `VanillaFluidRendererFactory` | 两个 loader 实现都只是把流体渲染绕经 loader 的 fluid render handler 间接层以便 mod 覆盖；无 loader 时直接用 `DefaultFluidRenderer` + 原版 `FluidStateModelSet` / `BlockTintSource`，即 Fabric 实现在「无 mod 覆盖」时的等价路径。`FabricColorProviders#adapt` 只用原版 API，原样并入 |
| `PlatformModelEmitter` | `DefaultModelEmitter`（上游 common 自带） | 上游即 `loadOr(..., DefaultModelEmitter::new)` |
| `FRAPIProvider` | 空实现 | 同上 |

### accesswidener

`common/src/main/resources/sodium-common.accesswidener` 中的放宽全部直接改到原版源码上，标注
`// MODIFIED for porting: sodium-common.accesswidener widened access`。受影响：
`SpriteContents$AnimatedTexture`（含 `frames`、`interpolateFrames`）、`SpriteContents$FrameInfo`、`Stitcher$Holder`、
`Biome$ClimateSettings`、`SectionBufferBuilderPool(List)`、`BakedSheetGlyph$EffectInstance`、`PoseStack$Pose.trustedNormals`、
`CloudRenderer$RelativeCameraPos`、`GlDevice`、`GrassColor.pixels`、`FoliageColor.pixels`、
`ItemModelGenerator.{MIN_Z,MAX_Z,UV_SHRINK,isTransparent,SideDirection,SideDirection#isHorizontal}`。
（`ModelPart$Vertex` / `ModelPart$Polygon` / `PalettedContainer$Data` 在 26.2 已经是 public，无需改动。）

### accessor / invoker mixin

| 原 Mixin | Minecraft / blaze3d Target | 迁移状态 |
| --- | --- | --- |
| `mixin.core.GlRenderPassAccessor` | `com.mojang.blaze3d.opengl.GlRenderPass` | DONE |
| `mixin.core.GpuDeviceAccessor` | `blaze3d.systems.GpuDevice` | DONE |
| `mixin.core.RenderPassAccessor` | `blaze3d.systems.RenderPass` | DONE |
| `mixin.core.VulkanRenderPassAccessor` | `blaze3d.vulkan.VulkanRenderPass` | DONE |
| `mixin.core.render.texture.TextureAtlasAccessor` | `TextureAtlas#getWidth/#getHeight` | DONE |
| `mixin.core.render.world.EntityRendererAccessor` | `EntityRenderer#getBoundingBoxForCulling` | DONE |
| `mixin.features.gui.OptionsAccessor` | `Options.exclusiveFullscreenFromStartup` | DONE |
| `mixin.features.gui.hooks.debug.DebugScreenEntriesAccessor` | `DebugScreenEntries.ENTRIES_BY_ID` | DONE，原版新增 `sodium$getEntries()` 静态方法，accessor 接口转发 |
| `mixin.features.textures.NativeImageAccessor` | `blaze3d.platform.NativeImage.pixels` | DONE |

### 不移植的 Mixin runtime

`mixin.SodiumMixinPlugin`、`mixin.MixinOption`、`client.data.config.MixinConfig`、`client.services.PlatformMixinOverrides`
属于 Mixin 配置装载逻辑，本项目不使用 Mixin，全部不移植（它们只被 `SodiumMixinPlugin` 引用）。

### 唯一的构建改动

`pom.xml` 中 `org.ow2.asm:asm` 由 `runtime` scope 改为默认（compile）scope。Sodium 的
`client.render.vertex.serializers.generated.VertexSerializerFactory` 用 ASM 在运行时生成顶点格式转换器
（这是 Sodium 自身的实现，不是用来绕过 Mixin→源码的转换），因此编译期也需要该库。未新增依赖、未改版本。

### Mixin → 源码修改（全部完成）

| 原 Mixin package | Minecraft / blaze3d Target | 说明 |
| --- | --- | --- |
| `core.MinecraftMixin` | `Minecraft`（`<init>`、`onGameLoadFinished`、`reloadResourcePacks`） | core shader 资源包检查、config 注册、Sodium 图标注册、独占全屏默认值选择 |
| `core.WindowMixin` | `GlBackend#setWindowHints` | 可选的 no-error GL context |
| `core.VulkanPipelineMixin` | `VulkanRenderPipeline#compile` | Sodium 自己的 pipeline 需要 push constant range |
| `core.gui.LevelLoadTrackerMixin` | `LevelLoadTracker$WaitingForPlayerChunk#isReady` | 用眼睛位置而不是脚下位置判断，避免加载界面卡住 |
| `core.model.colors.BlockColorsMixin` | `BlockColors#register` | 记录方块 → tint source 的映射，并标记被 mod 覆盖的原版方块 |
| `core.render.BlockEntityTypeMixin` | `BlockEntityType` | Sodium API 的 render predicate |
| `core.render.TextureAtlasMixin` | `TextureAtlas#upload` | sprite finder 缓存失效 |
| `core.render.VertexFormatMixin` | `VertexFormat#<init>` | 顶点格式全局 id |
| `core.render.frustum.{CameraMixin,FrustumMixin,GameRendererAccessor}` | `Camera#createProjectionMatrixForCulling`、`Frustum`、`GameRenderer` | 剔除矩阵加上眩晕/传送门的画面扭曲（修复剔除不受眩晕影响的 bug）；`Frustum` 变成 `ViewportProvider` |
| `core.render.immediate.consumer.*`（3） | `BufferBuilder`、`SpriteCoordinateExpander`、`SheetedDecalTextureGenerator` | 批量顶点写入 / UV 重映射 / overlay UV 生成 |
| `core.render.world.LevelRendererMixin` | `LevelRenderer` | **核心接入点**：`prepareChunkRenders`、`invalidateCompiledGeometry`、`hasRenderedAllSections`、`isSectionCompiledAndVisible` 全部改为转交 `SodiumWorldRenderer`；原版 `SectionRenderDispatcher` / `ViewArea` 换成 `Ignoring*` 空实现，不再分配任何区块缓冲 |
| `core.render.world.LevelExtractorMixin` | `LevelExtractor` | 地形剔除、脏区/脏 section 更新、可见方块实体提取、`countRenderedSections`、`sectionStatistics` 全部转交 Sodium；`applyFrustum` 调用被取消 |
| `core.render.world.ChunkSectionsToRenderMixin` | `ChunkSectionsToRender#renderGroup` | 因需要 5 个可变字段，该 record 改写为 final class（`equals`/`hashCode`/`toString` 无调用者） |
| `core.render.world.{GameRendererMixin,FogRendererMixin}` | `GameRenderer#renderLevel`、`FogRenderer#setupFog` | 捕获投影矩阵与雾参数供区块渲染使用 |
| `core.render.world.{ViewAreaMixin,RenderBuffersMixin}` | `ViewArea` 的 section 工厂、`RenderBuffers#<init>` | 容忍 null dispatcher；不分配原版 section buffer 池 |
| `core.render.world.ParticleFeatureRendererMixin` | `QuadParticleFeatureRenderer#executeGroup` | 显式绑定 `Globals`/`Lighting`（原版依赖从被替换掉的原版地形渲染继承） |
| `core.render.world.{FrustumAccessor,EntityRendererAccessor}` | `Frustum.matrix`、`EntityRenderer#getBoundingBoxForCulling` | accessor |
| `core.world.biome.ClientLevelMixin` | `ClientLevel#<init>` | 捕获 biome zoom seed |
| `core.world.chunk.{PalettedContainerMixin,SimpleBitStorageMixin,ZeroBitStorageMixin}` | `PalettedContainer`、`SimpleBitStorage`、`ZeroBitStorage` | 整块解包到平坦数组 |
| `core.world.map.*`（3） | `ClientChunkCache`、`ClientLevel#unload`、`ClientPacketListener` | chunk tracker（方块数据 / 光照数据到位情况） |
| `features.gui.hooks.console.GameRendererMixin` | `GameRenderer#render` | Sodium 控制台覆盖层 |
| `features.gui.hooks.debug.*`（5） | `Minecraft#renderFrame`、`DebugScreenOverlay`（`logFrameDuration`、`extractRenderState`）、`DebugEntryMemory#display`、`DebugScreenEntryList` | 帧时间环形缓冲、fps 百分位显示、off-heap 内存行、调试项启用状态 |
| `features.gui.hooks.settings.OptionsScreenMixin` | `OptionsScreen` 的 Video 按钮 | 打开 Sodium 的视频设置界面 |
| `features.render.entity.{ModelPartMixin,CubeMixin}` | `ModelPart#translateAndRotate`、`ModelPart$Cube`（`<init>`、`compile`） | 变换免分配；整个 cuboid 一次写入 |
| `features.render.entity.cull.EntityRendererMixin` | `EntityRenderer#shouldRender` | 额外用 Sodium 的区块可见性剔除实体 |
| `features.render.entity.shadows.ShadowFeatureRendererMixin` | `ShadowFeatureRenderer#prepare` | 阴影顶点直写 |
| `features.render.gui.font.BakedGlyphMixin` | `BakedSheetGlyph`（`render`、`buildEffect`） | 字形顶点直写 |
| `features.render.immediate.DirectionMixin` | `Direction#getApproximateNearest` | 免去遍历 6 个方向做点积 |
| `features.render.immediate.buffer_builder.intrinsics.BufferBuilderMixin` | `BufferBuilder`（`putBakedQuad` + 全部 23 处 `MemoryUtil.memPut*`） | 直写 baked quad；内存写入换成 Sodium intrinsics |
| `features.render.immediate.buffer_builder.sorting.*`（3） | `VertexSorting`、`StagedVertexBuffer`（`decodeSortingPoints`）、`StagedVertexBuffer$Draw` | 优化顶点排序；按「四边形上离相机最近的点」而不是重心排序 |
| `features.render.immediate.matrix_stack.VertexConsumerMixin` | `VertexConsumer#addVertex(Matrix4fc,…)`、`#setNormal(Pose,…)` | 免去临时 `Vector3f` |
| `features.render.particle.QuadParticleRenderStateMixin` | `QuadParticleRenderState#renderRotatedQuad` | 粒子四边形直写 |
| `features.render.viewport.GlStateManagerMixin` | `GlStateManager#_viewport` | 跳过重复的 viewport 调用 |
| `features.render.world.clouds.CloudRendererMixin` | `CloudRenderer#buildMesh` | 用裸指针写云网格 |
| `features.render.world.sky.LevelRendererMixin`（实际 target 是 `Camera`） | `Camera#extractRenderState` | 相机在流体中时不渲染天空（配合 Sodium 的雾遮挡，顺带修复 MC-152504） |
| `features.textures.animations.tracking.*`（11） | `SpriteContents`(+`AnimationState`)、`TextureAtlasSprite`、`TextureAtlas#getSprite`、`AtlasManager#get`、`FluidStateModelSet#get`、`ModelBlockRenderer#putQuadWithTint`、`GuiGraphicsExtractor#blitSprite`×2、`AtlasGlyphProvider$Instance#renderSprite`、`SingleQuadParticle` | 只 tick / 上传「上一帧真的用到」的动画纹理 |
| `features.textures.scan.TextureAtlasSpriteMixin` | `TextureAtlasSprite#createAnimationState` | 检测非原版 animation state（图像内容不可预测） |
| `workarounds.context_creation.{WindowMixin,GlSurfaceMixin}` | `Window#createGlfwWindow`、`GlSurface#present` | NVIDIA/AMD 驱动 workaround 的环境变量开关；GL context 被替换的检测与模块扫描 |
| `workarounds.window_minimized_state.GameRendererMixin` | `GameRenderer#extractWindow` | 直接向操作系统查询 framebuffer 尺寸（Intel blit 崩溃 workaround） |
| fabric `core.model.quad.BakedQuadMixin` | `BakedQuad` | 因需要 3 个缓存字段，该 record 改写为 final class（`equals`/`hashCode`/`toString` 按 record 生成的语义实现） |
| fabric `features.world.biome.BiomeMixin` | `Biome`（`<init>`、`getGrassColor`、`getFoliageColor`） | 草/树叶颜色缓存 |
| fabric `features.render.model.ItemModelGeneratorMixin` | `ItemModelGenerator#bakeSideFaces` | 合并生成的侧面（`ImprovedItemModelBuilder` 从 loader 源码集原样移入 common 包） |

不移植的 mixin：

* `workarounds.event_loop.RenderSystemMixin` —— 上游该 mixin 类**没有任何成员**（空 mixin），没有任何行为可移植。
* fabric `mixin.fabric.LevelSliceMixin` —— 让 `LevelSlice` 实现 Fabric API 的 `FabricBlockGetter`。本项目没有 Fabric API，
  该接口不存在，因此不移植（NeoForge 源码集的同名 mixin 同理）。
* `frapi/**` 的 4 个 mixin 与 `neoforge/src/mod` 的 10 个 mixin —— 分别依赖 FRAPI 与 NeoForge，均不适用。

### mod 初始化入口

上游有两个 loader 入口点：`SodiumPreLaunch`（Fabric `PreLaunchEntrypoint`，做环境检查 / 显卡探测 / workaround 初始化）与
`SodiumFabricMod`（`ClientModInitializer`，做版本注册 / config 入口点收集 / FlawlessFrames / FRAPI 注册）。移植版新增
`client.SodiumBootstrap`，把两者按原顺序合并，由 `net.minecraft.client.main.Main` 在 `new Minecraft(...)` **之前**调用一次
——与 loader 保证的时序相同。与 loader 版本的差异（全部源于「没有 loader」这一事实）在该类的 Javadoc 中逐条列出：
版本号为常量、`ConfigManager` 的 mod 信息来自 `SodiumBootstrap.registerModMetadata`（各移植 mod 自己登记，代替 loader
的 mod 列表）、config 入口点不靠扫描 mod 元数据发现（各移植 mod 在自己的 bootstrap 里注册，sodium 自己的
`SodiumConfigBuilder` 由 `finishConfigRegistration()` 最后注册，顺序与 `ConfigLoaderFabric` 相同）、不查找
`frex_flawless_frames` 入口点（`FlawlessFrames` 保持未激活，与「没装 FREX 的 Fabric 环境」一致）、`FRAPIProvider` 为空实现。

另外，loader 在客户端构造之前就知道游戏目录，而 sodium 的 pre-launch 代码依赖这一点（它要读自己的配置文件）。移植版
`VanillaRuntimeInformation` 因此新增一个静态游戏目录字段，由 `Main` 在调用 bootstrap 前用解析好的 `--gameDir` 设置；
`Minecraft.getInstance()` 可用之后行为不变。

### 资源

Sodium 的资源按原路径合并进 `src/main/resources`：`assets/sodium/**`（`lang`、core shader `blocks/block_layer_opaque.*`
与 `include/*.glsl`、GUI 贴图）、classpath 根下的 `config-icon.png`（`SodiumConfigBuilder` 用
`getResourceAsStream("/config-icon.png")` 读取）与 `sodium-icon.png`。

`assets/minecraft/models/block/` 下的 36 个 json + `README.txt` 是 Sodium 覆盖原版模型的资源（给若干方块补内面，
`cube_all_inner_faces` / `inner_stairs` 等是新增模板）。上游靠资源包排序覆盖原版，合并进单一资源树时只能直接覆盖同名文件，
因此这些原版 json 被替换。json 不能写注释，故记录于此。

## Sodium Extra 0.9.3 → MCP 26.2

| 项目 | 说明 |
| --- | --- |
| 源码集 | `common/src/main` 的 `me/flashyreese/mods/sodiumextra/{client,common,compat}`（30 个文件）与 `net/caffeinemc/caffeineconfig/{CaffeineConfig,CaffeineConfigPlatform,Option}` 原样移入 `src/main/java`；`fabric/`、`neoforge/` 源码集只有 loader 入口点与 `CaffeineConfigPlatform` 实现，见下。 |
| 资源 | `assets/sodium-extra/**`（33 个语言文件、`post_effect/panini.json`、`shaders/post/panini.fsh`、`textures/icon.png`）移入 `src/main/resources`。 |
| accesswidener | `sodium-extra.accesswidener` 只有一条 `mutable field DebugScreenEntries.PROFILES`，直接去掉该字段的 `final`。另外 `DebugScreenEntries#register(Identifier, DebugScreenEntry)` 放宽为 public——上游是通过 loader 的注册入口（Fabric 的 `DebugScreenEntries::register` 入口点 / NeoForge 的 `RegisterDebugEntriesEvent`）到达它的。 |
| 平台服务 | `CaffeineConfig` 里的 `ServiceLoader.load(CaffeineConfigPlatform.class)` 换成直接引用新增的 `VanillaCaffeineConfigPlatform`。上游的 `CaffeineConfigFabric` / `CaffeineConfigNeoForge` 遍历 loader 的 mod 列表寻找 mod 声明的选项覆盖；没有 loader 就没有别的 mod 元数据，因此 `applyModOverrides` 无事可做（properties 文件里的**用户**覆盖不受影响，那是 `CaffeineConfig` 自己读的）。 |
| mixin 开关 | `SodiumExtraMixinConfigPlugin` / `AbstractCaffeineConfigMixinPlugin` 是 Mixin 专有类（实现 `IMixinConfigPlugin`），不移植。但 `sodium-extra.properties` 的语义必须保留：上游用它决定某个 mixin 包**是否装载**。移植版新增 `client.config.SodiumExtraFeatures`，用**同一个** `CaffeineConfig#getEffectiveOptionForMixin(原 mixin 类名)` 调用把每个功能解析成一个常量（类初始化时解析一次，与「代码装载前就决定好」的语义一致，也让热路径里的判断零开销），每处移植代码都以对应常量为前置条件。 |

### Mixin → 源码修改（全部完成）

| 原 Mixin | Minecraft / blaze3d / sodium Target | 说明 |
| --- | --- | --- |
| `adaptive_sync.MixinGlSurface` | `GlSurface#supportedPresentModes`、`#configure` | 追加 `FIFO_RELAXED` present mode，并把它映射到 GLFW 的自适应垂直同步（`glfwSwapInterval(-1)`）；`@Unique sodiumExtra$usesAdaptiveSync` 一并搬入 |
| `animation.MixinSpriteContentsAnimationState` | `SpriteContents$AnimationState` | 实现 `AnimationStateExtended`，记住动画状态属于哪个 sprite |
| `animation.MixinTextureAtlas` | `TextureAtlas#cycleAnimationFrames`、`#upload` | 按 sprite 名字与用户开关决定是否 tick 动画。该 mixin 的 `@Unique animatedSprites` 表与 `shouldAnimate` 移入 `common.util.AnimatedSpriteFilter`（上游是每个图集一份的实例字段，但表本身不可变、每项都通过 supplier 现读选项，故改为静态表，查找逻辑不变） |
| `biome_colors.MixinBiomeColors` | `BiomeColors#getAverageGrassColor`、`#getAverageFoliageColor`、`#getAverageWaterColor` | 关闭生物群系颜色时返回固定色 |
| `cloud.MixinLevelRenderer` | `LevelRenderer#addCloudsPass` 里的 `lambda$addCloudsPass$0` | 云高度覆盖 |
| `fog.AccessorMinecraft` / `fog.AccessorIntegratedServer` | `Minecraft.singleplayerServer`、`IntegratedServer#commandsAllowedForOtherPlayers` | accessor / invoker，改为原版类直接实现同名接口 |
| `fog.MixinFogRenderer` | `FogRenderer#setupFog` | 判断当前是否 `AtmosphericFogEnvironment`（上游用 `@Unique` 字段，这里因为只在同一次调用内写读而改为局部变量），再按自定义雾距离改写 `renderDistanceStart/End` 并注入雾形状 |
| `fog.MixinAtmosphericFogEnvironment` | `AtmosphericFogEnvironment#setupFog` | 自定义环境雾起止、天空雾末端与云雾百分比（`applyCloudFog` 一并搬入） |
| `fog.Mixin{Blindness,Darkness,Lava,PowderedSnow,Water}FogEnvironment` | 对应 `FogEnvironment#setupFog` | 「受保护的玩法雾」——只有在允许的场合才收紧失明/黑暗/岩浆/细雪/水下的雾 |
| `fog.MixinShaderManager` | `ShaderManager#getShader` | 只对 sodium 的地形 shader (`sodium:blocks/block_layer_opaque`) 应用雾形状变换 |
| `fog.MixinRenderSectionManager` | **sodium** `RenderSectionManager#getEffectiveRenderDistance`、`#getSearchDistanceForCullType`、`#getSearchDistance` | 圆柱形雾的剔除距离扩展（sodium 的剔除器只有一个距离，取圆柱 shader 可见的最高轴） |
| `fog.MixinOcclusionCuller` | **sodium** `OcclusionCuller#processQueue` 里的 `visitNeighbors` 调用、`#testDistance` | 扩展圆柱剔除时允许向上/向下继续遍历，并改用圆柱距离测试；`@Unique` 常量与 `getOutwardVerticalDirections` 一并搬入 |
| `fog.MixinTraversableTree` | **sodium** `TraversableTree#cylindricalDistanceTest` | 同上的距离测试 |
| `fps.MixinGameRenderer` | `GameRenderer#render` | 帧计数 |
| `gui.MixinMinecraftClient` | `Minecraft#runTick` | 每 tick 驱动 HUD 与全屏分辨率确认 |
| `gui.MixinGui` | `Gui#extractRenderState` | 绘制 sodium-extra 的 HUD |
| `gui.MixinDebugOptionsScreen` | `DebugOptionsScreen$OptionEntry#extractContent` | sodium-extra 自己的调试项以翻译键命名，需要单独的排版与 tooltip |
| `instant_sneak.MixinCamera` | `Camera#tick` | 取消潜行时的视高插值 |
| `light_updates.MixinLevelLightEngine` | `LevelLightEngine#checkBlock`、`#runLightUpdates` | 暂停光照更新（`runLightUpdates` 只把**返回值**压成 0，更新照旧执行，与注入点一致） |
| `panini_projection.AccessorPostChain` / `AccessorPostPass` | `PostChain.passes`、`PostPass.customUniforms` | accessor，改为原版类直接实现 |
| `panini_projection.MixinGameRenderer` | `GameRenderer#renderLevel`（`renderItemInHand` 调用之前） | Panini 投影后处理 |
| `particle.MixinParticleEngine` | `ParticleEngine#createParticle` | 按粒子类型开关过滤 |
| `particle.MixinFireworkParticle` | `FireworkParticles$Starter#createParticle` | 烟花粒子被关掉时不要走 `createParticle` 的强制转型 |
| `particle.MixinClientLevel` | `ClientLevel#addDestroyBlockEffect`、`#addBreakingBlockEffect`、`#tickWeatherEffects` | 方块破坏 / 挖掘 / 雨滴飞溅粒子开关 |
| `particle.MixinLevelRenderer`（target 实为 `WeatherEffectRenderer`） | `WeatherEffectRenderer#render` | 雨雪开关 |
| `prevent_shaders.MixinGameRenderer` | `GameRenderer#togglePostEffect`、`#setPostEffect` | 阻止 creeper/spider/invert 等后处理 |
| `reduce_resolution_on_mac.MixinGlBackend` / `MixinVulkanBackend` | `GlBackend#setWindowHints`、`VulkanBackend#setWindowHints` | 记录后端并关掉 Cocoa Retina framebuffer |
| `reduce_resolution_on_mac.MixinWindow` | `Window#refreshFramebufferSize`、`#onFramebufferResize` | 缩小 framebuffer 尺寸（两个 `@Unique` 方法一并搬入，注释保留原因说明） |
| `reduce_resolution_on_mac.MixinGlCommandEncoder` | `GlCommandEncoder#presentTexture` | 把缩小后的渲染目标拉伸到整个 swapchain |
| `reduce_resolution_on_mac.MixinVulkanGpuSurface` | `VulkanGpuSurface#configure`、`#blitFromTexture` | swapchain 也按同样比例缩小；blit 目标区域相应拉伸 |
| `render.block.entity.MixinBeaconRenderer` | `BeaconRenderer#submit` | 信标光束开关；无限段光束按世界高度收口（上游用 `@Unique` 字段带出 render state，直接改源码时 `state` 本就在作用域内） |
| `render.block.entity.MixinEnchantingTableBlockEntityRenderer` | `EnchantTableRenderer#submit` | 附魔台书本开关 |
| `render.block.entity.MixinPistonBlockEntityRenderer` | `PistonHeadRenderer#submit` | 活塞头开关 |
| `render.entity.MixinItemFrameEntityRenderer` | `ItemFrameRenderer#submit`、`#shouldShowName` | 物品展示框与其名牌开关（注入点在 `super.submit` 之后，因此名牌照旧提交） |
| `render.entity.MixinLivingEntityRenderer` | `LivingEntityRenderer#submit`、`#shouldShowName` | 盔甲架开关（关掉时仍提交名牌）、玩家名牌开关 |
| `render.entity.MixinPaintingEntityRenderer` | `PaintingRenderer#submit` | 画开关 |
| `sky.MixinSkyRenderer` | `SkyRenderer#renderSkyDisc`、`#renderEndSky`、`#renderSun`、`#renderMoon`、`#renderStars`、`#renderSunriseAndSunset` | 天空 / 日 / 月 / 星 / 朝霞晚霞开关 |
| `sky_colors.MixinAtmosphericFogEnvironment` | `AtmosphericFogEnvironment#getBaseColor` | 关闭天空颜色时用固定色 |
| `steady_debug_hud.MixinDebugScreenOverlay` | `DebugScreenOverlay#extractRenderState` | 按刷新间隔缓存 F3 文本（4 个 `@Unique` 字段搬入原版类；缓存的内容包含 sodium 插入的 fps 百分位行，与两个 mixin 同时生效时一致） |
| `toasts.MixinToastManager` | `ToastManager#addToast`、`#update` 里的 `lambda$update$1` | 过滤被关掉的 toast |
| `toasts.MixinToastInstance` | `ToastManager$ToastInstance#update` | 已在队列里的 toast 被关掉时立即结束 |

不移植的 mixin：`SodiumExtraMixinConfigPlugin`（Mixin 专有，见上表「mixin 开关」一行）。

### mod 初始化入口

上游有三个 loader 入口：`SodiumExtraFabricPreLaunch`（`PreLaunchEntrypoint`，Wayland 全屏分辨率恢复）、
`SodiumExtraFabricClientModInitializer`（`ClientModInitializer`，注册调试项并把 light-updates 警告塞进默认/性能 profile）、
以及 `fabric.mod.json` 里的 `sodium:config_api_user` 入口点（`SodiumExtraConfig`）。移植版新增
`me.flashyreese.mods.sodiumextra.SodiumExtraBootstrap`，把三者按原顺序合并，由 `net.minecraft.client.main.Main` 在
`SodiumBootstrap.bootstrap(...)` 之后、`SodiumBootstrap.finishConfigRegistration()` 之前调用一次——与 loader 的时序一致
（sodium-extra 依赖 sodium；所有 config 入口点都在构建配置界面之前收集完）。差异逐条列在该类的 Javadoc 里。

### 已知的、有意的缺口

`client.fog.FogDistanceHelper` 的「受保护的玩法雾」原本通过 `me.flashyreese.mods:greenlight-api`
（`Greenlight.feature(...).decoder(1, ProtectedGameplayFogPolicy::fromJson).register()`）接收**服务器下发**的 JSON 策略。
Greenlight 不在本次要移植的五个 mod 之内，它是一个独立库（common 源码集里是 `compileOnly`，loader jar 里 jar-in-jar 打包），
本仓库没有它的源码，也没有它的 jar；仅凭这三处调用点反推它的网络协议等于伪造一个第三方 API。因此该字段保留为
`PROTECTED_GAMEPLAY_FOG_DECODER`（解码器仍然接在原处），策略查询改为 `protectedGameplayFogPolicy()` 并带 `TODO PORT`
说明。**后果**：单人世界与「允许命令的 LAN 世界」这条不经过 Greenlight 的分支照旧生效；远程服务器无法授权客户端收紧
失明/黑暗/岩浆/细雪/水下的雾距，那些距离在远程服务器上保持原版——与「没装 Greenlight 的环境」表现一致。补齐方式：
加入 greenlight-api，恢复该字段与两处 `policy()` 调用。

`compat.IrisCompat` 目前仍是上游的反射实现（Iris 是 sodium-extra 的可选依赖，反射是 mod 自己的可选依赖探测，不是绕过
Mixin 转换）。Iris 移植进同一份源码树之后会改为直接引用，见 Iris 章节。

## Iris 1.11.2 → MCP 26.2

**已全部移植。** 基础设施（源码、资源、依赖、平台服务层、accesswidener、accessor、注入接口）与 `mixin/**` 的逐个转换均已完成，
项目可编译。

### 已完成的基础设施

| 项目 | 说明 |
| --- | --- |
| 源码集 | `common/src/main/java` 的 `net/irisshaders/iris/**`（除 `mixin/**`、`compat/sodium/mixin/**`、`compat/dh/mixin/**`）、`kroppeb/stareval/**`；`common/src/api/java` 的 `net/irisshaders/iris/api/**`（Iris 的公开 API，sodium-extra 的 `IrisCompat` 就是冲它去的）；`common/src/vendored/java` 的 `de/odysseus/ithaka/digraph/**`（Iris 自带的 digraph 库）。共 530 个文件原样移入 `src/main/java`。 |
| `BuildConfig` | 上游用 `com.github.gmazzo.buildconfig` Gradle 插件生成；本项目没有生成器，按插件对 `main` 源码集配置的值手写出该类（`IS_SHARED_BETA=false`、`ACTIVATE_RENDERDOC=false`、`BETA_TAG=""`、`BETA_VERSION=0`）。 |
| 资源 | `assets/iris/**`（33 个文件）与 classpath 根下的 `centerDepth.fsh/vsh`、`colorSpace.csh/vsh` 移入 `src/main/resources`。 |
| 依赖（`pom.xml`） | Iris 真正需要的三个库：`io.github.douira:glsl-transformer:3.0.0-pre3`（GLSL 解析/变换）、`org.antlr:antlr4-runtime:4.13.1`、`org.anarres:jcpp:1.4.14`（着色器预处理）。上游在 `common/build.gradle.kts` 里声明为 `compileOnly` 并 jar-in-jar 打进 loader jar；没有 loader，就是普通依赖。版本与 Iris 锁定的完全一致。另外 `lib/DHApi.jar`（Distant Horizons API，上游放在仓库根目录并声明 `compileOnly`）以 `system` scope 引入，`compat/dh` 要冲它编译。 |
| 平台服务 | `IrisPlatformHelpers` 的 `ServiceLoader` 换成直接引用新增的 `VanillaIrisPlatformHelpers`（替代 `IrisFabricHelpers` / `IrisForgeHelpers`）。差异逐条列在该类的 Javadoc 里：`isModLoaded` 恒 false（Iris 只问 distanthorizons / continuity / monocle / fabric-resource-loader-v0，这里一个都装不上）、版本号为常量、game dir 由 `Main` 在客户端构造前交过来、`compareVersions` 自己实现（原本是 Fabric Loader 的 `SemanticVersion`，只被 `UpdateChecker` 用来比自己的版本号）、`registerKeyBinding` 收集到一个列表里由 `Options#keyMappings` 追加（原本是 Fabric API 的 `KeyMappingHelper`，做的正是同一件事）。 |
| `IrisMixinPlugin` | 上游是所有 Iris mixin config 的 `IMixinConfigPlugin`，唯一职责是那个判断：**用 Vulkan 后端时 Iris 的 mixin 一律不装载**，只有名字里含 `VKOnly` 的反过来只在 Vulkan 下装载。移植版保留该类、保留 `usingVulkan` 字段与读 `options.txt` 的静态初始化块，去掉插件接口，改为 `isEnabled()` / `isVulkanOnlyEnabled()`——每一处移植进原版的 Iris 代码都以它为前置条件。 |
| accesswidener | `iris.accesswidener` 的 33 条全部落到源码上（`GlStateManager$BlendState/BooleanState/TextureState/DepthState`、`GlRenderPass`(+`pipeline`/`samplers`/`TextureViewAndSampler`)、`Options$FieldAccess`、`OptionInstance`（去 final）、`GlProgram`(+`<init>`/`uniformsByName`)、`NativeImage.pixels`、`LevelRenderer.renderBuffers`（去 final）、`AbstractSelectionList$Entry`、`PoseStack$Pose.trustedNormals`、`ItemPickupParticleGroup$State`、`RenderSetup.outputTarget/pipeline`、`RenderType#create/<init>`、`TextureAtlasSprite#isAnimated`、`GlDevice`、`GlCommandEncoder`、`GlBuffer.handle`、`BlockEntityRenderState.blockState`、`RegistryAccess$RegistryEntry`、`Stitcher$Holder`、`Biome$ClimateSettings`、`SpriteContents$AnimatedTexture/$FrameInfo` 等）。`RegistryAccess$RegistryEntry` 上游写的是 `extendable`，record 不能被继承，Iris 也只是需要指代该类型，故只放宽为 public。 |
| accessor 接口（21 个） | `CloudRendererAccessor`、`DimensionTypeAccessor`、`EndFlashAccess`、`GameRendererAccessor`、`GlStateManagerAccessor`、`GpuDeviceAccessor`、`LevelRendererAccessor`、`BannerRendererAccessor`、`rendertype.RenderTypeAccessor`、`statelisteners.BooleanStateAccessor`、`texture.{AnimationMetadataSection,ReloadableTexture,SpriteContents,SpriteContentsAnimatedTexture,SpriteContentsFrameInfo,SpriteContentsTicker,TextureAtlas,TextureAtlasSprite}Accessor`、`compat.sodium.mixin.{BlockRenderer,EnumOptionBuilderImpl,IntegerOptionBuilderImpl}Accessor` 全部改为普通接口，由目标类直接实现；两个静态 accessor（`GlStateManagerAccessor`、`BannerRendererAccessor`）改为接口里的静态方法直接读放宽后的成员。 |
| 注入接口 | `fabric.mod.json` 的 `loom:injected_interfaces` 六条改为原版类直接 `implements`：`RenderTarget`→`RenderTargetInterface`、`GpuTexture`→`GpuTextureInterface`、`RenderPass`/`RenderPassBackend`→`RenderPassInterface`、`RenderType`→`RenderTypeInterface`、`ItemInHandRenderer`→`ItemInHandInterface`。 |
| record 改写 | `AnimationMetadataSection` 因 Iris 的 `texture.AnimationMetadataSectionAccessor` 需要 `@Mutable` 的 `frameWidth`/`frameHeight` setter（PBR 图集缩放后要就地修正帧尺寸），由 record 改写为 final class，访问器与 `equals`/`hashCode`/`toString` 按 record 生成的语义实现。 |

### Mixin → 源码修改（全部完成）

| 原 Mixin | Minecraft / blaze3d / sodium Target | 说明 |
| --- | --- | --- |
| `MixinOptions_Entrypoint` / `VKOnly_InitKeys` | `Minecraft#<init>`（`new Options(...)` 之前） | **Iris 的真正入口**：`new Iris().onEarlyInitialize()`（Vulkan 后端下改为 `IrisVKOnly.run()`）。必须在读取 options 之前，注册的键位才能被 `Options#keyMappings` 收进去 |
| `MixinMinecraft_Keybinds` / `VKOnly_InitKeys` | `Minecraft#tick`（RETURN） | 处理 Iris 的键位（上游注释说明这是手写的 Fabric API END_CLIENT_TICK） |
| `MixinMinecraft_Images` | `Minecraft#<init>`（TAIL） | 注册 Iris GUI 用的 widgets 贴图 |
| `MixinMinecraft_PipelineManagement` | `Minecraft#clearClientLevel`、`#setLevel`、`#updateLevelInEngines` | 记录上一个维度；换维度时立刻重建管线（必须早于 Sodium 重载世界渲染器，见 IrisShaders/Iris#1330） |
| （平台服务的键位注册） | `Options#keyMappings` | `IrisPlatformHelpers#registerKeyBinding` 收集的键位在此追加进原版数组（上游是 Fabric API 的 `KeyMappingHelper`） |
| `MixinRenderSystem` | `RenderSystem#initRenderer` | GL 初始化后建立 Iris 的 GL 状态、debug 输出与 sampler |
| `MixinTitleScreen` | `TitleScreen#init` | 第一次进标题界面时 `Iris.onLoadingComplete()` |
| `MixinClientPacketListener` | `ClientPacketListener#handleLogin` | 进服后提示更新 / 着色器加载失败 / DH 不兼容 |
| `MixinKeyboardHandler` | `KeyboardHandler#handleDebugKeys` | Iris 的调试快捷键 |
| `MixinSystemReport` | `SystemReport#<init>` | 崩溃报告里加上当前着色器包与改过的选项 |
| `MixinQuickPlayDev` | `QuickPlay#joinSingleplayerWorld` | 仅开发环境生效（本项目恒为非开发环境，该分支永不进入，按原样保留） |
| `MixinDebugEntries` / `MixinDebugScreenEntriesList` | `DebugScreenEntries` 的 `<clinit>`、`DebugScreenEntryList#rebuildCurrentList` | 注册并默认启用 Iris 的两个调试项 |
| `MixinClientLanguage` | `ClientLanguage#loadFrom`、`#appendFrom`、`#getOrDefault`、`#has` | 着色器包可以「旁挂」额外语言条目，不必重载资源管理器 |
| `MixinChainedJsonException` | `ChainedJsonException#forException` | 着色器编译失败改用 Iris 自己的异常类型 |
| `MixinItem` / `ItemStackMixin` | `Item`、`ItemStack#hasFoil` | `IrisItemLightProvider`；阴影 pass 不渲染附魔光效 |
| `MixinBiome` / `MixinBiomes` | `Biome`、`Biomes#register` | `ExtendedBiome`（biomeCategory / downfall）；给原版生物群系分配连续 id |
| `MixinBiomeAmbientSoundsHandler` / `MixinLocalPlayer` | `BiomeAmbientSoundsHandler#tick`、`LocalPlayer` | 与 `moodiness` 同样衰减、但播放音效后不清零的「洞穴黑暗度」 |
| `MixinBlockStateBehavior` | `BlockBehaviour$BlockStateBase#getShadeBrightness` | 着色器包的 `ambientOcclusionLevel` |
| `MixinBlockState` / `MixinBlockModelPart` | `BlockStateModelPart` | 两者都只是让该接口继承 `IrisModelPart` |
| `MixinCamera` | `Camera#prepareCullFrustum` | 着色器包可以整体关掉视锥剔除（换成 `NonCullingFrustum`） |
| `MixinBooleanState` | `GlStateManager$BooleanState#setEnabled` | `BooleanStateExtended`：Iris 绕过 `GlStateManager` 改过 GL 状态后，下一次必须真的发调用 |
| `MixinGlStateManager` | `GlStateManager` 的 `<clinit>` | 纹理单元上限 12 → 128 |
| `MixinGlStateManager_BlendOverride` | `GlStateManager#_disableBlend`、`#_enableBlend`、`#_blendFuncSeparate` | 着色器包锁定混合状态时把改动挂起 |
| `MixinGlStateManager_DepthColorOverride` | `GlStateManager#_colorMask`×2、`#_depthMask`、`#_drawElements`、`#_glUseProgram` | 锁定深度/颜色掩码；tessellation 时 `GL_TRIANGLES`→`GL_PATCHES` |
| `MixinGlStateManager_FramebufferBinding` | `GlStateManager#_glUseProgram`、`#_activeTexture`、`#_viewport` | 跳过冗余 program 绑定、纹理单元范围检查。`_viewport` 的冗余调用跳过与 Sodium 的 `features.render.viewport GlStateManagerMixin` 是同一个优化，**已合并为一份**（保留 Sodium 实现，行为完全一致） |
| `statelisteners.MixinGlStateManager` / `statelisteners.MixinRenderSystem` | `GlStateManager#_blendFuncSeparate`、`RenderSystem#setShaderFog` | 混合函数 / 雾距离变化通知 Iris 的 uniform |
| `MixinUniform`（target 是 `GlStateManager`） | `GlStateManager#_glGetUniformLocation` | 让 `Sampler0`/`Sampler2` 等原版 sampler 名落到着色器包的 `tex`/`gtexture`/`lightmap` 上 |
| `UndoReverseZOne` … `UndoReverseZFive` | `DeviceInfo#isZZeroToOne`、`GlHeuristics#createDeviceInfo`、`GlConst#toGl(CompareOp)`、`Projection`（3 处）、`GlCommandEncoder`（4 处 clear + `applyPipelineState` 的 polygon offset） | 有着色器包时把原版的 reversed-Z 深度整套还原回经典 [-1,1] |
| `MixinWindow` | `GlBackend#setWindowHints`（RETURN） | Iris 调试选项开启时请求 debug GL context，并关掉 Sodium 的 no-error context（写回配置 + 提示） |
| `MixinGpuTexture` / `MixinGpuTexture2` | `GlTexture` / `GpuTexture` | `GpuTextureInterface` 的实现（基类保留抛异常的 default，正是 `MixinGpuTexture2` 的语义） |
| `MixinRenderTarget` | `RenderTarget#destroyBuffers` | `Blaze3dRenderTargetExt` + `RenderTargetInterface`：深度/颜色纹理版本号与 `iris$bindFramebuffer` |
| `MixinRenderPass` / `MixinRenderPass3` / `MixinRenderPass_Stub` | `GlRenderPass`、`RenderPass`、`RenderPassBackend` | `CustomPass` 的存取：`GlRenderPass` 存字段，`RenderPass` 转交 backend，`RenderPassBackend` 保留抛异常的 default |
| `MixinRenderPipeline` / `vertices.immediate.MixinRenderType` | `RenderPipeline#getVertexFormatBinding(s)`、`RenderType#format` | 着色器包渲染世界时把原版顶点格式换成 Iris 的扩展格式（BLOCK→TERRAIN、ENTITY、glyph、Sodium 的 COMPACT→当前包格式） |
| `MixinRenderType` | `RenderType`（`state` 字段） | `RenderTypeInterface`：取该 render type 的 target 与 pipeline |
| `MixinCompiledShaderProgram`（target 是 `GlProgram`） | `GlProgram#setupBindGroupLayouts` | `ShaderInstanceInterface`：uniform block 索引改由 Iris 的 program 解析；Iris 自己的 program 不再输出「未使用 sampler」警告 |
| `MixinShaderManager_Overrides`（target 是 `GlDevice`） | `GlDevice#getOrCompilePipeline` | **着色器包接管原版 pipeline 的地方**：按 `ShaderKey` 换成 Iris 编译出来的 program，缺失时按 namespace 报 fatal/error（每个只报一次） |
| `MixinGlCommandEncoder` | `GlCommandEncoder#createRenderPass`、`#trySetup`、`#applyPipelineState`、`#submitRenderPass`、`#executeDraws`、`#drawFromBuffers` | 阴影 pass 不改 viewport / 按阴影贴图分辨率 scissor / 保留 Iris 已绑定的 FBO；`CustomPass` 自行套用 pipeline 状态；把 pass 的 sampler 交给 Iris 的 program 并在提交时清理；tessellation 拓扑 |
| `MixinFogRenderer` | `FogRenderer#setupFog` | 旧式水下雾密度 uniform + 雾颜色捕获（`HAS_CLOSER_WATER_FOG` 那一段上游已注释，带 `TODO PORT` 说明） |
| `MixinLightTexture` | `LightmapRenderStateExtractor#extract`、`#calculateDarknessScale` | 把 darkness 效果强度暴露给着色器包 |
| `MixinModelViewBobbing` | `GameRenderer#renderLevel` | **把视角摇晃与眩晕/传送门旋转从投影矩阵搬到模型视图矩阵**（OptiFine 的做法，绝大多数着色器包依赖它） |
| `MixinGameRenderer` (+`_NightVisionCompat`) | `GameRenderer#render`、`#<init>`、`#renderItemInHand`、`#renderLevel`、`#nightVisionScale` | 帧计时器、硬件信息日志、着色器包界面的背景模糊、有包时禁用原版手部渲染、`finalizeGameRendering`、夜视 NPE 兼容 |
| `MixinItemInHandRenderer` | `ItemInHandRenderer#submitArmWithItem`、`#submitHandsWithItems` | `ItemInHandInterface`：Iris 自己分固体/半透明两遍画手 |
| `MixinTweakFarPlane` | —— | **不移植**：不在任何 mixin config 里，上游注释写明「我已决定停用这个 Mixin，留在这里仅作参考」 |
| `MixinScreenEffectRenderer` | `ScreenEffectRenderer#submitWater` | 着色器包可以自己画水下覆盖层 |
| `MixinLevelRenderer` | `LevelRenderer#render`（HEAD / clear pass 之后 / `addMainPass` 之前 / `popMatrix`）、sky / clouds / weather / main / always_on_top 各 pass 的 lambda、`#submitBlockOutline` | **Iris 的主渲染接入点**：建立与收尾管线、插入 `iris_setup` frame pass、渲染阴影贴图、切换 `WorldRenderingPhase`、手部固体/半透明两遍、方块描边包装、有包时不清深度 |
| `MixinLevelRenderer_Sky` | `LevelRenderer#addSkyPass` 的 lambda | 无包时按 Sodium 的做法在雾距缩短时跳过天空（同时修复 MC-152504） |
| `MixinLevelRenderer_SkipRendering` | `LevelExtractor#extractVisibleEntities` | 管线可以整体跳过渲染 |
| `vertices.immediate.MixinLevelRenderer` | `LevelRenderer#render`（HEAD/RETURN） | `ImmediateState.isRenderingLevel` 标记 |
| `shadows.MixinLevelRenderer` | `LevelRenderer.visibleSections` | `CullingDataCache`：阴影 pass 前后交换可见 section 列表 |
| `shadows.MixinBeaconRenderer` | `BeaconRenderer#submitBeaconBeam` | 阴影 pass 不画信标光束 |
| `MixinSkyRenderer` | `SkyRenderer#renderSkyDisc`、`#renderSun`、`#renderMoon`、`#renderStars`、`#renderSunriseAndSunset`、`#renderDarkDisc`、`#renderSunMoonAndStars` | 切换 `WorldRenderingPhase`、日/月开关、太阳路径倾斜 |
| `sky.MixinDimensionSpecialEffects` | `SkyRenderer#renderSunriseAndSunset` | 失明或相机在流体中时不画朝霞晚霞。**与 sodium-extra 的同名注入合并**（两者都只是取消渲染，按 sodium-extra→Iris 顺序各判断一次） |
| `sky.MixinClientLevelData_DisableVoidPlane` | `ClientLevel$ClientLevelData#getHorizonHeight` | 相机在流体中时不画虚空面 |
| `sky.MixinOptions_CloudsOverride` | `Options#getCloudStatus` | 让当前管线覆盖云的画质设置 |
| `MixinWeatherRenderer` | `WeatherEffectRenderer#render` | 着色器包可以关掉天气渲染。**与 sodium-extra 的同名注入合并**（`extractRenderState` 的第二个 wrapper 上游已注释，未移植） |
| `MixinTheEndPortalRenderer` / `MixinTheEndGatewayRenderer` | `AbstractEndPortalRenderer#submitCube`、`TheEndGatewayRenderer#submit` | 有包时末地传送门/通道改用平面动画贴图（原版依赖被包替换掉的后处理） |
| `MixinParticleEngine`（target 是 `QuadParticleFeatureRenderer`） | `QuadParticleFeatureRenderer#executeGroup` | 粒子统一走 textured_lit 程序 |
| `MixinPreparedRenderType` | `PreparedRenderType#drawFromBuffer` | 因需要可变的 `wrapper` 字段，该 record 改写为 final class；绘制前后套上 Iris 选中的程序 |
| `MixinByteBufferBuilder` | `ByteBufferBuilder` | `MojangBufferAccessor`：暴露裸缓冲指针 |
| `MixinGuiItemAtlas` | `GuiItemAtlas#<init>`、`#drawToSlot` | GUI 物品图集用反转深度绘制 |
| `MixinEntityRenderDispatcher` | `EntityRenderDispatcher#submit` | 有真实阴影的包不要原版的圆形阴影 |
| `AbstractSignRendererMixin` / `MapRendererMixin` / `BannerRendererMixin` | `AbstractSignRenderer#submitSignText`、`MapRenderer#render`、`BannerRenderer#submitPatterns` | 阴影 pass 不画告示牌文字 / 地图 / 旗帜图案（旗帜只画底色层） |
| `fabulous.MixinDisableFabulousGraphics` | `LevelExtractor#onResourceManagerReload`、`#allChanged` | 开着色器时强制关掉 fabulous（improved transparency） |
| `gui.MixinGui` / `gui.MixinHud` / `gui.MixinVideoSettingsScreen` | `Hud#extractRenderState`、`#extractVignette`、`VideoSettingsScreen#addOptions` | `HudHideable` 屏幕隐藏 HUD + GL debug group；暗角开关；视频设置里加着色器包按钮与 Iris 渲染距离 |
| `texture.MixinAbstractTexture` | `AbstractTexture#getTexture` | 追踪每个 GL 纹理 id（PBR 用）。上游的 `iris$setFilter`/`onSet` 注入被注释、方法不可达，未移植 |
| `texture.MixinGlStateManager` | `GlStateManager#_texImage2D`、`#_deleteTexture` | 缓存纹理尺寸/格式；删除纹理时清理三处 PBR 缓存 |
| `texture.MixinIdentifier` | `Identifier#isValidPath`、`#validPathChar` | 拒绝 `DUMMY`；允许着色器包资源路径里的大写字母 |
| `texture.MixinSpriteContents` | `SpriteContents#increaseMipLevel` | PBR 格式可以提供自己的 mipmap 生成器 |
| `texture.MixinTextureManager` | `TextureManager#reload` 的 lambda、`#dumpAllSheets`、`#close` | 资源重载时重读 PBR 纹理格式并清空缓存 |
| `texture.pbr.MixinSpriteContents` | `SpriteContents#close`、`#sodium$setActive` | PBR sprite holder；**并且**在 Sodium 自己新增的 `sodium$setActive` 尾部把 normal/specular sprite 也标记为活跃（上游即 `@Dynamic("Added by Sodium")`，是 Iris 直接依赖 Sodium 的一处） |
| `texture.pbr.MixinTextureAtlas` / `MixinReloadableTexture` / `MixinDirectoryLister` | `TextureAtlas#cycleAnimationFrames`、`#upload`、`ReloadableTexture#doLoad`、`DirectoryLister#run` | PBR 图集 holder 与动画帧推进；加载后登记纹理；基础纹理存在时不把 `_n`/`_s` 当成独立 sprite |
| `vertices.MixinBufferBuilder` | `BufferBuilder#<init>`、`#addVertex(FFF)`、`#endLastVertex`、`#push` | **透明地把原版顶点格式扩展成 Iris 的格式**：格式替换、偏移缓存、逐顶点写 mid-block / 方块 id / 实体 id、按图元批量算 mid-UV / 法线 / 切线。`BlockSensitiveBufferBuilder` 的另外三个方法上游在合并后的类里**根本不存在**（调用即 `AbstractMethodError`），因此按同样语义抛 `AbstractMethodError` 并注明 |
| `vertices.MixinVertexFormat` | `VertexFormat` | `VertexFormatExtension`：`iris_` 前缀的属性绑定 |
| `vertices.block_rendering.MixinClientLevel`（target 是 `CardinalLighting`） | `CardinalLighting#byFace` | 着色器包可以完全关掉原版的方向着色 |
| `vertices.immediate.MixinBufferSource`（target 是 `StagedVertexBuffer`） | `StagedVertexBuffer#getVertexBuilder`、`#upload` | 非世界渲染时关掉扩展顶点格式 |
| `entity_render_context/**`（27 个） | `SubmitNodeCollection` 的 5 个 submit 方法、5 个 `*FeatureRenderer`（+`RenderTypeFeatureRenderer$Group`）、`BlockEntityRenderDispatcher`、`EntityRenderDispatcher`、`BlockModelResolver`、`BlockModelRenderState`、`ItemModelResolver`、`ItemStackRenderState`(+`LayerRenderState`)、`GlyphRenderTypes`、`CapeLayer`、`WingsLayer`、`SimpleEquipmentLayer`、`EquipmentLayerRenderer`、`EnderDragonRenderer` | **告诉着色器包「现在画的是哪个实体 / 方块实体 / 物品」**：提交时捕获三个 id，构建时恢复；方块实体几何体额外用 `OuterWrappedRenderType` 打标；名牌 / 火焰 / 披风 / 鞘翅 / 盔甲纹饰 / 末影龙光束各有专属 id。因需要 4 个可变字段，`BlockModelFeatureRenderer$Submit`、`ItemFeatureRenderer$Submit`、`ModelFeatureRenderer$Submit`、`TextFeatureRenderer$Submit`、`CustomFeatureRenderer$Submit` 五个 record 改写为 final class |
| `compat/sodium/mixin/**`（17 个非 accessor） | `ChunkMeshFormats#getCurrent`、`ChunkVertexEncoder$Vertex`、`BlockRenderer`、`DefaultFluidRenderer`、`ChunkBuilderMeshingTask`、`DefaultChunkRenderer`、`RenderSectionManager`、`SodiumWorldRenderer`、`RenderRegion`、`RenderRegionManager`、`ShaderChunkRenderer`、`UniformBufferManager`、`ChunkVertexConsumer`、`AbstractBlockRenderContext`、sodium 的 `VideoSettingsScreen` | **Iris 与 Sodium 的桥**：地形顶点格式换成着色器包的格式、每个 chunk 顶点带上方块 id / 渲染类型 / 光照，阴影 pass 有独立的 render list / task list / section tree / uniform ring buffer（在 `SodiumWorldRenderer`、`RenderSectionManager`、`RenderRegion`、`UniformBufferManager` 四层各自换入换出），阴影 pass 关闭面剔除与异步剔除，光源方块体素化，着色器包可以改方块的地形层 |
| `compat/dh/mixin/**`（4 个） | Iris 自己的 4 个 shadow frustum 类 | 让它们实现 DH 的 `IDhApiShadowCullingFrustum`。上游只在装了 Distant Horizons 时应用（`DHMixinConfigPlugin`），本项目装不上，方法永不被调用，但仍照实现 |

不移植 / 无需移植的 mixin（均已逐个核对上游源码）：

* `MixinTweakFarPlane` —— 见上表：不在任何 mixin config 里，上游自己注明已停用。
* `MixinTextureUtil`、`MixinSodiumOptions`、`state_tracking.MixinPostChain`、`vertices.block_rendering.MixinBufferBuilder_SeparateAo`
  —— 类里**没有任何成员**（空 mixin），没有行为可移植。
* `state_tracking.MixinRenderTarget` —— 唯一的注入被上游注释掉了（`// TODO 1.21.5`），当前不产生任何行为。
* `MixinMaxFpsCrashFix` —— 只有一个 `@Unique` 私有方法、**没有任何注入点**。
* `MixinChunkBorderRenderer` —— 只有一个假的 `VertexConsumer` 字段、没有注入点。
* `MixinLightningBoltRenderer` —— 唯一的 `@Redirect` 被注释掉，方法体 `return null`。
* `MixinRenderSection` —— 两个注入都被上游注释掉了。
* `shadows.MixinPreventRebuildNearInShadowPass` —— 只有一个 `@Shadow` 字段，没有注入点（上游注释也说明「装了 Sodium 就不需要这个补丁」）。
* `EnumOptionBuilderImplAccessor` —— 该 `@Accessor` 生成的方法名 `getEnumClass()` 与 MCP `EnumOptionBuilderImpl` 已有的同签名方法冲突
  （已有方法会经父 option 解析取值，而 `@Accessor` 直接读字段）。Iris 从不调用它（只出现在 mixin config 里），因此不挂接该接口，
  也不改变已有方法的语义、不另造一个方法。
* `fabric/**` 的 4 个 mixin（`MixinExtendedBlockModelFeatureRenderer`、`MixinExtendedBlockModelSubmit`、`MixinFluidRendererImpl`、
  `MixinRenderTypes`）与 `neoforge/**` 的 12 个 —— 分别依赖 Fabric API（FRAPI）与 NeoForge，本项目都没有。
* `compat/modmenu` 与 `common/src/headers` 的 ModMenu 桩、`common/src/desktop` 的 `LaunchWarn` —— ModMenu 集成与「双击 jar 时弹出的
  提示窗」，都属于 loader/打包层面。

### 一处需要说明的上游语义

`compat.sodium MixinSodiumWorldRenderer` 对 `setupTerrain` 里那**一个** `renderSectionManager.needsUpdate()` 调用同时挂了两个
注入：`#iris$forceChunkGraphRebuildInShadowPass`（`@Redirect`，阴影 pass 中若太阳角度变了就返回 true）与
`#iris$forceEndGraphRebuild`（`@WrapOperation`，阴影 pass 中一律返回 false，并带注释「TODO: Detect when the sun/moon isn't
moving」）。MixinExtras 的 `@WrapOperation` 会**包在**已有的 `@Redirect` 外层，因此外层先短路，`@Redirect` 里的太阳角度检测永远
不会执行。移植版实现的是这个**实际生效**的行为（阴影 pass 中恒为 false），上游自己的 TODO 也印证该检测仍被视为「尚未实现」；
相应地那个只被 `@Redirect` 使用的 `lastSunAngle` 字段也是死字段，未移植。

---

## Lithium 移植补充说明

### block.hopper 移植说明

`HopperBlockEntity` 现在直接实现 `UpdateReceiver` / `InventoryChangeListener` / `SectionedEntityMovementListener`，并持有上游
`HopperBlockEntityMixin` 的全部 `@Unique` 状态（插入/取出目标的缓存、`LithiumStackList` 的 mod count、三个
`Sectioned*MovementTracker`、sleep 判定标记）。原 mixin 的注入点对应关系：

| 原 Mixin 成员 | 移植后位置 |
| --- | --- |
| `getLithiumOutputInventory`（`@Redirect getAttachedContainer`） | `ejectItems` 开头改调 `self.lithium$getInsertInventory(level)` |
| `lithiumInsert`（`@Inject` 于 `isFullContainer` 之前，总是 cancel） | `ejectItems` 中的 `lithium$insert(...)`；仅当漏斗本身是 `WorldlyContainer` 时回落到原版循环 |
| `getExtractInventory`（`@Redirect getSourceContainer`） | `suckInItems` 改调 `lithium$getExtractInventory(...)` |
| `lithiumExtract`（`@Inject` 于 `Direction.DOWN` 读取之后） | `suckInItems` 中的 `lithium$extract(...)`，返回 `null` 表示回落原版循环 |
| `lithiumGetInputItemEntities`（`@Redirect getItemsAtAndAbove`） | `suckInItems` 的物品实体分支 |
| `lithiumHopperIsFull` / `lithiumHopperIsEmpty`（`@Redirect`） | `tryMoveItems` 中的 `lithium$inventoryFull` / `lithium$isEmpty` |
| `checkSleepingConditions`（`@Inject` 于 `tryMoveItems` 之后） | `pushItemsTick` 尾部 |
| `invalidateOnSetCachedState`（`@Inject` HEAD） | `setBlockState` 开头 |

`HopperHelper.updateHopperOnUpdateSuppression` 的调用点来自 fabric 源码集的 `block.hopper.LevelMixin`，移植到
`Level#setBlock` 中 `updatePOIOnBlockStateChange` 之前。

`BlockBehaviour#updateShape` 的 HEAD 注入只在**基类实现被真正执行**时触发（覆写且不调用 `super` 的方块不会触发）——
这与上游 Mixin 的作用范围完全相同（Mixin 也只改 `BlockBehaviour` 自己那个方法）。`HopperBlock` 没有覆写 `updateShape`，
因此堆肥桶发出的 shape update 仍能到达漏斗。

### 与上游行为不同的一处（已知、有意）

* `block.hopper.OldMinecartBehaviorMixin` 上游写的是 `this instanceof Container`，而 `this` 是 `OldMinecartBehavior`
  （`MinecartBehavior` 的子类），不是矿车实体，因此该条件恒为 false——上游这两个 hook 实际从不执行，且一旦执行其
  `(EntityAccessor) this` 强转还会 `ClassCastException`。移植版把判断改到 `this.minecart` 上（方法名 `avoidNotifyingMovementListeners`
  与同 package 的 `AbstractChestBoatMixin` 都表明这才是本意）。效果仅限于：容器矿车沿轨道移动时，移动监听器由「每个中间
  位置各通知一次」变为「结束时通知一次」；`EntityInLevelCallback#onMove` 本身仍照常执行（notification mask 只控制监听器
  通知，不影响 section 迁移），因此不改变游戏可观测行为。

### 已知的、有意省略的细节

* `world.chunk_ticking.random_block_ticking` 的 `LevelChunkSection$BlockCounterMixin#handleAfterCounting` 里有一段
  `if (LithiumMixinPlugin.DEBUG) sanityCheckRandomTickableBlockCount(...)` 的自检。`DEBUG` 来自默认关闭的
  `mixin.debug` 选项，恒为 false，而 `LithiumMixinPlugin` 属于不移植的 Mixin runtime，因此这段自检未移植。
  运行时行为不变。（`client_tick.entity.unused_brain.MemorySlotMixin` 同样引用 `LithiumMixinPlugin.DEBUG`，移植版改为引用
  `LithiumMod.DEBUG`，值同样恒为 false。）
* `ai.poi.SectionStorageMixin` 的 `GenerationChunkHolderAccessor` 未移植：26.2 的
  `GenerationChunkHolder#getChunkIfPresent(ChunkStatus)` 是 public 且语义与该 accessor 组合完全一致。
* `world.chunk_access.ServerChunkCacheMixin` 的 `@Overwrite` 去掉了原版 `getChunk` 里的两个
  `Profiler#incrementCounter("getChunk"/"getChunkCacheMiss")` 以及 `storeInCache` 调用（原版的
  `lastChunkPos/lastChunkStatus/lastChunk` 三个数组仍被 `getChunkNow` 使用）。这与上游一致，未做「修复」。
* `common/world/ChunkLoadTricks.tryRetrieveCurrentlyLoading` 在没有 NeoForge 的环境下恒返回 null（上游只有 NeoForge 源码集
  的 mixin 会覆写它）。调用点按原样保留，与上游 Fabric 环境行为一致。

---

## 完成情况汇总

Lithium：**已全部移植**（除下列按上游默认值判定为关闭的 module，见「默认关闭 / 冲突而不移植的 module」）：
`ai.pathing`、`debug.palette`、`experimental.entity.block_caching{,.block_support,.suffocation}`、
`experimental.entity.item_entity_merging`、fabric 的 `compat.worldedit`。

Sodium：**已全部移植**（见上方 Sodium 章节，含不移植项的理由）。

Sodium Extra：**已全部移植**（见上方 Sodium Extra 章节，含唯一的已知缺口与理由）。

Iris：**已全部移植**（见上方 Iris 章节，含不移植项的逐条理由）。

五个模组均已完成。
