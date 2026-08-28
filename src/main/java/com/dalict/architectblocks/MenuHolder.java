package com.dalict.architectblocks;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 菜单标识。
 * fillMode 仅用于 FILL_SELECT（选择填充方块页的来源标记）。
 * replaceMode 用于 FILL_REPLACE（替换模式的替换目标方块选择页）。
 * fillSelectPage 用于 FILL_REPLACE 页码记忆。
 */
public class MenuHolder implements InventoryHolder {

    public enum Type {
        MAIN, SEARCH, PAGE_SELECT, TRASH, ADMIN, ADMIN_LIST, FLIGHT, ACCESS_LIST,
        FILL, FILL_SELECT, FILL_REPLACE
    }

    private final Type type;
    private final int page;
    private final String keyword;
    private final boolean invOnly;
    private final String listMode;
    private final boolean invFilter;
    private final String fillMode;
    private final boolean replaceMode;
    private Inventory inventory;

    public MenuHolder(Type type, int page, String keyword, boolean invOnly) {
        this(type, page, keyword, invOnly, null, false, null, false);
    }

    public MenuHolder(Type type, int page, String keyword, boolean invOnly,
                      String listMode, boolean invFilter) {
        this(type, page, keyword, invOnly, listMode, invFilter, null, false);
    }

    public MenuHolder(Type type, int page, String keyword, boolean invOnly,
                      String listMode, boolean invFilter, String fillMode, boolean replaceMode) {
        this.type = type;
        this.page = page;
        this.keyword = keyword;
        this.invOnly = invOnly;
        this.listMode = listMode;
        this.invFilter = invFilter;
        this.fillMode = fillMode;
        this.replaceMode = replaceMode;
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

    public String getFillMode() {
        return fillMode;
    }

    public boolean isReplaceMode() {
        return replaceMode;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
