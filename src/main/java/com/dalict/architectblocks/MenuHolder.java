package com.dalict.architectblocks;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 菜单标识，用于在点击事件中区分本插件打开的菜单。
 */
public class MenuHolder implements InventoryHolder {

    public enum Type {
        CATEGORIES, ITEMS
    }

    private final Type type;
    private final Category category;
    private final int page;
    private Inventory inventory;

    public MenuHolder(Type type, Category category, int page) {
        this.type = type;
        this.category = category;
        this.page = page;
    }

    public Type getType() {
        return type;
    }

    public Category getCategory() {
        return category;
    }

    public int getPage() {
        return page;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
