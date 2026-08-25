# ArchitectBlocks

一个 Minecraft Bukkit/Paper 插件：自动检索服务器核心注册的全部物品，按原版创造物品栏的方式分类，供建筑师（或任意权限组）在游戏内直接取用建筑材料。

**作者：Dalict** | 协议：MIT

## 特性

- **零维护物品列表**：启动时遍历 `Material.values()`，物品随服务器版本自动更新，出新版本不用改任何配置
- **10 个分类**，与原版创造物品栏对应：建筑方块 / 染色方块 / 自然方块 / 功能方块 / 红石方块 / 工具与使用物品 / 战斗用品 / 食物与饮品 / 原材料 / 刷怪蛋
- 每个分类可独立开关（默认只开前 5 个方块类）
- **分类可重叠**：原木/菌柄同时出现在建筑方块与自然方块，与原版行为一致（可关闭）
- **生存不可获取物品默认进黑名单**（基岩、屏障、结构方块、刷怪笼、龙蛋、测试方块等，可在配置中增删）
- 点击一次获取一组，数量可配置，自动适配物品最大堆叠（不可堆叠物品显示并给予 1 个）
- **排序可配置**：按种类（家族聚簇，接近创造栏观感）或按字母
- **GUI 全可配置**：菜单尺寸、玻璃板、按钮材质与名称、分类图标、lore、标题全部走 config.yml
- 无物品形态的技术方块（如洞穴藤蔓）自动排除，不会引发菜单异常
- 权限控制：`architectblocks.use` 才能使用，`architectblocks.admin` 才能重载

## 兼容性

- 服务端：Paper / Purpur / Pufferfish / Spigot / CraftBukkit（Bukkit 系均可）
- 版本：Minecraft **1.21+**（`api-version: 1.21`），Java 21+
- 不支持 Fabric / Forge / NeoForge

## 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/mats`（别名 `/materials`、`/cailiao`） | 打开材料菜单 | `architectblocks.use` |
| `/mats reload` | 重载配置 | `architectblocks.admin`（默认 OP） |

## 构建

需要 JDK 21+，二选一：

**Maven：**

```
mvn package
```

产物在 `target/ArchitectBlocks-bX.X.X.jar`。

**手动 javac（无 Maven 时）：**

```
javac --release 21 -encoding UTF-8 -cp <paper-api.jar> -d classes src/main/java/com/dalict/architectblocks/*.java
cp src/main/resources/* classes/
jar cf ArchitectBlocks.jar -C classes .
```

## 分类原理

不硬编码任何物品 ID。启动时读取服务器 `Material` 注册表，用**名称规则链**（颜色前缀+染色后缀、红石/功能/自然关键词、刷怪蛋后缀、可食用判定、耐久判定等）逐项归类，未命中规则的方块兜底进"建筑方块"。因此新版本方块永远不会缺失，最坏情况只是归类需要微调一条规则。

## 许可

[MIT](LICENSE)
