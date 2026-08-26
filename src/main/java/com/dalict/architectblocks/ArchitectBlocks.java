package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ArchitectBlocks - 自动检索服务器全部物品的物品菜单插件
 * 作者: Dalict
 */
public class ArchitectBlocks extends JavaPlugin {

    public static final String PERM_USE = "architectblocks.use";
    public static final String PERM_ADMIN = "architectblocks.admin";

    /** 物品区槽位从 9 开始，共 36 格（0-8 与 45-53 为边框区） */
    public static final int ITEM_SLOT_START = 9;
    public static final int PAGE_SIZE = 36;

    /** 当前默认配置的版本号，用于配置文件升级 */
    private static final int CONFIG_VERSION = 2;

    private ItemRegistry itemRegistry;
    private LangManager lang;
    private ChatInputManager chatInput;
    private Database db;
    private FileConfiguration playersConfig;
    private final Map<UUID, Long> giveCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfig();
        loadPlayersFile();
        db = new Database(this);
        db.init();
        lang = new LangManager(this);
        itemRegistry = new ItemRegistry(this);
        itemRegistry.reload();
        // 异步下载配置的搜索语言文件（官方源，BMCLAPI 优先回退 Mojang）
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> lang.downloadConfiguredLanguages());
        chatInput = new ChatInputManager(this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);
        getCommand("mats").setExecutor(this);
        getCommand("mats").setTabCompleter(this);
        getLogger().info("ArchitectBlocks 已启用，作者 Dalict");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(getMessage("no-permission"));
                return true;
            }
            return mutatePlayerList(args, sender, args[0].equalsIgnoreCase("add"));
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(getMessage("no-permission"));
                return true;
            }
            reloadConfig();
            itemRegistry.reload();
            sendColored(sender, getMessage("reloaded"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("该命令只能由玩家执行");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission(PERM_ADMIN)) {
                player.sendMessage(getMessage("no-permission"));
                return true;
            }
            openAdmin(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("trash")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("该命令只能由玩家执行");
                return true;
            }
            Player player = (Player) sender;
            if (!canUse(player)) {
                player.sendMessage(getMessage("no-permission"));
                return true;
            }
            openTrash(player);
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("该命令只能由玩家执行");
            return true;
        }
        Player player = (Player) sender;
        if (!canUse(player)) {
            player.sendMessage(getMessage("no-permission"));
            return true;
        }
        openMenu(player);
        return true;
    }

    /**
     * 使用权限判定（按优先级）：
     * 管理员权限 > 全局允许所有人 > 玩家名单 > architectblocks.use 权限节点
     */
    public boolean canUse(Player player) {
        if (player.hasPermission(PERM_ADMIN)) {
            return true;
        }
        if (getConfig().getBoolean("access.allow-everyone", false)) {
            return true;
        }
        if (getConfig().getBoolean("access.use-player-list", true)
                && playersConfig.getStringList("players").stream()
                .anyMatch(name -> name.equalsIgnoreCase(player.getName()))) {
            return true;
        }
        return player.hasPermission(PERM_USE);
    }

    /** 名单增删并保存到 config.yml */
    private boolean mutatePlayerList(String[] args, CommandSender sender, boolean add) {
        if (args.length != 2) {
            sender.sendMessage(color("&c用法: /mats " + (add ? "add" : "remove") + " <玩家名>"));
            return true;
        }
        String target = args[1].trim();
        List<String> players = new ArrayList<>(playersConfig.getStringList("players"));
        boolean exists = players.stream().anyMatch(n -> n.equalsIgnoreCase(target));
        if (add) {
            if (exists) {
                sender.sendMessage(getMessage("access-duplicate").replace("%player%", target));
                return true;
            }
            players.add(target);
        } else {
            if (!exists) {
                sender.sendMessage(getMessage("access-not-in-list").replace("%player%", target));
                return true;
            }
            players.removeIf(n -> n.equalsIgnoreCase(target));
        }
        playersConfig.set("players", players);
        try {
            playersConfig.save(new File(getDataFolder(), "players.yml"));
        } catch (java.io.IOException e) {
            sender.sendMessage(color("&c保存 players.yml 失败: " + e.getMessage()));
        }
        sender.sendMessage(getMessage("access-" + (add ? "added" : "removed")).replace("%player%", target));
        return true;
    }

    /** 独立的玩家名单文件，避免写回主配置时丢失注释 */
    private void loadPlayersFile() {
        File file = new File(getDataFolder(), "players.yml");
        playersConfig = YamlConfiguration.loadConfiguration(file);
        playersConfig.options().header("可使用 ArchitectBlocks 的玩家名单\n可用 /mats add|remove <玩家名> 管理");
        if (!file.exists()) {
            playersConfig.set("players", new ArrayList<String>());
            try {
                playersConfig.save(file);
            } catch (java.io.IOException e) {
                getLogger().warning("无法保存 players.yml: " + e.getMessage());
            }
        }
    }

    /**
     * 配置文件升级：config-version 低于当前版本时，把内置默认配置中缺失的键
     * 补充到用户配置（用户已有设置全部保留），然后写回并重载。
     */
    private void upgradeConfig() {
        int version = getConfig().getInt("config-version", 1);
        if (version >= CONFIG_VERSION) {
            return;
        }
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
        getLogger().info("配置文件已从 v" + version + " 升级到 v" + CONFIG_VERSION
                + "，缺失的默认项已补充，原有设置保留");
    }

    private void sendColored(CommandSender sender, String msg) {
        if (sender instanceof Player) {
            sender.sendMessage(msg);
        } else {
            sender.sendMessage(ChatColor.stripColor(msg));
        }
    }

    /** 入口：恢复玩家上次退出的界面（主页/背包视图/搜索结果），管理员界面不记忆 */
    public void openMenu(Player player) {
        String[] state = db.loadState(player.getUniqueId());
        if (state == null) {
            openMainMenu(player, 0, false);
            return;
        }
        String view = state[0];
        String keyword = state[1];
        int page;
        try {
            page = Integer.parseInt(state[2]);
        } catch (NumberFormatException e) {
            page = 0;
        }
        if ("inv".equals(view)) {
            openMainMenu(player, page, true);
        } else if ("search".equals(view) && keyword != null && !keyword.isEmpty()) {
            openSearchMenu(player, page, keyword);
        } else {
            openMainMenu(player, page, false);
        }
    }

    // ==================== 主菜单 / 背包视图 ====================

    /** 主菜单（invOnly=false）或背包已有物品视图（invOnly=true） */
    public void openMainMenu(Player player, int page, boolean invOnly) {
        List<Material> items = invOnly ? itemRegistry.getInventoryVisible(player) : itemRegistry.getVisible();
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage(invOnly ? "title-inv" : "title-main")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.MAIN, page, null, invOnly);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        if (!invOnly) {
            // 背包视图不写入记忆状态，退出后回到之前的界面
            db.saveState(player.getUniqueId(), "main", null, page);
        }

        fillItems(inv, items, page);
        buildCommonFrame(player, inv, pageCount, invOnly);
        player.openInventory(inv);
    }

    // ==================== 搜索结果 ====================

    public void openSearchMenu(Player player, int page, String keyword) {
        List<Material> items = itemRegistry.search(keyword);
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage("title-search")
                .replace("%keyword%", keyword)
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.SEARCH, page, keyword, false);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        db.saveState(player.getUniqueId(), "search", keyword, page);

        if (items.isEmpty()) {
            player.sendMessage(getMessage("search-no-result").replace("%keyword%", keyword));
        }
        fillItems(inv, items, page);
        buildSearchFrame(player, inv, pageCount, keyword);
        player.openInventory(inv);
    }

    // ==================== 页码跳转 ====================

    /** 列出纸张图标选择页码：堆叠数 = 目标页码，点击跳转 */
    public void openPageSelect(Player player, int selectPage, String sourceView, String keyword) {
        int total = pageCountOf(player, sourceView, keyword);
        int selectPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (selectPage < 0) selectPage = 0;
        if (selectPage >= selectPages) selectPage = selectPages - 1;

        String title = color(getMessage("title-page-select")
                .replace("%page%", String.valueOf(selectPage + 1))
                .replace("%max%", String.valueOf(selectPages)));
        // 用 invOnly/keyword 编码来源视图：inv=true 为背包视图；keyword!=null 为搜索结果；否则为主页
        boolean fromInv = "inv".equals(sourceView);
        String kw = "search".equals(sourceView) ? keyword : null;
        MenuHolder holder = new MenuHolder(MenuHolder.Type.PAGE_SELECT, selectPage, kw, fromInv);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int targetPage = selectPage * PAGE_SIZE + i;
            if (targetPage >= total) break;
            ItemStack paper = icon(material("page-select-button", Material.PAPER),
                    color(getMessage("page-select-name").replace("%page%", String.valueOf(targetPage + 1))),
                    color(getMessage("page-select-lore").replace("%page%", String.valueOf(targetPage + 1))));
            paper.setAmount(Math.max(1, Math.min(127, targetPage + 1)));
            inv.setItem(ITEM_SLOT_START + i, paper);
        }

        boolean isAdmin = player.hasPermission(PERM_ADMIN);
        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        if (isAdmin) {
            inv.setItem(1, icon(material("admin-button", Material.COMMAND_BLOCK),
                    color(getGuiConfigString("names.admin", "&8[ &c管理员设置 &8]"))));
        }
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back", "&8[ &e返回 &8]"))));
        if (selectPages > 1) {
            inv.setItem(48, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            inv.setItem(50, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
        }
        inv.setItem(53, icon(material("trash-button", Material.LAVA_BUCKET),
                color(getGuiConfigString("names.trash", "&8[ &6垃圾桶 &8]")),
                color(getMessage("trash-lore"))));
        fillPanes(inv, 0, 8, 0, 1, 8);
        fillPanes(inv, 45, 53, 48, 50, 53);
        player.openInventory(inv);
    }

    /** 计算某视图的总页数 */
    private int pageCountOf(Player player, String view, String keyword) {
        List<Material> items;
        if ("inv".equals(view)) {
            items = itemRegistry.getInventoryVisible(player);
        } else if ("search".equals(view)) {
            items = itemRegistry.search(keyword == null ? "" : keyword);
        } else {
            items = itemRegistry.getVisible();
        }
        return Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    // ==================== 垃圾桶 ====================

    /** 垃圾桶：除 0 关闭 / 8 返回外全部槽位可放置，退出即销毁 */
    public void openTrash(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.TRASH, 0, null, false);
        Inventory inv = Bukkit.createInventory(holder, 54, color(getMessage("title-trash")));
        holder.setInventory(inv);
        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back", "&8[ &e返回 &8]"))));
        player.openInventory(inv);
    }

    // ==================== 管理员界面 ====================

    /** 管理主页：刷怪蛋开关 / 管理员物品开关 / 黑名单列表 / 垃圾桶 */
    public void openAdmin(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN, 0, null, false);
        Inventory inv = Bukkit.createInventory(holder, 27, color(getMessage("title-admin")));
        holder.setInventory(inv);

        inv.setItem(11, icon(material("eggs-toggle", Material.CREEPER_SPAWN_EGG),
                color(getGuiConfigString("names.eggs-toggle", "&8[ &d允许刷怪蛋 &8]")),
                stateLine(itemRegistry.isAllowSpawnEggs()),
                color(getMessage("eggs-toggle-lore")),
                color(getMessage("click-toggle"))));
        inv.setItem(13, icon(material("admin-items-toggle", Material.COMMAND_BLOCK),
                color(getGuiConfigString("names.admin-items-toggle", "&8[ &4允许管理员物品 &8]")),
                stateLine(itemRegistry.isAllowAdminItems()),
                color(getMessage("admin-items-lore")),
                color(getMessage("click-toggle"))));
        inv.setItem(15, icon(material("blacklist-button", Material.BLACK_WOOL),
                color(getGuiConfigString("names.blacklist-list", "&8[ &c物品黑名单列表 &8]")),
                color(getMessage("blacklist-list-lore"))));
        inv.setItem(22, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        fillPanes(inv, 18, 26, 22);
        player.openInventory(inv);
    }

    /** 黑名单管理页：点击列表物品移出，界面开着点背包物品加入 */
    public void openAdminList(Player player, int page) {
        List<Material> items = collectBlacklisted();
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage("title-admin-list")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN_LIST, page, null, false);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        if (items.isEmpty()) {
            player.sendMessage(getMessage("list-empty"));
        }
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= items.size()) break;
            Material mat = items.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(getMessage("state-black"));
            lore.add("");
            lore.add(color(getMessage("list-click-remove")));
            inv.setItem(ITEM_SLOT_START + i, icon(mat, null, lore.toArray(new String[0])));
        }

        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back", "&8[ &e返回 &8]"))));
        if (pageCount > 1) {
            inv.setItem(48, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            inv.setItem(50, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
        }
        fillPanes(inv, 0, 8, 0, 8);
        fillPanes(inv, 45, 53, 48, 50);
        player.openInventory(inv);
    }

    /** 全量枚举中收集黑名单物品（字母序） */
    public List<Material> collectBlacklisted() {
        List<Material> items = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir() && db.getFlag(mat) == MaterialFlag.BLACK) {
                items.add(mat);
            }
        }
        items.sort((a, b) -> a.name().compareTo(b.name()));
        return items;
    }

    // ==================== 公共组件 ====================

    /** 主菜单/背包视图边框：45 漏斗 | 48 上一页 | 49 选页 | 50 下一页 | 53 垃圾桶 */
    private void buildCommonFrame(Player player, Inventory inv, int pageCount, boolean invOnly) {
        boolean isAdmin = player.hasPermission(PERM_ADMIN);
        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        if (isAdmin) {
            inv.setItem(1, icon(material("admin-button", Material.COMMAND_BLOCK),
                    color(getGuiConfigString("names.admin", "&8[ &c管理员设置 &8]"))));
        }
        inv.setItem(8, icon(material("search-button", Material.COMPASS),
                color(getGuiConfigString("names.search", "&8[ &b搜索 &8]")),
                searchLore(player)));
        inv.setItem(45, icon(material("inv-button", Material.HOPPER),
                color(invOnly ? getGuiConfigString("names.inv-back", "&8[ &e显示全部物品 &8]")
                        : getGuiConfigString("names.inv", "&8[ &e只显示背包已有的物品 &8]")),
                color(getMessage("inv-lore"))));
        if (pageCount > 1) {
            inv.setItem(48, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            inv.setItem(50, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
        }
        inv.setItem(49, icon(material("page-select-button", Material.PAPER),
                color(getGuiConfigString("names.page-select", "&8[ &f选择页码 &8]")),
                color(getMessage("page-select-button-lore"))));
        inv.setItem(53, icon(material("trash-button", Material.LAVA_BUCKET),
                color(getGuiConfigString("names.trash", "&8[ &6垃圾桶 &8]")),
                color(getMessage("trash-lore"))));
        List<Integer> reservedTop = new ArrayList<>();
        reservedTop.add(0);
        reservedTop.add(8);
        if (isAdmin) reservedTop.add(1);
        fillPanes(inv, 0, 8, toInts(reservedTop));
        List<Integer> reservedBottom = new ArrayList<>();
        reservedBottom.add(45);
        reservedBottom.add(49);
        reservedBottom.add(53);
        if (pageCount > 1) {
            reservedBottom.add(48);
            reservedBottom.add(50);
        }
        fillPanes(inv, 45, 53, toInts(reservedBottom));
    }

    /** 搜索结果边框：4 号告示牌显示关键词，8 号返回主界面，底排与主页一致 */
    private void buildSearchFrame(Player player, Inventory inv, int pageCount, String keyword) {
        boolean isAdmin = player.hasPermission(PERM_ADMIN);
        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        if (isAdmin) {
            inv.setItem(1, icon(material("admin-button", Material.COMMAND_BLOCK),
                    color(getGuiConfigString("names.admin", "&8[ &c管理员设置 &8]"))));
        }
        inv.setItem(4, icon(material("keyword-button", Material.OAK_SIGN),
                color(getMessage("keyword-name").replace("%keyword%", keyword)),
                color(getMessage("keyword-lore"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back-main", "&8[ &e返回主界面 &8]"))));
        if (pageCount > 1) {
            inv.setItem(48, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            inv.setItem(50, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
        }
        inv.setItem(49, icon(material("page-select-button", Material.PAPER),
                color(getGuiConfigString("names.page-select", "&8[ &f选择页码 &8]")),
                color(getMessage("page-select-button-lore"))));
        inv.setItem(53, icon(material("trash-button", Material.LAVA_BUCKET),
                color(getGuiConfigString("names.trash", "&8[ &6垃圾桶 &8]")),
                color(getMessage("trash-lore"))));
        List<Integer> reservedTop = new ArrayList<>();
        reservedTop.add(0);
        reservedTop.add(4);
        reservedTop.add(8);
        if (isAdmin) reservedTop.add(1);
        fillPanes(inv, 0, 8, toInts(reservedTop));
        List<Integer> reservedBottom = new ArrayList<>();
        reservedBottom.add(49);
        reservedBottom.add(53);
        if (pageCount > 1) {
            reservedBottom.add(48);
            reservedBottom.add(50);
        }
        fillPanes(inv, 45, 53, toInts(reservedBottom));
    }

    /** 搜索按钮 lore：%languages% 直接显示 search.languages 的内容 */
    private String[] searchLore(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add(color(getMessage("search-lore-line1")));
        StringBuilder langs = new StringBuilder();
        for (String code : getConfig().getStringList("search.languages")) {
            String normalized = code == null ? "" : code.trim().toLowerCase().replace('-', '_');
            if (normalized.isEmpty() || "en_us".equals(normalized)) {
                continue;
            }
            if (langs.length() > 0) {
                langs.append(color(getMessage("search-lore-sep")));
            }
            langs.append(normalized);
        }
        if (langs.length() == 0) {
            langs.append(color(getMessage("search-lore-none")));
        }
        lines.add(color(getMessage("search-lore-line2").replace("%languages%", langs.toString())));
        return lines.toArray(new String[0]);
    }

    /** 填充物品网格（lore 数量按最大堆叠自适应） */
    private void fillItems(Inventory inv, List<Material> items, int page) {
        int amount = getClickAmount();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= items.size()) break;
            Material mat = items.get(index);
            int shown = Math.min(amount, mat.getMaxStackSize());
            String lore = color(getMessage("item-lore").replace("%amount%", String.valueOf(shown)));
            inv.setItem(ITEM_SLOT_START + i, icon(mat, null, lore));
        }
    }

    private String stateLine(boolean on) {
        return color(on ? getMessage("state-enabled") : getMessage("state-disabled"));
    }

    /** 取物冷却检查；返回 true 表示允许给予 */
    public boolean checkGiveCooldown(Player player) {
        long cd = Math.max(0, getConfig().getLong("settings.give-cooldown-ms", 1000L));
        if (cd <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = giveCooldown.get(player.getUniqueId());
        if (last != null && now - last < cd) {
            return false;
        }
        giveCooldown.put(player.getUniqueId(), now);
        return true;
    }

    public ItemRegistry getItemRegistry() {
        return itemRegistry;
    }

    public LangManager getLang() {
        return lang;
    }

    public ChatInputManager getChatInput() {
        return chatInput;
    }

    public Database getDb() {
        return db;
    }

    int getClickAmount() {
        int amount = getConfig().getInt("settings.click-amount", 64);
        return Math.max(1, Math.min(64, amount));
    }

    String getMessage(String key) {
        return color(getConfig().getString("messages." + key, "&c缺少消息配置: " + key));
    }

    static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void fillPanes(Inventory inv, int from, int to, int... reserved) {
        if (!getConfig().getBoolean("gui.use-pane", true)) {
            return;
        }
        ItemStack pane = pane(material("pane", Material.WHITE_STAINED_GLASS_PANE));
        for (int i = from; i <= to; i++) {
            boolean skip = false;
            for (int r : reserved) {
                if (r == i) skip = true;
            }
            if (!skip && inv.getItem(i) == null) {
                inv.setItem(i, pane);
            }
        }
    }

    private int[] toInts(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("reload", "trash"));
            if (sender.hasPermission(PERM_ADMIN)) {
                subs.addAll(Arrays.asList("admin", "add", "remove"));
            }
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2 && sender.hasPermission(PERM_ADMIN)
                && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    result.add(online.getName());
                }
            }
        }
        return result;
    }

    Material material(String key, Material def) {
        String name = getConfig().getString("gui.items." + key, def.name());
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("gui.items." + key + " 配置的材质无效: " + name + "，使用默认值 " + def);
            return def;
        }
    }

    String getGuiConfigString(String key, String def) {
        return getConfig().getString("gui." + key, def);
    }

    static ItemStack icon(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(name);
            if (lore.length > 0) {
                List<String> loreList = new ArrayList<>();
                for (String line : lore) {
                    loreList.add(line);
                }
                meta.setLore(loreList);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(" "));
            item.setItemMeta(meta);
        }
        return item;
    }
}
