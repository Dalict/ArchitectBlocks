package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * 内置快捷物品（与 ItemJoin 的 architectblocks-open-item 逻辑一致）：
 * 授权玩家加入服务器时自动获得；左右键点击打开物品菜单；不可放置；
 * 已持有则不重复发放。通过 PersistentDataContainer 标记识别，防伪造。
 */
public class QuickItemListener implements Listener {

    private final ArchitectBlocks plugin;
    private final NamespacedKey marker;

    public QuickItemListener(ArchitectBlocks plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "quick_item");
    }

    /** 生成快捷物品 */
    public ItemStack createItem() {
        ItemStack item = new ItemStack(plugin.material("quick-item.material", Material.GRASS_BLOCK));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ArchitectBlocks.color(
                    plugin.getGuiConfigString("names.quick-item", "&8[ &a建筑材料菜单 &8]")));
            List<String> lore = plugin.getConfig().getStringList("gui.quick-item-lore");
            if (!lore.isEmpty()) {
                java.util.List<String> colored = new java.util.ArrayList<>();
                for (String line : lore) {
                    colored.add(ArchitectBlocks.color(line));
                }
                meta.setLore(colored);
            }
            meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 判定物品是否为快捷物品 */
    public boolean isQuickItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte flag = item.getItemMeta().getPersistentDataContainer()
                .get(marker, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /** 授权玩家加入时补发（背包已有快捷物品则跳过） */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.canUse(player)) {
            return;
        }
        boolean has = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isQuickItem(item)) {
                has = true;
                break;
            }
        }
        if (!has) {
            give(player);
        }
    }

    private void give(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                Map<Integer, ItemStack> leftover =
                        player.getInventory().addItem(createItem());
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        });
    }

    /** 左/右键手持快捷物品打开菜单 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!isQuickItem(item)) {
            return;
        }
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK:
            case LEFT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
            case RIGHT_CLICK_AIR:
                break;
            default:
                return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.canUse(player)) {
            player.sendMessage(plugin.getMessage("no-permission"));
            return;
        }
        plugin.openMenu(player);
    }

    /** 阻止放置 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isQuickItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }
}
