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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int CONFIG_VERSION = 3;

    private ItemRegistry itemRegistry;
    private LangManager lang;
    private ChatInputManager chatInput;
    private Database db;
    private QuickItemListener quickItem;
    private Object expansionRef;
    private final Map<UUID, Long> giveCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfig();
        migratePlayersYml();
        db = new Database(this);
        db.init();
        lang = new LangManager(this);
        itemRegistry = new ItemRegistry(this);
        itemRegistry.reload();
        // 异步下载配置的搜索语言文件（官方源，BMCLAPI 优先回退 Mojang）
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> lang.downloadConfiguredLanguages());
        chatInput = new ChatInputManager(this);
        quickItem = new QuickItemListener(this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);
        Bukkit.getPluginManager().registerEvents(quickItem, this);
        getCommand("mats").setExecutor(this);
        getCommand("mats").setTabCompleter(this);
        registerPapi();
        getLogger().info("ArchitectBlocks 已启用，作者 Dalict");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            for (String line : getConfig().getStringList("messages.help")) {
                sender.sendMessage(sendable(sender, line));
            }
            return true;
        }
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
            // reload 时补生成缺失的配置文件（删除配置后无需重启即可恢复）
            if (!new File(getDataFolder(), "config.yml").isFile()) {
                saveDefaultConfig();
                sender.sendMessage(color("&aconfig.yml 不存在，已重新生成默认配置。"));
            }
            if (!new File(getDataFolder(), "config.yml").isFile()) {
                saveDefaultConfig();
                sender.sendMessage(color("&aconfig.yml 不存在，已重新生成默认配置。"));
            }
            reloadConfig();
            upgradeConfig();
            lang.clearCache();
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

    /** 控制台去色，玩家原样 */
    private String sendable(CommandSender sender, String msg) {
        return sender instanceof Player ? color(msg) : ChatColor.stripColor(color(msg));
    }

    /**
     * 使用权限判定（按优先级）：
     * 管理员权限 > 全局允许所有人 > 数据库授权名单 > architectblocks.use 权限节点
     */
    public boolean canUse(Player player) {
        if (player.hasPermission(PERM_ADMIN)) {
            return true;
        }
        if (getConfig().getBoolean("access.allow-everyone", false)) {
            return true;
        }
        if (getConfig().getBoolean("access.use-player-list", true)
                && db.isAccessGranted(player.getName())) {
            return true;
        }
        return player.hasPermission(PERM_USE);
    }

    /** 授权剩余文本：永久 / N天 / 未授权 */
    public String getAccessExpireText(Player player) {
        String[] rec = db.getAccessRecord(player.getName());
        if (rec == null) {
            return getMessage("expire-none");
        }
        return expireText(Long.parseLong(rec[1]));
    }

    public String expireText(long expires) {
        if (expires == 0) {
            return getMessage("expire-permanent");
        }
        long days = (expires - System.currentTimeMillis()) / 86400000L + 1;
        return getMessage("expire-days").replace("%days%", String.valueOf(Math.max(1, days)));
    }

    /** 旧 players.yml 授权名单一次性迁移到数据库后重命名备份 */
    private void migratePlayersYml() {
        File oldFile = new File(getDataFolder(), "players.yml");
        if (!oldFile.isFile()) {
            return;
        }
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(oldFile);
            List<String> names = cfg.getStringList("players");
            for (String name : names) {
                db.grantAccess(name.trim(), 0);
            }
            if (!names.isEmpty()) {
                getLogger().info("已将 players.yml 的 " + names.size() + " 名授权玩家迁移到数据库");
            }
            java.nio.file.Files.move(oldFile.toPath(),
                    new File(getDataFolder(), "players.yml.migrated").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            getLogger().warning("players.yml 迁移失败: " + e.getMessage());
        }
    }

    /** 名单增删（写入数据库）；add 可带 [天数]，0/缺省为永久 */
    private boolean mutatePlayerList(String[] args, CommandSender sender, boolean add) {
        if (args.length < 2) {
            sender.sendMessage(color("&c用法: /mats " + (add ? "add" : "remove")
                    + " <玩家名>" + (add ? " [天数]" : "")));
            return true;
        }
        String target = args[1].trim();
        long days = 0;
        if (add && args.length >= 3) {
            try {
                days = Long.parseLong(args[2]);
                if (days < 0) days = 0;
            } catch (NumberFormatException e) {
                sender.sendMessage(color("&c天数必须是数字: " + args[2]));
                return true;
            }
        }
        if (add) {
            db.grantAccess(target, days);
            String expire = days == 0 ? getMessage("expire-permanent")
                    : getMessage("expire-days").replace("%days%", String.valueOf(days));
            sender.sendMessage(color("&a已授权 &f" + target + " &7(" + expire + ")"));
            Player online = Bukkit.getPlayerExact(target);
            if (online != null) {
                giveQuickItem(online);
            }
        } else {
            String[] rec = db.getAccessRecord(target);
            db.removeAccess(rec != null ? rec[0] : target);
            sender.sendMessage(color("&a已移除 &f" + target + " &a的授权。"));
        }
        return true;
    }

    private void sendColored(CommandSender sender, String msg) {
        if (sender instanceof Player) {
            sender.sendMessage(msg);
        } else {
            sender.sendMessage(ChatColor.stripColor(msg));
        }
    }

    /** 入口：恢复玩家上次退出的界面（主页/搜索结果，背包视图为临时视图不恢复），管理员界面不记忆 */
    public void openMenu(Player player) {
        String[] target = db.getView(player.getUniqueId());
        if (target != null && "search".equals(target[0])
                && target[1] != null && !target[1].isEmpty()) {
            openSearchMenu(player, db.getPage(player.getUniqueId(), "search"), target[1]);
        } else {
            openMainMenu(player, db.getPage(player.getUniqueId(), "main"), false);
        }
    }

    // ==================== 主菜单 / 背包视图 ====================

    /** 主菜单（invOnly=false）或背包已有物品视图（invOnly=true） */
    public void openMainMenu(Player player, int page, boolean invOnly) {
        List<ItemEntry> items = invOnly ? itemRegistry.getInventoryVisible(player) : itemRegistry.getVisible();
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage(invOnly ? "title-inv" : "title-main")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.MAIN, page, null, invOnly);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        // 每个视图独立记忆页码；背包视图为临时视图，不作为恢复目标
        db.setPage(player.getUniqueId(), invOnly ? "inv" : "main", page);
        if (!invOnly) {
            db.setView(player.getUniqueId(), "main", null);
        }

        fillItems(inv, items, page);
        buildCommonFrame(player, inv, pageCount, invOnly);
        player.openInventory(inv);
    }

    // ==================== 搜索结果 ====================

    public void openSearchMenu(Player player, int page, String keyword) {
        List<ItemEntry> items = itemRegistry.search(keyword);
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
        db.setPage(player.getUniqueId(), "search", page);
        db.setView(player.getUniqueId(), "search", keyword);

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
        List<ItemEntry> items;
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

    /** 管理主页：7 个功能按钮 */
    public void openAdmin(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN, 0, null, false);
        Inventory inv = Bukkit.createInventory(holder, 27, color(getMessage("title-admin")));
        holder.setInventory(inv);

        inv.setItem(10, icon(material("eggs-toggle", Material.CREEPER_SPAWN_EGG),
                color(getGuiConfigString("names.eggs-toggle", "&8[ &d允许刷怪蛋 &8]")),
                stateLine(itemRegistry.isAllowSpawnEggs()),
                color(getMessage("eggs-toggle-lore")),
                color(getMessage("click-toggle"))));
        inv.setItem(11, icon(material("admin-items-toggle", Material.COMMAND_BLOCK),
                color(getGuiConfigString("names.admin-items-toggle", "&8[ &4允许管理员物品 &8]")),
                stateLine(itemRegistry.isAllowAdminItems()),
                color(getMessage("admin-items-lore")),
                color(getMessage("click-toggle"))));
        // 名单管理：单一入口，图标与说明跟随当前名单模式变色
        boolean white = itemRegistry.isWhiteMode();
        inv.setItem(12, icon(white ? material("whitelist-button", Material.WHITE_WOOL)
                        : material("blacklist-button", Material.BLACK_WOOL),
                color(getGuiConfigString("names.list-manage", "&8[ &d名单管理 &8]")),
                color(getMessage("mode-current") + modeName(white)),
                color(getMessage("list-manage-lore"))));
        inv.setItem(14, icon(material("upload-button", Material.CHEST),
                color(getGuiConfigString("names.upload-list", "&8[ &b上传物品管理 &8]")),
                color(getMessage("upload-list-lore"))));
        inv.setItem(15, icon(material("source-button", Material.BOOKSHELF),
                color(getGuiConfigString("names.source-toggle", "&8[ &6物品来源 &8]")),
                color(getMessage("source-current") + sourceName(itemRegistry.getItemSource())),
                color(getMessage("click-cycle"))));
        inv.setItem(16, icon(white ? Material.WHITE_WOOL : Material.BLACK_WOOL,
                color(getGuiConfigString("names.mode-toggle", "&8[ &e名单模式 &8]")),
                color(getMessage("mode-current") + modeName(white)),
                color(getMessage("click-cycle"))));
        inv.setItem(22, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        fillPanes(inv, 18, 26, 22);
        player.openInventory(inv);
    }

    public String sourceName(String source) {
        switch (source) {
            case "vanilla": return color(getMessage("source-vanilla"));
            case "custom": return color(getMessage("source-custom"));
            default: return color(getMessage("source-both"));
        }
    }

    public String modeName(boolean white) {
        return color(white ? getMessage("mode-white") : getMessage("mode-black"));
    }

    /**
     * 名单/上传管理页。
     * 名单页跟随当前名单模式（黑名单模式管黑名单库，白名单模式管白名单库）：
     * 点击背包物品 = 存入ID到当前名单库；点击列表物品 = 删除ID移出名单。
     */
    public void openAdminList(Player player, int page, String mode, boolean invFilter) {
        List<ItemEntry> items = buildAdminListEntries(player, mode, invFilter);
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage("upload".equals(mode) ? "title-admin-upload" : "title-admin-list")
                .replace("%mode%", modeName(itemRegistry.isWhiteMode()))
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount))
                .replace("%filter%", invFilter ? getMessage("filter-on") : getMessage("filter-off")));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN_LIST, page, null, false, mode, invFilter);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        if (items.isEmpty()) {
            player.sendMessage(getMessage("upload".equals(mode) ? "upload-list-empty" : "list-empty"));
        }
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= items.size()) break;
            ItemEntry entry = items.get(index);
            List<String> lore = new ArrayList<>();
            if (entry.isCustom()) {
                lore.add(color(getMessage("custom-item-tag")));
                if (entry.customName != null) {
                    lore.add(color(getMessage("custom-name-line").replace("%name%", entry.customName)));
                }
            } else if (!"upload".equals(mode)) {
                lore.add(color(getMessage("list-state-line")));
            }
            lore.add("");
            lore.add(color(getMessage("upload".equals(mode) ? "upload-click-remove" : "list-click-remove")));
            // 自定义物品用原物克隆展示，原版物品用普通图标（修复原版条目 NPE）
            inv.setItem(ITEM_SLOT_START + i, entry.isCustom()
                    ? entryIcon(entry, lore)
                    : icon(entry.material, null, lore.toArray(new String[0])));
        }

        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back", "&8[ &e返回 &8]"))));
        inv.setItem(45, icon(material("filter-button", Material.HOPPER),
                color(getMessage("filter-name")) + (invFilter ? getMessage("filter-on") : getMessage("filter-off"))));
        if (pageCount > 1) {
            inv.setItem(48, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            inv.setItem(50, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
        }
        fillPanes(inv, 0, 8, 0, 8);
        fillPanes(inv, 45, 53, 45, 48, 50);
        player.openInventory(inv);
    }

    /** 名单/上传管理页的数据列表（含背包过滤） */
    public List<ItemEntry> buildAdminListEntries(Player player, String mode, boolean invFilter) {
        List<ItemEntry> items = new ArrayList<>();
        if ("upload".equals(mode)) {
            items.addAll(itemRegistry.getCustoms());
        } else {
            // 名单页跟随当前名单模式读取对应名单库
            java.util.Set<Material> list = itemRegistry.isWhiteMode()
                    ? db.getWhitelist() : db.getBlacklist();
            List<Material> sorted = new ArrayList<>(list);
            sorted.sort((a, b) -> a.name().compareTo(b.name()));
            for (Material mat : sorted) {
                items.add(ItemEntry.vanilla(mat));
            }
        }
        if (invFilter) {
            java.util.Set<Material> owned = new java.util.LinkedHashSet<>();
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    owned.add(item.getType());
                }
            }
            items.removeIf(e -> !owned.contains(e.material));
        }
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
        inv.setItem(4, icon(material("flight-menu-button", Material.FEATHER),
                color(getGuiConfigString("names.flight-menu", "&8[ &b飞行设置 &8]")),
                color(getMessage("flight-lore"))));
        inv.setItem(4, icon(material("flight-menu-button", Material.FEATHER),
                color(getGuiConfigString("names.flight-menu", "&8[ &b飞行设置 &8]")),
                color(getMessage("flight-lore"))));
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
        reservedTop.add(4);
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

    // ==================== 飞行设置菜单 ====================

    /** 飞行设置界面：开关飞行、三档速度、永久夜视开关。不记忆状态。 */
    public void openFlightMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.FLIGHT, 0, null, false);
        Inventory inv = Bukkit.createInventory(holder, 27, color(getMessage("title-flight")));
        holder.setInventory(inv);

        boolean allowFlight = player.getAllowFlight();
        inv.setItem(10, icon(material("flight-toggle", Material.FEATHER),
                color(getMessage(allowFlight ? "flight-on-name" : "flight-off-name")),
                color(getMessage(allowFlight ? "flight-on-state" : "flight-off-state")),
                color(getMessage("click-cycle"))));

        float[] speedValues = {0.1f, 0.25f, 0.5f};
        int[] speedSlots = {12, 13, 14};
        String[] speedKeys = {"speed-1-name", "speed-2-name", "speed-3-name"};
        for (int i = 0; i < 3; i++) {
            boolean current = Math.abs(player.getFlySpeed() - speedValues[i]) < 0.01f;
            inv.setItem(speedSlots[i], icon(material("fly-speed-button", Material.SUGAR),
                    color(getMessage(speedKeys[i])),
                    color(current ? getMessage("speed-current") : getMessage("speed-select"))));
        }

        boolean nightVision = player.hasPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION);
        inv.setItem(16, icon(material("night-vision-toggle", Material.ENDER_EYE),
                color(nightVision ? getMessage("nv-on-name") : getMessage("nv-off-name")),
                color(nightVision ? getMessage("nv-on-state") : getMessage("nv-off-state")),
                color(getMessage("click-toggle"))));

        inv.setItem(22, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back-main", "&8[ &e返回主界面 &8]"))));
        fillPanes(inv, 18, 26, 22);
        player.openInventory(inv);
    }

    /** 飞行菜单点击处理 */
    public void handleFlightClick(Player player, int slot) {
        switch (slot) {
            case 10: {
                boolean enable = !player.getAllowFlight();
                player.setAllowFlight(enable);
                player.setFlying(enable && player.isOnGround());
                player.sendMessage(color(enable
                        ? getMessage("flight-toggled-on")
                        : getMessage("flight-toggled-off")));
                reopenFlightMenu(player);
                return;
            }
            case 12:
            case 13:
            case 14: {
                float speed = slot == 12 ? 0.1f : slot == 13 ? 0.25f : 0.5f;
                player.setFlySpeed(speed);
                String key = slot == 12 ? "speed-1-name" : slot == 13 ? "speed-2-name" : "speed-3-name";
                player.sendMessage(color(getMessage("speed-set").replace("%speed%", getMessage(key))));
                reopenFlightMenu(player);
                return;
            }
            case 16: {
                org.bukkit.potion.PotionEffectType nv = org.bukkit.potion.PotionEffectType.NIGHT_VISION;
                if (player.hasPotionEffect(nv)) {
                    player.removePotionEffect(nv);
                    player.sendMessage(color(getMessage("nv-toggled-off")));
                } else {
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            nv, org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
                    player.sendMessage(color(getMessage("nv-toggled-on")));
                }
                reopenFlightMenu(player);
                return;
            }
            case 22:
                openMainMenu(player, db.getPage(player.getUniqueId(), "main"), false);
                return;
        }
    }

    private void reopenFlightMenu(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                openFlightMenu(player);
            }
        });
    }

    // ==================== 授权玩家管理菜单 ====================

    /** 授权名单管理页：显示玩家与剩余时间，点击移除授权。不记忆状态。 */
    public void openAccessList(Player player, int page) {
        List<String[]> records = db.loadAllAccess();
        int pageCount = Math.max(1, (records.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage("title-access-list")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ACCESS_LIST, page, null, false);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        if (records.isEmpty()) {
            player.sendMessage(getMessage("access-list-empty"));
        }
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= records.size()) break;
            String name = records.get(index)[0];
            long expires = Long.parseLong(records.get(index)[1]);
            inv.setItem(ITEM_SLOT_START + i, icon(Material.NAME_TAG, color("&f" + name),
                    color(getMessage("access-expire-line").replace("%expire%", expireText(expires))),
                    color(getMessage("list-click-remove"))));
        }

        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back-admin", "&8[ &a返回管理主页 &8]"))));
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

    /** 授权名单点击处理：移除授权 */
    public void handleAccessListClick(Player player, int page, int slot) {
        List<String[]> records = db.loadAllAccess();
        int pageCount = Math.max(1, (records.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == 48 && pageCount > 1) {
            openAccessList(player, (page - 1 + pageCount) % pageCount);
            return;
        }
        if (slot == 50 && pageCount > 1) {
            openAccessList(player, (page + 1) % pageCount);
            return;
        }
        int offset = slot - ITEM_SLOT_START;
        if (offset < 0 || offset >= PAGE_SIZE) {
            return;
        }
        int index = page * PAGE_SIZE + offset;
        if (index >= records.size()) {
            return;
        }
        String name = records.get(index)[0];
        db.removeAccess(name);
        player.sendMessage(color("&a已移除 &f" + name + " &a的授权。"));
        openAccessList(player, page);
    }

    // ==================== PAPI ====================

    private void registerPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("未检测到 PlaceholderAPI，变量功能不可用");
            return;
        }
        try {
            Class<?> cls = Class.forName("com.dalict.architectblocks.ABExpansion");
            Object expansion = cls.getDeclaredConstructor(ArchitectBlocks.class).newInstance(this);
            cls.getMethod("register").invoke(expansion);
            expansionRef = expansion;
            getLogger().info("已注册 PlaceholderAPI 变量 (%architectblocks_authorized% 等)");
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI 变量注册失败: " + t.getMessage());
        }
    }

    /** 给予快捷物品（已持有则跳过） */
    public void giveQuickItem(Player player) {
        if (quickItem == null || hasQuickItem(player)) {
            return;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(quickItem.createItem());
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    public boolean hasQuickItem(Player player) {
        if (quickItem == null) {
            return false;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (quickItem.isQuickItem(item)) {
                return true;
            }
        }
        return false;
    }

    public QuickItemListener getQuickItem() {
        return quickItem;
    }

    /** 填充物品网格：原版物品按数量 lore，自定义物品原样展示并附加获取提示 */
    private void fillItems(Inventory inv, List<ItemEntry> items, int page) {
        int amount = getClickAmount();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= items.size()) break;
            ItemEntry entry = items.get(index);
            if (entry.isCustom()) {
                List<String> lore = new ArrayList<>();
                lore.add(color(getMessage("custom-get-lore")
                        .replace("%amount%", String.valueOf(entry.custom.getAmount()))));
                inv.setItem(ITEM_SLOT_START + i, entryIcon(entry, lore));
            } else {
                Material mat = entry.material;
                int shown = Math.min(amount, mat.getMaxStackSize());
                String lore = color(getMessage("item-lore").replace("%amount%", String.valueOf(shown)));
                inv.setItem(ITEM_SLOT_START + i, icon(mat, null, lore));
            }
        }
    }

    /** 自定义物品图标：克隆原物品（保留 NBT 与名称），附加 lore */
    private ItemStack entryIcon(ItemEntry entry, List<String> lore) {
        ItemStack item = entry.custom.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
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
            List<String> subs = new ArrayList<>(Arrays.asList("help", "reload", "trash"));
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

    /**
     * 配置文件升级：config-version 低于当前版本时，把内置默认配置中缺失的键
     * 补充到用户配置（用户已有设置全部保留），然后写回并重载。
     */
    private void upgradeConfig() {
        // 必须从磁盘文件读版本：getConfig() 会并入插件内置默认值，
        // 直接 getInt 会把默认版本号当成用户配置导致升级永远不触发
        File configFile = new File(getDataFolder(), "config.yml");
        int version = 1;
        boolean keyMissing = false;
        if (configFile.isFile()) {
            org.bukkit.configuration.file.YamlConfiguration onDisk =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            version = onDisk.getInt("config-version", 1);
            // 防呆：版本号可能已达标但关键键缺失（历史写入异常等），同样强制合并
            keyMissing = onDisk.getString("messages.title-main") == null;
        }
        if (version >= CONFIG_VERSION && !keyMissing) {
            return;
        }
        if (keyMissing) {
            getLogger().warning("检测到配置缺少关键键，强制执行合并升级");
        }
        getConfig().options().copyDefaults(true);
        getConfig().set("config-version", CONFIG_VERSION);
        saveConfig();
        reloadConfig();
        getLogger().info("配置文件已从 v" + version + " 升级到 v" + CONFIG_VERSION
                + "，缺失的默认项已补充，原有设置保留");
    }
}
