package com.krishcpatel.realm.core.database;

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
 *   <li>Provide configured {@link Connection} instances for repositories</li>
 * </ul>
 */
public class DatabaseManager {
    private final JavaPlugin plugin;
    private String jdbcUrl;

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
        jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try (Connection c = openConnection()) {
            initSchema(c);
        }
    }

    /**
     * Returns the active SQL connection.
     *
     * @return active connection
     * @throws SQLException if not connected or connection is closed
     */
    public synchronized Connection getConnection() throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new SQLException("Database is not connected.");
        }
        return openConnection();
    }

    /**
     * Closes the database connection.
     *
     * <p>Safe to call multiple times.</p>
     */
    public synchronized void close() {
        jdbcUrl = null;
    }

    private Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        configureConnection(c);
        return c;
    }

    private void configureConnection(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
            st.execute("PRAGMA busy_timeout = 5000;");
            st.execute("PRAGMA journal_mode = WAL;");
        }
    }

    private void initSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
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
}
