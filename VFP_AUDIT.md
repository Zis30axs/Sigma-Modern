# ViaFabricPlus 4.6.3 (ver/26.2) -> Sigma-Modern 移植账本 / port ledger

上游基准 upstream baseline: `ViaVersion/ViaFabricPlus` 分支 `ver/26.2`, commit `c54b78b` ("ViaFabricPlus 4.6.3", 2026-08-23)。本地副本 `E:\.sigma\PerMods\ViaFabricPlus-ver-26.2` 已逐字节比对，与该 commit 内容一致（仅行尾差异）。

**这份文件取代 VFP_PORTING.md 里“已完整移植”的结论。那个结论当时是错的：实测覆盖率 20.9%。**

## 方法 / method

1. 枚举上游 `injection/mixin/` 下全部 **368** 个 mixin。
2. 每个 mixin 逐 hook 展开：`@Inject` / `@Redirect` / `@ModifyExpressionValue` / `@ModifyArg(s)` / `@ModifyVariable` / `@ModifyConstant` / `@ModifyReturnValue` / `@ModifyReceiver` / `@WrapOperation` / `@WrapWithCondition` / `@WrapMethod` / `@Overwrite` / `@Accessor` / `@Invoker`，外加 accessor 接口实现、承载状态的 `@Unique` 字段、构造器 hook、静态初始化 hook。`@Definition` 不单独计数。
3. 每个 hook 都去对应 vanilla 类里**读实际代码**确认行为与版本门控。`// MODIFIED for porting` 注释不算证据。
4. 每条“已移植”结论再交给对抗性复核 agent 重读并尝试推翻。两轮审计分别推翻 6 条和 7 条。

## 总览 / summary

| 指标 | 会话开始 | 现在 |
|---|---|---|
| 上游 mixin | 368 | 368 |
| 上游 hook | 726 | 726 |
| 已内联 hook | 152 | **660** |
| 覆盖率 | 20.9% | **90.9%** |
| COMPLETE | 67 | **315** |
| REPLACED | 0 | **13** |
| PARTIAL | 16 | **3** |
| MISSING | 235 | **0** |
| NOT_APPLICABLE | 50 | 37 |

剩余未内联的 66 个 hook = PARTIAL 行里的 **4** 个 + NOT_APPLICABLE 行里的 62 个（`@Mixin` 目标是 jar 里的类，源码树里没有文件可改，且没有公开 API 路线）。

按优先级 / by priority:

| 优先级 | COMPLETE | REPLACED | PARTIAL | MISSING | NOT_APPLICABLE | 合计 |
|---|---|---|---|---|---|---|
| P0 | 61 | 10 | 1 | 0 | 6 | 78 |
| P1 | 125 | 2 | 0 | 0 | 4 | 131 |
| P2 | 78 | 0 | 0 | 0 | 6 | 84 |
| P3 | 51 | 1 | 2 | 0 | 21 | 75 |

优先级定义：**P0** 协议/连接状态、报文生成与顺序、keep-alive / ping / transaction / teleport 确认、移动报文节奏。**P1** 玩家与实体物理、交互。**P2** 方块/物品/世界行为。**P3** 观感、GUI、屏幕、字体、音效、Bedrock 专属附加项。

## 状态定义 / status

- **COMPLETE** — 每个 hook 都已按行为内联进对应 vanilla 类，版本门控一致。
- **PARTIAL** — 部分 hook 已内联，缺口逐条列在下面。
- **MISSING** — 完全没有移植。**现在为 0。**
- **REPLACED** — 目标是 ViaVersion 的类，改用 ViaVersion 公开协议 API 在 bootstrap 阶段重建等价行为，全部集中在 `ViaFabricPlusProtocolPatches`。
- **NOT_APPLICABLE** — 目标是 jar 依赖里的类且没有公开 API 路线。**这不等于没有行为缺口**，凡是仍有真实损失的都在 §库目标 一节写明。

## 仍未完成 / still open (4 hooks)

| 上游 mixin | hook | 优先级 | 剩余行为 |
|---|---|---|---|
| `features/large_container/MixinItemPacketRewriter1_14.java` | 1/2 | P0 | `@Inject dontResyncInventory`; `@Inject supportLargeContainers` |
| `core/integration/MixinConnection.java` | 1/2 | P3 | `@Override (merged, non-annotated) userEventTriggered` |
| `features/block/shape/MixinHopperBlock.java` | 2/4 | P3 | `@Override getOcclusionShape (MoreCulling workaround, VFP issue #45)`; `@Unique boolean viaFabricPlus$requireOriginalShape` |

**`features/large_container/MixinItemPacketRewriter1_14.java`** — 1/2, 目标 `com.viaversion.viaversion.protocols.v1_13_2to1_14.rewriter.ItemPacketRewriter1_14 (JAR: viaversion-common 5.12.0-20260819.184210-4)`

- `@Inject dontResyncInventory` — Silences ViaVersion's trade resync: re-registers serverbound SELECT_TRADE with a null handler and override=true so Via stops fabricating a serverbound CONTAINER_CLICK (window = latest trade window, slot -999, button 2, mode 5 drag, random action number, NaN force_resync item) on every trade selection. Applies to every target <= 1.13.2, i.e. whenever Protocol1_13_2To1_14 is in the pipeline.
  - 落点: ItemPacketRewriter1_14#registerPackets at RETURN. Landing site in Sigma: a new block in ViaFabricPlusProtocolPatches#apply doing protocol1_13_2To1_14.registerServerbound(ServerboundPackets1_14.SELECT_TRADE, ServerboundPackets1_13.SELECT_TRADE, null, true), plus Protocol1_13_2To1_14.class added to the awaitMappings call at ViaFabricPlusProtocolPatches.java:94-102.
- `@Inject supportLargeContainers` — In the clientbound OPEN_SCREEN handler, when type is minecraft:container or minecraft:chest and slots > 54 or slots <= 0, cancels Via's "Can't open inventory for player!" path and instead clears the packet, retypes it to CUSTOM_PAYLOAD and writes SyncTasks.PACKET_SYNC_IDENTIFIER + task uuid + windowId + slots + TextComponentTranslator.via1_14toViaLatest(title); the registered sync task then builds a ChestMenu(null, syncId, inventory, new SimpleContainer(size), ceil(size/9)) and a ContainerScreen on the client. Without it any legacy chest above 54 slots simply fails to open. Applies to targets <= 1.13.2.
  - 落点: ItemPacketRewriter1_14#registerPackets' OPEN_SCREEN lambda (lambda$registerPackets$0), at the ProtocolLogger#warning invocation reached when typeId == -1 (ItemPacketRewriter1_14.java:104). Landing site in Sigma: ViaFabricPlusProtocolPatches, re-registering ClientboundPackets1_13.OPEN_SCREEN with override=true and reproducing Via's own body (ItemPacketRewriter1_14.java:67-109) with the large-contai
- 备注: Real behaviour gap, recorded as NOT_APPLICABLE only because the @Mixin target is a JAR class. Both hooks COULD be reimplemented through Via public API at bootstrap: Protocol#registerServerbound(..., override=true) for the trade resync, and Protocol#registerClientbound/replaceClientbound on ClientboundPackets1_13.OPEN_SCREEN for the large container. The client half of the large-container feature is already ported and wired (util/network/SyncTasks.java, util/network/DataCustomPayload.java, and ClientCommonPacketListenerImpl.java:204-206 calls SyncTasks.handleSyncTask), so only the Via-side registration is absent - nothing in-tree currently calls SyncTasks.executeSyncTask. The mixin's constructor is a compile shim for `extends ItemRewriter<...>` (Mixin does not merge class-mixin constructors) and is not counted as a hook. \| POST-AUDIT: dontResyncInventory rebuilt through registerServerbound(SELECT_TRADE, null, override). supportLargeContainers is still open: it is an @Inject with @Local captures INSIDE ViaVersion's OPEN_WINDOW lambda, so expressing it through the public API needs that 

**`core/integration/MixinConnection.java`** — 1/2, 目标 `net.minecraft.network.Connection`

- `@Override (merged, non-annotated) userEventTriggered` — Krypton mod-compat shim, all versions: when the incoming netty user event is me.steinborn.krypton.mod.shared.misc.KryptonPipelineEvent with toString()=="COMPRESSION_ENABLED", call ViaChannelInitializer.reorderPipeline(ctx.pipeline(), HandlerNames.COMPRESS, HandlerNames.DECOMPRESS), log the Krypton warning and swallow the event; otherwise super.userEventTriggered.
  - 落点: Connection has no userEventTriggered override at all - the port would be a new public void userEventTriggered(ChannelHandlerContext, Object) throws Exception on net.minecraft.network.Connection, next to the existing channelRegistered override at Connection.java:132-138.
- 备注: The second hook is the merged @Override of SimpleChannelInboundHandler#userEventTriggered (a plain non-annotated method in the mixin, counted the same way the already-ported bedrock MixinConnection#channelRegistered merged override is counted at Connection.java:129-138). grep for userEventTriggered/Krypton across net/minecraft and com/viaversion returns nothing. This gap is unreachable in practice: Krypton is a Fabric mod that cannot be loaded into this tree, and the pipeline reorder it compensates for already runs unconditionally in Sigma - ViaChannelInitializer.reorderPipeline(pipeline, COMPRESS, DECOMPRESS) at Connection.java:793-794 inside setupCompression. Recorded for completeness only; do not spend P0/P1 time on it.

**`features/block/shape/MixinHopperBlock.java`** — 2/4, 目标 `net.minecraft.world.level.block.HopperBlock`

- `@Override getOcclusionShape (MoreCulling workaround, VFP issue #45)` — Sets viaFabricPlus$requireOriginalShape = true then delegates to super, so the nested getShape call returns the vanilla shape once. The consuming branch in changeOutlineShape is guarded by ViaFabricPlusMixinPlugin.MORE_CULLING_PRESENT, which is false in this tree (ViaFabricPlusMixinPlugin.onLoad() is never called from anywhere in Sigma-Modern - verified by grep - so the flag stays at its default false). Without MoreCulling upstream also falls through to the <=1.12.2 branch, so upstream and Sigma both return vfpHopperShapeR1_12_2 from occlusion: no behavioural gap on any target version. Applies to <= 1.12.2 only.
  - 落点: HopperBlock#getOcclusionShape (absent); the flag would be read at the HEAD of HopperBlock#getShape, before the <=v1_12_2 branch at HopperBlock.java:84
- `@Unique boolean viaFabricPlus$requireOriginalShape` — One-shot state carrying 'this getShape call comes from getOcclusionShape'. Only meaningful together with the hook above and only when MoreCulling is present; dead in this tree.
  - 落点: HopperBlock field block (absent)
- 备注: Both real version-gated shape behaviours are inlined and correct; the only gap is upstream's MoreCulling compat shim, which is provably inert here (BlockBehaviour#getOcclusionShape at BlockBehaviour.java:303 delegates to state.getShape(...), so Sigma's occlusion already follows the legacy hopper shape exactly as upstream-without-MoreCulling does). Same deliberate omission is recorded for AbstractCauldronBlock:85, AnvilBlock:86 and BedBlock:196, so it is a tree-wide convention rather than an oversight. Only worth porting if a MoreCulling-style occlusion cache is ever added.

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

剩下 37 个 mixin 的目标是 jar 依赖里的类，且没有公开 API 路线。凡是仍有真实行为缺口的，下表 `备注` 列写明。

| 上游 mixin | 目标类 | 优先级 | hook | 备注 |
|---|---|---|---|---|
| `features/interaction/container_clicking/MixinBlockItemPacketRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewriter1_21_5` | P0 | 1 | Real behaviour gap, not a non-issue: Via's own handler at BlockItemPacketRewriter1_21_5.java:166 (replaceServerbound ServerboundPackets1_21_5.CONTAINER_CLICK) still translates any raw ServerboundContainerClickPacket that reaches the pipeline, bypassing the version-correct writer inlined at MultiPlayerGameMode.java:731. CAN be reimplemented through public API at bootstrap: protocol1_21_4To1_21_5.registerServerbound(Se |
| `features/interaction/container_clicking/MixinEntityTrackerBase.java` | `com.viaversion.viaversion.data.entity.EntityTrackerBase` | P0 | 1 | Real gap. The instaBuild flag is the only thing gating cancellation of serverbound SET_CREATIVE_MODE_SLOT (StructuredItemRewriter.java:496 registerSetCreativeModeSlot1_21_5, BlockItemPacketRewriter1_21_5.java:155, ItemRewriter.java:261), and it is only ever set from clientbound LOGIN/RESPAWN/PLAYER_ABILITIES/GAME_EVENT (EntityRewriter.java:421-452, EntityPacketRewriter1_20_5.java:286-356) - so a creative-inventory ed |
| `features/interaction/container_clicking/MixinItemPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.ItemPacketRewriter1_17` | P0 | 1 | Same real gap as MixinBlockItemPacketRewriter1_21_5 on the 1.17 path: Via's own handler at ItemPacketRewriter1_17.java:47 (replaceServerbound ServerboundPackets1_17.CONTAINER_CLICK) still translates a raw packet, bypassing the hand-built <= 1.16.4 writer at MultiPlayerGameMode.java:879. CAN be reimplemented through public API: protocol1_16_4To1_17.registerServerbound(ServerboundPackets1_17.CONTAINER_CLICK, Serverboun |
| `features/networking/level_loading/MixinEntityPacketRewriter1_20_3.java` | `com.viaversion.viaversion.protocols.v1_20_2to1_20_3.rewriter.EntityPacketRewriter1_20_3 (v` | P0 | 1 | Recorded as a real gap, but I verified the residual effect is currently inert rather than harmful: the synthetic GAME_EVENT 13 reaches ClientPacketListener.java:1799-1801 and only advances LevelLoadTracker from WaitingForServer to WaitingForPlayerChunk (LevelLoadTracker.java:58-62, :151-156); isLevelReady() is consulted in exactly two places, LevelLoadingScreen.tick() :110 (dead for <=1.20.2 because the ported legacy |
| `features/networking/limitation/nbt/MixinNamedCompoundTagType.java` | `com.viaversion.viaversion.api.type.types.misc.NamedCompoundTagType (viaversion-common JAR ` | P0 | 1 | No clean public-API route: unlike TagType there is no limit-free variant of this class, and Types.NAMED_COMPOUND_TAG / OPTIONAL_NAMED_COMPOUND_TAG / NAMED_COMPOUND_TAG_ARRAY are public static final (Types.java:207-209), so the only route is a bootstrap-time forced rewrite of those static finals to a NamedCompoundTagType subclass overriding read(ByteBuf) — Unsafe/VarHandle territory, not a supported API. |
| `features/networking/limitation/nbt/MixinTagType.java` | `com.viaversion.viaversion.api.type.types.misc.TagType (viaversion-common JAR — no source f` | P0 | 1 | Partial public-API route exists and is not used: Via already ships TagType(false) (maxBytes = Integer.MAX_VALUE, TagType.java:62-65) as Types.TRUSTED_TAG (Types.java:220), so pointing Types.TAG / TAG_ARRAY / OPTIONAL_TAG at a TagType(false) would remove the byte cap — but those are public static final (Types.java:214-216) so it still needs a forced static-final rewrite, and the nesting cap would remain, unlike TagLim |
| `core/integration/MixinUserConnectionImpl.java` | `com.viaversion.viaversion.connection.UserConnectionImpl` | P1 | 1 | COULD be reimplemented through public API, and it should be. UserConnectionImpl is public and non-final and its send entry points (sendRawPacket(ByteBuf), scheduleSendRawPacket(ByteBuf), sendRawPacketFuture(ByteBuf)) are public - so subclass it in com.viaversion.viafabricplus.protocoltranslator.util, override those three to release the buffer and return, and instantiate that subclass instead of UserConnectionImpl at  |
| `features/entity/attribute/MixinEntityPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.EntityPacketRewriter1_20_5` | P1 | 1 | Real behaviour gap, not merely inapplicable. It COULD be reimplemented through public API: sendRangeAttributes ends in wrapper.scheduleSend(Protocol1_20_3To1_20_5.class), so the packet bypasses that protocol's own clientbound handlers - an appendClientbound(ClientboundPackets1_20_5.UPDATE_ATTRIBUTES, ...) registered on the NEXT protocol in the chain (Protocol1_20_5To1_21) inside ViaFabricPlusProtocolPatches.apply() c |
| `features/entity/metadata/MixinEntityPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.EntityPacketRewriter1_9 (ViaVersion` | P1 | 1 | Real gap. Via derives the 1.9 PLAYER_HAND (hand-active) entity data from the 1.8 status byte; without the cancel, a 1.8 server's status metadata for the local player overwrites the client's own item-use/blocking state, which is why upstream drops it for the tracked clientEntityId. Reimplementable at bootstrap: append a clientbound SET_ENTITY_DATA handler on Protocol1_8To1_9 (or in ViaFabricPlusProtocol) that removes  |
| `features/item/sword_blocking/MixinBlockItemPacketRewriter1_21_X.java` | `com.viaversion.viaversion.protocols.v1_21_2to1_21_4.rewriter.BlockItemPacketRewriter1_21_4` | P1 | 1 | Real behaviour gap, recorded rather than dismissed. It COULD be reimplemented at bootstrap through Via's public API: Protocol#appendClientbound on the item-carrying clientbound packets of Protocol1_21_2To1_21_4/Protocol1_21_4To1_21_5 (container content/slot, cursor and player inventory, set equipment, entity metadata, show_item) stripping StructuredDataKey.CONSUMABLE1_21_2 / BLOCKS_ATTACKS1_21_5 from those five ids w |
| `features/entity/metadata/MixinCommonBoss.java` | `com.viaversion.viaversion.legacy.bossbar.CommonBoss (ViaVersion JAR)` | P2 | 1 | Real behaviour gap, not merely inapplicable. CommonBoss.java:56 and :78 assert 0<=health<=1; a <=1.8 server sending NaN wither/dragon health survives Via's own clamp (Math.max(0, Math.min(NaN/max, 1)) == NaN) and throws IllegalArgumentException inside EntityTracker1_9#handleEntityData, which runs on the packet path. No Via config toggle disables the assertion; the only bootstrap-level equivalent is to take over the 1 |
| `features/entity/metadata/MixinEntityPacketRewriter1_15.java` | `com.viaversion.viaversion.protocols.v1_14_4to1_15.rewriter.EntityPacketRewriter1_15 (ViaVe` | P2 | 1 | Real gap. The tracker is registered but never filled, so getWolfHealth always returns the `entity.getHealth()` fallback and the fully ported MixinWolf redirect is inert on <=1.14.4 servers (wolf tail angle stays at full health, whine sound never selected, client-side feed check mispredicts). Reimplementable at bootstrap only by replacing Protocol1_14_4To1_15's clientbound SET_ENTITY_DATA handler via registerClientbou |
| `features/entity/metadata/MixinEntityTracker1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9 (ViaVersion JAR)` | P2 | 3 | Real gap covering all three redirects; the branch they patch only runs while Via's bossbar-anti-flicker is off (its default). Together they turn `Math.max(0, Math.min(value/maxHealth, 1))` into a raw ratio and map NaN to 0. Reimplementable at bootstrap only by owning the path: enable bossbar-anti-flicker so Via skips its clamped update, then track ENDER_DRAGON/WITHER entity-data id 6 from an appended clientbound SET_ |
| `features/item/attack_damage/MixinItemPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.ItemPacketRewriter1_9 (remap=false)` | P2 | 5 | Real behaviour gap, not merely inapplicable: on <=1.8 targets no vanilla-1.8 AttributeModifiers NBT is synthesised for weapons/tools/armour, so damage and armour values (and the tooltips built from them) are wrong even though the tooltip-side hooks of this same feature are ported. It COULD be reimplemented through the public API without touching the jar: after Protocol1_8To1_9's mapping future completes, appendClient |
| `features/item/data_fix/MixinBlockItemPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.BlockItemPacketRewriter1_20_5` | P2 | 5 | Real behaviour gap: this mixin is how VFP emulates legacy item behaviour purely through components, so without it every pre-1.20.5 target loses the per-version TOOL component (mining speeds, suitable_for block sets, damage_per_block), b1.8.1 armour MAX_DAMAGE (durability tooltip) and the b1.7.3 food fix (MAX_STACK_SIZE 1 + empty FOOD), i.e. wrong block-breaking speed and tool damage on all older targets. It COULD be  |
| `features/recipe/MixinEntityPacketRewriter1_12.java` | `com.viaversion.viaversion.protocols.v1_11_1to1_12.rewriter.EntityPacketRewriter1_12$1 (the` | P2 | 1 | Real behaviour gap, not a non-issue. Via's LOGIN handler does `if (protocolInfo.protocolVersion().newerThanOrEqualTo(v1_13)) wrapper.create(ClientboundPackets1_13.UPDATE_RECIPES, w -> w.write(VAR_INT, 0)).scheduleSend(Protocol1_12_2To1_13.class)`; a 26.2 client always satisfies that check, so on every join to a <=1.11.1 server an empty recipe list reaches the untouched ClientPacketListener#handleUpdateRecipes (src/ma |
| `compat/classic4j/MixinCCAuthenticationResponse.java` | `de.florianreuth.classic4j.model.classicube.CCAuthenticationResponse (classic4j-2.3.0.jar)` | P3 | 1 | REAL but cosmetic gap. classic4j wraps getErrorDisplay() in a javax.security.auth.login.LoginException and hands it to LoginProcessHandler#handleException (verified by javap of ClassiCubeHandler), and Sigma's com/viaversion/viafabricplus/screen/impl/classic4j/ClassiCubeLoginScreen.java:95 does setupSubtitle(Component.nullToEmpty(throwable.getMessage())), so the user currently sees classic4j's hardcoded English ("Inva |
| `compat/fabricapi/MixinClientRegistrySyncHandler.java` | `net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` | P3 | 1 | No behaviour gap: without Fabric API's registry-sync there is no S2C registry-sync payload and no "Received unknown remote registry entry" logger.error to cancel, so the hook has nothing to guard. Side effect worth knowing: DebugSettings.ignoreFabricSyncErrors (com/viaversion/viafabricplus/settings/impl/DebugSettings.java:39) is now a dead toggle - it is read nowhere in the tree. If Sigma ever adds its own registry-s |
| `compat/ipnext/MixinAutoRefillHandler_ItemSlotMonitor.java` | `org.anti_ad.mc.ipnext.event.AutoRefillHandler$ItemSlotMonitor (@Pseudo, Inventory Profiles` | P3 | 3 | No gap. Three injection points counted: checkHandle@HEAD and checkShouldHandle@HEAD (one @Inject with method={...}) plus updateCurrent at the currentSlotId FIELD write (shift=AFTER), all cancelling when currentSlotId == 45 and target <= 1.8. @Shadow currentSlotId is not counted as a hook. Sigma has no auto-refill of its own (no AutoRefill/slot-45 handling under com/mentalfrostbyte), so nothing re-touches the offhand  |
| `compat/mcstructs/MixinTextComponentSerializer.java` | `com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer (shaded i` | P3 | 1 | No behaviour gap - the fix has been upstreamed into the shaded MCStructs that this build already depends on, so the @Overwrite would be a no-op here. Nothing to port. @Shadow legacyGson and @Shadow getGson() are not counted as hooks. If viaversion-common is ever downgraded, re-check this one: the missing piece would be the LegacyGson.checkStartingType/fixInvalidEscapes pre-pass for legacy (<= 1.8) chat JSON, and it c |
| `compat/minecraftauth/MixinClasses.java` | `io.jsonwebtoken.lang.Classes (jjwt-api-0.13.0.jar, via MinecraftAuth)` | P3 | 1 | No behaviour gap. The @Overwrite exists only because Fabric's Knot classloader is not the TCCL, so jjwt's classloader chain fails under the Fabric loader. Sigma launches from Start.java with a plain `java -cp` app classloader, where TCCL == own CL == system CL, so the stock Classes.forName resolves and the workaround is unnecessary. Nothing to port; if a custom classloader is ever introduced by the launcher this beco |
| `compat/minecraftauth/MixinDefaultJwtParserBuilder.java` | `io.jsonwebtoken.impl.DefaultJwtParserBuilder (jjwt-impl-0.13.0.jar, via MinecraftAuth)` | P3 | 1 | No behaviour gap - same root cause as MixinClasses: the @Redirect replaces jjwt's java.util.ServiceLoader lookup with `new GsonDeserializer<>()` purely to survive Fabric's Knot classloader. Under Sigma's normal classpath launch the service file is found, so MinecraftAuth's JWT parsing works unpatched. Nothing to port. |
| `core/access/MixinChunkTracker.java` | `net.raphimc.viabedrock.protocol.storage.ChunkTracker (ViaBedrock-0.0.29-SNAPSHOT.jar)` | P3 | 3 | REAL gap and a live crash, not just a missing readout: VFPDebugHudEntry was copied verbatim and still does `(IChunkTracker) chunkTracker` at line 79, but ChunkTracker cannot implement that interface without a mixin, so this throws ClassCastException. The entry is registered (net/minecraft/client/gui/components/debug/DebugScreenEntries.java:129), so the F3 overlay will blow up on any Bedrock connection once a ChunkTra |
| `core/access/MixinExtensionProtocolMetadataStorage.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.storage.ExtensionProtocolMetadat` | P3 | 1 | REAL gap and a live ClassCastException: ListExtensionsCommand.java:49 still casts the storage to IExtensionProtocolMetadataStorage, and the command is registered (ViaFabricPlusCommandHandler.java:43), so `listextensions` on a ClassiCube/CPE server throws instead of listing. COULD be reimplemented through the public API: iterate ClassicProtocolExtension.values() and probe the public hasServerExtension(extension, versi |
| `core/access/MixinRakSessionCodec.java` | `org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec (netty-transport-raknet` | P3 | 2 | REAL gap and a live crash: VFPDebugHudEntry.java:88 does `(IRakSessionCodec) rakSessionCodec` unchanged, which cannot succeed without a mixin, so the registered F3 entry (DebugScreenEntries.java:129) throws ClassCastException as soon as the channel is a RakClientChannel. Note the pipeline lookup and the getRTT()/getPing() parts of that line are fine - only TQ/RTQ need the accessors. No public API route (both fields p |
| `core/integration/MixinViaBedrockConfig.java` | `net.raphimc.viabedrock.ViaBedrockConfig` | P3 | 1 | COULD be reimplemented through public API: ViaBedrock.init(ViaBedrockPlatform, ViaBedrockConfig) / ViaBedrockPlatform#init(ViaBedrockConfig) both accept a caller-supplied config, so subclass net.raphimc.viabedrock.ViaBedrockConfig, override shouldEnableExperimentalFeatures() to return BedrockSettings.INSTANCE.experimentalFeatures.getValue(), and pass it in from a ViaBedrockPlatformImpl subclass at ProtocolTranslator. |
| `core/integration/MixinViaLegacyConfig.java` | `net.raphimc.vialegacy.ViaLegacyConfig` | P3 | 1 | hookTotal counted as 1 (a single @Inject annotation) but it expands to two override sites, isLegacySkullLoading and isLegacySkinLoading, both forced to the same setting. COULD be reimplemented through public API: subclass net.raphimc.vialegacy.ViaLegacyConfig overriding both getters to return GeneralSettings.INSTANCE.loadSkinsAndSkullsInLegacyVersions.getValue(), and override init(File) in the existing ViaFabricPlusV |
| `features/bedrock/allow_new_line/MixinFont.java` | `net.minecraft.client.gui.Font` | P3 | 0 | Feature is disabled upstream on ver/26.2 (VFP itself has not re-mapped it to the new Font.PreparedText API). If it is ever wanted, the landing sites are Font#prepareText(String,float,float,int,boolean,int) and Font#prepareText(FormattedCharSequence,...) at HEAD, plus Font#width(FormattedText); the commented code is also self-inconsistent (references ci/str/drawInBatch), so it would have to be rewritten, not transcrib |
| `features/classic/cpe_extension/MixinClassicProtocolExtension.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolExtension (V` | P3 | 3 | COULD be reimplemented at bootstrap with public API only: getSupportedVersions() hands back the live mutable IntSet, so ClassicProtocolExtension.ENV_WEATHER_TYPE.getSupportedVersions().add(1) makes supportsVersion(1)/isSupported()/getHighestSupportedVersion() behave as the mixin forces (the mixin additionally returns true for ANY version argument, the set only for 1). Currently the gap is inert rather than broken: Si |
| `features/classic/cpe_extension/MixinClientboundPacketsc0_30cpe.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.packet.ClientboundPacketsc0_30cp` | P3 | 1 | NO public API can do this - REGISTRY has no setter and no public accessor. Loaderless route is reflection: after CPEAdditions.createNewPacket, write the new constant into REGISTRY[packetId] with net.lenni0451.reflect (already a dependency and already used in that same method for Enums.newInstance/addEnumInstance). Currently moot because CPEAdditions.CUSTOM_PACKETS is never populated (createNewPacket call commented ou |
| `features/classic/cpe_extension/MixinProtocolc0_30cpeToc0_28_30.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.Protocolc0_30cpeToc0_28_30 (ViaL` | P3 | 2 | The packet registration CAN be done through public API from ViaFabricPlusProtocolPatches.apply(): Via.getManager().getProtocolManager().getProtocol(Protocolc0_30cpeToc0_28_30.class).registerClientbound(CPEAdditions.EXT_WEATHER_TYPE, null, handlers) - Protocol#registerClientbound(CU, CM, PacketHandler) is public and verified present in the 5.12.0 jar. The per-connection reset has no API hook (Protocol#init) but is equ |
| `features/item/tooltip/MixinComponentRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.ComponentRewriter1_21_5` | P3 | 1 | Real behaviour gap: the write side is missing, so for items inside show_item hover components the ported read side (src/main/java/net/minecraft/world/item/ItemStack.java:1019 vfpShowAdditionalTooltip, reading ItemUtil.vvNbtName(Protocol1_21_4To1_21_5.class, "backup"), used at :937) can never fire and chat-hover items on <=1.21.4 targets show additional tooltip lines the server hid. Real inventory items are unaffected |
| `features/scoreboard/MixinComponentUtil.java` | `com.viaversion.viaversion.util.ComponentUtil (methods legacyToJson and legacyToJsonString(` | P3 | 1 | Real behaviour gap. The single @Redirect covers two target methods; both 3-arg and 4-arg StringFormat#fromString overloads are confirmed present in the shaded mcstructs, and the 3-arg one skips empty (formatting-only) sections, so trailing style-only runs in legacy §-strings are dropped for every pre-1.13 target - the scoreboard team prefix/suffix case this feature exists for, plus every other legacy->JSON conversion |
| `features/world/footstep_particle/MixinMappingDataBase.java` | `com.viaversion.viaversion.api.data.MappingDataBase (viaversion-common JAR, 5.12.0-20260819` | P3 | 1 | Real behaviour gap, recorded below. In the JAR getNewParticleId is `return checkValidity(id, particleMappings.getNewId(id), "particles")`, so without the HEAD pass-through the synthetic viafabricplus:footstep raw id is rejected (and logged) at every step of the 1.12.2->26.2 protocol chain. COULD be reimplemented, but only at packet level: the ParticleMappings instance cannot be substituted (MappingDataBase.particleMa |
| `features/world/footstep_particle/MixinParticleIdMappings1_13.java` | `com.viaversion.viaversion.protocols.v1_12_2to1_13.data.ParticleIdMappings1_13 (viaversion-` | P3 | 2 | hookTotal=2: the static-init @Inject("<clinit>", RETURN) overlap assertion and the @ModifyArg on add(I)V. The @Shadow @Final `particles` field is a shadow, not a hook. Real behaviour gap: FootStepParticle1_12_2 is still registered in Sigma (FeaturesLoading.java:48 -> FootStepParticle1_12_2.init(), with the registry temporarily unfrozen at FootStepParticle1_12_2.java:59-62) but vanilla Via maps 1.12.2 particle 28 to - |
| `features/world/footstep_particle/MixinParticleMappings.java` | `com.viaversion.viaversion.api.data.ParticleMappings (viaversion-common JAR; mixin declares` | P3 | 2 | hookTotal=2: two overwrite-style method overrides merged into the target (getNewId(int) and mappedIdentifier(int)); the mixin's constructor is scaffolding for the `extends FullMappingsBase` declaration, not a target constructor hook. Same real gap as rows 3 and 4 - the footstep feature is inert in Sigma. NOT reimplementable as-is: the ParticleMappings instance lives in MappingDataBase.particleMappings (protected, con |
| `features/world/footstep_particle/MixinRegistrySyncManager.java` | `net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager (Fabric API impl class)` | P3 | 1 | The only genuinely empty gap in this unit: the @WrapOperation exists solely to hide the runtime-registered viafabricplus:footstep entry from Fabric's registry-sync map (Registry#getKey returning null for it), and Sigma has no Fabric loader and no registry sync, so there is nothing to skip and nothing to reimplement through any API. The analogous Sigma-side concern - inserting a custom entry into an already frozen van |

## 全量账本 / full ledger (368)

`Sigma 位置` 为空表示行为不落在源码树里（REPLACED 落在 `ViaFabricPlusProtocolPatches`，NOT_APPLICABLE 无落点）。

### `compat/classic4j` — 4/5 hook, COMPLETE 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCCAuthenticationResponse.java` | `de.florianreuth.classic4j.model.classicube.CCAuthenticationResponse (classic4j-2` | 1 | 0 | NOT_APPLICABLE | P3 | — |
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
| `MixinTextComponentSerializer.java` | `com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer` | 1 | 0 | NOT_APPLICABLE | P3 | — |

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

### `core/access` — 3/9 hook, COMPLETE 1, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinChunkTracker.java` | `net.raphimc.viabedrock.protocol.storage.ChunkTracker (ViaBedrock-0.0.29-SNAPSHOT` | 3 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinExtensionProtocolMetadataStorage.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.storage.ExtensionProto` | 1 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinLocalSampleLogger.java` | `net.minecraft.util.debugchart.LocalSampleLogger` | 3 | 3 | COMPLETE | P0 | `net/minecraft/util/debugchart/LocalSampleLogger.java` |
| `MixinRakSessionCodec.java` | `org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec (netty-transp` | 2 | 0 | NOT_APPLICABLE | P3 | — |

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

### `core/integration` — 15/19 hook, COMPLETE 6, NOT_APPLICABLE 3, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread created i` | 4 | 4 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` |
| `MixinConnection.java` | `net.minecraft.network.Connection` | 2 | 1 | PARTIAL | P3 | `net/minecraft/network/Connection.java` |
| `MixinDebugScreenEntries.java` | `net.minecraft.client.gui.components.debug.DebugScreenEntries` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/gui/components/debug/DebugScreenEntries.java` |
| `MixinJoinMultiplayerScreen.java` | `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.java` |
| `MixinServerData.java` | `net.minecraft.client.multiplayer.ServerData` | 4 | 4 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ServerData.java` |
| `MixinServerStatusPinger_1.java` | `net.minecraft.client.multiplayer.ServerStatusPinger$1 (the anonymous ClientStatu` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` |
| `MixinUserConnectionImpl.java` | `com.viaversion.viaversion.connection.UserConnectionImpl` | 1 | 0 | NOT_APPLICABLE | P1 | — |
| `MixinViaBedrockConfig.java` | `net.raphimc.viabedrock.ViaBedrockConfig` | 1 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinViaLegacyConfig.java` | `net.raphimc.vialegacy.ViaLegacyConfig` | 1 | 0 | NOT_APPLICABLE | P3 | — |

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

### `features/block/shape` — 61/69 hook, COMPLETE 29, PARTIAL 1

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
| `MixinHopperBlock.java` | `net.minecraft.world.level.block.HopperBlock` | 4 | 2 | PARTIAL | P3 | `net/minecraft/world/level/block/HopperBlock.java` |
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

### `features/classic/cpe_extension` — 1/7 hook, COMPLETE 1, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClassicProtocolExtension.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolEx` | 3 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientLevel.java` |
| `MixinClientboundPacketsc0_30cpe.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.packet.ClientboundPack` | 1 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinProtocolc0_30cpeToc0_28_30.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.Protocolc0_30cpeToc0_2` | 2 | 0 | NOT_APPLICABLE | P3 | — |

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

### `features/entity/attribute` — 1/2 hook, COMPLETE 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.EntityPacketRewrite` | 1 | 0 | NOT_APPLICABLE | P1 | — |
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

### `features/entity/metadata` — 1/7 hook, COMPLETE 1, NOT_APPLICABLE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCommonBoss.java` | `com.viaversion.viaversion.legacy.bossbar.CommonBoss (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P2 | — |
| `MixinEntityPacketRewriter1_15.java` | `com.viaversion.viaversion.protocols.v1_14_4to1_15.rewriter.EntityPacketRewriter1` | 1 | 0 | NOT_APPLICABLE | P2 | — |
| `MixinEntityPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.EntityPacketRewriter1_9 (` | 1 | 0 | NOT_APPLICABLE | P1 | — |
| `MixinEntityTracker1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9 (ViaVersi` | 3 | 0 | NOT_APPLICABLE | P2 | — |
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

### `features/interaction/container_clicking` — 18/21 hook, COMPLETE 6, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinAbstractContainerMenu.java` | `net.minecraft.world.inventory.AbstractContainerMenu` | 4 | 4 | COMPLETE | P0 | `net/minecraft/world/inventory/AbstractContainerMenu.java` |
| `MixinAbstractContainerScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen` | 4 | 4 | COMPLETE | P1 | `net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java` |
| `MixinAbstractFurnaceMenu.java` | `net.minecraft.world.inventory.AbstractFurnaceMenu` | 2 | 2 | COMPLETE | P2 | `net/minecraft/world/inventory/AbstractFurnaceMenu.java` |
| `MixinBlockItemPacketRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewr` | 1 | 0 | NOT_APPLICABLE | P0 | — |
| `MixinCraftingMenu.java` | `net.minecraft.world.inventory.CraftingMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/CraftingMenu.java` |
| `MixinEntityTrackerBase.java` | `com.viaversion.viaversion.data.entity.EntityTrackerBase` | 1 | 0 | NOT_APPLICABLE | P0 | — |
| `MixinItemPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.ItemPacketRewriter1_1` | 1 | 0 | NOT_APPLICABLE | P0 | — |
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

### `features/item/attack_damage` — 4/9 hook, COMPLETE 2, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinItemAttributeModifiers_Display_Default.java` | `net.minecraft.world.item.component.ItemAttributeModifiers$Display$Default` | 3 | 3 | COMPLETE | P3 | `net/minecraft/world/item/component/ItemAttributeModifiers.java` |
| `MixinItemPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.ItemPacketRewriter1_9 (re` | 5 | 0 | NOT_APPLICABLE | P2 | — |
| `MixinItemStack.java` | `net.minecraft.world.item.ItemStack` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/item/ItemStack.java` |

### `features/item/data_fix` — 0/5 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockItemPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.BlockItemPacketRewr` | 5 | 0 | NOT_APPLICABLE | P2 | — |

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

### `features/item/sword_blocking` — 0/1 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinBlockItemPacketRewriter1_21_X.java` | `com.viaversion.viaversion.protocols.v1_21_2to1_21_4.rewriter.BlockItemPacketRewr` | 1 | 0 | NOT_APPLICABLE | P1 | — |

### `features/item/tooltip` — 2/3 hook, COMPLETE 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinComponentRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.ComponentRewriter1_` | 1 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinItemStack.java` | `net.minecraft.world.item.ItemStack` | 2 | 2 | COMPLETE | P3 | `net/minecraft/world/item/ItemStack.java` |

### `features/large_container` — 1/2 hook, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinItemPacketRewriter1_14.java` | `com.viaversion.viaversion.protocols.v1_13_2to1_14.rewriter.ItemPacketRewriter1_1` | 2 | 1 | PARTIAL | P0 | — |

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

### `features/networking/level_loading` — 9/10 hook, COMPLETE 2, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientPacketListener.java` |
| `MixinEntityPacketRewriter1_20_3.java` | `com.viaversion.viaversion.protocols.v1_20_2to1_20_3.rewriter.EntityPacketRewrite` | 1 | 0 | NOT_APPLICABLE | P0 | — |
| `MixinLevelLoadingScreen.java` | `net.minecraft.client.gui.screens.LevelLoadingScreen` | 7 | 7 | COMPLETE | P0 | `net/minecraft/client/gui/screens/LevelLoadingScreen.java` |

### `features/networking/limitation` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinClientHandshakePacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java` |

### `features/networking/limitation/nbt` — 0/2 hook, NOT_APPLICABLE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinNamedCompoundTagType.java` | `com.viaversion.viaversion.api.type.types.misc.NamedCompoundTagType (viaversion-c` | 1 | 0 | NOT_APPLICABLE | P0 | — |
| `MixinTagType.java` | `com.viaversion.viaversion.api.type.types.misc.TagType (viaversion-common JAR — n` | 1 | 0 | NOT_APPLICABLE | P0 | — |

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

### `features/recipe` — 2/3 hook, COMPLETE 2, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinCraftingMenu.java` | `net.minecraft.world.inventory.CraftingMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/CraftingMenu.java` |
| `MixinEntityPacketRewriter1_12.java` | `com.viaversion.viaversion.protocols.v1_11_1to1_12.rewriter.EntityPacketRewriter1` | 1 | 0 | NOT_APPLICABLE | P2 | — |
| `MixinInventoryMenu.java` | `net.minecraft.world.inventory.InventoryMenu` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/inventory/InventoryMenu.java` |

### `features/scoreboard` — 1/2 hook, COMPLETE 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinComponentUtil.java` | `com.viaversion.viaversion.util.ComponentUtil (methods legacyToJson and legacyToJ` | 1 | 0 | NOT_APPLICABLE | P3 | — |
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

### `features/world/footstep_particle` — 0/6 hook, NOT_APPLICABLE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 |
|---|---|---|---|---|---|---|
| `MixinMappingDataBase.java` | `com.viaversion.viaversion.api.data.MappingDataBase (viaversion-common JAR, 5.12.` | 1 | 0 | NOT_APPLICABLE | P3 | — |
| `MixinParticleIdMappings1_13.java` | `com.viaversion.viaversion.protocols.v1_12_2to1_13.data.ParticleIdMappings1_13 (v` | 2 | 0 | NOT_APPLICABLE | P3 | — |
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
