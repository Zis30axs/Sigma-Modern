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
  弹性打开动画（`Animation(450,125)`）和关闭动画（`SigmaClickGui.beginClose()`）、位置会话内记忆；`BrainFreeze` 模块开着时卡片后面下雪（`JelloSnow`，屏幕宽度一半数量的小白点）；右键模块进设置页（500px 白卡：名字/说明/Keybind 胶囊/
  开关/滑条/枚举下拉/文本/HSV 取色）。`-Dsigma.debug.jelloSettings=<Module>`。
- **音乐窗口** `JelloMusicPanel`（ClickGUI 右下角的 "Music" 按钮开关；窗口够宽时默认打开，卡片会让到它左边）：800×600 的旧版布局——黑色左栏（来源列表，
  当前歌曲的封面横跨左栏和底栏）、右边 3 列封面网格、底部暗紫色控制条（上一首/播放暂停/下一首、右侧音量竖线、底边进度线和两端的时间）。
  浏览逻辑用的是 SigmaModern 那一套：分类、搜索框文字和结果、打开的歌单、点一行做什么，都在 `gui.modern.MusicBrowse`（从 `ModernMusicBrowser` 里
  抽出来的无界面模型，静态状态），两个窗口只是两种画法——在一边搜过的词、停留的分类，换到另一边还在；往 `MusicBrowse.CATEGORIES` 加一行两边都多一个来源。
  播放走同一个 `MusicPlayer`，所以两边是同一个队列。来源是网易云的几个榜单和一位歌手、搜索，以及登录后的每日推荐和我的歌单（左栏用 Modern 的短名字）。
  **登录界面（二维码）没搬**，要在 SigmaModern 的音乐窗口里登录；旧版的 "Open Folder"、Local Music、重复模式和频谱按钮也没搬（后端没有对应的东西）。
  `-Dsigma.debug.musicDemo=1` 让榜单和歌手列一批假歌曲、Jello 的窗口默认打开，截图不用联网（取代了之前的 `jelloMusic=demo`）。

- **暂停菜单里的 Jello 页面**：`PauseScreen` 里一行钩子在 Jello 下多出 "Jello for Sigma Options" 按钮（位置同旧版：底部居中 204×20），打开
  `JelloOptionsScreen`（版本号、ClickGUI 绑的键、Keybind Manager / ClickGUI / Credits 三个入口，缩放进出）。
  - `JelloKeybindScreen` + `JelloKeys`（Keybind Manager）：1060×357 的虚拟键盘（键位表是旧 `Keys` 原样，按下时下沉 3px），点一个键（或按真实按键，或鼠标侧键/中键）
    弹出 250×330 的卡片列出绑在这个键上的模块、垃圾桶解除、Add 打开 500×600 的选择列表（搜索：前缀匹配排前面，再是包含匹配）。直接读写模块自己的 `Keybind`，
    所以跟 ClickGUI 里的绑定按钮是同一份数据；关闭时存配置。旧版还能把 *界面*（Click GUI、Maps、Snake……）绑到键上，这里没有界面绑定的存储，没搬。
  - `JelloCreditsScreen`：`assets/minecraft/sigma/credits.txt`，**内容是重写的**，不是旧版那段 746 行的依赖许可证（那是旧客户端当时依赖的列表，放在这里会是错的）。
  - 调试：`-Dsigma.debug.openScreen=JELLO_OPTIONS|KEYBINDS|CREDITS|SPOTLIGHT|SNAKE|BIRD`。
- **Spotlight / Snake / Bird**：旧版把这些界面绑到键上；这里每个是一个 INTERFACE 模块（`Spotlight`、`Snake`、`Bird`，继承 `ScreenLauncher`），
  打开它就在下一个 tick 开界面并把自己关掉，所以可以用 Keybind Manager / ClickGUI / TabGUI 绑键和打开，不需要另一套存储。
  - `JelloSpotlightScreen`：屏幕 25% 处一条 675×60 的白色搜索条，输入模块名前缀，灰字补全成 "Speed - Disabled"，回车切换第一个匹配并关闭。
  - `JelloSnakeScreen` + `SnakeGame`：48×27 格、14px 一格、70ms 一步，移动键或方向键转向；规则（不能掉头、两步之间只转一次、吃苹果长一格、撞墙/自己重来）是纯逻辑，有测试。
  - `JelloBirdScreen` + `BirdGame`：背景、管子、滚动的地面、三帧的鸟是旧版的图。**旧版这个游戏没有碰撞也不计分**，而且下落是匀速 600px/s，这里补成了能玩的：
    重力加速度、按空格给固定向上速度、碰管子或地面重来、过一根管子得一分。物理是新写的，不是旧数字。
  - 两个游戏共用 `JelloGameScreen` 的外框（白卡、标题、"Max | Score"、弹出动画），吃苹果有 `pop` 音效。
- **游戏内 HUD** `gui.legacy.hud`（`LegacyHud` 从 `Hud` 的钩子进来，只在 JELLO/CLASSIC 下画）：
  - 水印：170×104 的图片，F3 时移到顶部居中。
  - ActiveMods `JelloActiveMods`：右上角白色 Helvetica Neue Light，文字后面是黑色柔光（`shadow.png`），按名字宽度（20 号字）从宽到窄排；
    开关时 150ms 缩放淡入淡出并把下面的行挤开。`ArrayList` 模块的 `Size`（Normal/Small/Tiny = 20/18/14）、`Animations`、`Suffix`、`Hide Visuals` 生效。
  - TabGUI `JelloTabGui`：左边 150 宽的分类面板（一次 5 行，选中行文字右移 14px，光带滑动），右边 170 宽的模块面板（开的模块用 Medium 字重）。
    键位逻辑是 `TabGui` 模块自己的，这里只画。
  - 位置和旧版一样：TabGUI 从 y=99 开始，`LegacyHud.leftBottom()` 是下面元素该从哪开始。列表会让开原版的药水图标和 F3 右栏。
  - 小部件 `JelloWidgets`（各是一个 INTERFACE 模块，默认关，只有 Jello 画）：`MiniMap`（`JelloMiniMap`：150px 方块，TabGUI 下面，周围 10×10 个区块的地表，
    **朝向永远在上**，中间一个箭头，另一个淡箭头指移动方向，边缘有内羽化和外阴影。颜色就是旧版的办法：每列最上面那块的地图色，雪下白色、熔岩下熔岩色、含水方块水色，
    北/南邻列是空气就压暗/提亮 60%；一个区块等南北邻居都到了再上色，每帧最多 4 个，图是一张 160×160 的动态纹理，约 20Hz 重拼），`KeyStrokes`（WASD + 左右键，按下变亮、松开时从中心扩散一圈涟漪）、
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
| Jello Maps / Waypoints | 探索过的世界地图（逐区块存盘）、缩放拖动、路标列表 | `WaypointsManager` + `MapFrame` + `Zoom` 一共 1100 多行，要自己的区块存储和路标文件；MiniMap 只画眼前的区块，不需要这些 |
| Jello Radar（WarThunderRadar） | "战争雷霆"风格雷达，带威胁检测和警报声 | 这是旧仓库后来加的东西，不是 Jello 原版的；2000 行，带投射物弹道推演和音频 |
| TargetHUD、RearView、ShulkerInfo、MusicParticles、YsmGUI | 旧仓库里的其它模块 | TargetHUD 在旧仓库里就是个空壳（"渲染逻辑待实现"）；其余同样是后加的，不是 Jello 原版的 |
| Jello / Classic 下的 PotionStatus | 旧版没有这个模块 | 原版药水图标留在右上角，列表会让开它 |
| 通知 | Jello/Classic 的右下角通知卡片 | 旧版里是各个模块自己发的；这里没有通知系统，也没有模块在发，先不加 |
| Jello ClickGUI 的配置面板 | 右下角 "more" 按钮和配置名 | 这里没有配置档案。IRC 按你说的不搬 |
| Jello Alt 信息面板的 Bans 列表 | 账号封禁记录 | 没有数据源 |
| Classic 的 Edit Alt | 旧版能改邮箱密码 | 现在账号不存邮箱密码，按钮换成了 "Launcher" |
| 把界面绑到键上 | Keybind Manager 里能把 Click GUI、Maps 等绑到键 | 这里界面是通过模块（`ScreenLauncher`）进的，绑的是模块的键；Click GUI 的键仍是固定的右 Shift |

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
