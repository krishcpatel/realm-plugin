package com.krishcpatel.realm.core.player;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database access layer for player records.
 *
 * <p>This class contains SQL operations for the {@code players} table.</p>
 */
public class PlayerRepository {
    private final DatabaseManager db;
    private final Core plugin;

    /**
     * Creates a repository backed by the provided database manager.
     *
     * @param db database manager
     * @param plugin owning plugin (used for debug logging)
     */
    public PlayerRepository(DatabaseManager db, Core plugin) {
        this.db = db;
        this.plugin = plugin;
    }

    /**
     * Inserts or updates a player record.
     *
     * <p>If the UUID does not exist, a new row is created. If it exists, the username and
     * last login timestamp are updated.</p>
     *
     * @param uuid player UUID (string form)
     * @param username current player name
     * @param now epoch milliseconds
     * @throws SQLException if the database operation fails
     */
    public void upsertPlayer(String uuid, String username, long now) throws SQLException {
        int updated;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
            INSERT INTO players (uuid, username, first_join, last_login)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                last_login = excluded.last_login
        """)) {
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setLong(3, now);
            ps.setLong(4, now);
            updated = ps.executeUpdate();
        }

        plugin.debug("Upserted player " + username + " (" + uuid + "), rows=" + updated);
    }

    /**
     * Checks whether a player record exists.
     *
     * @param uuid player UUID (string form)
     * @return true if present in database
     * @throws SQLException if query fails
     */
    public boolean exists(String uuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE uuid = ? LIMIT 1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
