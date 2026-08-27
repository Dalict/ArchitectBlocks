package com.dalict.architectblocks;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * 独立 YAML 配置文件持有者（如 players.yml），
 * 避免写回主配置时丢失注释。
 */
public class FileConfigurationHolder {

    private final JavaPlugin plugin;
    private final String fileName;
    private final String header;
    private final String defaultKey;
    private FileConfiguration config;
    private File file;

    public FileConfigurationHolder(JavaPlugin plugin, String fileName, String header, String defaultKey) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.header = header;
        this.defaultKey = defaultKey;
        reload();
        if (!file.exists()) {
            config.set(defaultKey, new ArrayList<String>());
            save();
        }
    }

    public void reload() {
        file = new File(plugin.getDataFolder(), fileName);
        config = YamlConfiguration.loadConfiguration(file);
        config.options().header(header);
    }

    public FileConfiguration get() {
        return config;
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存 " + fileName + ": " + e.getMessage());
        }
    }
}
