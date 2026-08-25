package com.dalict.architectblocks;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 启动时自动检索当前服务器核心注册的全部物品，并按名称规则归类。
 * 分类结果随服务器版本自动更新，无需手动维护物品列表。
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

    private final ArchitectBlocks plugin;
    private final Map<Category, List<Material>> categorized = new EnumMap<>(Category.class);
    private Set<Material> blacklist = new HashSet<>();

    public CategoryManager(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 重新检索并分类全部物品，读取黑名单配置 */
    public void reload() {
        categorized.clear();
        for (Category c : Category.values()) {
            categorized.put(c, new ArrayList<>());
        }
        blacklist = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("blacklist")) {
            try {
                blacklist.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("黑名单中的物品名无效: " + name);
            }
        }
        boolean overlap = plugin.getConfig().getBoolean("settings.overlap", true);
        for (Material mat : Material.values()) {
            if (!mat.isItem() || mat.isAir() || blacklist.contains(mat)) {
                continue;
            }
            Category primary = classify(mat);
            categorized.get(primary).add(mat);
            // 分类重叠：与原版创造栏一致，原木/菌柄同时出现在建筑方块和自然方块
            if (overlap && primary == Category.NATURAL && isWoodLike(mat.name())) {
                categorized.get(Category.BUILDING).add(mat);
            }
        }
        sortAll();
        for (Category c : Category.values()) {
            plugin.getLogger().info(c.getDisplayName() + ": " + categorized.get(c).size() + " 种");
        }
    }

    /** 分类规则，顺序即优先级 */
    private Category classify(Material mat) {
        String n = mat.name();
        if (n.endsWith("_SPAWN_EGG")) {
            return Category.SPAWN_EGGS;
        }
        if (mat.isEdible()) {
            return Category.FOOD;
        }
        if (mat.isBlock()) {
            // 精确匹配的散件
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
        // 非方块物品：粗略分入剩余三类（默认关闭）
        if (containsAny(n, COMBAT_KEYWORDS)) {
            return Category.COMBAT;
        }
        if (mat.getMaxDurability() > 0 || n.endsWith("_BUCKET") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("FISHING_ROD") || n.equals("BRUSH")
                || n.equals("SPYGLASS") || n.equals("COMPASS") || n.equals("CLOCK")
                || n.equals("WRITABLE_BOOK") || n.equals("WRITTEN_BOOK") || n.equals("BOOK")
                ) {
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

    /**
     * 按配置排序：type=按种类（家族聚簇，接近创造栏观感），alphabetical=按字母。
     * 家族取名称最后一个单词，如 OAK_LOG/SPRUCE_LOG 的家族都是 LOG，木板/原木各自聚在一起。
     */
    private void sortAll() {
        String mode = plugin.getConfig().getString("settings.sort", "type");
        Comparator<Material> cmp;
        if ("alphabetical".equalsIgnoreCase(mode)) {
            cmp = Comparator.comparing(Enum::name);
        } else {
            cmp = Comparator.comparing(CategoryManager::family).reversed().thenComparing(Enum::name);
        }
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

    public List<Material> getItems(Category category) {
        return categorized.getOrDefault(category, new ArrayList<>());
    }

    public boolean isEnabled(Category category) {
        return plugin.getConfig().getBoolean("categories." + category.getConfigKey(),
                category.isDefaultEnabled());
    }
}
