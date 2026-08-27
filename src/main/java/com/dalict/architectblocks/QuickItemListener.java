package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置快捷物品：
 * - 配置独立于 gui 段（quick-item.*），含材质/名称/描述/附魔光效开关
 * - 发放条件：数据库授权 或 architectblocks.use（管理员权限不再隐式放行）
 * - 加入时自动补发；背包满则提示；配置指纹变更自动回收旧物品；
 *   失去授权自动移除；禁止放入容器与放置
 */
public class QuickItemListener implements Listener {

    private final ArchitectBlocks plugin;
    private final NamespacedKey marker;
    private final NamespacedKey signatureKey;

    public QuickItemListener(ArchitectBlocks plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "quick_item");
        this.signatureKey = new NamespacedKey(plugin, "quick_signature");
        // 周期检测：回收无权限/过期签名的快捷物品（30秒一次）
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepOnline, 600L, 600L);
    }

    // ==================== 配置读取与签名 ====================

    private boolean enabled() {
        return plugin.getConfig().getBoolean("quick-item.enabled", true);
    }

    /** 配置指纹：材质+名称+描述+光效，任何一项变更都会导致旧物品被回收 */
    public String signature() {
        return plugin.getConfig().getString("quick-item.material", "KNOWLEDGE_BOOK")
                + "|" + plugin.getConfig().getString("quick-item.name", "")
                + "|" + String.join(";", plugin.getConfig().getStringList("quick-item.lore"))
                + "|" + plugin.getConfig().getBoolean("quick-item.enchanted", true);
    }

    /** 生成快捷物品（名称/描述支持 PlaceholderAPI 变量） */
    public ItemStack createItem(Player player) {
        ItemStack item = new ItemStack(plugin.material(
                "quick-item.material", Material.KNOWLEDGE_BOOK));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = plugin.getConfig().getString("quick-item.name",
                    "&8[ &a建筑材料菜单 &8]");
            meta.setDisplayName(plugin.applyPapi(player, ArchitectBlocks.color(name)));
            List<String> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList("quick-item.lore")) {
                lore.add(plugin.applyPapi(player, ArchitectBlocks.color(line)));
            }
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            if (plugin.getConfig().getBoolean("quick-item.enchanted", true)) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(signatureKey,
                    PersistentDataType.STRING, signature());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 判定物品是否为快捷物品（任意版本） */
    public boolean isQuickItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte flag = item.getItemMeta().getPersistentDataContainer()
                .get(marker, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /** 是否为当前配置版本的快捷物品 */
    private boolean isCurrent(ItemStack item) {
        if (!isQuickItem(item)) {
            return false;
        }
        String sig = item.getItemMeta().getPersistentDataContainer()
                .get(signatureKey, PersistentDataType.STRING);
        return signature().equals(sig);
    }

    // ==================== 发放与回收 ====================

    /**
     * 快捷物品发放资格：use 权限 > 全局允许 > 数据库授权。
     * 注意：admin 权限不再隐式放行——管理员也必须在名单或持有 use 权限。
     */
    public boolean canUseQuickItem(Player player) {
        if (!enabled()) {
            return false;
        }
        if (player.hasPermission(ArchitectBlocks.PERM_USE)) {
            return true;
        }
        if (plugin.getConfig().getBoolean("access.allow-everyone", false)) {
            return true;
        }
        return plugin.getConfig().getBoolean("access.use-player-list", true)
                && plugin.getDb().isAccessGranted(player.getName());
    }

    /**
     * 发放快捷物品：
     * - 无资格 / 已持有当前版本 → 不发放（notify 时提示）
     * - 背包满且无当前版本 → 提示无法给予
     * @return 是否实际发放
     */
    public boolean give(Player player, boolean notify) {
        if (!canUseQuickItem(player)) {
            if (notify) {
                player.sendMessage(plugin.getMessage("quick-no-permission"));
            }
            return false;
        }
        if (hasCurrent(player)) {
            if (notify) {
                player.sendMessage(plugin.getMessage("quick-already-owned"));
            }
            return false;
        }
        if (player.getInventory().firstEmpty() == -1) {
            if (notify) {
                player.sendMessage(plugin.getMessage("quick-inventory-full"));
            }
            return false;
        }
        player.getInventory().addItem(createItem(player));
        if (notify) {
            player.sendMessage(plugin.getMessage("quick-given"));
        }
        return true;
    }

    private boolean hasCurrent(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCurrent(item)) {
                return true;
            }
        }
        return false;
    }

    /** 移除玩家背包内所有快捷物品（任意版本） */
    public void removeAll(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isQuickItem(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    /** 回收：无资格移除全部；有资格但存在旧签名物品则移除旧版并补发新版 */
    private void sweep(Player player) {
        if (!player.isOnline()) {
            return;
        }
        if (!canUseQuickItem(player)) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (isQuickItem(item)) {
                    removeAll(player);
                    player.sendMessage(plugin.getMessage("quick-removed-no-permission"));
                    return;
                }
            }
            return;
        }
        boolean hadStale = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isQuickItem(contents[i]) && !isCurrent(contents[i])) {
                player.getInventory().setItem(i, null);
                hadStale = true;
            }
        }
        if (hadStale) {
            player.sendMessage(plugin.getMessage("quick-removed-outdated"));
        }
        if (!hasCurrent(player) && player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(createItem(player));
        }
    }

    private void sweepOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sweep(player);
        }
    }

    // ==================== 事件 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 延迟 1 秒处理，等权限插件就绪
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sweep(player);
            }
        }, 20L);
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

    /** 阻止放入容器（箱子/潜影盒/漏斗等）；玩家自身背包内整理不受影响 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isQuickItem(event.getCurrentItem()) && !isQuickItem(event.getCursor())) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        Inventory bottom = event.getView().getBottomInventory();
        // 点击发生在容器区（非玩家背包格） → 阻止
        if (clicked != bottom) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player) {
                ((Player) event.getWhoClicked())
                        .sendMessage(plugin.getMessage("quick-no-container"));
            }
            return;
        }
        // 玩家背包内 shift/键盘点击快捷物品 → 会尝试移入容器，阻止
        if (event.getClick().isShiftClick() || event.getClick().isKeyboardClick()) {
            if (isQuickItem(event.getCurrentItem())) {
                event.setCancelled(true);
            }
        }
    }

    /** 阻止拖拽放入容器 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isQuickItem(event.getOldCursor())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player) {
                    ((Player) event.getWhoClicked())
                            .sendMessage(plugin.getMessage("quick-no-container"));
                }
                return;
            }
        }
    }
}
