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

## 测试用的战斗与发包模块（移植自 LiquidBounce，GPL-3.0）

来源：LiquidBounce `nextgen` @ `f37f07f`（2026-09-26）。和 `Speed` 一样，每个模块都挑了落在判定线两侧的模式，用来测 SelfDetection 能抓到什么、会不会冤枉合法操作。

| 模块 | 模式 | 做什么 | 预期 |
|---|---|---|---|
| `Criticals`（Combat） | `Jump` | 敌人在攻击范围内时替玩家起跳，下落时出手 | 合法 |
| | `Packet` | 攻击前多发几个移动包，谎称跳起了一点。偏移表是上游的 7 组：Vanilla、NoCheatPlus、Falling、Low、Down、Grim、BlocksMC | 违规 |
| | `NoGround` | 所有移动包都说"没着地" | 违规 |
| `Velocity`（Combat） | `Modify` | 按比例缩放击退；默认横竖都是 0，直接丢掉击退包 | 违规 |
| | `JumpReset` | 疾跑中挨打，下一 tick 起跳（手动也能做到） | 合法 |
| `SuperKnockback`（Combat） | Packet | 攻击前连发 停止/开始/停止/开始 疾跑 | 违规 |
| `NoFall`（Player） | `SpoofGround`、`Packet` | 摔落超过 3 格后谎称着地：前者改原本的移动包，后者每 tick 另发一个 | 违规 |
| `Derp`（Misc） | 视角随机/旋转/抖动 | 只改报给服务器的视角；关掉 `Safe Pitch` 后俯仰角可以超出 ±90° | 越界时违规 |

新增的钩子：
- `EventAttack`：在 `MultiPlayerGameMode.attack` 开头、攻击包之前触发，可以取消。这里发的包会先于攻击包到达服务器。
- `LocalPlayer.wasSprinting()` / `setWasSprinting()`：自己发疾跑指令的模块，用它记下"服务器现在以为的疾跑状态"。

每个模块没移植的内容写在各自的类注释里。`Velocity` 没有移植会扣住 pong 的 `TransactionBuffer`，原因见上面"给今后的模块定的规矩"。

## 实机：本地 26.2 服务器（2026-09-27）

环境：
- 会话的权限策略不允许从容器直连第三方服务器（mc.loyisa.cn），所以这次在本机起了 Mojang 官方的 26.2 服务端 jar（SHA1 已校验），离线模式、超平坦、关闭刷怪。
- 同一份源码不能直接当专用服跑：移植进来的 SodiumExtra 会在服务端的光照线程里访问 `Minecraft.getInstance()`，直接崩溃。
- 客户端用 `--quickPlayMultiplayer` 经 ConnectScreen 进服，SelfDetection 照常挂上。本节的服务器版本是 26.2，loyisa 那边是 1.8；两边的 Grim 在版本相关的阈值上会不同（例如 BadPacketsV 在 ≤1.18 用 0.03）。

场景：
- 全部由服务器控制台驱动：tp 定位，刷一只不动、1000 血的尸壳当靶子；`/damage <玩家> 1 minecraft:mob_attack by <尸壳>` 制造击退；tp 到 10 格高制造摔落；xdotool 模拟点击和按键。
- 跳跃次数用服务器的 `minecraft.custom:minecraft.jump` 计分板核对。
- 每个场景跑 20 s，每组都有不开模块的基线。表里只数动作期间的 flag；进服头几秒的 Timer/TimerLimit 是已知误报（见上），不计入。
- 第一次跑时超平坦世界刷出了史莱姆，推动并攻击玩家，那一轮数据作废。之后关闭了刷怪，每次开场先清掉所有非玩家实体。

结果（Grim 默认配置，以及打开 `experimental-checks` 后的增量）：

| 场景 | 默认配置 | 实验性检查另外报的 |
|---|---|---|
| 站着打（基线）/ 边跳边打（基线）/ 边走边打（基线） | 无 | 无 |
| `Criticals` Jump | 无（46 次自动起跳，587 次预测，最大偏差 0） | 无 |
| `Criticals` Packet NoCheatPlus（Full） | TickTimer 73、Timer 50、Simulation 38（0.11）、GroundSpoof 31、AimDuplicateLook 25、Post 10 | PacketOrderO 50、BadPacketsV 29 |
| `Criticals` Packet NoCheatPlus（Position） | 同上，但没有 AimDuplicateLook：那一项纯粹来自 Full 包里没变的视角 | 同上 |
| `Criticals` Packet Low（1e-9） | TickTimer 49、Timer 26、GroundSpoof 24、AimDuplicateLook 23、Post 9；**Simulation 0**（偏移低于阈值） | BadPacketsV 50、PacketOrderO 50 |
| `Criticals` Packet Grim（空中下压 1e-6） | Simulation 12（最大 0.75）、GroundSpoof 3、TickTimer 2、Timer 2 | PacketOrderO、BadPacketsV |
| `Criticals` NoGround | GroundSpoof 24 | 无 |
| `SuperKnockback`，边走边打 | **无** | BadPacketsX 36、PacketOrderF 18 |
| `SuperKnockback`，站着打（关 Only On Move） | Timer 1、TimerLimit 1 | BadPacketsX 72、PacketOrderF 24 |
| 挨打（基线） | 无 | 无 |
| `Velocity` Modify 0/0 | AntiKB 12，每一下都报（偏差 0.538） | 无 |
| `Velocity` Modify 横向 80% | AntiKB 12（0.079），每一下都报；之后每下约 15 个递减的 Simulation | 无 |
| `Velocity` Modify 横向 95%（只在实验性一轮跑过） | AntiKB 13（0.019），每一下都报；Simulation 169 | — |
| 疾跑挨打（基线） | Simulation 4 | 无 |
| `Velocity` JumpReset | Simulation 4，和基线完全相同；8 次挨打起跳 8 次，基线 0 次 | 无 |
| 摔落（基线） | 无 | 无 |
| `NoFall` SpoofGround | GroundSpoof 21 | 无 |
| `NoFall` Packet | Simulation 61、TickTimer 42、GroundSpoof 28、Timer 25、AimDuplicateLook 14、TimerLimit 7 | BadPacketsV 21 |
| `Derp` 随机（安全范围） | AimModulo360 1 | 无 |
| `Derp` 俯仰 120°（Static，只发一次视角包） | BadPacketsD 1 | 无 |
| `Derp` 随机，关 Safe Pitch（只在实验性一轮跑过） | BadPacketsD 241（开场 148） | — |

两个场景本身的问题，已排除：
- 疾跑中被服务器 tp，基线本身就有 4 个 Simulation，偏差数值和 JumpReset 那一轮逐位相同。
- 第一版"边走边打"每 2 s 才 tp 一次，玩家 0.7 s 后就走过了尸壳，后面的点击大多打空，SuperKnockback 几乎没机会触发；基线同样有 6 个 Simulation。
  改成每次 tp 后 0.25 s 出手，基线就干净了。

结论：
- **AntiKB 有效**：丢掉击退、保留 80%、保留 95%，每一次挨打都被抓到；合法的 JumpReset 没有多出任何 flag。
- **BadPackets**：越界俯仰角（D）在默认配置下就抓；Criticals 的微小位移（V）、SuperKnockback 的一 tick 内重复疾跑指令（X）都是实验性检查。
  Grim 默认配置下，**SuperKnockback 的发包式 W-tap 完全抓不到**；打开实验性检查后每一下都能抓到。
- Criticals 的各种发包模式在默认配置下主要靠移动预测（Simulation）、着地（GroundSpoof）、计时（Timer/TickTimer）和包序（Post）抓到，而不是 BadPackets。
  专门针对 Grim 调的 Grim 模式，在 26.2 上照样被 Simulation 抓：多出来的每个移动包都会被 Grim 当作一个 tick 去模拟。
- 除了上面两个已排除的场景问题，两轮里的基线和合法模式（Jump、JumpReset）都没有被报，打开实验性检查也一样。

## KillAura、FakePlayer 与跨版本实验（2026-09-27）

### 模块
- `KillAura`（Combat，自写，不是移植）：
  - 每 tick 开头选目标、算出这一 tick 的视角；视角射线在攻击距离内碰到目标、且到了该攻击的时候就出手。这些都发生在这一 tick 的移动包之前，和原版点击的顺序一样：先发攻击，瞄准用的视角随同一 tick 的移动包上报。
  - 攻击走 `MultiPlayerGameMode.attack`，所以 `EventAttack` 的模块（Criticals、SuperKnockback）照常配合。挥手顺序按版本：≤1.8 先挥手后攻击。
  - Timing：Auto 在 1.9+ 等冷却满，在 1.8 按随机 CPS（默认 8–12）。
  - Rotation：`None`、`Snap`（连续 yaw）、`Wrapped`（把 atan2 的 -180..180 直接发出去，对照用）、`Smooth`（每 tick 最多转 `Turn Speed`）、`Claude1`（实验性，见下）。
  - `Silent` 只改上报的视角；`Movement Fix` 让按键和起跳加速按上报的朝向推（`EventStrafe`、`EventJump`）。
  - AutoBlock：`Hold`（一直举着、隔着格挡攻击）、`SameTick`（同一 tick 放下、攻击、再举起）、`Claude2`（实验性，见下）。1.8 用剑（ViaFabricPlus 给剑加了格挡组件），其他版本用副手的盾。
- `Claude1` / `Claude2` 是暂定名：
  - `Claude1`：每 tick 走完剩余角度的 60%（至少 3°，横向最多 55°、纵向最多 30°），瞄目标碰撞箱上离眼睛最近的点，再把转角取整到当前鼠标灵敏度的整数步长（`MouseHandler` 的 f³·8·0.15）。
  - `Claude2`：要攻击时如果正在格挡，这一 tick 先放下，不攻击；下一 tick 攻击，并按原版右键的方式重新举起（先对准星上的实体发交互包，再用物品）。这就是玩家手动格挡攻击时的节奏：原版在使用物品的那一 tick 会丢掉攻击点击。
- `FakePlayer`（Misc）：只存在于客户端的假人，四种模式：
  - `Moving`：在圈内随机走动，原版步行速度。
  - `Jumping`：疾跑加原版跳跃弧线。
  - `Flying`：在圈上方的空中飞，用来测俯仰角。
  - `CombatSimulation`：保持在攻击距离、绕着玩家换边横移、偶尔起跳、挥手后后撤（W-tap）。
  - 任何模式都不会离开以出现点为圆心、半径 `Radius` 的圈，被击退也一样；对打时对手走远了，它就守在圈边离对手最近的地方。
  - 打它不发攻击包：在包发出前取消，本地变红、击退，并像真打中一样重置攻击冷却。它不能被推动，否则玩家会被一个服务器不知道的东西推开。
  - 瞄它时发出的视角、格挡、移动都是真的，所以能拿来测 Aim 和移动类检查；需要攻击包的检查（Hitboxes、AutoBlock）仍然用服务器上的真实体。
- 新钩子：
  - `EventStrafe`（`Entity.moveRelative`，只对本地玩家）：改按键推动所用的 yaw。
  - `EventStopUsingItem`（`Minecraft.handleKeybinds`）：右键没按着时原版会松开正在用的物品；取消它就等于一直按着。
  - `EventMotion.forceRotation()`：本 tick 即使视角和客户端记录的相同，也强制上报。

### 实验环境（沿用上一节，另加一台带插件的 1.8 服）
- **Paper 1.8.8**（build 445，JDK 21）+ **ViaVersion 5.12.0** + **GrimAC 2.3.74-8eb5f28**。服务器上的 Grim 和 SelfDetection 内嵌的是同一个提交，所以每个场景都有两份判决可以逐项对照。
  - 服务器打开了 `verbose.print-to-console`：告警要到 `punishments.yml` 的阈值（例如 Simulation 组是 100 VL）才发，逐条对照必须看 verbose。
  - ViaVersion 设置：`show-shield-when-sword-in-hand: true`，让 1.9+ 客户端拿剑时副手有盾可举；`fix-1_21-placement-rotation: false`，这是 Grim 启动时明确要求关掉的，否则会有误报和绕过。
  - 客户端版本用 ViaFabricPlus 切换：1.8.x、1.12.2、26.2。
- **原版 26.2 服**：26.2 客户端直连，只有本地判决。
- 场景：
  - `fake`：玩家站着打 FakePlayer，假人半径 3.5，光环瞄准距离 6。
  - `fake-walk`：边往前走边打，每 1.2 s 传回起点。
  - `side`：不转头、关射线，打正侧面的僵尸。
  - `fight`：一只会还手、不吃击退的僵尸或尸壳，困难难度，玩家加 20 级生命提升。结束时从服务器记分板读血量，看格挡在服务器那边算不算。
  - 每个场景 20 s，默认配置一遍、`experimental-checks: true` 一遍（本地和服务器同时切）。
- 这台 Paper 服踩过的坑：
  - `spawn-monsters=false` 时 summon 会打印"成功"，但怪物实体被 CraftBukkit 直接丢掉。
  - 控制台的 `@e[...]` 选择器不生效，被当成玩家名或 UUID。改成每轮换一块远处的新场地，上一轮的怪留在原地不管。
  - 不认 `--nogui`，要用 `--nojline nogui`。
  - **Paper 1.8.8 自带 Netty 4.0**，ViaVersion 5 给 26.2 客户端翻译僵尸、蜘蛛等怪物时会调用 `ByteBuf.writeShortLE`（Netty 4.1 才有），服务器随即把玩家踢掉。猪没问题。所以"26.2 客户端在 1.8 服上用 AutoBlock"在这个环境里测不了。现实里这种组合要靠带新 Netty 的 1.8 分支（如 PandaSpigot），这个会话拿不到 GitHub 上的构件。
- 脚本层面：
  - quickPlay 会在客户端打开 SelfDetection 之前就开始连服（见"已知问题"）。每轮核对 SelfCheck 日志里有没有本轮的玩家，以及协议版本对不对，不对就重跑，最多三次。
  - 原版服也改成每轮换新场地，让假人出现在玩家面前。第一版原版假人实验里，假人离玩家 5–11 格，光环多数时间根本没锁定，那一批数据作废。

### 结果：转头（打 FakePlayer，默认配置，本地 / 服务器端）
| Rotation | Moving | Jumping | Flying | CombatSimulation |
|---|---|---|---|---|
| Snap | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Wrapped（1.8.x @Paper） | AimModulo360 16 / 16 | 20 / 20 | 27 / 27 | 15 / 15 |
| Wrapped（原版 26.2） | AimModulo360 20 | 28 | 23 | 16 |
| Smooth | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Claude1 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

- 另有零星的单次 GroundSpoof、TimerLimit 等，只在本地出现、服务器端没有对应，没有计入。
- 1.12.2 和 26.2 客户端经 ViaVersion 连 1.8 服，打 CombatSimulation：Wrapped 两边都是 AimModulo360 15 / 15，Claude1 都是 0 / 0。
- **AimModulo360 什么时候触发**，规则见 `AimModulo360.java`：
  - yaw 绝对值小于 360；
  - 这一 tick 的 yaw 变化超过 320°；
  - 上一 tick 的变化小于 30°。
- 发 atan2 算出的 -180..180 yaw 的光环，在目标跨过玩家正北（±180° 那条线）的那一 tick，yaw 会从 179 跳到 -179，差 358°，正好满足。跨线越频繁报得越多，所以飞行、跳跃这类到处跑的目标报得最多。
- 只要把 yaw 保持连续（`上次上报的 yaw + wrapDegrees(目标 − 上次)`），Snap 这种瞬间转头也不会触发。
- 另一种触发方式也验证过：玩家先把自己的视角转两圈（yaw≈900），光环再用 Wrapped 锁定时，第一 tick 从 900 跳到 180 左右，同样会报。
- 单元测试 `KillAuraTest` 用同一条规则复现了"Wrapped 每次跨线都报、连续 yaw 一次都不报"。

### 结果：Hitboxes 与移动修正（默认配置，本地 / 服务器端）
| 场景 | 1.8.x @Paper | 原版 26.2 |
|---|---|---|
| 不转头、关射线，打正侧面的僵尸 | Hitboxes 187 / 187（几乎每一刀） | Hitboxes 36 |
| 静默转头边走边打，**关**移动修正 | Simulation 268 + AntiKB 146 / Simulation 137 | Simulation 417 |
| 静默转头边走边打，开修正（Snap / Claude1） | 0 / 0 | 0 |

原版 26.2 打开实验性检查那一遍，开修正的两轮出现过 Simulation 19 / 35，同时伴有 Timer 6 / 13 和 TimerLimit：那一轮有卡顿（新场地离出生点很远，要现生成区块）。这两个场景各复测两次，都是 0。

### 结果：AutoBlock
对打场景：一只会还手、不吃击退的怪，20 s。

检测（本地 / 服务器端，打开 `experimental-checks` 之后；**默认配置下四种模式在所有版本上都是 0**）：

| AutoBlock | 1.8.x @Paper | 1.12.2 @Paper | 原版 26.2（盾） |
|---|---|---|---|
| None | 0 / 0 | 0 / 0 | 0 |
| Hold | MultiActionsA 188 + MultiActionsE 188 / 同 | MultiActionsA 345 + E 345 + PacketOrderJ 29 / A 239 + E 239 + J 29 | MultiActionsA 35 + E 35 + PacketOrderJ 1 |
| SameTick | PacketOrderI 376 + PacketOrderJ 188 / 同 | PacketOrderI 268 + J 136 / 同 | PacketOrderI 70 + J 36 |
| Claude2 | 0 / 0 | 0 / 0 | 0 |

- Hold：原版在使用物品时会丢掉攻击点击，所以"格挡中攻击"（MultiActionsA）和"格挡中挥手"（MultiActionsE）原版客户端发不出来。
- SameTick：同一 tick 里"放下 → 攻击 → 举起"，原版也做不到（放下的那一 tick 攻击点击同样被丢掉），PacketOrderI 抓的就是这个包序；直接用物品、前面没有对实体的交互包，PacketOrderJ 也会报。
- Claude2：把放下和攻击拆到两个 tick，再举起时先发实体交互包，包序和原版右键一致，两遍、三个版本都没有任何 flag。

减伤（`fight`，玩家 104 血，20 s 内掉的血）：
- 原版 26.2 盾牌：None 58–61；Hold 0；SameTick 1；Claude2 5–7（每次攻击前放下的那一 tick 会漏一点）。
- 1.8 剑挡（1.8.x @Paper，每种 3 轮；单轮噪声很大，有两轮僵尸根本没打到人，所以重复取平均）：

| 情况 | 3 轮掉血 | 平均 | 相对不挡 |
|---|---|---|---|
| 光环不格挡 | 85 / 90 / 85 | 87 | — |
| 原版手动，不举剑 | 80 / 86 / 80 | 82 | — |
| 原版手动，一直按右键举剑、不攻击 | 49 / 50 / 50 | 50 | −40% |
| Hold | 76 / 69 / 79 | 75 | −14% |
| SameTick | 49 / 52 / 47 | 49 | −44% |
| Claude2 | 57 / 58 / 60 | 58 | −33% |

  - 实验方法本身是准的：手动举剑减伤 40%，1.8 的剑挡公式是 (1+伤害)/2，每下 4.5 变 2.75，约 −39%。
  - Hold 在 1.8 上基本不减伤：两边的 Grim 都认为玩家一直在用物品，但隔着格挡攻击之后，服务器的伤害计算不再把它当格挡。具体机制没有细查，大概率是 Paper 在攻击这条路径上清掉了使用状态。
  - SameTick 每次攻击后当 tick 就重新举起，格挡几乎不间断，效果和手动举剑一样。
  - Claude2 在攻击前那一 tick 放下，那一瞬间挨的打会漏过去，所以挡住约四分之三。
  - 1.12.2 客户端经 ViaVersion 只跑了单轮，趋势相同（None 85、Hold 60–73、SameTick 52–54、Claude2 63–71）。

结论：
- 用 Grim 默认配置的服务器看不到任何一种 AutoBlock。
- 打开实验性检查后，Hold 和 SameTick 每一刀都会被抓；Claude2 在三个客户端版本上都没有被抓，1.8 上挡住约 3/4 的伤害，盾牌挡住约 9 成。
- 这个结论只针对 GrimAC `8eb5f28`，而且是本地实验环境；其他反作弊或其他配置没有测过。

### 这一轮发现并修掉的问题
- **传送后静默视角没发出去**：
  - 服务器传送时，客户端用确认包上报的是镜头视角，但它记录的"上次上报的视角"并没有更新。
  - 光环的视角恰好等于那个旧记录时，这一 tick 就不发视角，于是服务器按传送的朝向预测移动，玩家却按光环的朝向在走。实测 Claude1 收敛后会出现一串 Simulation。
  - 修复：`EventMotion.forceRotation()`，光环从所有发出去的移动包（包括传送确认）记录服务器实际知道的视角，不一致就强制上报。没有模块使用时，原版行为不变。
- **AutoBlock 每 tick 闪一下**：
  - 原版在右键没按着时会松开正在用的物品。光环在 tick 开头举起格挡，同一 tick 就被原版放下了，服务器看到的格挡一直在闪，伤害照吃不误。
  - 实验性的 PacketOrderI 因此每 tick 都报（20 s 里约 466 次）。
  - 修复：`EventStopUsingItem`。另外，1.8 下剑的 `useItem` 返回 PASS（物品堆没变），是否在格挡改为直接读玩家的状态。
- 实验脚本自身的问题也记在上面的"实验环境"里：史莱姆、选择器、场地位置、版本竞争。

### 已知问题（未修）
- **quickPlay 启动时序**：`Minecraft.onGameLoadFinished` 先执行 `showScreen`（quickPlay 就在这里发起连接），后执行 `Client.start()`（按配置打开模块）。所以用 `--quickPlayMultiplayer` 启动时，SelfDetection 有时会错过第一个服务器。从多人游戏菜单进服不受影响。
  - 正确的修法是把 `Client.start` 拆成"打开模块"和"主菜单跳转"两段，前者放到 `showScreen` 之前。但它是带回滚的事务式启动，改动面较大，这次没动。
- **本地进服阶段的噪声**：本地 SelfDetection 在进服到场景开始之间会报一些 BadPacketsE、Phase、AimDuplicateLook、Timer，服务器端的 Grim 多数没有。这是引擎就绪前扣包、之后集中放行造成的，统计时已排除在外。
- ~~原版药水效果图标会盖住右上角的 ArrayList。~~ 已修：PotionStatus 模块在 SigmaModern 下代替原版图标，画在 ArrayList 上方；模块关闭时 ArrayList 让到原版图标下面（见 `SIGMA_MODERN.md` 的 PotionStatus 一节）。

## 状态

- Phase 0：通过（上表）。
- Phase 1（宿主 + 录制/回放）：完成。游戏内钩子已接上：`Connection.connect`（仅 ConnectScreen 的进服连接）、`handlePing`、`ClientPacketListener.sendChat` 的聊天截取，以及两处 `reorderPipeline` 之后的 `reanchor`。`ModuleSelfDetection` 已注册，并在 loyisa 实机验证过。数据存储的开关测试随 Grim 一起放到 Phase 2。
- Phase 2（Grim 全量移植 + 平台层）：完成。编译通过，回放测试和实机对照都已验证（见上）。
- Phase 3（模块、`.grim` 指令、实机联调）：模块和实机联调已完成。**还没有实机验证**的有：`.grim` 指令（只有单元测试覆盖路由）、Discord webhook、
  `plugins/*.jar` 外部引擎（只有单元测试覆盖加载）、连续进出服务器后隔离加载器的内存回收，以及每包耗时。
- 测试模块（`Criticals`、`Velocity`、`SuperKnockback`、`NoFall`、`Derp`）：已在本地 26.2 服上逐个模式验证（见"实机：本地 26.2 服务器"）。**还没有**在 1.8 的 Grim 服上和服务器自己的 Grim 对照过；这一步需要能连外网的环境，在 loyisa 上照 `Speed` 那一节的方式做。
