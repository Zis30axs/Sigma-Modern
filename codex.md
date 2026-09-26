# Codex — Sigma-Modern 简要介绍

## 项目定位

**Sigma-Modern** 是基于 MCP 反编译源码构建的 **Minecraft 26.2** 客户端工程。
Sodium、Iris、ViaFabricPlus、SodiumExtra、Lithium、FerriteCore 等模组的完整逻辑以
**源码形式直接合入 Minecraft 本体**——无 Fabric/NeoForge 模组加载器，无 Mixin 运行时
依赖。全部代码位于 `src/main/java`（约 8600+ 个 Java 文件）。

## 核心特性

- **渲染优化**：Sodium 0.9.1+（区块/实体渲染、GPU 加速管线）
- **光影**：Iris 光影加载器（匹配 Sodium 0.9.x）
- **多版本协议**：ViaFabricPlus 4.6.3（Classic 到最新版跨版本连接）
- **逻辑优化**：Lithium（区块调度、实体/AI）+ FerriteCore（NBT/内存）
- **扩展**：SodiumExtra

## 移植约定（改动代码前必读）

- 原 Mixin 注入点全部转换为 vanilla 源码中的直接调用，改动处标注 `// MODIFIED for porting`。
- 模组自身的包名 / 类名 / 目录结构保持原样（如 `malte0811.ferritecore.*`）。
- 原 accessor 接口保留为普通接口，由对应 vanilla 类直接 `implements`。
- Loader 特有的入口点 / 配置读取 / Mixin 插件不移植，其承载的必要逻辑接到真实调用点。
- 不要新增 Mixin 或模组加载器依赖。

## 构建与运行

- 环境：JDK 25+（推荐 OpenJDK 26）、Maven 3.9+
- 构建：`JAVA_HOME=<jdk25+> mvn -o -DskipTests compile`（不修改 `pom.xml`）
- 运行：IDEA 中运行 `Start` 主类；游戏目录为项目根目录 `run/`
- 首次启动自动下载资产（约 457MB）；VFP 协议配置位于 `run/config/viafabricplus/`

## 文档索引

| 文件 | 内容 |
|------|------|
| `README.md` | 构建 / 运行说明 |
| `PORTING.md` | sodium / ferritecore / lithium / sodium-extra / iris 移植记录与状态 |
| `VFP_PORTING.md` | ViaFabricPlus 移植进度与外部库清单 |
| `VFP_AUDIT.md` | VFP 移植账本（726 个 hook 的逐条审计，权威状态来源） |
| `ANTIEXPLOIT.md` | AntiExploit 研究结论与实现范围 |

## 已知限制

- Classic CPE 天气类型暂不支持
- Bedrock RakNet / NetherNet 复杂连接场景待完善