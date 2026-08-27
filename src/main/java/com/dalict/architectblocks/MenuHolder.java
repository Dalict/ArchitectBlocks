package com.dalict.architectblocks;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 菜单标识。
 * listMode 仅用于 ADMIN_LIST：black / white / upload；
 * invFilter 用于 ADMIN_LIST 的"只显示背包已有"过滤。
 */
public class MenuHolder implements InventoryHolder {

    public enum Type {
        MAIN, SEARCH, PAGE_SELECT, TRASH, ADMIN, ADMIN_LIST
    }

    private final Type type;
    private final int page;
    private final String keyword;
    private final boolean invOnly;
    private final String listMode;
    private final boolean invFilter;
    private Inventory inventory;

    public MenuHolder(Type type, int page, String keyword, boolean invOnly) {
        this(type, page, keyword, invOnly, null, false);
    }

    public MenuHolder(Type type, int page, String keyword, boolean invOnly, String listMode, boolean invFilter) {
        this.type = type;
        this.page = page;
        this.keyword = keyword;
        this.invOnly = invOnly;
        this.listMode = listMode;
        this.invFilter = invFilter;
    }

    public Type getType() {
        return type;
    }

    public int getPage() {
        return page;
    }

    public String getKeyword() {
        return keyword;
    }

    public boolean isInvOnly() {
        return invOnly;
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
