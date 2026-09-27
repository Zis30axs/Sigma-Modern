# SELFCHECK_PORTING.md — 内嵌真 Grim 的 SelfDetection（自检）

日期：2026-09-26
上游：GrimAnticheat/Grim `8eb5f28`（2026-09-09，GPL-3.0）· PacketEvents `2.13.1+4d40422-SNAPSHOT`（GPL-3.0）
入口：`com.mentalfrostbyte.jello.module.impl.misc.ModuleSelfDetection`，宿主在 `com.mentalfrostbyte.jello.selfcheck.*`

与 `GRIM_PORTING.md` 的区别：`ModuleAntiCheat` 旁观**别人**，是按 Grim 思路重写的，不含 Grim 代码。
`ModuleSelfDetection` 检测**自己**，内嵌的是真正的 `ac.grim.grimac` 和 PacketEvents。它吃的是线上字节，
也就是服务器上的 Grim 看到的那一份。

## 怎么接到包

```
S 包: 网络 → 解密 → 拆帧 → 解压 → [selfcheck_in 复制] → via-decoder → … → decoder → packet_handler(EventReceivePacket)
C 包: EventSendPacket → encoder → via-encoder → [selfcheck_out 复制] → 压缩 → 加密 → 网络
```

- 两个分流点拿到的都是目标服务器版本的字节。S 包取在被模块取消之前，C 包取在所有模块改动之后。
- 事务（Grim 的 ping/pong）走带内注入：注入真实的 `ClientboundPingPacket`，id 在 `0x6000–0x7FFF`；
  客户端回的 pong 在 encoder 之前截下，不会发到服务器。
- 每个连接用一个 child-first 类加载器，因为 Grim 把服务器版本写死在约 23 个 `static final` 里。

## Phase 0 门槛（2026-09-26，全部通过）

| # | 门槛 | 结果 |
|---|---|---|
| 1 | 构件可取 | `https://repo.grim.ac/snapshots` 上有 PE `2.13.1+4d40422-SNAPSHOT`（api、netty-common），以及 `GrimAPI`/`grim-internal`/`grim-internal-shims` `1.6.0.12`。`maven.grim.ac/public/releases` 只有 1.5.0.5，不够用 |
| 2 | Lombok + JDK 26 | Lombok 1.18.48 用 `-proc:full` 加 processorpath，Grim common 全部 559 个文件编译通过（约 36 s）。只有 `sun.misc.Unsafe` 弃用警告。编译期需要 jsr305；Geyser 的 base-api 需要 cumulus，否则类文件报错会让 Lombok 根本不运行 |
| 3 | PE 跑在客户端的 netty 4.2.15 上 | 在 1.8.8、1.16.5、1.21.4、26.2 上，ping/window-confirmation、实体速度、pong、位置包都能完整往返。PE 的 POM **没有声明任何依赖**，必须自己补上 adventure-api/nbt/key/gson/json-legacy-impl/legacy/plain 和 option |
| 4 | 隔离类加载器 | 同一个 JVM 里三个 child-first 加载器（26.2、1.8.8、26.2）互不干扰，各自保留自己的服务器版本。PE `load()+init()` 冷启动约 3–4 s，热启动约 2.4 s，主要花在映射表上，所以要在后台线程、建连时就开始 |
| 5 | PE 事件入口 | 用 `NettyPacketEventsBuilder.buildNoCache(...)` 搭一个不注入任何东西的平台，再用 `PacketEventsImplHelper.handleClientBoundPacket/handleServerBoundPacket(channel, user, null, buf, **true**)` 喂包。**必须传 `true`**：`EventManager.callEvent` 只调用 `isPreVia() == !autoProtocolTranslation` 的监听器，而 Grim 的监听器都不是 preVia。`tasksAfterSend` 由调用方执行。这两个方法在需要重编码时会改写传入的缓冲区，所以引擎要拿自己的拷贝 |
| 6 | 告警格式 | adventure 4.26.1 默认的 `GsonComponentSerializer.gson()` 就输出 `click_event`/`hover_event`，26.2 的 `ComponentSerialization.CODEC` 能正确解析，悬停和点击都在 |

Phase 0 发现、要带进实现的事项：
- Grim 告警里的点击是 `run_command:/grim …`。本地告警必须把它改写成本地前缀，否则点一下就会把 `/grim` 发给真服务器。
- ViaVersion 的 `ViaChannelInitializer.reorderPipeline` 可能把 via-decoder/via-encoder 挪到紧挨解压/压缩的位置。
  挪完之后分流点必须重新放回原位，否则看到的是 26.2 的字节。钩子挂在 `Connection.setupCompression` 的重排之后。

## 验收

对照服：`mc.loyisa.cn`（用户指定的反作弊测试服），不自建服务器。它的 Grim 版本和配置未知，只能做近似对照，差异记在下面。

## 宿主（Phase 1，`selfcheck/api` + `selfcheck/host`）

| 部件 | 做什么 |
|---|---|
| `api/*` | 引擎 SPI，只依赖 JDK 和 netty 的 `ByteBuf`，由父加载器共享 |
| `SelfCheckPipeline` | 四个无状态、`@Sharable` 的 handler。`reanchor` 在 Via 重排后把它们放回原位（netty 不允许重新添加非 Sharable 的 handler，这一点是测试抓出来的） |
| `SelfCheckSession` | 按线上全序分发；引擎就绪前缓存（上限 32 MB）并按原顺序排空；事务支持 BEFORE/AFTER/NOW 三种放置；故障分数每次失败 +1、每次成功 −0.05，到 10 关闭该引擎 |
| `SelfCheckPing`/`SelfCheckPong` | 原版 ping/pong 的子类，靠类型而不是 id 区分归属，所以服务器恰好用了同一个 id 也不会误截。本地的 pong 在任何情况下都不会发出去，包括会话已经结束之后 |
| `TransactionPool` | `0x6000–0x7FFF`：16 位、window 0，适配 ≤1.16.4 的 window confirmation |
| `EngineLoader` | 内置引擎用 child-first 的 `Isolating` 加载器，每个连接一份；插件 jar 只认它自己 `META-INF/services` 里列出的工厂，并且拒绝在自己加载器之外解析出来的类。实例化用 `Class.forName`，和 ServiceLoader 的约定相同，只是限定了来源 |
| `ServerRoot` | `plugins/<id>/`、`plugins/*.jar`；`logs/latest.log` 像服务端一样轮转成 `yyyy-MM-dd-N.log.gz`；`recordings/` |
| `LocalCommands` | 只截取已注册的命令根；没有会话时只认 `grim`，普通的 `.xxx` 聊天照常发出 |
| `Recording`/`Replay` | 二进制录制；回放时按序号把引擎自己发出的第 n 个事务对应到录制里的第 n 个应答 |

原版改动：`PacketDecoder.protocol()`（`// Sigma hook:`，只读访问器），用来判断当前阶段能不能注入 ping。

**给今后的模块定的规矩**：扣住、延迟、重发 pong 的模块（PingSpoof、Disabler、Blink 一类）必须**持有原来那个包对象**再发出去，不能 `new ServerboundPongPacket(sameId)` 重新造一个。本地 pong 靠 `SelfCheckPong` 这个类型识别，重新造的普通 pong 会被当成服务器的应答发出去，服务器会收到一个它从没发过的 id。

测试：`src/test/java/.../selfcheck/*`，35 个全部通过（用真正的 `PacketDecoder` 加自定义 `ProtocolInfo`，不需要引导游戏）。

## 实机：mc.loyisa.cn（2026-09-26，1.8.x，诊断引擎，约 60 s）

- 连接正常，没有被踢。引擎在建连后 3.04 s 就绪（诊断引擎故意等 3 s），此前只缓存了 9 个包（0 KB），因为登录和加密握手比它慢。
- 事务通道：发出 3560，应答 3558，丢弃 0，在途 2。往返平均 15–20 ms（主线程处理延迟），最大 1.4 s（进服加载区块时）。
- **下行量**：进 PLAY 后头 10 s 就有约 30 MB（解压后）。Grim 的启动（PE 2.4–4 s 加上 Grim 自己的初始化）如果赶不上登录，32 MB 的缓存上限会被顶到。Phase 2 实测 Grim 的启动时间后再决定：提高上限、分片排空，或者为当前选中的版本预热一个加载器。
- 离线用 PE 从握手解码整段录制：24,662 个包，**0 错误**，状态 HANDSHAKING → LOGIN → PLAY（正版服，包含加密握手）。
- **服务器自己的事务**：loyisa 在 window 0 发了 3081 个 window confirmation，action 分布在 **−32768…23608**，几乎覆盖整个 16 位空间，而不是只用负数。客户端回了 3076 个，线上**没有**任何我们的事务 id。
  - 结论 1：按类型（`SelfCheckPing`/`SelfCheckPong`）判断归属是必要的，按 id 迟早会撞上。
  - 结论 2：Grim 适配层必须把所有不属于我们的 window-0 确认包，以及所有 ping/pong，都对本地 Grim 隐藏，不能只过滤负数 id。
- 旧版本的空档：目标低于 1.20.2 时，客户端解码器处在 Via 模拟出来的 CONFIGURATION 阶段，而线上已经是 PLAY。这时注入 ping 会越过 Via 缓存着的 play 包，所以这些版本只在 PLAY 阶段注入（`pingsAllowedInConfiguration`）。VFP 在 ≤1.16.4 拒绝应答时，由 `handlePing` 钩子把该事务报为丢弃（`SelfCheck.refused`）。
- 录制：`run/sigma5/selfcheck/recordings/2026-09-26_16.16.42_mc.loyisa.cn_25565.sgsc`（34 MB，Phase 2 的回放样本）。

## 测试用的移动模块：`Speed`（移植自 LiquidBounce，GPL-3.0）

来源：LiquidBounce `nextgen` 的 `ModuleSpeed` / `SpeedGeneric.kt`，strafe 计算取自 `EntityExtensions.kt`。只移植两种模式，正好落在反作弊判定线的两侧：
- `LEGIT_HOP`：在地面上移动时替玩家按住跳跃。这是真按空格也能做到的，正确的反作弊应该不报。
- `YPORT`：每次起跳把水平速度设成 `Speed`（默认 0.4），腾空时把竖直速度设成 −1。原版移动做不到，Grim 的 Simulation 应该报。

为此加了两个钩子：
- `EventJump` 在起跳冲量加上之后再以 POST 触发一次（沿用 `Event` "同一实例触发两次" 的设计）；
- 新增 `EventMovementInput`（`LocalPlayer.aiStep` 里 `input.tick()` 之后），模块可以替玩家按下跳跃或潜行。

没有移植：LiquidBounce 的其他模式（大多针对特定反作弊）、防撞墙角、与 Criticals 的协调、仅战斗中/仅药水时生效。

## Grim 移植（Phase 2）

### 源码
- `src/main/java/ac/grim/grimac/**` 取自 common @ `8eb5f28`，共 554 个文件，保留包名。资源保持 Grim 原来的路径（`config/`、`punishments/`、`messages/`、`database(s)/`、`discord/`、`grimac.properties`）：
  客户端里没有同名资源，所以 Grim 的资源读取一行都不用改（计划里原本想加 `grimac/` 前缀，没有必要）。
- 没有移植：`GrimSpectate`、`GrimStopSpectating`、`GrimHistoryCopy`、`GrimHistoryMigrate`、`platform/luckperms/**`。
- 依赖（`pom.xml`）：PE api + netty-common、GrimAPI/grim-internal（`repo.grim.ac/snapshots`）、cloud-core、configuralize(slim)、snakeyaml、adventure（api/nbt/minimessage/gson/json-legacy-impl/legacy/plain）、HikariCP、sqlite-jdbc；
  mongodb 只在编译期用（`provided`）；lombok 1.18.48 通过 `annotationProcessorPaths` 加入，是唯一运行的注解处理器。
- 整个仓库全量编译约 2.5 分钟。

### `// MODIFIED for porting` 清单
| 位置 | 改动 | 原因 |
|---|---|---|
| `Platform` / `GrimAPI.detectPlatform` | 新增 `SIGMA` 平台 | 客户端里 Fabric/Bukkit 的探测都答不上来 |
| `ViaVersionUtil.isAvailable` | 恒为 false | 客户端里的 Via 属于 VFP，不是服务器装的插件 |
| `ViaBackwardsManager` | 不再设置 JVM 全局属性 `com.viaversion.handlePingsAsInvAcknowledgements` | 这个属性会改变 VFP 对玩家自己连接的翻译 |
| `UpdateChecker` | 从不联网 | 内嵌的 Grim 跟随客户端更新 |
| `CloudCommandService` | 不注册 4 个未移植的指令 | — |
| `GeyserUtil` | 恒为"不是基岩版" | 只判本地 Java 版玩家；同时去掉了 Geyser/Floodgate 依赖 |
| `GrimPlayer.resyncHandler` / `reload` | 永远用 NoOp，不管配置怎么写 | 默认的重同步会读服务器世界并写给客户端 |
| `GrimPlayer.disconnect` | 只调用 `SigmaGrim.wouldDisconnect`，报告一次，不关连接，之后继续判定 | 踢人只报告不执行 |
| `SetbackTeleportUtil.blockMovementsUntilResync` | 报告后直接返回，任何状态都不碰 | 否则会传送真实客户端，并永远等一个不会来的传送确认 |
| `SetbackTeleportUtil.sendSetback` | 报告，并把回拉标记为已完成 | 玩家无视传送时 Grim 会重发回拉，不能因此卡住 |
| `CompensatedInventory` 的全部读取方法 | 永远用包推出来的物品栏 | 平台物品栏在客户端只能是客户端自己的，作弊可以改 |
| `CorrectingPlayerInventoryStorage` | 不再按服务器物品栏纠正 | 同上；服务器下发的 SetSlot 仍会纠正 |
| `GhostBlockMitigation` | 视为允许 | 需要服务器世界 |
| `PacketPluginMessage`（vv:proxy_details） | 不关连接 | 前面发出的断开包已经变成报告 |

### 平台层（`selfcheck/engine/grim`，每个连接由隔离加载器加载）
- `GrimEngine`：
  - PE 的 User 是一个 `EmbeddedChannel`，但它的 `eventLoop()` 返回真实连接的 network 线程，所以 Grim 的 `runSafely`/`schedule` 和服务端一样落在网络线程上。
    构造期间要用它自己的 loop，否则注册会断言失败；判断"构造完成"的标志位不能是 `final`，常量字段会被内联。
  - 每个包分发两遍：C 包先 preVia 再 postVia，S 包先 postVia 再 preVia。PE 只会调用 `isPreVia()==!autoProtocolTranslation` 的监听器，而 Grim 的 `PreViaCheckManagerListener` 必须运行；另外补注册了 preVia 的 `InternalPacketListener`。
  - JOIN_GAME 之后触发 `UserLoginEvent`（原本由平台注入器触发，这里没有注入器）。
- 事务：
  - Grim 自己的 ping 在"被写出"时就要触发一次发送事件，因为 Grim 靠 `PacketPingListener.onSendTransaction` 计数；
  - 线上的 ping/pong，以及 window 0 的确认包，一律对本地 Grim 隐藏；
  - 丢弃的事务当场替客户端应答，以免 Grim 的时钟卡住、最后超时"踢人"；
  - 从其他线程写出的包先切到网络线程，和 netty 的做法一致。
- 权限采用**白名单**：只允许指令权限和 alerts/verbose/brand 开关，其余一律拒绝。第一版用的是黑名单，漏掉了 `grim.disabled`，Grim 因此直接关掉了对玩家的判定；这个问题是靠"正面信号"抓出来的。
- 正面信号：订阅 `CompletePredictionEvent` 统计预测次数和最大偏差，每 30 s 写一行日志；结束时再写一行玩家状态（传送是否确认、回拉、事务收发、`disableGrim`……），预测数为 0 时先看这一行。
- 告警里的点击 `/grim …` 会改写成本地前缀的"建议输入"，点击后不会把命令发给服务器。

### 引擎就绪之前：扣住，而不是事后补
第一版是"服务器的包照常交给客户端，另外存一份拷贝，引擎就绪后补给引擎"。实机结果（loyisa）：进服过程中的 2199 个包（30 MB）全被补发。
Grim 为传送包包的事务只能在事后当场补上，时机全错；补发的一大批移动包在 Timer 看来就是加速。结果头一秒有 117 次误报，之后 Grim 卡死不再预测。
现在的做法是：就绪前服务器的包连客户端也不给，就绪后按原顺序"现场"放行；引擎并行启动；超过 15 s 就放弃并放行。实测扣住 3 个包、等 3 s。

**登录阶段例外**（2026-09-27，连本地离线服时发现）：
- 登录阶段的包照常交给客户端，只给引擎存一份拷贝，就绪后按原顺序补上。
- 原因是登录会改连接自己的管线。服务器发出"开启压缩"后，紧接着就发压缩过的包，不等客户端回应。
- 分流点在解压器后面。在这里扣住"开启压缩"，客户端就装不上解压器，后面的包以压缩形态被扣下；放行时它们也不会再经过解压器，于是解码失败：`login_disconnect ... found 41 bytes extra`，连接断开。
- loyisa 是正版服，加密握手比 Grim 启动慢，所以一直没暴露。本地离线服登录只要几毫秒，一连就出错。
- 登录阶段没有移动、也没有事务，补给引擎和现场看到是等价的。判断依据是客户端解码器当前所处的阶段（`PacketDecoder.protocol()`）。一旦扣住过一个包，后面的就全部扣住，保证顺序不乱。
- 回归测试：`SelfCheckSessionTest.theLoginGoesStraightThroughAndTheEnginesSeeItOnceReady`。在修复前的代码上跑，这条测试会失败。

### 回放
- 录制格式 2：录制时宿主在**每个服务器包后面**注入一个标记 ping，它的应答位置就是客户端处理完这个包的准确时刻。
  回放时，引擎放在第 i 个包前面的事务按第 i−1 个标记的应答位置应答，放在后面的按第 i 个标记的位置应答，精确到单个包。
  格式 1 只能按读取批次对齐，粗到一个 tick：会把"传送包前面的事务"答到位置确认包之后，Grim 就会一直等传送确认。这正是第一次回放预测数为 0 的原因之一。
- 实时回放：Grim 的 Timer 类检测读的是真实时钟，全速回放会把 70 s 压成 6 s，看起来就像加速作弊。`GrimReplayTest` 用实时模式。
- `GrimReplayTest`（需要本地有录制，没有就跳过）用 2026-09-26 17:33 的 loyisa 录制（1.8，站立约 70 s）：
  **69 次预测，最大偏差 0.00000**；事务 1358/1358 全部应答；flag 为 1 次 Timer 加 74 次 TimerLimit，全部集中在进服的头几秒。
  原因待实机确认：26.2 客户端在加载地形的界面上不发移动包，而真正的 1.8 客户端会发。这可能是 VFP 模拟 1.8 的真实差异。

## 实机对照：mc.loyisa.cn 服务器上的真 GrimAC（2026-09-26，1.8.x）

loyisa 自己装了 GrimAC，并把告警广播给玩家，所以录制里的聊天包就是服务器上 Grim 的判决。下面是同一段流量，服务器 Grim 与本地 SelfDetection 的对照：

| 场景 | 服务器 GrimAC | 本地 SelfDetection（默认配置） |
|---|---|---|
| 站立（17:33，回放） | TimerLimit 76（全部在进服时） | TimerLimit 74 + Timer 1（进服时）；69 次预测，最大偏差 0.00000 |
| `Speed` YPort 0.4，按住 W 10 s（17:59） | **Simulation 101**；TimerLimit 53（进服时）；服务器回拉 103 次 | **Simulation 102**；TimerLimit 47 + Timer 1（进服时）；AntiKB 1；183 次预测，最大偏差 0.387 |
| `Speed` LegitHop，按住 W 10 s（18:01） | **Simulation 0**；Timer 20 + TimerLimit 31 + TransactionOrder 1（进服时） | **Simulation 0**；TimerLimit 47 + Timer 1（进服时）；146 次预测，最大偏差 0.00000 |

结论：
- 本地的判定与服务器的真 Grim 在数量级和时间段上都一致，违规（YPort）与合法（LegitHop）两个方向都对。
- **进服时的 TimerLimit 是真实的**：服务器上的 Grim 也报了。原因是 26.2 客户端在加载地形界面上不发移动包，而 1.8 原版客户端会发。
  这是 VFP 的 1.8 模拟与真 1.8 之间的差异；只要连 Grim 服就会有这批误报，可以在 VFP 这一侧修（另开任务）。
- 服务器报的 TransactionOrder 1 次本地没有复现。本地事务几乎零延迟，这类由网络时序导致的检测不会一模一样。

## 状态

- Phase 0：通过（上表）。
- Phase 1（宿主 + 录制/回放）：完成。游戏内钩子已接上：`Connection.connect`（仅 ConnectScreen 的进服连接）、`handlePing`、`ClientPacketListener.sendChat` 的聊天截取，以及两处 `reorderPipeline` 之后的 `reanchor`。`ModuleSelfDetection` 已注册，并在 loyisa 实机验证过。数据存储的开关测试随 Grim 一起放到 Phase 2。
- Phase 2（Grim 全量移植 + 平台层）：完成。编译通过，回放测试和实机对照都已验证（见上）。
- Phase 3（模块、`.grim` 指令、实机联调）：模块和实机联调已完成。**还没有实机验证**的有：`.grim` 指令（只有单元测试覆盖路由）、Discord webhook、
  `plugins/*.jar` 外部引擎（只有单元测试覆盖加载）、连续进出服务器后隔离加载器的内存回收，以及每包耗时。
