package com.dalict.architectblocks;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 菜单条目：原版物品（custom=null）或管理员上传的自定义物品（含完整 NBT）。
 */
public class ItemEntry {

    public final Material material;
    public final ItemStack custom;
    public final int customId;
    public final String customName;

    private ItemEntry(Material material, ItemStack custom, int customId, String customName) {
        this.material = material;
        this.custom = custom;
        this.customId = customId;
        this.customName = customName;
    }

    public static ItemEntry vanilla(Material material) {
        return new ItemEntry(material, null, -1, null);
    }

    public static ItemEntry custom(int id, ItemStack item, String customName) {
        return new ItemEntry(item.getType(), item, id, customName);
    }

    public boolean isCustom() {
        return custom != null;
    }
}
