package com.dalict.architectblocks;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 菜单标识，用于在点击事件中区分本插件打开的菜单。
 * listMode 仅用于 ADMIN_LIST：black = 黑名单管理视图，white = 白名单管理视图。
 */
public class MenuHolder implements InventoryHolder {

    public enum Type {
        CATEGORIES, ITEMS, INV, ADMIN_MAIN, ADMIN_CATS, ADMIN_LIST
    }

    private final Type type;
    private final Category category;
    private final int page;
    private final String listMode;
    private final boolean invFilter;
    private Inventory inventory;

    public MenuHolder(Type type, Category category, int page) {
        this(type, category, page, null, false);
    }

    public MenuHolder(Type type, Category category, int page, String listMode, boolean invFilter) {
        this.type = type;
        this.category = category;
        this.page = page;
        this.listMode = listMode;
        this.invFilter = invFilter;
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

    public String getListMode() {
        return listMode;
    }

    public boolean isInvFilter() {
        return invFilter;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
