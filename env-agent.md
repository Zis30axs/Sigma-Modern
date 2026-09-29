# env-agent.md：修改 SigmaModern 的环境与脚本

给后续接手改 SigmaModern 的 agent（和人）看的：怎么把环境装起来，怎么编译、跑测试、无头启动游戏、开本地服务器、截图验证，以及踩过的坑。所有脚本在 `scripts/`，都是幂等的，改坏了重跑即可。代码约定（移植标记 `// MODIFIED for porting`、`// Sigma hook:`、哪些包不要碰）看 `CLAUDE.md`，这里只讲环境。

## 一分钟上手

```bash
scripts/setup.sh                       # 装 JDK 25、Linux 原生库、Maven 依赖和 classpath（首次几分钟，之后秒过）
scripts/build.sh compile               # 增量编译（整个项目冷编译约 3 分钟）
scripts/build.sh test                  # 全部测试，约 3 分钟；末尾一行汇总，失败的类会列出来
scripts/build.sh test-only Foo,BarTest # 只跑指定测试类

scripts/server.sh start                # 本地原版 26.2 服务器（127.0.0.1:25565，离线模式，超平坦）
SERVER=127.0.0.1:25565 scripts/game.sh start
scripts/game.sh wait-ready && scripts/server.sh wait-join
scripts/server.sh cmd "effect give @a speed 300 1"
scripts/game.sh shot                   # 打印截图路径，用 Read 工具看图
scripts/game.sh stop; scripts/server.sh stop
```

不用服务器、只看某个界面：`scripts/capture.sh out.png 240 -Dsigma.debug.openGui=SIGMA_MODERN`。

一次会话的标准流程：**改代码 → `build.sh compile`（游戏在跑就先 `game.sh stop`）→ `build.sh test-only 相关测试` → 启动游戏截图确认 → 改文档 → 全量 `build.sh test` → 提交推送。**

## 东西放在哪

脚本不往仓库里写任何东西。下载和构建产物都在 `SIGMA_WORK`（默认 `~/.cache/sigma-modern`）：

| 路径 | 内容 |
|---|---|
| `jdk/` | Temurin JDK 25（系统里没有 25+ 时才下载，校验 sha256） |
| `natives/` | pom 只为 Windows 列的原生库的 Linux 版：LWJGL、jtracy、Skija（版本从 `pom.xml` 推出来，升级版本不用改脚本） |
| `runtime-cp.list` | 游戏运行时 classpath，一行一个 jar（Windows 原生库换成 `natives/` 里的） |
| `settings.xml` | Maven 的代理配置，每次由 `$HTTPS_PROXY` 重新生成（代理端口每个会话都不一样） |
| `build/` | Maven 输出目录（`-Dsigma.buildDirectory`），不是 `target/` |
| `game/` | 游戏的工作目录，游戏目录是 `game/run/`；`game/session.log` 是游戏输出 |
| `server/` | 本地服务器，日志 `server/server.log` |
| `logs/mvn.log` | 最近一次 Maven 的完整输出 |

**为什么放在这里而不是会话的 scratchpad**：scratchpad 路径带会话编号，`/clear` 或新对话就找不到了；`~/.cache` 在同一个容器里一直在。容器被回收后这些会没有，重跑 `scripts/setup.sh` 即可（约 1.2 GB，主要是 JDK、游戏资源 456 MB、服务器）。

## 脚本

**`setup.sh [--check] [--build]`**：`--check` 只报缺什么（缺东西则退出码 1），`--build` 装完顺手编译。要做成环境的启动脚本时，把 `bash scripts/setup.sh` 放进去就行；它检测到齐了就不做任何事。

**`build.sh compile | test | test-only 类名,类名 | javac 文件... | clean`**：自动选 JDK 25、走离线 Maven（缺依赖时自动联网重试一次）、输出到 `build/`。`test` 给 Skija 补上 Linux 原生库，渲染相关测试需要它。多余参数原样交给 Maven（例如 `build.sh test -Dtest=Foo#method`）。`javac` 模式用来解决“只有 BUILD FAILURE 没有文件行号”：隐式编译错误时 Maven 不报位置，把可疑文件直接交给它就能看到真正的错误。

**`game.sh start|wait-ready|shot|key|type|click|move|log|status|stop`**：Xvfb + 软件渲染（llvmpipe，约 20 帧），用 xdotool 操作。
- `SERVER=host:port` 启动即进服（quick play），`WORLD=存档名` 进单人存档。
- `start` 后面的参数原样给 JVM，`-Dsigma.debug.*` 见下。
- 分辨率默认 1280×720，界面缩放固定 2（首次启动时写入 `run/options.txt`），所以截图坐标是窗口像素。
- 首次启动会下载原版资源（456 MB），`wait-ready` 默认等 120 秒，第一次不够就传更大的值：`game.sh wait-ready 400`。
- 就绪的标志是日志里的 `Started with N modules.`。

**`capture.sh 输出.png 帧数 [-D...]`**：开一个一次性的无头游戏，渲染指定帧数后用游戏自己的 debug 截图退出。`MOUSE=x,y` 让指针停在某处（悬停状态）。和 `game.sh` 共用游戏目录，不能同时跑。

**`server.sh start|cmd|wait-join|log|status|stop|reset`**：官方原版服务器，从 Mojang 的 launcher manifest 下载并校验 sha1。`MC_VERSION`（默认 26.2）、`SERVER_PORT`、`SERVER_MEM` 可调。启动后会关掉自然生成的怪（平坦世界里全是史莱姆，会挡住所有战斗和截图）。**只连本地服务器**，不要把客户端指向别人的服务器。

**`_env.sh`**：被上面所有脚本 `source`。放公共路径、JDK 查找、代理、`sigma_mvn`、进程组停止等函数。

## -Dsigma.debug.* 开关

只在启动时生效，不会写进玩家配置（配置里保存的还是用户自己的值）。

| 开关 | 作用 |
|---|---|
| `enableModules=A,B` | 启动时打开这些模块 |
| `settings='Module/Setting=值;...'` | 设置模块的设置，如 `ArrayList/Position=BOTTOM_RIGHT;PotionStatus/Layout=INLINE` |
| `setClientMode=SIGMA_MODERN` | 客户端模式（脚本默认就是它） |
| `openGui=SIGMA_MODERN` | 启动后打开 ClickGUI |
| `modernPreviewView=CATEGORY:RENDER` 或 `DETAIL:Module` | ClickGUI 直接打开某个分类或模块页 |
| `openGuiInWorld` | 进世界后再打开 ClickGUI，用来看世界上层的效果 |
| `openScreen=`、`openScreenDelayFrames=` | 打开某个界面 |
| `chatInput=`、`chatType=`、`chatLines=N`、`chatCloseAfterFrames=N` | 聊天框相关截图 |
| `screenshotAfterFrames=N` | 渲染 N 帧后截图（`capture.sh` 用它） |
| `musicOffline`（脚本默认开）、`musicSearch=`、`musicAutoplay`、`musicPreview`、`musicPage=fx\|lyrics`、`musicDrawer=open`、`musicLyrics=文件`、`musicMuted` | 音乐播放器截图 |
| `islandActivity`、`modernMenuDock`、`selectFirst`、`smoke`、`screenSmoke`、`frameTiming`、`maximizeAfterFrames=N`、`logMode` | 其他截图和性能辅助，见 `Client.java` 里的注释 |

## 验证一个 HUD 或界面改动

1. `build.sh compile`，然后 `server.sh start`、`SERVER=... game.sh start -Dsigma.debug.enableModules=...`。
2. `game.sh wait-ready`、`server.sh wait-join`，再等约 8 秒（进服后前几秒画面没就绪）。
3. 用服务器命令造出要看的状态：`effect give @a <效果> <秒> <等级>`、`time set noon`、`gamemode creative @a`、`give @a ...`。
4. `game.sh shot`，用 Read 工具打开打印出的 png。
5. 换设置对比时要重启游戏（`game.sh stop` 再 `start` 带新的 `-Dsigma.debug.settings`），不要靠改配置文件。

## 踩过的坑

- **JAVA_HOME**：PATH 上的 `java` 可能是 21，Maven 会报误导性的 `release 25 not supported`。用脚本，别直接跑 `mvn`；不要为此改 `pom.xml` 的 `release`。
- **别用 `pkill -f 模式`**：模式如果同时出现在你自己的命令行里，会杀掉你自己的 shell（发生过两次）。脚本用 pid 文件停进程组（`sigma_stop_group`），新脚本沿用。
- **代理端口每个会话都变**：不要把 `settings.xml` 或端口抄来抄去，`_env.sh` 每次重新生成。JVM 的代理由环境里的 `JAVA_TOOL_OPTIONS` 带，`Picked up JAVA_TOOL_OPTIONS...` 那行是噪声，脚本用 `noise` 过滤。
- **新游戏目录的首次启动界面**：欢迎/无障碍界面和多人警告会挡住 quick play，永远进不了服。`sigma_prepare_game_dir` 首次写 `options.txt` 跳过它们；换游戏目录（`SIGMA_GAME=`）时同样适用。
- **`Start` 要求 `run/assets/indexes` 存在**：不存在就直接抛异常；存在但为空则自动下载。`sigma_prepare_game_dir` 会建好。
- **Iris/Sodium 的 `./config`** 是相对进程工作目录的，不是游戏目录；缺了会在日志里报 `NoSuchFileException`（不致命，但吵）。
- **编译会替换游戏正在用的 classes**：`build/classes` 就是游戏的 classpath 首项，编译时游戏在跑会 `NoClassDefFoundError`。先 `game.sh stop`。（`target/` 是 IntelliJ 用的，脚本故意不碰。）
- **软件渲染很慢**：截图前多等几秒；动画和缓动需要更多帧才到位。`GAME_MEM` 默认 3g。
- **quick play 与 SelfDetection 有竞态**：客户端在 `Client.start()` 打开模块之前就开始连服，第一个服务器可能没被 SelfDetection 接管。做 SelfDetection 实验时看日志里有没有本轮的 `=== ... connecting to` 行，没有就重来（详见 `SELFCHECK_PORTING.md` 的“已知问题”）。
- **测试要 Skija 的 Linux 原生库**：不经过 `build.sh test` 直接跑 Maven 时，渲染类测试会因为找不到原生库失败。
- **Paper 1.8.8 + ViaVersion + GrimAC 那套跨版本实验环境没有脚本化**：它依赖多处第三方下载，且 Netty 4.0 有已知限制。做法和结果记在 `SELFCHECK_PORTING.md`，需要时按里面的步骤手动搭。

## 网络

出网走代理（`$HTTPS_PROXY`，CA 在 `/root/.ccr/ca-bundle.crt`，不要关 TLS 校验）。脚本用到的主机：`api.adoptium.net`（及它跳转的 GitHub 发布下载）、`repo1.maven.org`、`libraries.minecraft.net`、pom 里其他仓库、`piston-meta.mojang.com`、`piston-data.mojang.com`、原版资源下载站。某个主机被拒时，先看代理状态：`curl -sS "$HTTPS_PROXY/__agentproxy/status"`。GitHub 的 API 只对本仓库开放，别指望能从别的仓库下载。

## 怎么改这些脚本

- 新脚本放 `scripts/`，开头 `source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"`，`set -euo pipefail`，用 `say`/`die` 输出，路径都从 `_env.sh` 的变量取，不要写死路径。
- 要保持幂等：已经有的就跳过，下载先写 `.part` 再改名（`sigma_fetch`），有校验值的要校验。
- 后台进程用 `setsid` 启动并写 pid 文件，用 `sigma_stop_group` 停。
- 需要新的 Linux 原生库时不用改 `setup.sh`：在 pom 里照 Windows 的写法加 `natives-windows` 依赖，脚本会自动推出 Linux 版。
- 改完在干净环境验证：`SIGMA_WORK=$(mktemp -d) scripts/setup.sh`（会重新下载 JDK 等，约 1 GB）；快速检查用 `scripts/setup.sh --check`。
- 改了脚本的行为，同步改这份文档里对应的段落。

## 提交与推送

- 开发分支 `claude/great-goodall-yaafrw`；推到 `main` 只在用户明确说要推的时候做（先 `git fetch`，main 上有新提交就合并进来、重新跑全量测试再推）。用户没要求就不建 PR。
- 提交信息末尾原样照抄会话给出的署名行（`Co-Authored-By` 和 `Claude-Session`）；除这两行外，提交、注释、文档里不要出现模型名。
- 改了行为就同步 `SIGMA_MODERN.md`（界面和 HUD）、`PORTING.md`/`VFP_AUDIT.md`（移植状态）、`SELFCHECK_PORTING.md`（反作弊实验）。
