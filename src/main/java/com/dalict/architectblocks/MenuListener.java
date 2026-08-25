package com.dalict.architectblocks;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 处理菜单内的一切点击：取物品、翻页、返回、关闭。
 * 槽位根据菜单尺寸动态计算，与 GUI 配置联动。
 */
public class MenuListener implements Listener {

    private final ArchitectBlocks plugin;

    public MenuListener(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission(ArchitectBlocks.PERM_USE)) {
            player.sendMessage(plugin.getMessage("no-permission"));
            event.getView().close();
            return;
        }
        // 只处理菜单内点击，玩家自身背包的点击仅取消
        if (event.getClickedInventory() != top) {
            return;
        }
        MenuHolder holder = (MenuHolder) top.getHolder();
        int slot = event.getSlot();
        int size = top.getSize();
        int bottomStart = size - 9;
        switch (holder.getType()) {
            case CATEGORIES:
                handleCategoriesClick(player, slot, bottomStart);
                break;
            case ITEMS:
                handleItemsClick(player, holder, slot, size, bottomStart);
                break;
        }
    }

    private void handleCategoriesClick(Player player, int slot, int bottomStart) {
        if (slot == bottomStart + 4) {
            player.closeInventory();
            return;
        }
        Category category = plugin.getCategorySlotMap().get(slot);
        if (category != null) {
            plugin.openItemMenu(player, category, 0);
        }
    }

    private void handleItemsClick(Player player, MenuHolder holder, int slot, int size, int bottomStart) {
        Category category = holder.getCategory();
        int pageSize = size - 9;
        List<Material> items = plugin.getCategoryManager().getItems(category);
        int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int page = holder.getPage();
        if (slot == bottomStart && page > 0) {
            plugin.openItemMenu(player, category, page - 1);
            return;
        }
        if (slot == size - 1 && page + 1 < pageCount) {
            plugin.openItemMenu(player, category, page + 1);
            return;
        }
        if (slot == bottomStart + 4) {
            plugin.openCategoryMenu(player);
            return;
        }
        if (slot >= 0 && slot < pageSize) {
            int index = page * pageSize + slot;
            if (index >= items.size()) {
                return;
            }
            Material material = items.get(index);
            int amount = plugin.getClickAmount();
            if (amount > material.getMaxStackSize()) {
                amount = material.getMaxStackSize();
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        }
    }
}
