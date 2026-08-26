package com.dalict.architectblocks;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
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
import java.util.jar.JarInputStream;

/**
 * 语言文件管理，三级来源：
 * 1. 服务器 jar 自带（版本严格一致，部分核心不打包）
 * 2. 插件数据目录 lang/&lt;code&gt;.json（启动时按配置自动从官方源下载）
 * 3. 插件内置 lang/&lt;code&gt;.json 兜底
 * 懒加载 + 缓存。
 */
public class LangManager {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String BMCLAPI = "https://bmclapi2.bangbang93.com";

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
        // 3) 插件内置兜底
        try (InputStream in = LangManager.class.getResourceAsStream("/lang/" + code + ".json")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    Map<String, String> map = gson.fromJson(reader,
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

    /** 下载客户端 jar 并流式抽取目标语言条目（不保存整个 jar） */
    private void downloadAll(List<String> langs, String mc, Path dir, String mirror) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        // 1) 版本清单 -> 版本详情地址
        Map<String, Object> manifestMap = gson.fromJson(httpGet(client, rewrite(MANIFEST_URL, mirror)),
                new TypeToken<Map<String, Object>>() { }.getType());
        String versionUrl = null;
        Object versionsObj = manifestMap == null ? null : manifestMap.get("versions");
        if (versionsObj instanceof List<?> versions) {
            for (Object o : versions) {
                if (o instanceof Map<?, ?> v && mc.equals(String.valueOf(v.get("id")))) {
                    versionUrl = String.valueOf(v.get("url"));
                    break;
                }
            }
        }
        if (versionUrl == null) {
            throw new IllegalStateException("版本清单中找不到 " + mc);
        }
        // 2) 版本详情 -> 客户端 jar 地址与 sha1
        Map<String, Object> version = gson.fromJson(httpGet(client, rewrite(versionUrl, mirror)),
                new TypeToken<Map<String, Object>>() { }.getType());
        Map<?, ?> downloads = (Map<?, ?>) version.get("downloads");
        Map<?, ?> clientJar = (Map<?, ?>) downloads.get("client");
        String jarUrl = String.valueOf(clientJar.get("url"));
        String expectedSha1 = String.valueOf(clientJar.get("sha1"));

        // 3) 流式下载 jar，抽取全部目标语言文件
        Map<String, byte[]> extracted = new HashMap<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create(rewrite(jarUrl, mirror)))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("客户端 jar 下载失败 HTTP " + response.statusCode());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (JarInputStream jarIn = new JarInputStream(response.body())) {
            JarEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = jarIn.getNextJarEntry()) != null) {
                boolean isWanted = false;
                for (String code : langs) {
                    if (("assets/minecraft/lang/" + code + ".json").equals(entry.getName())) {
                        isWanted = true;
                        break;
                    }
                }
                ByteArrayOutputStream out = isWanted ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = jarIn.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                    if (out != null) {
                        out.write(buffer, 0, n);
                    }
                }
                if (out != null && out.size() > 0) {
                    extracted.put(entry.getName(), out.toByteArray());
                }
            }
        }
        String actualSha1 = hex(digest.digest());
        if (expectedSha1 != null && !expectedSha1.isEmpty()
                && !"null".equals(expectedSha1) && !actualSha1.equalsIgnoreCase(expectedSha1)) {
            throw new IllegalStateException("客户端 jar 校验不一致 sha1=" + actualSha1);
        }
        for (String code : langs) {
            byte[] data = extracted.get("assets/minecraft/lang/" + code + ".json");
            if (data == null) {
                plugin.getLogger().warning("官方客户端中不存在语言: " + code);
                continue;
            }
            Files.write(dir.resolve(code + ".json"), data);
            plugin.getLogger().info("已下载官方语言文件: " + code + ".json (" + data.length / 1024 + " KB)");
        }
        if (extracted.isEmpty()) {
            throw new IllegalStateException("未从客户端 jar 中抽取到任何语言文件");
        }
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

    /** BMCLAPI 镜像：把官方域替换为镜像域 */
    private String rewrite(String url, String mirror) {
        if (!"bmclapi".equals(mirror)) {
            return url;
        }
        return url.replace("https://piston-meta.mojang.com", BMCLAPI)
                .replace("https://piston-data.mojang.com", BMCLAPI)
                .replace("https://launchermeta.mojang.com", BMCLAPI);
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
