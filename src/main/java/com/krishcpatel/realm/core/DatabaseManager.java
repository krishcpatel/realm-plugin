package com.krishcpatel.realm.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;

/**
 * Manages the Realm SQLite database connection and schema.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Create/open {@code database.db} in the plugin data folder</li>
 *   <li>Initialize schema and apply migrations</li>
 *   <li>Provide a shared {@link Connection} for repositories</li>
 * </ul>
 */
public class DatabaseManager {
    private final JavaPlugin plugin;
    private Connection connection;

    /**
     * Creates a database manager bound to the given plugin (for data folder access).
     *
     * @param plugin owning plugin instance
     */
    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the database connection and initializes schema/migrations.
     *
     * @throws SQLException if the database cannot be opened or initialized
     */
    public synchronized void connect() throws SQLException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
        }

        File dbFile = new File(dataFolder, "database.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        connection = DriverManager.getConnection(url);

        initSchema();
    }

    /**
     * Returns the active SQL connection.
     *
     * @return active connection
     * @throws SQLException if not connected or connection is closed
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database is not connected.");
        }
        return connection;
    }

    /**
     * Closes the database connection.
     *
     * <p>Safe to call multiple times.</p>
     */
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
                last_login INTEGER NOT NULL
              );
            """);
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
