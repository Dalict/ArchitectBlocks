# ArchitectBlocks

一个 Minecraft Bukkit/Paper 插件：自动检索服务器核心注册的全部物品，提供可搜索、可跳页、带记忆的物品菜单，供建筑师（或任意授权玩家）直接取用。

**作者：Dalict** | 协议：MIT | 当前版本 b1.10.1

## 特性

- **零维护物品列表**：启动时遍历 `Material.values()`，物品随服务器版本自动更新，出新版本不用改任何配置
- **全物品主菜单**（无分类）：36 个/页，循环翻页，纸张页码跳转（堆叠数=页码，支持超过 64）
- **聊天栏搜索**：点搜索按钮后在聊天栏输入（消息截获不广播、30 秒超时、`cancel`/`取消` 放弃）；匹配英文 ID、英文名（en_us）与配置的任意多种语言
- **语言文件自动下载**：按 `search.languages` 从官方资源索引按文件下载（BMCLAPI 优先回退 Mojang，SHA1 校验），随 MC 版本自动更新；语言表同时缓存进数据库，MySQL 多服可共享
- **背包已有物品视图**：把想要的物品放进背包点漏斗即可筛出，替代翻找
- **每个视图独立记忆页码**：主页/背包/搜索各记各的页，退出重进回到原界面
- **管理员 GUI**（`/mats admin`）：允许刷怪蛋 / 允许管理员物品 / 物品黑名单管理（界面开着点背包物品拉黑、点列表移出）
- **管理员物品默认隐藏**：仅创造模式可获取的 21 种技术/管理方块，开关控制
- **垃圾桶**：`/mats trash` 或菜单按钮，放入即销毁
- **取物冷却**（默认 1 秒）、点击数量可配置、自动适配物品最大堆叠
- **多种访问控制**：权限节点 / 玩家名单（独立 `players.yml`，`/mats add|remove` 管理，Tab 补全在线玩家）/ 全局允许所有人
- **SQLite / MySQL 数据库**：开关设置、黑名单、页码记忆、语言缓存全部入库
- **配置自动升级**：`config-version` 标记，新版默认项自动补入旧配置，用户设置保留

## 兼容性

- 服务端：Paper / Purpur / Pufferfish / Spigot / CraftBukkit（Bukkit 系均可）
- 版本：Minecraft **1.21+**（`api-version: 1.21`），Java 21+
- 不支持 Fabric / Forge / NeoForge

## 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/mats`（别名 `/materials`、`/cailiao`） | 打开物品菜单 | `architectblocks.use`（或玩家名单/全局允许；拥有 admin 权限亦视为可用） |
| `/mats admin` | 打开管理员设置界面 | `architectblocks.admin`（默认 OP） |
| `/mats trash` | 打开垃圾桶 | 同 `/mats` |
| `/mats add\|remove <玩家名>` | 管理可用玩家名单 | `architectblocks.admin` |
| `/mats reload` | 重载配置 | `architectblocks.admin` |

## 构建

需要 JDK 21+，二选一：

**Maven：**

```
mvn package
```

**手动 javac（无 Maven 时）：**

```
javac --release 21 -encoding UTF-8 -cp <paper-api.jar> -d classes src/main/java/com/dalict/architectblocks/*.java
cp src/main/resources/* classes/
jar cf ArchitectBlocks.jar -C classes .
```

## 设计说明

- 物品归类不存在：主菜单就是全部物品；显示与否由 黑名单 > 白名单(历史数据) > 管理员物品开关 > 刷怪蛋开关 决定
- 语言文件不打包进插件（避免版本过时），来源顺序：服务器 jar → 下载目录 → 数据库缓存
- 管理员物品名单（21 项）是唯一需要随新版本维护的代码内列表，运营中可用游戏内黑名单即时兜底

## 许可

[MIT](LICENSE)
