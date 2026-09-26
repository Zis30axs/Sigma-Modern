# GRIM_PORTING.md — Grim → 客户端本地 AntiCheat 模块

> 这份文档讲的是**旁观别人**的 `ModuleAntiCheat`，它是按 Grim 思路重写的，不含 Grim 代码。
> 检测**自己**的 `ModuleSelfDetection` 内嵌了真正的 `ac.grim.grimac` 和 PacketEvents，见 `SELFCHECK_PORTING.md`。
> 下文"这里不搬 `ac.grim.grimac`"只针对 AntiCheat 模块而言。

日期: 2026-09-26
参考: GrimAnticheat/Grim `8eb5f28` (2026-09-09, GPL-3.0；本仓库 LICENSE 同为 GPLv3)
入口: `com.mentalfrostbyte.jello.module.impl.misc.ModuleAntiCheat`，逻辑在 `com.mentalfrostbyte.jello.anticheat.*`

## 路线：重写并移植判定思想，不整包移植

Grim 是服务端反作弊：它拿到玩家自己发的每 tick 移动包和服务器插入的传输包，做逐 tick 的 1:1 物理复现。
客户端只能旁观其他玩家，且旁观到的数据是被服务器降采样、量化、带抖动的（下表逐条对照源码核实）。
所以这里不搬 `ac.grim.grimac`（也没有 PacketEvents），而是把 Grim 的**判定思想**（violation 缓冲/衰减、检测分类、
豁免条件、告警格式）搬到客户端能观测到的数据上。

## 客户端能看到什么（源码核对）

| 事实 | 出处 | 结论 |
|---|---|---|
| 玩家 `updateInterval(2)`：位置每 2 server tick 同步一次；实体数据 dirty 会额外触发一次非周期同步 | `EntityTypes.java:1172`、`ServerEntity.java:125,144-175` | 样本间隔不固定，不能逐样本拟合 |
| 服务器汇报的是玩家自己客户端最后一次被接受的位置；网络抖动使两个 server tick 装下 0~4 个玩家物理 tick | 同上 | Speed 必须窗口化并留突发额度 |
| 位置量化：1.9+ 为 1/4096；1.8 为 1/32（ViaVersion 1.8→1.9 只把字节 ×128 换算成短整数） | `EntityPacketRewriter1_9.toNewShort`(`(short)(v*128)`) | 容差是协议版本的函数（`ModuleAntiCheat.checkSettings`） |
| `onGround` 随移动包广播，翻转时服务器发完整位置同步 | `ServerEntity.java:157` | GroundSpoof 有直接数据 |
| 速度只在 `hurtMarked`（击退等）时发给旁观者 | `ServerEntity.java:225-227` | 速度冲量后豁免 1 s |
| 移速/跳跃力/重力**属性**广播给旁观者；药水效果本身只发给自己，但药水粒子是同步实体数据 | `ServerEntity.java:341-352`、`ServerPlayer.java:1657`、`LivingEntity.java:197` | 属性直接读；粒子非空 = "有未知效果"，飞行判定放弃 |
| 相对位移放不进 ±8 格时，服务器改发完整位置同步（末影珍珠、插件传送就是这样到达旁观者的，没有传送包） | `ServerEntity.java:152-160` | 两次报告间隔超过 8 格视为传送，之后 0.2 s 豁免（`ObservedPlayers.SILENT_TELEPORT`） |
| 世界视图领先/落后于玩家所处的世界（方块更新延迟） | — | 附近方块变化后 0.5 s 豁免（对应 Grim 的延迟补偿） |

## 检测对照

| Grim | 本地类 | 状态 | 采用了什么 / 为什么不同 |
|---|---|---|---|
| `checks.impl.prediction.OffsetHandler` ("Simulation") | `SpeedCheck` | DONE（改写） | 采用：每次违规 VL+，干净时衰减（Grim `decay=0.02`，这里按窗口衰减 0.5）。不同：Grim 用逐 tick offset 阈值 `0.001`，旁观者拿不到逐 tick 输入，改为"3 s 窗口内总位移 ≤ 合法最快冲刺跳的均速 × (tick + 突发额度)"，连续两个窗口超标才 flag（Strict 一个） |
| `checks.impl.prediction.OffsetHandler` 垂直部分 | `FlightCheck` | DONE（改写） | 逐 tick 垂直预测换成整段滞空的两条规则：顶点高度（普通跳 ≈1.2522，加 0.6 的台阶容差）和悬停（1.5 s 内高度变化 < 0.6 且无支撑） |
| `checks.impl.groundspoof.NoFall` | `GroundSpoofCheck`（声称在地面） | DONE | 采用：脚下判定盒 0.6 宽、0.001 高，按移动量放宽（`movementThreshold`），并考虑船/潜影贝等硬实体（`isNearHardEntity`）。不同：放宽量随该样本的实际位移取，因为客户端先解算垂直碰撞再横移 |
| `checks.impl.prediction.GroundSpoof` | `GroundSpoofCheck`（否认在地面） | DONE（简化） | Grim 对比预测出的 onGround；这里只抓"站在实心地面上、静止、连续多份报告都声称在空中" |
| `checks.impl.movement.NoSlow` | `NoSlowCheck` | DONE（改写） | 采用：只在实体标志显示"正在使用物品"时判；Grim 要求连续两 tick 偏差才 flag（`flaggedLastTick`）。这里用 16 tick 整窗，首窗口给一次性的惯性额度（冲刺跳后举起物品仍会滑行数格） |
| `Check.violations` / `reward()` / `flag()` | `ViolationBuffer` + `Detector` | DONE | 同样的"flag 加、干净时减、不低于 0"；告警按 (玩家, 检测) 2 s 限频，等级到 `Alert Level` 才播报 |
| `PunishmentManager.handleAlert` | `ModuleAntiCheat.Announcer` | DONE（简化） | 聊天 + 日志 + 名单；不做 setback/kick/命令 |
| `predictionengine.*`（`PredictionEngine*`、`MovementTicker*`、`PlayerBaseTick`…） | — | SKIP | 需要每 tick 的输入与延迟补偿；旁观者没有。上界由 `MovementLimits` 直接按原版规则模拟 |
| `checks.impl.sprint.*`（SprintA–G）、`badpackets`、`packetorder`、`timer`、`aim`、`chat`、`crash`、`exploit`、`misc.*`(Post/TransactionOrder/ClientBrand) | — | SKIP | 全部检查的是客户端发给服务器的包，旁观者看不到 |
| `checks.impl.combat.*`（Reach/Hitbox…）、`scaffolding`、`breaking` | — | TODO（第二期） | 需要挥手/伤害/方块更新事件，10 Hz 位置下误报风险高 |
| `SetbackTeleportUtil`、`GhostBlockDetector`、`PointThreeEstimator`… | — | SKIP | 服务端处置与其内部估算 |
| `command.*`、`GrimAPI`、`bukkit/`、`fabric/` 平台层 | — | SKIP | 与本地模块无关 |

## 上界模拟（`predict/MovementLimits`）

按 `LivingEntity.travelInAir` 的顺序模拟"最好的玩家"：先按输入加速，按结果移动，再乘摩擦；每次落地立刻起跳；冲刺起跳 +0.2。
输入取对角（前 + 横，长度封顶 1），所以比只按前进略宽。常数：地面加速 `speed × 0.216/f³`、空中 0.02（冲刺 0.026）、
摩擦 `f × 0.91` / `0.91`、跳跃 0.42、使用物品输入 ×0.2。这些在 1.8 到当前版本一致（`LivingEntity.getFrictionInfluencedSpeed`
里 ≤1.13.2 分支的 `0.16277136/(drag³)` 与新公式对同一摩擦给出同一加速度）。差异大的场景（液体、爬梯、鞘翅、游泳、骑乘…）
全部整体豁免，不判定。冰的稳态步行速度并不比草地快（加速慢、滑行长），所以混合地面的窗口取"最小/最大摩擦/0.6 三者天花板的最大值"，
而不是猜哪个最坏。

## 偏离 `~/.claude/plans/...` 的地方

1. **没有 `ShadowPlayerModel`。** 计划里设想用脱离世界的 `AbstractClientPlayer` 跑真实 `travel`。写窗口化判定时发现它不合适：
   我们要的是"理想空间里的上界"，而影子实体只能在真实世界里跑，会被墙、天花板截断，得到偏低的"上界"，反而制造误报；
   另外它还得压住声音/粒子/方块副作用。因为 v1 范围内的物理常数跨协议一致，改用纯公式的 `MovementLimits`，世界相关的部分
   （摩擦、支撑、环境）由 `WorldProbe` 接口提供，生产实现是 `client/ClientWorldProbe`。`EventMove`/`EventJump` 的
   `instanceof LocalPlayer` 门控因此没有被触碰。
2. **名单界面不是全屏页，而是常驻抽屉。** 名单是独立模块 `SuspectList`（`module/impl/gui`，只持开关和数据：`ranked()`、`sourceEnabled()`、
   `forget`/`forgetAll`、`showOnHud()`，读 AntiCheat 的 `Suspects`），任何主题都可以用自己的方式画。SigmaModern 下由
   `gui/modern/ModernSuspectDrawer` 画成从屏幕**左边**拖出的抽屉（音乐播放器抽屉在右边，镜像的交互：拖标签/标题栏，松手弹簧吸附，
   甩动带动量）。抽屉不属于任何一个界面：`Gui` 在每个屏幕渲染尾部画它（有世界时），`ModernHud` 在抽屉拉出时画在游戏画面上
   （`ShowOnHud`），`MouseHandler` 先把鼠标事件交给它。模块关闭、非 SigmaModern、没有世界时既不画也不吞事件。
3. **设置**：`Prediction` 拆成 `Speed`、`Flight` 两个开关。

## 已知局限（会误报或漏报的场景）

- **服务器授予的飞行**：生存模式 + `allow-flight` 的玩家，`abilities.flying` 不会同步给旁观者，悬停/上升会被当成 Flight。
- **不可见的药水效果**：只有药水粒子会同步。隐藏粒子的 Levitation / Slow Falling / Jump Boost 无法识别；属性（移速、跳跃力、重力）能看到的会被采信。
- **服务器改速度**：发射台、弹射插件不一定走 `SetEntityMotion`；会让 Speed / Flight 看到一次"无法解释"的位移。缓解：窗口 + 突发额度 + 两窗口确认。
- **只判 v1 范围内的环境**：液体、爬梯、蜘蛛网、蜂蜜、粉雪、气泡柱、脚手架、床、粘液块、活塞移动、鞘翅、激流、骑乘、睡眠、创造/旁观整体豁免，
  这些场景里的作弊不会被抓到。
- **漏报是设计取舍**：为了不误报，天花板取"最快的冲刺跳"并加容差与突发额度，所以有效阈值大约是该场景合法最快均速的 1.5 倍
  （Strict 约 1.25 倍）。比这慢的加速（例如 1.2 倍）不会被抓。

## 验证状态

- 单测：`src/test/java/.../anticheat/*` 与 `ModuleAntiCheatTest`（合法轨迹含 12 个抖动种子、1.8 精度、冰面、传送/冲量/方块变化/液体/鞘翅/创造豁免、
  违规轨迹、告警限频、检测互不影响）。
- 进游戏验证（`-Dsigma.debug.openScreen=SUSPECTS|SUSPECTS_HUD`，`AntiCheatDemo`：合成的 `RemotePlayer` + 真实 `ObservedPlayers`/
  `ClientWorldProbe`/检测器）：聊天告警行、名牌 `⚠ VL n`、抽屉渲染（拉出在游戏画面上、在暂停菜单和 ClickGUI 上只露标签）；真实鼠标：
  从左边拖出标签、点垃圾桶忘记一个玩家（名牌与列表同时清空）、拖标题栏收回。
- **未验证**：
  - 真实数据包路径：`ClientPacketListener` 里的采集 hook 还没有被真实玩家的包触发过（单人世界没有其他玩家，demo 绕过了包）。
  - 真实服务器上的误报率。唯一能标定的办法是把 `Notify`/`NameTag` 关掉只看日志，1.8 与 1.9+ 服务器各跑一次。
  - 旧协议（1.8）下 `Sample` 的到达节奏；测试只覆盖了 1/32 精度，没有覆盖 1.8 服务器实际的同步间隔。
