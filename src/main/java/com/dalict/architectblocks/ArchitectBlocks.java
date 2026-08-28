package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
 * 贡献者: ZTF3
 */
public class ArchitectBlocks extends JavaPlugin {

    public static final String PERM_USE = "architectblocks.use";
    public static final String PERM_ADMIN = "architectblocks.admin";
    public static final String PERM_FILL = "architectblocks.fill";

    /** 物品区槽位从 9 开始，共 36 格（0-8 与 45-53 为边框区） */
    public static final int ITEM_SLOT_START = 9;
    public static final int PAGE_SIZE = 36;

    /** 当前默认配置的版本号，用于配置文件升级 */
    private ItemRegistry itemRegistry;
    private LangManager lang;
    private LangMessages langMsg;
    private ChatInputManager chatInput;
    private Database db;
    private QuickItemListener quickItem;
    private FillSession fillSession;
    private Object expansionRef;
    private final Map<UUID, Long> giveCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfig();
        langMsg = new LangMessages(this);
        langMsg.load();
        db = new Database(this);
        db.init();
        migratePlayersYml();
        lang = new LangManager(this);
        itemRegistry = new ItemRegistry(this);
        itemRegistry.reload();
        // 异步下载配置的搜索语言文件（官方源，BMCLAPI 优先回退 Mojang）
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> lang.downloadConfiguredLanguages());
        chatInput = new ChatInputManager(this);
        quickItem = new QuickItemListener(this);
        fillSession = new FillSession(this);
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
            List<String> help = langMsg != null ? langMsg.msgList("help")
                    : new ArrayList<>();
            if (help.isEmpty()) {
                help = getConfig().getStringList("messages.help");
            }
            for (String line : help) {
                sender.sendMessage(sendable(sender, ChatColor.stripColor(line).isEmpty() ? line : line));
            }
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(getMessage("no-permission"));
                return true;
            }
            return handleAccessCommand(args, sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(getMessage("no-permission"));
                return true;
            }
            sendColored(sender, getMessage("access-list-header"));
            java.util.List<String[]> records = db.loadAllAccess();
            if (records.isEmpty()) {
                sendColored(sender, getMessage("access-list-empty"));
            } else {
                for (String[] rec : records) {
                    sendColored(sender, getMessage("access-list-line")
                            .replace("%name%", rec[0])
                            .replace("%expire%", expireText(Long.parseLong(rec[1]))));
                }
                sendColored(sender, getMessage("access-list-footer")
                        .replace("%count%", String.valueOf(records.size())));
            }
            return true;
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
            reloadConfig();
            upgradeConfig();
            langMsg.load();
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

    /**
     * 填充工具使用资格：architectblocks.fill 权限 或 数据库授权含填充标记。
     * use/admin 权限不隐式放行——填充是独立授权的能力。
     */
    public boolean canFill(Player player) {
        if (player.hasPermission(PERM_FILL)) {
            return true;
        }
        if (getConfig().getBoolean("access.use-player-list", true)
                && db.hasFillGrant(player.getName())) {
            return true;
        }
        return false;
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
                db.grantAccess(name.trim(), 0, false);
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

    /** 授权命令：/mats add <名> [时长] / /mats remove <名>；时长支持单位（30d/12h/45m/10s，纯数字=天） */
    private boolean handleAccessCommand(String[] args, CommandSender sender) {
        boolean add = args[0].equalsIgnoreCase("add");
        if (args.length < 2) {
            sender.sendMessage(color("&c用法: /mats " + (add ? "add" : "remove")
                    + " <玩家名>" + (add ? " [时长]" : "")));
            return true;
        }
        String target = args[1].trim();
        boolean allowFill = args.length >= 4 && (args[3].equalsIgnoreCase("fill")
                || args[3].equalsIgnoreCase("true") || args[3].equalsIgnoreCase("是"));
        if (add) {
            long expires = 0;
            if (args.length >= 3 && !args[2].equalsIgnoreCase("fill")
                    && !args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("是")) {
                Long millis = parseDuration(args[2]);
                if (millis == null) {
                    sender.sendMessage(getMessage("duration-invalid").replace("%input%", args[2]));
                    return true;
                }
                expires = millis == 0 ? 0 : System.currentTimeMillis() + millis;
            }
            db.grantAccess(target, expires, allowFill);
            String expire = expires == 0 ? getMessage("expire-permanent") : expireText(expires);
            sender.sendMessage(color(getMessage("access-granted")
                    .replace("%name%", target).replace("%expire%", expire)));
            Player online = Bukkit.getPlayerExact(target);
            if (online != null && quickItem != null) {
                quickItem.give(online, false);
            }
        } else {
            String[] rec = db.getAccessRecord(target);
            if (rec == null) {
                sender.sendMessage(getMessage("access-not-found").replace("%name%", target));
                return true;
            }
            db.removeAccess(rec[0]);
            sender.sendMessage(color(getMessage("access-revoked").replace("%name%", rec[0])));
        }
        return true;
    }

    /**
     * 时长解析：30d/12h/45m/10s（可组合如 1d12h），纯数字=天。
     * 返回毫秒；0=永久；null=格式无效。
     */
    public Long parseDuration(String input) {
        input = input.trim().toLowerCase();
        if (input.isEmpty()) {
            return null;
        }
        if (input.equals("0") || input.equals("permanent") || input.equals("永久")) {
            return 0L;
        }
        if (input.matches("[0-9]+")) {
            return Long.parseLong(input) * 86400000L;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+)([dhms])").matcher(input);
        long total = 0;
        int matched = 0;
        while (m.find()) {
            matched++;
            long value = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d": total += value * 86400000L; break;
                case "h": total += value * 3600000L; break;
                case "m": total += value * 60000L; break;
                case "s": total += value * 1000L; break;
            }
        }
        return matched == 0 ? null : total;
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
        List<String> listLore = langMsg != null ? langMsg.msgList("list-manage-lore") : new ArrayList<>();
        listLore.add(0, color(getMessage("mode-current") + modeName(white)));
        inv.setItem(12, icon(white ? material("whitelist-button", Material.WHITE_WOOL)
                        : material("blacklist-button", Material.BLACK_WOOL),
                color(getGuiConfigString("names.list-manage", "&8[ &d名单管理 &8]")),
                listLore.toArray(new String[0])));
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
                color(getMessage(white ? "mode-hint-white" : "mode-hint-black")),
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

    // ==================== 填充工具 ====================

    /** 填充工具主界面（记忆） */
    public void openFillMenu(Player player) {
        FillSession.PlayerSession fs = fillSession.get(player);
        MenuHolder holder = new MenuHolder(MenuHolder.Type.FILL, 0, null, false);
        Inventory inv = Bukkit.createInventory(holder, 54, color(getMessage("title-fill")));
        holder.setInventory(inv);

        // 22 选填充方块
        Material fb = fs.fillBlock;
        String fbName = fb == Material.AIR ? getMessage("fill-block-air")
                : fb.name().toLowerCase();
        inv.setItem(22, icon(fb == Material.AIR ? Material.STRUCTURE_VOID : fb,
                color(getMessage("fill-select-block-name")),
                color(getMessage("fill-current-block").replace("%block%", fbName)),
                color(getMessage("fill-click-select"))));

        // 29-33 模式
        FillSession.Mode[] modes = {FillSession.Mode.HOLLOW, FillSession.Mode.OUTLINE,
                FillSession.Mode.REPLACE, FillSession.Mode.KEEP, FillSession.Mode.REPLACE_ALL};
        int[] modeSlots = {29, 30, 31, 32, 33};
        String[] modeNameKeys = {"fill-mode-hollow", "fill-mode-outline", "fill-mode-replace",
                "fill-mode-keep", "fill-mode-replace-all"};
        String[] modeDescKeys = {"fill-mode-hollow-desc", "fill-mode-outline-desc",
                "fill-mode-replace-desc", "fill-mode-keep-desc", "fill-mode-replace-all-desc"};
        for (int i = 0; i < modes.length; i++) {
            boolean current = fs.mode == modes[i];
            List<String> lore = new ArrayList<>();
            lore.add(color(getMessage(modeDescKeys[i])));
            lore.add(color(current ? getMessage("fill-mode-current") : getMessage("fill-mode-click")));
            inv.setItem(modeSlots[i], icon(
                    material("fill-mode-" + modes[i].name().toLowerCase(), Material.LEVER),
                    color(getMessage(modeNameKeys[i])),
                    lore.toArray(new String[0])));
        }

        // 47 A点 | 51 B点
        inv.setItem(47, icon(Material.BLUE_WOOL, color(getMessage("fill-point-a-name")),
                color(getMessage("fill-point-a-desc")),
                color(getMessage("fill-point-set").replace("%pos%",
                        fs.pointA == null ? getMessage("fill-not-set")
                                : formatPos(fs.pointA)))));
        inv.setItem(51, icon(Material.RED_WOOL, color(getMessage("fill-point-b-name")),
                color(getMessage("fill-point-b-desc")),
                color(getMessage("fill-point-set").replace("%pos%",
                        fs.pointB == null ? getMessage("fill-not-set")
                                : formatPos(fs.pointB)))));

        // 49 立即填充
        inv.setItem(49, icon(material("fill-execute-button", Material.WOODEN_AXE),
                color(getMessage("fill-execute-name")),
                color(getMessage("fill-execute-desc"))));

        // 40 清空方块
        inv.setItem(40, icon(Material.BUCKET, color(getMessage("fill-clear-blocks-name")),
                color(getMessage("fill-clear-blocks-desc"))));

        // 4 清空所有
        inv.setItem(4, icon(Material.TNT, color(getMessage("fill-reset-all-name")),
                color(getMessage("fill-reset-all-desc"))));

        // 0 关闭 | 8 返回主页
        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back-main", "&8[ &e返回主界面 &8]"))));

        fillPanes(inv, 0, 8, 0, 4, 8);
        fillPanes(inv, 45, 53, 47, 49, 51);
        player.openInventory(inv);
    }

    private String formatPos(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY()
                + "," + loc.getBlockZ();
    }

    /** 填充方块选择页：仅显示可放置方块 + 上传的可放置物品（页码记忆） */
    public void openFillSelect(Player player, int page) {
        List<ItemEntry> items = new ArrayList<>();
        // 空气（默认）永远第一个
        items.add(ItemEntry.vanilla(Material.AIR));
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir() && mat.isBlock() && mat.isOccluding()
                    && !itemRegistry.isAdminItem(mat) && itemRegistry.isVisible(mat)) {
                items.add(ItemEntry.vanilla(mat));
            }
        }
        // 上传的可放置自定义物品
        for (ItemEntry ce : itemRegistry.getCustoms()) {
            if (ce.material.isBlock()) {
                items.add(ce);
            }
        }
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage("title-fill-select")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.FILL_SELECT, page, null, false, "fill_select", false);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        db.setPage(player.getUniqueId(), "fill_select", page);

        FillSession.PlayerSession fs = fillSession.get(player);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= items.size()) break;
            ItemEntry entry = items.get(index);
            boolean selected = entry.isCustom() ? false
                    : entry.material == fs.fillBlock;
            List<String> lore = new ArrayList<>();
            if (entry.material == Material.AIR) {
                lore.add(color(getMessage("fill-block-air-desc")));
            }
            if (selected) {
                lore.add(color(getMessage("fill-block-selected")));
            }
            lore.add(color(getMessage("fill-block-click-select")));
            if (entry.isCustom()) {
                inv.setItem(ITEM_SLOT_START + i, entryIcon(entry, lore));
            } else {
                Material mat = entry.material;
                inv.setItem(ITEM_SLOT_START + i, icon(
                        mat == Material.AIR ? Material.STRUCTURE_VOID : mat,
                        null, lore.toArray(new String[0])));
            }
        }

        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back", "&8[ &e返回填充工具 &8]"))));
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

    /** 替换模式的目标方块选择页（与选方块页一致，但选择的是替换目标） */
    public void openFillReplace(Player player, int page) {
        List<ItemEntry> items = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir() && mat.isBlock() && mat.isOccluding()
                    && !itemRegistry.isAdminItem(mat) && itemRegistry.isVisible(mat)) {
                items.add(ItemEntry.vanilla(mat));
            }
        }
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        String title = color(getMessage("title-fill-replace")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(pageCount)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.FILL_REPLACE, page, null, false);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        db.setPage(player.getUniqueId(), "fill_replace", page);

        FillSession.PlayerSession fs = fillSession.get(player);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= items.size()) break;
            Material mat = items.get(index).material;
            boolean selected = mat == fs.replaceBlock;
            List<String> lore = new ArrayList<>();
            if (selected) {
                lore.add(color(getMessage("fill-block-selected")));
            }
            lore.add(color(getMessage("fill-block-click-select")));
            inv.setItem(ITEM_SLOT_START + i, icon(mat, null, lore.toArray(new String[0])));
        }

        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));
        inv.setItem(8, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back-fill", "&8[ &e返回填充工具 &8]"))));
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

    /** 填充工具点击处理 */
    public void handleFillClick(Player player, int slot) {
        FillSession.PlayerSession fs = fillSession.get(player);
        switch (slot) {
            case 0:
                player.closeInventory();
                return;
            case 4: {
                fillSession.clear(player);
                player.sendMessage(color(getMessage("fill-reset-all-done")));
                reopenFillMenu(player);
                return;
            }
            case 8:
                openMainMenu(player, db.getPage(player.getUniqueId(), "main"), false);
                return;
            case 22:
                openFillSelect(player, 0);
                return;
            case 29:
                fs.mode = FillSession.Mode.HOLLOW;
                player.sendMessage(color(getMessage("fill-mode-set")
                        .replace("%mode%", FillSession.Mode.HOLLOW.getDisplayName())));
                reopenFillMenu(player);
                return;
            case 30:
                fs.mode = FillSession.Mode.OUTLINE;
                player.sendMessage(color(getMessage("fill-mode-set")
                        .replace("%mode%", FillSession.Mode.OUTLINE.getDisplayName())));
                reopenFillMenu(player);
                return;
            case 31:
                // 替换模式 → 打开替换目标选择
                openFillReplace(player, 0);
                return;
            case 32:
                fs.mode = FillSession.Mode.KEEP;
                player.sendMessage(color(getMessage("fill-mode-set")
                        .replace("%mode%", FillSession.Mode.KEEP.getDisplayName())));
                reopenFillMenu(player);
                return;
            case 33:
                fs.mode = FillSession.Mode.REPLACE_ALL;
                player.sendMessage(color(getMessage("fill-mode-set")
                        .replace("%mode%", FillSession.Mode.REPLACE_ALL.getDisplayName())));
                reopenFillMenu(player);
                return;
            case 40: {
                // 清空方块：把 A-B 区域所有方块设为空气
                fs.fillBlock = Material.AIR;
                int result = fillSession.execute(player);
                if (result > 0) {
                    player.sendMessage(color(getMessage("fill-done").replace("%count%", String.valueOf(result))));
                } else if (result == -1) {
                    player.sendMessage(color(getMessage("fill-incomplete")));
                } else if (result == -2) {
                    player.sendMessage(color(getMessage("fill-different-world")));
                } else {
                    player.sendMessage(color(getMessage("fill-too-large").replace("%volume%", String.valueOf(-result))));
                }
                reopenFillMenu(player);
                return;
            }
            case 47:
                fs.pointA = player.getLocation();
                player.sendMessage(color(getMessage("fill-point-a-set")
                        .replace("%pos%", formatPos(fs.pointA))));
                reopenFillMenu(player);
                return;
            case 49: {
                int result = fillSession.execute(player);
                if (result > 0) {
                    player.sendMessage(color(getMessage("fill-done").replace("%count%", String.valueOf(result))));
                } else if (result == -1) {
                    player.sendMessage(color(getMessage("fill-incomplete")));
                } else if (result == -2) {
                    player.sendMessage(color(getMessage("fill-different-world")));
                } else {
                    player.sendMessage(color(getMessage("fill-too-large").replace("%volume%", String.valueOf(-result))));
                }
                reopenFillMenu(player);
                return;
            }
            case 51:
                fs.pointB = player.getLocation();
                player.sendMessage(color(getMessage("fill-point-b-set")
                        .replace("%pos%", formatPos(fs.pointB))));
                reopenFillMenu(player);
                return;
        }
    }

    /** 填充方块选择页点击处理 */
    public void handleFillSelectClick(Player player, int page, int slot) {
        if (slot == 0) {
            player.closeInventory();
            return;
        }
        if (slot == 8) {
            openFillMenu(player);
            return;
        }
        List<ItemEntry> items = new ArrayList<>();
        items.add(ItemEntry.vanilla(Material.AIR));
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir() && mat.isBlock() && mat.isOccluding()
                    && !itemRegistry.isAdminItem(mat) && itemRegistry.isVisible(mat)) {
                items.add(ItemEntry.vanilla(mat));
            }
        }
        for (ItemEntry ce : itemRegistry.getCustoms()) {
            if (ce.material.isBlock()) {
                items.add(ce);
            }
        }
        int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == 48 && pageCount > 1) {
            openFillSelect(player, (page - 1 + pageCount) % pageCount);
            return;
        }
        if (slot == 50 && pageCount > 1) {
            openFillSelect(player, (page + 1) % pageCount);
            return;
        }
        int offset = slot - ITEM_SLOT_START;
        if (offset < 0 || offset >= PAGE_SIZE) {
            return;
        }
        int index = page * PAGE_SIZE + offset;
        if (index >= items.size()) {
            return;
        }
        ItemEntry entry = items.get(index);
        FillSession.PlayerSession fs = fillSession.get(player);
        fs.fillBlock = entry.material;
        player.sendMessage(color(getMessage("fill-block-set").replace("%block%",
                entry.material == Material.AIR ? getMessage("fill-block-air")
                        : entry.material.name().toLowerCase())));
        openFillSelect(player, page);
    }

    /** 替换目标选择页点击处理 */
    public void handleFillReplaceClick(Player player, int page, int slot) {
        if (slot == 0) {
            player.closeInventory();
            return;
        }
        if (slot == 8) {
            openFillMenu(player);
            return;
        }
        List<Material> mats = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir() && mat.isBlock() && mat.isOccluding()
                    && !itemRegistry.isAdminItem(mat) && itemRegistry.isVisible(mat)) {
                mats.add(mat);
            }
        }
        int pageCount = Math.max(1, (mats.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == 48 && pageCount > 1) {
            openFillReplace(player, (page - 1 + pageCount) % pageCount);
            return;
        }
        if (slot == 50 && pageCount > 1) {
            openFillReplace(player, (page + 1) % pageCount);
            return;
        }
        int offset = slot - ITEM_SLOT_START;
        if (offset < 0 || offset >= PAGE_SIZE) {
            return;
        }
        int index = page * PAGE_SIZE + offset;
        if (index >= mats.size()) {
            return;
        }
        Material mat = mats.get(index);
        FillSession.PlayerSession fs = fillSession.get(player);
        fs.replaceBlock = mat;
        fs.mode = FillSession.Mode.REPLACE;
        player.sendMessage(color(getMessage("fill-mode-set")
                .replace("%mode%", FillSession.Mode.REPLACE.getDisplayName()
                        + " → " + mat.name().toLowerCase())));
        openFillMenu(player);
    }

    private void reopenFillMenu(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                openFillMenu(player);
            }
        });
    }

    // ==================== 公共组件 ====================

    /** 主菜单/背包视图边框：45 漏斗 | 48 上一页 | 49 选页 | 50 下一页 | 53 垃圾桶 */
    private void buildCommonFrame(Player player, Inventory inv, int pageCount, boolean invOnly) {
        boolean isAdmin = player.hasPermission(PERM_ADMIN);
        inv.setItem(0, icon(material("close-button", Material.BARRIER),
                color(getGuiConfigString("names.close", "&8[ &c关闭 &8]"))));

        inv.setItem(1, icon(material("flight-menu-button", Material.FEATHER),
                color(getGuiConfigString("names.flight-menu", "&8[ &b飞行设置 &8]")),
                color(getMessage("flight-lore"))));
        if (isAdmin) {
            inv.setItem(4, icon(material("admin-button", Material.COMMAND_BLOCK),
                    color(getGuiConfigString("names.admin", "&8[ &c管理员设置 &8]"))));
        }
        if (canFill(player)) {
            inv.setItem(7, icon(material("fill-button", Material.WOODEN_SHOVEL),
                    color(getGuiConfigString("names.fill-tool", "&8[ &6填充工具 &8]")),
                    color(getMessage("fill-lore"))));
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

        // 发放快捷物品按钮（4 号位顶排中央）：快捷物品禁用时自动隐藏
        if (getConfig().getBoolean("quick-item.enabled", true)) {
            inv.setItem(4, icon(material("give-quick-button", Material.KNOWLEDGE_BOOK),
                    color(getGuiConfigString("names.give-quick", "&8[ &e发放快捷物品 &8]")),
                    color(getMessage("give-quick-lore"))));
        }
        inv.setItem(22, icon(material("back-button", Material.COMPASS),
                color(getGuiConfigString("names.back-main", "&8[ &e返回主界面 &8]"))));
        fillPanes(inv, 0, 8, 4);
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
            case 4:
                giveQuickItem(player);
                reopenFlightMenu(player);
                return;
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
        player.sendMessage(color(getMessage("access-revoked").replace("%name%", name)));
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

    /** 快捷物品发放转发（带提示） */
    public void giveQuickItem(Player player) {
        if (quickItem != null) {
            quickItem.give(player, true);
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

    public FillSession getFillSession() {
        return fillSession;
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

    /** 自定义物品图标：克隆原物品（保留 NBT、名称与原版 lore），追加插件提示 */
    private ItemStack entryIcon(ItemEntry entry, List<String> extraLore) {
        ItemStack item = entry.custom.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore()
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            if (!lore.isEmpty()) {
                lore.add(""); // 原 lore 与插件提示之间空一行
            }
            lore.addAll(extraLore);
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

    public LangMessages getLangMsg() {
        return langMsg;
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
        return langMsg != null ? langMsg.msg(key)
                : color(getConfig().getString("messages." + key, "&c缺少消息配置: " + key));
    }

    /** 快捷物品名称（语言文件） */
    public String getQuickItemName() {
        return langMsg != null ? langMsg.quickItemName()
                : getConfig().getString("quick-item.name", "&8[ &a建筑物品菜单 &8]");
    }

    /** 快捷物品描述（语言文件） */
    public List<String> getQuickItemLore() {
        return langMsg != null ? langMsg.quickItemLore()
                : getConfig().getStringList("quick-item.lore");
    }

    /** 占位符应用：PlaceholderAPI 存在时替换变量，否则原样返回 */
    public String applyPapi(Player player, String text) {
        if (expansionRef == null || text == null) {
            return text;
        }
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) papi.getMethod("setPlaceholders",
                    Player.class, String.class).invoke(null, player, text);
        } catch (Throwable t) {
            return text;
        }
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
        String partial = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("help", "reload", "trash"));
            if (sender.hasPermission(PERM_ADMIN)) {
                subs.addAll(Arrays.asList("admin", "add", "remove", "list"));
            }
            for (String sub : subs) {
                if (sub.startsWith(partial)) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2 && sender.hasPermission(PERM_ADMIN)
                && args[0].equalsIgnoreCase("add")) {
            // add：补全在线玩家名（已有授权的排前，方便续期）
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(partial)) {
                    result.add(online.getName());
                }
            }
            for (String[] rec : db.loadAllAccess()) {
                if (rec[0].toLowerCase().startsWith(partial)
                        && result.stream().noneMatch(n -> n.equalsIgnoreCase(rec[0]))) {
                    result.add(rec[0]);
                }
            }
        } else if (args.length == 2 && sender.hasPermission(PERM_ADMIN)
                && args[0].equalsIgnoreCase("remove")) {
            // remove：补全已授权玩家名
            for (String[] rec : db.loadAllAccess()) {
                if (rec[0].toLowerCase().startsWith(partial)) {
                    result.add(rec[0]);
                }
            }
        } else if (args.length == 3 && sender.hasPermission(PERM_ADMIN)
                && args[0].equalsIgnoreCase("add")) {
            // add 的第三参：时长单位提示
            for (String unit : Arrays.asList("30d", "7d", "1d", "12h", "1h", "30m", "10m", "永久")) {
                if (unit.toLowerCase().startsWith(partial)) {
                    result.add(unit);
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
        if (key.startsWith("names.") && langMsg != null) {
            return langMsg.guiName(key.substring(6), def);
        }
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
     * 配置升级（键结构比对方案）：
     * 启动/reload 时拿用户配置与打包的默认配置比对键集合，
     * 不一致则：以默认结构为骨架、用户已设值覆盖 → 重建配置文件。
     * 无需维护版本号，用户自定义的值全部保留。
     */
    private void upgradeConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            return;
        }
        try {
            org.bukkit.configuration.file.YamlConfiguration user =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            org.bukkit.configuration.file.YamlConfiguration defaults =
                    new org.bukkit.configuration.file.YamlConfiguration();
            try (java.io.InputStream in = getResource("config.yml");
                 java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }
            java.util.Set<String> defaultKeys = flattenKeys(defaults);
            java.util.Set<String> userKeys = flattenKeys(user);
            if (defaultKeys.equals(userKeys)) {
                return;
            }
            // 1) 提取用户自定义值（与默认值不同的）
            java.util.Map<String, Object> overrides = new java.util.LinkedHashMap<>();
            for (String key : userKeys) {
                if (defaultKeys.contains(key)) {
                    Object userVal = user.get(key);
                    Object defVal = defaults.get(key);
                    if (!java.util.Objects.equals(userVal, defVal)) {
                        overrides.put(key, userVal);
                    }
                }
            }
            // 2) 删除旧配置
            java.nio.file.Files.deleteIfExists(configFile.toPath());
            // 3) 原汁原味释放新配置（注释保留）
            saveResource("config.yml", false);
            // 4) 把用户值填回去（文本级替换，注释不丢）
            applyOverrides(configFile, overrides);
            reloadConfig();
            java.util.Set<String> added = new java.util.HashSet<>(defaultKeys);
            added.removeAll(userKeys);
            java.util.Set<String> removed = new java.util.HashSet<>(userKeys);
            removed.removeAll(defaultKeys);
            getLogger().info("配置结构已自动更新：新增 " + added.size() + " 键，移除 "
                    + removed.size() + " 键，用户设置全部保留（含注释）");
        } catch (Exception e) {
            getLogger().warning("配置结构比对失败，继续使用现有配置: " + e.getMessage());
        }
    }

    private void applyOverrides(File file, java.util.Map<String, Object> overrides) {
        if (overrides.isEmpty()) {
            return;
        }
        try {
            java.util.List<String> lines = new ArrayList<>(
                    java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8));
            java.util.List<String> output = new ArrayList<>();
            java.util.Deque<String> pathStack = new java.util.ArrayDeque<>();
            java.util.Deque<Integer> indentStack = new java.util.ArrayDeque<>();
            int i = 0;
            while (i < lines.size()) {
                String line = lines.get(i);
                String trimmed = line.trim();
                int indent = line.length() - line.stripLeading().length();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    output.add(line);
                    i++;
                    continue;
                }
                while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
                    indentStack.pop();
                    pathStack.pop();
                }
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx < 0) {
                    output.add(line);
                    i++;
                    continue;
                }
                String key = trimmed.substring(0, colonIdx).trim();
                String valuePart = colonIdx + 1 < trimmed.length()
                        ? trimmed.substring(colonIdx + 1).trim() : "";
                String fullPath = buildFullPath(pathStack, key);
                if (valuePart.isEmpty()) {
                    int next = i + 1;
                    while (next < lines.size()
                            && (lines.get(next).trim().isEmpty() || lines.get(next).trim().startsWith("#"))) {
                        next++;
                    }
                    if (next < lines.size() && lines.get(next).trim().startsWith("- ")) {
                        if (overrides.containsKey(fullPath) && overrides.get(fullPath) instanceof List) {
                            output.add(line);
                            List<?> list = (List<?>) overrides.get(fullPath);
                            String itemIndent = " ".repeat(indent + 2);
                            for (Object item : list) {
                                output.add(itemIndent + "- " + yamlValue(item));
                            }
                            i = next;
                            while (i < lines.size() && lines.get(i).trim().startsWith("- ")) {
                                i++;
                            }
                            continue;
                        }
                        output.add(line);
                        i++;
                        while (i < lines.size() && lines.get(i).trim().startsWith("- ")) {
                            output.add(lines.get(i));
                            i++;
                        }
                        continue;
                    }
                    pathStack.push(key);
                    indentStack.push(indent);
                    output.add(line);
                    i++;
                } else {
                    if (overrides.containsKey(fullPath)) {
                        output.add(line.substring(0, indent) + key + ": " + yamlValue(overrides.get(fullPath)));
                    } else {
                        output.add(line);
                    }
                    i++;
                }
            }
            java.nio.file.Files.write(file.toPath(), output, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            getLogger().warning("用户配置回填失败: " + e.getMessage());
        }
    }

    private String buildFullPath(java.util.Deque<String> stack, String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : stack) {
            sb.append(part).append(".");
        }
        sb.append(key);
        return sb.toString();
    }

    private String yamlValue(Object val) {
        if (val == null) {
            return "''";
        }
        if (val instanceof Boolean || val instanceof Number) {
            return String.valueOf(val);
        }
        String s = String.valueOf(val);
        if (s.isEmpty()) {
            return "''";
        }
        return "'" + s.replace("'", "''") + "'";
    }

    /** 递归获取配置的全部叶子键 */
    private java.util.Set<String> flattenKeys(org.bukkit.configuration.file.YamlConfiguration cfg) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        collectKeys(cfg, "", keys);
        return keys;
    }

    private void collectKeys(org.bukkit.configuration.ConfigurationSection section,
                             String prefix, java.util.Set<String> keys) {
        for (String key : section.getKeys(false)) {
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collectKeys(section.getConfigurationSection(key), full, keys);
            } else {
                keys.add(full);
            }
        }
    }
}
