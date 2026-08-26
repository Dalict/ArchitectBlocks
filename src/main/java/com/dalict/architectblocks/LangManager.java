package com.dalict.architectblocks;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 语言文件管理，两级来源：
 * 1. 服务器 jar 自带（版本严格一致，部分核心不打包）
 * 2. 插件数据目录 lang/&lt;code&gt;.json（启动时按配置自动从官方源下载，en_us 始终下载）
 * 不打包进插件，避免 MC 版本更新后语言文件过时。
 * 懒加载 + 缓存。
 */
public class LangManager {


    private final ArchitectBlocks plugin;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    public LangManager(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    /** 获取指定语言代码（如 zh_cn）的翻译表，失败返回空表 */
    public Map<String, String> get(String code) {
        if (code == null || code.isEmpty()) {
            return Collections.emptyMap();
        }
        String normalized = code.toLowerCase(Locale.ROOT).replace('-', '_');
        return cache.computeIfAbsent(normalized, this::load);
    }

    public void clearCache() {
        cache.clear();
    }

    private Map<String, String> load(String code) {
        // 1) 服务器 jar
        try (JarFile jar = locateServerJar()) {
            JarEntry entry = jar.getJarEntry("assets/minecraft/lang/" + code + ".json");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry);
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    Map<String, String> map = gson.fromJson(reader,
                            new TypeToken<Map<String, String>>() { }.getType());
                    if (map != null && !map.isEmpty()) {
                        plugin.getLogger().info("已加载语言文件(服务器 jar): " + code + " (" + map.size() + " 条)");
                        return map;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 2) 数据目录下载缓存
        Path downloaded = plugin.getDataFolder().toPath().resolve("lang").resolve(code + ".json");
        if (Files.isRegularFile(downloaded)) {
            try (java.io.BufferedReader reader = Files.newBufferedReader(downloaded, StandardCharsets.UTF_8)) {
                Map<String, String> map = gson.fromJson(reader,
                        new TypeToken<Map<String, String>>() { }.getType());
                if (map != null && !map.isEmpty()) {
                    plugin.getLogger().info("已加载语言文件(已下载): " + code + " (" + map.size() + " 条)");
                    return map;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("语言缓存文件损坏，将尝试重新获取: " + code + " - " + e.getMessage());
            }
        }
        // 3) 数据库缓存（跨服共用 MySQL 时可共享）
        Map<String, String> fromDb = plugin.getDb().loadLang(code);
        if (!fromDb.isEmpty()) {
            plugin.getLogger().info("已加载语言文件(数据库): " + code + " (" + fromDb.size() + " 条)");
            return fromDb;
        }
        plugin.getLogger().warning("语言文件不可用（未下载成功且服务器 jar 未附带），该语言搜索将被跳过: " + code);
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

    // ==================== 官方语言文件下载 ====================

    /**
     * 按配置的语言列表从官方源下载语言文件到 plugins/ArchitectBlocks/lang/。
     * 需在异步线程调用。同一 MC 版本只下载一次（.mcversion 标记）。
     */
    public void downloadConfiguredLanguages() {
        List<String> langs = new ArrayList<>();
        for (String code : plugin.getConfig().getStringList("search.languages")) {
            String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.isEmpty() && !langs.contains(normalized)) {
                langs.add(normalized);
            }
        }
        if (langs.isEmpty()) {
            langs.add("zh_cn");
        }
        if (!langs.contains("en_us")) {
            langs.add("en_us"); // 英文名搜索依赖，始终下载
        }
        Path dir = plugin.getDataFolder().toPath().resolve("lang");
        String mc = detectMcVersion();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            plugin.getLogger().warning("无法创建语言目录: " + e.getMessage());
            return;
        }
        Path marker = dir.resolve(".mcversion");
        boolean cached = false;
        if (Files.isRegularFile(marker)) {
            try {
                cached = mc.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
            } catch (IOException ignored) {
            }
        }
        if (cached) {
            boolean allPresent = true;
            for (String code : langs) {
                if (!Files.isRegularFile(dir.resolve(code + ".json"))) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                plugin.getLogger().info("语言文件已是最新（" + mc + "），跳过下载");
                return;
            }
        }

        String mirrorCfg = plugin.getConfig().getString("search.mirror", "auto");
        List<String> mirrors = new ArrayList<>();
        if ("mojang".equalsIgnoreCase(mirrorCfg)) {
            mirrors.add("mojang");
        } else if ("bmclapi".equalsIgnoreCase(mirrorCfg)) {
            mirrors.add("bmclapi");
        } else {
            // auto：国内服务器友好，BMCLAPI 优先，失败回退官方
            mirrors.add("bmclapi");
            mirrors.add("mojang");
        }
        for (String mirror : mirrors) {
            try {
                downloadAll(langs, mc, dir, mirror);
                Files.writeString(marker, mc, StandardCharsets.UTF_8);
                cache.clear();
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("从 " + mirror + " 下载语言文件失败: " + e.getMessage());
            }
        }
        plugin.getLogger().warning("所有下载源均失败，将使用已有语言文件继续运行。");
    }

    /**
     * 按资源索引下载语言文件（借鉴 SweetPlayerMarket 的方案）：
     * 版本清单 -> 版本 JSON -> assetIndex -> objects["minecraft/lang/<code>.json"].hash
     * -> 单文件下载（每个语言文件约数百 KB，无需下载整个客户端 jar）
     */
    private void downloadAll(List<String> langs, String mc, Path dir, String mirror) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        String bmclapi = "https://bmclapi2.bangbang93.com";
        boolean isBmclapi = "bmclapi".equals(mirror);
        // 1) 版本清单
        String manifestUrl = isBmclapi
                ? bmclapi + "/mc/game/version_manifest.json"
                : "https://piston-meta.mojang.com/mc/game/version_manifest.json";
        Map<String, Object> manifest = gson.fromJson(httpGet(client, manifestUrl),
                new TypeToken<Map<String, Object>>() { }.getType());
        // 2) 版本 JSON（BMCLAPI 有专用直连端点）
        String versionJsonUrl = null;
        Object versionsObj = manifest.get("versions");
        if (versionsObj instanceof List<?> versions) {
            for (Object o : versions) {
                if (o instanceof Map<?, ?> v && mc.equals(String.valueOf(v.get("id")))) {
                    versionJsonUrl = isBmclapi
                            ? bmclapi + "/version/" + mc + "/json"
                            : String.valueOf(v.get("url"));
                    break;
                }
            }
        }
        if (versionJsonUrl == null) {
            throw new IllegalStateException("版本清单中找不到 " + mc);
        }
        Map<String, Object> version = gson.fromJson(httpGet(client, versionJsonUrl),
                new TypeToken<Map<String, Object>>() { }.getType());
        // 3) 资源索引地址（BMCLAPI 取路径改写）
        Map<?, ?> assetIndex = (Map<?, ?>) version.get("assetIndex");
        String assetIndexUrl = String.valueOf(assetIndex.get("url"));
        if (isBmclapi) {
            String path = URI.create(assetIndexUrl).getPath();
            assetIndexUrl = bmclapi + path;
        }
        Map<String, Object> index = gson.fromJson(httpGet(client, assetIndexUrl),
                new TypeToken<Map<String, Object>>() { }.getType());
        Map<?, ?> objects = (Map<?, ?>) index.get("objects");
        if (objects == null) {
            throw new IllegalStateException("资源索引格式异常");
        }
        // 4) 逐个语言文件按哈希下载
        boolean any = false;
        for (String code : langs) {
            Map<?, ?> entry = (Map<?, ?>) objects.get("minecraft/lang/" + code + ".json");
            if (entry == null) {
                plugin.getLogger().warning("官方资源索引中不存在语言: " + code);
                continue;
            }
            String hash = String.valueOf(entry.get("hash"));
            String assetUrl = isBmclapi
                    ? bmclapi + "/assets/" + hash.substring(0, 2) + "/" + hash
                    : "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            byte[] data = httpGetBytes(client, assetUrl);
            // SHA1 校验
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            String actual = hex(digest.digest(data));
            if (!actual.equalsIgnoreCase(hash)) {
                throw new IllegalStateException(code + " 下载校验失败 sha1=" + actual);
            }
            Files.write(dir.resolve(code + ".json"), data);
            plugin.getLogger().info("已下载官方语言文件: " + code + ".json (" + data.length / 1024 + " KB)");
            // 写入数据库缓存
            try {
                Map<String, String> parsed = gson.fromJson(
                        new String(data, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() { }.getType());
                plugin.getDb().saveLang(code, parsed);
            } catch (Exception ignored) {
            }
            any = true;
        }
        if (!any) {
            throw new IllegalStateException("未下载到任何语言文件");
        }
    }

    private byte[] httpGetBytes(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
        }
        return response.body();
    }

    private String httpGet(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
        }
        return response.body();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 从 Bukkit 版本串提取 MC 版本，如 1.21.11-R0.1-SNAPSHOT -> 1.21.11 */
    private String detectMcVersion() {
        String v = Bukkit.getBukkitVersion();
        int idx = v.indexOf('-');
        return idx > 0 ? v.substring(0, idx) : v;
    }
}
