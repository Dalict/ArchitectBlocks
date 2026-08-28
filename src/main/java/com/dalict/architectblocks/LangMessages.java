package com.dalict.architectblocks;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 语言文件管理：从 lang/<code>.yml 加载全部显示文本。
 * 首次运行自动从 jar 释放；结构变化自动比对重建（与主配置同方案）。
 */
public class LangMessages {

    private final ArchitectBlocks plugin;
    private YamlConfiguration lang = new YamlConfiguration();
    private File langFile;

    public LangMessages(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 加载（或切换）语言文件 */
    public void load() {
        String code = plugin.getConfig().getString("language", "zh_CN");
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        langFile = new File(dir, code + ".yml");
        if (!langFile.isFile()) {
            copyFromJar(code);
        }
        if (!langFile.isFile()) {
            // 指定的语言不存在，回退 zh_CN
            plugin.getLogger().warning("语言文件 " + code + ".yml 不存在，回退 zh_CN");
            code = "zh_CN";
            langFile = new File(dir, code + ".yml");
            if (!langFile.isFile()) {
                copyFromJar(code);
            }
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
        // 缺失键回退 jar 内默认
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in != null) {
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                lang.setDefaults(defaults);
            }
        } catch (Exception ignored) {
        }
        syncStructure();
        plugin.getLogger().info("语言文件已加载: " + code + ".yml");
    }

    /** 结构比对：不一致时以 jar 默认为骨架重建（保留用户值） */
    private void syncStructure() {
        if (langFile == null || !langFile.isFile()) {
            return;
        }
        try {
            YamlConfiguration user = YamlConfiguration.loadConfiguration(langFile);
            YamlConfiguration defaults = new YamlConfiguration();
            String code = plugin.getConfig().getString("language", "zh_CN");
            try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
                if (in == null) {
                    return;
                }
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            Set<String> defKeys = flatten(defaults);
            Set<String> userKeys = flatten(user);
            if (defKeys.equals(userKeys)) {
                return;
            }
            java.util.Map<String, Object> overrides = new java.util.LinkedHashMap<>();
            for (String key : userKeys) {
                if (defKeys.contains(key) && !java.util.Objects.equals(user.get(key), defaults.get(key))) {
                    overrides.put(key, user.get(key));
                }
            }
            java.nio.file.Files.deleteIfExists(langFile.toPath());
            copyFromJar(code);
            if (langFile.isFile() && !overrides.isEmpty()) {
                applyOverrides(langFile, overrides);
            }
            lang = YamlConfiguration.loadConfiguration(langFile);
            try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
                if (in != null) {
                    YamlConfiguration fallback = new YamlConfiguration();
                    fallback.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                    lang.setDefaults(fallback);
                }
            }
            plugin.getLogger().info("语言文件结构已自动更新（用户文本与注释保留）");
        } catch (Exception e) {
            plugin.getLogger().warning("语言文件结构比对失败: " + e.getMessage());
        }
    }

    private void applyOverrides(File file, java.util.Map<String, Object> overrides) {
        try {
            List<String> lines = new ArrayList<>(
                    Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            List<String> output = new ArrayList<>();
            java.util.Deque<String> pathStack = new java.util.ArrayDeque<>();
            java.util.Deque<Integer> indentStack = new java.util.ArrayDeque<>();
            int i = 0;
            while (i < lines.size()) {
                String line = lines.get(i);
                String trimmed = line.trim();
                int indent = line.length() - line.stripLeading().length();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    output.add(line);
                    i++;
                    continue;
                }
                while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
                    indentStack.pop();
                    pathStack.pop();
                }
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx < 0) {
                    output.add(line);
                    i++;
                    continue;
                }
                String key = trimmed.substring(0, colonIdx).trim();
                String valuePart = colonIdx + 1 < trimmed.length()
                        ? trimmed.substring(colonIdx + 1).trim() : "";
                String fullPath = buildPathOf(pathStack, key);
                if (valuePart.isEmpty()) {
                    int next = i + 1;
                    while (next < lines.size()
                            && (lines.get(next).trim().isEmpty() || lines.get(next).trim().startsWith("#"))) {
                        next++;
                    }
                    if (next < lines.size() && lines.get(next).trim().startsWith("- ")) {
                        if (overrides.containsKey(fullPath) && overrides.get(fullPath) instanceof List) {
                            output.add(line);
                            List<?> list = (List<?>) overrides.get(fullPath);
                            String itemIndent = " ".repeat(indent + 2);
                            for (Object item : list) {
                                output.add(itemIndent + "- " + yamlValueOf(item));
                            }
                            i = next;
                            while (i < lines.size() && lines.get(i).trim().startsWith("- ")) {
                                i++;
                            }
                            continue;
                        }
                        output.add(line);
                        i++;
                        while (i < lines.size() && lines.get(i).trim().startsWith("- ")) {
                            output.add(lines.get(i));
                            i++;
                        }
                        continue;
                    }
                    pathStack.push(key);
                    indentStack.push(indent);
                    output.add(line);
                    i++;
                } else {
                    if (overrides.containsKey(fullPath)) {
                        output.add(line.substring(0, indent) + key + ": " + yamlValueOf(overrides.get(fullPath)));
                    } else {
                        output.add(line);
                    }
                    i++;
                }
            }
            Files.write(file.toPath(), output, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("语言文件用户值回填失败: " + e.getMessage());
        }
    }

    private String buildPathOf(java.util.Deque<String> stack, String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : stack) {
            sb.append(part).append(".");
        }
        sb.append(key);
        return sb.toString();
    }

    private String yamlValueOf(Object val) {
        if (val == null) {
            return "''";
        }
        if (val instanceof Boolean || val instanceof Number) {
            return String.valueOf(val);
        }
        String s = String.valueOf(val);
        if (s.isEmpty()) {
            return "''";
        }
        return "'" + s.replace("'", "''") + "'";
    }

    private void copyFromJar(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in != null) {
                Files.copy(in, langFile.toPath());
                plugin.getLogger().info("已释放语言文件: lang/" + code + ".yml");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("释放语言文件失败: " + e.getMessage());
        }
    }

    /** 获取消息（带颜色转换） */
    public String msg(String key) {
        return ArchitectBlocks.color(
                lang.getString("messages." + key, "&c缺少消息: " + key));
    }

    /** 获取消息原文（不带颜色），用于列表型消息 */
    public List<String> msgList(String key) {
        List<String> raw = lang.getStringList("messages." + key);
        List<String> out = new ArrayList<>();
        for (String line : raw) {
            out.add(ArchitectBlocks.color(line));
        }
        return out;
    }

    /** 获取 GUI 名称 */
    public String guiName(String key, String def) {
        return lang.getString("gui.names." + key, def);
    }

    /** 快捷物品名称 */
    public String quickItemName() {
        return lang.getString("quick-item.name", "&8[ &a建筑物品菜单 &8]");
    }

    /** 快捷物品描述 */
    public List<String> quickItemLore() {
        return lang.getStringList("quick-item.lore");
    }

    private Set<String> flatten(YamlConfiguration cfg) {
        Set<String> keys = new TreeSet<>();
        collect(cfg, "", keys);
        return keys;
    }

    private void collect(ConfigurationSection section, String prefix, Set<String> keys) {
        for (String key : section.getKeys(false)) {
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collect(section.getConfigurationSection(key), full, keys);
            } else {
                keys.add(full);
            }
        }
    }
}
