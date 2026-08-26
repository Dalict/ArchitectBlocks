package com.dalict.architectblocks;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 启动时检索服务器全部物品，提供统一的主列表。
 * 显示过滤：黑名单 > 白名单 > 管理员物品开关 > 刷怪蛋开关。
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
    /** 创造物品栏排序索引：来自 Registry.ITEM 的注册顺序 */
    private final Map<Material, Integer> creativeIndex = new HashMap<>();

    public ItemRegistry(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 重新检索全部物品 */
    public void reload() {
        all.clear();
        creativeIndex.clear();
        int idx = 0;
        try {
            for (var itemType : Registry.ITEM) {
                Material mat = Material.matchMaterial(String.valueOf(itemType.getKey()));
                if (mat != null) {
                    creativeIndex.put(mat, idx++);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("无法读取物品注册表顺序，创造序排序退化为字母序: " + t.getMessage());
        }
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir()) {
                all.add(mat);
            }
        }
        all.sort(currentComparator());
        plugin.getLogger().info("共检索到物品: " + all.size() + " 种");
    }

    // ---------- 开关设置（数据库） ----------

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

    // ---------- 显示过滤 ----------

    public boolean isAdminItem(Material mat) {
        return ADMIN_ITEM_NAMES.contains(mat.name());
    }

    public boolean isVisible(Material mat) {
        MaterialFlag flag = plugin.getDb().getFlag(mat);
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

    /** 主列表：全部可见物品 */
    public List<Material> getVisible() {
        List<Material> out = new ArrayList<>();
        for (Material mat : all) {
            if (isVisible(mat)) {
                out.add(mat);
            }
        }
        return out;
    }

    /** 背包已有物品列表 */
    public List<Material> getInventoryVisible(Player player) {
        Set<Material> owned = new LinkedHashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                owned.add(item.getType());
            }
        }
        List<Material> out = new ArrayList<>();
        for (Material mat : all) {
            if (owned.contains(mat) && isVisible(mat)) {
                out.add(mat);
            }
        }
        return out;
    }

    /** 搜索：匹配 枚举名 / en_us / zh_cn / 玩家客户端语言 */
    public List<Material> search(String query, String localeCode) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Material> out = new ArrayList<>();
        for (Material mat : all) {
            if (!isVisible(mat)) {
                continue;
            }
            if (matches(mat, q, localeCode)) {
                out.add(mat);
            }
        }
        return out;
    }

    private boolean matches(Material mat, String q, String localeCode) {
        if (mat.name().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        String key = translationKey(mat);
        if (langContains("en_us", key, q)) {
            return true;
        }
        // 配置的搜索语言列表
        for (String code : plugin.getConfig().getStringList("search.languages")) {
            String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.isEmpty() && !"en_us".equals(normalized)
                    && langContains(normalized, key, q)) {
                return true;
            }
        }
        // 玩家客户端语言（去重后追加）
        if (localeCode != null) {
            String normalized = localeCode.toLowerCase(Locale.ROOT).replace('-', '_');
            boolean already = "en_us".equals(normalized) || normalized.isEmpty();
            if (!already) {
                for (String code : plugin.getConfig().getStringList("search.languages")) {
                    if (normalized.equals(code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace('-', '_'))) {
                        already = true;
                        break;
                    }
                }
            }
            if (!already && langContains(normalized, key, q)) {
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

    // ---------- 排序 ----------

    /** 排序: alphabetical=字母序, 其他(默认 creative)=按原版物品注册顺序(近似创造物品栏) */
    private Comparator<Material> currentComparator() {
        String mode = plugin.getConfig().getString("settings.sort", "creative");
        if ("alphabetical".equalsIgnoreCase(mode) || creativeIndex.isEmpty()) {
            return Comparator.comparing(Enum::name);
        }
        return Comparator.comparing(mat -> creativeIndex.getOrDefault(mat, Integer.MAX_VALUE));
    }
}
