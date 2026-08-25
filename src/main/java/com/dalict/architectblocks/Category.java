package com.dalict.architectblocks;

import org.bukkit.Material;

/**
 * 材料分类，对应原版创造物品栏的分类标签。
 * configKey 对应 config.yml 中 categories 段的开关。
 */
public enum Category {

    BUILDING("建筑方块", "building_blocks", true, Material.BRICKS),
    DYE("染色方块", "colored_blocks", true, Material.CYAN_WOOL),
    NATURAL("自然方块", "natural_blocks", true, Material.GRASS_BLOCK),
    FUNCTIONAL("功能方块", "functional_blocks", true, Material.OAK_SIGN),
    REDSTONE("红石方块", "redstone_blocks", true, Material.REDSTONE),
    TOOLS("工具与使用物品", "tools_utilities", false, Material.DIAMOND_PICKAXE),
    COMBAT("战斗用品", "combat", false, Material.NETHERITE_SWORD),
    FOOD("食物与饮品", "food_drinks", false, Material.GOLDEN_APPLE),
    INGREDIENTS("原材料", "ingredients", false, Material.IRON_INGOT),
    SPAWN_EGGS("刷怪蛋", "spawn_eggs", false, Material.CREEPER_SPAWN_EGG);

    private final String displayName;
    private final String configKey;
    private final boolean defaultEnabled;
    private final Material icon;

    Category(String displayName, String configKey, boolean defaultEnabled, Material icon) {
        this.displayName = displayName;
        this.configKey = configKey;
        this.defaultEnabled = defaultEnabled;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getConfigKey() {
        return configKey;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public Material getIcon() {
        return icon;
    }
}
