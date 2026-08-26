package com.dalict.architectblocks;

import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
    private final Map<Material, MaterialFlag> flagCache = new HashMap<>();

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
            loadFlags();
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
        String pagesTable = "CREATE TABLE IF NOT EXISTS ab_player_pages ("
                + "uuid VARCHAR(40) NOT NULL, category VARCHAR(40) NOT NULL, page INT NOT NULL,"
                + " PRIMARY KEY(uuid, category))";
        String stateTable = "CREATE TABLE IF NOT EXISTS ab_player_state ("
                + "uuid VARCHAR(40) PRIMARY KEY, view VARCHAR(16) NOT NULL,"
                + " keyword VARCHAR(64), page INT NOT NULL)";
        String langTable = "CREATE TABLE IF NOT EXISTS ab_lang ("
                + "code VARCHAR(8) NOT NULL, k VARCHAR(180) NOT NULL, v VARCHAR(255) NOT NULL,"
                + " PRIMARY KEY(code, k))";
        try (Statement st = conn.createStatement()) {
            st.execute(settingsTable);
            st.execute(flagsTable);
            st.execute(pagesTable);
            st.execute(stateTable);
            st.execute(langTable);
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

    /** 降版本保护：数据库里不存在于当前版本的物品名仅警告并跳过，不删除记录 */
    private void loadFlags() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT material, flag FROM ab_item_flags")) {
            while (rs.next()) {
                String name = rs.getString(1);
                String flag = rs.getString(2);
                Material mat = Material.matchMaterial(name);
                if (mat == null) {
                    plugin.getLogger().warning("数据库中的物品在当前版本不存在，已跳过: " + name);
                    continue;
                }
                try {
                    flagCache.put(mat, MaterialFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("数据库中的标记值无效，已跳过: " + name + " = " + flag);
                }
            }
        }
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

    public MaterialFlag getFlag(Material mat) {
        return flagCache.getOrDefault(mat, MaterialFlag.NORMAL);
    }

    public Map<Material, MaterialFlag> getFlags() {
        return flagCache;
    }

    /** flag 为 null 表示清除标记 */
    public void setFlag(Material mat, MaterialFlag flag) {
        if (flag == null || flag == MaterialFlag.NORMAL) {
            flagCache.remove(mat);
            asyncUpdate("DELETE FROM ab_item_flags WHERE material = ?", ps -> {
                try {
                    ps.setString(1, mat.name());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("标记删除失败: " + e.getMessage());
                }
            });
        } else {
            flagCache.put(mat, flag);
            asyncUpdate("INSERT INTO ab_item_flags(material, flag) VALUES(?, ?)"
                            + (mysql ? " ON DUPLICATE KEY UPDATE flag = VALUES(flag)" : " ON CONFLICT(material) DO UPDATE SET flag = excluded.flag"),
                    ps -> {
                        try {
                            ps.setString(1, mat.name());
                            ps.setString(2, flag.name());
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            plugin.getLogger().warning("标记写入失败: " + e.getMessage());
                        }
                    });
        }
    }

    /** 读取玩家界面状态：[view, keyword, page]；无记录返回 null（同步读，主键单行查询） */
    public String[] loadState(UUID uuid) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT view, keyword, page FROM ab_player_state WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))};
                }
                return null;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("界面状态读取失败: " + e.getMessage());
            return null;
        }
    }

    public void saveState(UUID uuid, String view, String keyword, int page) {
        asyncUpdate("INSERT INTO ab_player_state(uuid, view, keyword, page) VALUES(?, ?, ?, ?)"
                        + (mysql ? " ON DUPLICATE KEY UPDATE view = VALUES(view), keyword = VALUES(keyword), page = VALUES(page)"
                                 : " ON CONFLICT(uuid) DO UPDATE SET view = excluded.view, keyword = excluded.keyword, page = excluded.page"),
                ps -> {
                    try {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, view);
                        if (keyword == null || keyword.isEmpty()) {
                            ps.setString(3, null);
                        } else {
                            ps.setString(3, keyword.length() > 64 ? keyword.substring(0, 64) : keyword);
                        }
                        ps.setInt(4, Math.max(0, page));
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("界面状态写入失败: " + e.getMessage());
                    }
                });
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
