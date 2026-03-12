package com.krishcpatel.realm.core.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
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
    private static final int SQLITE_BUSY_RETRY_ATTEMPTS = 8;
    private static final long SQLITE_BUSY_RETRY_BASE_DELAY_MS = 25L;

    private final JavaPlugin plugin;
    private final Object writeLock = new Object();
    private String jdbcUrl;

    /**
     * SQL operation that returns a value and may throw {@link SQLException}.
     *
     * @param <T> return type
     */
    @FunctionalInterface
    public interface SqlCallable<T> {
        T call() throws SQLException;
    }

    /**
     * SQL operation with no return value that may throw {@link SQLException}.
     */
    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }

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

    /**
     * Executes a write operation under a global write lock with retry/backoff
     * for transient {@code SQLITE_BUSY} lock contention.
     *
     * @param action write action
     * @param <T> return type
     * @return action return value
     * @throws SQLException when the operation permanently fails
     */
    public <T> T executeWrite(SqlCallable<T> action) throws SQLException {
        synchronized (writeLock) {
            return executeWithBusyRetry(action);
        }
    }

    /**
     * Executes a write operation under a global write lock with retry/backoff
     * for transient {@code SQLITE_BUSY} lock contention.
     *
     * @param action write action
     * @throws SQLException when the operation permanently fails
     */
    public void executeWrite(SqlRunnable action) throws SQLException {
        executeWrite(() -> {
            action.run();
            return null;
        });
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

    private <T> T executeWithBusyRetry(SqlCallable<T> action) throws SQLException {
        SQLException lastError = null;

        for (int attempt = 1; attempt <= SQLITE_BUSY_RETRY_ATTEMPTS; attempt++) {
            try {
                return action.call();
            } catch (SQLException ex) {
                if (!isSqliteBusy(ex) || attempt == SQLITE_BUSY_RETRY_ATTEMPTS) {
                    throw ex;
                }

                lastError = ex;
                long backoffMs = SQLITE_BUSY_RETRY_BASE_DELAY_MS * attempt;
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    SQLException wrapped = new SQLException("Interrupted while waiting for SQLite write lock", interrupted);
                    wrapped.addSuppressed(ex);
                    throw wrapped;
                }
            }
        }

        throw lastError == null ? new SQLException("SQLite write operation failed after retries.") : lastError;
    }

    private boolean isSqliteBusy(SQLException ex) {
        SQLException cursor = ex;
        while (cursor != null) {
            if (cursor.getErrorCode() == 5) {
                return true;
            }

            String message = cursor.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains("SQLITE_BUSY")) {
                return true;
            }

            cursor = cursor.getNextException();
        }
        return false;
    }
}
