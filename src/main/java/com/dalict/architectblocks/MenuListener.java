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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 处理菜单内的一切点击：取物品(带冷却)、翻页(循环)、返回、管理员操作。
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
        if (!plugin.canUse(player)) {
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
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        int size = top.getSize();
        int bottomStart = size - 9;
        switch (holder.getType()) {
            case CATEGORIES:
                handleCategories(player, slot, bottomStart);
                break;
            case ITEMS:
                handleItems(player, holder, slot, size, bottomStart);
                break;
            case INV:
                handleInv(player, holder, slot, size, bottomStart);
                break;
            case ADMIN_MAIN:
                handleAdminMain(player, slot, bottomStart);
                break;
            case ADMIN_CATS:
                handleAdminCats(player, slot, bottomStart);
                break;
            case ADMIN_LIST:
                handleAdminList(player, holder, slot, size, bottomStart, event.isShiftClick());
                break;
        }
    }

    // ---------- 分类菜单 ----------

    private void handleCategories(Player player, int slot, int bottomStart) {
        if (slot == bottomStart + 4) {
            player.closeInventory();
            return;
        }
        if (slot == bottomStart + 2) {
            plugin.openInventoryMenu(player, 0);
            return;
        }
        if (slot == bottomStart && player.hasPermission(ArchitectBlocks.PERM_ADMIN)) {
            plugin.openAdminMain(player);
            return;
        }
        Category category = plugin.getCategorySlotMap().get(slot);
        if (category != null) {
            // 恢复该玩家在此分类的记忆页码
            int remembered = plugin.getDb().getPage(player.getUniqueId(), category.getConfigKey());
            plugin.openItemMenu(player, category, remembered);
        }
    }

    // ---------- 物品页 ----------

    private void handleItems(Player player, MenuHolder holder, int slot, int size, int bottomStart) {
        Category category = holder.getCategory();
        int pageSize = size - 9;
        List<Material> items = plugin.getCategoryManager().getDisplayItems(category);
        int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int page = holder.getPage();
        if (slot == bottomStart && pageCount > 1) {
            plugin.openItemMenu(player, category, (page - 1 + pageCount) % pageCount);
            return;
        }
        if (slot == size - 1 && pageCount > 1) {
            plugin.openItemMenu(player, category, (page + 1) % pageCount);
            return;
        }
        if (slot == bottomStart + 4) {
            plugin.openCategoryMenu(player);
            return;
        }
        giveIfItem(player, items, page, pageSize, slot);
    }

    // ---------- 背包已有物品页 ----------

    private void handleInv(Player player, MenuHolder holder, int slot, int size, int bottomStart) {
        int pageSize = size - 9;
        List<Material> items = plugin.getCategoryManager().getInventoryItems(player);
        int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int page = holder.getPage();
        if (slot == bottomStart && pageCount > 1) {
            plugin.openInventoryMenu(player, (page - 1 + pageCount) % pageCount);
            return;
        }
        if (slot == size - 1 && pageCount > 1) {
            plugin.openInventoryMenu(player, (page + 1) % pageCount);
            return;
        }
        if (slot == bottomStart + 4) {
            plugin.openCategoryMenu(player);
            return;
        }
        giveIfItem(player, items, page, pageSize, slot);
    }

    private void giveIfItem(Player player, List<Material> items, int page, int pageSize, int slot) {
        if (slot < 0 || slot >= pageSize) {
            return;
        }
        int index = page * pageSize + slot;
        if (index >= items.size()) {
            return;
        }
        if (!plugin.checkGiveCooldown(player)) {
            player.sendMessage(plugin.getMessage("give-cooldown"));
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

    // ---------- 管理员菜单 ----------

    private void handleAdminMain(Player player, int slot, int bottomStart) {
        if (slot == bottomStart + 4) {
            player.closeInventory();
            return;
        }
        if (slot == 11) {
            plugin.openAdminList(player, "black", 0, false);
        } else if (slot == 13) {
            plugin.openAdminList(player, "white", 0, false);
        } else if (slot == 15) {
            plugin.openAdminCats(player);
        }
    }

    private void handleAdminCats(Player player, int slot, int bottomStart) {
        if (slot == bottomStart + 4) {
            plugin.openAdminMain(player);
            return;
        }
        if (slot == 28) {
            boolean now = !plugin.getCategoryManager().isAllowAdminItems();
            plugin.getCategoryManager().setAllowAdminItems(now);
            player.sendMessage(plugin.getMessage("admin-items-toggled")
                    .replace("%state%", now ? plugin.getMessage("state-enabled") : plugin.getMessage("state-disabled")));
            plugin.openAdminCats(player);
            return;
        }
        // 分类开关：槽位与 openAdminCats 的布局算法一致
        int rowCap = 7;
        int total = Category.values().length;
        for (Category c : Category.values()) {
            int idx = c.ordinal();
            int row = idx / rowCap;
            int inRow = Math.min(total - row * rowCap, rowCap);
            int expected = 10 + row * 9 + (rowCap - inRow) / 2 + (idx % rowCap);
            if (expected == slot) {
                boolean now = !plugin.getCategoryManager().isEnabled(c);
                plugin.getCategoryManager().setEnabled(c, now);
                player.sendMessage(plugin.getMessage("category-toggled")
                        .replace("%category%", c.getDisplayName())
                        .replace("%state%", now ? plugin.getMessage("state-enabled") : plugin.getMessage("state-disabled")));
                plugin.openAdminCats(player);
                return;
            }
        }
    }

    private void handleAdminList(Player player, MenuHolder holder, int slot, int size, int bottomStart,
                                 boolean shiftClick) {
        String mode = holder.getListMode();
        int pageSize = size - 9;
        if (slot == bottomStart + 4) {
            plugin.openAdminMain(player);
            return;
        }
        if (slot == bottomStart + 2) {
            plugin.openAdminList(player, mode, 0, !holder.isInvFilter());
            return;
        }
        List<Material> items = plugin.getCategoryManager().getAllItems();
        if (holder.isInvFilter()) {
            items = new ArrayList<>(items);
            items.retainAll(plugin.getCategoryManager().getInventoryItems(player));
        }
        int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int page = holder.getPage();
        if (slot == bottomStart && pageCount > 1) {
            plugin.openAdminList(player, mode, (page - 1 + pageCount) % pageCount, holder.isInvFilter());
            return;
        }
        if (slot == size - 1 && pageCount > 1) {
            plugin.openAdminList(player, mode, (page + 1) % pageCount, holder.isInvFilter());
            return;
        }
        if (slot < 0 || slot >= pageSize) {
            return;
        }
        int index = page * pageSize + slot;
        if (index >= items.size()) {
            return;
        }
        Material mat = items.get(index);
        if (shiftClick) {
            plugin.getDb().setFlag(mat, null);
            player.sendMessage(plugin.getMessage("flag-cleared").replace("%item%", mat.name()));
        } else if ("black".equals(mode)) {
            plugin.getDb().setFlag(mat, MaterialFlag.WHITE);
            player.sendMessage(plugin.getMessage("flag-set-white").replace("%item%", mat.name()));
        } else {
            plugin.getDb().setFlag(mat, MaterialFlag.BLACK);
            player.sendMessage(plugin.getMessage("flag-set-black").replace("%item%", mat.name()));
        }
        plugin.openAdminList(player, mode, page, holder.isInvFilter());
    }
}
