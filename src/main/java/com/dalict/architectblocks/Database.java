package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 数据库层：SQLite / MySQL 可选。
 * 存储：设置(分类开关、管理员物品开关)、物品黑白名单、玩家各分类的记忆页码。
 * 全部带内存缓存；写操作异步执行，读操作走缓存或快速查询。
 */
public class Database {

    private final ArchitectBlocks plugin;
    private Connection conn;
    private boolean mysql = false;
    private final Map<String, String> settingsCache = new HashMap<>();
    private final Set<Material> blacklistCache = new HashSet<>();
    private final Set<Material> whitelistCache = new HashSet<>();

    public Database(ArchitectBlocks plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        String type = plugin.getConfig().getString("database.type", "sqlite");
        mysql = "mysql".equalsIgnoreCase(type);
        try {
            if (mysql) {
                String host = plugin.getConfig().getString("database.mysql.host", "localhost");
                int port = plugin.getConfig().getInt("database.mysql.port", 3306);
                String db = plugin.getConfig().getString("database.mysql.database", "architectblocks");
                String user = plugin.getConfig().getString("database.mysql.user", "root");
                String pass = plugin.getConfig().getString("database.mysql.password", "");
                boolean ssl = plugin.getConfig().getBoolean("database.mysql.use-ssl", false);
                tryConnectDriver("com.mysql.cj.jdbc.Driver");
                tryConnectDriver("com.mysql.jdbc.Driver");
                conn = DriverManager.getConnection(
                        "jdbc:mysql://" + host + ":" + port + "/" + db
                                + "?useSSL=" + ssl + "&autoReconnect=true&characterEncoding=utf8",
                        user, pass);
            } else {
                tryConnectDriver("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:"
                        + plugin.getDataFolder().toPath().resolve("data.db").toString());
            }
            createTables();
            loadSettings();
            loadLists();
            plugin.getLogger().info("数据库已连接 (" + (mysql ? "MySQL" : "SQLite") + ")");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库连接失败，将使用内存默认值运行: " + e.getMessage());
            conn = null;
            return false;
        }
    }

    private void tryConnectDriver(String name) {
        try {
            Class.forName(name);
        } catch (ClassNotFoundException ignored) {
        }
    }

    private void createTables() throws SQLException {
        String settingsTable = "CREATE TABLE IF NOT EXISTS ab_settings ("
                + "k VARCHAR(64) PRIMARY KEY, v VARCHAR(255))";
        String flagsTable = "CREATE TABLE IF NOT EXISTS ab_item_flags ("
                + "material VARCHAR(64) PRIMARY KEY, flag VARCHAR(8))";
        // 黑名单与白名单：独立两个存储库，只存物品ID，存入即在名单，删除即移出
        String blacklistTable = "CREATE TABLE IF NOT EXISTS ab_blacklist ("
                + "material VARCHAR(64) PRIMARY KEY)";
        String whitelistTable = "CREATE TABLE IF NOT EXISTS ab_whitelist ("
                + "material VARCHAR(64) PRIMARY KEY)";
        String pagesTable = "CREATE TABLE IF NOT EXISTS ab_player_pages ("
                + "uuid VARCHAR(40) NOT NULL, category VARCHAR(40) NOT NULL, page INT NOT NULL,"
                + " PRIMARY KEY(uuid, category))";
        String stateTable = "CREATE TABLE IF NOT EXISTS ab_player_state ("
                + "uuid VARCHAR(40) PRIMARY KEY, view VARCHAR(16) NOT NULL,"
                + " keyword VARCHAR(64), page INT NOT NULL)";
        String langTable = "CREATE TABLE IF NOT EXISTS ab_lang ("
                + "code VARCHAR(8) NOT NULL, k VARCHAR(180) NOT NULL, v VARCHAR(255) NOT NULL,"
                + " PRIMARY KEY(code, k))";
        // 每视图独立记忆页码 + 恢复目标视图（替代旧的单槽 ab_player_state）
        String viewPagesTable = "CREATE TABLE IF NOT EXISTS ab_pages ("
                + "uuid VARCHAR(40) NOT NULL, view VARCHAR(16) NOT NULL, page INT NOT NULL,"
                + " PRIMARY KEY(uuid, view))";
        String viewTable = "CREATE TABLE IF NOT EXISTS ab_view ("
                + "uuid VARCHAR(40) PRIMARY KEY, view VARCHAR(16) NOT NULL, keyword VARCHAR(64))";
        // 授权名单：name=玩家名(忽略大小写匹配)，expires=过期时间戳毫秒（0=永久）
        String accessTable = "CREATE TABLE IF NOT EXISTS ab_access ("
                + "player VARCHAR(32) PRIMARY KEY, expires INTEGER NOT NULL)";
        String customTable = mysql
                ? "CREATE TABLE IF NOT EXISTS ab_custom_items (id INT AUTO_INCREMENT PRIMARY KEY, base64 MEDIUMTEXT, name VARCHAR(255) NULL)"
                : "CREATE TABLE IF NOT EXISTS ab_custom_items (id INTEGER PRIMARY KEY AUTOINCREMENT, base64 TEXT, name VARCHAR(255) NULL)";
        try (Statement st = conn.createStatement()) {
            st.execute(settingsTable);
            st.execute(flagsTable);
            st.execute(blacklistTable);
            st.execute(whitelistTable);
            // 旧 ab_item_flags 数据迁移进独立名单表（只迁移一次，失败忽略）
            try (Statement st2 = conn.createStatement()) {
                st2.executeUpdate("INSERT OR IGNORE INTO ab_blacklist(material) SELECT material FROM ab_item_flags WHERE flag = 'BLACK'");
                st2.executeUpdate("INSERT OR IGNORE INTO ab_whitelist(material) SELECT material FROM ab_item_flags WHERE flag = 'WHITE'");
            } catch (SQLException ignored) {
                try (Statement st2 = conn.createStatement()) {
                    st2.executeUpdate("INSERT IGNORE INTO ab_blacklist(material) SELECT material FROM ab_item_flags WHERE flag = 'BLACK'");
                    st2.executeUpdate("INSERT IGNORE INTO ab_whitelist(material) SELECT material FROM ab_item_flags WHERE flag = 'WHITE'");
                } catch (SQLException ignored2) {
                }
            }
            st.execute(pagesTable);
            st.execute(stateTable);
            st.execute(langTable);
            st.execute(viewPagesTable);
            st.execute(viewTable);
            st.execute(customTable);
            st.execute(accessTable);
        }
    }

    private void loadSettings() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT k, v FROM ab_settings")) {
            while (rs.next()) {
                settingsCache.put(rs.getString(1), rs.getString(2));
            }
        }
    }

    /** 加载黑/白名单到内存缓存（含降版本保护：未知物品名仅警告跳过） */
    private void loadLists() throws SQLException {
        blacklistCache.clear();
        whitelistCache.clear();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT material FROM ab_blacklist")) {
            while (rs.next()) {
                Material mat = Material.matchMaterial(rs.getString(1));
                if (mat == null) {
                    plugin.getLogger().warning("黑名单中的物品在当前版本不存在，已跳过: " + rs.getString(1));
                } else {
                    blacklistCache.add(mat);
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT material FROM ab_whitelist")) {
            while (rs.next()) {
                Material mat = Material.matchMaterial(rs.getString(1));
                if (mat == null) {
                    plugin.getLogger().warning("白名单中的物品在当前版本不存在，已跳过: " + rs.getString(1));
                } else {
                    whitelistCache.add(mat);
                }
            }
        }
    }

    public Set<Material> getBlacklist() {
        return blacklistCache;
    }

    public Set<Material> getWhitelist() {
        return whitelistCache;
    }

    /** 存入名单（white=false 为黑名单）：只存物品ID，存入即在名单 */
    public void addToList(Material mat, boolean white) {
        String table = white ? "ab_whitelist" : "ab_blacklist";
        (white ? whitelistCache : blacklistCache).add(mat);
        asyncUpdate("INSERT " + (mysql ? "IGNORE " : "OR IGNORE ") + "INTO " + table + "(material) VALUES(?)",
                ps -> {
                    try {
                        ps.setString(1, mat.name());
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("名单写入失败: " + e.getMessage());
                    }
                });
    }

    /** 从名单移除：删除ID即移出名单 */
    public void removeFromList(Material mat, boolean white) {
        String table = white ? "ab_whitelist" : "ab_blacklist";
        (white ? whitelistCache : blacklistCache).remove(mat);
        asyncUpdate("DELETE FROM " + table + " WHERE material = ?", ps -> {
            try {
                ps.setString(1, mat.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("名单删除失败: " + e.getMessage());
            }
        });
    }

    public String getSetting(String key, String def) {
        return settingsCache.getOrDefault(key, def);
    }

    public void setSetting(String key, String value) {
        settingsCache.put(key, value);
        asyncUpdate("INSERT INTO ab_settings(k, v) VALUES(?, ?)"
                + (mysql ? " ON DUPLICATE KEY UPDATE v = VALUES(v)" : " ON CONFLICT(k) DO UPDATE SET v = excluded.v"),
                ps -> {
                    try {
                        ps.setString(1, key);
                        ps.setString(2, value);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("设置写入失败: " + e.getMessage());
                    }
                });
    }

    /** 读取恢复目标视图：[view, keyword]；无记录返回 null */
    public String[] getView(UUID uuid) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT view, keyword FROM ab_view WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), rs.getString(2)};
                }
                return null;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("视图状态读取失败: " + e.getMessage());
            return null;
        }
    }

    public void setView(UUID uuid, String view, String keyword) {
        asyncUpdate("INSERT INTO ab_view(uuid, view, keyword) VALUES(?, ?, ?)"
                        + (mysql ? " ON DUPLICATE KEY UPDATE view = VALUES(view), keyword = VALUES(keyword)"
                                 : " ON CONFLICT(uuid) DO UPDATE SET view = excluded.view, keyword = excluded.keyword"),
                ps -> {
                    try {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, view);
                        if (keyword == null || keyword.isEmpty()) {
                            ps.setString(3, null);
                        } else {
                            ps.setString(3, keyword.length() > 64 ? keyword.substring(0, 64) : keyword);
                        }
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("视图状态写入失败: " + e.getMessage());
                    }
                });
    }

    /** 读取某视图的记忆页码（同步读，主键单行查询） */
    public int getPage(UUID uuid, String view) {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT page FROM ab_pages WHERE uuid = ? AND view = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, view);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("页码读取失败: " + e.getMessage());
            return 0;
        }
    }

    public void setPage(UUID uuid, String view, int page) {
        asyncUpdate("INSERT INTO ab_pages(uuid, view, page) VALUES(?, ?, ?)"
                        + (mysql ? " ON DUPLICATE KEY UPDATE page = VALUES(page)"
                                 : " ON CONFLICT(uuid, view) DO UPDATE SET page = excluded.page"),
                ps -> {
                    try {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, view);
                        ps.setInt(3, Math.max(0, page));
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("页码写入失败: " + e.getMessage());
                    }
                });
    }

    /** 读取全部自定义物品原始记录：[id, base64, name] */
    public List<String[]> loadCustomRaw() {
        List<String[]> out = new ArrayList<>();
        if (conn == null) {
            return out;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, base64, name FROM ab_custom_items ORDER BY id")) {
            while (rs.next()) {
                out.add(new String[]{String.valueOf(rs.getInt(1)), rs.getString(2), rs.getString(3)});
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("自定义物品读取失败: " + e.getMessage());
        }
        return out;
    }

    /** 新增自定义物品 */
    public void addCustom(String base64, String name) {
        if (conn == null) {
            plugin.getLogger().warning("无数据库连接，无法保存自定义物品");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ab_custom_items(base64, name) VALUES(?, ?)")) {
            ps.setString(1, base64);
            if (name == null || name.isEmpty()) {
                ps.setString(2, null);
            } else {
                ps.setString(2, name);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("自定义物品写入失败: " + e.getMessage());
        }
    }

    /** 删除自定义物品 */
    public void removeCustom(int id) {
        if (conn == null) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ab_custom_items WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("自定义物品删除失败: " + e.getMessage());
        }
    }

    /** 判定玩家名是否在授权名单内且未过期（按名称忽略大小写匹配） */
    public boolean isAccessGranted(String playerName) {
        String[] rec = getAccessRecord(playerName);
        if (rec == null) {
            return false;
        }
        long expires = Long.parseLong(rec[1]);
        if (expires != 0 && expires <= System.currentTimeMillis()) {
            removeAccess(rec[0]); // 过期自动清理
            return false;
        }
        return true;
    }

    /** 按名称查找授权记录（忽略大小写精确查询，不再全表扫描）：[存储名, expires] */
    public String[] getAccessRecord(String playerName) {
        if (conn == null) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                mysql ? "SELECT player, expires FROM ab_access WHERE player = ? COLLATE utf8mb4_general_ci"
                      : "SELECT player, expires FROM ab_access WHERE player = ? COLLATE NOCASE")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), String.valueOf(rs.getLong(2))};
                }
                return null;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("授权查询失败: " + e.getMessage());
            return null;
        }
    }

    /** 授权：expiresAt=0 表示永久，否则为过期毫秒时间戳 */
    public void grantAccess(String playerName, long expiresAt) {
        final long expires = expiresAt <= 0 ? 0 : expiresAt;
        asyncUpdate("INSERT INTO ab_access(player, expires) VALUES(?, ?)"
                        + (mysql ? " ON DUPLICATE KEY UPDATE expires = VALUES(expires)"
                                 : " ON CONFLICT(player) DO UPDATE SET expires = excluded.expires"),
                ps -> {
                    try {
                        ps.setString(1, playerName);
                        ps.setLong(2, expires);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("授权写入失败: " + e.getMessage());
                    }
                });
    }

    public void removeAccess(String playerName) {
        asyncUpdate(mysql
                ? "DELETE FROM ab_access WHERE player = ? COLLATE utf8mb4_general_ci"
                : "DELETE FROM ab_access WHERE player = ? COLLATE NOCASE", ps -> {
            try {
                ps.setString(1, playerName);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("授权移除失败: " + e.getMessage());
            }
        });
    }

    /** 全部授权记录：[name, expires] */
    public List<String[]> loadAllAccess() {
        List<String[]> out = new ArrayList<>();
        if (conn == null) {
            return out;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT player, expires FROM ab_access ORDER BY player")) {
            while (rs.next()) {
                out.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2))});
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("授权列表读取失败: " + e.getMessage());
        }
        return out;
    }

    /** 从数据库读取语言缓存表 */
    public Map<String, String> loadLang(String code) {
        Map<String, String> out = new HashMap<>();
        if (conn == null) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT k, v FROM ab_lang WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("语言缓存读取失败: " + e.getMessage());
        }
        return out;
    }

    /** 语言表写入数据库（异步；先清后批量插入）。跨服共用 MySQL 时可共享语言缓存。 */
    public void saveLang(String code, Map<String, String> map) {
        if (conn == null || map == null || map.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                if (conn == null) {
                    return;
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM ab_lang WHERE code = ?")) {
                    del.setString(1, code);
                    del.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("语言缓存清理失败: " + e.getMessage());
                    return;
                }
                String insert = mysql
                        ? "INSERT IGNORE INTO ab_lang(code, k, v) VALUES(?, ?, ?)"
                        : "INSERT OR IGNORE INTO ab_lang(code, k, v) VALUES(?, ?, ?)";
                try (PreparedStatement ins = conn.prepareStatement(insert)) {
                    int batch = 0;
                    for (Map.Entry<String, String> e : map.entrySet()) {
                        ins.setString(1, code);
                        ins.setString(2, e.getKey());
                        ins.setString(3, e.getValue());
                        ins.addBatch();
                        if (++batch % 500 == 0) {
                            ins.executeBatch();
                        }
                    }
                    ins.executeBatch();
                } catch (SQLException e) {
                    plugin.getLogger().warning("语言缓存写入失败: " + e.getMessage());
                }
            }
        });
    }

    private interface SqlTask {
        void run(PreparedStatement ps) throws SQLException;
    }

    private void asyncUpdate(String sql, SqlTask task) {
        if (conn == null) {
            return; // 无数据库时仅内存生效
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    task.run(ps);
                } catch (SQLException e) {
                    plugin.getLogger().warning("数据库写入异常: " + e.getMessage());
                }
            }
        });
    }
}
