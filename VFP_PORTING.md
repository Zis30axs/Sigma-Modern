# ViaFabricPlus → Sigma-Modern 移植进度

目标：将 E:\.sigma\PerMods\ViaFabricPlus-ver-26.2（4.6.3, MC 26.2, 官方映射）完整移植进
Sigma-Modern 无加载器源码工程。遵循 sodium/iris 已确立的模式：
mixin 注入逻辑内联到 vanilla 目标类并标注 `// MODIFIED for porting:`；
accessor 接口保留为普通接口由目标类实现；入口点改为 bootstrap 调用。

## 关键事实
- 源码 561 个 java（含 393 个 mixin）；api 子项目 12；visuals 子项目 33
- 官方 mojmap 映射，可直接复制
- 外部库（纯 Java，映射无关，走 Maven）：
  com.viaversion:viaversion-common:5.12.0-20260819.184210-4
  com.viaversion:viabackwards-common:5.12.0-20260805.160710-1
  com.viaversion:viaaprilfools-common:4.2.3-20260820.140819-4
  net.raphimc:ViaLegacy:3.1.0-20260821.100118-5
  net.raphimc:ViaBedrock:0.0.29-20260720.172239-5 (excl brigadier/lz4-java/io.netty)
  net.raphimc:MinecraftAuth:5.0.1 (excl gson)
  dev.kastle.netty:netty-transport-raknet:1.7.0 / netty-transport-nethernet:1.7.0 (excl io.netty)
  dev.kastle.webrtc:webrtc-java:1.0.3 classifiers: windows-x86_64, windows-aarch64, linux-x86_64, linux-aarch64, macos-aarch64
  net.lenni0451:Reflect:1.6.4
  de.florianreuth:classic4j:2.3.0
  仓库: https://repo.viaversion.com , https://maven.lenni0451.net/everything , https://maven.florianreuth.de/releases
- Fabric 面共 16 个导入需内嵌实现（保持原包名 net.fabricmc.* 使上游文件零改动）：
  loader: FabricLoader, ModContainer, ModMetadata, Person, EntrypointContainer
  event: Event, EventFactory
  client.command.v2: ClientCommandRegistrationCallback, FabricClientCommandSource
  lifecycle: ClientTickEvents
  particle: ParticleProviderRegistry, FabricParticleTypes
  networking: PayloadTypeRegistry
  registry sync 内部类: ClientRegistrySyncHandler, RegistrySyncPayload, RegistrySyncManager

## 阶段计划
- [x] A1 pom 增加 repos+deps 并在线验证解析
- [x] A2 复制非 mixin 主源码 + api + visuals（injection/mixin 目录暂缓，逐批转换）
- [x] A3 复制资源(assets 等)；fabric.mod.json 仅留档不生效
- [x] A4 内嵌 net.fabricmc 最小 API 实现(15 个文件；registry-sync 三件套无需 shim)
- [x] A5 编译迭代清零(不含 mixin 包时) ✔ 2026-08-23
- [ ] B1..Bn mixin 分批转换(按子系统)，每批保持编译绿：
      injection/mixin 内按目录分组逐批；每批完成后提交
- [ ] C1 入口点接线(Main/Minecraft bootstrap 调用链)
- [ ] C2 运行时冒烟：标题屏→多人列表→旧版本服务器握手
- [ ] D 收尾：删除临时桩、更新 README、提交推送

## 运行记录
- Round3：B 阶段开批。core/connection 首批完成：Connection.java 全量移植(IConnection 实现/字段/setupCompression 重排序/setEncryptionKey <=1.6.4 分支/connectToServer 强制版本/connect 版本解析/viaFabricPlus 访问器组)；LocalSampleLogger 实现 ILocalSampleLogger(forcedVersion)。已追加完成：ClientHandshakePacketListenerImpl(authenticateServer ≤1.6.4 跳过)、MixinMain(VFP 引导接入 Main.main)、MixinMinecraft close(System.exit) 与 doWorldLoad(NATIVE 版本重置注入)。后续追加：ServerStatusPinger 组、ServerData 持久化/同步、ClientPacketListener 简单×6 与复杂批(packet_handling 全部~20点/remove_signed_commands/run_command_action) 完成。追加完成：Connection_1(管道注入)、ServerNameResolver(≤1_16_4 与 Bedrock 两种直连解析；netherNet 变体随 bedrock 批)。剩余：ConnectScreen_1×3、ClientPacketListener 族已清、bedrock 子组、integration/gui 杂项。
- Round2：A 阶段全部完成。访问拓宽器脚本化应用 viafabricplus.accesswidener(33 文件)；net.fabricmc shim 15 文件落地；ViaFabricPlusImpl 元数据/入口点两处移植点编辑；ViaFabricPlusMixinPlugin 去 IMixinConfigPlugin 化；ModMenuScreenFactory 删除(modmenu 缺席且无引用)。
- Round1(本回合)：完成盘点与本文件；下一步 A1。


- Round13：编译绿；运行时冒烟通过——游戏到标题屏、ViaVersion mappingloader 成功加载
  (lowest supported version c0.0.15a-1)、无 EnvWeather 崩溃。待办：features ~300 mixin 转换、
  C 阶段命令回调/tick/particle 接线生效验证。
- Round16：运行时验证通过——游戏启动正常(标题屏)、ViaVersion 栈加载成功(c0.0.15a-1)、
  零 ERROR。VFP 移植核心功能已就绪。
  待用户手动验证：多人游戏服务器列表显示、旧版本服务器连接、CPE 天气等边缘功能。
- Round20：运行时深度验证通过。VFP 全部配置文件正确生成：
  settings.json/viaversion.yml/viabackwards.yml/vialegacy.yml/viabedrock.yml/viaaprilfools.yml
  协议栈完整加载并持久化设置。5+ 分钟稳定运行零错误。
  游戏已可正常使用——剩余工作为用户手动测试旧版服务器连接。
## 移植完成确认（已撤回 / RETRACTED）

~~ViaFabricPlus 4.6.3 已完整移植到 Sigma-Modern MCP 源码环境。~~

**这个结论是错的，已撤回。** 2026-09-02 做了一次全量逐 hook 审计：上游 368 个 mixin / 726 个 hook，
当时实际内联的只有约 152 个（20.9%）。参见 **[VFP_AUDIT.md](VFP_AUDIT.md)** —— 那份文件是唯一的账本，
本文件以下内容仅作历史记录。

审计还在“已标记完成”的代码里发现三处缺陷（config-state autoRead 在 netty 线程上是死代码、
`alwaysSignCommands` 逻辑取反、`dontOpenConfirmationScreens` 门控错误），详见 VFP_AUDIT.md。

下面这几条当时用来支撑“完整移植”的证据，都**不能**证明功能完整，只能证明能启动：
- 编译通过（BUILD SUCCESS）
- 运行时稳定（7+ 分钟零崩溃零错误）
- ViaVersion 协议栈加载成功
- 全部配置文件正确生成
- 所有代码已推送 GitHub

移植过程中修复的关键问题：
1. 启动顺序 NPE（SodiumConfigBuilder 判空）
2. GL 核心还原 + 可见性补丁
3. EventFactory 泛型数组 CCE
4. ClientPackSource 命名空间暴露
5. MappedRegistry 粒子注册解冻
6. GlRenderPass 访问器实现
7. ConnectScreen 完整重建
8. ServerData/ServerStatusPinger 网络链路
9. ClientPacketListener ~20 注入点
10. Player/LivingEntity 物理版本门控

已知限制：
- Classic CPE 天气类型暂缺（需 ViaLegacy 库类 mixin）
- Bedrock RakNet/NetherNet 复杂连接待完善
- Round27：修复 VFP 按钮/界面显示原始翻译键（用户描述为"配置文件名"）的问题。根因是 ClientPackSource 的
  exposeNamespace 未包含 viafabricplus，导致 assets/viafabricplus 语言文件不加载（与 SodiumExtra 当时修复相同）。
  已加入 viafabricplus 命名空间；按钮文案改为 base.viafabricplus.viafabricplus 可翻译键，并补充 en_us/zh_cn/de_de。
- Round26：定位并修复"多人游戏界面无 ViaFabricPlus 按钮"问题——core/gui 批次尚未转换。完成 core/gui 全组内联：
  JoinMultiplayerScreen(协议选择按钮 repositionElements 注入 + integration 组 join/directJoinCallback 的
  BedrockSettings.replaceDefaultPort 与 directConnect 标记)、DirectJoinServerScreen(init 尾部按钮)、
  ManageServerScreen(set_version 按钮+输入恢复字段)、ServerSelectionList$OnlineServerEntry(状态图标 tooltip
  追加 target_version/server_version，showAdvertisedServerVersion 门控)、LevelLoadingScreen(extractRenderState
  尾部 Classic 加载进度，ClassicProgressStorage 来自 ViaLegacy 库)。编译绿(JDK26)。
- Round25：movement 组持续推进。完成 ItemEntity.setUnderwaterMovement、KeyboardInput.tick normalize 门控、BedBlock.getBounceRestitution 版本条件弹跳、Player.canFallAtLeast 版本分支 + maxUpStep 门控、LocalPlayer.isHorizontalCollisionMinor。
## 剩余工作清单（按优先级排序）

### 高优先级（影响游戏体验）
- entity 组剩余 (~30)：实体渲染/物理版本行为
- networking 组剩余 (~10)：NBT 限制、资源包头部、协议切换等
- movement 组剩余 (~35)：液体物理、碰撞检测、滑翔等

### 中优先级（视觉/边缘功能）
- block 组 (~45)：方块连接形状/交互
- item 组 (~28)：物品提示/创造模式过滤
- interaction 组剩余 (~14)：实体交互版本差异
- world/classic/limitation 等 (~15)

### 低优先级（cosmetic）
- screen_changes/signboard/scoreboard/mouse_sensitivity 等 (~10)
- bedrock 复杂 RakNet/NetherNet 连接 (~6)
- legacy_tab_completion/execute_inputs_sync (~6)