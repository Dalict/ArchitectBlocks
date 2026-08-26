package com.dalict.architectblocks;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 菜单标识。keyword 仅用于 SEARCH / PAGE_SELECT(来源为搜索时)；
 * invOnly 用于 MAIN（true = 只显示背包已有物品）。
 */
public class MenuHolder implements InventoryHolder {

    public enum Type {
        MAIN, SEARCH, PAGE_SELECT, TRASH, ADMIN, ADMIN_LIST
    }

    private final Type type;
    private final int page;
    private final String keyword;
    private final boolean invOnly;
    private Inventory inventory;

    public MenuHolder(Type type, int page, String keyword, boolean invOnly) {
        this.type = type;
        this.page = page;
        this.keyword = keyword;
        this.invOnly = invOnly;
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

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
