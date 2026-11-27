# Sigma-Modern

基于 MCP 反编译源码的 Minecraft 26.2 客户端工程，集成了 Sodium/Iris/ViaFabricPlus/SodiumExtra/Lithium 等模组的完整移植版本。无需 Fabric/NeoForge 等模组加载器，所有模组逻辑以源码形式直接合入 Minecraft 本体。

## 构建要求

- JDK 25+（推荐 OpenJDK 26）
- Maven 3.9+

## 构建

    mvn -o compile          # 离线增量编译（需本地 .m2 已有依赖）
    mvn compile             # 在线构建（首次自动下载依赖）

## 运行

在 IDEA 中使用 Start 主类直接运行，或命令行：

    java -cp "target\classes;<依赖classpath>" Start

游戏目录为项目根目录下的 run/ 文件夹。

## 集成模组

| 模组 | 版本 | 说明 |
|------|------|------|
| Sodium | 0.9.1+mc26.2 | 渲染优化（区块构建/实体渲染/GPU 抽取） |
| Iris | 匹配 Sodium 0.9.x | 光影加载器 |
| ViaFabricPlus | 4.6.3+mc26.2 | 多版本协议转换（Classic 到最新版） |
| SodiumExtra | 匹配 VFP | Sodium 扩展功能 |
| Lithium | 对应 mc26.2 | 游戏逻辑优化 |

所有模组以源码形式合入 src/main/java 对应包下，mixin 注入点已转换为 vanilla 源码中的直接调用（标注 MODIFIED for porting）。无模组加载器、无 mixin 框架运行时依赖。

## 首次运行说明

- 启动时自动检测并下载资产索引与对象文件（约 457MB，仅首次）
- 需要 JDK 25+
- run/config/viafabricplus/ 下为各协议子系统配置文件

## 网易网络游戏

在 Modern 主菜单原有的账号管理中，复制自己的网易 Cookie 或 SAuth 后点击「导入网易凭据」。凭据只在本次会话的内存中处理。客户端会显示格式检查或实际认证的结果。内置版本尚未实现独立换取网易游戏会话。

回到「多人游戏」，网易目录使用同一张服务器列表。搜索框支持回车查询。取得有效会话后可以查找网络服、翻页、选服和角色，通过原有的「加入服务器」进入。网易要求登录或未返回角色时，列表会显示相应状态。

## 已知限制

- Classic CPE 天气类型暂不支持
- Bedrock RakNet/NetherNet 复杂连接场景待完善
