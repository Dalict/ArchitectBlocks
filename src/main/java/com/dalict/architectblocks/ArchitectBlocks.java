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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ArchitectBlocks - 自动检索服务器全部物品并分类的材料菜单插件
 * 作者: Dalict
 */
public class ArchitectBlocks extends JavaPlugin {

    public static final String PERM_USE = "architectblocks.use";
    public static final String PERM_ADMIN = "architectblocks.admin";

    private CategoryManager categoryManager;
    private final Map<Integer, Category> categorySlotMap = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
            if (sender instanceof Player) {
                sender.sendMessage(getMessage("reloaded"));
            } else {
                sender.sendMessage(org.bukkit.ChatColor.stripColor(getMessage("reloaded")));
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("该命令只能由玩家执行");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission(PERM_USE)) {
            player.sendMessage(getMessage("no-permission"));
            return true;
        }
        openCategoryMenu(player);
        return true;
    }

    /** 分类选择菜单：分类按钮从第二行起每行 7 个居中，底排为关闭按钮 + 玻璃板 */
    public void openCategoryMenu(Player player) {
        categorySlotMap.clear();
        List<Category> enabled = new ArrayList<>();
        for (Category c : Category.values()) {
            if (categoryManager.isEnabled(c)) {
                enabled.add(c);
            }
        }
        int size = normalizeSize(getGuiConfigInt("category-menu-size", 27));
        // 自动扩容：开启的分类超过当前尺寸容量时增大菜单，保证全部可见 (27格=7个, 36格=14个)
        while (size < 54 && enabled.size() > categoryCapacity(size)) {
            size += 9;
        }
        String title = color(getMessage("title-categories"));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.CATEGORIES, null, 0);
        Inventory inv = Bukkit.createInventory(holder, size, title);
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
                            .replace("%count%", String.valueOf(categoryManager.getItems(c).size()))));
                }
                String name = color(getGuiConfigString("names.category", "&8[ &f%name% &8]")
                        .replace("%name%", c.getDisplayName()));
                inv.setItem(slot, icon(categoryMaterial(c), name, lore.toArray(new String[0])));
                categorySlotMap.put(slot, c);
            }
        }

        int closeSlot = bottomStart + 4;
        inv.setItem(closeSlot, icon(material("close-button", Material.CLOCK),
                color(getGuiConfigString("names.close", "&8[ &b关闭菜单 &8]"))));
        fillPanes(inv, bottomStart, size - 1, closeSlot);
        player.openInventory(inv);
    }

    /** 物品分页菜单：物品区为 size-9 格，底排为 上一页(左下) / 返回(中) / 下一页(右下) */
    public void openItemMenu(Player player, Category category, int page) {
        List<Material> items = categoryManager.getItems(category);
        int size = normalizeSize(getGuiConfigInt("item-menu-size", 54));
        if (size < 18) size = 18; // 物品区至少 9 格，避免 pageSize 为 0
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

        int amount = getClickAmount();
        for (int i = 0; i < pageSize; i++) {
            int index = page * pageSize + i;
            if (index >= items.size()) break;
            Material mat = items.get(index);
            // 按物品实际最大堆叠数显示获取数量，非堆叠/小堆叠物品不会误导性地显示 64
            int shown = Math.min(amount, mat.getMaxStackSize());
            String lore = color(getMessage("item-lore").replace("%amount%", String.valueOf(shown)));
            inv.setItem(i, icon(mat, null, lore));
        }

        int bottomStart = size - 9;
        int backSlot = bottomStart + 4;
        List<Integer> reserved = new ArrayList<>();
        reserved.add(backSlot);
        if (page > 0) {
            int prevSlot = bottomStart;
            inv.setItem(prevSlot, icon(material("prev-button", Material.ARROW),
                    color(getGuiConfigString("names.prev", "&8[ &f上一页 &8]"))));
            reserved.add(prevSlot);
        }
        if ((page + 1) * pageSize < items.size()) {
            int nextSlot = size - 1;
            inv.setItem(nextSlot, icon(material("next-button", Material.ARROW),
                    color(getGuiConfigString("names.next", "&8[ &f下一页 &8]"))));
            reserved.add(nextSlot);
        }
        inv.setItem(backSlot, icon(material("back-button", Material.CLOCK),
                color(getGuiConfigString("names.back", "&8[ &a返回分类菜单 &8]"))));
        int[] reservedSlots = new int[reserved.size()];
        for (int i = 0; i < reservedSlots.length; i++) {
            reservedSlots[i] = reserved.get(i);
        }
        fillPanes(inv, bottomStart, size - 1, reservedSlots);
        player.openInventory(inv);
    }

    /** 当前尺寸下分类按钮的容量：每行 7 个，行从 10 开始到底排之前 */
    private int categoryCapacity(int size) {
        int rows = Math.max(0, (size - 9 - 10 + 8) / 9);
        return rows * 7;
    }

    /** 底排空位填玻璃板 */
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

    /** 尺寸规范化为 9 的倍数且在 9-54 之间 */
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

    /** 分类图标：优先读 gui.icons.<分类key>，未配置用内置默认 */
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

    int getClickAmount() {
        int amount = getConfig().getInt("settings.click-amount", 64);
        return Math.max(1, Math.min(64, amount));
    }

    CategoryManager getCategoryManager() {
        return categoryManager;
    }

    Map<Integer, Category> getCategorySlotMap() {
        return categorySlotMap;
    }

    String getMessage(String key) {
        return color(getConfig().getString("messages." + key, "&c缺少消息配置: " + key));
    }

    static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
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
