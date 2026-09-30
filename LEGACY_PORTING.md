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
- **音乐窗口** `JelloMusicPanel`（ClickGUI 右下角的 "Music" 按钮开关；窗口够宽时默认打开，卡片会让到它左边）：800×600 的旧版布局——黑色左栏（来源列表，
  当前歌曲的封面横跨左栏和底栏）、右边 3 列封面网格、底部暗紫色控制条（上一首/播放暂停/下一首、右侧音量竖线、底边进度线和两端的时间）。
  数据和播放走 SigmaModern 音乐窗口用的同一套后端（`MusicLibrary` / `MusicPlayer`），所以两边看到的是同一个队列。来源是网易云的几个榜单和一位歌手、
  搜索，以及登录后的每日推荐和我的歌单。**登录界面（二维码）没搬**，要在 SigmaModern 的音乐窗口里登录；旧版的 "Open Folder"、Local Music、
  重复模式和频谱按钮也没搬（后端没有对应的东西）。`-Dsigma.debug.jelloMusic=demo` 开着窗口并列一批假歌曲，截图不用联网。

- **游戏内 HUD** `gui.legacy.hud`（`LegacyHud` 从 `Hud` 的钩子进来，只在 JELLO/CLASSIC 下画）：
  - 水印：170×104 的图片，F3 时移到顶部居中。
  - ActiveMods `JelloActiveMods`：右上角白色 Helvetica Neue Light，文字后面是黑色柔光（`shadow.png`），按名字宽度（20 号字）从宽到窄排；
    开关时 150ms 缩放淡入淡出并把下面的行挤开。`ArrayList` 模块的 `Size`（Normal/Small/Tiny = 20/18/14）、`Animations`、`Suffix`、`Hide Visuals` 生效。
  - TabGUI `JelloTabGui`：左边 150 宽的分类面板（一次 5 行，选中行文字右移 14px，光带滑动），右边 170 宽的模块面板（开的模块用 Medium 字重）。
    键位逻辑是 `TabGui` 模块自己的，这里只画。
  - 位置和旧版一样：TabGUI 从 y=99 开始，`LegacyHud.leftBottom()` 是下面元素该从哪开始。列表会让开原版的药水图标和 F3 右栏。
  - 小部件 `JelloWidgets`（各是一个 INTERFACE 模块，默认关，只有 Jello 画）：`KeyStrokes`（WASD + 左右键，按下变亮、松开时从中心扩散一圈涟漪）、
    `Coords`（x=85，静止时暗，走动时亮并弹一下）、`Compass`（顶部居中的方位条，S/W/N/E 用 Medium 40 号字，其余是刻度和度数）、
    `InfoHUD`（左下角：角色小模型、护甲和耐久条、坐标；`Move Chat Up` 通过 `ChatComponent` 里的一行钩子把聊天往上抬，绘制和点击共用）。
  - 开关声音：`LegacyToggleSound` 订阅 `EventModuleToggle`，Jello 播 `activate/deactivate.mp3`（JLayer 解码成 PCM，Java Sound 播，后台线程；
    没有声卡就跳过并记一次日志），Classic 播原版石头按钮声。`ArrayList` 的 `Sound` 设置控制，跟旧版 ActiveMods 一样。

### Classic
- **游戏内 HUD**：水印（深色底板 + "Sigma" + 彩虹版本号）、ActiveMods `ClassicActiveMods`（深色框 + SF UI Display Bold 名字 + 灰色模式，
  色相沿列表往下走，2 秒转一圈；`Outline` All/Left/Right/None 和 `Transition` Smooth/Slide/Both 是新加的设置）、
  TabGUI `ClassicTabGui`（三级面板：分类 → 模块 → 设置，深蓝到亮蓝的光带，Right 进入设置，再按 Right 编辑值，
  编辑时出现指向说明框的三角；4 秒不按键整体暗到一半）。设置这一级是 `TabGui.Layout(settings=true)` 打开的，SigmaModern 不受影响。
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
- 加载界面早就在 `LoadingOverlay` 里（旧 `LoadingScreen#xd` 的 26.2 版本），这次没动。

## 没搬（按价值排序）

| 项 | 旧客户端里 | 为什么没搬 |
|---|---|---|
| Jello 的 MiniMap / Radar（含 WarThunderRadar） | 旧版各是一个 Jello 专用模块 | 还没搬，要采样区块，比其它小部件重得多 |
| Jello / Classic 下的 PotionStatus | 旧版没有这个模块 | 原版药水图标留在右上角，列表会让开它 |
| 通知 | Jello/Classic 的右下角通知卡片 | 旧版里是各个模块自己发的；这里没有通知系统，也没有模块在发，先不加 |
| 游戏内 Jello 页面 | Keyboard、Maps、Snake、Bird、Spotlight、IRC、Options/Credits、Waypoints | 不是渲染效果，是功能；没有对应模块 |
| Jello ClickGUI 附属面板 | 配置面板（右下角 "more" 按钮和配置名）、BrainFreeze 遮罩 | 这里没有配置档案；仓库里没有 BrainFreeze 模块。IRC 按你说的不搬 |
| Jello Alt 信息面板的 Bans 列表 | 账号封禁记录 | 没有数据源 |
| Classic 的 Edit Alt | 旧版能改邮箱密码 | 现在账号不存邮箱密码，按钮换成了 "Launcher" |

## 已知取舍

- **字体版权**：`assets/minecraft/font/sigma/helvetica_neue_{light,medium}.ttf` 和 `sf_ui_display_bold.ttf`（Classic HUD 用）是从旧仓库原样拷来的，
  Helvetica Neue 和 SF UI Display 都是商业字体。
  如果这个仓库要公开发布，换成免费替代（如 Inter），只需要替换这几个文件，`LegacyFonts` 只按度量取基线。
- **面板没有毛玻璃**：旧 Jello 的 TabGUI 面板是背后世界的模糊图。26.2 的 GUI 只能整屏模糊，切不出一块矩形，所以面板是半透明深色板加同样的柔边。
- **Classic 的 Slide 过渡**：旧版只把文字滑出去，深色框留在原地（看着像个 bug），这里整行一起滑。
- **`Animation` 曲线**：Jello 的弹性打开用仓库已有的 `Animation`，没有逐帧对照旧曲线；肉眼一致，数值没验过。
- **Classic 的文字**：用原版字体放大 2 倍，不是旧版的位图字，字形会有差别。

## 验证方式

- 单元测试：`LegacyTextureTest`（每个枚举项都指向存在的文件、尺寸对得上、带线性过滤的 `.mcmeta`）、`LegacyCanvasColorsTest`、`LegacyScrollTest`、`LegacyBlurredImageTest`。
- 截图：`CLIENT_MODE=JELLO|CLASSIC scripts/capture.sh out.png 300 -Dsigma.debug.openGui=<JELLO|CLASSIC>`（ClickGUI）或 `-Dsigma.debug.openScreen=<ALTS|ALTS_CLASSIC|ALTS_CLASSIC_ADD>`（Alt Manager；帧数给够，前 40 帧还是加载界面），
  在世界里用 `scripts/server.sh start` + `scripts/game.sh start -Dsigma.debug.openGuiInWorld=true`。
- 全量：`scripts/build.sh test` → 330 个测试 0 失败（1 个跳过）。
