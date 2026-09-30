# LEGACY_PORTING.md — 旧客户端 Jello / Classic 的渲染效果移植回来

日期: 2026-09-30
来源: `juzibujiji/SigmaClient`（旧版 Sigma 5，Jello 与 Classic 两套界面）
去向: `com.mentalfrostbyte.jello.gui.{legacy,jello,classic,account,mainmenu}`、`gui.modern.Legacy*`

不是逐行搬源码：旧客户端画在 LWJGL2 的 GL 立即模式上，26.2 只有 `GuiGraphicsExtractor`。
搬的是**版式数字、动画曲线、贴图和分层顺序**，画法重写。

## 基础设施（`gui.legacy`、`gui.modern.Legacy*`）

| 类 | 作用 |
|---|---|
| `LegacyCanvas` | 旧客户端的坐标系：1 单位 = 1 个帧缓冲像素（pose 缩放 1/guiScale）。旧代码里的 `x, y, 宽, 高` 原样搬过来就能对上。鼠标用 `LegacyCanvas.mouseX()/mouseY()`。提供 `image/region/rotated/fill/rounded/disc`、`text*`、`scissor`、`floatingCard`（旧 `method11467`，用 `floating_corner/border` 贴图切块拼成，避免缝线）、`outerGlow`、`innerFeather` |
| `LegacyTexture` | 贴图 id + 像素尺寸（26.2 的 `blit` 要显式给尺寸）。全部在 `textures/gui/sigma/legacy/**`，每张带 `.png.mcmeta` `{"texture":{"blur":true,"clamp":true}}` 走线性过滤，旧客户端就是这样画的（背景是 3840 宽，缩小显示） |
| `LegacyFonts` | Helvetica Neue Light/Medium（Jello）、`regular.ttf`（Classic），走 Skija 的 `ModernFontRenderer`；基线按字体 ascent 每实例算。Classic 的原版位图字用 2 倍原版字体（`LegacyCanvas.vanilla`） |
| `LegacyBlurredImage` | 旧 Jello 的模糊全景：面积缩小 → 高斯 σ=半径/3 → 饱和度 ×1.1，CPU 异步算，算好再上传 |
| `LegacyScroll` `LegacyLabelButton` `LegacyTextField` `LegacyDropdown` `LegacyDialog` `LegacyKeys` | 旧控件：滚动条（带上下端贴图）、带增长下划线的文字按钮、`TextFieldHelper` 上的输入框、下拉、Jello 弹窗（弹性弹出，先 `nextStratum()` 再 `blurBeforeThisStratum()`）、按键名 |

**每个旧界面都必须重写 `extractBackground`**（`SigmaMainMenuScreen` 提供了空实现），否则原版会在它们后面再画一层
全景/模糊，同一帧里第二次 `blurBeforeThisStratum` 会崩。ClickGUI 想要世界模糊时在 `extractBackground` 里调 `GuiVisuals.blurBackground`。

## 已移植

### Jello
- **主菜单** `JelloMainMenuScreen` + `JelloBackdrop`：三层视差（背景/中景/前景，偏移 `-mouseX`、`mouseY/W*-114`，层宽 600/450/0 × W/1920，滞后系数 `min(1, 0.7·ms/36.2)`）、
  漂浮泡泡（`W·H/14000` 个，鼠标推开半径 114）、模糊全景叠在压暗的场景上、Changelog 页、告别页（2 秒后关游戏）、带增长下划线的文字按钮。
  调试开关 `-Dsigma.debug.jelloMenu=changelog|goodbye`（主菜单本身是启动后的默认界面，不带 `openGui` 即可）。
- **Alt Manager** `JelloAltManagerScreen`：账号卡片（圆形头像 = 原 `cercle.png` 环 + 内侧羽化）、跟随鼠标的 3D 皮肤模型（`graphics.skin`，画在 canvas 之外）、
  排序下拉、搜索、Add+、删除/选项/添加弹窗。后端仍是 `SigmaAccountManager`（微软设备码 + 离线名），不存邮箱密码。
  调试 `-Dsigma.debug.openScreen=ALTS`（空库时塞几个离线账号）、`-Dsigma.debug.altSelect=<行>`、`-Dsigma.debug.altDialog=add|delete|options`。
- **ClickGUI** `JelloClickGuiScreen` + `JelloSettingsPage`：白色 200×350 分类卡片带外发光、30px 模块行（开=`0xFF29A6FF`）、拖标题移动、每卡独立滚动、
  弹性打开动画（`Animation(450,125)`）和关闭动画（`SigmaClickGui.beginClose()`）、位置会话内记忆；右键模块进设置页（500px 白卡：名字/说明/Keybind 胶囊/
  开关/滑条/枚举下拉/文本/HSV 取色）。`-Dsigma.debug.jelloSettings=<Module>`。

### Classic
- **主菜单** `ClassicMainMenuScreen` + `ClassicParticles` + `ClassicButton`：三层鼠标视差（背景 /200、分组 /40、粒子 /12，每帧追 5.5%）、漂浮粒子、
  按钮 300ms 弹簧插值（带过冲）、进场上浮 5px。
- **Alt Manager** `ClassicAltManagerScreen` + `ClassicAltPromptScreen`(ADD/DIRECT)：列表 + 工具栏 + Add/Direct Login 页。调试 `-Dsigma.debug.openScreen=ALTS_CLASSIC` / `ALTS_CLASSIC_ADD`。
- **ClickGUI** `ClassicClickGuiScreen` + `ClassicSettingsPage`：第一页 396×520 六个分组图标（Combat/Movement/World/Player=PLAYER+ITEM/Visuals=RENDER+INTERFACE/Others=MISC+EXPLOIT），
  第二页 592×692 三列模块卡（20 帧滑动开关精灵、旋转齿轮、说明条），窗口小时整体缩小；设置页从下往上擦入（150ms），
  BIND/开关/滑条/枚举/文本/取色。`-Dsigma.debug.classicGroup=<Group>[:Module]`。

### 共用
- `AccountOps`：登录/添加/删除/排序，两套 Alt Manager 共用。
- `SigmaClickGui.beginClose()`：ClickGUI 想播放关闭动画就返回 true，`ClickGuiHandler` 据此决定是否直接 `close()`。
- 旧的 `SigmaAccountScreen` 没动，`ModernMainMenuScreen` 还在用。

## 没搬（按价值排序）

| 项 | 旧客户端里 | 为什么没搬 |
|---|---|---|
| Jello / Classic 游戏内 HUD | Jello `ActiveMods`（白色轻字 + 阴影光晕，缩放入场）、`TabGUI`；Classic `ActiveMods`/`TabGUI`；Compass/Coords/KeyStrokes/InfoHUD/MiniMap/Radar/TargetHUD | `ModernHud` 只在 `SIGMA_MODERN` 下画，Jello/Classic 下 ArrayList/TabGUI/PotionStatus 目前**什么都不显示**。这是下一个最大缺口 |
| 加载界面 | `LoadingScreen`：logo + 进度条叠在模糊 `back.png` 上 | 26.2 的加载覆盖层另有一套，需要单独接 |
| 通知 | Jello/Classic 的通知卡片 | 依赖 HUD 层 |
| 游戏内 Jello 页面 | Keyboard、Maps、Snake、Bird、Spotlight、IRC、Options/Credits、Waypoints | 不是渲染效果，是功能；没有对应模块 |
| Jello ClickGUI 附属面板 | 音乐播放器、IRC、配置面板、BrainFreeze 遮罩 | 仓库里没有 BrainFreeze 模块 |
| Jello Alt 信息面板的 Bans 列表 | 账号封禁记录 | 没有数据源 |
| Classic 的 Edit Alt | 旧版能改邮箱密码 | 现在账号不存邮箱密码，按钮换成了 "Launcher" |

## 已知取舍

- **字体版权**：`assets/minecraft/font/sigma/helvetica_neue_{light,medium}.ttf` 是从旧仓库原样拷来的，Helvetica Neue 是商业字体。
  如果这个仓库要公开发布，换成免费替代（如 Inter / Helvetica 兼容字体），只需要替换这两个文件，`LegacyFonts` 只按度量取基线。
- **`Animation` 曲线**：Jello 的弹性打开用仓库已有的 `Animation`，没有逐帧对照旧曲线；肉眼一致，数值没验过。
- **Classic 的文字**：用原版字体放大 2 倍，不是旧版的位图字，字形会有差别。

## 验证方式

- 单元测试：`LegacyTextureTest`（每个枚举项都指向存在的文件、尺寸对得上、带线性过滤的 `.mcmeta`）、`LegacyCanvasColorsTest`、`LegacyScrollTest`、`LegacyBlurredImageTest`。
- 截图：`CLIENT_MODE=JELLO|CLASSIC scripts/capture.sh out.png 300 -Dsigma.debug.openGui=<JELLO|CLASSIC>`（ClickGUI）或 `-Dsigma.debug.openScreen=<ALTS|ALTS_CLASSIC|ALTS_CLASSIC_ADD>`（Alt Manager；帧数给够，前 40 帧还是加载界面），
  在世界里用 `scripts/server.sh start` + `scripts/game.sh start -Dsigma.debug.openGuiInWorld=true`。
- 全量：`scripts/build.sh test` → 330 个测试 0 失败（1 个跳过）。
