<p align="center">
<img src="assets/icon-256.png" alt="architectblocks-logo" width="18%"/>
</p>

<h1 align="center">ArchitectBlocks</h1>

<p align="center">Minecraft 物品总库插件：全物品检索 · 自定义物品上传 · 黑白名单 · 多语言搜索</p>

<div align="center">
    <img src="https://img.shields.io/github/v/release/Dalict/ArchitectBlocks?color=blue&label=release" alt="Release"/>
    <img src="https://img.shields.io/github/last-commit/Dalict/ArchitectBlocks" alt="GitHub last commit"/>
    <img src="https://img.shields.io/github/commit-activity/w/Dalict/ArchitectBlocks" alt="GitHub commit activity"/>
    <br>
    <img src="https://img.shields.io/github/languages/code-size/Dalict/ArchitectBlocks" alt="GitHub code size in bytes"/>
    <img src="https://img.shields.io/endpoint?url=https://ghloc.vercel.app/api/Dalict/ArchitectBlocks/badge?filter=.java$&label=lines%20of%20code&color=blue" alt="GitHub lines of code"/>
    <img src="https://img.shields.io/github/license/Dalict/ArchitectBlocks" alt="License"/>
    <br>
    <img src="https://img.shields.io/badge/Spigot%20%2F%20Paper-1.21%2B-857e00" alt="Server"/>
    <img src="https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk" alt="Java"/>
    <img src="https://img.shields.io/badge/MC-1.21%2B-green?logo=minecraft" alt="Minecraft"/>
</div>

---

## 插件定位

服务器里常有这样的需求：有玩家（或建筑队）申请做大型建筑，靠生存肝材料不现实，但你又不放心直接给创造——创造权限一旦下放，误伤地形、顺手拿走不该拿的东西、权限失控，都是隐患。

ArchitectBlocks 就是为这个场景设计的：**让信任的建筑师在不开创造的前提下，安全、隐蔽、可控地随意获取物品**。

- **对建筑师**：一个命令 / 一个菜单按钮，任意原版物品成组获取；聊天栏多语言搜索；页码跳转与记忆；管理员还能上传带 NBT 的特殊物品（材质包物品、插件物品）供他们取用
- **对生存玩家**：零影响——他们看不到这个菜单，世界里的经济与生存节奏不受干扰
- **对服主**：多层控制权始终在手里——谁能用（权限 / 名单 / 全局开关）、能拿什么（黑白名单模式、管理员物品开关、刷怪蛋开关、物品来源）、拿多快（冷却、数量）；所有配置游戏内实时生效，动过的每一笔都进数据库

一句话：**在不给创造的前提下，为你熟悉的建筑师们开一条不影响任何人的建筑高速路。**

---

## 特性

- **零维护物品列表**：启动时遍历 `Material.values()`，物品随服务器版本自动更新，出新版本不用改任何配置
- **全物品主菜单**（无分类）：36 个/页，循环翻页，纸张页码跳转（堆叠数 = 页码，支持超过 64）
- **聊天栏搜索**：点搜索后在聊天栏输入（消息截获不广播、超时可配、`cancel`/`取消` 放弃）；匹配英文 ID、英文名（en_us）、配置的任意多种语言
- **语言文件自动下载**：按 `search.languages` 从官方资源索引按文件下载 + 客户端 jar 抽取 en_us（BMCLAPI 优先回退 Mojang，SHA1 校验），随 MC 版本自动更新；翻译表同时缓存进数据库，MySQL 多服可共享
- **背包已有物品视图**：把想要的物品放进背包点漏斗即可筛出
- **每视图独立记忆页码**：主页 / 背包视图 / 搜索结果各记各的页，退出重进回到原界面
- **自定义物品上传**：管理上传页保持打开，点击背包物品即以 Base64 完整序列化存储（带 NBT 的材质包物品、插件物品原汁原味），玩家在主页点击获得一模一样的物品；按自定义名称搜索，未命名的回退基础物品语言文字；相同 NBT 自动去重
- **物品来源切换**：仅原版 / 仅上传 / 原版+上传（默认），一击切换即时生效
- **黑白名单双模式**：黑名单模式（默认，存入 ID 的隐藏）/ 白名单模式（仅存入 ID 的可见）；两套独立数据库表独立管理，界面单一入口随模式变色；**上传的自定义物品不受名单控制**
- **管理员物品开关**：仅创造可获取的 21 种技术方块（基岩、屏障、命令方块等）默认隐藏；刷怪蛋独立开关
- **垃圾桶**：全部槽位可放，退出销毁；`/mats trash`
- **取物冷却**（默认 1 秒）、点击数量可配置、自动适配物品最大堆叠
- **多种访问控制**：权限节点 / 玩家名单（独立 `players.yml`，`/mats add|remove` 管理，Tab 补全在线玩家）/ 全局允许所有人
- **SQLite / MySQL 数据库**：设置、名单、玩家界面状态、语言缓存、自定义物品全部入库
- **配置自愈与升级**：`config-version` 标记 + 关键键缺失自愈 + reload 时自动重建被误删的配置文件；玩家名单独立 `players.yml`，主配置注释永不丢失

---

## 安装

1. 将 `ArchitectBlocks-x.x.x.jar` 放入服务器 `plugins/` 文件夹
2. 重启服务器（SQLite 自动建库，语言文件自动下载）
3. `/mats` 打开菜单；`/mats admin` 进入管理界面

> 要求：Spigot / Paper 系 1.21+，Java 21+。

---

## 命令与权限

| 命令 | 别名 | 权限 | 说明 |
|------|------|------|------|
| `/mats` | `/materials`、`/cailiao` | `architectblocks.use` * | 打开物品菜单 |
| `/mats trash` | — | 同上 | 打开垃圾桶 |
| `/mats help` | — | — | 显示帮助 |
| `/mats admin` | — | `architectblocks.admin`（默认 OP） | 管理员设置界面 |
| `/mats add\|remove <玩家名>` | — | `architectblocks.admin` | 管理可用玩家名单（Tab 补全在线玩家） |
| `/mats reload` | — | `architectblocks.admin` | 重载配置 |

\* 拥有 `architectblocks.admin` 权限亦视为可用。

## 界面速查

| 槽位 | 功能 |
|------|------|
| 0 | 关闭（屏障） |
| 1 | 管理员设置（命令方块，仅管理员可见） |
| 4 | 搜索结果页显示当前关键词（告示牌） |
| 8 | 搜索（指南针）/ 搜索页返回主界面 |
| 9–44 | 物品区（点击获取） |
| 45 | 只显示背包已有物品（漏斗） |
| 48 / 50 | 上一页 / 下一页（循环翻页） |
| 49 | 选择页码（纸张堆叠数 = 页码） |
| 53 | 垃圾桶（岩浆桶） |

## 管理界面（`/mats admin`）

| 按钮 | 功能 |
|------|------|
| 允许刷怪蛋（苦力怕刷怪蛋） | 刷怪蛋显示开关 |
| 允许管理员物品（命令方块） | 21 种创造专属方块的显示开关 |
| 名单管理（羊毛随模式变色） | 管理当前模式对应的名单库：点背包物品存入 ID、点列表移出 |
| 上传物品管理（箱子） | 上传/删除自定义物品 |
| 物品来源（书架） | 仅原版 / 仅上传 / 原版+上传 循环切换 |
| 名单模式（拉杆） | 黑名单模式 ↔ 白名单模式 |

> 白名单模式下只有白名单内的原版物品可见；黑名单模式下存入的黑名单物品隐藏；上传的自定义物品不受名单控制。

---

## 存储结构

配置存于 `config.yml`（排序、数量、冷却、GUI、下载源等），访问名单存于 `players.yml`，其余全部入 SQLite（`data.db`）或 MySQL：

| 表 | 内容 |
|----|------|
| `ab_settings` | 设置键值（允许刷怪蛋、允许管理员物品、来源、名单模式） |
| `ab_blacklist` / `ab_whitelist` | 黑/白名单物品 ID（独立两库） |
| `ab_player_pages` / `ab_view` | 玩家各视图记忆页码与恢复目标 |
| `ab_lang` | 语言文件缓存（MySQL 多服共享） |
| `ab_custom_items` | 管理员上传的自定义物品（Base64 完整序列化） |

## 构建

```bash
mvn package
```

产物位于 `target/ArchitectBlocks-x.x.x.jar`。图标重新生成：`python assets/make_icon.py`。

## 设计说明

- 语言文件不打包进插件（避免版本过时）：优先读服务器 jar 自带 → 已下载文件 → 数据库缓存；`en_us.json` 不在官方资源索引中（客户端内置默认语言），由客户端 jar 流式抽取
- "管理员物品"名单是唯一需要随新版本维护的代码内列表，运营中发现的漏网之鱼用游戏内黑名单即时兜底

## 开源协议

MIT License，见 [LICENSE](LICENSE)。

## 支持与反馈

- 问题反馈：[GitHub Issues](https://github.com/Dalict/ArchitectBlocks/issues)
- 源码仓库：[https://github.com/Dalict/ArchitectBlocks](https://github.com/Dalict/ArchitectBlocks)
