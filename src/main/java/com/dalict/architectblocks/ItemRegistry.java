package com.dalict.architectblocks;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 物品注册表：原版物品（启动时检索）+ 管理员上传的自定义物品（数据库 Base64）。
 * 显示过滤：名单模式(黑/白) > 物品标记 > 管理员物品开关 > 刷怪蛋开关；
 * 上传的自定义物品不受名单与开关控制，仅受物品来源开关控制。
 */
public class ItemRegistry {

    /** 真正的管理员物品：仅创造模式可获取的技术/管理方块 */
    private static final Set<String> ADMIN_ITEM_NAMES = new HashSet<>(Arrays.asList(
            "BEDROCK", "BARRIER", "LIGHT", "JIGSAW", "STRUCTURE_BLOCK", "STRUCTURE_VOID",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "DEBUG_STICK", "KNOWLEDGE_BOOK", "PETRIFIED_OAK_SLAB",
            "SPAWNER", "TRIAL_SPAWNER", "VAULT",
            "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL",
            "TEST_BLOCK", "TEST_INSTANCE_BLOCK", "END_PORTAL_FRAME"));

    private final ArchitectBlocks plugin;
    private final List<Material> all = new ArrayList<>();
    private final List<String> searchLangs = new ArrayList<>();
    private final List<ItemEntry> customs = new ArrayList<>();

    public ItemRegistry(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 重新检索全部物品 */
    public void reload() {
        all.clear();
        searchLangs.clear();
        for (String code : plugin.getConfig().getStringList("search.languages")) {
            String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.isEmpty() && !"en_us".equals(normalized) && !searchLangs.contains(normalized)) {
                searchLangs.add(normalized);
            }
        }
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir()) {
                all.add(mat);
            }
        }
        all.sort(currentComparator());
        reloadCustoms();
        plugin.getLogger().info("共检索到物品: " + all.size() + " 种, 自定义物品: " + customs.size() + " 个");
    }

    // ==================== 设置（数据库） ====================

    public boolean isAllowSpawnEggs() {
        return "true".equalsIgnoreCase(plugin.getDb().getSetting("allow_spawn_eggs", "true"));
    }

    public void setAllowSpawnEggs(boolean allow) {
        plugin.getDb().setSetting("allow_spawn_eggs", String.valueOf(allow));
    }

    public boolean isAllowAdminItems() {
        return "true".equalsIgnoreCase(plugin.getDb().getSetting("allow_admin_items", "false"));
    }

    public void setAllowAdminItems(boolean allow) {
        plugin.getDb().setSetting("allow_admin_items", String.valueOf(allow));
    }

    /** 名单模式: black=黑名单(默认,未标记的可见) / white=白名单(仅白名单内的可见) */
    public boolean isWhiteMode() {
        return "white".equalsIgnoreCase(plugin.getDb().getSetting("list_mode", "black"));
    }

    public void setWhiteMode(boolean white) {
        plugin.getDb().setSetting("list_mode", white ? "white" : "black");
    }

    /** 物品来源: both(原版+上传,默认) / vanilla(仅原版) / custom(仅上传) */
    public String getItemSource() {
        String v = plugin.getDb().getSetting("item_source", "both");
        return "vanilla".equals(v) || "custom".equals(v) || "both".equals(v) ? v : "both";
    }

    public void setItemSource(String source) {
        plugin.getDb().setSetting("item_source", source);
    }

    // ==================== 显示过滤 ====================

    public boolean isAdminItem(Material mat) {
        return ADMIN_ITEM_NAMES.contains(mat.name());
    }

    /** 原版物品可见性：白名单模式下仅白名单可见；黑名单模式下 黑名单隐藏 > 白名单显示 > 各开关 */
    public boolean isVisible(Material mat) {
        MaterialFlag flag = plugin.getDb().getFlag(mat);
        if (isWhiteMode()) {
            return flag == MaterialFlag.WHITE;
        }
        if (flag == MaterialFlag.BLACK) {
            return false;
        }
        if (flag == MaterialFlag.WHITE) {
            return true;
        }
        if (isAdminItem(mat)) {
            return isAllowAdminItems();
        }
        if (mat.name().endsWith("_SPAWN_EGG")) {
            return isAllowSpawnEggs();
        }
        return true;
    }

    // ==================== 列表构建 ====================

    /** 主列表：自定义物品在前（按名称排序），原版物品在后（按配置排序），受物品来源开关控制 */
    public List<ItemEntry> getVisible() {
        List<ItemEntry> out = new ArrayList<>();
        String source = getItemSource();
        if (!"vanilla".equals(source)) {
            out.addAll(customs);
        }
        if (!"custom".equals(source)) {
            for (Material mat : all) {
                if (isVisible(mat)) {
                    out.add(ItemEntry.vanilla(mat));
                }
            }
        }
        return out;
    }

    /** 背包已有物品列表 */
    public List<ItemEntry> getInventoryVisible(Player player) {
        Set<Material> owned = new LinkedHashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                owned.add(item.getType());
            }
        }
        List<ItemEntry> out = new ArrayList<>();
        for (ItemEntry entry : getVisible()) {
            if (owned.contains(entry.material)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 搜索：自定义物品按其显示名（无显示名则按基础物品的语言文字）匹配，原版物品走语言匹配 */
    public List<ItemEntry> search(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<ItemEntry> out = new ArrayList<>();
        for (ItemEntry entry : getVisible()) {
            if (entry.isCustom()) {
                if (entry.customName != null && entry.customName.toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(entry);
                } else if (matches(entry.material, q)) {
                    out.add(entry);
                }
            } else if (isVisible(entry.material) && matches(entry.material, q)) {
                out.add(entry);
            }
        }
        return out;
    }

    private boolean matches(Material mat, String q) {
        if (mat.name().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        String key = translationKey(mat);
        if (langContains("en_us", key, q)) {
            return true;
        }
        for (String code : searchLangs) {
            if (langContains(code, key, q)) {
                return true;
            }
        }
        return false;
    }

    private boolean langContains(String code, String key, String q) {
        Map<String, String> lang = plugin.getLang().get(code);
        if (lang == null || lang.isEmpty()) {
            return false;
        }
        String v = lang.get(key);
        return v != null && v.toLowerCase(Locale.ROOT).contains(q);
    }

    private String translationKey(Material mat) {
        try {
            return mat.translationKey();
        } catch (Throwable t) {
            return (mat.isBlock() ? "block." : "item.") + "minecraft." + mat.name().toLowerCase(Locale.ROOT);
        }
    }

    // ==================== 自定义物品（上传） ====================

    /** 从数据库重新加载自定义物品 */
    public void reloadCustoms() {
        customs.clear();
        for (String[] row : plugin.getDb().loadCustomRaw()) {
            int id;
            try {
                id = Integer.parseInt(row[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            ItemStack item = fromBase64(row[1]);
            if (item == null) {
                plugin.getLogger().warning("自定义物品 #" + id + " 解码失败，已跳过（数据库记录保留）");
                continue;
            }
            customs.add(ItemEntry.custom(id, item, row[2]));
        }
        customs.sort(Comparator.comparing(e -> sortKey(e).toLowerCase(Locale.ROOT)));
    }

    private String sortKey(ItemEntry e) {
        return e.customName != null ? e.customName : e.material.name();
    }

    /** 上传一个物品（完整 NBT）；返回 false 表示已存在相同物品 */
    public boolean addCustom(ItemStack item) {
        String base64 = toBase64(item);
        if (base64 == null) {
            return false;
        }
        for (ItemEntry e : customs) {
            String existing = toBase64(e.custom);
            if (base64.equals(existing)) {
                return false;
            }
        }
        String name = resolveCustomName(item);
        plugin.getDb().addCustom(base64, name);
        reloadCustoms();
        return true;
    }

    public void removeCustom(int id) {
        plugin.getDb().removeCustom(id);
        reloadCustoms();
    }

    public List<ItemEntry> getCustoms() {
        return customs;
    }

    /** 自定义物品的显示名：有自定义名称取其纯文本，否则为 null（搜索回退到基础物品语言文字） */
    private String resolveCustomName(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String stripped = ChatColor.stripColor(meta.getDisplayName());
                if (stripped != null && !stripped.trim().isEmpty()) {
                    return stripped.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String toBase64(ItemStack item) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             BukkitObjectOutputStream data = new BukkitObjectOutputStream(out)) {
            data.writeObject(item);
            data.flush();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    public static ItemStack fromBase64(String base64) {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            Object obj = in.readObject();
            return obj instanceof ItemStack ? (ItemStack) obj : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 排序 ====================

    /** 排序: type=按种类(家族聚簇，接近创造栏观感), alphabetical=字母序 */
    private Comparator<Material> currentComparator() {
        String mode = plugin.getConfig().getString("settings.sort", "type");
        if ("alphabetical".equalsIgnoreCase(mode)) {
            return Comparator.comparing(Enum::name);
        }
        return Comparator.comparing(ItemRegistry::family).reversed().thenComparing(Enum::name);
    }

    private static String family(Material mat) {
        String n = mat.name();
        int idx = n.lastIndexOf('_');
        return idx < 0 ? n : n.substring(idx + 1);
    }
}
