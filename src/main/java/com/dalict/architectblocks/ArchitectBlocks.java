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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ArchitectBlocks - 自动检索服务器全部物品并分类的材料菜单插件
 * 作者: Dalict
 */
public class ArchitectBlocks extends JavaPlugin {

    public static final String PERM_USE = "architectblocks.use";
    public static final String PERM_ADMIN = "architectblocks.admin";

    private CategoryManager categoryManager;
    private Database db;
    private final Map<Integer, Category> categorySlotMap = new HashMap<>();
    private final Map<UUID, Long> giveCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        db = new Database(this);
        db.init();
        categoryManager = new CategoryManager(this);
        categoryManager.reload();
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        getCommand("mats").setExecutor(this);
        getLogger().info("ArchitectBlocks 已启用，作者 Dalict");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(getMessage("no-permission"));
                return true;
            }
            reloadConfig();
            categoryManager.reload();
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
            openAdminMain(player);
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
        openCategoryMenu(player);
        return true;
    }

    /** 管理员无需额外授予 use 权限即可使用菜单 */
    public boolean canUse(Player player) {
        return player.hasPermission(PERM_USE) || player.hasPermission(PERM_ADMIN);
    }

    private void sendColored(CommandSender sender, String msg) {
        if (sender instanceof Player) {
            sender.sendMessage(msg);
        } else {
            sender.sendMessage(ChatColor.stripColor(msg));
        }
    }

    // ==================== 玩家菜单 ====================

    /** 分类选择菜单：分类按钮居中，底排为 管理员设置(仅管理员可见) / 背包已有 / 关闭 + 玻璃板 */
    public void openCategoryMenu(Player player) {
        categorySlotMap.clear();
        List<Category> enabled = new ArrayList<>();
        for (Category c : Category.values()) {
            if (categoryManager.isEnabled(c)) {
                enabled.add(c);
            }
        }
        int size = normalizeSize(getGuiConfigInt("category-menu-size", 27));
        while (size < 54 && enabled.size() > categoryCapacity(size)) {
            size += 9;
        }
        MenuHolder holder = new MenuHolder(MenuHolder.Type.CATEGORIES, null, 0);
        Inventory inv = Bukkit.createInventory(holder, size, color(getMessage("title-categories")));
        holder.setInventory(inv);

        int bottomStart = size - 9;
        int rowCap = 7;
        int placed = 0;
        for (int row = 0; placed < enabled.size() && 10 + row * 9 < bottomStart; row++) {
            int rowStart = 10 + row * 9;
            int inRow = Math.min(enabled.size() - placed, rowCap);
            int offset = (rowCap - inRow) / 2;
            for (int i = 0; i < inRow; i++) {
                Category c = enabled.get(placed++);
                int slot = rowStart + offset + i;
                List<String> lore = new ArrayList<>();
                for (String line : getConfig().getStringList("gui.category-lore")) {
                    lore.add(color(line.replace("%name%", c.getDisplayName())
                            .replace("%count%", String.valueOf(categoryManager.getDisplayItems(c).size()))));
                }
                String name = color(getGuiConfigString("names.category", "&8[ &f%name% &8]")
                        .replace("%name%", c.getDisplayName()));
                inv.setItem(slot, icon(categoryMaterial(c), name, lore.toArray(new String[0])));
                categorySlotMap.put(slot, c);
            }
        }

        boolean isAdmin = player.hasPermission(PERM_ADMIN);
        // 背包已有物品按钮：顶部中央
        inv.setItem(4, icon(material("inv-button", Material.HOPPER),
                color(getGuiConfigString("names.inv", "&8[ &e获取背包已有的物品 &8]")),
                color(getMessage("inv-lore"))));
        int closeSlot = bottomStart + 4;
        inv.setItem(closeSlot, icon(material("close-button", Material.CLOCK),
                color(getGuiConfigString("names.close", "&8[ &b关闭菜单 &8]"))));
        if (isAdmin) {
            inv.setItem(bottomStart, icon(material("admin-button", Material.COMMAND_BLOCK),
                    color(getGuiConfigString("names.admin", "&8[ &c管理员设置 &8]"))));
        }
        List<Integer> reserved = new ArrayList<>();
        reserved.add(closeSlot);
        if (isAdmin) {
            reserved.add(bottomStart);
        }
        fillPanes(inv, bottomStart, size - 1, toInts(reserved));
        player.openInventory(inv);
    }

    /** 物品分页菜单（每次翻页都会把当前页写入数据库，供下次恢复） */
    public void openItemMenu(Player player, Category category, int page) {
        List<Material> items = categoryManager.getDisplayItems(category);
        int size = normalizeSize(getGuiConfigInt("item-menu-size", 54));
        if (size < 18) size = 18;
        int pageSize = size - 9;
        int maxPage = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        if (page < 0) page = 0;
        if (page >= maxPage) page = maxPage - 1;

        String title = color(getMessage("title-items")
                .replace("%category%", category.getDisplayName())
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(maxPage)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ITEMS, category, page);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);
        db.setPage(player.getUniqueId(), category.getConfigKey(), page);

        fillItemGrid(inv, items, page, pageSize);
        int bottomStart = size - 9;
        int backSlot = bottomStart + 4;
        List<Integer> reserved = new ArrayList<>();
        reserved.add(backSlot);
        if (maxPage > 1) {
            int prevSlot = bottomStart;
            inv.setItem(prevSlot, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            reserved.add(prevSlot);
            int nextSlot = size - 1;
            inv.setItem(nextSlot, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
            reserved.add(nextSlot);
        }
        inv.setItem(backSlot, icon(material("back-button", Material.CLOCK),
                color(getGuiConfigString("names.back", "&8[ &a返回分类菜单 &8]"))));
        fillPanes(inv, bottomStart, size - 1, toInts(reserved));
        player.openInventory(inv);
    }

    /** 背包已有物品菜单（替代搜索：把想要的东西放进背包再点进来） */
    public void openInventoryMenu(Player player, int page) {
        List<Material> items = categoryManager.getInventoryItems(player);
        int size = normalizeSize(getGuiConfigInt("item-menu-size", 54));
        if (size < 18) size = 18;
        int pageSize = size - 9;
        int maxPage = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        if (page < 0) page = 0;
        if (page >= maxPage) page = maxPage - 1;

        MenuHolder holder = new MenuHolder(MenuHolder.Type.INV, null, page);
        Inventory inv = Bukkit.createInventory(holder, size, color(getMessage("title-inv")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(maxPage))));
        holder.setInventory(inv);

        if (!items.isEmpty()) {
            fillItemGrid(inv, items, page, pageSize);
        }
        int bottomStart = size - 9;
        int backSlot = bottomStart + 4;
        List<Integer> reserved = new ArrayList<>();
        reserved.add(backSlot);
        if (maxPage > 1) {
            int prevSlot = bottomStart;
            inv.setItem(prevSlot, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            reserved.add(prevSlot);
            int nextSlot = size - 1;
            inv.setItem(nextSlot, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
            reserved.add(nextSlot);
        }
        inv.setItem(backSlot, icon(material("back-button", Material.CLOCK),
                color(getGuiConfigString("names.back", "&8[ &a返回分类菜单 &8]"))));
        fillPanes(inv, bottomStart, size - 1, toInts(reserved));
        player.openInventory(inv);
    }

    // ==================== 管理员菜单 ====================

    /** 管理主页：黑名单列表 / 白名单列表 / 分类与功能开关 */
    public void openAdminMain(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN_MAIN, null, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, color(getMessage("title-admin-main")));
        holder.setInventory(inv);

        inv.setItem(12, icon(material("blacklist-button", Material.BLACK_WOOL),
                color(getGuiConfigString("names.blacklist-list", "&8[ &c物品黑名单列表 &8]")),
                color(getMessage("blacklist-list-lore")),
                color(getMessage("whitelist-list-lore"))));
        inv.setItem(14, icon(material("toggle-button", Material.COMPARATOR),
                color(getGuiConfigString("names.cats", "&8[ &6分类与功能开关 &8]")),
                color(getMessage("cats-lore"))));

        int bottomStart = 18;
        inv.setItem(bottomStart + 4, icon(material("close-button", Material.CLOCK),
                color(getGuiConfigString("names.close", "&8[ &b关闭菜单 &8]"))));
        fillPanes(inv, bottomStart, 26, bottomStart + 4);
        player.openInventory(inv);
    }

    /** 分类与功能开关页：10 个分类 + 管理员物品开关，点击即切换并实时生效 */
    public void openAdminCats(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN_CATS, null, 0);
        Inventory inv = Bukkit.createInventory(holder, 36, color(getMessage("title-admin-cats")));
        holder.setInventory(inv);

        List<Category> all = new ArrayList<>(Arrays.asList(Category.values()));
        int rowCap = 7;
        int placed = 0;
        for (int row = 0; placed < all.size() && 10 + row * 9 < 27; row++) {
            int rowStart = 10 + row * 9;
            int inRow = Math.min(all.size() - placed, rowCap);
            int offset = (rowCap - inRow) / 2;
            for (int i = 0; i < inRow; i++) {
                Category c = all.get(placed++);
                int slot = rowStart + offset + i;
                boolean on = categoryManager.isEnabled(c);
                inv.setItem(slot, icon(categoryMaterial(c),
                        color(getGuiConfigString("names.category", "&8[ &f%name% &8]")
                                .replace("%name%", c.getDisplayName())),
                        stateLine(on),
                        color(getMessage("click-toggle"))));
            }
        }
        boolean allowAdmin = categoryManager.isAllowAdminItems();
        inv.setItem(28, icon(material("admin-items-button", Material.BEDROCK),
                color(getGuiConfigString("names.admin-items", "&8[ &4允许管理员物品 &8]")),
                stateLine(allowAdmin),
                color(getMessage("admin-items-lore")),
                color(getMessage("click-toggle"))));

        int bottomStart = 27;
        inv.setItem(bottomStart + 4, icon(material("back-button", Material.CLOCK),
                color(getGuiConfigString("names.back-admin", "&8[ &a返回管理主页 &8]"))));
        fillPanes(inv, bottomStart, 35, bottomStart + 4, 28);
        player.openInventory(inv);
    }

    /**
     * 黑名单管理页：只显示已拉黑的物品，点击列表中的物品即移出黑名单。
     * 添加方式：保持本界面打开，直接点击自己背包中的物品即可加入黑名单。
     */
    public void openAdminList(Player player, int page) {
        List<Material> items = new ArrayList<>();
        for (Material mat : categoryManager.getAllItems()) {
            if (db.getFlag(mat) == MaterialFlag.BLACK) {
                items.add(mat);
            }
        }
        int size = 54;
        int pageSize = size - 9;
        int maxPage = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        if (page < 0) page = 0;
        if (page >= maxPage) page = maxPage - 1;

        String title = color(getMessage("title-admin-list-black")
                .replace("%page%", String.valueOf(page + 1))
                .replace("%max%", String.valueOf(maxPage)));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN_LIST, null, page);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        if (items.isEmpty()) {
            player.sendMessage(getMessage("list-empty"));
        }
        for (int i = 0; i < pageSize; i++) {
            int index = page * pageSize + i;
            if (index >= items.size()) break;
            Material mat = items.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(getMessage("state-black"));
            if (categoryManager.isAdminItem(mat)) {
                lore.add(getMessage("state-admin-item"));
            }
            lore.add("");
            lore.add(color(getMessage("list-click-remove")));
            inv.setItem(i, icon(mat, null, lore.toArray(new String[0])));
        }

        int bottomStart = size - 9;
        List<Integer> reserved = new ArrayList<>();
        if (maxPage > 1) {
            inv.setItem(bottomStart, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            reserved.add(bottomStart);
            inv.setItem(size - 1, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
            reserved.add(size - 1);
        }
        int backSlot = bottomStart + 4;
        inv.setItem(backSlot, icon(material("back-button", Material.CLOCK),
                color(getGuiConfigString("names.back-admin", "&8[ &a返回管理主页 &8]"))));
        reserved.add(backSlot);
        fillPanes(inv, bottomStart, size - 1, toInts(reserved));
        player.openInventory(inv);
    }

    // ==================== 公共逻辑 ====================

    /** 填充物品网格（lore 数量按最大堆叠自适应） */
    private void fillItemGrid(Inventory inv, List<Material> items, int page, int pageSize) {
        int amount = getClickAmount();
        for (int i = 0; i < pageSize; i++) {
            int index = page * pageSize + i;
            if (index >= items.size()) break;
            Material mat = items.get(index);
            int shown = Math.min(amount, mat.getMaxStackSize());
            String lore = color(getMessage("item-lore").replace("%amount%", String.valueOf(shown)));
            inv.setItem(i, icon(mat, null, lore));
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

    public CategoryManager getCategoryManager() {
        return categoryManager;
    }

    public Database getDb() {
        return db;
    }

    Map<Integer, Category> getCategorySlotMap() {
        return categorySlotMap;
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

    private int categoryCapacity(int size) {
        int rows = Math.max(0, (size - 9 - 10 + 8) / 9);
        return rows * 7;
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

    private int normalizeSize(int size) {
        if (size < 9) size = 9;
        if (size > 54) size = 54;
        return (size + 8) / 9 * 9;
    }

    private Material material(String key, Material def) {
        String name = getConfig().getString("gui.items." + key, def.name());
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("gui.items." + key + " 配置的材质无效: " + name + "，使用默认值 " + def);
            return def;
        }
    }

    private Material categoryMaterial(Category category) {
        String name = getConfig().getString("gui.icons." + category.getConfigKey(), "");
        if (name == null || name.isEmpty()) {
            return category.getIcon();
        }
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("gui.icons." + category.getConfigKey() + " 配置的材质无效: " + name
                    + "，使用默认值 " + category.getIcon());
            return category.getIcon();
        }
    }

    private int getGuiConfigInt(String key, int def) {
        return getConfig().getInt("gui." + key, def);
    }

    private String getGuiConfigString(String key, String def) {
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
