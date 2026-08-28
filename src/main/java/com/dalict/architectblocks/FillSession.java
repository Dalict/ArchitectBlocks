package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 填充工具会话：每个玩家独立记忆选点、方块、模式。
 * 数据仅存内存（退出服务器自动清空，符合临时工具定位）。
 */
public class FillSession {

    /** 填充模式 */
    public enum Mode {
        REPLACE_ALL("替换全部"), HOLLOW("空心"), OUTLINE("轮廓"), KEEP("保留"), REPLACE("替换");

        private final String displayName;

        Mode(String name) {
            this.displayName = name;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** 每玩家会话 */
    public static class PlayerSession {
        public Location pointA;
        public Location pointB;
        public Material fillBlock = Material.AIR;
        public Material replaceBlock = Material.AIR;
        public Mode mode = Mode.REPLACE_ALL;
    }

    private final ArchitectBlocks plugin;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    public FillSession(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    public PlayerSession get(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), k -> new PlayerSession());
    }

    public void clear(Player player) {
        sessions.remove(player.getUniqueId());
    }

    /** 执行填充，返回填充的方块数；-1 表示参数不完整或超出限制 */
    public int execute(Player player) {
        PlayerSession s = get(player);
        if (s.pointA == null || s.pointB == null) {
            return -1;
        }
        if (s.pointA.getWorld() != s.pointB.getWorld()) {
            return -2;
        }
        int maxVolume = plugin.getConfig().getInt("settings.fill-max-volume", 32768);
        int minX = Math.min(s.pointA.getBlockX(), s.pointB.getBlockX());
        int maxX = Math.max(s.pointA.getBlockX(), s.pointB.getBlockX());
        int minY = Math.min(s.pointA.getBlockY(), s.pointB.getBlockY());
        int maxY = Math.max(s.pointA.getBlockY(), s.pointB.getBlockY());
        int minZ = Math.min(s.pointA.getBlockZ(), s.pointB.getBlockZ());
        int maxZ = Math.max(s.pointA.getBlockZ(), s.pointB.getBlockZ());
        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > maxVolume) {
            return -(int) volume; // 负数表示超出限制，绝对值=请求的体积
        }
        World world = s.pointA.getWorld();
        int count = 0;
        boolean fillAir = s.fillBlock == Material.AIR;
        boolean replaceMode = s.mode == Mode.REPLACE;
        Material replaceTarget = s.replaceBlock;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    boolean shouldFill;
                    switch (s.mode) {
                        case HOLLOW:
                            shouldFill = edge;
                            break;
                        case OUTLINE:
                            shouldFill = edge;
                            break;
                        case KEEP:
                            Block keepBlock = world.getBlockAt(x, y, z);
                            shouldFill = keepBlock.getType().isAir();
                            break;
                        case REPLACE:
                            Block repBlock = world.getBlockAt(x, y, z);
                            shouldFill = repBlock.getType() == replaceTarget;
                            break;
                        default:
                            shouldFill = true;
                            break;
                    }
                    if (!shouldFill) {
                        continue;
                    }
                    if (fillAir) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    } else {
                        world.getBlockAt(x, y, z).setType(s.fillBlock);
                    }
                    count++;
                }
            }
        }
        return count;
    }
}
