package com.dalict.architectblocks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI 变量：
 * %architectblocks_authorized% - 是否已授权 (true/false)
 * %architectblocks_expires%    - 授权剩余文本（永久 / N天 / 未授权）
 */
public class ABExpansion extends PlaceholderExpansion {

    private final ArchitectBlocks plugin;

    public ABExpansion(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "architectblocks";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Dalict";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        switch (params.toLowerCase()) {
            case "authorized": {
                boolean yes = player.hasPermission(ArchitectBlocks.PERM_ADMIN)
                        || plugin.canUse(player);
                return String.valueOf(yes);
            }
            case "expires":
                return plugin.getAccessExpireText(player);
            case "flying":
                return player.isFlying() ? "true" : "false";
            default:
                return null;
        }
    }
}
