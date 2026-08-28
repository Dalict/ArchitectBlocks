<p align="center">
<img src="https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/assets/icon-256.png" alt="architectblocks-logo" width="18%"/>
</p>

<h1 align="center">ArchitectBlocks</h1>

<p align="center">Minecraft 物品总库插件：全物品检索 · 自定义物品上传 · 黑白名单 · 多语言搜索 · 限时授权</p>

<div align="center">
    <img src="https://img.shields.io/github/v/release/Dalict/ArchitectBlocks?color=blue&label=release" alt="Release"/>
    <img src="https://img.shields.io/github/last-commit/Dalict/ArchitectBlocks" alt="GitHub last commit"/>
    <img src="https://img.shields.io/github/commit-activity/w/Dalict/ArchitectBlocks" alt="GitHub commit activity"/>
    <br>
    <img src="https://img.shields.io/github/languages/code-size/Dalict/ArchitectBlocks" alt="GitHub code size in bytes"/>
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

- **对建筑师**：一个命令 / 一个快捷物品，任意原版物品成组获取；聊天栏多语言搜索；页码跳转与记忆；管理员还能上传带 NBT 的特殊物品供他们取用；内置飞行开关/三档速度/永久夜视
- **对生存玩家**：零影响——他们看不到这个菜单，世界里的经济与生存节奏不受干扰
- **对服主**：多层控制权始终在手里——谁能用（权限/限时授权/全局开关）、能拿什么（黑白名单模式、管理员物品开关、刷怪蛋开关、物品来源）、拿多快（冷却、数量）；授权可限时（如 30 天后自动失效）；所有配置游戏内实时生效，动过的每一笔都进数据库

一句话：**在不给创造的前提下，为你熟悉的建筑师们开一条不影响任何人的建筑高速路。**

---

## 效果图

**主菜单**（全物品列表，点击获取）：

![主菜单](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/Homepage-screenshot.png)

**聊天栏搜索**（输入关键词即搜，支持中英多语言）：

![搜索](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/Search-screenshot.png)

**管理员设置**（开关、名单、上传、授权一站式管理）：

![管理设置](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/Management-Settings-Screenshot.png)

**飞行设置**（开关、三档速度、永久夜视、发放快捷物品）：

![飞行设置](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/fly-interface-screenshot.png)

**快捷物品**（知识之书，点击打开菜单，附魔光效）：

![快捷物品](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/quick-item-screenshot.png)

**页码跳转**（纸张堆叠数 = 页码，点击直达）：

![页码跳转](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/Select-page-number-screenshot.png)

**帮助命令**：

![帮助](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/screenshot/Help-screenshot.png)

---

## 特性

### 物品总库
- **零维护物品列表**：启动时遍历 `Material.values()`，随服务器版本自动更新
- **全物品主菜单**：36 个/页，循环翻页，纸张页码跳转（堆叠数=页码，支持超过 64）
- **聊天栏搜索**：消息截获不广播、超时可配；匹配英文 ID、英文名、配置的任意多种语言；语言文件从官方源自动下载（BMCLAPI 优先回退 Mojang，SHA1 校验，随版本更新），翻译表缓存进数据库
- **背包已有物品视图**：把想要的物品放进背包点漏斗即可筛出
- **每视图独立记忆页码**：主页/背包视图/搜索结果各记各的页

### 自定义物品
- **管理员上传**：上传页保持打开，点击背包物品即以 Base64 完整序列化存储（带 NBT 的材质包物品、插件物品原汁原味），相同 NBT 自动去重
- 上传物品的原版 **lore 原样保留**，插件提示追加在空行之后
- **物品来源切换**：仅原版 / 仅上传 / 原版+上传（默认）

### 名单体系
- **黑名单模式**（默认）：存入黑名单库 ID 的物品隐藏
- **白名单模式**：仅白名单库内存入 ID 的物品可获取
- **两套独立数据库表**，单一管理入口随模式变色
- 上传的自定义物品不受名单控制

### 授权体系
- **数据库限时授权**：`/mats add <玩家> [时长]`，支持 `30d`/`12h`/`45m`/`10s`（可组合如 `1d12h`，纯数字=天，缺省永久）
- **授权管理 GUI** + `/mats list` 命令：显示每个玩家剩余时间，点击移除
- Tab 补全：remove 补全已授权玩家名，add 补全在线玩家+授权玩家，add 第三参补全时长示例
- 到期自动失效并清理

### 内置快捷物品
- 独立 `quick-item` 配置段（材质/附魔光效），名称与描述在语言文件中
- 授权玩家上线自动发放；支持 PlaceholderAPI 变量
- **严格资格**：仅 use 权限 / 全局允许 / 数据库授权可持有——admin 权限不隐式放行
- 无资格自动收回；配置变更（指纹）自动回收旧版并换发新版
- 禁止放置、禁止放入容器（点击/Shift/数字键/拖拽全拦截）

### 实用工具
- **飞行设置菜单**：开关飞行、三档速度、永久夜视、发放快捷物品
- **垃圾桶**：全部槽位可放，退出销毁
- **取物冷却**（默认 1 秒）、数量可配置、自动适配最大堆叠
- **PlaceholderAPI 变量**：`%architectblocks_authorized%` / `%architectblocks_expires%` / `%architectblocks_flying%`
- **多语言支持**：显示文本独立在 `lang/zh_CN.yml`，主配置 `language` 选项切换
- **配置自愈**：键结构比对自动升级（删旧→释放新→回填用户值，注释保留）

---

## 安装

1. 将 `ArchitectBlocks-x.x.x.jar` 放入服务器 `plugins/` 文件夹
2. 重启服务器（SQLite 自动建库，语言文件自动下载）
3. `/mats add 你的名字` 给自己授权，获得快捷物品

> 要求：Spigot / Paper 系 1.21+，Java 21+。PlaceholderAPI 可选。

---

## 命令与权限

| 命令 | 权限 | 说明 |
|------|------|------|
| `/mats` | `architectblocks.use` * | 打开物品菜单（别名 `/materials`、`/cailiao`） |
| `/mats trash` | 同上 | 垃圾桶 |
| `/mats help` | — | 帮助 |
| `/mats admin` | `architectblocks.admin`（默认 OP） | 管理员设置界面 |
| `/mats add <玩家名> [时长]` | `architectblocks.admin` | 授权（`30d`/`12h`/`45m`/`10s`/纯数字=天/缺省永久） |
| `/mats remove <玩家名>` | `architectblocks.admin` | 移除授权 |
| `/mats list` | `architectblocks.admin` | 授权列表与剩余时间 |
| `/mats reload` | `architectblocks.admin` | 重载配置 |

\* 菜单与所有功能：admin **或** use **或** 授权 **或** 全局允许。**快捷物品发放是严格通道**：仅 use / 授权 / 全局允许（admin 不隐式放行）。

---

## 存储结构

| 位置 | 内容 |
|------|------|
| `config.yml` | 功能配置（语言选择、访问控制、数量/冷却、搜索语言、数据库、GUI材质） |
| `lang/zh_CN.yml` | 显示文本（GUI名称、消息、快捷物品名称/描述） |
| `plugins/ArchitectBlocks/lang/*.json` | 自动下载的 Minecraft 官方语言文件 |
| `data.db`（SQLite）| 设置、黑白名单、授权、页码记忆、语言缓存、自定义物品 |

---

## 构建

```bash
mvn package
```

## 贡献者

| 贡献者 | 角色 |
|--------|------|
| [Dalict](https://github.com/Dalict) | 作者 / 主要维护者 |
| ZTF3 | 贡献者 |

## 开源协议

MIT License，见 [LICENSE](https://raw.githubusercontent.com/Dalict/ArchitectBlocks/main/LICENSE)。

## 支持与反馈

- 问题反馈：[GitHub Issues](https://github.com/Dalict/ArchitectBlocks/issues)
- 源码仓库：[https://github.com/Dalict/ArchitectBlocks](https://github.com/Dalict/ArchitectBlocks)
