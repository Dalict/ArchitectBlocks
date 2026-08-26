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
        return cache.computeIfAbsent(code, this::load);
    }

    private Map<String, String> load(String code) {
        try (JarFile jar = locateServerJar()) {
            JarEntry entry = jar.getJarEntry("assets/minecraft/lang/" + code + ".json");
            if (entry == null) {
                plugin.getLogger().warning("服务器 jar 中不存在语言文件: " + code + ".json（该语言搜索不可用）");
                return Collections.emptyMap();
            }
            try (InputStream in = jar.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, String> map = new Gson().fromJson(reader,
                        new TypeToken<Map<String, String>>() { }.getType());
                if (map == null) {
                    return Collections.emptyMap();
                }
                plugin.getLogger().info("已加载语言文件: " + code + " (" + map.size() + " 条)");
                return map;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("加载语言文件失败: " + code + " - " + e.getMessage());
            return Collections.emptyMap();
        }
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
