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
 * 处理菜单内的一切点击：取物品(带冷却)、循环翻页、页码跳转、搜索入口、垃圾桶放行、管理员操作。
 */
public class MenuListener implements Listener {

    private final ArchitectBlocks plugin;

    public MenuListener(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        MenuHolder holder = (MenuHolder) top.getHolder();
        if (holder.getType() == MenuHolder.Type.TRASH) {
            // 垃圾桶：拖拽不触及 0/8 号按钮则放行
            for (int raw : event.getRawSlots()) {
                if (raw == 0 || raw == 8) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        MenuHolder holder = (MenuHolder) top.getHolder();

        // 垃圾桶：只保护关闭/返回两个按钮，其余点击全部放行（物品可自由放入，退出即销毁）
        if (holder.getType() == MenuHolder.Type.TRASH) {
            if (event.getClickedInventory() == top && (event.getSlot() == 0 || event.getSlot() == 8)) {
                event.setCancelled(true);
                if (!(event.getWhoClicked() instanceof Player)) return;
                Player trashPlayer = (Player) event.getWhoClicked();
                if (event.getSlot() == 0) {
                    trashPlayer.closeInventory();
                } else {
                    plugin.openMenu(trashPlayer);
                }
            }
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

        // 名单/上传管理页：点击自己背包中的物品 = 加入名单 / 上传
        if (holder.getType() == MenuHolder.Type.ADMIN_LIST
                && event.getClickedInventory() != null
                && event.getClickedInventory() != top
                && event.getCurrentItem() != null
                && !event.getCurrentItem().getType().isAir()) {
            ItemStack clicked = event.getCurrentItem().clone();
            String mode = holder.getListMode();
            if ("upload".equals(mode)) {
                boolean added = plugin.getItemRegistry().addCustom(clicked);
                if (added) {
                    player.sendMessage(plugin.getMessage("uploaded")
                            .replace("%item%", clicked.getType().name()));
                } else {
                    player.sendMessage(plugin.getMessage("upload-duplicate"));
                }
            } else {
                // 名单页跟随当前模式：存入ID到对应名单库
                boolean white = plugin.getItemRegistry().isWhiteMode();
                plugin.getDb().addToList(clicked.getType(), white);
                player.sendMessage(plugin.getMessage("list-added")
                        .replace("%item%", clicked.getType().name())
                        .replace("%mode%", plugin.modeName(white)));
            }
            plugin.openAdminList(player, holder.getPage(), mode, holder.isInvFilter());
            return;
        }
        // 其他菜单：玩家自身背包的点击仅取消
        if (event.getClickedInventory() != top) {
            return;
        }
        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        switch (holder.getType()) {
            case MAIN:
                handleMain(player, holder, slot);
                break;
            case SEARCH:
                handleSearch(player, holder, slot);
                break;
            case PAGE_SELECT:
                handlePageSelect(player, holder, slot);
                break;
            case ADMIN:
                handleAdmin(player, slot);
                break;
            case ADMIN_LIST:
                handleAdminList(player, holder, slot);
                break;
        }
    }

    // ---------- 主菜单 / 背包视图 ----------

    private void handleMain(Player player, MenuHolder holder, int slot) {
        List<ItemEntry> items = holder.isInvOnly()
                ? plugin.getItemRegistry().getInventoryVisible(player)
                : plugin.getItemRegistry().getVisible();
        int pageCount = Math.max(1, (items.size() + ArchitectBlocks.PAGE_SIZE - 1) / ArchitectBlocks.PAGE_SIZE);
        int page = holder.getPage();
        if (slot == 0) {
            player.closeInventory();
            return;
        }
        if (slot == 1 && player.hasPermission(ArchitectBlocks.PERM_ADMIN)) {
            plugin.openAdmin(player);
            return;
        }
        if (slot == 45) {
            if (holder.isInvOnly()) {
                // 返回主页：恢复主页自己的记忆页码
                plugin.openMainMenu(player, plugin.getDb().getPage(player.getUniqueId(), "main"), false);
            } else {
                // 进入背包视图：恢复背包视图自己的记忆页码
                plugin.openMainMenu(player, plugin.getDb().getPage(player.getUniqueId(), "inv"), true);
            }
            return;
        }
        if (slot == 8) {
            plugin.getChatInput().startSearch(player);
            return;
        }
        if (slot == 49) {
            plugin.openPageSelect(player, 0, holder.isInvOnly() ? "inv" : "main", null);
            return;
        }
        if (slot == 48 && pageCount > 1) {
            plugin.openMainMenu(player, (page - 1 + pageCount) % pageCount, holder.isInvOnly());
            return;
        }
        if (slot == 50 && pageCount > 1) {
            plugin.openMainMenu(player, (page + 1) % pageCount, holder.isInvOnly());
            return;
        }
        if (slot == 53) {
            plugin.openTrash(player);
            return;
        }
        giveIfItem(player, items, page, slot);
    }

    // ---------- 搜索结果 ----------

    private void handleSearch(Player player, MenuHolder holder, int slot) {
        String keyword = holder.getKeyword();
        List<ItemEntry> items = plugin.getItemRegistry().search(keyword);
        int pageCount = Math.max(1, (items.size() + ArchitectBlocks.PAGE_SIZE - 1) / ArchitectBlocks.PAGE_SIZE);
        int page = holder.getPage();
        if (slot == 0) {
            player.closeInventory();
            return;
        }
        if (slot == 1 && player.hasPermission(ArchitectBlocks.PERM_ADMIN)) {
            plugin.openAdmin(player);
            return;
        }
        if (slot == 8) {
            // 返回主界面：恢复主页自己的记忆页码
            plugin.openMainMenu(player, plugin.getDb().getPage(player.getUniqueId(), "main"), false);
            return;
        }
        if (slot == 49) {
            plugin.openPageSelect(player, 0, "search", keyword);
            return;
        }
        if (slot == 48 && pageCount > 1) {
            plugin.openSearchMenu(player, (page - 1 + pageCount) % pageCount, keyword);
            return;
        }
        if (slot == 50 && pageCount > 1) {
            plugin.openSearchMenu(player, (page + 1) % pageCount, keyword);
            return;
        }
        if (slot == 53) {
            plugin.openTrash(player);
            return;
        }
        giveIfItem(player, items, page, slot);
    }

    // ---------- 页码跳转 ----------

    private void handlePageSelect(Player player, MenuHolder holder, int slot) {
        if (slot == 0) {
            player.closeInventory();
            return;
        }
        if (slot == 1 && player.hasPermission(ArchitectBlocks.PERM_ADMIN)) {
            plugin.openAdmin(player);
            return;
        }
        if (slot == 8) {
            plugin.openMenu(player);
            return;
        }
        String sourceView = holder.getKeyword() != null ? "search" : (holder.isInvOnly() ? "inv" : "main");
        int total = totalOf(player, sourceView, holder.getKeyword());
        int selectPages = Math.max(1, (total + ArchitectBlocks.PAGE_SIZE - 1) / ArchitectBlocks.PAGE_SIZE);
        if (slot == 48 && selectPages > 1) {
            plugin.openPageSelect(player, (holder.getPage() - 1 + selectPages) % selectPages, sourceView, holder.getKeyword());
            return;
        }
        if (slot == 50 && selectPages > 1) {
            plugin.openPageSelect(player, (holder.getPage() + 1) % selectPages, sourceView, holder.getKeyword());
            return;
        }
        if (slot == 53) {
            plugin.openTrash(player);
            return;
        }
        // 点击纸张：跳转到目标页
        if (slot >= ArchitectBlocks.ITEM_SLOT_START && slot < ArchitectBlocks.ITEM_SLOT_START + ArchitectBlocks.PAGE_SIZE) {
            int targetPage = holder.getPage() * ArchitectBlocks.PAGE_SIZE + (slot - ArchitectBlocks.ITEM_SLOT_START);
            if (targetPage >= total) {
                return;
            }
            if ("search".equals(sourceView)) {
                plugin.openSearchMenu(player, targetPage, holder.getKeyword());
            } else if ("inv".equals(sourceView)) {
                plugin.openMainMenu(player, targetPage, true);
            } else {
                plugin.openMainMenu(player, targetPage, false);
            }
        }
    }

    private int totalOf(Player player, String view, String keyword) {
        List<ItemEntry> items;
        if ("inv".equals(view)) {
            items = plugin.getItemRegistry().getInventoryVisible(player);
        } else if ("search".equals(view)) {
            items = plugin.getItemRegistry().search(keyword == null ? "" : keyword);
        } else {
            items = plugin.getItemRegistry().getVisible();
        }
        return Math.max(1, (items.size() + ArchitectBlocks.PAGE_SIZE - 1) / ArchitectBlocks.PAGE_SIZE);
    }

    // ---------- 取物 ----------

    private void giveIfItem(Player player, List<ItemEntry> items, int page, int slot) {
        int offset = slot - ArchitectBlocks.ITEM_SLOT_START;
        if (offset < 0 || offset >= ArchitectBlocks.PAGE_SIZE) {
            return;
        }
        int index = page * ArchitectBlocks.PAGE_SIZE + offset;
        if (index >= items.size()) {
            return;
        }
        if (!plugin.checkGiveCooldown(player)) {
            player.sendMessage(plugin.getMessage("give-cooldown"));
            return;
        }
        ItemEntry entry = items.get(index);
        ItemStack give;
        if (entry.isCustom()) {
            give = entry.custom.clone(); // 原汁原味（含 NBT 与数量）
        } else {
            int amount = plugin.getClickAmount();
            if (amount > entry.material.getMaxStackSize()) {
                amount = entry.material.getMaxStackSize();
            }
            give = new ItemStack(entry.material, amount);
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
    }

    // ---------- 管理员 ----------

    private void handleAdmin(Player player, int slot) {
        if (slot == 22) {
            plugin.openMenu(player);
            return;
        }
        if (slot == 10) {
            boolean now = !plugin.getItemRegistry().isAllowSpawnEggs();
            plugin.getItemRegistry().setAllowSpawnEggs(now);
            player.sendMessage(plugin.getMessage("eggs-toggled")
                    .replace("%state%", now ? plugin.getMessage("state-enabled") : plugin.getMessage("state-disabled")));
            plugin.openAdmin(player);
            return;
        }
        if (slot == 11) {
            boolean now = !plugin.getItemRegistry().isAllowAdminItems();
            plugin.getItemRegistry().setAllowAdminItems(now);
            player.sendMessage(plugin.getMessage("admin-items-toggled")
                    .replace("%state%", now ? plugin.getMessage("state-enabled") : plugin.getMessage("state-disabled")));
            plugin.openAdmin(player);
            return;
        }
        if (slot == 12) {
            // 名单管理：打开当前名单模式的名单库
            plugin.openAdminList(player, 0, "list", false);
            return;
        }
        if (slot == 14) {
            plugin.openAdminList(player, 0, "upload", false);
            return;
        }
        if (slot == 15) {
            String next;
            String current = plugin.getItemRegistry().getItemSource();
            switch (current) {
                case "both": next = "vanilla"; break;
                case "vanilla": next = "custom"; break;
                default: next = "both"; break;
            }
            plugin.getItemRegistry().setItemSource(next);
            player.sendMessage(plugin.getMessage("source-toggled") + plugin.sourceName(next));
            plugin.openAdmin(player);
            return;
        }
        if (slot == 16) {
            boolean white = !plugin.getItemRegistry().isWhiteMode();
            plugin.getItemRegistry().setWhiteMode(white);
            player.sendMessage(plugin.getMessage("mode-toggled") + plugin.modeName(white));
            plugin.openAdmin(player);
            return;
        }
    }

    private void handleAdminList(Player player, MenuHolder holder, int slot) {
        String mode = holder.getListMode();
        if (slot == 0) {
            player.closeInventory();
            return;
        }
        if (slot == 8) {
            plugin.openAdmin(player);
            return;
        }
        if (slot == 45) {
            plugin.openAdminList(player, 0, mode, !holder.isInvFilter());
            return;
        }
        List<ItemEntry> items = plugin.buildAdminListEntries(player, mode, holder.isInvFilter());
        int pageCount = Math.max(1, (items.size() + ArchitectBlocks.PAGE_SIZE - 1) / ArchitectBlocks.PAGE_SIZE);
        int page = holder.getPage();
        if (slot == 48 && pageCount > 1) {
            plugin.openAdminList(player, (page - 1 + pageCount) % pageCount, mode, holder.isInvFilter());
            return;
        }
        if (slot == 50 && pageCount > 1) {
            plugin.openAdminList(player, (page + 1) % pageCount, mode, holder.isInvFilter());
            return;
        }
        // 点击列表中的物品 = 移出名单 / 删除上传
        int offset = slot - ArchitectBlocks.ITEM_SLOT_START;
        if (offset < 0 || offset >= ArchitectBlocks.PAGE_SIZE) {
            return;
        }
        int index = page * ArchitectBlocks.PAGE_SIZE + offset;
        if (index >= items.size()) {
            return;
        }
        ItemEntry entry = items.get(index);
        if (entry.isCustom()) {
            plugin.getItemRegistry().removeCustom(entry.customId);
            player.sendMessage(plugin.getMessage("upload-removed")
                    .replace("%item%", entry.customName != null ? entry.customName : entry.material.name()));
        } else {
            // 删除ID即移出当前名单库
            boolean white = plugin.getItemRegistry().isWhiteMode();
            plugin.getDb().removeFromList(entry.material, white);
            player.sendMessage(plugin.getMessage("list-removed")
                    .replace("%item%", entry.material.name())
                    .replace("%mode%", plugin.modeName(white)));
        }
        plugin.openAdminList(player, page, mode, holder.isInvFilter());
    }
}
