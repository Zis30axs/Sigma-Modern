# ViaFabricPlus 4.6.3 (ver/26.2) -> Sigma-Modern 移植账本 / port ledger

上游基准 upstream baseline: `ViaVersion/ViaFabricPlus` 分支 `ver/26.2`, commit `c54b78b` ("ViaFabricPlus 4.6.3", 2026-08-23)。本地副本 `E:\.sigma\PerMods\ViaFabricPlus-ver-26.2` 已逐字节比对，与该 commit 内容一致（仅行尾差异）。

**这份文件取代 VFP_PORTING.md 里“已完整移植”的结论。那个结论当时是错的：实测覆盖率 20.9%。**

## 方法 / method

1. 枚举上游 `injection/mixin/` 下全部 **368** 个 mixin。
2. 每个 mixin 逐 hook 展开：`@Inject` / `@Redirect` / `@ModifyExpressionValue` / `@ModifyArg(s)` / `@ModifyVariable` / `@ModifyConstant` / `@ModifyReturnValue` / `@ModifyReceiver` / `@WrapOperation` / `@WrapWithCondition` / `@WrapMethod` / `@Overwrite` / `@Accessor` / `@Invoker`，外加 accessor 接口实现、承载状态的 `@Unique` 字段、构造器 hook、静态初始化 hook。`@Definition` 不单独计数。
3. 每个 hook 都去对应 vanilla 类里**读实际代码**确认行为与版本门控。`// MODIFIED for porting` 注释不算证据。
4. 每条“已移植”结论再交给对抗性复核 agent 重读并尝试推翻。两轮全量审计分别推翻 6 条和 7 条；最后一轮针对收尾工作的 39 行独立重验又推翻若干条并报出 9 处缺陷，其中 4 处已修。

## 总览 / summary

| 指标 | 会话开始 | 现在 |
|---|---|---|
| 上游 mixin | 368 | 368 |
| 上游 hook | 726 | 726 |
| 已内联 hook | 152 | **700** |
| 覆盖率 | 20.9% | **96.4%** |
| COMPLETE | 67 | **317** |
| REPLACED | 0 | **36** |
| PARTIAL | 16 | **1** |
| MISSING | 235 | **5** |
| NOT_APPLICABLE | 50 | 9 |

剩余 26 个未写出的 hook 分三类，没有第四类：

- **7 个真的还开着**（6 行，见下面 §仍未完成）。每条都写明为什么无法复现以及确切代价。
- **11 个在 NOT_APPLICABLE 行里**（9 行）。目标是 jar 里的类，且**已证明本树没有行为损失** —— 修复已在依赖的 jar 里、或它守卫的功能在这里不可能存在。证据是 bytecode / grep / 源码行号，不是“看起来合理”。
- **8 个在 4 个 COMPLETE 行里**：上游为 MoreCulling 兼容加的 `getOcclusionShape` + `requireOriginalShape` 成对 hook。它们的守卫 `ViaFabricPlusMixinPlugin.MORE_CULLING_PRESENT` 唯一的赋值在一个本树从不调用的 `onLoad()` 里，所以那条分支恒假；照抄只会写出死代码。

按优先级 / by priority:

| 优先级 | COMPLETE | REPLACED | PARTIAL | MISSING | NOT_APPLICABLE | 合计 |
|---|---|---|---|---|---|---|
| P0 | 61 | 15 | 0 | 2 | 0 | 78 |
| P1 | 125 | 6 | 0 | 0 | 0 | 131 |
| P2 | 78 | 3 | 0 | 3 | 0 | 84 |
| P3 | 53 | 12 | 1 | 0 | 9 | 75 |

优先级定义：**P0** 协议/连接状态、报文生成与顺序、keep-alive / ping / transaction / teleport 确认、移动报文节奏。**P1** 玩家与实体物理、交互。**P2** 方块/物品/世界行为。**P3** 观感、GUI、屏幕、字体、音效、Bedrock 专属附加项。

## 状态定义 / status

- **COMPLETE** — 每个 hook 都已按行为内联进对应 vanilla 类，版本门控一致。
- **PARTIAL** — 部分 hook 已内联，缺口逐条列在下面。
- **MISSING** — 该 mixin 的行为在本树里仍然没有落点。剩 5 行，全部在下面逐条说明。
- **REPLACED** — 目标是 ViaVersion 的类，改用 ViaVersion 公开协议 API 在 bootstrap 阶段重建等价行为，全部集中在 `ViaFabricPlusProtocolPatches`。
- **NOT_APPLICABLE** — 目标是 jar 依赖里的类且没有公开 API 路线。**这不等于没有行为缺口**，凡是仍有真实损失的都在 §库目标 一节写明。

## 仍未完成 / still open (26 hooks, 6 rows)

| 上游 mixin | hook | 优先级 | 剩余行为 |
|---|---|---|---|
| `features/networking/limitation/nbt/MixinNamedCompoundTagType.java` | 0/1 | P0 | `@Redirect removeNBTSizeLimit` |
| `features/networking/limitation/nbt/MixinTagType.java` | 0/1 | P0 | `@Redirect removeNBTSizeLimit` |
| `features/entity/metadata/MixinCommonBoss.java` | 0/1 | P2 | `@Redirect ignoreHealthCheck` |
| `features/entity/metadata/MixinEntityPacketRewriter1_15.java` | 0/1 | P2 | `@Redirect removeAndTrackHealth` |
| `features/entity/metadata/MixinEntityTracker1_9.java` | 0/3 | P2 | `@Redirect removeMin`; `@Redirect removeMax`; `@Redirect remapNaNToZero` |
| `features/scoreboard/MixinComponentUtil.java` | 1/1 | P3 | `@Redirect dontSkipEmptySections` |

**`features/networking/limitation/nbt/MixinNamedCompoundTagType.java`** — 0/1, 目标 `com.viaversion.viaversion.api.type.types.misc.NamedCompoundTagType (viaversion-common JAR — no source file in the tree)`

- `@Redirect removeNBTSizeLimit` — Replaces TagLimiter.create(maxBytes, TagLimiter.DEFAULT_MAX_NESTING_LEVEL) with TagLimiter.noop(), so NBT arriving on a translated connection is read with no byte cap and no nesting cap. Ungated — applies to every target version. Without it, oversized or deeply nested NBT throws inside the decoder and drops the connection where upstream reads it.
  - 落点: com/viaversion/viaversion/api/type/types/misc/NamedCompoundTagType.java:78 — the TagLimiter.create call in `public static CompoundTag read(ByteBuf, int maxBytes, boolean readName)`, which is also what the instance read(ByteBuf) (:48-54) and OptionalNamedCompoundTagType route through.
- 备注: Not ported anywhere and not provably gap-free, so the recorded NOT_APPLICABLE does not hold. Nothing in the tree removes the limiter: grep over E:/.sigma/Sigma-Modern/src/main/java for TagLimiter / NamedCompoundTagType / noop() returns 0 hits, and there is no forced static-final rewrite of Types.NAMED_COMPOUND_TAG (the only NAMED_COMPOUND_TAG hits are reads in WorldHeightSupport.java:51-67; no Unsafe/VarHandle/privateLookupIn anywhere in the viafabricplus package). The cap is live in the shipped jar, so this is not the 'fix already in the jar' case: javap of viaversion-common-5.12.0-SNAPSHOT.jar shows NamedCompoundTagType.read(ByteBuf) -> read(buf, 2097152, true) and read(ByteBuf,int,boolean) -> TagLimiter.create(maxBytes, 512); TagLimiter.create(int,int) forwards to create(maxBytes, maxLevels, 262144) and TagLimiterImpl.countBytes/countTag/checkLevel each throw IllegalArgumentException, while upstream substitutes NoopTagLimiter.INSTANCE. So NBT past 2 MiB of raw bytes, 512 nesting levels or 262144 tags on a translated connection throws inside the Via decoder (read(ByteBuf) even rewr

**`features/networking/limitation/nbt/MixinTagType.java`** — 0/1, 目标 `com.viaversion.viaversion.api.type.types.misc.TagType (viaversion-common JAR — no source file in the tree)`

- `@Redirect removeNBTSizeLimit` — Replaces TagLimiter.create(this.maxBytes, TagLimiter.DEFAULT_MAX_NESTING_LEVEL) with TagLimiter.noop() for the instance read, removing both the byte cap and the nesting cap on every translated Tag read. Ungated.
  - 落点: com/viaversion/viaversion/api/type/types/misc/TagType.java:74 — the TagLimiter.create call in `public Tag read(ByteBuf)`, immediately before TagRegistry.read(id, in, tagLimiter, 0).
- 备注: Still open, and the pre-closing NOT_APPLICABLE is a plausibility argument rather than a proof - the fix is NOT in the shipped jar. javap -c of viaversion-common-5.12.0-SNAPSHOT!com/viaversion/viaversion/api/type/types/misc/TagType.read(ByteBuf), offsets 11-18: getfield maxBytes; sipush 512; invokestatic TagLimiter.create(II) - the exact invocation the @Redirect replaces is present in the bytecode, and TagLimiter.create(II) delegates to create(III) with maxTags=262144 (javap TagLimiter). Constants: DEFAULT_MAX_BYTES=2097152, DEFAULT_MAX_NESTING_LEVEL=512, DEFAULT_MAX_TAGS=262144. Types.java:214 Types.TAG = new TagType() so limitBytes=true and maxBytes=2097152; Types.java:220 TRUSTED_TAG = new TagType(false) lifts only the byte cap and still keeps the 512-level and 262144-tag caps, so the 'point Types.TAG at TagType(false)' idea would not even be equivalent. Nothing in this tree does it anyway: grep -rn over src/main/java for TagLimiter, nbt.limiter, misc.TagType and TRUSTED_TAG returns zero hits (the only Types.TAG uses are reads/writes at ContainerAndLevelLoadingPatches.java:274 and 

**`features/entity/metadata/MixinCommonBoss.java`** — 0/1, 目标 `com.viaversion.viaversion.legacy.bossbar.CommonBoss (ViaVersion JAR)`

- `@Redirect ignoreHealthCheck` — No-ops the health precondition so out-of-range or NaN boss health can never throw; needed for every <=1.8 target that displays a wither/ender dragon bar, and it is the pair of the three EntityTracker1_9 redirects that deliberately produce unclamped health.
  - 落点: CommonBoss#<init> and CommonBoss#setHealth, at the `Preconditions.checkArgument(ZLjava/lang/Object;)V` calls (viaversion-common CommonBoss.java:56 and :78)
- 备注: Half the guard is genuinely unreachable here, the other half is a live crash, so NOT_APPLICABLE is not defensible. Nothing in the tree touches CommonBoss, BossBar, BossBarProvider or Via's legacyAPI (grep over com/viaversion/viafabricplus -> 0 hits), so Preconditions.checkArgument(health >= 0 && health <= 1) at CommonBoss.java:56 (ctor) and :78 (setHealth) is intact. Out-of-range health cannot occur, for a reason the audit omits: the paired MixinEntityTracker1_9 removeMin/removeMax are also unported, so Via's own clamp Math.max(0.0f, Math.min(v/maxHealth, 1.0f)) at EntityTracker1_9.java:268 still runs and keeps every finite value (and both infinities) inside [0,1]. NaN is the gap: Math.min(NaN,1)=NaN and Math.max(0,NaN)=NaN, and upstream's neutralizer for exactly that (MixinEntityTracker1_9#remapNaNToZero) is unported too. A <=1.8 server sending NaN as wither/ender-dragon entity-data id 6 therefore reaches createLegacyBossBar(title, NaN, ...) at EntityTracker1_9.java:271 or bar.setHealth(NaN) at :278 and throws IllegalArgumentException on the packet path; AbstractProtocol.transform:4

**`features/entity/metadata/MixinEntityPacketRewriter1_15.java`** — 0/1, 目标 `com.viaversion.viaversion.protocols.v1_14_4to1_15.rewriter.EntityPacketRewriter1_15 (ViaVersion JAR)`

- `@Redirect removeAndTrackHealth` — Replaces `filter().type(WOLF).removeIndex(18)` with a handler that stores the wolf health value in WolfHealthTracker1_14_4 (keyed by entity id), cancels that entry, and shifts indices >18 down by one - i.e. removeIndex's own renumbering plus the snapshot. Applies to <=1.14.4 targets.
  - 落点: EntityPacketRewriter1_15#registerRewrites, at the `EntityDataFilter$Builder#removeIndex(I)` invocation (viaversion-common EntityPacketRewriter1_15.java:132)
- 备注: The write half of the redirect was never rebuilt. grep -rn over all of src/main/java: zero references to Protocol1_14_4To1_15, EntityPacketRewriter1_15, EntityDataFilter or EntityTypes1_15, and WolfHealthTracker1_14_4#setWolfHealth (WolfHealthTracker1_14_4.java:48) has no caller anywhere in the tree. Via's own filter().type(WOLF).removeIndex(18) (EntityPacketRewriter1_15.java:132) therefore still runs unmodified, which does keep the half of upstream's replacement that is pure renumbering - EntityDataFilter.java:282-292 is exactly event.cancel() for index==18 and event.setIndex(dataIndex-1) for dataIndex>18 - so nothing is mis-numbered; what is missing is only the event.user().get(WolfHealthTracker1_14_4.class).setWolfHealth(event.entityId(), meta.value()) snapshot. ViaFabricPlusProtocol.java:120-121 still puts an (empty) tracker into every connection for serverVersion <= 1.14.4, so the storable exists but stays empty forever.

**`features/entity/metadata/MixinEntityTracker1_9.java`** — 0/3, 目标 `com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9 (ViaVersion JAR)`

- `@Redirect removeMin` — Returns the first argument of Math.min(value/maxHealth, 1.0F), i.e. removes the upper clamp so boss health above the assumed 200/300 max is not flattened to a full bar; <=1.8 targets, only when Via's bossbar-anti-flicker is disabled.
  - 落点: EntityTracker1_9#handleEntityData, the `Math.min(FF)F` call inside `float health = Math.max(0.0f, Math.min(((float) entityData.getValue()) / maxHealth, 1.0f))`, in the slice starting at the ViaVersionConfig#isBossbarAntiflicker() call (viaversion-common EntityTracker1_9.java:264-268)
- `@Redirect removeMax` — Returns the second argument of Math.max(0.0F, x), i.e. removes the lower clamp so negative boss health is passed through unchanged; <=1.8 targets, same branch.
  - 落点: EntityTracker1_9#handleEntityData, the `Math.max(FF)F` call of the same health expression, in the slice starting at the ViaVersionConfig#isBossbarAntiflicker() call (viaversion-common EntityTracker1_9.java:268)
- `@Redirect remapNaNToZero` — Wraps EntityData#getValue() in that branch so a Float NaN health becomes 0F; without it NaN survives both clamps and trips CommonBoss's precondition, throwing inside packet handling. <=1.8 targets, same branch.
  - 落点: EntityTracker1_9#handleEntityData, the `EntityData#getValue()` call feeding the health division, in the slice starting at the ViaVersionConfig#isBossbarAntiflicker() call (viaversion-common EntityTracker1_9.java:268)
- 备注: Nothing in the tree implements any of the three redirects, and the no-gap claim is not a proof - it is contradicted by the tree's own ledger. Reachability: the patched branch is EntityTracker1_9.java:264-281, guarded by isBossbarPatch() (default true, AbstractViaConfig.java:137 and assets/viaversion/config.yml:230) and !isBossbarAntiflicker() (default false, AbstractViaConfig.java:138, config.yml:232); ViaFabricPlusConfig overrides neither, so both defaults stand. It is on the packet path: handleEntityData is called from EntityPacketRewriter1_9.java:228 (SET_ENTITY_DATA) and SpawnPacketRewriter1_9.java:218/297. The clamps are still in the shipped jar (`Math.max(0.0f, Math.min(((float) entityData.getValue()) / maxHealth, 1.0f))`, EntityTracker1_9.java:268), so no fix is 'already in the jar'. grep over the whole tree for EntityTracker1_9 / BossBar / CommonBoss / bossbar finds only vanilla net.minecraft boss-bar code - no replacement route, no client-side equivalent, no config override. VFP_AUDIT.md:135 itself records 'Real gap covering all three redirects', while VFP_AUDIT.md:523 files

**`features/scoreboard/MixinComponentUtil.java`** — 1/1, 目标 `com.viaversion.viaversion.util.ComponentUtil (methods legacyToJson and legacyToJsonString(String, boolean))`

- `@Redirect dontSkipEmptySections` — Swaps StringFormat#fromString(String, ColorHandling, DeserializerUnknownHandling) for the 4-arg overload with false, so empty formatting-only sections survive the legacy->JSON conversion; applies to all pre-1.13 targets, ungated.
  - 落点: No vanilla site - library methods com.viaversion.viaversion.util.ComponentUtil#legacyToJson (line 187) and #legacyToJsonString(String,boolean) (line 195), at the StringFormat.vanilla().fromString(...) call in each.
- 备注: What IS present is correct and I verified it end to end. The flag's meaning is confirmed from the shaded mcstructs bytecode: StringFormat.fromString(String,ColorHandling,DeserializerUnknownHandling) delegates to the 4-arg overload with iconst_1, and inside the 4-arg body the boolean (iload 4) appears only at offsets 104-114 and 196-206, guarding `if (sb.length() > 0 \|\| !skipEmpty) components.add(...)` - so its ONLY effect is whether zero-length styled components are emitted; both overloads exist with the exact parameter types the patch uses. Protocol1_12_2To1_13Patches:304 reproduces ComponentUtil.legacyToJson with skipEmpty=false, and applyLegacyTextSections replaces SET_OBJECTIVE and SET_PLAYER_TEAM with byte-for-byte copies of Protocol1_12_2To1_13.java:401-419 and :421-470 (same passthrough/read/write order, same mode/action branches, Via.getConfig().is1_13TeamColourFix(), protocol.getLastColorChar - public at :879 - and a faithful copy of the protected rewriteTeamMemberName at :893-909 plus all 22 SCOREBOARD_TEAM_NAME_REWRITE entries from :92-113). CU is right (ClientboundPacke

## 用 ViaVersion 公开 API 重建 / rebuilt through the public protocol API

这些 mixin 的 `@Mixin` 目标是 ViaVersion 的类，源码树里没有文件可改，改为在 bootstrap 阶段用 Via 自己的公开协议 API 装同等行为，全部集中在 `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java`。

**时序是这里最重要的约束。** `ProtocolManager#registerProtocols` 只把实例放进 map；真正装 handler 的`loadMappingData -> initialize -> registerPackets` 是提交到 Via 的 mapping-loader 线程池异步跑的。抢在它之前打补丁会：`replace*` 找不到可替换的 handler 直接抛异常并杀掉整个 Via 启动；`append*` 退化成首次注册、之后被 Via 自己的注册撞成 `IllegalArgumentException`；`override = true` 的注册被静默覆盖。所以 `apply()` 先对每个 protocol `join` 它的 mapping-loader future，并且是在 `ViaManagerImpl.initAndLoad` **返回之后**调用，而不是从 `ViaFabricPlusPlatformLoader#load()` 里调用（后者本身就跑在那次 bootstrap 内部）。

| 上游 mixin | 重建方式 |
|---|---|
| `features/movement/packet/MixinEntityPacketRewriter1_21_2.java` | `appendServerbound(MOVE_PLAYER_STATUS_ONLY, w -> w.setCancelled(false))`；用 javap 确认 `lambda$registerPackets$14` 就是该 handler 且只含一个 `cancel()` |
| `features/networking/packet_handling/MixinEntityPacketRewriter1_19_4.java` | `registerClientbound(TELEPORT_ENTITY, TELEPORT_ENTITY, passthrough, override)` |
| `features/networking/remove_signed_commands/MixinProtocol1_20_3To1_20_5.java` | 5 个 `registerServerbound(..., override=true)`：拿掉 VV 的 chat-ack / session-update 处理，并把 `CHAT_COMMAND_SIGNED` 映射到普通命令包 |
| `features/networking/remove_signed_commands/MixinProtocol1_21_5To1_21_6.java` | `registerServerbound(CHANGE_GAME_MODE, CHAT_COMMAND, handler, true)` |
| `features/networking/config_state/MixinProtocol1_20To1_20_2.java` | 3 个 `registerServerbound(State.CONFIGURATION, id, -1, handler, true)`；排队分支逐字复现 Via 自己的 `queueServerboundPacket`，让 `queueConfigPackets` 设置真正生效 |
| `features/limitation/max_chat_length/MixinProtocol1_10To1_11.java` | 重注册 1.10→1.11 的 serverbound `CHAT`，把硬编码的 100 换成 `MaxChatLength.getChatLength()` |
| `features/classic/world_height/MixinEntityPacketRewriter1_17.java` | `appendClientbound(LOGIN/RESPAWN, WorldHeightSupport.handleJoinGame/handleRespawn(no-op parent))` —— 这两个 support 方法本来就是 parent-first 语义，与 append 完全等价 |
| `features/classic/world_height/MixinWorldPacketRewriter1_17.java` | `appendClientbound(LEVEL_CHUNK, handleChunkData(no-op parent))` 加 `replaceClientbound(LIGHT_UPDATE, handleUpdateLight(<Via 1.17 光照 handler 的逐字重实现>))` |
| `features/classic/world_height/MixinWorldPacketRewriter1_16_2.java` | `replaceClientbound(CHUNK_BLOCKS_UPDATE, ...)`，段数组 16 → 64 |
| `features/interaction/r1_18_2_block_ack_emulation/MixinWorldPacketRewriter1_19.java` | `registerClientbound(BLOCK_BREAK_ACK, CUSTOM_PAYLOAD, sync-task handler, override=true)`，顶掉 Via 的 `cancelClientbound` |
| `features/networking/packet_handling/MixinProtocol1_21_7To1_21_9.java` | `registerClientbound(CUSTOM_PAYLOAD, handler)`，把两个 game-test 调试 payload 变成 sync task；按上游原样不加 override |
| `features/networking/legacy_chat_signature/MixinYggdrasilUserApiService.java` | 在 `AccountProfileKeyPairManager` 里反射取 authlib 的 `minecraftClient` / `routeKeyPair` 重新发请求，把 1.19.0 的 legacy 签名写到 `ProfilePublicKey.Data` 上 |
| `features/networking/legacy_chat_signature/MixinKeyPairResponse.java` | 同上，legacy 签名不再需要挂在 authlib 的响应类上 |

### 明确决定不重建的一个 / one deliberately left alone

`features/networking/level_loading/MixinEntityPacketRewriter1_20_3#sendChunksSentGameEvent`（`@Overwrite` 成空方法）。它的效果是让 Via 不再在 LOGIN / RESPAWN / INITIALIZE_BORDER 时提前 send + cancel，也不再合成 `GAME_EVENT 13`（1.20.3 才有的包，≤1.20.2 服务端不可能自己发）。

公开 API 没有干净的等价物：`Protocol` 不暴露已注册 handler 的 getter，重建 LOGIN / RESPAWN 就得逐字段复制 Via 的 `map(...)` 列表；只拦合成的 `GAME_EVENT 13` 也不行，它是在该 protocol **之后**的链路上 `send` 的。

与本轮 `MixinLevelLoadingScreen` 移植的关系（审计提示过这个顺序有风险）：本轮内联的 `vfpLegacyTick()` 对 ≤1.20.2 目标**整体替换**了 vanilla 的 `tick()`，vanilla 那条 `loadTracker.isLevelReady() -> onClose()` 不再执行，合成出来的 `GAME_EVENT 13` 只更新一个此路径不再读取的 tracker，不会二次关屏。组合结果自洽，但**这一条属于已知行为偏差**。

## 与上游的故意偏差 / deliberate deviations from upstream

三处，都在代码里就地标注，理由都是“照抄上游会让功能在本树上失效或更差”：

1. **`Entity#getFluidInteractionBox`**（`features/movement/liquid/MixinEntity#skipPassengerChanges`）。上游 else 分支调用的是未限定的 `modifyPassengerFluidInteractionBox(...)`，即在 **passenger** 上调用，而 vanilla 是在 vehicle 上调用。只有 `AbstractBoat` 覆写了它，所以照抄会让船的流体盒调整在**所有**版本（含原生 26.2）失效。这里保留 vanilla 的接收者，只在 target 新于 1.21.11 时与上游不同。
2. **`MultiPlayerGameMode#startPrediction`**（`r1_18_2_block_ack_emulation/MixinMultiPlayerGameMode#trackPlayerAction`）。上游在 HEAD 判 `predictiveAction instanceof ServerboundPlayerActionPacket`，而 26.2 的 `PredictiveAction` 是函数式接口、该包不实现它、六个调用点全传 lambda —— 上游那个条件永远为假。这里改判 `predict()` 返回的包，于是 START/STOP_DESTROY_BLOCK 真的被跟踪，符合真实 1.18.2 行为。
3. **`MouseHandler#setup`**（`execute_inputs_sync/MixinMouseHandler#storeEvent`）。上游写的目标是 `lambda$setup$1` 与 `lambda$setup$2`；按 26.2 的 lambda 命名，`setup` 的四个回调 desugar 成 `$0` 光标外层 / `$1` 其内层 `onMove` Runnable / `$2` 按键外层 / `$3` 其内层，只有 `$2` 里有 `Minecraft#execute` 调用。所以上游实际只排队鼠标按键。这里也只包按键回调 —— 把光标移动一起排队会让 GUI 悬停/拖拽从每帧降到每 tick 更新，那是上游没有的行为。

## 库目标 mixin（NOT_APPLICABLE）/ library-target mixins

剩下 9 个 mixin 的目标是 jar 依赖里的类，且没有公开 API 路线。凡是仍有真实行为缺口的，下表 `备注` 列写明。

| 上游 mixin | 目标类 | 优先级 | hook | 备注 |
|---|---|---|---|---|
| `compat/fabricapi/MixinClientRegistrySyncHandler.java` | `net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` | P3 | 1 | The @Mixin target is absent from this tree and cannot exist, proven by enumeration rather than assertion. find over src/main/java/net/fabricmc returns 15 files: api/client/command/v2 (2), api/client/event/lifecycle/v1, api/client/particle/v1, api/event (2), api/networking/v1/PayloadTypeRegistry, api/particle/v1, and loader/api (7). There is no net/fabricmc/fabric/impl package at all, so neither net.fabricmc.fabric.im |
| `compat/ipnext/MixinAutoRefillHandler_ItemSlotMonitor.java` | `org.anti_ad.mc.ipnext.event.AutoRefillHandler$ItemSlotMonitor (@Pseudo, Inventory Profiles` | P3 | 3 | Proof, not plausibility, on two independent grounds. (1) The @Pseudo target cannot be loaded: I scanned the central directory of all 140 jars in target/dependency with unzip -Z1 for 'anti_ad' and got zero hits, and there is no mod-loading mechanism in this tree, so org.anti_ad.mc.ipnext.event.AutoRefillHandler$ItemSlotMonitor does not exist on any classpath - checkHandle/checkShouldHandle/updateCurrent have no bodies |
| `compat/mcstructs/MixinTextComponentSerializer.java` | `com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer (shaded i` | P3 | 1 | Bytecode proof, not a plausibility argument. The @Overwrite replaces deserialize(String) with `if (legacyGson) { LegacyGson.checkStartingType(json, true); json = LegacyGson.fixInvalidEscapes(json); } return getGson().fromJson(json, TextComponent.class)`. javap of the shipped class shows deserialize(String) is already exactly that: 0 getfield legacyGson / 4 ifeq 17 / 8 iconst_1 (the boolean is true, matching upstream) |
| `compat/minecraftauth/MixinClasses.java` | `io.jsonwebtoken.lang.Classes (jjwt-api-0.13.0.jar, via MinecraftAuth)` | P3 | 1 | Target io.jsonwebtoken.lang.Classes is a jar class (jjwt-api-0.13.0.jar) and I have a real proof, not a plausibility argument, that there is no behaviour gap here. javap -c of Classes.forName shows the three-step heuristic THREAD_CL_ACCESSOR -> CLASS_CL_ACCESSOR -> SYSTEM_CL_ACCESSOR, throwing UnknownClassException only if all three miss; the @Overwrite exists purely because those three are not the same loader under  |
| `compat/minecraftauth/MixinDefaultJwtParserBuilder.java` | `io.jsonwebtoken.impl.DefaultJwtParserBuilder (jjwt-impl-0.13.0.jar, via MinecraftAuth)` | P3 | 1 | Real proof, not a plausibility argument, and it is bytecode plus a jar entry. The mixin @Redirects Services.get(Class) inside DefaultJwtParserBuilder.build() and returns new GsonDeserializer<>(), purely to survive a classloader that cannot see service files. javap of jjwt-impl-0.13.0.jar shows build() at offsets 0-20: `if (this.deserializer == null) json((Deserializer) Services.get(Deserializer.class))` - one call, o |
| `features/bedrock/allow_new_line/MixinFont.java` | `net.minecraft.client.gui.Font` | P3 | 0 | Feature is disabled upstream on ver/26.2 (VFP itself has not re-mapped it to the new Font.PreparedText API). If it is ever wanted, the landing sites are Font#prepareText(String,float,float,int,boolean,int) and Font#prepareText(FormattedCharSequence,...) at HEAD, plus Font#width(FormattedText); the commented code is also self-inconsistent (references ci/str/drawInBatch), so it would have to be rewritten, not transcrib |
| `features/world/footstep_particle/MixinMappingDataBase.java` | `com.viaversion.viaversion.api.data.MappingDataBase (viaversion-common JAR, 5.12.0-20260819` | P3 | 1 | Jar target (MappingDataBase in viaversion-common) plus a real proof that the guarded condition cannot arise: the footstep feature was rebuilt so RAW_ID never enters a ViaVersion mapping at all. MixinParticleIdMappings1_13#replaceFootStepId was deliberately not ported, so the table still reads add(-1); // (28->-1) footstep -> REMOVED at ParticleIdMappings1_13.java:65, and Via's own LEVEL_PARTICLES handler cancels the  |
| `features/world/footstep_particle/MixinParticleMappings.java` | `com.viaversion.viaversion.api.data.ParticleMappings (viaversion-common JAR; mixin declares` | P3 | 2 | Real proof: the guarded value can never reach either method in this tree. Both overrides only fire for FootStepParticle1_12_2.RAW_ID, and RAW_ID is never handed to a ViaVersion mapping - grep across the whole tree shows its only two uses are BuiltInRegistries.PARTICLE_TYPE.byId(RAW_ID) at Protocol1_12_2To1_13Patches.java:206 and a log line at :163. The upstream design that put RAW_ID into Via's id space (MixinParticl |
| `features/world/footstep_particle/MixinRegistrySyncManager.java` | `net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager (Fabric API impl class)` | P3 | 1 | The hook's whole job is to hide the synthetic viafabricplus:footstep particle from the map Fabric API's RegistrySyncManager#createAndPopulateRegistryMap builds and ships to the server. The host of that behaviour does not exist here, proven by absence rather than by argument: net.fabricmc in this tree is 15 hand-written stub files (net/fabricmc/fabric/api/{client/command/v2, client/event/lifecycle/v1, client/particle/ |

## 全量账本 / full ledger (368)

`Sigma 位置` 为空表示行为不落在源码树里（REPLACED 落在 `ViaFabricPlusProtocolPatches`，NOT_APPLICABLE 无落点）。

### `compat/classic4j` — 5/5 hook, COMPLETE 1, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCCAuthenticationResponse.java` | `de.florianreuth.classic4j.model.classicube.CCAuthenticationResponse (classic4j-2` | 1 | 1 | REPLACED | P3 | `com/viaversion/viafabricplus/screen/impl/classic4j/ClassiCubeErrorTranslations.java:68` |
| `MixinEditBox.java` | `net.minecraft.client.gui.components.EditBox` | 4 | 4 | COMPLETE | P3 | `net/minecraft/client/gui/components/EditBox.java` |

### `compat/fabricapi` — 0/1 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientRegistrySyncHandler.java` | `net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` | 1 | 0 | NOT_APPLICABLE | P3 | — |

### `compat/ipnext` — 0/3 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAutoRefillHandler_ItemSlotMonitor.java` | `org.anti_ad.mc.ipnext.event.AutoRefillHandler$ItemSlotMonitor (@Pseudo, Inventor` | 3 | 0 | NOT_APPLICABLE | P3 | — |

### `compat/lithium` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity (method lithium$CollideMovement, injected by L` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |

### `compat/mcstructs` — 0/1 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinTextComponentSerializer.java` | `com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer` | 1 | 0 | NOT_APPLICABLE | P3 | `target/dependency/viaversion-common-5.12.0-SNAPSHOT.jar!com/viaversion/viaversion/libs/mcstructs/text/serializer/TextComponentSerializer.class#deserialize(String)` |

### `compat/minecraftauth` — 0/2 hook, NOT_APPLICABLE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClasses.java` | `io.jsonwebtoken.lang.Classes (jjwt-api-0.13.0.jar, via MinecraftAuth)` | 1 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinDefaultJwtParserBuilder.java` | `io.jsonwebtoken.impl.DefaultJwtParserBuilder (jjwt-impl-0.13.0.jar, via Minecraf` | 1 | 0 | NOT_APPLICABLE | P3 | — |

### `core` — 2/2 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMain.java` | `net.minecraft.client.main.Main` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/main/Main.java` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/Minecraft.java` |

### `core/access` — 9/9 hook, COMPLETE 1, REPLACED 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinChunkTracker.java` | `net.raphimc.viabedrock.protocol.storage.ChunkTracker (ViaBedrock-0.0.29-SNAPSHOT` | 3 | 3 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/LibraryFieldAccessPatches.java:127` |
| `MixinExtensionProtocolMetadataStorage.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.storage.ExtensionProto` | 1 | 1 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/LibraryFieldAccessPatches.java:168` |
| `MixinLocalSampleLogger.java` | `net.minecraft.util.debugchart.LocalSampleLogger` | 3 | 3 | COMPLETE | P0 | `net/minecraft/util/debugchart/LocalSampleLogger.java` |
| `MixinRakSessionCodec.java` | `org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec (netty-transp` | 2 | 2 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/LibraryFieldAccessPatches.java:147 and :154, consumed at VFPDebugHudEntry.java:97-98` |

### `core/connection` — 13/13 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientHandshakePacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java` |
| `MixinConnection.java` | `net.minecraft.network.Connection` | 9 | 9 | COMPLETE | P0 | `net/minecraft/network/Connection.java` |
| `MixinConnection_1.java` | `net.minecraft.network.Connection$1 (anonymous ChannelInitializer created in Conn` | 1 | 1 | COMPLETE | P0 | `net/minecraft/network/Connection.java` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/Minecraft.java` |
| `MixinServerStatusPinger.java` | `net.minecraft.client.multiplayer.ServerStatusPinger` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` |

### `core/connection/bedrock` — 13/13 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the "Server Connector #N" Thre` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` |
| `MixinConnection.java` | `net.minecraft.network.Connection (second mixin, priority 1001)` | 5 | 5 | COMPLETE | P0 | `net/minecraft/network/Connection.java` |
| `MixinEventLoopGroupHolder.java` | `net.minecraft.server.network.EventLoopGroupHolder` | 3 | 3 | COMPLETE | P0 | `net/minecraft/server/network/EventLoopGroupHolder.java` |
| `MixinServerAddress.java` | `net.minecraft.client.multiplayer.resolver.ServerAddress` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/resolver/ServerAddress.java` |
| `MixinServerNameResolver.java` | `net.minecraft.client.multiplayer.resolver.ServerNameResolver` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/resolver/ServerNameResolver.java` |

### `core/gui` — 8/8 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinDirectJoinServerScreen.java` | `net.minecraft.client.gui.screens.DirectJoinServerScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/DirectJoinServerScreen.java` |
| `MixinJoinMultiplayerScreen.java` | `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.java` |
| `MixinLevelLoadingScreen.java` | `net.minecraft.client.gui.screens.LevelLoadingScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/LevelLoadingScreen.java` |
| `MixinManageServerScreen.java` | `net.minecraft.client.gui.screens.ManageServerScreen` | 3 | 3 | COMPLETE | P3 | `net/minecraft/client/gui/screens/ManageServerScreen.java` |
| `MixinServerSelectionList_OnlineServerEntry.java` | `net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEnt` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/multiplayer/ServerSelectionList.java` |

### `core/integration` — 19/19 hook, COMPLETE 7, REPLACED 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread created i` | 4 | 4 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` |
| `MixinConnection.java` | `net.minecraft.network.Connection` | 2 | 2 | COMPLETE | P3 | `net/minecraft/network/Connection.java:161-170 (printNetworkingErrors only)` |
| `MixinDebugScreenEntries.java` | `net.minecraft.client.gui.components.debug.DebugScreenEntries` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/components/debug/DebugScreenEntries.java` |
| `MixinJoinMultiplayerScreen.java` | `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.java` |
| `MixinServerData.java` | `net.minecraft.client.multiplayer.ServerData` | 4 | 4 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ServerData.java` |
| `MixinServerStatusPinger_1.java` | `net.minecraft.client.multiplayer.ServerStatusPinger$1 (the anonymous ClientStatu` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` |
| `MixinUserConnectionImpl.java` | `com.viaversion.viaversion.connection.UserConnectionImpl` | 1 | 1 | REPLACED | P1 | `com/viaversion/viafabricplus/protocoltranslator/util/NoPacketSendUserConnection.java:50 and :59, installed at ProtocolTranslator.java:246` |
| `MixinViaBedrockConfig.java` | `net.raphimc.viabedrock.ViaBedrockConfig` | 1 | 1 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/platform/ViaFabricPlusViaBedrockConfig.java:46, carried in by ViaFabricPlusViaBedrockPlatform.java:37 (installed at ProtocolTranslator.java:318)` |
| `MixinViaLegacyConfig.java` | `net.raphimc.vialegacy.ViaLegacyConfig` | 1 | 1 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/platform/ViaFabricPlusViaLegacyConfig.java:48 and :53, wired at ViaFabricPlusViaLegacyPlatform.java:42` |

### `core/integration/bedrock` — 3/3 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinConfirmScreen.java` | `net.minecraft.client.gui.screens.ConfirmScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/ConfirmScreen.java` |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread created i` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` |
| `MixinServerStatusPinger.java` | `net.minecraft.client.multiplayer.ServerStatusPinger` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` |

### `core/integration/event` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/Minecraft.java` |

### `core/integration/sync_tasks` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientCommonPacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java` |

### `features/april_fools_8bit_sound` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinSoundBuffer.java` | `com.mojang.blaze3d.audio.SoundBuffer` | 1 | 1 | COMPLETE | P3 | `com/mojang/blaze3d/audio/SoundBuffer.java` |

### `features/bedrock/allow_new_line` — 0/0 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinFont.java` | `net.minecraft.client.gui.Font` | 0 | 0 | NOT_APPLICABLE | P3 | — |

### `features/bedrock/block` — 20/20 hook, COMPLETE 12

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBambooStalkBlock.java` | `net.minecraft.world.level.block.BambooStalkBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/BambooStalkBlock.java` |
| `MixinBlockBehaviour_Properties.java` | `net.minecraft.world.level.block.state.BlockBehaviour$Properties` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/state/BlockBehaviour.java` |
| `MixinCactusBlock.java` | `net.minecraft.world.level.block.CactusBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/CactusBlock.java` |
| `MixinCandleCakeBlock.java` | `net.minecraft.world.level.block.CandleCakeBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/CandleCakeBlock.java` |
| `MixinConduitBlock.java` | `net.minecraft.world.level.block.ConduitBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/ConduitBlock.java` |
| `MixinDoorBlock.java` | `net.minecraft.world.level.block.DoorBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/DoorBlock.java` |
| `MixinDragonEggBlock.java` | `net.minecraft.world.level.block.DragonEggBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/DragonEggBlock.java` |
| `MixinHoneyBlock.java` | `net.minecraft.world.level.block.HoneyBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/HoneyBlock.java` |
| `MixinLanternBlock.java` | `net.minecraft.world.level.block.LanternBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/LanternBlock.java` |
| `MixinLecternBlock.java` | `net.minecraft.world.level.block.LecternBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/LecternBlock.java` |
| `MixinSeaPickleBlock.java` | `net.minecraft.world.level.block.SeaPickleBlock` | 3 | 3 | COMPLETE | P2 | `net/minecraft/world/level/block/SeaPickleBlock.java` |
| `MixinTrapDoorBlock.java` | `net.minecraft.world.level.block.TrapDoorBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/TrapDoorBlock.java` |

### `features/bedrock/chat` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientSuggestionProvider.java` | `net.minecraft.client.multiplayer.ClientSuggestionProvider` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientSuggestionProvider.java` |

### `features/bedrock/inventory` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinInventoryScreen.java` | `net.minecraft.client.gui.screens.inventory.InventoryScreen` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/gui/screens/inventory/InventoryScreen.java` |

### `features/bedrock/movement` — 17/17 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinHoneyBlock.java` | `net.minecraft.world.level.block.HoneyBlock` | 6 | 6 | COMPLETE | P1 | `net/minecraft/world/level/block/HoneyBlock.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 6 | 6 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/bedrock/networking` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinServerNameResolver.java` | `net.minecraft.client.multiplayer.resolver.ServerNameResolver` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/resolver/ServerNameResolver.java` |

### `features/bedrock/reach_around_raycast` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/Minecraft.java` |

### `features/block/connections` — 4/4 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientChunkCache.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 4 | 4 | COMPLETE | P2 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |

### `features/block/interaction` — 16/16 hook, COMPLETE 11

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCanPlaceAt1_14.java` | `net.minecraft.world.level.block.BaseTorchBlock, net.minecraft.world.level.block.` | 3 | 3 | COMPLETE | P2 | `net/minecraft/world/level/block/BaseTorchBlock.java` |
| `MixinDecoratedPotBlock.java` | `net.minecraft.world.level.block.DecoratedPotBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/DecoratedPotBlock.java` |
| `MixinFenceBlock.java` | `net.minecraft.world.level.block.FenceBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/FenceBlock.java` |
| `MixinFlowerPotBlock.java` | `net.minecraft.world.level.block.FlowerPotBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/FlowerPotBlock.java` |
| `MixinIronBarsBlock.java` | `net.minecraft.world.level.block.IronBarsBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/IronBarsBlock.java` |
| `MixinNoteBlock.java` | `net.minecraft.world.level.block.NoteBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/NoteBlock.java` |
| `MixinRedStoneWireBlock.java` | `net.minecraft.world.level.block.RedStoneWireBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/RedStoneWireBlock.java` |
| `MixinRespawnAnchorBlock.java` | `net.minecraft.world.level.block.RespawnAnchorBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/RespawnAnchorBlock.java` |
| `MixinShelfBlock.java` | `net.minecraft.world.level.block.ShelfBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/ShelfBlock.java` |
| `MixinSignBlock.java` | `net.minecraft.world.level.block.SignBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/SignBlock.java` |
| `MixinSimpleWaterloggedBlock.java` | `net.minecraft.world.level.block.SimpleWaterloggedBlock (interface default method` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/SimpleWaterloggedBlock.java` |

### `features/block/mining_calculation` — 5/5 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockBehaviour.java` | `net.minecraft.world.level.block.state.BlockBehaviour` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/level/block/state/BlockBehaviour.java` |
| `MixinBlockBehaviour_BlockStateBase.java` | `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/level/block/state/BlockBehaviour.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/block/shape` — 61/69 hook, COMPLETE 30

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractCauldronBlock.java` | `net.minecraft.world.level.block.AbstractCauldronBlock` | 4 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/AbstractCauldronBlock.java` |
| `MixinAnvilBlock.java` | `net.minecraft.world.level.block.AnvilBlock` | 3 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/AnvilBlock.java` |
| `MixinBaseRailBlock.java` | `net.minecraft.world.level.block.BaseRailBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/BaseRailBlock.java` |
| `MixinBedBlock.java` | `net.minecraft.world.level.block.BedBlock` | 3 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/BedBlock.java` |
| `MixinBrewingStandBlock.java` | `net.minecraft.world.level.block.BrewingStandBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/BrewingStandBlock.java` |
| `MixinCarpetBlock.java` | `net.minecraft.world.level.block.CarpetBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/CarpetBlock.java` |
| `MixinChestBlock.java` | `net.minecraft.world.level.block.ChestBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/ChestBlock.java` |
| `MixinCropBlocks.java` | `net.minecraft.world.level.block.CropBlock, net.minecraft.world.level.block.Carro` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/level/block/CropBlock.java` |
| `MixinCrossCollisionBlock.java` | `net.minecraft.world.level.block.CrossCollisionBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/CrossCollisionBlock.java` |
| `MixinEndPortalBlock.java` | `net.minecraft.world.level.block.EndPortalBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/EndPortalBlock.java` |
| `MixinEndPortalFrameBlock.java` | `net.minecraft.world.level.block.EndPortalFrameBlock` | 3 | 3 | COMPLETE | P2 | `net/minecraft/world/level/block/EndPortalFrameBlock.java` |
| `MixinEnderChestBlock.java` | `net.minecraft.world.level.block.EnderChestBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/EnderChestBlock.java` |
| `MixinFarmlandBlock.java` | `net.minecraft.world.level.block.FarmlandBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/FarmlandBlock.java` |
| `MixinFenceBlock.java` | `net.minecraft.world.level.block.FenceBlock` | 5 | 5 | COMPLETE | P2 | `net/minecraft/world/level/block/FenceBlock.java` |
| `MixinFenceGateBlock.java` | `net.minecraft.world.level.block.FenceGateBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/FenceGateBlock.java` |
| `MixinFireBlock.java` | `net.minecraft.world.level.block.FireBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/FireBlock.java` |
| `MixinFlowerBedBlock.java` | `net.minecraft.world.level.block.FlowerBedBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/FlowerBedBlock.java` |
| `MixinHopperBlock.java` | `net.minecraft.world.level.block.HopperBlock` | 4 | 2 | COMPLETE | P3 | `net/minecraft/world/level/block/HopperBlock.java:80` |
| `MixinIronBarsBlock.java` | `net.minecraft.world.level.block.IronBarsBlock` | 5 | 5 | COMPLETE | P2 | `net/minecraft/world/level/block/IronBarsBlock.java` |
| `MixinLadderBlock.java` | `net.minecraft.world.level.block.LadderBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/LadderBlock.java` |
| `MixinLeavesBlock.java` | `net.minecraft.world.level.block.LeavesBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/LeavesBlock.java` |
| `MixinLilyPadBlock.java` | `net.minecraft.world.level.block.LilyPadBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/LilyPadBlock.java` |
| `MixinPistonBaseBlock.java` | `net.minecraft.world.level.block.piston.PistonBaseBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/piston/PistonBaseBlock.java` |
| `MixinPistonHeadBlock.java` | `net.minecraft.world.level.block.piston.PistonHeadBlock` | 3 | 3 | COMPLETE | P2 | `net/minecraft/world/level/block/piston/PistonHeadBlock.java` |
| `MixinPitcherCropBlock.java` | `net.minecraft.world.level.block.PitcherCropBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/PitcherCropBlock.java` |
| `MixinRedStoneWireBlock.java` | `net.minecraft.world.level.block.RedStoneWireBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/RedStoneWireBlock.java` |
| `MixinSnowLayerBlock.java` | `net.minecraft.world.level.block.SnowLayerBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/SnowLayerBlock.java` |
| `MixinSoulSandBlock.java` | `net.minecraft.world.level.block.SoulSandBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/SoulSandBlock.java` |
| `MixinTransparentBlock.java` | `net.minecraft.world.level.block.TransparentBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/TransparentBlock.java` |
| `MixinWallBlock.java` | `net.minecraft.world.level.block.WallBlock` | 10 | 10 | COMPLETE | P2 | `net/minecraft/world/level/block/WallBlock.java` |

### `features/classic/cpe_extension` — 7/7 hook, COMPLETE 1, REPLACED 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClassicProtocolExtension.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolEx` | 3 | 3 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ClassicCpeExtensionPatches.java:164` |
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientLevel.java` |
| `MixinClientboundPacketsc0_30cpe.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.packet.ClientboundPack` | 1 | 1 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ClassicCpeExtensionPatches.java:171 (registerPreNettyPacketId), called at :115` |
| `MixinProtocolc0_30cpeToc0_28_30.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.Protocolc0_30cpeToc0_2` | 2 | 2 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ClassicCpeExtensionPatches.java:106-165` |

### `features/classic/world_height` — 3/3 hook, REPLACED 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.EntityPacketRewriter1` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinWorldPacketRewriter1_16_2.java` | `com.viaversion.viaversion.protocols.v1_16_1to1_16_2.rewriter.WorldPacketRewriter` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinWorldPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.WorldPacketRewriter1_` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |

### `features/entity/allow_duplicated_uuid` — 2/2 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntityLookup.java` | `net.minecraft.world.level.entity.EntityLookup` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/level/entity/EntityLookup.java` |

### `features/entity/attribute` — 2/2 hook, COMPLETE 1, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.EntityPacketRewrite` | 1 | 1 | REPLACED | P1 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/EntityAttributePatches.java:82-140` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |

### `features/entity/dimensions` — 52/52 hook, COMPLETE 39

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/vehicle/boat/AbstractBoat.java` |
| `MixinAbstractChestedHorse.java` | `net.minecraft.world.entity.animal.equine.AbstractChestedHorse` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/AbstractChestedHorse.java` |
| `MixinArmadillo.java` | `net.minecraft.world.entity.animal.armadillo.Armadillo` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/armadillo/Armadillo.java` |
| `MixinAxolotl.java` | `net.minecraft.world.entity.animal.axolotl.Axolotl` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/axolotl/Axolotl.java` |
| `MixinCamel.java` | `net.minecraft.world.entity.animal.camel.Camel` | 6 | 6 | COMPLETE | P1 | `net/minecraft/world/entity/animal/camel/Camel.java` |
| `MixinCamelHusk.java` | `net.minecraft.world.entity.animal.camel.CamelHusk` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/camel/CamelHusk.java` |
| `MixinCat.java` | `net.minecraft.world.entity.animal.feline.Cat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/feline/Cat.java` |
| `MixinChicken.java` | `net.minecraft.world.entity.animal.chicken.Chicken` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/chicken/Chicken.java` |
| `MixinCow.java` | `net.minecraft.world.entity.animal.cow.Cow` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/Cow.java` |
| `MixinDolphin.java` | `net.minecraft.world.entity.animal.dolphin.Dolphin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/dolphin/Dolphin.java` |
| `MixinDrowned.java` | `net.minecraft.world.entity.monster.zombie.Drowned` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/monster/zombie/Drowned.java` |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinFox.java` | `net.minecraft.world.entity.animal.fox.Fox` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/fox/Fox.java` |
| `MixinGoat.java` | `net.minecraft.world.entity.animal.goat.Goat` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/animal/goat/Goat.java` |
| `MixinHappyGhast.java` | `net.minecraft.world.entity.animal.happyghast.HappyGhast` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/happyghast/HappyGhast.java` |
| `MixinHoglin.java` | `net.minecraft.world.entity.monster.hoglin.Hoglin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/hoglin/Hoglin.java` |
| `MixinHorse.java` | `net.minecraft.world.entity.animal.equine.Horse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/Horse.java` |
| `MixinHusk.java` | `net.minecraft.world.entity.monster.zombie.Husk` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/monster/zombie/Husk.java` |
| `MixinItemFrame.java` | `net.minecraft.world.entity.decoration.ItemFrame` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/decoration/ItemFrame.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinMushroomCow.java` | `net.minecraft.world.entity.animal.cow.MushroomCow` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/MushroomCow.java` |
| `MixinNautilus.java` | `net.minecraft.world.entity.animal.nautilus.Nautilus` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/nautilus/Nautilus.java` |
| `MixinOcelot.java` | `net.minecraft.world.entity.animal.feline.Ocelot` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/feline/Ocelot.java` |
| `MixinPanda.java` | `net.minecraft.world.entity.animal.panda.Panda` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/panda/Panda.java` |
| `MixinPig.java` | `net.minecraft.world.entity.animal.pig.Pig` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/pig/Pig.java` |
| `MixinPiglin.java` | `net.minecraft.world.entity.monster.piglin.Piglin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/piglin/Piglin.java` |
| `MixinPolarBear.java` | `net.minecraft.world.entity.animal.polarbear.PolarBear` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/polarbear/PolarBear.java` |
| `MixinRabbit.java` | `net.minecraft.world.entity.animal.rabbit.Rabbit` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/rabbit/Rabbit.java` |
| `MixinSheep.java` | `net.minecraft.world.entity.animal.sheep.Sheep` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/sheep/Sheep.java` |
| `MixinShulker.java` | `net.minecraft.world.entity.monster.Shulker` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/Shulker.java` |
| `MixinSkeletonHorse.java` | `net.minecraft.world.entity.animal.equine.SkeletonHorse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/SkeletonHorse.java` |
| `MixinSquid.java` | `net.minecraft.world.entity.animal.squid.Squid` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/squid/Squid.java` |
| `MixinStrider.java` | `net.minecraft.world.entity.monster.Strider` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/Strider.java` |
| `MixinVillager.java` | `net.minecraft.world.entity.npc.villager.Villager` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/npc/villager/Villager.java` |
| `MixinWolf.java` | `net.minecraft.world.entity.animal.wolf.Wolf` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/wolf/Wolf.java` |
| `MixinZoglin.java` | `net.minecraft.world.entity.monster.Zoglin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/Zoglin.java` |
| `MixinZombie.java` | `net.minecraft.world.entity.monster.zombie.Zombie` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/zombie/Zombie.java` |
| `MixinZombieVillager.java` | `net.minecraft.world.entity.monster.zombie.ZombieVillager` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/monster/zombie/ZombieVillager.java` |
| `MixinZombifiedPiglin.java` | `net.minecraft.world.entity.monster.zombie.ZombifiedPiglin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/zombie/ZombifiedPiglin.java` |

### `features/entity/interaction` — 22/22 hook, COMPLETE 17

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/vehicle/boat/AbstractBoat.java` |
| `MixinAbstractChestBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractChestBoat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/vehicle/boat/AbstractChestBoat.java` |
| `MixinAbstractCow.java` | `net.minecraft.world.entity.animal.cow.AbstractCow` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/AbstractCow.java` |
| `MixinAbstractHorse.java` | `net.minecraft.world.entity.animal.equine.AbstractHorse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/AbstractHorse.java` |
| `MixinAnimal.java` | `net.minecraft.world.entity.animal.Animal` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/animal/Animal.java` |
| `MixinArmadillo.java` | `net.minecraft.world.entity.animal.armadillo.Armadillo` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/armadillo/Armadillo.java` |
| `MixinAxolotl.java` | `net.minecraft.world.entity.animal.axolotl.Axolotl` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/axolotl/Axolotl.java` |
| `MixinBee.java` | `net.minecraft.world.entity.animal.bee.Bee` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/bee/Bee.java` |
| `MixinCamel.java` | `net.minecraft.world.entity.animal.camel.Camel` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/camel/Camel.java` |
| `MixinCat.java` | `net.minecraft.world.entity.animal.feline.Cat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/feline/Cat.java` |
| `MixinCreeper.java` | `net.minecraft.world.entity.monster.Creeper` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/entity/monster/Creeper.java` |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinMob.java` | `net.minecraft.world.entity.Mob` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/Mob.java` |
| `MixinMushroomCow.java` | `net.minecraft.world.entity.animal.cow.MushroomCow` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/MushroomCow.java` |
| `MixinSquid.java` | `net.minecraft.world.entity.animal.squid.Squid` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/squid/Squid.java` |
| `MixinWolf.java` | `net.minecraft.world.entity.animal.wolf.Wolf` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/wolf/Wolf.java` |
| `MixinZombieVillager.java` | `net.minecraft.world.entity.monster.zombie.ZombieVillager` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/zombie/ZombieVillager.java` |

### `features/entity/legacy_boat_model` — 16/16 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 12 | 12 | COMPLETE | P1 | `net/minecraft/world/entity/vehicle/boat/AbstractBoat.java` |
| `MixinEntityRenderDispatcher.java` | `net.minecraft.client.renderer.entity.EntityRenderDispatcher` | 3 | 3 | COMPLETE | P3 | `net/minecraft/client/renderer/entity/EntityRenderDispatcher.java` |
| `MixinLayerDefinitions.java` | `net.minecraft.client.model.geom.LayerDefinitions` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/model/geom/LayerDefinitions.java` |

### `features/entity/metadata` — 2/7 hook, COMPLETE 1, MISSING 3, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCommonBoss.java` | `com.viaversion.viaversion.legacy.bossbar.CommonBoss (ViaVersion JAR)` | 1 | 0 | MISSING | P2 | — |
| `MixinEntityPacketRewriter1_15.java` | `com.viaversion.viaversion.protocols.v1_14_4to1_15.rewriter.EntityPacketRewriter1` | 1 | 0 | MISSING | P2 | — |
| `MixinEntityPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.EntityPacketRewriter1_9 (` | 1 | 1 | REPLACED | P1 | `net/minecraft/client/multiplayer/ClientPacketListener.java:703-713 (helper at src/main/java/net/minecraft/world/entity/LivingEntity.java:193)` |
| `MixinEntityTracker1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9 (ViaVersi` | 3 | 0 | MISSING | P2 | — |
| `MixinWolf.java` | `net.minecraft.world.entity.animal.wolf.Wolf` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/entity/animal/wolf/Wolf.java` |

### `features/entity/pose` — 2/2 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |
| `MixinRemotePlayer.java` | `net.minecraft.client.player.RemotePlayer` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/RemotePlayer.java` |

### `features/execute_inputs_sync` — 7/7 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinKeyboardHandler.java` | `net.minecraft.client.KeyboardHandler` | 3 | 3 | COMPLETE | P0 | `net/minecraft/client/KeyboardHandler.java` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/Minecraft.java` |
| `MixinMouseHandler.java` | `net.minecraft.client.MouseHandler` | 3 | 3 | COMPLETE | P0 | `net/minecraft/client/MouseHandler.java` |

### `features/font` — 8/8 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinFontSet.java` | `net.minecraft.client.gui.font.FontSet` | 8 | 8 | COMPLETE | P3 | `net/minecraft/client/gui/font/FontSet.java` |

### `features/interaction` — 4/4 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/interaction/container_clicking` — 21/21 hook, COMPLETE 6, REPLACED 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractContainerMenu.java` | `net.minecraft.world.inventory.AbstractContainerMenu` | 4 | 4 | COMPLETE | P0 | `net/minecraft/world/inventory/AbstractContainerMenu.java` |
| `MixinAbstractContainerScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen` | 4 | 4 | COMPLETE | P1 | `net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java` |
| `MixinAbstractFurnaceMenu.java` | `net.minecraft.world.inventory.AbstractFurnaceMenu` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/inventory/AbstractFurnaceMenu.java` |
| `MixinBlockItemPacketRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewr` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ContainerAndLevelLoadingPatches.java:136` |
| `MixinCraftingMenu.java` | `net.minecraft.world.inventory.CraftingMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/CraftingMenu.java` |
| `MixinEntityTrackerBase.java` | `com.viaversion.viaversion.data.entity.EntityTrackerBase` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ContainerAndLevelLoadingPatches.java:176` |
| `MixinItemPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.ItemPacketRewriter1_1` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ContainerAndLevelLoadingPatches.java:148-157` |
| `MixinMerchantMenu.java` | `net.minecraft.world.inventory.MerchantMenu` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/inventory/MerchantMenu.java` |
| `MixinMultiPlayerGameMode.java` | `net.minecraft.client.multiplayer.MultiPlayerGameMode` | 5 | 5 | COMPLETE | P0 | `net/minecraft/client/multiplayer/MultiPlayerGameMode.java` |

### `features/interaction/cooldown` — 6/6 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinItemCooldowns.java` | `net.minecraft.world.item.ItemCooldowns` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/ItemCooldowns.java` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 3 | 3 | COMPLETE | P1 | `net/minecraft/client/Minecraft.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/interaction/r1_18_2_block_ack_emulation` — 5/5 hook, COMPLETE 1, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMultiPlayerGameMode.java` | `net.minecraft.client.multiplayer.MultiPlayerGameMode` | 4 | 4 | COMPLETE | P1 | `net/minecraft/client/multiplayer/MultiPlayerGameMode.java` |
| `MixinWorldPacketRewriter1_19.java` | `com.viaversion.viaversion.protocols.v1_18_2to1_19.rewriter.WorldPacketRewriter1_` | 1 | 1 | REPLACED | P1 | — |

### `features/interaction/remove_fuel_slot` — 2/2 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBrewingStandMenu_FuelSlot.java` | `net.minecraft.world.inventory.BrewingStandMenu$FuelSlot` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/inventory/BrewingStandMenu.java` |

### `features/interaction/remove_offhand_slot` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinInventoryMenu.java` | `net.minecraft.world.inventory.InventoryMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/InventoryMenu.java` |

### `features/interaction/replace_block_item_use_logic` — 20/20 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockPlaceContext.java` | `net.minecraft.world.item.context.BlockPlaceContext` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/item/context/BlockPlaceContext.java` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 2 | 2 | COMPLETE | P1 | `net/minecraft/client/Minecraft.java` |
| `MixinMultiPlayerGameMode.java` | `net.minecraft.client.multiplayer.MultiPlayerGameMode` | 16 | 16 | COMPLETE | P0 | `net/minecraft/client/multiplayer/MultiPlayerGameMode.java` |

### `features/item/attack_damage` — 9/9 hook, COMPLETE 2, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinItemAttributeModifiers_Display_Default.java` | `net.minecraft.world.item.component.ItemAttributeModifiers$Display$Default` | 3 | 3 | COMPLETE | P3 | `net/minecraft/world/item/component/ItemAttributeModifiers.java` |
| `MixinItemPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.ItemPacketRewriter1_9 (re` | 5 | 5 | REPLACED | P2 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ItemAttackDamagePatches.java:84-277` |
| `MixinItemStack.java` | `net.minecraft.world.item.ItemStack` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/item/ItemStack.java` |

### `features/item/data_fix` — 5/5 hook, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockItemPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.BlockItemPacketRewr` | 5 | 5 | REPLACED | P2 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/LegacyItemAndRecipePatches.java:86-397 (tables :86-112, loadItemMappings :309, blockJsonArrayToIds :373, fixItem :246, appended handlers :190-196)` |

### `features/item/filter_creative_tabs` — 4/4 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCreativeModeTab_ItemDisplayBuilder.java` | `net.minecraft.world.item.CreativeModeTab$ItemDisplayBuilder` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/item/CreativeModeTab.java` |
| `MixinCreativeModeTabs.java` | `net.minecraft.world.item.CreativeModeTabs` | 3 | 3 | COMPLETE | P3 | `net/minecraft/world/item/CreativeModeTabs.java` |

### `features/item/interaction` — 23/23 hook, COMPLETE 18

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAxeItem.java` | `net.minecraft.world.item.AxeItem` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/item/AxeItem.java` |
| `MixinBlockItem.java` | `net.minecraft.world.item.BlockItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/BlockItem.java` |
| `MixinBoneMealItem.java` | `net.minecraft.world.item.BoneMealItem` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/item/BoneMealItem.java` |
| `MixinBowItem.java` | `net.minecraft.world.item.BowItem` | 3 | 3 | COMPLETE | P2 | `net/minecraft/world/item/BowItem.java` |
| `MixinBrushItem.java` | `net.minecraft.world.item.BrushItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/BrushItem.java` |
| `MixinBucketItem.java` | `net.minecraft.world.item.BucketItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/BucketItem.java` |
| `MixinBundleItem.java` | `net.minecraft.world.item.BundleItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/BundleItem.java` |
| `MixinConsumable.java` | `net.minecraft.world.item.component.Consumable` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/component/Consumable.java` |
| `MixinEnderpearlItem.java` | `net.minecraft.world.item.EnderpearlItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/EnderpearlItem.java` |
| `MixinEquippable.java` | `net.minecraft.world.item.equipment.Equippable` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/item/equipment/Equippable.java` |
| `MixinFireChargeItem.java` | `net.minecraft.world.item.FireChargeItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/FireChargeItem.java` |
| `MixinFireworkRocketItem.java` | `net.minecraft.world.item.FireworkRocketItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/FireworkRocketItem.java` |
| `MixinFishingRodItem.java` | `net.minecraft.world.item.FishingRodItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/FishingRodItem.java` |
| `MixinKnowledgeBookItem.java` | `net.minecraft.world.item.KnowledgeBookItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/KnowledgeBookItem.java` |
| `MixinLeadItem.java` | `net.minecraft.world.item.LeadItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/LeadItem.java` |
| `MixinNameTagItem.java` | `net.minecraft.world.item.NameTagItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/NameTagItem.java` |
| `MixinShovelItem.java` | `net.minecraft.world.item.ShovelItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/ShovelItem.java` |
| `MixinSpawnEggItem.java` | `net.minecraft.world.item.SpawnEggItem` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/item/SpawnEggItem.java` |

### `features/item/negative_item_count` — 2/2 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinGuiGraphicsExtractor.java` | `net.minecraft.client.gui.GuiGraphicsExtractor` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/gui/GuiGraphicsExtractor.java` |

### `features/item/sword_blocking` — 1/1 hook, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockItemPacketRewriter1_21_X.java` | `com.viaversion.viaversion.protocols.v1_21_2to1_21_4.rewriter.BlockItemPacketRewr` | 1 | 1 | REPLACED | P1 | `net/minecraft/world/item/Item.java:202 (use) and :315 (getUseAnimation)` |

### `features/item/tooltip` — 3/3 hook, COMPLETE 1, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinComponentRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.ComponentRewriter1_` | 1 | 1 | REPLACED | P3 | `net/minecraft/world/item/ItemStack.java:1042-1075 (consumed at ItemStack.java:936-939)` |
| `MixinItemStack.java` | `net.minecraft.world.item.ItemStack` | 2 | 2 | COMPLETE | P3 | `net/minecraft/world/item/ItemStack.java` |

### `features/large_container` — 2/2 hook, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinItemPacketRewriter1_14.java` | `com.viaversion.viaversion.protocols.v1_13_2to1_14.rewriter.ItemPacketRewriter1_1` | 2 | 2 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java:221 and E:/.sigma/Sigma-Modern/src/main/java/com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ContainerAndLevelLoadingPatches.java:207` |

### `features/legacy_tab_completion` — 8/8 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractCommandBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen` | 1 | 1 | COMPLETE | P2 | `net/minecraft/client/gui/screens/inventory/AbstractCommandBlockEditScreen.java` |
| `MixinChatScreen.java` | `net.minecraft.client.gui.screens.ChatScreen` | 4 | 4 | COMPLETE | P3 | `net/minecraft/client/gui/screens/ChatScreen.java` |
| `MixinCommandSuggestions.java` | `net.minecraft.client.gui.components.CommandSuggestions` | 3 | 3 | COMPLETE | P3 | `net/minecraft/client/gui/components/CommandSuggestions.java` |

### `features/limitation/allow_negative_amplifier` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMobEffectInstance.java` | `net.minecraft.world.effect.MobEffectInstance` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/effect/MobEffectInstance.java` |

### `features/limitation/book_edit` — 2/2 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBookEditScreen.java` | `net.minecraft.client.gui.screens.inventory.BookEditScreen` | 2 | 2 | COMPLETE | P2 | `net/minecraft/client/gui/screens/inventory/BookEditScreen.java` |

### `features/limitation/max_chat_length` — 4/4 hook, COMPLETE 3, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinChatScreen.java` | `net.minecraft.client.gui.screens.ChatScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/ChatScreen.java` |
| `MixinProtocol1_10To1_11.java` | `com.viaversion.viaversion.protocols.v1_10to1_11.Protocol1_10To1_11$6 (JAR: viave` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinServerboundChatPacket.java` | `net.minecraft.network.protocol.game.ServerboundChatPacket` | 1 | 1 | COMPLETE | P0 | `net/minecraft/network/protocol/game/ServerboundChatPacket.java` |
| `MixinStringUtil.java` | `net.minecraft.util.StringUtil` | 1 | 1 | COMPLETE | P0 | `net/minecraft/util/StringUtil.java` |

### `features/mouse_sensitivity` — 2/2 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMouseHandler.java` | `net.minecraft.client.MouseHandler` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/MouseHandler.java` |
| `MixinMouseSettingsScreen.java` | `net.minecraft.client.gui.screens.options.MouseSettingsScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/options/MouseSettingsScreen.java` |

### `features/movement/collision` — 21/21 hook, COMPLETE 7

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/vehicle/boat/AbstractBoat.java` |
| `MixinBedBlock.java` | `net.minecraft.world.level.block.BedBlock` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/level/block/BedBlock.java` |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 11 | 11 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 4 | 4 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinShapes.java` | `net.minecraft.world.phys.shapes.Shapes` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/phys/shapes/Shapes.java` |
| `MixinSoulSandBlock.java` | `net.minecraft.world.level.block.SoulSandBlock` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/level/block/SoulSandBlock.java` |

### `features/movement/constants` — 14/14 hook, COMPLETE 8

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAvatar.java` | `net.minecraft.world.entity.Avatar` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/Avatar.java` |
| `MixinAvatarRenderer.java` | `net.minecraft.client.renderer.entity.player.AvatarRenderer` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/renderer/entity/player/AvatarRenderer.java` |
| `MixinBlockGetter.java` | `net.minecraft.world.level.BlockGetter` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/level/BlockGetter.java` |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinMth.java` | `net.minecraft.util.Mth` | 2 | 2 | COMPLETE | P1 | `net/minecraft/util/Mth.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/movement/elytra` — 7/7 hook, COMPLETE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinFireworkRocketItem.java` | `net.minecraft.world.item.FireworkRocketItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/FireworkRocketItem.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 4 | 4 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/movement/jump` — 5/5 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 2 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` |

### `features/movement/limitation` — 10/10 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 6 | 6 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 4 | 4 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/movement/limitation/rotation` — 4/4 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/entity/player/Player.java` |

### `features/movement/liquid` — 27/27 hook, COMPLETE 9

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 4 | 4 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |
| `MixinEntityFluidInteraction.java` | `net.minecraft.world.entity.EntityFluidInteraction` | 5 | 5 | COMPLETE | P1 | `net/minecraft/world/entity/EntityFluidInteraction.java` |
| `MixinEntityFluidInteraction_Tracker.java` | `net.minecraft.world.entity.EntityFluidInteraction$Tracker` | 4 | 4 | COMPLETE | P1 | `net/minecraft/world/entity/EntityFluidInteraction.java` |
| `MixinFlowingFluid.java` | `net.minecraft.world.level.material.FlowingFluid` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/material/FlowingFluid.java` |
| `MixinItemEntity.java` | `net.minecraft.world.entity.item.ItemEntity` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/item/ItemEntity.java` |
| `MixinLiquidBlock.java` | `net.minecraft.world.level.block.LiquidBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/LiquidBlock.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 7 | 7 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 3 | 3 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinSkeletonHorse.java` | `net.minecraft.world.entity.animal.equine.SkeletonHorse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/SkeletonHorse.java` |

### `features/movement/packet` — 4/4 hook, COMPLETE 2, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_21_2.java` | `com.viaversion.viaversion.protocols.v1_21to1_21_2.rewriter.EntityPacketRewriter1` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinPositionMoveRotation.java` | `net.minecraft.world.entity.PositionMoveRotation` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/PositionMoveRotation.java` |

### `features/movement/slowdown` — 3/3 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEnderEyeItem.java` | `net.minecraft.world.item.EnderEyeItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/EnderEyeItem.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 2 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` |

### `features/movement/sprinting_and_sneaking` — 24/24 hook, COMPLETE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinKeyboardInput.java` | `net.minecraft.client.player.KeyboardInput` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/KeyboardInput.java` |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 19 | 19 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` |

### `features/movement/vehicle` — 4/4 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractHorse.java` | `net.minecraft.world.entity.animal.equine.AbstractHorse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/AbstractHorse.java` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 3 | 3 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` |

### `features/networking/config_state` — 7/7 hook, COMPLETE 4, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientConfigurationPacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl.java` |
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinProtocol1_20To1_20_2.java` | `com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2 (viaversi` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinProtocolSwapHandler.java` | `net.minecraft.network.ProtocolSwapHandler` | 1 | 1 | COMPLETE | P0 | `net/minecraft/network/ProtocolSwapHandler.java` |
| `MixinUnconfiguredPipelineHandler.java` | `net.minecraft.network.UnconfiguredPipelineHandler` | 1 | 1 | COMPLETE | P0 | `net/minecraft/network/UnconfiguredPipelineHandler.java` |

### `features/networking/keep_player_loaded` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |

### `features/networking/legacy_chat_signature` — 7/7 hook, COMPLETE 3, REPLACED 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAccountProfileKeyPairManager.java` | `net.minecraft.client.multiplayer.AccountProfileKeyPairManager` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/AccountProfileKeyPairManager.java` |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous connect Runnable` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` |
| `MixinKeyPairResponse.java` | `com.mojang.authlib.yggdrasil.response.KeyPairResponse (AuthLib JAR — final recor` | 2 | 2 | REPLACED | P0 | `net/minecraft/client/multiplayer/AccountProfileKeyPairManager.java` |
| `MixinProfilePublicKey_Data.java` | `net.minecraft.world.entity.player.ProfilePublicKey$Data` | 2 | 2 | COMPLETE | P0 | `net/minecraft/world/entity/player/ProfilePublicKey.java` |
| `MixinYggdrasilUserApiService.java` | `com.mojang.authlib.yggdrasil.YggdrasilUserApiService (AuthLib JAR — no source fi` | 1 | 1 | REPLACED | P0 | `net/minecraft/client/multiplayer/AccountProfileKeyPairManager.java` |

### `features/networking/level_loading` — 10/10 hook, COMPLETE 2, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinEntityPacketRewriter1_20_3.java` | `com.viaversion.viaversion.protocols.v1_20_2to1_20_3.rewriter.EntityPacketRewrite` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ContainerAndLevelLoadingPatches.java:301` |
| `MixinLevelLoadingScreen.java` | `net.minecraft.client.gui.screens.LevelLoadingScreen` | 7 | 7 | COMPLETE | P0 | `net/minecraft/client/gui/screens/LevelLoadingScreen.java` |

### `features/networking/limitation` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientHandshakePacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java` |

### `features/networking/limitation/nbt` — 0/2 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinNamedCompoundTagType.java` | `com.viaversion.viaversion.api.type.types.misc.NamedCompoundTagType (viaversion-c` | 1 | 0 | MISSING | P0 | — |
| `MixinTagType.java` | `com.viaversion.viaversion.api.type.types.misc.TagType (viaversion-common JAR — n` | 1 | 0 | MISSING | P0 | — |

### `features/networking/open_inventory_packet` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/Minecraft.java` |

### `features/networking/packet_handling` — 29/29 hook, COMPLETE 3, REPLACED 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientCommonPacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl` | 5 | 5 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java` |
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 21 | 21 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinEntityPacketRewriter1_19_4.java` | `com.viaversion.viaversion.protocols.v1_19_3to1_19_4.rewriter.EntityPacketRewrite` | 1 | 1 | REPLACED | P1 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinGameTestBlockHighlightRenderer.java` | `net.minecraft.client.renderer.debug.GameTestBlockHighlightRenderer` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/renderer/debug/GameTestBlockHighlightRenderer.java` |
| `MixinProtocol1_21_7To1_21_9.java` | `com.viaversion.viaversion.protocols.v1_21_7to1_21_9.Protocol1_21_7To1_21_9 (ViaV` | 1 | 1 | REPLACED | P3 | — |

### `features/networking/player_abilities` — 3/3 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinServerboundPlayerAbilitiesPacket.java` | `net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket` | 3 | 3 | COMPLETE | P0 | `net/minecraft/network/protocol/game/ServerboundPlayerAbilitiesPacket.java` |

### `features/networking/registry_validation` — 3/3 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinHolderSetCodec.java` | `net.minecraft.resources.HolderSetCodec` | 1 | 1 | COMPLETE | P2 | `net/minecraft/resources/HolderSetCodec.java` |
| `MixinHolderSet_Named.java` | `net.minecraft.core.HolderSet$Named` | 2 | 2 | COMPLETE | P2 | `net/minecraft/core/HolderSet.java` |

### `features/networking/remove_legacy_pinger` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinServerStatusPinger.java` | `net.minecraft.client.multiplayer.ServerStatusPinger` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` |

### `features/networking/remove_signed_commands` — 6/6 hook, COMPLETE 3, REPLACED 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinGameModeSwitcherScreen.java` | `net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/gui/screens/debug/GameModeSwitcherScreen.java` |
| `MixinKeyboardHandler.java` | `net.minecraft.client.KeyboardHandler` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/KeyboardHandler.java` |
| `MixinProtocol1_20_3To1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5 (ViaV` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |
| `MixinProtocol1_21_5To1_21_6.java` | `com.viaversion.viaversion.protocols.v1_21_5to1_21_6.Protocol1_21_5To1_21_6 (ViaV` | 1 | 1 | REPLACED | P0 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java` |

### `features/networking/resource_pack_header` — 3/3 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinDownloadedPackSource_4.java` | `net.minecraft.client.resources.server.DownloadedPackSource$4 (the anonymous Pack` | 3 | 3 | COMPLETE | P2 | `net/minecraft/client/resources/server/DownloadedPackSource.java` |

### `features/networking/run_command_action` — 2/2 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinScreen.java` | `net.minecraft.client.gui.screens.Screen` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/gui/screens/Screen.java` |

### `features/networking/server_pinging` — 10/10 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinServerSelectionList_OnlineServerEntry.java` | `net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEnt` | 9 | 9 | COMPLETE | P3 | `net/minecraft/client/gui/screens/multiplayer/ServerSelectionList.java` |
| `MixinServerStatusPinger_1.java` | `net.minecraft.client.multiplayer.ServerStatusPinger$1 (the anonymous ClientStatu` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` |

### `features/networking/srv_resolving` — 5/5 hook, COMPLETE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous "Server Connecto` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` |
| `MixinServerAddress.java` | `net.minecraft.client.multiplayer.resolver.ServerAddress` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/resolver/ServerAddress.java` |
| `MixinServerNameResolver.java` | `net.minecraft.client.multiplayer.resolver.ServerNameResolver` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/resolver/ServerNameResolver.java` |
| `MixinServerRedirectHandler.java` | `net.minecraft.client.multiplayer.resolver.ServerRedirectHandler (the lambda retu` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/resolver/ServerRedirectHandler.java` |

### `features/recipe` — 3/3 hook, COMPLETE 2, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCraftingMenu.java` | `net.minecraft.world.inventory.CraftingMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/CraftingMenu.java` |
| `MixinEntityPacketRewriter1_12.java` | `com.viaversion.viaversion.protocols.v1_11_1to1_12.rewriter.EntityPacketRewriter1` | 1 | 1 | REPLACED | P2 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/LegacyItemAndRecipePatches.java:408` |
| `MixinInventoryMenu.java` | `net.minecraft.world.inventory.InventoryMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/InventoryMenu.java` |

### `features/scoreboard` — 2/2 hook, COMPLETE 1, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinComponentUtil.java` | `com.viaversion.viaversion.util.ComponentUtil (methods legacyToJson and legacyToJ` | 1 | 1 | PARTIAL | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/Protocol1_12_2To1_13Patches.java:242 (handlers) and :304 (skipEmpty=false helper)` |
| `MixinPlayerTeam.java` | `net.minecraft.world.scores.PlayerTeam` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/scores/PlayerTeam.java` |

### `features/screen_changes` — 8/8 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCommandBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/inventory/CommandBlockEditScreen.java` |
| `MixinJigsawBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/gui/screens/inventory/JigsawBlockEditScreen.java` |
| `MixinStructureBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen` | 3 | 3 | COMPLETE | P3 | `net/minecraft/client/gui/screens/inventory/StructureBlockEditScreen.java` |
| `MixinStructureBlockEditScreen_1.java` | `net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen$1 (anonymous` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/inventory/StructureBlockEditScreen.java` |
| `MixinWorldOptionsScreen.java` | `net.minecraft.client.gui.screens.options.WorldOptionsScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/options/WorldOptionsScreen.java` |

### `features/sign_editor_reach` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractSignEditScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen.java` |

### `features/skin_loading` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinSkinManager.java` | `net.minecraft.client.resources.SkinManager` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/resources/SkinManager.java` |

### `features/swinging` — 6/6 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 1 | COMPLETE | P0 | `net/minecraft/world/entity/LivingEntity.java` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 5 | 5 | COMPLETE | P0 | `net/minecraft/client/Minecraft.java` |

### `features/world/always_tick_entities` — 5/5 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 3 | 3 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientLevel.java` |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/Entity.java` |

### `features/world/disable_sequencing` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientLevel.java` |

### `features/world/duplicated_sounds` — 3/3 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockItem.java` | `net.minecraft.world.item.BlockItem` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/item/BlockItem.java` |
| `MixinButtonBlock.java` | `net.minecraft.world.level.block.ButtonBlock` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/level/block/ButtonBlock.java` |
| `MixinItems.java` | `net.minecraft.world.item.FlintAndSteelItem, net.minecraft.world.item.HoeItem` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/item/FlintAndSteelItem.java, src/main/java/net/minecraft/world/item/HoeItem.java` |

### `features/world/entity_distance` — 3/3 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientLevel.java` |
| `MixinLevelExtractor.java` | `net.minecraft.client.renderer.extract.LevelExtractor` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/renderer/extract/LevelExtractor.java` |
| `MixinRemotePlayer.java` | `net.minecraft.client.player.RemotePlayer` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/player/RemotePlayer.java` |

### `features/world/footstep_particle` — 2/6 hook, NOT_APPLICABLE 3, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMappingDataBase.java` | `com.viaversion.viaversion.api.data.MappingDataBase (viaversion-common JAR, 5.12.` | 1 | 0 | NOT_APPLICABLE | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/Protocol1_12_2To1_13Patches.java:168 (the route that makes the hook unnecessary; the hook itself is implemented nowhere)` |
| `MixinParticleIdMappings1_13.java` | `com.viaversion.viaversion.protocols.v1_12_2to1_13.data.ParticleIdMappings1_13 (v` | 2 | 2 | REPLACED | P3 | `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/Protocol1_12_2To1_13Patches.java:151` |
| `MixinParticleMappings.java` | `com.viaversion.viaversion.api.data.ParticleMappings (viaversion-common JAR; mixi` | 2 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinRegistrySyncManager.java` | `net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager (Fabric API impl clas` | 1 | 0 | NOT_APPLICABLE | P3 | — |

### `features/world/item_picking` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/Minecraft.java` |

### `features/world/remove_server_view_distance` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinOptions.java` | `net.minecraft.client.Options` | 1 | 1 | COMPLETE | P2 | `net/minecraft/client/Options.java` |


## GrimAC 诊断链路追踪 / diagnostics trace

三个上报项分开追，不假设它们是同一个 bug。**目标是让翻译后的客户端行为符合对应版本的 vanilla 协议时序，
没有、也不会加入任何针对 GrimAC 的 bypass / spoof / 特判。**

### NegativeTimer (-2231ms / -3133ms)

Timer 余额为负 = 单位墙钟时间内客户端发出的移动报文**少于** 20/s。找到三个叠加成因，都已修复：

1. `features/movement/packet/MixinLocalPlayer#sendIdlePacket` 缺失。`r1_4_2..1.8` 与
   `<= r1_2_4tor1_2_5` 的 vanilla **每 tick**都发 idle 移动报文；26.2 只在 `onGround`/`horizontalCollision`
   变化时才发 `StatusOnly`。上游用 `@Redirect` 把 `lastOnGround` 的读取换成“即将发送值的取反”，使那个
   `else if` 恒真。缺这条时，静止或匀速走动的 tick 大多**一个移动报文都不发**。
2. `MixinEntityPacketRewriter1_21_2#dontCancelIdlePacket` 缺失。即使客户端发了 StatusOnly，ViaVersion 的
   `Protocol1_21To1_21_2` 在 `MOVE_PLAYER_STATUS_ONLY` handler 里，当 on-ground 未变**且** horizontal-collision
   标志变了就 `wrapper.cancel()`。该链路对**任何 ≤1.21 目标**都在路径上。已用
   `appendServerbound(..., w -> w.setCancelled(false))` 重建。
3. `MixinLevelLoadingScreen` 整组缺失（0/7），其中一条是 ≤1.12.1 目标在地形下载期间**每 20 tick 发一个
   `ServerboundKeepAlivePacket(0)`**；缺失会让旧服在 join 阶段判超时。

-2231ms ≈ 44 个丢失的 tick，-3133ms ≈ 62 个，与 (1)+(2) 叠加的量级一致。
**是否完全解释观测到的 VL，未经实机验证。**

### BadPacketsV (delta=0.0，VL 持续增长)

该族检查报文内容自相矛盾。修掉的相关保真度缺陷：

1. `moveLastPosPacketIncrement` 缺失：≤1.8 在**递增之前**判 20-tick 强制位置提醒，26.2 是先增后判，
   于是强制位置报文早一个 tick，在服务端不预期的时刻出现 delta 为 0 的位置报文。
2. 潜行状态从未上报：`sendSneakingAfterSprinting` / `sendSneakingPacket` 缺失，≤1.21 目标收不到潜行
   player-command，服务端按站立速度预测而客户端按 0.3 倍潜行速度移动。
3. `removeSprintingPacket` 缺失：<1.19.3 时 `tick()` 的载具分支**也**发冲刺 player-command，多发一份。
4. `features/swinging/MixinMinecraft#fixSwingPacketOrder` 缺失：≤1.8 期望 swing 在 attack / start-destroy
   **之前**，且方法内后续两处 swing 要被抑制。

**这些是那些检查所盯路径上确实存在的协议保真度缺陷，但“修完 VL 就归零”未经实机验证。**

### TransactionOrder (skipped=1)

完整链路（≤1.16.4 目标）逐段核过：

1. 服务端（1.16.x，Grim 用 transaction）发 `ClientboundContainerAckPacket`（window id, action number,
   `accepted=false`）。
2. ViaVersion `Protocol1_16_4To1_17` 的 `ItemPacketRewriter1_17`（`v1_16_4to1_17/rewriter/
   ItemPacketRewriter1_17.java:82-96`）转成 1.17 `PING`，id = `(1 << 30) | (inventoryId << 16) |
   (confirmationId & 0xFFFF)`，并取消原报文。
3. 26.2 客户端 `ClientCommonPacketListenerImpl#handlePing`：`ensureRunningOnSameThread` 转主线程，
   然后 `send(new ServerboundPongPacket(id))`。
4. Via 的 serverbound `PONG` handler（同文件 :99-114）检查第 30 位，解出 window / confirmation id，
   重建 `ServerboundContainerAckPacket(..., accepted=true)` 发往服务端。

链路上找到并修掉的 Sigma 侧偏差：

- **`addMissingConditions` 缺失。** 1.16 vanilla 只在 window id 为 0 或等于当前打开容器 id 时才回应
  transaction；Sigma 之前对**每一个** ping 都回，即为服务端并不追踪的 window 发出额外 transaction 回应。
  已内联，并且 `minecraft.player == null` 时**取消回应**而不是穿透过滤器（上游那里是裸解引用会 NPE，
  而穿透会发出正是要抑制的那个回应 —— 两者都不是 1.16 vanilla 的行为）。
- **config-state `disableAutoRead` 是死代码。** 它被放在 `PacketUtils.ensureRunningOnSameThread`
  **之后**，而该调用在 netty 线程上 reschedule 并抛异常，所以那一趟根本不执行；等主线程再跑时，netty 线程
  已在 auto-read 打开的状态下继续读了后续报文 —— 这正是上游 `@Inject(HEAD)` 要堵的 play↔configuration
  竞态，也正是“恰好丢一个在途报文”的形状。已移到该调用之前。
- **`queueConfigPackets` 设置此前完全无效**（目标是库类，未重建）。现在已重建：设置关闭时，客户端在被模拟出来
  的 CONFIGURATION 阶段发出的 `PONG` / `KEEP_ALIVE` / `CUSTOM_PAYLOAD` 会被改型成 1.19.4 的 play 等价物
  立即发出，而不是排队到切 play 之后再冲刷。**可验证动作：把 `queueConfigPackets` 关掉复现一次。**
  若 `skipped=1` 消失，就定位到这条排队路径（该行为与上游默认一致，不是 Sigma 独有的缺口）。

### 与 Sigma event 层的相互作用（需要单独确认）

`LocalPlayer#sendPosition` 里 `EventMotion` 被取消会**直接 return，一个移动报文都不发**；
`LocalPlayer#tick` 里 `EventUpdate(PRE)` 被取消会跳过整个 tick。任何模块只要取消其中之一，就能独立复现
NegativeTimer，与 VFP 层无关。本轮所有 VFP 语义都内联在这两个 hook **之内**并使用 event 提供的值
（例如 idle 判定用的是即将发送的 `onGround` 而不是 `this.onGround()`），以保证 Sigma 事件层是附加层、
VFP 版本语义是基础层。复现时建议先全部模块关闭跑一遍作为基线。

## 运行时验证矩阵 / runtime test matrix

**NOT RUNTIME VERIFIED.** 本轮验证到 `mvn -B -DskipTests package dependency:copy-dependencies` BUILD
SUCCESS（JDK 26，`--release 25`，全量重编译 8639 个源文件，`pom.xml` 未改动）。本环境没有实机连服条件，
下表全部未执行。

| 目标版本 | 连接 | 进入世界 | 静止 | 走路 | 奔跑 | 跳跃 | 转头 | 潜行 | transaction/keepalive | 重连 |
|---|---|---|---|---|---|---|---|---|---|---|
| Native 26.2 | — | — | — | — | — | — | — | — | — | — |
| 1.21.x | — | — | — | — | — | — | — | — | — | — |
| 1.20.1 | — | — | — | — | — | — | — | — | — | — |
| 1.19.x | — | — | — | — | — | — | — | — | — | — |
| 1.18.x | — | — | — | — | — | — | — | — | — | — |
| 1.12.2 | — | — | — | — | — | — | — | — | — | — |
| 1.8.9 | — | — | — | — | — | — | — | — | — | — |

`—` = 未测试。别把空格当通过。

本轮改动直接影响、需要重点抓包确认的项：

- 1.8.9 / 1.12.2：静止站立时确认**每 tick 都有**一个移动报文（`sendIdlePacket` + `dontCancelIdlePacket`）。
- 1.8.9：潜行/取消潜行有 `PLAYER_COMMAND` press/release shift；左键攻击时 swing 在 attack 之前。
- 1.12.2：地形下载期间每 20 tick 有一个 keep-alive(0)；容器点击顺序（`execute_inputs_sync`）。
- 1.20.1：configuration↔play 切换时没有报文丢失（autoRead 落点修复）。
- ≤1.20.3：命令走签名分支（`alwaysSignCommands` 取反修复）。
- (1.20.3, 1.21.5]：命令不再弹确认框（`dontOpenConfirmationScreens` 门控修复）。
- 1.14.4–1.18.2：挖掘被服务端拒绝时方块能回滚（block-ack sync task 链路已通）。
- Classic (c0.28–c0.30)：高于 255 的世界高度、光照与多方块变更不再被截断。

## 复现本审计 / reproducing this audit

审计脚本与中间产物在 `target/vfp-audit/`（`target/` 已 gitignore）：`unit-01..25.txt` 是 368 个 mixin 的分片
清单，`impl-01..20.txt` 是按目标文件切出的互不重叠实施桶，`audit2_rows.json` 是 25 个审计 agent + 25 个对抗
复核 agent 的合并输出，`audit2_final.json` 叠加了审计快照之后落地的改动，`g2a.py` / `g2b.py` / `g2c.py` /
`g2d.py` 生成本文件。

诊断编译问题时注意一个坑：javac 对经 `-sourcepath` 隐式编译的文件会**隐藏诊断**，`mvn compile` 只会报
`BUILD FAILURE` 而不打印任何 `.java` 错误行。`target/vfp-audit/Diag.java` 是一个用 Compiler API +
`DiagnosticCollector` 拿到真实诊断的小工具，`bisect.py` 可以在改动集里二分定位。
