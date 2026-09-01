# ViaFabricPlus 4.6.3 (ver/26.2) → Sigma-Modern 移植账本 / port ledger

上游基准 upstream baseline: `ViaVersion/ViaFabricPlus` 分支 `ver/26.2`, commit `c54b78b` ("ViaFabricPlus 4.6.3", 2026-08-23). 本地副本 `E:\.sigma\PerMods\ViaFabricPlus-ver-26.2` 已逐字节比对，与该 commit 内容一致（仅行尾差异）。

**这份文件取代 VFP_PORTING.md 里“已完整移植”的结论。那个结论是错的。**

## 方法 / method

1. 枚举上游 `src/main/java/com/viaversion/viafabricplus/injection/mixin/` 下全部 **368** 个 mixin。
2. 每个 mixin 逐 hook 展开：`@Inject` / `@Redirect` / `@ModifyExpressionValue` / `@ModifyArg(s)` / `@ModifyVariable` / `@ModifyConstant` / `@ModifyReturnValue` / `@ModifyReceiver` / `@WrapOperation` / `@WrapWithCondition` / `@WrapMethod` / `@Overwrite` / `@Accessor` / `@Invoker`，外加 accessor 接口实现、承载状态的 `@Unique` 字段、构造器 hook、静态初始化 hook。`@Definition` 不单独计数（它只是相邻 `@ModifyExpressionValue`/`@Expression` 的目标声明）。
3. 每个 hook 都去对应的 vanilla 类里**读实际代码**确认行为与版本门控是否一致。`// MODIFIED for porting` 注释本身不算证据。
4. 每个宣称“已移植”的结论再交给一个对抗性复核 agent 重新读一遍并尝试推翻。该复核推翻并修正了 6 条结论。

因此下表的 `已移植 hook 数` 是逐 hook 判定的结果，不是“目标文件被改过”。

## 总览 / summary

| 指标 | 数值 |
|---|---|
| 上游 mixin 总数 | **368** |
| 上游 hook 总数 | **726** |
| 已内联 hook 数 | **197** |
| hook 覆盖率 | **27.1%** |
| COMPLETE | 88 |
| PARTIAL | 12 |
| MISSING | 218 |
| REPLACED | 2 |
| NOT_APPLICABLE | 48 |

按优先级 / by priority:

| 优先级 | COMPLETE | REPLACED | PARTIAL | MISSING | NOT_APPLICABLE | 合计 |
|---|---|---|---|---|---|---|
| P0 | 35 | 2 | 5 | 15 | 16 | 73 |
| P1 | 34 | 0 | 7 | 82 | 3 | 126 |
| P2 | 6 | 0 | 0 | 78 | 7 | 91 |
| P3 | 13 | 0 | 0 | 43 | 22 | 78 |

优先级定义：**P0** 协议/连接状态、报文生成与顺序、keep-alive / ping / transaction / teleport 确认、移动报文节奏。**P1** 玩家与实体物理、交互。**P2** 方块/物品/世界行为。**P3** 观感、GUI、屏幕、字体、音效、Bedrock 专属附加项。

## 状态定义 / status

- **COMPLETE** — 该 mixin 的每个 hook 都已按行为内联进对应 vanilla 类，版本门控一致。
- **PARTIAL** — 部分 hook 已内联。`已移植/总数` 给出确切比例，缺口逐条列在下面。
- **MISSING** — 完全没有移植。
- **REPLACED** — 用结构不同但等价的实现覆盖，给出代码位置。
- **NOT_APPLICABLE** — `@Mixin` 目标是 jar 依赖里的类（ViaVersion / ViaBackwards / ViaLegacy / ViaBedrock / MinecraftAuth / classic4j / lithium / `net.fabricmc.*` shim），源码树里没有可改的文件。**这不等于没有行为缺口** —— 凡是仍有真实行为损失的，都在下面 §库目标 一节里写清楚，并注明能否通过公开 API 在 bootstrap 阶段重建。

## 本轮修复 / fixed in this round

全部基于 `main` HEAD，未新建分支。每批都通过 `mvn -DskipTests compile`（JDK 26，`--release 25`）。

| commit | 内容 |
|---|---|
| `187094a8` | movement/packet MixinLocalPlayer 2/2（`moveLastPosPacketIncrement`、`sendIdlePacket`）；packet_handling MixinClientCommonPacketListenerImpl 5/5（含 `forceSendKeepAlive`、`addMissingConditions`）；packet_handling MixinClientPacketListener 补 `dontChangeYawWhenMountingBoats`；movement/sprinting_and_sneaking 4 个 mixin 共 22 hook；movement/vehicle MixinLocalPlayer 3/3 |
| `cb9c21ab` | execute_inputs_sync 3/3；新增 `ViaFabricPlusProtocolPatches`，用 ViaVersion 公开协议 API 重建 `MixinEntityPacketRewriter1_21_2#dontCancelIdlePacket` 与 `MixinEntityPacketRewriter1_19_4#fixTeleportBehaviour` |
| `96251a5d` | networking/player_abilities 2/2；networking/limitation 压缩门控 1/1；networking/registry_validation 3/3；networking/srv_resolving MixinServerRedirectHandler 1/1；networking/open_inventory_packet 1/1 |
| `a5cba06c` | legacy_tab_completion 8/8 |
| `2be0a7df` | **修掉三处已移植代码里的缺陷**（见下）；swinging 5/5；level_loading MixinLevelLoadingScreen 7/7；world/disable_sequencing 1/1；world/item_picking 1/1；networking/srv_resolving MixinServerNameResolver 1/1 |

### 已移植代码里发现的三处缺陷 / defects in code previously marked done

1. **`config_state` 的 `disableAutoRead` 在 netty 线程上是死代码。** `ClientPacketListener#handleConfigurationStart` 与 `ClientConfigurationPacketListenerImpl#handleConfigurationFinished` 都把 `setAutoRead(false)` 放在 `PacketUtils.ensureRunningOnSameThread` **之后**。该调用在 netty 线程上会 reschedule 并抛 `RunningOnDifferentThreadException`，所以其后的语句在 netty 线程这一趟根本不执行；等到主线程再跑时，netty 线程已经在 auto-read 打开的状态下继续读了后续报文 —— 而这正是上游 `@Inject(HEAD)` 要堵的那个 play↔configuration 竞态。已移到该调用之前。
2. **`remove_signed_commands` 的 `alwaysSignCommands` 逻辑取反。** 上游把 `sendCommand` 里的 `isEmpty()` 改写成 `newerThan(1.20.3) && isEmpty()`，效果是 **≤1.20.3 一律走签名分支**；原内联写的是 `!(≤1.20.3 && isEmpty())`，按德摩根等于 `newerThan(1.20.3) || !isEmpty()`，选中的是相反的分支，导致 ≤1.20.3 **带参数**的命令以未签名形式发出。
3. **`run_command_action` 的 `dontOpenConfirmationScreens` 用错了门控。** 它被和 ≤1.20.3 的签名开关合并成同一个布尔量，而它自己的门控是 ≤1.21.5。结果 (1.20.3, 1.21.5] 区间的目标版本仍会弹现代确认框且命令根本发不出去。

## 仍未完成的 P0 / P0 still open

| 上游 mixin | hook | 状态 | 剩余行为 |
|---|---|---|---|
| `core/connection/bedrock/MixinConnectScreen_1.java` | 0/2 | MISSING | `@WrapOperation handleNullExceptionMessage`; `@Redirect markAsConnecting` |
| `core/connection/bedrock/MixinConnection.java` | 0/5 | MISSING | `channelRegistered (merged override of SimpleChannelInboundHandler#channelRegistered)`; `@WrapWithCondition dontCallChannelActiveTwice`; `@Inject setTargetVersion (connect HEAD, mutates the eventLoopGroupHolder arg via LocalRef)`; `@WrapOperation useRakNetChannelFactory`; `@WrapOperation useRakNetPingHandlers` |
| `core/integration/MixinConnectScreen_1.java` | 0/4 | MISSING | `@WrapOperation setServerInfoAndProtocolVersion`; `@WrapOperation resetProtocolVersionAfterDisconnect`; `@Redirect useClassiCubeUsername`; `@Unique boolean viaFabricPlus$useClassiCubeAccount` |
| `features/bedrock/inventory/MixinInventoryScreen.java` | 0/1 | MISSING | `@Inject sendBedrockPacket (<init> RETURN)` |
| `features/bedrock/movement/MixinEntity.java` | 0/2 | MISSING | `@Inject cancelSwimming`; `@Redirect prioritySlowestMovementMultiplier` |
| `features/bedrock/networking/MixinServerNameResolver.java` | 0/1 | MISSING | `@Inject oldResolveBehaviour` |
| `features/interaction/container_clicking/MixinAbstractContainerMenu.java` | 0/3 | MISSING | `@Redirect preventUpdate`; `@Unique short viaFabricPlus$actionId (state)`; `IAbstractContainerMenu implementation (viaFabricPlus$getActionId, viaFabricPlus$incrementAndGetActionId)` |
| `features/interaction/container_clicking/MixinMultiPlayerGameMode.java` | 0/5 | MISSING | `@ModifyVariable captureOldItems`; `@WrapWithCondition handleWindowClick`; `@Inject removeClickActions`; `@Unique field viaFabricPlus$oldCursorStack`; `@Unique field viaFabricPlus$oldItems` |
| `features/interaction/replace_block_item_use_logic/MixinMultiPlayerGameMode.java` | 0/16 | MISSING | `@Redirect changeSpectatorAction`; `@Inject sendPlayerPosPacket`; `@Inject changeCalculation`; `@WrapWithCondition fixPacketOrder`; `@Redirect checkFireBlock`; `@Inject resetBlockBreaking`; `@Inject interactBlock1_12_2`; `@Inject cancelOffHandItemInteract`; `@Inject cancelOffHandBlockPlace`; `@Redirect eitherSuccessOrPass`; `@Inject trackLastUsedItem`; `@Overwrite lambda$useItemOn$0`; `@Redirect catchPacketCancelException`; `@Redirect fixMiningReset1_7`; `@WrapWithCondition preventPacketWhenNotMining1_7`; `@WrapWithCondition preventAttackResetWhenNotMining1_7` |
| `features/limitation/max_chat_length/MixinServerboundChatPacket.java` | 0/1 | MISSING | `@ModifyConstant(method="write", intValue=256) modifyChatLength` |
| `features/limitation/max_chat_length/MixinStringUtil.java` | 0/1 | MISSING | `@ModifyExpressionValue(method="trimChatMessage", CONSTANT intValue=256) modifyMaxChatLength` |
| `features/networking/legacy_chat_signature/MixinAccountProfileKeyPairManager.java` | 0/1 | MISSING | `@Inject trackLegacyKey (parsePublicKey, RETURN)` |
| `features/networking/remove_signed_commands/MixinGameModeSwitcherScreen.java` | 0/1 | MISSING | `@Redirect wrapAsCommand (static switchToHoveredGameMode(Minecraft, GameModeSwitcherScreen$GameModeIcon) -> ClientPacketListener#send)` |
| `features/networking/remove_signed_commands/MixinKeyboardHandler.java` | 0/1 | MISSING | `@Redirect wrapAsCommand (handleDebugKeys -> ClientPacketListener#send)` |
| `features/networking/run_command_action/MixinScreen.java` | 0/1 | MISSING | `@Inject changeCommandHandling (static, HEAD, cancellable)` |
| `core/connection/bedrock/MixinEventLoopGroupHolder.java` | 2/3 | PARTIAL | `@Inject resetConnectingFlag (remote, @At("RETURN") = every return)` |
| `core/integration/sync_tasks/MixinClientCommonPacketListenerImpl.java` | 1/1 | PARTIAL | `prerequisite of @Inject(HEAD) handleSyncTask - DataCustomPayload codec registration (upstream SyncTasks.init -> DataCustomPayload.init -> fabric PayloadTypeRegistry)` |
| `features/networking/packet_handling/MixinClientPacketListener.java` | 20/21 | PARTIAL | `@WrapWithCondition removeChatPacketError (second call site)` |
| `features/networking/srv_resolving/MixinConnectScreen_1.java` | 2/2 | PARTIAL | `@Redirect getRealAddress - second (un-ordinaled) call site`; `@Redirect getRealPort - second (un-ordinaled) call site`; `(not upstream) unconditional `address` reassignment - over-application, must be reverted` |
| `features/networking/srv_resolving/MixinServerAddress.java` | 1/1 | PARTIAL | `@Inject resolveSrv - the `!cir.getReturnValue().equals(INVALID)` guard, plus the vanilla branch it replaced` |

详细行为与落点：

**`core/connection/bedrock/MixinConnectScreen_1.java`** — MISSING 0/2, 目标 `net.minecraft.client.gui.screens.ConnectScreen$1 (the "Server Connector" Thread in ConnectScreen#connect)`

- `@WrapOperation handleNullExceptionMessage` — Return "" instead of null from Exception#getMessage() inside run()'s catch block - Via/RakNet/ViaLegacy pipeline exceptions can carry a null message, which currently NPEs on the .replaceAll chain. Version-independent (all targets, not just Bedrock).
  - 落点: ConnectScreen$1#run, catch(Exception) block: both cause.getMessage() reads at ConnectScreen.java:220 and :221
- `@Redirect markAsConnecting` — Replace EventLoopGroupHolder.remote(allowNativeTransport) with a call that then marks the returned holder viaFabricPlus$setConnecting(true), so bedrock MixinConnection#useRakNetPingHandlers takes the connect path instead of the RakNet ping path. Bedrock targets only in effect.
  - 落点: ConnectScreen$1#run, the EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()) argument of Connection.connect(...) inside the synchronized block at ConnectScreen.java:146-149
- 备注: ConnectScreen.java carries only the srv_resolving and legacy_chat_signature ports (markers at :135, :153, :174); neither bedrock hook is present. Nothing anywhere in the tree calls viaFabricPlus$setConnecting(true) (grep over src/main/java), so the connecting flag is permanently false.

**`core/connection/bedrock/MixinConnection.java`** — MISSING 0/5, 目标 `net.minecraft.network.Connection (mixin priority 1001, applied after core/connection/MixinConnection)`

- `channelRegistered (merged override of SimpleChannelInboundHandler#channelRegistered)` — After super.channelRegistered(ctx), when target version is BedrockProtocolVersion.bedrockLatest, call this.channelActive(ctx) manually - RakNet/NetherNet channels never fire channelActive on their own.
  - 落点: Connection has no channelRegistered override at all; add one next to channelActive (Connection.java:106-115)
- `@WrapWithCondition dontCallChannelActiveTwice` — Skip the super.channelActive(ctx) call when target version is bedrockLatest, so the manual invocation above is not duplicated.
  - 落点: Connection#channelActive, the super.channelActive(ctx) call at Connection.java:108
- `@Inject setTargetVersion (connect HEAD, mutates the eventLoopGroupHolder arg via LocalRef)` — When bedrockLatest and (eventLoopGroupHolder.channelCls()==KQueueSocketChannel.class \|\| address instanceof NetherNetInetSocketAddress), swap the holder for EventLoopGroupHolder.remote(false) (NIO) and carry over viaFabricPlus$isConnecting() - RakNet has no KQueue support and NetherNet requires NIO.
  - 落点: Connection#connect HEAD (Connection.java:475), before the Bootstrap chain at :485; must rebind the eventLoopGroupHolder local used at :485 and :499
- `@WrapOperation useRakNetChannelFactory` — For bedrockLatest replace Bootstrap#channel(Class) with channelFactory(...): NetherNetChannelFactory.client(new PeerConnectionFactory(), NetherNetXboxRpcSignaling\|NetherNetXboxSignaling(authHeader)) for NetherNetInetSocketAddress, otherwise RakChannelFactory.client(NioDatagramChannel/EpollDatagramChannel mapped from the socket channel class, throwing for anything else).
  - 落点: Connection#connect, the .channel(eventLoopGroupHolder.channelCls()) call at Connection.java:499
- `@WrapOperation useRakNetPingHandlers` — For bedrockLatest replace Bootstrap#connect(InetAddress,int): NetherNet -> connect(netherNetAddress) + remove viabedrock MessageCodec on success; RakNet while !isConnecting() (i.e. pinging) -> register().channel().bind(new InetSocketAddress(0)) and on success replace MessageCodec with RakNetPingEncapsulationCodec(target), remove PacketCodec and HandlerNames.SPLITTER, and add RakNetStatusProtocol.INSTANCE to the UserConnection's protocol pipeline.
  - 落点: Connection#connect, the terminal .connect(address.getAddress(), address.getPort()) call at Connection.java:499
- 备注: Nothing bedrock-related exists in net/minecraft/network/Connection.java (no matches for bedrock/raknet/NetherNet/channelRegistered/channelFactory). RakChannelFactory / RakNetStatusProtocol / NetherNetChannelFactory appear nowhere in src/main/java except the verbatim VFP copy RakNetPingEncapsulationCodec.java, which is unreferenced. Bedrock pinging and joining cannot work until this lands; it is pure transport/pipeline work, hence P0 rather than rule 5's "Bedrock-only extras" P3.

**`core/integration/MixinConnectScreen_1.java`** — MISSING 0/4, 目标 `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread created in ConnectScreen#connect)`

- `@WrapOperation setServerInfoAndProtocolVersion` — Picks the effective target version for the join: per-server forced version when viaFabricPlus$forcedVersion() != null && !viaFabricPlus$passedDirectConnectScreen() (then resets that flag to false), else the global target; if the result is AUTO_DETECT_PROTOCOL, reuse ProtocolVersion.getProtocol(server.protocol) when the server was already pinged (state SUCCESSFUL or INCOMPATIBLE) and otherwise call ProtocolVersionDetector.get(hostAndPort, address, NATIVE_VERSION) while showing status base.viafabricplus.detecting_server_version (swallowing ConnectException); finally ProtocolTranslator.setTargetVersion(version, true). Also latches viaFabricPlus$useClassiCubeAccount = AuthenticationSettings.INSTANCE.setSessionNameToClassiCubeNameInServerList && ViaFabricPlusClassicMPPassProvider.classicubeMPPass != null. Applies to all target versions.
  - 落点: ConnectScreen#connect, inside the anonymous Thread#run, immediately after `address = resolvedAddress.get();` (ConnectScreen.java:134) and before the vfp$useRawAddress block
- `@WrapOperation resetProtocolVersionAfterDisconnect` — Wraps Connection.connect and calls ProtocolTranslator.injectPreviousVersionReset(future.channel()) so a version set with revertOnDisconnect=true is reverted when the channel closes. All versions.
  - 落点: ConnectScreen#connect, directly after the `ConnectScreen.this.channelFuture = Connection.connect(address, EventLoopGroupHolder.remote(...), pendingConnection);` assignment inside the synchronized block (ConnectScreen.java:147-149)
- `@Redirect useClassiCubeUsername` — Returns SaveManager.INSTANCE.getAccountsSave().getClassicubeAccount().username() instead of User#getName() when the latch from hook 1 is set and an account is stored; otherwise the real name. All versions (ClassiCube/classic servers).
  - 落点: ConnectScreen#connect, the `minecraft.getUser().getName()` argument of `new ServerboundHelloPacket(...)` (ConnectScreen.java:205)
- `@Unique boolean viaFabricPlus$useClassiCubeAccount` — State latched in hook 1 and read in hook 3; needs a local (or field) visible across the Thread#run body.
  - 落点: ConnectScreen#connect, anonymous Thread#run local state
- 备注: Real P0 gap. The per-server forced version is honoured only for pings (ServerStatusPinger.java:66-74 via the LocalSampleLogger carrier) and for the Bedrock port swap (JoinMultiplayerScreen.java:272-277); on an actual join Connection.connect (Connection.java:476-484) finds no per-connection version and falls back to ProtocolTranslator.getTargetVersion(), silently mapping AUTO_DETECT_PROTOCOL to NATIVE_VERSION. ProtocolVersionDetector (com/viaversion/viafabricplus/protocoltranslator/util/ProtocolVersionDetector.java) has no caller anywhere in the tree; AuthenticationSettings.setSessionNameToClassiCubeNameInServerList (AuthenticationSettings.java:35) is never read. When porting, the global ProtocolTranslator.setTargetVersion must be used, not only IConnection#viaFabricPlus$setTargetVersion, because every inlined gate reads ProtocolTranslator.getTargetVersion().

**`features/bedrock/inventory/MixinInventoryScreen.java`** — MISSING 0/1, 目标 `net.minecraft.client.gui.screens.inventory.InventoryScreen`

- `@Inject sendBedrockPacket (<init> RETURN)` — bedrockLatest only: emit a serverbound Bedrock INTERACT packet on inventory open - PacketWrapper.create(ServerboundBedrockPackets.INTERACT, ProtocolTranslator.getPlayNetworkUserConnection()), write Types.UNSIGNED_BYTE = (short) InteractPacket_Action.OpenInventory.getValue(), BedrockTypes.UNSIGNED_VAR_LONG = connection.get(EntityTracker.class).getClientPlayer().runtimeId(), BedrockTypes.OPTIONAL_POSITION_3F = null, then sendToServer(BedrockProtocol.class). Without it the Bedrock server never learns the client opened its inventory.
  - 落点: InventoryScreen#<init>(Player), at the end of the constructor body (Sigma InventoryScreen.java:33, immediately after `this.effects = new EffectsInInventory(this);`)
- 备注: P0: this is serverbound packet generation, not cosmetics. Directly portable - ViaBedrock 0.0.29 is a declared dependency (pom.xml:78-88), so ServerboundBedrockPackets, InteractPacket_Action, EntityTracker and BedrockTypes are all on Sigma's compile classpath, and ProtocolTranslator.getPlayNetworkUserConnection() already exists at src/main/java/com/viaversion/viafabricplus/protocoltranslator/ProtocolTranslator.java.

**`features/bedrock/movement/MixinEntity.java`** — MISSING 0/2, 目标 `net.minecraft.world.entity.Entity`

- `@Inject cancelSwimming` — When target == BedrockProtocolVersion.bedrockLatest and the new swimming value differs from isSwimming(), push StartSwimming/StopSwimming into EntityTracker.getClientPlayer().addAuthInputData() on the play UserConnection.
  - 落点: Entity#setSwimming(boolean) HEAD, before the setSharedFlag(4, swimming) call (Entity.java:2951)
- `@Redirect prioritySlowestMovementMultiplier` — On bedrockLatest, when the existing stuckSpeedMultiplier != Vec3.ZERO, component-wise min() the incoming multiplier into it instead of overwriting; otherwise plain assign.
  - 落点: Entity#makeStuckInBlock(BlockState, Vec3), the PUTFIELD write `this.stuckSpeedMultiplier = speedMultiplier;` (Entity.java:3163)
- 备注: Hook count: @Inject setSwimming(HEAD) + @Redirect on the PUTFIELD of stuckSpeedMultiplier in makeStuckInBlock. Entity.java is otherwise ported (18 "MODIFIED for porting" markers incl. VFP removeLeashActions at :2472), so the absence is a real gap, not a wrong file. Priority is P0 because the setSwimming hook feeds input flags into the Bedrock PlayerAuthInput packet (packet generation); the makeStuckInBlock half is P1 physics. Both need ProtocolTranslator.getPlayNetworkUserConnection()/EntityTracker access, which has no Sigma equivalent yet.

**`features/bedrock/networking/MixinServerNameResolver.java`** — MISSING 0/1, 目标 `net.minecraft.client.multiplayer.resolver.ServerNameResolver`

- `@Inject oldResolveBehaviour` — When target equals BedrockProtocolVersion.bedrockLatest, return this.resolver.resolve(address) directly, skipping addressCheck.isAllowed and the redirectHandler SRV lookup.
  - 落点: ServerNameResolver#resolveAddress(ServerAddress) HEAD, before the first this.resolver.resolve(address) (ServerNameResolver.java:26)
- 备注: Single @Inject at HEAD, cancellable. P0: on Bedrock this bypasses the SRV redirect lookup and the AddressCheck allow-list, returning this.resolver.resolve(address) raw - i.e. it changes which endpoint the client actually dials, so joining a Bedrock/RakNet server can silently resolve to the wrong host without it. The two dead imports are a false positive for any grep-based "is it ported" check.

**`features/interaction/container_clicking/MixinAbstractContainerMenu.java`** — MISSING 0/3, 目标 `net.minecraft.world.inventory.AbstractContainerMenu`

- `@Redirect preventUpdate` — Only assigns the carried stack when target >=1.17.1; on older versions Window Items carries no cursor item, so writing it would wipe the client's cursor stack.
  - 落点: AbstractContainerMenu#initializeContents - the PUTFIELD of `carried` at E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/inventory/AbstractContainerMenu.java:628
- `@Unique short viaFabricPlus$actionId (state)` — Counter for the legacy Window Click action number (the transaction id that <=1.16.5 servers acknowledge).
  - 落点: field on AbstractContainerMenu, reset per menu instance
- `IAbstractContainerMenu implementation (viaFabricPlus$getActionId, viaFabricPlus$incrementAndGetActionId)` — Supplies the pre-increment action number for each legacy container click.
  - 落点: accessor pair on AbstractContainerMenu; consumer is upstream MixinMultiPlayerGameMode#viaFabricPlus$clickSlot1_16_5, which writes containerClick.write(Types.SHORT, ...incrementAndGetActionId()) into the hand-built ServerboundPackets1_16_2.CONTAINER_CLICK
- 备注: P0: the action number is the transaction id in the <=1.16.5 container-click packet VFP builds by hand. The consumer half is unported too - E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/MultiPlayerGameMode.java has zero 'MODIFIED for porting' comments and no CONTAINER_CLICK/ViaVersion code - so the whole container_clicking feature is absent, and the carried-suppression hook above is separately observable as a wrong cursor item on <1.17.1 servers.

**`features/interaction/container_clicking/MixinMultiPlayerGameMode.java`** — MISSING 0/5, 目标 `net.minecraft.client.multiplayer.MultiPlayerGameMode`

- `@ModifyVariable captureOldItems` — On the STORE of the local `itemsBeforeClick`, snapshot player.containerMenu.getCarried().copy() into viaFabricPlus$oldCursorStack and keep the list in viaFabricPlus$oldItems - both are consumed by the 1.16.5 click writer to send the pre-click slot item.
  - 落点: MultiPlayerGameMode#handleContainerInput, the STORE of local variable `itemsBeforeClick` at line 458 (after the for loop that fills it, i.e. capture must be after line 461's loop completes as upstream stores the fully-populated list)
- `@WrapWithCondition handleWindowClick` — Suppresses the vanilla send and writes a legacy packet instead. targetVersion <= 1.16.4: viaFabricPlus$clickSlot1_16_5 - BYTE containerId, SHORT slotNum, BYTE buttonNum, SHORT action id from IAbstractContainerMenu#viaFabricPlus$incrementAndGetActionId, VAR_INT containerInput.ordinal(), ITEM1_13_2 of the pre-click slot item (EMPTY for QUICK_CRAFT, THROW, QUICK_MOVE when > 1.11.1, and PICKUP at slot -999; oldCursorStack when slotNum out of range), scheduleSendToServer(Protocol1_16_4To1_17). targetVersion <= 1.21.4: viaFabricPlus$clickSlot1_21_4 - VAR_INT containerId, VAR_INT stateId, SHORT slotNum, BYTE buttonNum, VAR_INT containerInput.id(), then the changed-slot map re-encoded as full VersionedTypes.V1_21_4.item values read from the live menu slots plus the carried item, scheduleSendToServer(Protocol1_21_4To1_21_5). >= 1.21.5 keeps the vanilla send.
  - 落点: MultiPlayerGameMode#handleContainerInput, the `this.connection.send(new ServerboundContainerClickPacket(...))` call at line 476
- `@Inject removeClickActions` — Cancels handleContainerInput for click types the old protocol cannot encode: <= b1.5-b1.5.2 anything that is not PICKUP; <= r1.4.6-r1.4.7 anything that is not PICKUP/QUICK_MOVE/SWAP/CLONE; <= 1.15.2 SWAP with buttonNum == 40 (the F offhand swap).
  - 落点: MultiPlayerGameMode#handleContainerInput HEAD, line 451 (before the containerId mismatch check)
- `@Unique field viaFabricPlus$oldCursorStack` — Per-gamemode ItemStack state holding the carried stack as it was before the click; read and then nulled by the 1.16.5 writer.
  - 落点: New private field on MultiPlayerGameMode (alongside the existing fields near line 60)
- `@Unique field viaFabricPlus$oldItems` — Per-gamemode List<ItemStack> state holding the pre-click slot contents; read and then nulled by the 1.16.5 writer.
  - 落点: New private field on MultiPlayerGameMode (alongside the existing fields near line 60)
- 备注: Highest-value gap in this unit: on any target <= 1.21.4 the client currently sends 26.2 hashed-stack container clicks straight through Via's default mapping, so every inventory click is desync-prone. Dependency: the 1.16.5 path needs IAbstractContainerMenu#viaFabricPlus$incrementAndGetActionId - the interface is present at E:/.sigma/Sigma-Modern/src/main/java/com/viaversion/viafabricplus/injection/access/interaction/container_clicking/IAbstractContainerMenu.java:28 but NOTHING implements it (net.minecraft.world.inventory.AbstractContainerMenu does not), so that accessor mixin must land too. ItemTranslator is available at com/viaversion/viafabricplus/protocoltranslator/translator/ItemTranslator.java.

**`features/interaction/replace_block_item_use_logic/MixinMultiPlayerGameMode.java`** — MISSING 0/16, 目标 `net.minecraft.client.multiplayer.MultiPlayerGameMode`

- `@Redirect changeSpectatorAction` — <=1.21: spectator interaction returns InteractionResult.SUCCESS instead of CONSUME.
  - 落点: MultiPlayerGameMode#performUseItemOn, the GETSTATIC of InteractionResult.CONSUME in the `if (this.localPlayerMode == GameType.SPECTATOR)` branch (MultiPlayerGameMode.java:345)
- `@Inject sendPlayerPosPacket` — 1.17..1.20.5 inclusive: send an extra ServerboundMovePlayerPacket.PosRot(x, y, z, yRot, xRot, onGround, horizontalCollision) before the use-item prediction.
  - 落点: MultiPlayerGameMode#useItem, immediately after `this.ensureHasSentCarriedItem();` (MultiPlayerGameMode.java:391)
- `@Inject changeCalculation` — <=1.19.4: destroy stage = (int)(destroyProgress * 10.0F) - 1 unconditionally (no `destroyProgress > 0` guard, no -1 sentinel).
  - 落点: MultiPlayerGameMode#getDestroyStage, HEAD - replaces `return this.destroyProgress > 0.0F ? (int)(this.destroyProgress * 10.0F) : -1;` (MultiPlayerGameMode.java:558-560)
- `@WrapWithCondition fixPacketOrder` — <=1.18.2: skip startPrediction entirely; instead send ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot()) FIRST, then call predictiveAction.predict(0). Changes serverbound ordering (packet before the client-side item use).
  - 落点: MultiPlayerGameMode#useItem, around the `this.startPrediction(this.minecraft.level, sequence -> {...})` call (MultiPlayerGameMode.java:393)
- `@Redirect checkFireBlock` — <=1.15.2: before destroying, if the block at pos.relative(direction) is Blocks.FIRE, fire levelEvent(player, 1009, thatPos, 0) + level.removeBlock(thatPos, false) and skip destroyBlock for this tick.
  - 落点: the `this.destroyBlock(pos);` call inside lambda$startDestroyBlock$0 (the instabuild branch lambda, MultiPlayerGameMode.java:168) and inside lambda$continueDestroyBlock$0 (the instabuild branch lambda, MultiPlayerGameMode.java:245). The survival-path lambdas at lines 195 and 282 are deliberately NOT targeted upstream.
- `@Inject resetBlockBreaking` — <=1.14.3: after a successful/attempted destroyBlock, set destroyBlockPos = new BlockPos(x, -1, z) so the next mining start is not treated as the same target.
  - 落点: MultiPlayerGameMode#destroyBlock, TAIL - just before the final `return changed;` (MultiPlayerGameMode.java:148)
- `@Inject interactBlock1_12_2` — <=1.12.2: full replacement of the block-interaction path, delivered by throwing ActionResultException1_12_2. (a) if held item is a BlockItem and the clicked block is SNOW with LAYERS==1, retarget the hit to Direction.UP; (b) pre-validate with BlockPlaceContext.canPlace() and BlockItem#getPlacementState != null, else throw PASS; (c) send ServerboundUseItemOnPacket(hand, blockHit, 0) BEFORE running useOn; (d) empty stack -> throw PASS; (e) creative preserves stack count around useOn; (f) any non-consuming result is folded to PASS (1.12.2 has no FAIL).
  - 落点: MultiPlayerGameMode#performUseItemOn, at the INVOKE of ItemStack#isEmpty() ordinal 2 - the `!itemStack.isEmpty()` test in `if (!itemStack.isEmpty() && !player.getCooldowns().isOnCooldown(itemStack))` (MultiPlayerGameMode.java:369)
- `@Inject cancelOffHandItemInteract` — <=1.8: return InteractionResult.PASS for any hand != MAIN_HAND.
  - 落点: MultiPlayerGameMode#useItem, HEAD (MultiPlayerGameMode.java:386)
- `@Inject cancelOffHandBlockPlace` — <=1.8: return InteractionResult.PASS for any hand != MAIN_HAND.
  - 落点: MultiPlayerGameMode#useItemOn, HEAD (MultiPlayerGameMode.java:327)
- `@Redirect eitherSuccessOrPass` — <=1.8: reinterpret the result of ItemStack#use as 1.8 did (boolean). accepted = output non-empty && (output != the pre-use stack \|\| output.getCount() != pre-use count); if vanilla's consumesAction() disagrees with accepted, return SUCCESS.heldItemTransformedTo(output) or PASS accordingly.
  - 落点: the startPrediction lambda in MultiPlayerGameMode#useItem, at `InteractionResult resultHolder = itemStack.use(this.minecraft.level, player, hand);` (MultiPlayerGameMode.java:401)
- `@Inject trackLastUsedItem` — <=1.8: ViaFabricPlusHandItemProvider.lastUsedItem = player.getItemInHand(hand).copy() before the item is used.
  - 落点: HEAD of the startPrediction lambda inside MultiPlayerGameMode#useItem (lambda begins MultiPlayerGameMode.java:393)
- `@Overwrite lambda$useItemOn$0` — Always: set ViaFabricPlusHandItemProvider.lastUsedItem (<=1.8 only) then wrap performUseItemOn in try/catch(ActionResultException1_12_2) - on catch, write e.getActionResult() into the MutableObject and rethrow so the packet send is skipped.
  - 落点: the startPrediction lambda body inside MultiPlayerGameMode#useItemOn - `result.setValue(this.performUseItemOn(...)); return new ServerboundUseItemOnPacket(hand, blockHit, sequence);` (MultiPlayerGameMode.java:334-337)
- `@Redirect catchPacketCancelException` — Always (ungated): wrap the startPrediction call in try/catch(ActionResultException1_12_2) and swallow it, so the 1.12.2 path can abort packet emission without escaping.
  - 落点: MultiPlayerGameMode#useItemOn, the `this.startPrediction(this.minecraft.level, sequence -> {...});` call (MultiPlayerGameMode.java:334)
- `@Redirect fixMiningReset1_7` — <=1.7.6: treat isDestroying as true so the stopDestroyBlock body always runs (progress/tutorial/highlight reset).
  - 落点: MultiPlayerGameMode#stopDestroyBlock, the GETFIELD of isDestroying in `if (this.isDestroying)` (MultiPlayerGameMode.java:213)
- `@WrapWithCondition preventPacketWhenNotMining1_7` — <=1.7.6: suppress the ABORT_DESTROY_BLOCK send unless isDestroying is genuinely true (pairs with fixMiningReset1_7 so the body runs but no packet leaves).
  - 落点: MultiPlayerGameMode#stopDestroyBlock, the `this.connection.send(new ServerboundPlayerActionPacket(ABORT_DESTROY_BLOCK, this.destroyBlockPos, Direction.DOWN))` call (MultiPlayerGameMode.java:220-221)
- `@WrapWithCondition preventAttackResetWhenNotMining1_7` — <=1.7.6: suppress resetAttackStrengthTicker unless isDestroying is genuinely true.
  - 落点: MultiPlayerGameMode#stopDestroyBlock, `this.minecraft.player.resetAttackStrengthTicker();` (MultiPlayerGameMode.java:225)
- 备注: Counterpart E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/MultiPlayerGameMode.java (573 lines) read in full: ZERO occurrences of ProtocolTranslator, ProtocolVersion or "// MODIFIED for porting". Entire mixin un-ported. Supporting classes were copied but are dead: ActionResultException1_12_2 (com/viaversion/viafabricplus/features/interaction/replace_block_placement_logic/ActionResultException1_12_2.java) has no thrower or catcher, and ViaFabricPlusHandItemProvider.lastUsedItem is registered as a Via provider (ViaFabricPlusPlatformLoader.java:68) but is never assigned anywhere, so 1.8 hand-item tracking is silently broken. The @Unique helper viaFabricPlus$extinguishFire is a method, not state, so it is folded into the checkFireBlock hook and not counted. Highest-value P0 items here are fixPacketOrder, sendPlayerPosPacket and interactBlock1_12_2 (all change serverbou

**`features/limitation/max_chat_length/MixinServerboundChatPacket.java`** — MISSING 0/1, 目标 `net.minecraft.network.protocol.game.ServerboundChatPacket`

- `@ModifyConstant(method="write", intValue=256) modifyChatLength` — Use MaxChatLength.getChatLength() as the writeUtf cap so messages longer than 256 chars (classic LONGER_MESSAGES, Bedrock 512) can be encoded instead of throwing on encode.
  - 落点: ServerboundChatPacket#write - the literal 256 in output.writeUtf(this.message, 256) at ServerboundChatPacket.java:24. Upstream patches write() only; leave the readUtf(256) in the FriendlyByteBuf constructor at :19 alone.
- 备注: Packet-encoding limit, hence P0: a message longer than 256 chars fails in writeUtf and kills the connection. Only reachable once the ChatScreen/StringUtil hooks in this feature are also ported, but it is the hook that actually decides what goes on the wire.

**`features/limitation/max_chat_length/MixinStringUtil.java`** — MISSING 0/1, 目标 `net.minecraft.util.StringUtil`

- `@ModifyExpressionValue(method="trimChatMessage", CONSTANT intValue=256) modifyMaxChatLength` — Truncate outgoing chat at MaxChatLength.getChatLength() instead of the fixed 256, so longer-message targets are not clipped and short-limit targets (<= 1.9.3 -> 100) are clipped client-side.
  - 落点: StringUtil#trimChatMessage - the literal 256 argument of truncateStringIfNecessary(message, 256, false) at StringUtil.java:59
- 备注: P0 because this is the value applied to the message that is actually serialised for the server (via ChatScreen#normalizeChatMessage), not a display-only limit.

**`features/networking/legacy_chat_signature/MixinAccountProfileKeyPairManager.java`** — MISSING 0/1, 目标 `net.minecraft.client.multiplayer.AccountProfileKeyPairManager`

- `@Inject trackLegacyKey (parsePublicKey, RETURN)` — copies the legacy pre-1.20-rc1 'publicKeySignature' from the KeyPairResponse onto the returned ProfilePublicKey.Data via viafabricplus$setLegacyPublicKeySignature. Ungated upstream; only consumed for target version exactly 1.19.0.
  - 落点: AccountProfileKeyPairManager#parsePublicKey — on the ProfilePublicKey.Data constructed and returned at line 142, immediately before the return
- 备注: The sink exists (ProfilePublicKey.Data carries viafabricplus$legacyKeySignature) and the consumer exists (ConnectScreen.java:165), but nothing in the tree ever writes it, so ChatSession1_19_0 is never installed and 1.19.0 servers with enforce-secure-profiles will reject signed chat. The value's source is also unported (see the MixinKeyPairResponse and MixinYggdrasilUserApiService rows) — fixing all three in AccountProfileKeyPairManager is the cheapest route.

**`features/networking/remove_signed_commands/MixinGameModeSwitcherScreen.java`** — MISSING 0/1, 目标 `net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen`

- `@Redirect wrapAsCommand (static switchToHoveredGameMode(Minecraft, GameModeSwitcherScreen$GameModeIcon) -> ClientPacketListener#send)` — For targets <=1.21.5, swallow the ServerboundChangeGameModePacket and call SignedCommands1_21_6.sendGameMode(mode) instead, which routes it through ClientPacketListener#sendCommand as '/gamemode <mode>' (and the username-prefixed '/gamemode <user> <id>' form for <=1.2.5); otherwise send the packet unchanged.
  - 落点: GameModeSwitcherScreen#switchToHoveredGameMode(Minecraft, GameModeIcon), the minecraft.player.connection.send(new ServerboundChangeGameModePacket(toGameMode.mode)) invocation (GameModeSwitcherScreen.java:114)
- 备注: Not fatal today because VV's own Protocol1_21_5To1_21_6 still maps CHANGE_GAME_MODE to CHAT_COMMAND "gamemode <mode>" (viaversion-common sources Protocol1_21_5To1_21_6.java:105), so 1.21.5-and-older targets keep working through the library path. Genuinely broken for b1.8-1.2.5 targets, which need SignedCommands1_21_6's username-prefixed legacy syntax, and for <=1.20.3 servers where VFP intentionally cancels unsigned commands (row 15).

**`features/networking/remove_signed_commands/MixinKeyboardHandler.java`** — MISSING 0/1, 目标 `net.minecraft.client.KeyboardHandler`

- `@Redirect wrapAsCommand (handleDebugKeys -> ClientPacketListener#send)` — For targets <=1.21.5, replace the ServerboundChangeGameModePacket send with SignedCommands1_21_6.sendGameMode(mode) (client-side '/gamemode ...' command, legacy username form for <=1.2.5); otherwise send unchanged. Upstream's @Redirect has no ordinal, so it covers every ClientPacketListener#send call in the method.
  - 落点: KeyboardHandler#handleDebugKeys, both sends in the options.keyDebugSpectate branch: ServerboundChangeGameModePacket(GameType.SPECTATOR) (KeyboardHandler.java:241) and ServerboundChangeGameModePacket(newGameType) (KeyboardHandler.java:244)
- 备注: Same practical impact as row 12 (F3+F4 / F3+N debug gamemode switching): survives on 1.21.5-and-older via VV's default CHANGE_GAME_MODE -> CHAT_COMMAND mapping, breaks on b1.8-1.2.5 legacy targets. Both sites need the inline, not just the first.

**`features/networking/run_command_action/MixinScreen.java`** — MISSING 0/1, 目标 `net.minecraft.client.gui.screens.Screen`

- `@Inject changeCommandHandling (static, HEAD, cancellable)` — For target <=1.21.4: a run_command click event whose value does not start with "/" must not be routed through sendUnattendedCommand at all (cancel), and for target <=1.19 it must instead be sent as chat via player.connection.sendChat(command). Targets newer than 1.21.4 keep vanilla behaviour.
  - 落点: Screen#clickCommandAction HEAD (line 322), before player.connection.sendUnattendedCommand(...); the same fix is needed for the two call sites at Screen.java:255 and BookViewScreen.java:243 only insofar as they go through this method
- 备注: Only hook in the mixin; the @Shadow minecraft field is unused by it. Affects which serverbound packet (chat vs command) a clickable-text action produces on legacy targets, hence P0.

**`core/connection/bedrock/MixinEventLoopGroupHolder.java`** — PARTIAL 2/3, 目标 `net.minecraft.server.network.EventLoopGroupHolder`

- `@Inject resetConnectingFlag (remote, @At("RETURN") = every return)` — Reset viaFabricPlus$setConnecting(false) on the returned holder for ALL return paths of remote(boolean). The holders are static singletons, so a stale connecting=true from a previous connect makes the next Bedrock ping take the connect path. Version-independent.
  - 落点: EventLoopGroupHolder#remote(boolean): the two native-transport early returns, `return KQUEUE;` at EventLoopGroupHolder.java:71 and `return EPOLL;` at :75 (the NIO path at :78-81 already resets)
- 备注: Only reachable on macOS/Linux with useNativeTransport, and currently latent because nothing sets the flag to true (bedrock ConnectScreen_1 hook missing). Cleanest fix: reset in one place at the end, or wrap all three returns.

**`core/integration/sync_tasks/MixinClientCommonPacketListenerImpl.java`** — PARTIAL 1/1, 目标 `net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl`

- `prerequisite of @Inject(HEAD) handleSyncTask - DataCustomPayload codec registration (upstream SyncTasks.init -> DataCustomPayload.init -> fabric PayloadTypeRegistry)` — Make the clientbound custom-payload decoder resolve DataCustomPayload.ID so packet.payload() can be a DataCustomPayload: add a CustomPacketPayload.TypeAndCodec<>(DataCustomPayload.ID, <the read-only codec from DataCustomPayload.init>) to ClientboundCustomPayloadPacket's CONFIG_STREAM_CODEC and GAMEPLAY_STREAM_CODEC lists (Via sends sync tasks in the play phase too), or give PayloadTypeRegistry.Registry a getter and have CustomPacketPayload.codec's findCodec consult it before the DiscardedPayload fallback. Without this the inlined hook is dead code.
  - 落点: net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java:17-25 (CONFIG_STREAM_CODEC / GAMEPLAY_STREAM_CODEC type lists, only BrandPayload) and net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java:33-39 (write-only codecs map)
- 备注: Correctly placed at the true HEAD, i.e. before PacketUtils.ensureRunningOnSameThread, so the sync task still runs on the network thread as upstream intends. The payload type itself is registered (DataCustomPayload.init via SyncTasks.init, ViaFabricPlusImpl.java:116). Upstream's `priority = 1` only ordered this against Fabric's Networking API and has no meaning without a mixin runtime. \| REFUTED: The hook text is at the right point (ClientCommonPacketListenerImpl.java:196-200, before PacketUtils.ensureRunningOnSameThread, so still on the netty thread; `return` == ci.cancel()), but the branch can never be taken, so the behaviour is NOT ported. DataCustomPayload is only ever registered through the Fabric stand-in net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java, whose inner Registry (lines 33-39) puts codecs into a private ConcurrentHashMap that has NO reader anywhere in the 

**`features/networking/packet_handling/MixinClientPacketListener.java`** — PARTIAL 20/21, 目标 `net.minecraft.client.multiplayer.ClientPacketListener`

- `@WrapWithCondition removeChatPacketError (second call site)` — The un-ordinaled @At also wraps the second Logger#error(String,Object) in handlePlayerChat; only the first is gated. Log noise only.
  - 落点: ClientPacketListener#handlePlayerChat, the unknown-sender LOGGER.error call
- 备注: hookTotal = 20 annotations + the @Unique viaFabricPlus$teleportConfirmPacket state field. The @Unique field is replaced by a method-local (vfp$teleportConfirm at :889) - equivalent, since upstream writes it in the WrapWithCondition and reads it at RETURN of the same handleMovePlayer call. Two cosmetic deviations, neither a behaviour gap: allowPlayerToBeMovedByEntityPackets uses '== BedrockProtocolVersion.bedrockLatest' where upstream uses .equals() (registry singletons, same result); handleWinGameState0 constructs the WinScreen only in the param==1 branch instead of receiving the already-built screen. Caveat outside this row: the <=v1_19_3 branch of allowPlayerToBeMovedByEntityPackets cannot fire for local-player teleports until the EntityPacketRewriter1_19_4 override (row 4) exists. \| REFUTED: 19 of the 20 injectors plus the @Unique/@Mutable state are faithful - I re-checked every gate

**`features/networking/srv_resolving/MixinConnectScreen_1.java`** — PARTIAL 2/2, 目标 `net.minecraft.client.gui.screens.ConnectScreen$1 (anonymous "Server Connector" Thread in ConnectScreen#startConnecting)`

- `@Redirect getRealAddress - second (un-ordinaled) call site` — For <=1.17 the error-message stripping must use hostAndPort.getHost(); Sigma leaves `address` = resolvedAddress.get() on every throw before line 175, so the resolved (possibly SRV-target) host is stripped instead.
  - 落点: ConnectScreen$1#run catch block, the address.getHostName() read in `cause.getMessage().replaceAll(address.getHostName() + ":" + address.getPort(), "")` - E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/ConnectScreen.java:221
- `@Redirect getRealPort - second (un-ordinaled) call site` — Same site needs hostAndPort.getPort(); with SRV the resolved port differs from the raw port, which is exactly what this redirect exists to undo.
  - 落点: ConnectScreen$1#run catch block, the address.getPort() read at E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/ConnectScreen.java:221
- `(not upstream) unconditional `address` reassignment - over-application, must be reverted` — Line 175 rebuilds `address` for ALL target versions, so >1.17 loses the resolved InetSocketAddress (extra DNS resolve; address.toString() at :221 reports a different endpoint than the one connected to). Fix: delete :175, pass vfp$connectHost/vfp$connectPort directly into initiateServerboundPlayConnection (as 384b94c3 did) and use the same two locals in the catch-block replaceAll, leaving address.toString() on the resolved address.
  - 落点: E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/ConnectScreen.java:175
- 备注: Structurally different route (one local-variable substitution instead of two per-call redirects) but covers both getHostName/getPort read sites inside run(). Two minor deviations, neither behavioural for the handshake: (a) on the >1.17 path the address object is needlessly rebuilt from its own hostname/port after the channel is up (extra DNS attempt, same values); (b) if the connect throws before line 175, the catch-block message stripping at 221 uses the resolved address whereas upstream would use the raw host/port - cosmetic only. \| REFUTED: Both @Redirects are un-ordinaled, so each covers TWO call sites in ConnectScreen$1#run, not one. Pre-port vanilla (git ea431639) has exactly 2 InetSocketAddress#getHostName() and 2 #getPort() calls: the handshake (E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/ConnectScreen.java:188-189) and the exception-message stripping (

**`features/networking/srv_resolving/MixinServerAddress.java`** — PARTIAL 1/1, 目标 `net.minecraft.client.multiplayer.resolver.ServerAddress`

- `@Inject resolveSrv - the `!cir.getReturnValue().equals(INVALID)` guard, plus the vanilla branch it replaced` — Restore `if (result.getHost().isEmpty()) return INVALID;` before the SRV wrap and only wrap when the parsed address is not INVALID, so blank/hostless input still maps to INVALID (all versions) and is not SRV-looked-up on <=1.16.4.
  - 落点: ServerAddress#parseString, the try-block return - E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/resolver/ServerAddress.java:56-63
- 备注: Upstream filters with !returnValue.equals(INVALID); Sigma gets the same effect by only applying the redirect on the success return (the null-input path at 51-53 and the IllegalArgumentException path at 64-66 both return INVALID untouched). Only divergence: a literal "server.invalid[:25565]" input, which upstream would leave unredirected and Sigma would run through the SRV lookup - irrelevant in practice. \| REFUTED: Gate and call body are right (olderThanOrEqualTo(ProtocolVersion.v1_16_4); ServerNameResolver.DEFAULT.redirectHandler.lookupRedirect(addr).orElse(addr); field widened to public at E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/resolver/ServerNameResolver.java:16), and placing it on the success path is equivalent to @At("RETURN") since the two `return INVALID` paths bypass it. But the hook is not faithful: upstream's `!cir.getReturnValue().equals(INVALID

## 库目标 mixin（NOT_APPLICABLE）/ library-target mixins

### 本轮已用公开 API 重建 / rebuilt this round

`ViaFabricPlusProtocolPatches` 目前重建了 5 个：

| 上游 mixin | 重建方式 | commit |
|---|---|---|
| `features/movement/packet/MixinEntityPacketRewriter1_21_2#dontCancelIdlePacket` | `Protocol1_21To1_21_2.appendServerbound(MOVE_PLAYER_STATUS_ONLY, w -> w.setCancelled(false))`；用 javap 确认 `lambda$registerPackets$14` 就是该 handler 且只含一个 `cancel()` | `cb9c21ab` |
| `features/networking/packet_handling/MixinEntityPacketRewriter1_19_4#fixTeleportBehaviour` | `Protocol1_19_3To1_19_4.registerClientbound(TELEPORT_ENTITY, TELEPORT_ENTITY, passthrough, override)` | `cb9c21ab` |
| `features/networking/remove_signed_commands/MixinProtocol1_20_3To1_20_5#removeCommandHandlers` | 5 个 `registerServerbound(..., override=true)` | `a712df3c` |
| `features/networking/remove_signed_commands/MixinProtocol1_21_5To1_21_6#cancelInvalidPackets` | `registerServerbound(CHANGE_GAME_MODE, CHAT_COMMAND, handler, true)` | `a712df3c` |
| `features/networking/config_state/MixinProtocol1_20To1_20_2#dontQueueConfigPackets` | 3 个 `registerServerbound(State.CONFIGURATION, id, -1, handler, true)`；排队分支逐字复现 Via 自己的 `queueServerboundPacket` | `a712df3c` |
| `features/limitation/max_chat_length/MixinProtocol1_10To1_11#changeMaxChatLength` | `registerServerbound(ServerboundPackets1_9_3.CHAT, handler, true)`，把硬编码的 100 换成 `MaxChatLength.getChatLength()` | 本轮 |

### 明确决定不重建的一个 / one deliberately left alone

`features/networking/level_loading/MixinEntityPacketRewriter1_20_3#sendChunksSentGameEvent`（`@Overwrite` 成空方法）。
它的效果是让 Via 不再在 LOGIN / RESPAWN / INITIALIZE_BORDER 时提前 send + cancel，也不再合成
`GAME_EVENT 13`（LEVEL_CHUNKS_LOAD_START，1.20.3 才有的包，≤1.20.2 服务端不可能自己发）。

公开 API 没有干净的等价物：`Protocol` 不暴露已注册 handler 的 getter，重建 LOGIN / RESPAWN 就得逐字段复制
Via 的 `map(...)` 列表，写错一个字段就是协议错位。仅取消合成的 `GAME_EVENT 13` 也不行 —— 它是在
`Protocol1_20_2To1_20_3` **之后**的链路上 `send` 的，要拦就得改后一个 protocol 的 GAME_EVENT handler 并在
appended handler 里重读已消费的读指针，风险高于收益。

**与本轮 `MixinLevelLoadingScreen` 移植的关系**（审计提示过这个顺序有风险）：本轮内联的
`vfpLegacyTick()` 对 ≤1.20.2 目标**整体替换**了 vanilla 的 `tick()`，所以 vanilla 那条
`loadTracker.isLevelReady() -> onClose()` 路径不再执行；合成出来的 `GAME_EVENT 13` 只会更新一个此路径不再读取的
tracker，不会造成二次关屏。两处的组合结果是自洽的，但**这一条属于已知的行为偏差**，记录在此。


这 48 个 mixin 的 `@Mixin` 目标是 jar 依赖里的类，源码树里没有文件可改，所以在“逐文件内联”的意义上是 NOT_APPLICABLE。**但其中一部分仍有真实行为缺口。**凡是能通过公开 API 在 bootstrap 阶段重建的，都在这里注明；本轮已重建两个（见 `com/viaversion/viafabricplus/protocoltranslator/impl/viaversion/ViaFabricPlusProtocolPatches.java`，由 `ViaFabricPlusPlatformLoader#load()` 在 `ProtocolManager#registerProtocols` 之后调用）。

| 上游 mixin | 目标类 | 优先级 | 备注 |
|---|---|---|---|
| `features/classic/world_height/MixinEntityPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.EntityPacketRewriter1_17 (ViaVersion JAR)` | P0 | CAN be reimplemented through the public API with no mixin: both wrappers run the parent handler first and then patch the wrapper, which is exactly Protocol#appendClientbound semantics - at bootstrap call appendClientbound(ClientboundPackets1_16_2.LOGIN, ...) and appendClientbound(ClientboundPackets1_16_2.RESPAWN, ...) on the Protocol1_16_4To1_17 instance, passing WorldHeightSupport's bodies (the v |
| `features/classic/world_height/MixinWorldPacketRewriter1_16_2.java` | `com.viaversion.viaversion.protocols.v1_16_1to1_16_2.rewriter.WorldPacketRewriter1_16_2 (ViaVersion JAR)` | P0 | CAN be reimplemented through the public API: the handler needs nothing from the original, so at bootstrap call Protocol1_16_1To1_16_2#replaceClientbound(ClientboundPackets1_16.CHUNK_BLOCKS_UPDATE, copy) with a re-implementation of that lambda using a 64-length array (it only needs public Types, PacketWrapper#create/send and protocol.getMappingData().getNewBlockStateId). replaceClientbound keeps th |
| `features/classic/world_height/MixinWorldPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.WorldPacketRewriter1_17 (ViaVersion JAR)` | P0 | PARTLY reimplementable through the public API: LEVEL_CHUNK fits Protocol#appendClientbound exactly (parent first, then resetReader + rewrite - the API docs even prescribe that pattern). LIGHT_UPDATE does not, because it must run INSTEAD of Via's handler for classic and fall back to it otherwise, and Protocol exposes no getter for an already-registered handler (only PacketMapping#handler(), unreach |
| `features/interaction/container_clicking/MixinBlockItemPacketRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewriter1_21_5` | P0 | The behaviour gap is real and CAN be reimplemented through public Via API: after Protocol1_21_4To1_21_5 is constructed (e.g. from the ViaFabricPlus load entrypoint / ViaFabricPlusViaVersionPlatform), call protocol.registerServerbound(ServerboundPackets1_21_5.CONTAINER_CLICK, ServerboundPackets1_21_4.CONTAINER_CLICK, wrapper -> { NotificationUtil.warnIncompatibilityPacket(...); wrapper.cancel(); }, |
| `features/interaction/container_clicking/MixinEntityTrackerBase.java` | `com.viaversion.viaversion.data.entity.EntityTrackerBase` | P0 | Real gap. Reimplementable through public API: EntityTracker#setInstaBuild(boolean) is public (viaversion-common api/data/entity/EntityTracker.java:129) and the instaBuild flag is the ONLY thing gating the serverbound creative-slot cancel (StructuredItemRewriter.java:496, ItemRewriter.java:261, BlockItemPacketRewriter1_21_5.java:155 all do `if (!tracker(user).canInstaBuild()) cancel`). So force set |
| `features/interaction/container_clicking/MixinItemPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.ItemPacketRewriter1_17` | P0 | Same real gap as MixinBlockItemPacketRewriter1_21_5 but for the 1.17 path. Reimplementable via public API: protocol.registerServerbound(ServerboundPackets1_17.CONTAINER_CLICK, ServerboundPackets1_16_2.CONTAINER_CLICK, warn+cancel, true) on the loaded Protocol1_16_4To1_17 instance. |
| `features/large_container/MixinItemPacketRewriter1_14.java` | `com.viaversion.viaversion.protocols.v1_13_2to1_14.rewriter.ItemPacketRewriter1_14 (JAR: viaversion-common 5.12.0-SNAPSHOT)` | P0 | Real behaviour gap, not a no-op: without it any >54-slot legacy chest fails to open. The client half is already ported and wired - com/viaversion/viafabricplus/util/network/SyncTasks.java + DataCustomPayload.java exist and net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java:198 calls SyncTasks.handleSyncTask - so only the protocol-side registration is absent. The only Via registra |
| `features/limitation/max_chat_length/MixinProtocol1_10To1_11.java` | `com.viaversion.viaversion.protocols.v1_10to1_11.Protocol1_10To1_11$6 (JAR: viaversion-common; anonymous PacketHandlers for ServerboundPackets1_9_3.CHAT)` | P0 | Real gap for classic/Bedrock targets whose effective chat length exceeds 100: every serverbound chat message passing through the 1.10->1.11 leg is silently cut. MaxChatLength.getChatLength() is already available in-tree. |
| `features/networking/config_state/MixinProtocol1_20To1_20_2.java` | `com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2 (viaversion-common JAR, pom.xml:60-63)` | P0 | Real behaviour gap, not merely a library detail. Upstream: when queueConfigPackets is false, cancel the queueing lambda and re-type the packet to ServerboundPackets1_19_4 CUSTOM_PAYLOAD/KEEP_ALIVE/PONG so it is sent immediately instead of being parked in ConfigurationState until the play state. Reimplementable through public API: at bootstrap fetch the Protocol1_20To1_20_2 instance from Via's Prot |
| `features/networking/legacy_chat_signature/MixinKeyPairResponse.java` | `com.mojang.authlib.yggdrasil.response.KeyPairResponse (authlib 9.0.75 JAR, pom.xml:219-223)` | P0 | Real gap. The behaviour COULD be reimplemented without the JAR because the legacy signature does not have to ride on KeyPairResponse at all: AccountProfileKeyPairManager (a Sigma source file) can fetch it itself and write it straight onto the ProfilePublicKey.Data (see the MixinYggdrasilUserApiService row), which collapses this hook plus the other two unported hooks in the chain into a single edit |
| `features/networking/legacy_chat_signature/MixinYggdrasilUserApiService.java` | `com.mojang.authlib.yggdrasil.YggdrasilUserApiService (authlib 9.0.75 JAR, pom.xml:219-223)` | P0 | Real gap and the root of the broken 1.19.0 chain. Reimplementable without touching the JAR: in AccountProfileKeyPairManager#fetchProfileKeyPair (:120-130) reflectively read YggdrasilUserApiService's private minecraftClient and routeKeyPair fields and re-issue minecraftClient.post(routeKeyPair, KeyPairResponse1_19_0.class) (MinecraftClient#post is public in authlib 9.0.75, MinecraftClient.java:85), |
| `features/networking/level_loading/MixinEntityPacketRewriter1_20_3.java` | `com.viaversion.viaversion.protocols.v1_20_2to1_20_3.rewriter.EntityPacketRewriter1_20_3 (viaversion-common JAR, pom.xml:60-63)` | P0 | Upstream neuters the method so Via stops early-sending LOGIN/RESPAWN/INITIALIZE_BORDER and stops synthesizing GAME_EVENT 13, because VFP closes the terrain screen itself. Since Sigma also skipped MixinLevelLoadingScreen, the synthetic event is still produced and still drives vanilla's tracker (ClientPacketListener.java:1774, LEVEL_CHUNKS_LOAD_START -> LevelLoadTracker.loadingPacketsReceived), so t |
| `features/networking/limitation/nbt/MixinNamedCompoundTagType.java` | `com.viaversion.viaversion.api.type.types.misc.NamedCompoundTagType (viaversion-common JAR, pom.xml:60-63)` | P0 | Real gap: oversized or deeply nested NBT arriving through a translated connection throws during read and takes the connection down, where upstream reads it unbounded. No clean public API for this one — the closest route is a bootstrap-time reflective replacement of the three public static final Types fields with a NamedCompoundTagType subclass whose read(ByteBuf) delegates to NamedCompoundTagType. |
| `features/networking/limitation/nbt/MixinTagType.java` | `com.viaversion.viaversion.api.type.types.misc.TagType (viaversion-common JAR, pom.xml:60-63)` | P0 | Real gap with a partial public-API route already shipped by Via: TagType(false) / Types.TRUSTED_TAG (Types.java:220) is a limit-free-by-bytes variant, so a bootstrap-time reflective repoint of Types.TAG / TAG_ARRAY / OPTIONAL_TAG at new TagType(false) removes the byte cap; the nesting cap (TagLimiter.DEFAULT_MAX_NESTING_LEVEL) would still apply, unlike upstream's TagLimiter.noop(). |
| `features/networking/remove_signed_commands/MixinProtocol1_20_3To1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5 (ViaVersion JAR)` | P0 | COULD be reimplemented through public API: getProtocolManager().getProtocol(Protocol1_20_3To1_20_5.class).registerServerbound(ServerboundPackets1_20_5.X, ServerboundPackets1_20_3.Y, handlerOrNull, true) - registerServerbound(SU,SM,PacketHandler,boolean) is public. Current consequence: chat and commands to <=1.20.3 servers run entirely through VV's standalone path (salt/signature stripping unless s |
| `features/networking/remove_signed_commands/MixinProtocol1_21_5To1_21_6.java` | `com.viaversion.viaversion.protocols.v1_21_5to1_21_6.Protocol1_21_5To1_21_6 (ViaVersion JAR)` | P0 | COULD be reimplemented through public API: getProtocolManager().getProtocol(Protocol1_21_5To1_21_6.class).registerServerbound(ServerboundPackets1_21_6.CHANGE_GAME_MODE, ServerboundPackets1_21_5.CHAT_COMMAND, handler, true). Delta while rows 12/13 are also unported is small - VV's default at Protocol1_21_5To1_21_6.java:105 already emits the same 'gamemode <mode>' command - the missing part is the < |
| `features/entity/attribute/MixinEntityPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.EntityPacketRewriter1_20_5` | P1 | Hook count: 1 @Redirect; the @Shadow abstract writeAttribute is not a hook. Real behaviour gap, not merely inapplicable: legacy reach is wrong without it. COULD be reimplemented through public API at bootstrap - sendRangeAttributes ends with wrapper.scheduleSend(Protocol1_20_3To1_20_5.class), so an appendClientbound handler registered on a LATER protocol in the chain (e.g. Protocol1_20_5To1_21) fo |
| `features/entity/metadata/MixinEntityPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.EntityPacketRewriter1_9 (ViaVersion JAR)` | P1 | Reimplementable at bootstrap: register a clientbound SET_ENTITY_DATA handler in ViaFabricPlusProtocol that drops the using-item/hand-active entry for the local player's entity id when the server is <=1.8; a source-tree alternative is to skip it in ClientPacketListener#handleSetEntityData. Without it, 1.8 servers overwrite the local player's client-side item-use state. |
| `features/interaction/r1_18_2_block_ack_emulation/MixinWorldPacketRewriter1_19.java` | `com.viaversion.viaversion.protocols.v1_18_2to1_19.rewriter.WorldPacketRewriter1_19 (and Protocol1_18_2To1_19)` | P1 | Real gap, and reimplementable through public API: Protocol#registerClientbound(CU, CM, PacketHandler, boolean override) is public (viaversion-common Protocol.java:149), so registering ClientboundPackets1_18.BLOCK_BREAK_ACK -> ClientboundPackets1_19.CUSTOM_PAYLOAD with override=true after Protocol1_18_2To1_19 is loaded replaces Via's cancel. The transport it needs is already ported and wired: SyncT |
| `core/integration/MixinUserConnectionImpl.java` | `com.viaversion.viaversion.connection.UserConnectionImpl (viaversion-common 5.12.0 JAR)` | P2 | The gap is real, not cosmetic. Verified against the viaversion-common 5.12.0 sources: sendRawPacket(buf, currentThread) reaches sendRawPacketNow, and with clientSide=true (ProtocolTranslator.java:243 passes true) that does pipeline.context(Via.getManager().getInjector().getDecoderName()).fireChannelRead(buf); NoPacketSendChannel is a bare LocalChannel with an empty pipeline and the platform uses N |
| `features/entity/metadata/MixinCommonBoss.java` | `com.viaversion.viaversion.legacy.bossbar.CommonBoss (ViaVersion JAR)` | P2 | Pairs with MixinEntityTracker1_9: with neither patch present, a 1.8 server sending NaN wither/dragon health makes Via throw IllegalArgumentException inside packet handling. No Via API disables the assertion, so the only bootstrap-level equivalent is to own the boss-bar health path (see the EntityTracker1_9 row) and hand BossBar#setHealth a pre-normalised value. |
| `features/entity/metadata/MixinEntityTracker1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9 (ViaVersion JAR)` | P2 | That health branch only runs when Via's bossbar-anti-flicker is disabled (its default). Reimplementable at bootstrap only by taking the path over entirely - enable bossbar-anti-flicker so Via skips its clamped update, then track ENDER_DRAGON/WITHER entity-data id 6 yourself and drive the bar via Via.getAPI().legacyAPI(); no config toggle reproduces unclamped health plus NaN->0. |
| `features/item/attack_damage/MixinItemPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.ItemPacketRewriter1_9 (remap=false)` | P2 | REAL BEHAVIOUR GAP, not merely inapplicable: on 1.8 servers, vanilla-1.8 item attribute modifiers (sword/tool damage, armour values) are never synthesised, so damage numbers and tooltips are wrong. The mapping JSONs are present (src/main/resources/assets/viafabricplus/data/item-attributes-1.8.json, item-identifiers-1.8.json) but no Java code loads them - `grep -rn "attributeFix\|item-attributes-1. |
| `features/item/data_fix/MixinBlockItemPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.BlockItemPacketRewriter1_20_5 (remap=false)` | P2 | REAL BEHAVIOUR GAP: this is how VFP emulates legacy item behaviour without touching game code - per-version TOOL components (mining speeds, suitable-for blocks, damage-per-block), b1.8.1 armour MAX_DAMAGE, and b1.7.3 food items forced to MAX_STACK_SIZE 1 with an empty FOOD component. Without it, block-breaking speed and tool damage are wrong on every pre-1.20.5 target, not just betas. item-tool-co |
| `features/item/sword_blocking/MixinBlockItemPacketRewriter1_21_X.java` | `com.viaversion.viaversion.protocols.v1_21_2to1_21_4.rewriter.BlockItemPacketRewriter1_21_4, com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewriter1_21_5` | P2 | Real gap, library target. Reimplementable through public API: since the redirect only narrows the range, append a clientbound item handler in E:/.sigma/Sigma-Modern/src/main/java/com/viaversion/viafabricplus/protocoltranslator/protocol/ViaFabricPlusProtocol.java (appendClientbound on the inventory/set-slot/equipment packets, or a per-item hook) that removes StructuredDataKey.CONSUMABLE1_21_2 / Str |
| `features/recipe/MixinEntityPacketRewriter1_12.java` | `com.viaversion.viaversion.protocols.v1_11_1to1_12.rewriter.EntityPacketRewriter1_12$1 (anonymous PacketHandlers for the JOIN_GAME registration)` | P2 | Real behaviour gap, not a non-issue. Could be reimplemented through a public API: register a clientbound handler for UPDATE_RECIPES in ViaFabricPlusProtocol (src/main/java/com/viaversion/viafabricplus/protocoltranslator/protocol/ViaFabricPlusProtocol.java#registerPackets, which already registers packet handlers this way) that cancels an empty recipe list when ProtocolTranslator's target is <=1.11. |
| `compat/classic4j/MixinCCAuthenticationResponse.java` | `de.florianreuth.classic4j.model.classicube.CCAuthenticationResponse` | P3 | Real but cosmetic gap: ClassiCube login/MFA errors surface classic4j's raw English CCError.description instead of the translated strings; the lang keys are still shipped and ClassiCubeMFAScreen.java:49 already hardcodes one of them. Reimplementable without touching the lib: map the CCError enum (or its five raw description strings) to Component.translatable in ClassiCubeLoginScreen's LoginProcessH |
| `compat/fabricapi/MixinClientRegistrySyncHandler.java` | `net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` | P3 | No behaviour gap: the code path being suppressed (Fabric API's registry-sync remap error logging during login) does not exist in Sigma at all, matching VFP_PORTING.md's "registry sync 三件套无需 shim". The setting it reads, DebugSettings.INSTANCE.ignoreFabricSyncErrors (src/main/java/com/viaversion/viafabricplus/settings/impl/DebugSettings.java:39), is therefore dead and could be hidden from the settin |
| `compat/ipnext/MixinAutoRefillHandler_ItemSlotMonitor.java` | `org.anti_ad.mc.ipnext.event.AutoRefillHandler$ItemSlotMonitor (@Pseudo, targets=)` | P3 | No behaviour gap: IPN cannot be loaded into a loader-less MCP tree, so there is no offhand-slot (slot 45) handling to cancel for <=1.8 targets. The @Pseudo guard in ViaFabricPlusMixinPlugin is now dead and can be dropped. |
| `compat/mcstructs/MixinTextComponentSerializer.java` | `com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer` | P3 | No behaviour gap against the pinned dependency version: the upstream fix has landed in viaversion-common 5.12.0, so the @Overwrite is a no-op there. If the dep is ever downgraded the only route back would be reflection (net.lenni0451:Reflect is already a dependency, pom.xml:139-143) to swap the public static final V1_6..LATEST instances for a subclass overriding deserialize; VFP-in-Sigma never cal |
| `compat/minecraftauth/MixinClasses.java` | `io.jsonwebtoken.lang.Classes` | P3 | No behaviour gap: the @Overwrite exists purely because the Fabric loader's classloader isolation breaks jjwt's service lookup. Sigma launches from a plain classpath, so Classes.forName's normal thread-context-classloader chain resolves. Would only need revisiting if the game is ever repackaged behind a custom/shaded classloader; the reimplementation route would then be reflection, since the class  |
| `compat/minecraftauth/MixinDefaultJwtParserBuilder.java` | `io.jsonwebtoken.impl.DefaultJwtParserBuilder` | P3 | No behaviour gap for the same reason as MixinClasses - the redirect substitutes `new GsonDeserializer<>()` only because Fabric's loader breaks ServiceLoader. If it ever became necessary, the public route is to build the parser via Jwts.parser().json(new GsonDeserializer<>()) at VFP's own call sites rather than patching the builder; Sigma has no such call sites today. |
| `core/access/MixinChunkTracker.java` | `net.raphimc.viabedrock.protocol.storage.ChunkTracker` | P3 | Latent, not live: the cast (IChunkTracker) chunkTracker at VFPDebugHudEntry.java:79 would throw ClassCastException, but VFPDebugHudEntry is never registered in Sigma (repo-wide grep finds only its own declaration; upstream registers it in core/integration/MixinDebugScreenEntries.java:55, which is not yet ported), so the line is currently unreachable. Reimplementable through the public API only by  |
| `core/access/MixinExtensionProtocolMetadataStorage.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.storage.ExtensionProtocolMetadataStorage` | P3 | Live crash path, unlike the two Bedrock accessors: ListExtensionsCommand IS registered (src/main/java/com/viaversion/viafabricplus/protocoltranslator/impl/command/ViaFabricPlusCommandHandler.java:43), so running the subcommand on a c0.30-CPE server throws ClassCastException at ListExtensionsCommand.java:49. Reimplementable without the mixin: read the private serverExtensions field reflectively (ne |
| `core/access/MixinRakSessionCodec.java` | `org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec` | P3 | Latent like MixinChunkTracker: the cast at VFPDebugHudEntry.java:88 would CCE, but VFPDebugHudEntry is not registered anywhere in Sigma yet. Reimplementable only by reflecting the two private fields (net.lenni0451:Reflect already on the classpath); rakSessionCodec.getRTT()/getPing() used on the same line are public and unaffected. |
| `core/integration/MixinViaBedrockConfig.java` | `net.raphimc.viabedrock.ViaBedrockConfig (ViaBedrock 0.0.29 JAR)` | P3 | Reimplementable through public API: ViaBedrockPlatform#init(File) is a default method that the ViaBedrockPlatformImpl constructor calls virtually, so a small VFP subclass overriding init(File) to call init(new ViaBedrockConfig(file, getLogger()) { @Override shouldEnableExperimentalFeatures() -> BedrockSettings... }) reproduces the overwrite exactly. Until then Bedrock experimental features are off |
| `core/integration/MixinViaLegacyConfig.java` | `net.raphimc.vialegacy.ViaLegacyConfig (ViaLegacy 3.1.0 JAR)` | P3 | Verified in the ViaLegacy 3.1.0 sources that ViaLegacyConfig#loadFields defaults legacy-skull-loading and legacy-skin-loading to false, so today skins and skulls do not load on legacy servers regardless of the GUI setting. Reimplementable through public API: ViaLegacyPlatform#init(File) is a default method called virtually from the ViaLegacyPlatformImpl constructor, so ViaFabricPlusViaLegacyPlatfo |
| `features/classic/cpe_extension/MixinClassicProtocolExtension.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolExtension (ViaLegacy JAR enum)` | P3 | CAN be reimplemented through a public API with no mixin: getSupportedVersions() hands out the live backing IntSet, so `ClassicProtocolExtension.ENV_WEATHER_TYPE.getSupportedVersions().add(1)` at bootstrap makes all three methods behave exactly as the mixin does. Currently inert rather than broken - because the extension is never advertised, classic servers never send the ext packet, so there is no |
| `features/classic/cpe_extension/MixinClientboundPacketsc0_30cpe.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.packet.ClientboundPacketsc0_30cpe (ViaLegacy JAR enum)` | P3 | No public API can do this - REGISTRY is a private static field with no setter. Loaderless route is reflection: after CPEAdditions.createNewPacket, write the new constant into REGISTRY[packetId] with net.lenni0451.reflect (already a dependency, already used for Enums.newInstance/addEnumInstance in that same method). Must land together with the extension being advertised, otherwise the splitter reso |
| `features/classic/cpe_extension/MixinProtocolc0_30cpeToc0_28_30.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.Protocolc0_30cpeToc0_28_30 (ViaLegacy JAR protocol)` | P3 | The packet registration CAN be done through the public API: at bootstrap take the protocol instance from Via.getManager().getProtocolManager().getProtocol(Protocolc0_30cpeToc0_28_30.class) and call the public Protocol#registerClientbound(EXT_WEATHER_TYPE, null, handlers). The per-connection reset has no API hook (Protocol#init is a default method) but is equivalent to calling CPEAdditions.setSnowi |
| `features/entity/metadata/MixinEntityPacketRewriter1_15.java` | `com.viaversion.viaversion.protocols.v1_14_4to1_15.rewriter.EntityPacketRewriter1_15 (ViaVersion JAR)` | P3 | WolfHealthTracker1_14_4 exists and is registered on the connection (src/main/java/com/viaversion/viafabricplus/protocoltranslator/protocol/ViaFabricPlusProtocol.java:121) but nothing ever calls setWolfHealth, so the map stays empty. Reimplementable at bootstrap only by re-registering Protocol1_14_4To1_15's clientbound SET_ENTITY_DATA handler so the wolf-health entry is snapshotted before Via's rem |
| `features/item/tooltip/MixinComponentRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.ComponentRewriter1_21_5` | P3 | Real gap, library target. Its consumer, features/item/tooltip/MixinItemStack#hideAdditionalTooltip (separate unit), is also not ported: grep over E:/.sigma/Sigma-Modern/src/main/java/net for "hide_additional_tooltip" only hits datafixers, and ItemUtil.vvNbtName(Class,String) in com/viaversion/viafabricplus/util/ItemUtil.java has no callers. Public-API route is poor: ViaVersion exposes no provider  |
| `features/networking/packet_handling/MixinProtocol1_21_7To1_21_9.java` | `com.viaversion.viaversion.protocols.v1_21_7to1_21_9.Protocol1_21_7To1_21_9 (ViaVersion JAR)` | P3 | COULD be reimplemented through public API: getProtocolManager().getProtocol(Protocol1_21_7To1_21_9.class).registerClientbound(ClientboundPackets1_21_6.CUSTOM_PAYLOAD, handler). Both prerequisites already exist in Sigma - SyncTasks/DataCustomPayload (com/viaversion/viafabricplus/util/network/) with the sync-task dispatch already inlined at ClientCommonPacketListenerImpl.java:196, and LevelExtractor |
| `features/scoreboard/MixinComponentUtil.java` | `com.viaversion.viaversion.util.ComponentUtil (methods legacyToJson and legacyToJsonString(String,boolean))` | P3 | Real gap, and there is no ViaVersion API that swaps ComponentUtil (static utility used by every legacy protocol). The only public-API route would be re-registering the affected clientbound handlers - e.g. the team/objective packets in Protocol1_12_2To1_13 - from the ViaFabricPlusProtocol bootstrap so they do the legacy->JSON conversion themselves with StringFormat.vanilla().fromString(s, RESET, WH |
| `features/world/footstep_particle/MixinMappingDataBase.java` | `com.viaversion.viaversion.api.data.MappingDataBase (viaversion-common JAR, 5.12.0-20260819.184210-4)` | P3 | Real behaviour gap, recorded below. In the JAR, MappingDataBase#getNewParticleId is `return checkValidity(id, particleMappings.getNewId(id), "particles")`, so VFP's HEAD pass-through exists to stop checkValidity from rejecting/logging the out-of-range viafabricplus:footstep raw id. There is no public Via API to substitute a protocol's MappingData or intercept getNewParticleId; the whole footstep p |
| `features/world/footstep_particle/MixinParticleIdMappings1_13.java` | `com.viaversion.viaversion.protocols.v1_12_2to1_13.data.ParticleIdMappings1_13 (viaversion-common JAR)` | P3 | hookTotal=2: the static-init @Inject("<clinit>", RETURN) overlap assertion and the @ModifyArg on add(I)V. The @Shadow @Final `particles` field is a shadow, not a hook, so it is not counted. Real behaviour gap, recorded below. In the JAR the 1.12.2 footstep entry is `add(-1); // (28->-1) footstep -> REMOVED` and `particles` is a private static final List<NewParticle> whose element type NewParticle  |
| `features/world/footstep_particle/MixinParticleMappings.java` | `com.viaversion.viaversion.api.data.ParticleMappings (viaversion-common JAR; mixin extends FullMappingsBase)` | P3 | hookTotal=2: two overwrite-style overrides injected into the target (getNewId(int) and mappedIdentifier(int)); the constructor is scaffolding. Both target methods are inherited, not declared, by ParticleMappings - getNewId comes from FullMappingsBase (`return mappings.getNewId(id)`) and mappedIdentifier(int) is a default on the FullMappings interface. Real behaviour gap, recorded below. No public  |
| `features/world/footstep_particle/MixinRegistrySyncManager.java` | `net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager (Fabric API impl class)` | P3 | This is the only genuinely empty gap in the unit: the hook exists purely to hide the runtime-registered viafabricplus:footstep entry from Fabric's registry-sync payload/hash, and Sigma has no Fabric loader and no registry sync, so nothing needs skipping. Upstream itself marks it require = 0 (tolerated absence). Sigma's equivalent concern - the custom particle being inserted into a frozen vanilla r |

## 全量账本 / full ledger (368)

`Sigma 位置` 为空表示该行为在源码树里还没有落点。`本轮` 列给出本次会话修复它的 commit。

### `compat/classic4j` — 0/5 hook, MISSING 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinCCAuthenticationResponse.java` | `de.florianreuth.classic4j.model.classicube.CCAuthenticationResponse` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinEditBox.java` | `net.minecraft.client.gui.components.EditBox` | 4 | 0 | MISSING | P3 | — | — |

### `compat/fabricapi` — 0/1 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientRegistrySyncHandler.java` | `net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `compat/ipnext` — 0/2 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAutoRefillHandler_ItemSlotMonitor.java` | `org.anti_ad.mc.ipnext.event.AutoRefillHandler$ItemSlotMonitor (@Pseudo, targets=)` | 2 | 0 | NOT_APPLICABLE | P3 | — | — |

### `compat/lithium` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity (priority 1001, applied after Lithium)` | 1 | 0 | MISSING | P1 | — | — |

### `compat/mcstructs` — 0/1 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinTextComponentSerializer.java` | `com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `compat/minecraftauth` — 0/2 hook, NOT_APPLICABLE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClasses.java` | `io.jsonwebtoken.lang.Classes` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinDefaultJwtParserBuilder.java` | `io.jsonwebtoken.impl.DefaultJwtParserBuilder` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `core` — 2/2 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMain.java` | `net.minecraft.client.main.Main` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/main/Main.java` | — |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/Minecraft.java` | — |

### `core/access` — 2/5 hook, COMPLETE 1, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinChunkTracker.java` | `net.raphimc.viabedrock.protocol.storage.ChunkTracker` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinExtensionProtocolMetadataStorage.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.storage.ExtensionProtocolMetadataStorage` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinLocalSampleLogger.java` | `net.minecraft.util.debugchart.LocalSampleLogger` | 2 | 2 | COMPLETE | P0 | `net/minecraft/util/debugchart/LocalSampleLogger.java` | — |
| `MixinRakSessionCodec.java` | `org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `core/connection` — 13/13 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientHandshakePacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java` | — |
| `MixinConnection.java` | `net.minecraft.network.Connection` | 9 | 9 | COMPLETE | P0 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/network/Connection.java` | — |
| `MixinConnection_1.java` | `net.minecraft.network.Connection$1 (anonymous ChannelInitializer inside Connection#connect)` | 1 | 1 | COMPLETE | P0 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/network/Connection.java` | — |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/Minecraft.java` | — |
| `MixinServerStatusPinger.java` | `net.minecraft.client.multiplayer.ServerStatusPinger` | 1 | 1 | COMPLETE | P0 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/ServerStatusPinger.java` | — |

### `core/connection/bedrock` — 4/13 hook, COMPLETE 1, MISSING 3, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the "Server Connector" Thread in ConnectScreen#connect)` | 2 | 0 | MISSING | P0 | — | — |
| `MixinConnection.java` | `net.minecraft.network.Connection (mixin priority 1001, applied after core/connection/MixinConnection)` | 5 | 0 | MISSING | P0 | — | — |
| `MixinEventLoopGroupHolder.java` | `net.minecraft.server.network.EventLoopGroupHolder` | 3 | 2 | PARTIAL | P0 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/server/network/EventLoopGroupHolder.java` | — |
| `MixinServerAddress.java` | `net.minecraft.client.multiplayer.resolver.ServerAddress` | 2 | 2 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/resolver/ServerAddress.java` | — |
| `MixinServerNameResolver.java` | `net.minecraft.client.multiplayer.resolver.ServerNameResolver` | 1 | 0 | MISSING | P3 | — | — |

### `core/gui` — 8/8 hook, COMPLETE 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinDirectJoinServerScreen.java` | `net.minecraft.client.gui.screens.DirectJoinServerScreen` | 1 | 1 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/DirectJoinServerScreen.java` | — |
| `MixinJoinMultiplayerScreen.java` | `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen` | 2 | 2 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.java` | — |
| `MixinLevelLoadingScreen.java` | `net.minecraft.client.gui.screens.LevelLoadingScreen` | 1 | 1 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/LevelLoadingScreen.java` | — |
| `MixinManageServerScreen.java` | `net.minecraft.client.gui.screens.ManageServerScreen` | 3 | 3 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/ManageServerScreen.java` | — |
| `MixinServerSelectionList_OnlineServerEntry.java` | `net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry` | 1 | 1 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/gui/screens/multiplayer/ServerSelectionList.java` | — |

### `core/integration` — 10/22 hook, COMPLETE 3, MISSING 4, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/client/multiplayer/ClientPacketListener.java` | — |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread created in ConnectScreen#connect)` | 4 | 0 | MISSING | P0 | — | — |
| `MixinConnection.java` | `net.minecraft.network.Connection` | 2 | 0 | MISSING | P3 | — | — |
| `MixinDebugScreenEntries.java` | `net.minecraft.client.gui.components.debug.DebugScreenEntries` | 1 | 0 | MISSING | P3 | — | — |
| `MixinJoinMultiplayerScreen.java` | `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.java` | — |
| `MixinServerData.java` | `net.minecraft.client.multiplayer.ServerData` | 7 | 7 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ServerData.java` | — |
| `MixinServerStatusPinger_1.java` | `net.minecraft.client.multiplayer.ServerStatusPinger$1 (the anonymous ClientStatusPacketListener built in ServerStatusPinger#pingServer)` | 2 | 0 | MISSING | P3 | — | — |
| `MixinUserConnectionImpl.java` | `com.viaversion.viaversion.connection.UserConnectionImpl (viaversion-common 5.12.0 JAR)` | 1 | 0 | NOT_APPLICABLE | P2 | — | — |
| `MixinViaBedrockConfig.java` | `net.raphimc.viabedrock.ViaBedrockConfig (ViaBedrock 0.0.29 JAR)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinViaLegacyConfig.java` | `net.raphimc.vialegacy.ViaLegacyConfig (ViaLegacy 3.1.0 JAR)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `core/integration/bedrock` — 0/3 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinConfirmScreen.java` | `net.minecraft.client.gui.screens.ConfirmScreen` | 1 | 0 | MISSING | P3 | — | — |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread created in ConnectScreen#connect)` | 1 | 0 | MISSING | P3 | — | — |
| `MixinServerStatusPinger.java` | `net.minecraft.client.multiplayer.ServerStatusPinger` | 1 | 0 | MISSING | P3 | — | — |

### `core/integration/event` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/Minecraft.java` | — |

### `core/integration/sync_tasks` — 1/1 hook, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientCommonPacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl` | 1 | 1 | PARTIAL | P0 | `net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java` | — |

### `features/april_fools_8bit_sound` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinSoundBuffer.java` | `com.mojang.blaze3d.audio.SoundBuffer` | 1 | 0 | MISSING | P3 | — | — |

### `features/bedrock/allow_new_line` — 0/0 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinFont.java` | `net.minecraft.client.gui.Font` | 0 | 0 | COMPLETE | P3 | — | — |

### `features/bedrock/block` — 0/21 hook, MISSING 12

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBambooStalkBlock.java` | `net.minecraft.world.level.block.BambooStalkBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinBlockBehaviour_Properties.java` | `net.minecraft.world.level.block.state.BlockBehaviour$Properties` | 1 | 0 | MISSING | P2 | — | — |
| `MixinCactusBlock.java` | `net.minecraft.world.level.block.CactusBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinCandleCakeBlock.java` | `net.minecraft.world.level.block.CandleCakeBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinConduitBlock.java` | `net.minecraft.world.level.block.ConduitBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinDoorBlock.java` | `net.minecraft.world.level.block.DoorBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinDragonEggBlock.java` | `net.minecraft.world.level.block.DragonEggBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinHoneyBlock.java` | `net.minecraft.world.level.block.HoneyBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinLanternBlock.java` | `net.minecraft.world.level.block.LanternBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinLecternBlock.java` | `net.minecraft.world.level.block.LecternBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinSeaPickleBlock.java` | `net.minecraft.world.level.block.SeaPickleBlock` | 3 | 0 | MISSING | P2 | — | — |
| `MixinTrapDoorBlock.java` | `net.minecraft.world.level.block.TrapDoorBlock` | 2 | 0 | MISSING | P2 | — | — |

### `features/bedrock/chat` — 0/2 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientSuggestionProvider.java` | `net.minecraft.client.multiplayer.ClientSuggestionProvider` | 2 | 0 | MISSING | P3 | — | — |

### `features/bedrock/inventory` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinInventoryScreen.java` | `net.minecraft.client.gui.screens.inventory.InventoryScreen` | 1 | 0 | MISSING | P0 | — | — |

### `features/bedrock/movement` — 0/19 hook, MISSING 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 0 | MISSING | P0 | — | — |
| `MixinHoneyBlock.java` | `net.minecraft.world.level.block.HoneyBlock` | 7 | 0 | MISSING | P1 | — | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 2 | 0 | MISSING | P1 | — | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 6 | 0 | MISSING | P1 | — | — |

### `features/bedrock/networking` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinServerNameResolver.java` | `net.minecraft.client.multiplayer.resolver.ServerNameResolver` | 1 | 0 | MISSING | P0 | — | — |

### `features/bedrock/reach_around_raycast` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 0 | MISSING | P1 | — | — |

### `features/block/connections` — 0/4 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientChunkCache.java` | `net.minecraft.client.multiplayer.ClientPacketListener (class name is misleading - the @Mixin target is the packet listener, not ClientChunkCache)` | 4 | 0 | MISSING | P2 | — | — |

### `features/block/interaction` — 1/16 hook, COMPLETE 1, MISSING 10

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinCanPlaceAt1_14.java` | `net.minecraft.world.level.block.BaseTorchBlock, net.minecraft.world.level.block.LadderBlock, net.minecraft.world.level.block.TripWireHookBlock` | 3 | 0 | MISSING | P2 | — | — |
| `MixinDecoratedPotBlock.java` | `net.minecraft.world.level.block.DecoratedPotBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinFenceBlock.java` | `net.minecraft.world.level.block.FenceBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinFlowerPotBlock.java` | `net.minecraft.world.level.block.FlowerPotBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinIronBarsBlock.java` | `net.minecraft.world.level.block.IronBarsBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinNoteBlock.java` | `net.minecraft.world.level.block.NoteBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinRedStoneWireBlock.java` | `net.minecraft.world.level.block.RedStoneWireBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/RedStoneWireBlock.java` | — |
| `MixinRespawnAnchorBlock.java` | `net.minecraft.world.level.block.RespawnAnchorBlock` | 1 | 0 | MISSING | P3 | — | — |
| `MixinShelfBlock.java` | `net.minecraft.world.level.block.ShelfBlock` | 1 | 0 | MISSING | P3 | — | — |
| `MixinSignBlock.java` | `net.minecraft.world.level.block.SignBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinSimpleWaterloggedBlock.java` | `net.minecraft.world.level.block.SimpleWaterloggedBlock (interface, default methods)` | 2 | 0 | MISSING | P2 | — | — |

### `features/block/mining_calculation` — 0/5 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBlockBehaviour.java` | `net.minecraft.world.level.block.state.BlockBehaviour` | 1 | 0 | MISSING | P1 | — | — |
| `MixinBlockBehaviour_BlockStateBase.java` | `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase` | 2 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 0 | MISSING | P1 | — | — |

### `features/block/shape` — 0/67 hook, MISSING 30

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractCauldronBlock.java` | `net.minecraft.world.level.block.AbstractCauldronBlock` | 4 | 0 | MISSING | P2 | — | — |
| `MixinAnvilBlock.java` | `net.minecraft.world.level.block.AnvilBlock` | 3 | 0 | MISSING | P2 | — | — |
| `MixinBaseRailBlock.java` | `net.minecraft.world.level.block.BaseRailBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinBedBlock.java` | `net.minecraft.world.level.block.BedBlock` | 3 | 0 | MISSING | P2 | — | — |
| `MixinBrewingStandBlock.java` | `net.minecraft.world.level.block.BrewingStandBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinCarpetBlock.java` | `net.minecraft.world.level.block.CarpetBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinChestBlock.java` | `net.minecraft.world.level.block.ChestBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinCropBlocks.java` | `net.minecraft.world.level.block.CropBlock, net.minecraft.world.level.block.CarrotBlock, net.minecraft.world.level.block.PotatoBlock` | 1 | 0 | MISSING | P3 | — | — |
| `MixinCrossCollisionBlock.java` | `net.minecraft.world.level.block.CrossCollisionBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinEndPortalBlock.java` | `net.minecraft.world.level.block.EndPortalBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinEndPortalFrameBlock.java` | `net.minecraft.world.level.block.EndPortalFrameBlock` | 3 | 0 | MISSING | P2 | — | — |
| `MixinEnderChestBlock.java` | `net.minecraft.world.level.block.EnderChestBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinFarmlandBlock.java` | `net.minecraft.world.level.block.FarmlandBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinFenceBlock.java` | `net.minecraft.world.level.block.FenceBlock` | 4 | 0 | MISSING | P2 | — | — |
| `MixinFenceGateBlock.java` | `net.minecraft.world.level.block.FenceGateBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinFireBlock.java` | `net.minecraft.world.level.block.FireBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinFlowerBedBlock.java` | `net.minecraft.world.level.block.FlowerBedBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinHopperBlock.java` | `net.minecraft.world.level.block.HopperBlock` | 4 | 0 | MISSING | P2 | — | — |
| `MixinIronBarsBlock.java` | `net.minecraft.world.level.block.IronBarsBlock` | 4 | 0 | MISSING | P2 | — | — |
| `MixinLadderBlock.java` | `net.minecraft.world.level.block.LadderBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinLeavesBlock.java` | `net.minecraft.world.level.block.LeavesBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinLilyPadBlock.java` | `net.minecraft.world.level.block.LilyPadBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinPistonBaseBlock.java` | `net.minecraft.world.level.block.piston.PistonBaseBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinPistonHeadBlock.java` | `net.minecraft.world.level.block.piston.PistonHeadBlock` | 3 | 0 | MISSING | P2 | — | — |
| `MixinPitcherCropBlock.java` | `net.minecraft.world.level.block.PitcherCropBlock` | 2 | 0 | MISSING | P2 | — | — |
| `MixinRedStoneWireBlock.java` | `net.minecraft.world.level.block.RedStoneWireBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinSnowLayerBlock.java` | `net.minecraft.world.level.block.SnowLayerBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinSoulSandBlock.java` | `net.minecraft.world.level.block.SoulSandBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinTransparentBlock.java` | `net.minecraft.world.level.block.TransparentBlock` | 1 | 0 | MISSING | P2 | — | — |
| `MixinWallBlock.java` | `net.minecraft.world.level.block.WallBlock` | 10 | 0 | MISSING | P2 | — | — |

### `features/classic/cpe_extension` — 0/7 hook, MISSING 1, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClassicProtocolExtension.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.data.ClassicProtocolExtension (ViaLegacy JAR enum)` | 3 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 0 | MISSING | P3 | — | — |
| `MixinClientboundPacketsc0_30cpe.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.packet.ClientboundPacketsc0_30cpe (ViaLegacy JAR enum)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinProtocolc0_30cpeToc0_28_30.java` | `net.raphimc.vialegacy.protocol.classic.c0_30cpetoc0_28_30.Protocolc0_30cpeToc0_28_30 (ViaLegacy JAR protocol)` | 2 | 0 | NOT_APPLICABLE | P3 | — | — |

### `features/classic/world_height` — 0/3 hook, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.EntityPacketRewriter1_17 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinWorldPacketRewriter1_16_2.java` | `com.viaversion.viaversion.protocols.v1_16_1to1_16_2.rewriter.WorldPacketRewriter1_16_2 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinWorldPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.WorldPacketRewriter1_17 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |

### `features/entity/allow_duplicated_uuid` — 0/2 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntityLookup.java` | `net.minecraft.world.level.entity.EntityLookup` | 2 | 0 | MISSING | P1 | — | — |

### `features/entity/attribute` — 0/2 hook, MISSING 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.EntityPacketRewriter1_20_5` | 1 | 0 | NOT_APPLICABLE | P1 | — | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 0 | MISSING | P1 | — | — |

### `features/entity/dimensions` — 19/52 hook, COMPLETE 15, MISSING 20, PARTIAL 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 1 | 0 | MISSING | P1 | — | — |
| `MixinAbstractChestedHorse.java` | `net.minecraft.world.entity.animal.equine.AbstractChestedHorse` | 3 | 0 | MISSING | P1 | — | — |
| `MixinArmadillo.java` | `net.minecraft.world.entity.animal.armadillo.Armadillo` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/armadillo/Armadillo.java` | — |
| `MixinAxolotl.java` | `net.minecraft.world.entity.animal.axolotl.Axolotl` | 1 | 0 | MISSING | P1 | — | — |
| `MixinCamel.java` | `net.minecraft.world.entity.animal.camel.Camel` | 6 | 0 | MISSING | P1 | — | — |
| `MixinCamelHusk.java` | `net.minecraft.world.entity.animal.camel.CamelHusk` | 1 | 0 | MISSING | P1 | — | — |
| `MixinCat.java` | `net.minecraft.world.entity.animal.feline.Cat` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/feline/Cat.java` | — |
| `MixinChicken.java` | `net.minecraft.world.entity.animal.chicken.Chicken` | 1 | 0 | MISSING | P1 | — | — |
| `MixinCow.java` | `net.minecraft.world.entity.animal.cow.Cow` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/Cow.java` | — |
| `MixinDolphin.java` | `net.minecraft.world.entity.animal.dolphin.Dolphin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/dolphin/Dolphin.java` | — |
| `MixinDrowned.java` | `net.minecraft.world.entity.monster.zombie.Drowned` | 2 | 1 | PARTIAL | P1 | `net/minecraft/world/entity/monster/zombie/Drowned.java` | — |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 0 | MISSING | P1 | — | — |
| `MixinFox.java` | `net.minecraft.world.entity.animal.fox.Fox` | 1 | 0 | MISSING | P1 | — | — |
| `MixinGoat.java` | `net.minecraft.world.entity.animal.goat.Goat` | 2 | 0 | MISSING | P1 | — | — |
| `MixinHappyGhast.java` | `net.minecraft.world.entity.animal.happyghast.HappyGhast` | 1 | 0 | MISSING | P1 | — | — |
| `MixinHoglin.java` | `net.minecraft.world.entity.monster.hoglin.Hoglin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/hoglin/Hoglin.java` | — |
| `MixinHorse.java` | `net.minecraft.world.entity.animal.equine.Horse` | 1 | 0 | MISSING | P1 | — | — |
| `MixinHusk.java` | `net.minecraft.world.entity.monster.zombie.Husk` | 2 | 1 | PARTIAL | P1 | `net/minecraft/world/entity/monster/zombie/Husk.java` | — |
| `MixinItemFrame.java` | `net.minecraft.world.entity.decoration.ItemFrame` | 1 | 0 | MISSING | P1 | — | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 0 | MISSING | P1 | — | — |
| `MixinMushroomCow.java` | `net.minecraft.world.entity.animal.cow.MushroomCow` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/MushroomCow.java` | — |
| `MixinNautilus.java` | `net.minecraft.world.entity.animal.nautilus.Nautilus` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/nautilus/Nautilus.java` | — |
| `MixinOcelot.java` | `net.minecraft.world.entity.animal.feline.Ocelot` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/feline/Ocelot.java` | — |
| `MixinPanda.java` | `net.minecraft.world.entity.animal.panda.Panda` | 1 | 0 | MISSING | P1 | — | — |
| `MixinPig.java` | `net.minecraft.world.entity.animal.pig.Pig` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/pig/Pig.java` | — |
| `MixinPiglin.java` | `net.minecraft.world.entity.monster.piglin.Piglin` | 1 | 0 | MISSING | P1 | — | — |
| `MixinPolarBear.java` | `net.minecraft.world.entity.animal.polarbear.PolarBear` | 1 | 0 | MISSING | P1 | — | — |
| `MixinRabbit.java` | `net.minecraft.world.entity.animal.rabbit.Rabbit` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/rabbit/Rabbit.java` | — |
| `MixinSheep.java` | `net.minecraft.world.entity.animal.sheep.Sheep` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/sheep/Sheep.java` | — |
| `MixinShulker.java` | `net.minecraft.world.entity.monster.Shulker` | 1 | 0 | MISSING | P1 | — | — |
| `MixinSkeletonHorse.java` | `net.minecraft.world.entity.animal.equine.SkeletonHorse` | 1 | 0 | MISSING | P1 | — | — |
| `MixinSquid.java` | `net.minecraft.world.entity.animal.squid.Squid` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/squid/Squid.java` | — |
| `MixinStrider.java` | `net.minecraft.world.entity.monster.Strider` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/Strider.java` | — |
| `MixinVillager.java` | `net.minecraft.world.entity.npc.villager.Villager` | 2 | 1 | PARTIAL | P1 | `net/minecraft/world/entity/npc/villager/Villager.java` | — |
| `MixinWolf.java` | `net.minecraft.world.entity.animal.wolf.Wolf` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/wolf/Wolf.java` | — |
| `MixinZoglin.java` | `net.minecraft.world.entity.monster.Zoglin` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/monster/Zoglin.java` | — |
| `MixinZombie.java` | `net.minecraft.world.entity.monster.zombie.Zombie` | 1 | 0 | MISSING | P1 | — | — |
| `MixinZombieVillager.java` | `net.minecraft.world.entity.monster.zombie.ZombieVillager` | 2 | 1 | PARTIAL | P1 | `net/minecraft/world/entity/monster/zombie/ZombieVillager.java` | — |
| `MixinZombifiedPiglin.java` | `net.minecraft.world.entity.monster.zombie.ZombifiedPiglin` | 1 | 0 | MISSING | P1 | — | — |

### `features/entity/interaction` — 9/22 hook, COMPLETE 7, MISSING 9, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 1 | 0 | MISSING | P1 | — | — |
| `MixinAbstractChestBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractChestBoat` | 1 | 0 | MISSING | P1 | — | — |
| `MixinAbstractCow.java` | `net.minecraft.world.entity.animal.cow.AbstractCow` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/cow/AbstractCow.java` | — |
| `MixinAbstractHorse.java` | `net.minecraft.world.entity.animal.equine.AbstractHorse` | 1 | 0 | MISSING | P1 | — | — |
| `MixinAnimal.java` | `net.minecraft.world.entity.animal.Animal` | 2 | 2 | COMPLETE | P1 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/animal/Animal.java` | — |
| `MixinArmadillo.java` | `net.minecraft.world.entity.animal.armadillo.Armadillo` | 1 | 1 | COMPLETE | P1 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/animal/armadillo/Armadillo.java` | — |
| `MixinAxolotl.java` | `net.minecraft.world.entity.animal.axolotl.Axolotl` | 1 | 0 | MISSING | P1 | — | — |
| `MixinBee.java` | `net.minecraft.world.entity.animal.bee.Bee` | 1 | 1 | COMPLETE | P1 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/animal/bee/Bee.java` | — |
| `MixinCamel.java` | `net.minecraft.world.entity.animal.camel.Camel` | 1 | 0 | MISSING | P1 | — | — |
| `MixinCat.java` | `net.minecraft.world.entity.animal.feline.Cat` | 1 | 1 | COMPLETE | P1 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/animal/feline/Cat.java` | — |
| `MixinCreeper.java` | `net.minecraft.world.entity.monster.Creeper` | 1 | 1 | COMPLETE | P3 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/monster/Creeper.java` | — |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 2 | 1 | PARTIAL | P1 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/Entity.java` | — |
| `MixinMob.java` | `net.minecraft.world.entity.Mob` | 2 | 0 | MISSING | P1 | — | — |
| `MixinMushroomCow.java` | `net.minecraft.world.entity.animal.cow.MushroomCow` | 3 | 0 | MISSING | P1 | — | — |
| `MixinSquid.java` | `net.minecraft.world.entity.animal.squid.Squid` | 1 | 1 | COMPLETE | P1 | `E:/.sigma/Sigma-Modern/src/main/java/net/minecraft/world/entity/animal/squid/Squid.java` | — |
| `MixinWolf.java` | `net.minecraft.world.entity.animal.wolf.Wolf` | 1 | 0 | MISSING | P1 | — | — |
| `MixinZombieVillager.java` | `net.minecraft.world.entity.monster.zombie.ZombieVillager` | 1 | 0 | MISSING | P1 | — | — |

### `features/entity/legacy_boat_model` — 0/16 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 12 | 0 | MISSING | P1 | — | — |
| `MixinEntityRenderDispatcher.java` | `net.minecraft.client.renderer.entity.EntityRenderDispatcher` | 3 | 0 | MISSING | P3 | — | — |
| `MixinLayerDefinitions.java` | `net.minecraft.client.model.geom.LayerDefinitions` | 1 | 0 | MISSING | P3 | — | — |

### `features/entity/metadata` — 0/7 hook, MISSING 1, NOT_APPLICABLE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinCommonBoss.java` | `com.viaversion.viaversion.legacy.bossbar.CommonBoss (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P2 | — | — |
| `MixinEntityPacketRewriter1_15.java` | `com.viaversion.viaversion.protocols.v1_14_4to1_15.rewriter.EntityPacketRewriter1_15 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinEntityPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.EntityPacketRewriter1_9 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P1 | — | — |
| `MixinEntityTracker1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.storage.EntityTracker1_9 (ViaVersion JAR)` | 3 | 0 | NOT_APPLICABLE | P2 | — | — |
| `MixinWolf.java` | `net.minecraft.world.entity.animal.wolf.Wolf` | 1 | 0 | MISSING | P3 | — | — |

### `features/entity/pose` — 0/2 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 1 | 0 | MISSING | P1 | — | — |
| `MixinRemotePlayer.java` | `net.minecraft.client.player.RemotePlayer` | 1 | 0 | MISSING | P1 | — | — |

### `features/execute_inputs_sync` — 7/7 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinKeyboardHandler.java` | `net.minecraft.client.KeyboardHandler` | 3 | 3 | COMPLETE | P1 | — | `cb9c21ab` |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P1 | — | `cb9c21ab` |
| `MixinMouseHandler.java` | `net.minecraft.client.MouseHandler` | 3 | 3 | COMPLETE | P1 | — | `cb9c21ab` |

### `features/font` — 0/8 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinFontSet.java` | `net.minecraft.client.gui.font.FontSet` | 8 | 0 | MISSING | P3 | — | — |

### `features/interaction` — 0/4 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 2 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 0 | MISSING | P1 | — | — |

### `features/interaction/container_clicking` — 0/20 hook, MISSING 6, NOT_APPLICABLE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractContainerMenu.java` | `net.minecraft.world.inventory.AbstractContainerMenu` | 3 | 0 | MISSING | P0 | — | — |
| `MixinAbstractContainerScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen` | 4 | 0 | MISSING | P1 | — | — |
| `MixinAbstractFurnaceMenu.java` | `net.minecraft.world.inventory.AbstractFurnaceMenu` | 2 | 0 | MISSING | P2 | — | — |
| `MixinBlockItemPacketRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewriter1_21_5` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinCraftingMenu.java` | `net.minecraft.world.inventory.CraftingMenu` | 1 | 0 | MISSING | P2 | — | — |
| `MixinEntityTrackerBase.java` | `com.viaversion.viaversion.data.entity.EntityTrackerBase` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinItemPacketRewriter1_17.java` | `com.viaversion.viaversion.protocols.v1_16_4to1_17.rewriter.ItemPacketRewriter1_17` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinMerchantMenu.java` | `net.minecraft.world.inventory.MerchantMenu` | 2 | 0 | MISSING | P1 | — | — |
| `MixinMultiPlayerGameMode.java` | `net.minecraft.client.multiplayer.MultiPlayerGameMode` | 5 | 0 | MISSING | P0 | — | — |

### `features/interaction/cooldown` — 0/6 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinItemCooldowns.java` | `net.minecraft.world.item.ItemCooldowns` | 1 | 0 | MISSING | P1 | — | — |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 3 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 0 | MISSING | P1 | — | — |

### `features/interaction/r1_18_2_block_ack_emulation` — 0/5 hook, MISSING 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMultiPlayerGameMode.java` | `net.minecraft.client.multiplayer.MultiPlayerGameMode (implements com.viaversion.viafabricplus.injection.access.interaction.r1_18_2_block_ack_emulation.IMultiPlayerGameMode)` | 4 | 0 | MISSING | P1 | — | — |
| `MixinWorldPacketRewriter1_19.java` | `com.viaversion.viaversion.protocols.v1_18_2to1_19.rewriter.WorldPacketRewriter1_19 (and Protocol1_18_2To1_19)` | 1 | 0 | NOT_APPLICABLE | P1 | — | — |

### `features/interaction/remove_fuel_slot` — 0/2 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBrewingStandMenu_FuelSlot.java` | `net.minecraft.world.inventory.BrewingStandMenu$FuelSlot` | 2 | 0 | MISSING | P2 | — | — |

### `features/interaction/remove_offhand_slot` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinInventoryMenu.java` | `net.minecraft.world.inventory.InventoryMenu` | 1 | 0 | MISSING | P2 | — | — |

### `features/interaction/replace_block_item_use_logic` — 0/20 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBlockPlaceContext.java` | `net.minecraft.world.item.context.BlockPlaceContext` | 2 | 0 | MISSING | P2 | — | — |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 2 | 0 | MISSING | P1 | — | — |
| `MixinMultiPlayerGameMode.java` | `net.minecraft.client.multiplayer.MultiPlayerGameMode` | 16 | 0 | MISSING | P0 | — | — |

### `features/item/attack_damage` — 0/9 hook, MISSING 2, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinItemAttributeModifiers_Display_Default.java` | `net.minecraft.world.item.component.ItemAttributeModifiers$Display$Default` | 3 | 0 | MISSING | P3 | — | — |
| `MixinItemPacketRewriter1_9.java` | `com.viaversion.viaversion.protocols.v1_8to1_9.rewriter.ItemPacketRewriter1_9 (remap=false)` | 5 | 0 | NOT_APPLICABLE | P2 | — | — |
| `MixinItemStack.java` | `net.minecraft.world.item.ItemStack` | 1 | 0 | MISSING | P3 | — | — |

### `features/item/data_fix` — 0/5 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBlockItemPacketRewriter1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.rewriter.BlockItemPacketRewriter1_20_5 (remap=false)` | 5 | 0 | NOT_APPLICABLE | P2 | — | — |

### `features/item/filter_creative_tabs` — 0/4 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinCreativeModeTab_ItemDisplayBuilder.java` | `net.minecraft.world.item.CreativeModeTab$ItemDisplayBuilder (targets="net.minecraft.world.item.CreativeModeTab$ItemDisplayBuilder")` | 1 | 0 | MISSING | P3 | — | — |
| `MixinCreativeModeTabs.java` | `net.minecraft.world.item.CreativeModeTabs` | 3 | 0 | MISSING | P3 | — | — |

### `features/item/interaction` — 0/23 hook, MISSING 18

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAxeItem.java` | `net.minecraft.world.item.AxeItem` | 2 | 0 | MISSING | P2 | — | — |
| `MixinBlockItem.java` | `net.minecraft.world.item.BlockItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinBoneMealItem.java` | `net.minecraft.world.item.BoneMealItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinBowItem.java` | `net.minecraft.world.item.BowItem` | 3 | 0 | MISSING | P2 | — | — |
| `MixinBrushItem.java` | `net.minecraft.world.item.BrushItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinBucketItem.java` | `net.minecraft.world.item.BucketItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinBundleItem.java` | `net.minecraft.world.item.BundleItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinConsumable.java` | `net.minecraft.world.item.component.Consumable` | 1 | 0 | MISSING | P2 | — | — |
| `MixinEnderpearlItem.java` | `net.minecraft.world.item.EnderpearlItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinEquippable.java` | `net.minecraft.world.item.equipment.Equippable` | 2 | 0 | MISSING | P1 | — | — |
| `MixinFireChargeItem.java` | `net.minecraft.world.item.FireChargeItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinFireworkRocketItem.java` | `net.minecraft.world.item.FireworkRocketItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinFishingRodItem.java` | `net.minecraft.world.item.FishingRodItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinKnowledgeBookItem.java` | `net.minecraft.world.item.KnowledgeBookItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinLeadItem.java` | `net.minecraft.world.item.LeadItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinNameTagItem.java` | `net.minecraft.world.item.NameTagItem` | 1 | 0 | MISSING | P1 | — | — |
| `MixinShovelItem.java` | `net.minecraft.world.item.ShovelItem` | 1 | 0 | MISSING | P2 | — | — |
| `MixinSpawnEggItem.java` | `net.minecraft.world.item.SpawnEggItem` | 2 | 0 | MISSING | P2 | — | — |

### `features/item/negative_item_count` — 0/2 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinGuiGraphicsExtractor.java` | `net.minecraft.client.gui.GuiGraphicsExtractor` | 2 | 0 | MISSING | P3 | — | — |

### `features/item/sword_blocking` — 0/1 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBlockItemPacketRewriter1_21_X.java` | `com.viaversion.viaversion.protocols.v1_21_2to1_21_4.rewriter.BlockItemPacketRewriter1_21_4, com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.BlockItemPacketRewriter1_21_5` | 1 | 0 | NOT_APPLICABLE | P2 | — | — |

### `features/item/tooltip` — 0/3 hook, MISSING 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinComponentRewriter1_21_5.java` | `com.viaversion.viaversion.protocols.v1_21_4to1_21_5.rewriter.ComponentRewriter1_21_5` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinItemStack.java` | `net.minecraft.world.item.ItemStack` | 2 | 0 | MISSING | P3 | — | — |

### `features/large_container` — 0/2 hook, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinItemPacketRewriter1_14.java` | `com.viaversion.viaversion.protocols.v1_13_2to1_14.rewriter.ItemPacketRewriter1_14 (JAR: viaversion-common 5.12.0-SNAPSHOT)` | 2 | 0 | NOT_APPLICABLE | P0 | — | — |

### `features/legacy_tab_completion` — 8/8 hook, COMPLETE 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractCommandBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen` | 1 | 1 | COMPLETE | P2 | — | `a5cba06c` |
| `MixinChatScreen.java` | `net.minecraft.client.gui.screens.ChatScreen` | 4 | 4 | COMPLETE | P2 | — | `a5cba06c` |
| `MixinCommandSuggestions.java` | `net.minecraft.client.gui.components.CommandSuggestions` | 3 | 3 | COMPLETE | P2 | — | `a5cba06c` |

### `features/limitation/allow_negative_amplifier` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMobEffectInstance.java` | `net.minecraft.world.effect.MobEffectInstance` | 1 | 0 | MISSING | P1 | — | — |

### `features/limitation/book_edit` — 0/2 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBookEditScreen.java` | `net.minecraft.client.gui.screens.inventory.BookEditScreen` | 2 | 0 | MISSING | P2 | — | — |

### `features/limitation/max_chat_length` — 0/4 hook, MISSING 3, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinChatScreen.java` | `net.minecraft.client.gui.screens.ChatScreen` | 1 | 0 | MISSING | P2 | — | — |
| `MixinProtocol1_10To1_11.java` | `com.viaversion.viaversion.protocols.v1_10to1_11.Protocol1_10To1_11$6 (JAR: viaversion-common; anonymous PacketHandlers for ServerboundPackets1_9_3.CHAT)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinServerboundChatPacket.java` | `net.minecraft.network.protocol.game.ServerboundChatPacket` | 1 | 0 | MISSING | P0 | — | — |
| `MixinStringUtil.java` | `net.minecraft.util.StringUtil` | 1 | 0 | MISSING | P0 | — | — |

### `features/mouse_sensitivity` — 0/2 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMouseHandler.java` | `net.minecraft.client.MouseHandler` | 1 | 0 | MISSING | P1 | — | — |
| `MixinMouseSettingsScreen.java` | `net.minecraft.client.gui.screens.options.MouseSettingsScreen` | 1 | 0 | MISSING | P3 | — | — |

### `features/movement/collision` — 1/21 hook, COMPLETE 1, MISSING 5, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractBoat.java` | `net.minecraft.world.entity.vehicle.boat.AbstractBoat` | 1 | 0 | MISSING | P1 | — | — |
| `MixinBedBlock.java` | `net.minecraft.world.level.block.BedBlock` | 1 | 0 | PARTIAL | P1 | `net/minecraft/world/level/block/BedBlock.java` | — |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 11 | 0 | MISSING | P1 | — | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 4 | 0 | MISSING | P1 | — | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/LocalPlayer.java` | — |
| `MixinShapes.java` | `net.minecraft.world.phys.shapes.Shapes` | 1 | 0 | MISSING | P1 | — | — |
| `MixinSoulSandBlock.java` | `net.minecraft.world.level.block.SoulSandBlock` | 2 | 0 | MISSING | P1 | — | — |

### `features/movement/constants` — 4/14 hook, COMPLETE 3, MISSING 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAvatar.java` | `net.minecraft.world.entity.Avatar` | 1 | 0 | MISSING | P1 | — | — |
| `MixinAvatarRenderer.java` | `net.minecraft.client.renderer.entity.player.AvatarRenderer` | 2 | 0 | MISSING | P3 | — | — |
| `MixinBlockGetter.java` | `net.minecraft.world.level.BlockGetter` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/level/BlockGetter.java` | — |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 3 | 0 | MISSING | P1 | — | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 2 | 0 | MISSING | P1 | — | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` | — |
| `MixinMth.java` | `net.minecraft.util.Mth` | 2 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 2 | 2 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` | — |

### `features/movement/elytra` — 1/7 hook, COMPLETE 1, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinFireworkRocketItem.java` | `net.minecraft.world.item.FireworkRocketItem` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/item/FireworkRocketItem.java` | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 4 | 0 | MISSING | P1 | — | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 1 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 1 | 0 | MISSING | P1 | — | — |

### `features/movement/jump` — 1/5 hook, MISSING 1, PARTIAL 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 3 | 1 | PARTIAL | P1 | `net/minecraft/world/entity/LivingEntity.java` | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 0 | MISSING | P1 | — | — |

### `features/movement/limitation` — 0/10 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 6 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 4 | 0 | MISSING | P1 | — | — |

### `features/movement/limitation/rotation` — 1/4 hook, COMPLETE 1, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 3 | 0 | MISSING | P1 | — | — |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 1 | 1 | COMPLETE | P3 | `net/minecraft/world/entity/player/Player.java` | — |

### `features/movement/liquid` — 2/27 hook, COMPLETE 2, MISSING 7

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 4 | 0 | MISSING | P1 | — | — |
| `MixinEntityFluidInteraction.java` | `net.minecraft.world.entity.EntityFluidInteraction` | 5 | 0 | MISSING | P1 | — | — |
| `MixinEntityFluidInteraction_Tracker.java` | `net.minecraft.world.entity.EntityFluidInteraction$Tracker` | 4 | 0 | MISSING | P1 | — | — |
| `MixinFlowingFluid.java` | `net.minecraft.world.level.material.FlowingFluid` | 1 | 0 | MISSING | P2 | — | — |
| `MixinItemEntity.java` | `net.minecraft.world.entity.item.ItemEntity` | 1 | 0 | MISSING | P1 | — | — |
| `MixinLiquidBlock.java` | `net.minecraft.world.level.block.LiquidBlock` | 1 | 1 | COMPLETE | P2 | `net/minecraft/world/level/block/LiquidBlock.java` | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 7 | 0 | MISSING | P1 | — | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 3 | 0 | MISSING | P1 | — | — |
| `MixinSkeletonHorse.java` | `net.minecraft.world.entity.animal.equine.SkeletonHorse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/SkeletonHorse.java` | — |

### `features/movement/packet` — 4/4 hook, COMPLETE 2, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEntityPacketRewriter1_21_2.java` | `com.viaversion.viaversion.protocols.v1_21to1_21_2.rewriter.EntityPacketRewriter1_21_2 (ViaVersion JAR - no source in the tree)` | 1 | 1 | REPLACED | P0 | — | `cb9c21ab` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` | `187094a8` |
| `MixinPositionMoveRotation.java` | `net.minecraft.world.entity.PositionMoveRotation` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/PositionMoveRotation.java` | — |

### `features/movement/slowdown` — 1/3 hook, COMPLETE 1, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinEnderEyeItem.java` | `net.minecraft.world.item.EnderEyeItem` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/item/EnderEyeItem.java` | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 2 | 0 | MISSING | P1 | — | — |

### `features/movement/sprinting_and_sneaking` — 24/24 hook, COMPLETE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinKeyboardInput.java` | `net.minecraft.client.player.KeyboardInput` | 1 | 1 | COMPLETE | P1 | `net/minecraft/client/player/KeyboardInput.java` | — |
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/LivingEntity.java` | `187094a8` |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 19 | 19 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` | `187094a8` |
| `MixinPlayer.java` | `net.minecraft.world.entity.player.Player` | 3 | 3 | COMPLETE | P1 | `net/minecraft/world/entity/player/Player.java` | `187094a8` |

### `features/movement/vehicle` — 4/4 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractHorse.java` | `net.minecraft.world.entity.animal.equine.AbstractHorse` | 1 | 1 | COMPLETE | P1 | `net/minecraft/world/entity/animal/equine/AbstractHorse.java` | — |
| `MixinLocalPlayer.java` | `net.minecraft.client.player.LocalPlayer` | 3 | 3 | COMPLETE | P0 | `net/minecraft/client/player/LocalPlayer.java` | `187094a8` |

### `features/networking/config_state` — 6/7 hook, COMPLETE 4, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientConfigurationPacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl.java` | `2be0a7df` |
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` | `2be0a7df` |
| `MixinProtocol1_20To1_20_2.java` | `com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2 (viaversion-common JAR, pom.xml:60-63)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinProtocolSwapHandler.java` | `net.minecraft.network.ProtocolSwapHandler` | 1 | 1 | COMPLETE | P0 | `net/minecraft/network/ProtocolSwapHandler.java` | — |
| `MixinUnconfiguredPipelineHandler.java` | `net.minecraft.network.UnconfiguredPipelineHandler` | 1 | 1 | COMPLETE | P0 | `net/minecraft/network/UnconfiguredPipelineHandler.java` | — |

### `features/networking/keep_player_loaded` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` | — |

### `features/networking/legacy_chat_signature` — 3/7 hook, COMPLETE 2, MISSING 1, NOT_APPLICABLE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAccountProfileKeyPairManager.java` | `net.minecraft.client.multiplayer.AccountProfileKeyPairManager` | 1 | 0 | MISSING | P0 | — | — |
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (the anonymous Thread subclass in ConnectScreen#connect)` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` | — |
| `MixinKeyPairResponse.java` | `com.mojang.authlib.yggdrasil.response.KeyPairResponse (authlib 9.0.75 JAR, pom.xml:219-223)` | 2 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinProfilePublicKey_Data.java` | `net.minecraft.world.entity.player.ProfilePublicKey$Data` | 2 | 2 | COMPLETE | P0 | `net/minecraft/world/entity/player/ProfilePublicKey.java` | — |
| `MixinYggdrasilUserApiService.java` | `com.mojang.authlib.yggdrasil.YggdrasilUserApiService (authlib 9.0.75 JAR, pom.xml:219-223)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |

### `features/networking/level_loading` — 9/10 hook, COMPLETE 2, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ClientPacketListener.java` | — |
| `MixinEntityPacketRewriter1_20_3.java` | `com.viaversion.viaversion.protocols.v1_20_2to1_20_3.rewriter.EntityPacketRewriter1_20_3 (viaversion-common JAR, pom.xml:60-63)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinLevelLoadingScreen.java` | `net.minecraft.client.gui.screens.LevelLoadingScreen` | 7 | 7 | COMPLETE | P0 | — | `2be0a7df` |

### `features/networking/limitation` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientHandshakePacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` | 1 | 1 | COMPLETE | P0 | — | `96251a5d` |

### `features/networking/limitation/nbt` — 0/2 hook, NOT_APPLICABLE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinNamedCompoundTagType.java` | `com.viaversion.viaversion.api.type.types.misc.NamedCompoundTagType (viaversion-common JAR, pom.xml:60-63)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinTagType.java` | `com.viaversion.viaversion.api.type.types.misc.TagType (viaversion-common JAR, pom.xml:60-63)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |

### `features/networking/open_inventory_packet` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | — | `96251a5d` |

### `features/networking/packet_handling` — 26/29 hook, COMPLETE 1, MISSING 1, NOT_APPLICABLE 1, PARTIAL 1, REPLACED 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientCommonPacketListenerImpl.java` | `net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl` | 5 | 5 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java` | `187094a8` |
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 21 | 20 | PARTIAL | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` | `187094a8` |
| `MixinEntityPacketRewriter1_19_4.java` | `com.viaversion.viaversion.protocols.v1_19_3to1_19_4.rewriter.EntityPacketRewriter1_19_4 (ViaVersion JAR, viaversion-common 5.12.0)` | 1 | 1 | REPLACED | P0 | — | `cb9c21ab` |
| `MixinGameTestBlockHighlightRenderer.java` | `net.minecraft.client.renderer.debug.GameTestBlockHighlightRenderer` | 1 | 0 | MISSING | P3 | — | — |
| `MixinProtocol1_21_7To1_21_9.java` | `com.viaversion.viaversion.protocols.v1_21_7to1_21_9.Protocol1_21_7To1_21_9 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `features/networking/player_abilities` — 3/3 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinServerboundPlayerAbilitiesPacket.java` | `net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket` | 3 | 3 | COMPLETE | P0 | — | `96251a5d` |

### `features/networking/registry_validation` — 3/3 hook, COMPLETE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinHolderSetCodec.java` | `net.minecraft.resources.HolderSetCodec` | 1 | 1 | COMPLETE | P0 | — | `96251a5d` |
| `MixinHolderSet_Named.java` | `net.minecraft.core.HolderSet$Named` | 2 | 2 | COMPLETE | P0 | — | `96251a5d` |

### `features/networking/remove_legacy_pinger` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinServerStatusPinger.java` | `net.minecraft.client.multiplayer.ServerStatusPinger` | 1 | 1 | COMPLETE | P3 | `net/minecraft/client/multiplayer/ServerStatusPinger.java` | — |

### `features/networking/remove_signed_commands` — 2/6 hook, COMPLETE 1, MISSING 2, NOT_APPLICABLE 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 2 | 2 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` | `2be0a7df` |
| `MixinGameModeSwitcherScreen.java` | `net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen` | 1 | 0 | MISSING | P0 | — | — |
| `MixinKeyboardHandler.java` | `net.minecraft.client.KeyboardHandler` | 1 | 0 | MISSING | P0 | — | — |
| `MixinProtocol1_20_3To1_20_5.java` | `com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |
| `MixinProtocol1_21_5To1_21_6.java` | `com.viaversion.viaversion.protocols.v1_21_5to1_21_6.Protocol1_21_5To1_21_6 (ViaVersion JAR)` | 1 | 0 | NOT_APPLICABLE | P0 | — | — |

### `features/networking/resource_pack_header` — 0/3 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinDownloadedPackSource_4.java` | `net.minecraft.client.resources.server.DownloadedPackSource$4 (anonymous PackDownloader returned by DownloadedPackSource#createDownloader)` | 3 | 0 | MISSING | P2 | — | — |

### `features/networking/run_command_action` — 1/2 hook, COMPLETE 1, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientPacketListener.java` | `net.minecraft.client.multiplayer.ClientPacketListener` | 1 | 1 | COMPLETE | P0 | `net/minecraft/client/multiplayer/ClientPacketListener.java` | `2be0a7df` |
| `MixinScreen.java` | `net.minecraft.client.gui.screens.Screen` | 1 | 0 | MISSING | P0 | — | — |

### `features/networking/server_pinging` — 0/10 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinServerSelectionList_OnlineServerEntry.java` | `net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry` | 9 | 0 | MISSING | P3 | — | — |
| `MixinServerStatusPinger_1.java` | `net.minecraft.client.multiplayer.ServerStatusPinger$1 (anonymous ClientStatusPacketListener created in ServerStatusPinger#pingServer)` | 1 | 0 | MISSING | P3 | — | — |

### `features/networking/srv_resolving` — 5/5 hook, COMPLETE 2, PARTIAL 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinConnectScreen_1.java` | `net.minecraft.client.gui.screens.ConnectScreen$1 (anonymous "Server Connector" Thread in ConnectScreen#startConnecting)` | 2 | 2 | PARTIAL | P0 | `net/minecraft/client/gui/screens/ConnectScreen.java` | — |
| `MixinServerAddress.java` | `net.minecraft.client.multiplayer.resolver.ServerAddress` | 1 | 1 | PARTIAL | P0 | `net/minecraft/client/multiplayer/resolver/ServerAddress.java` | — |
| `MixinServerNameResolver.java` | `net.minecraft.client.multiplayer.resolver.ServerNameResolver` | 1 | 1 | COMPLETE | P0 | — | `2be0a7df` |
| `MixinServerRedirectHandler.java` | `net.minecraft.client.multiplayer.resolver.ServerRedirectHandler (the lambda returned by createDnsSrvRedirectHandler, compiled as lambda$createDnsSrvRedirectHandler$0)` | 1 | 1 | COMPLETE | P0 | — | `96251a5d` |

### `features/recipe` — 0/3 hook, MISSING 2, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinCraftingMenu.java` | `net.minecraft.world.inventory.CraftingMenu` | 1 | 0 | MISSING | P2 | — | — |
| `MixinEntityPacketRewriter1_12.java` | `com.viaversion.viaversion.protocols.v1_11_1to1_12.rewriter.EntityPacketRewriter1_12$1 (anonymous PacketHandlers for the JOIN_GAME registration)` | 1 | 0 | NOT_APPLICABLE | P2 | — | — |
| `MixinInventoryMenu.java` | `net.minecraft.world.inventory.InventoryMenu` | 1 | 0 | MISSING | P2 | — | — |

### `features/scoreboard` — 0/2 hook, MISSING 1, NOT_APPLICABLE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinComponentUtil.java` | `com.viaversion.viaversion.util.ComponentUtil (methods legacyToJson and legacyToJsonString(String,boolean))` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinPlayerTeam.java` | `net.minecraft.world.scores.PlayerTeam` | 1 | 0 | MISSING | P3 | — | — |

### `features/screen_changes` — 0/8 hook, MISSING 5

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinCommandBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen` | 1 | 0 | MISSING | P3 | — | — |
| `MixinJigsawBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen` | 2 | 0 | MISSING | P3 | — | — |
| `MixinStructureBlockEditScreen.java` | `net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen` | 3 | 0 | MISSING | P3 | — | — |
| `MixinStructureBlockEditScreen_1.java` | `net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen$1 (anonymous EditBox subclass created in StructureBlockEditScreen#init)` | 1 | 0 | MISSING | P3 | — | — |
| `MixinWorldOptionsScreen.java` | `net.minecraft.client.gui.screens.options.WorldOptionsScreen` | 1 | 0 | MISSING | P3 | — | — |

### `features/sign_editor_reach` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinAbstractSignEditScreen.java` | `net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen` | 1 | 0 | MISSING | P3 | — | — |

### `features/skin_loading` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinSkinManager.java` | `net.minecraft.client.resources.SkinManager` | 1 | 0 | MISSING | P3 | — | — |

### `features/swinging` — 5/6 hook, COMPLETE 1, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinLivingEntity.java` | `net.minecraft.world.entity.LivingEntity` | 1 | 0 | MISSING | P1 | — | — |
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 5 | 5 | COMPLETE | P0 | — | `2be0a7df` |

### `features/world/always_tick_entities` — 0/6 hook, MISSING 2

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 3 | 0 | MISSING | P1 | — | — |
| `MixinEntity.java` | `net.minecraft.world.entity.Entity` | 3 | 0 | MISSING | P1 | — | — |

### `features/world/disable_sequencing` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 1 | COMPLETE | P0 | — | `2be0a7df` |

### `features/world/duplicated_sounds` — 0/3 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinBlockItem.java` | `net.minecraft.world.item.BlockItem` | 1 | 0 | MISSING | P3 | — | — |
| `MixinButtonBlock.java` | `net.minecraft.world.level.block.ButtonBlock` | 1 | 0 | MISSING | P3 | — | — |
| `MixinItems.java` | `net.minecraft.world.item.FlintAndSteelItem, net.minecraft.world.item.HoeItem` | 1 | 0 | MISSING | P3 | — | — |

### `features/world/entity_distance` — 0/3 hook, MISSING 3

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinClientLevel.java` | `net.minecraft.client.multiplayer.ClientLevel` | 1 | 0 | MISSING | P3 | — | — |
| `MixinLevelExtractor.java` | `net.minecraft.client.renderer.extract.LevelExtractor` | 1 | 0 | MISSING | P3 | — | — |
| `MixinRemotePlayer.java` | `net.minecraft.client.player.RemotePlayer` | 1 | 0 | MISSING | P3 | — | — |

### `features/world/footstep_particle` — 0/6 hook, NOT_APPLICABLE 4

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMappingDataBase.java` | `com.viaversion.viaversion.api.data.MappingDataBase (viaversion-common JAR, 5.12.0-20260819.184210-4)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinParticleIdMappings1_13.java` | `com.viaversion.viaversion.protocols.v1_12_2to1_13.data.ParticleIdMappings1_13 (viaversion-common JAR)` | 2 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinParticleMappings.java` | `com.viaversion.viaversion.api.data.ParticleMappings (viaversion-common JAR; mixin extends FullMappingsBase)` | 2 | 0 | NOT_APPLICABLE | P3 | — | — |
| `MixinRegistrySyncManager.java` | `net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager (Fabric API impl class)` | 1 | 0 | NOT_APPLICABLE | P3 | — | — |

### `features/world/item_picking` — 1/1 hook, COMPLETE 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinMinecraft.java` | `net.minecraft.client.Minecraft` | 1 | 1 | COMPLETE | P0 | — | `2be0a7df` |

### `features/world/remove_server_view_distance` — 0/1 hook, MISSING 1

| 上游 mixin | 目标 vanilla 类 | hook | 已移植 | 状态 | 优先级 | Sigma 位置 | 本轮 |
|---|---|---|---|---|---|---|---|
| `MixinOptions.java` | `net.minecraft.client.Options` | 1 | 0 | MISSING | P2 | — | — |


## GrimAC 诊断链路追踪 / diagnostics trace

三个上报项分开追，不假设它们是同一个 bug。**目标是让翻译后的客户端行为符合对应版本的 vanilla 协议时序，
没有、也不会加入任何针对 GrimAC 的 bypass / spoof / 特判。**

### NegativeTimer (-2231ms / -3133ms)

Timer 余额为负的含义是：单位墙钟时间内客户端发出的移动报文**少于** 20/s。找到两个叠加的成因，都已修复：

1. `features/movement/packet/MixinLocalPlayer#sendIdlePacket` 缺失。
   `r1_4_2..1.8` 以及 `≤ r1_2_4tor1_2_5` 的 vanilla 会**每 tick**发一个 idle 移动报文；26.2 只在
   `onGround`/`horizontalCollision` 变化时才发 `ServerboundMovePlayerPacket.StatusOnly`。
   上游用 `@Redirect` 把 `lastOnGround` 的读取替换成“即将发送值的取反”，使那个 `else if` 恒真。
   缺这一条时，静止或匀速走动的 tick 大多**一个移动报文都不发**。已在 `187094a8` 内联。
2. `features/movement/packet/MixinEntityPacketRewriter1_21_2#dontCancelIdlePacket` 缺失。
   即使客户端发了 StatusOnly，ViaVersion 的 `Protocol1_21To1_21_2` 在
   `EntityPacketRewriter1_21_2` 的 `MOVE_PLAYER_STATUS_ONLY` handler 里，只要 on-ground 状态没变就
   `wrapper.cancel()`（源码注释写的是 "Newer clients will send idle packets even though the on ground state
   didn't change, ignore them"）。该链路对**任何 ≤1.21 目标**都在路径上。
   已用 `appendServerbound(..., w -> w.setCancelled(false))` 在 `cb9c21ab` 重建 —— 用 javap 确认了
   `lambda$registerPackets$14` 就是那个 handler，且其中只有一个 `cancel()` 调用。
3. `features/networking/level_loading/MixinLevelLoadingScreen` 缺失（0/7）。其中一条是
   ≤1.12.1 目标在地形下载期间**每 20 tick 发一个 `ServerboundKeepAlivePacket(0)`**；缺失会让旧服在 join
   阶段把客户端判超时。已在 `2be0a7df` 内联。

-2231ms ≈ 44 个丢失的 tick，-3133ms ≈ 62 个，与 (1)+(2) 叠加后的量级一致。
**是否完全解释观测到的 VL，未经实机验证。**

### BadPacketsV (delta=0.0，VL 持续增长)

该族检查报文内容自相矛盾。修掉的相关保真度缺陷：

1. `moveLastPosPacketIncrement` 缺失：≤1.8 的 vanilla 在**递增之前**判 20-tick 强制位置提醒，
   26.2 是先递增后判。缺这一条时强制位置报文比 1.8 语义早一个 tick，于是在服务端不预期的时刻
   出现一个 delta 为 0 的位置报文。已修（`187094a8`）。
2. 潜行状态从未上报：`sendSneakingAfterSprinting` / `sendSneakingPacket` 缺失，
   ≤1.21 目标收不到潜行 player-command，服务端按站立速度预测、客户端按 0.3 倍潜行速度移动，位置对不上。
   已修（`187094a8`）。
3. `removeSprintingPacket` 缺失：<1.19.3 时 `tick()` 的载具分支**也**会发冲刺 player-command，
   等于多发一份。已修（`187094a8`）。
4. `features/swinging/MixinMinecraft#fixSwingPacketOrder` 缺失：≤1.8 期望 swing 在 attack /
   start-destroy **之前**。已修（`2be0a7df`）。

同样：**这些是那些检查所盯路径上确实存在的协议保真度缺陷，但“修完 VL 就归零”未经实机验证。**

### TransactionOrder (skipped=1)

完整链路（≤1.16.4 目标）逐段核过：

1. 服务端（1.16.x，Grim 用 transaction）发 `ClientboundContainerAckPacket`（window id, action number,
   `accepted=false`）。
2. ViaVersion `Protocol1_16_4To1_17` 的 `ItemPacketRewriter1_17`（`v1_16_4to1_17/rewriter/
   ItemPacketRewriter1_17.java:82-96`）把它转成 1.17 的 `PING`，
   id 编码为 `(1 << 30) | (inventoryId << 16) | (confirmationId & 0xFFFF)`，并取消原报文。
3. 26.2 客户端在 `ClientCommonPacketListenerImpl#handlePing` 处理：
   `ensureRunningOnSameThread` 转主线程，然后 `send(new ServerboundPongPacket(id))`。
4. Via 的 serverbound `PONG` handler（同文件 :99-114）检查第 30 位，解出 window id / confirmation id，
   重建 `ServerboundContainerAckPacket(..., accepted=true)` 发往服务端。

在这条链路上找到的 Sigma 侧偏差：

- **`addMissingConditions` 缺失。** 1.16 vanilla 只在 window id 为 0、或等于当前打开容器的 id 时才回应
  transaction；Sigma 之前对**每一个** ping 都回。也就是说客户端会为服务端并不追踪的 window 发出额外的
  transaction 回应。已在 `187094a8` 内联（并对 `minecraft.player == null` 加了保护 —— 该 listener 也服务
  configuration 阶段，上游那里是裸解引用）。
- **config-state autoRead 缺陷**（见上文缺陷 1）本身就是一个“在 play↔configuration 边界上恰好丢一个在途报文”
  的机制，而 `skipped=1` 正是单个丢失的形状。已在 `2be0a7df` 修掉。

仍然存在、且**与上游默认行为一致**（不是 Sigma 的缺口，但是最后一个可疑点）：

- `DebugSettings.queueConfigPackets` 默认 `true`（上游同样是 `true`）。此时 ViaVersion
  `Protocol1_20To1_20_2#queueServerboundPacket` 会把客户端在（被模拟出来的）CONFIGURATION 阶段发出的
  `PONG` / `KEEP_ALIVE` / `CUSTOM_PAYLOAD` **排队**，等切到 play 之后再冲刷。
  服务端若在这个窗口内发了 transaction，回应就会迟到，表现正是 `skipped=1`。
  上游 `MixinProtocol1_20To1_20_2#dontQueueConfigPackets` 的作用是在该设置**关闭**时改为立刻转成 play 报文，
  所以上游默认行为和现在一致。该 mixin 目标是库类，本轮未重建（见 §库目标）。
  **可验证动作：在设置里把 `queueConfigPackets` 关掉再复现一次。** 若 `skipped=1` 消失，就定位到这里，
  届时按上游语义用 Via 公开 API 重建该 handler。

### 与 Sigma event 层的相互作用（需要单独确认）

`LocalPlayer#sendPosition` 里 `EventMotion` 被取消会**直接 return，一个移动报文都不发**；
`LocalPlayer#tick` 里 `EventUpdate(PRE)` 被取消会跳过整个 tick（含移动报文）。
任何模块只要取消其中之一，就能独立复现 NegativeTimer，与 VFP 层无关。
本轮所有 VFP 语义都是内联在这两个 hook **之内**并使用 event 提供的值（例如 idle 判定用的是即将发送的
`onGround`，而不是 `this.onGround()`），以保证 Sigma 事件层是附加层、VFP 版本语义是基础层。
复现时建议先全部模块关闭跑一遍作为基线。

## 运行时验证矩阵 / runtime test matrix

**NOT RUNTIME VERIFIED.** 本轮只做到 `mvn -DskipTests compile`（JDK 26，`--release 25`）BUILD SUCCESS。
本环境没有实机连服条件，下表全部未执行。

| 目标版本 | 连接 | 进入世界 | 静止 | 走路 | 奔跑 | 跳跃 | 转头 | 潜行 | transaction/keepalive 稳定性 | 重连 |
|---|---|---|---|---|---|---|---|---|---|---|
| Native 26.2 | — | — | — | — | — | — | — | — | — | — |
| 1.21.x | — | — | — | — | — | — | — | — | — | — |
| 1.20.1 | — | — | — | — | — | — | — | — | — | — |
| 1.19.x | — | — | — | — | — | — | — | — | — | — |
| 1.18.x | — | — | — | — | — | — | — | — | — | — |
| 1.12.2 | — | — | — | — | — | — | — | — | — | — |
| 1.8.9 | — | — | — | — | — | — | — | — | — | — |

`—` = 未测试。别把空格当通过。

需要重点观察的项（本轮改动直接影响）：

- 1.8.9 / 1.12.2：静止站立时抓包确认**每 tick 都有**一个移动报文（`sendIdlePacket` +
  `dontCancelIdlePacket` 的联合效果）。
- 1.8.9：潜行/取消潜行时确认有 `PLAYER_COMMAND` press/release shift；左键攻击时确认 swing 在 attack 之前。
- 1.12.2：地形下载期间确认每 20 tick 有一个 keep-alive(0)。
- 1.20.1：configuration↔play 切换时确认没有报文丢失（autoRead 修复）。
- ≤1.20.3：确认命令走签名分支（`alwaysSignCommands` 取反修复）。
- (1.20.3, 1.21.5]：确认命令不再弹确认框（`dontOpenConfirmationScreens` 门控修复）。
- ≤1.12.2：容器点击顺序（`execute_inputs_sync`）。

## 已知的功能级失效 / feature-level dead paths

`SyncTasks` 整条机制目前是空转的：

- `core/integration/sync_tasks/MixinClientCommonPacketListenerImpl#handleSyncTask` 已内联，
  但 `DataCustomPayload` 从未注册进 `ClientboundCustomPayloadPacket` 的 `CONFIG_STREAM_CODEC` /
  `GAMEPLAY_STREAM_CODEC`，所以 `packet.payload()` 永远不可能是 `DataCustomPayload`
  （上游靠 Fabric `PayloadTypeRegistry`；Sigma 的 shim 只是个 map，没有接到 codec 上）。
- 而且三个**生产者**全部是库目标 mixin，都还没重建：
  `features/interaction/r1_18_2_block_ack_emulation/MixinWorldPacketRewriter1_19`、
  `features/large_container/MixinItemPacketRewriter1_14`、
  `features/networking/packet_handling/MixinProtocol1_21_7To1_21_9`。

所以本轮**没有**去补那个 codec 注册 —— 在生产者重建之前它是纯死代码。两件事要一起做。

## 下一步优先级 / next steps

1. **P0 剩余 15 MISSING + 5 PARTIAL**（见 §仍未完成的 P0）。其中协议影响最大的三块：
   `features/interaction/container_clicking`（8 hook，容器点击报文）、
   `features/interaction/replace_block_item_use_logic/MixinMultiPlayerGameMode`（16 hook，方块/物品使用报文）、
   `features/networking/legacy_chat_signature`（1.19.0 签名链）。
2. `SyncTasks` 生产者 + codec 注册一起补（见上一节）。
3. 用 Via 公开 API 重建剩下有真实缺口的库目标 mixin，继续放进 `ViaFabricPlusProtocolPatches`。
4. **P1 82 MISSING + 7 PARTIAL**：movement 组的 liquid / collision / constants / limitation，
   以及 entity 组的 dimensions（39 个 mixin，几乎整组未移植）。
5. P2 / P3 按上表逐组推进。
6. 每批之后重跑本文件的生成流程，让账本跟着代码走。

## 复现本审计 / reproducing this audit

审计脚本与中间产物在 `target/vfp-audit/`（`target/` 已被 gitignore）：
`unit-01..25.txt` 是分片清单，`audit_rows.json` 是 25 个审计 agent + 15 个对抗复核 agent 的合并输出，
`audit_final.json` 是叠加本轮修复后的结果，`gen1.py` / `gen2.py` / `gen3.py` 生成本文件。
