package com.dalict.architectblocks;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时自动检索当前服务器核心注册的全部物品，并按名称规则归类。
 * 显示与否由 数据库设置(分类开关/管理员物品) + 物品标记(黑/白名单) 共同决定。
 */
public class CategoryManager {

    private static final List<String> COLORS = Arrays.asList(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK",
            "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK");

    private static final List<String> DYE_SUFFIXES = Arrays.asList(
            "_WOOL", "_CONCRETE", "_CONCRETE_POWDER", "_TERRACOTTA", "_GLAZED_TERRACOTTA",
            "_STAINED_GLASS", "_STAINED_GLASS_PANE", "_CARPET", "_SHULKER_BOX", "_BED",
            "_CANDLE", "_BUNDLED_CANDLE", "_BANNER");

    private static final List<String> REDSTONE_KEYWORDS = Arrays.asList(
            "REDSTONE", "REPEATER", "COMPARATOR", "TARGET", "OBSERVER", "PISTON", "DISPENSER",
            "DROPPER", "HOPPER", "LEVER", "TRIPWIRE_HOOK", "DAYLIGHT_DETECTOR", "NOTE_BLOCK",
            "JUKEBOX", "RAIL", "TNT", "SCULK_SENSOR", "SCULK_SHRIEKER", "CRAFTER",
            "LIGHTNING_ROD", "COPPER_BULB", "BIG_DRIPLEAF", "SLIME_BLOCK", "PRESSURE_PLATE", "BUTTON");

    private static final List<String> FUNCTIONAL_KEYWORDS = Arrays.asList(
            "CRAFTING_TABLE", "FURNACE", "SMOKER", "CAMPFIRE", "ANVIL", "ENCHANTING_TABLE",
            "BREWING_STAND", "BEACON", "CHEST", "BARREL", "LECTERN", "BELL", "BOOKSHELF",
            "LOOM", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "SMITHING_TABLE", "GRINDSTONE",
            "STONECUTTER", "COMPOSTER", "CAULDRON", "RESPAWN_ANCHOR", "BEEHIVE", "BEE_NEST",
            "SHULKER_BOX", "CAKE", "FLOWER_POT", "LADDER", "SCAFFOLDING", "SHELF", "CONDUIT",
            "LODESTONE", "DECORATED_POT", "COPPER_CHEST", "COPPER_GOLEM_STATUE", "HONEY_BLOCK");

    private static final List<String> FUNCTIONAL_SUFFIXES = Arrays.asList(
            "_DOOR", "_TRAPDOOR", "_SHELF");

    private static final List<String> NATURAL_KEYWORDS = Arrays.asList(
            "DIRT", "GRASS_BLOCK", "GRAVEL", "CLAY", "SNOW", "ICE", "MYCELIUM", "PODZOL",
            "_LOG", "_LEAVES", "MUSHROOM", "CORAL", "PUMPKIN", "MELON", "CACTUS", "VINE",
            "WART_BLOCK", "NYLIUM", "SCULK", "MOSS", "AZALEA", "AMETHYST_CLUSTER", "AMETHYST_BUD",
            "DRIPSTONE", "CHORUS", "SPONGE", "SEA_PICKLE", "TURTLE_EGG", "SNIFFER_EGG",
            "RESIN_CLUMP", "FROGSPAWN", "HANGING_ROOTS", "DRIPLEAF", "SPORE_BLOSSOM", "SUGAR_CANE",
            "BAMBOO_SAPLING", "EYEBLOSSOM", "DRY_GRASS", "BUSH", "FIREFLY_BUSH", "CACTUS_FLOWER",
            "LEAF_LITTER", "WILDFLOWERS", "PINK_PETALS", "DEAD_BUSH", "FERN", "GRASS", "FLOWER",
            "TULIP", "ORCHID", "DAISY", "ALLIUM", "POPPY", "DANDELION", "LILY", "PEONY",
            "LILAC", "SUNFLOWER", "ROSE_BUSH", "WITHER_ROSE", "TORCHFLOWER", "BONE_BLOCK",
            "HAY_BLOCK", "ROOTED_DIRT", "COARSE_DIRT", "FROSTED_ICE",
            "POINTED_DRIPSTONE", "CRIMSON_ROOTS", "WARPED_ROOTS", "NETHER_SPROUTS", "FUNGUS",
            "WEEPING_VINES", "TWISTING_VINES", "CAVE_VINES", "GLOW_LICHEN", "LILY_PAD",
            "MANGROVE_ROOTS", "PALE_MOSS", "PALE_HANGING_MOSS", "SUSPICIOUS");

    private static final List<String> NATURAL_PATTERNS_END = Arrays.asList(
            "_SAPLING", "_PROPAGULE", "_PLANT");

    // 注意: 镐/斧/铲/锄属于工具类(有耐久条)，不在此列
    private static final List<String> COMBAT_KEYWORDS = Arrays.asList(
            "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS", "_SWORD", "SHIELD", "BOW",
            "CROSSBOW", "ARROW", "TRIDENT", "TOTEM_OF_UNDYING", "HORSE_ARMOR", "WOLF_ARMOR", "SPEAR");

    /** 管理员物品：生存无法获取/特殊物品，默认不允许显示，可在管理界面开启 */
    private static final Set<String> ADMIN_ITEM_NAMES = new HashSet<>(Arrays.asList(
            "BEDROCK", "BARRIER", "JIGSAW", "STRUCTURE_BLOCK", "STRUCTURE_VOID",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "LIGHT", "PISTON_HEAD", "MOVING_PISTON", "REINFORCED_DEEPSLATE", "DRAGON_EGG",
            "SPAWNER", "TRIAL_SPAWNER", "VAULT", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL",
            "PETRIFIED_OAK_SLAB", "DEBUG_STICK", "KNOWLEDGE_BOOK", "FARMLAND", "DIRT_PATH",
            "FROSTED_ICE", "BUDDING_AMETHYST", "INFESTED_STONE", "INFESTED_COBBLESTONE",
            "INFESTED_STONE_BRICKS", "INFESTED_MOSSY_STONE_BRICKS", "INFESTED_CRACKED_STONE_BRICKS",
            "INFESTED_CHISELED_STONE_BRICKS", "INFESTED_DEEPSLATE", "END_PORTAL_FRAME",
            "TEST_BLOCK", "TEST_INSTANCE_BLOCK", "SCULK_SHRIEKER", "FROGSPAWN"));

    private final ArchitectBlocks plugin;
    private final Map<Category, List<Material>> categorized = new EnumMap<>(Category.class);

    public CategoryManager(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 重新检索并分类全部物品 */
    public void reload() {
        categorized.clear();
        for (Category c : Category.values()) {
            categorized.put(c, new ArrayList<>());
        }
        boolean overlap = plugin.getConfig().getBoolean("settings.overlap", true);
        for (Material mat : Material.values()) {
            if (!mat.isItem() || mat.isAir()) {
                continue;
            }
            Category primary = classify(mat);
            categorized.get(primary).add(mat);
            if (overlap && primary == Category.NATURAL && isWoodLike(mat.name())) {
                categorized.get(Category.BUILDING).add(mat);
            }
        }
        sortAll();
        for (Category c : Category.values()) {
            plugin.getLogger().info(c.getDisplayName() + ": " + categorized.get(c).size() + " 种");
        }
    }

    // ---------- 分类设置（数据库） ----------

    public boolean isEnabled(Category category) {
        return "true".equalsIgnoreCase(plugin.getDb().getSetting("cat." + category.getConfigKey(), "true"));
    }

    public void setEnabled(Category category, boolean enabled) {
        plugin.getDb().setSetting("cat." + category.getConfigKey(), String.valueOf(enabled));
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

    /** 单个物品是否可见：黑名单永远隐藏 > 白名单永远显示 > 分类开关 > 管理员物品开关 */
    public boolean isVisible(Material mat, Category category) {
        MaterialFlag flag = plugin.getDb().getFlag(mat);
        if (flag == MaterialFlag.BLACK) {
            return false;
        }
        if (flag == MaterialFlag.WHITE) {
            return true;
        }
        if (!isEnabled(category)) {
            return false;
        }
        return !isAdminItem(mat) || isAllowAdminItems();
    }

    /** 分类菜单实际显示的物品列表 */
    public List<Material> getDisplayItems(Category category) {
        List<Material> out = new ArrayList<>();
        for (Material mat : categorized.getOrDefault(category, new ArrayList<>())) {
            if (isVisible(mat, category)) {
                out.add(mat);
            }
        }
        return out;
    }

    /** 背包已有物品视图：列出玩家背包中可显示的物品种类 */
    public List<Material> getInventoryItems(Player player) {
        Set<Material> owned = new LinkedHashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                owned.add(item.getType());
            }
        }
        List<Material> out = new ArrayList<>();
        for (Material mat : owned) {
            Category cat = classify(mat);
            if (isVisible(mat, cat)) {
                out.add(mat);
            }
        }
        out.sort(currentComparator());
        return out;
    }

    /** 管理用：全部物品（不分类、不过滤），按字母排序 */
    public List<Material> getAllItems() {
        List<Material> out = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (mat.isItem() && !mat.isAir()) {
                out.add(mat);
            }
        }
        out.sort(Comparator.comparing(Enum::name));
        return out;
    }

    // ---------- 分类规则 ----------

    private Category classify(Material mat) {
        String n = mat.name();
        if (n.endsWith("_SPAWN_EGG")) {
            return Category.SPAWN_EGGS;
        }
        if (mat.isEdible()) {
            return Category.FOOD;
        }
        if (mat.isBlock()) {
            if (n.equals("SAND") || n.equals("RED_SAND") || n.equals("MUD")
                    || n.equals("SNOW") || n.equals("POWDER_SNOW") || n.equals("SNOW_BLOCK")) {
                return Category.NATURAL;
            }
            for (String color : COLORS) {
                if (n.startsWith(color + "_")) {
                    for (String suffix : DYE_SUFFIXES) {
                        if (n.endsWith(suffix)) {
                            return Category.DYE;
                        }
                    }
                    break;
                }
            }
            if (containsAny(n, REDSTONE_KEYWORDS)) {
                return Category.REDSTONE;
            }
            if (containsAny(n, FUNCTIONAL_KEYWORDS)) {
                return Category.FUNCTIONAL;
            }
            for (String suffix : FUNCTIONAL_SUFFIXES) {
                if (n.endsWith(suffix)) {
                    return Category.FUNCTIONAL;
                }
            }
            if (containsAny(n, NATURAL_KEYWORDS) || endsWithAny(n, NATURAL_PATTERNS_END)) {
                return Category.NATURAL;
            }
            return Category.BUILDING;
        }
        if (containsAny(n, COMBAT_KEYWORDS)) {
            return Category.COMBAT;
        }
        if (mat.getMaxDurability() > 0 || n.endsWith("_BUCKET") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("FISHING_ROD") || n.equals("BRUSH")
                || n.equals("SPYGLASS") || n.equals("COMPASS") || n.equals("CLOCK")
                || n.equals("WRITABLE_BOOK") || n.equals("WRITTEN_BOOK") || n.equals("BOOK")) {
            return Category.TOOLS;
        }
        return Category.INGREDIENTS;
    }

    private boolean containsAny(String name, List<String> keywords) {
        for (String k : keywords) {
            if (name.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithAny(String name, List<String> suffixes) {
        for (String s : suffixes) {
            if (name.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 排序 ----------

    private Comparator<Material> currentComparator() {
        String mode = plugin.getConfig().getString("settings.sort", "type");
        if ("alphabetical".equalsIgnoreCase(mode)) {
            return Comparator.comparing(Enum::name);
        }
        return Comparator.comparing(CategoryManager::family).reversed().thenComparing(Enum::name);
    }

    private void sortAll() {
        Comparator<Material> cmp = currentComparator();
        for (List<Material> list : categorized.values()) {
            list.sort(cmp);
        }
    }

    private static boolean isWoodLike(String name) {
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE") || name.equals("BAMBOO_BLOCK") || name.equals("STRIPPED_BAMBOO_BLOCK");
    }

    private static String family(Material mat) {
        String n = mat.name();
        int idx = n.lastIndexOf('_');
        return idx < 0 ? n : n.substring(idx + 1);
    }
}
