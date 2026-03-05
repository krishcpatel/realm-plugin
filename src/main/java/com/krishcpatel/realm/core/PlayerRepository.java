package com.krishcpatel.realm.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PlayerRepository {
    private final DatabaseManager db;
    private final Core plugin;

    public PlayerRepository(DatabaseManager db, Core plugin) {
        this.db = db;
        this.plugin = plugin;
    }

    // Insert if missing, otherwise update username + last_login
    public void upsertPlayer(String uuid, String username, long now) throws SQLException {
        Connection c = db.getConnection();
        int updated;
        try (PreparedStatement ps = c.prepareStatement("""
          INSERT INTO players (uuid, username, first_join, last_login, balance)
          VALUES (?, ?, ?, ?, 0)
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

    public boolean exists(String uuid) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE uuid = ? LIMIT 1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long getBalance(String uuid) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    public void addBalance(String uuid, long amount) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET balance = balance + ? WHERE uuid = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public void subtractBalance(String uuid, long amount) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET balance = balance - ? WHERE uuid = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }
}
