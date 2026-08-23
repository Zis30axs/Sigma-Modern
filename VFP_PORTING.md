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
- Round3：B 阶段开批。core/connection 首批完成：Connection.java 全量移植(IConnection 实现/字段/setupCompression 重排序/setEncryptionKey <=1.6.4 分支/connectToServer 强制版本/connect 版本解析/viaFabricPlus 访问器组)；LocalSampleLogger 实现 ILocalSampleLogger(forcedVersion)。已追加完成：ClientHandshakePacketListenerImpl(authenticateServer ≤1.6.4 跳过)、MixinMain(VFP 引导接入 Main.main)、MixinMinecraft close(System.exit) 与 doWorldLoad(NATIVE 版本重置注入)。当前剩余：Connection_1、ServerStatusPinger 组、ServerNameResolver 组、ConnectScreen_1 组、ClientPacketListener 族、bedrock 子组、integration/gui 杂项。
- Round2：A 阶段全部完成。访问拓宽器脚本化应用 viafabricplus.accesswidener(33 文件)；net.fabricmc shim 15 文件落地；ViaFabricPlusImpl 元数据/入口点两处移植点编辑；ViaFabricPlusMixinPlugin 去 IMixinConfigPlugin 化；ModMenuScreenFactory 删除(modmenu 缺席且无引用)。
- Round1(本回合)：完成盘点与本文件；下一步 A1。
