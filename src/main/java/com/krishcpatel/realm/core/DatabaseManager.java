package com.krishcpatel.realm.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;

public class DatabaseManager {
    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void connect() throws SQLException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
        }

        File dbFile = new File(dataFolder, "database.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        connection = DriverManager.getConnection(url);

        initSchema();
        migrateSchema();
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database is not connected.");
        }
        return connection;
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            } finally {
                connection = null;
            }
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS players (
                uuid TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                first_join INTEGER NOT NULL,
                last_login INTEGER NOT NULL,
                balance INTEGER NOT NULL DEFAULT 0
              );
            """);
        }
    }

    private void migrateSchema() throws SQLException {
        migratePlayersTable();
    }

    private void migratePlayersTable() throws SQLException {
        if (!tableExists("players")) return;

        if (!columnExists("players", "first_join")) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE players ADD COLUMN first_join INTEGER NOT NULL DEFAULT 0;");
            }
        }

        if (!columnExists("players", "last_login")) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE players ADD COLUMN last_login INTEGER NOT NULL DEFAULT 0;");
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("PRAGMA table_info(" + table + ")")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (column.equalsIgnoreCase(name)) return true;
                }
                return false;
            }
        }
    }
}
