package com.dalict.architectblocks;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天栏搜索输入：以 LOWEST 优先级截获聊天并取消广播，
 * 输入内容不会发送给任何玩家。会话 30 秒超时，输 cancel 取消。
 */
public class ChatInputManager implements Listener {

    private final ArchitectBlocks plugin;
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();

    public ChatInputManager(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 开始一次搜索输入会话 */
    public void startSearch(Player player) {
        long timeout = Math.max(5, plugin.getConfig().getInt("settings.chat-timeout-seconds", 30)) * 1000L;
        sessions.put(player.getUniqueId(), System.currentTimeMillis() + timeout);
        player.closeInventory();
        player.sendMessage(plugin.getMessage("search-prompt"));
    }

    public boolean isActive(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Long deadline = sessions.remove(event.getPlayer().getUniqueId());
        if (deadline == null) {
            return;
        }
        event.setCancelled(true); // 不广播给任何玩家
        Player player = event.getPlayer();
        if (System.currentTimeMillis() > deadline) {
            player.sendMessage(plugin.getMessage("search-timeout"));
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.isEmpty()) {
            player.sendMessage(plugin.getMessage("search-empty-input"));
            return;
        }
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("取消")) {
            player.sendMessage(plugin.getMessage("search-cancelled"));
            return;
        }
        String keyword = text.length() > 64 ? text.substring(0, 64) : text;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.openSearchMenu(player, 0, keyword));
    }
}
