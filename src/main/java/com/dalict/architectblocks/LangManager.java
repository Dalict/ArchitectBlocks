package com.dalict.architectblocks;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 语言文件管理：从服务器自身 jar 内的 assets/minecraft/lang/ 按需解出语言，
 * 与服务器版本严格一致，无需网络下载。懒加载 + 缓存。
 */
public class LangManager {

    private final ArchitectBlocks plugin;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public LangManager(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 获取指定语言代码（如 zh_cn）的翻译表，失败返回空表 */
    public Map<String, String> get(String code) {
        if (code == null || code.isEmpty()) {
            return Collections.emptyMap();
        }
        String normalized = code.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return cache.computeIfAbsent(normalized, this::load);
    }

    private Map<String, String> load(String code) {
        // 优先：服务器 jar 自带语言（与版本严格一致）
        try (JarFile jar = locateServerJar()) {
            JarEntry entry = jar.getJarEntry("assets/minecraft/lang/" + code + ".json");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry);
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    Map<String, String> map = new Gson().fromJson(reader,
                            new TypeToken<Map<String, String>>() { }.getType());
                    if (map != null && !map.isEmpty()) {
                        plugin.getLogger().info("已加载语言文件(服务器 jar): " + code + " (" + map.size() + " 条)");
                        return map;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 兜底：插件内置语言文件（lang/<code>.json）
        try (InputStream in = LangManager.class.getResourceAsStream("/lang/" + code + ".json")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    Map<String, String> map = new Gson().fromJson(reader,
                            new TypeToken<Map<String, String>>() { }.getType());
                    if (map != null && !map.isEmpty()) {
                        plugin.getLogger().info("已加载语言文件(插件内置): " + code + " (" + map.size() + " 条)");
                        return map;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("加载语言文件失败: " + code + " - " + e.getMessage());
        }
        plugin.getLogger().warning("语言文件不可用，该语言搜索将被跳过: " + code);
        return Collections.emptyMap();
    }

    private JarFile locateServerJar() throws IOException {
        try {
            Path jarPath = Path.of(Bukkit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new JarFile(jarPath.toFile());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("无法定位服务器 jar", e);
        }
    }
}
