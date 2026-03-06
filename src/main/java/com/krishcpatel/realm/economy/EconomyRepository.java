package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database access layer for economy operations.
 *
 * <p>Initially, economy may be stored directly in the {@code players.balance} column.
 * This repository provides SQL helpers for balance reads/updates.</p>
 */
public class EconomyRepository {
    private final DatabaseManager db;

    /**
     * Creates a repository backed by the provided database manager.
     *
     * @param db database manager
     */
    public EconomyRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Initializes economy accounts for players
     * @throws SQLException if query fails
     */
    public void initSchema() throws SQLException {
        try (var st = db.getConnection().createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS economy_accounts (
                player_uuid TEXT PRIMARY KEY,
                balance INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
              );
            """);
        }
    }

    /**
     * Ensures an account exists for the given player.
     *
     * @param uuid player UUID
     * @throws SQLException if database access fails
     */
    public void ensureAccount(String uuid) throws SQLException {
        ensureAccount(db.getConnection(), uuid);
    }

    /**
     * Ensures an account exists for the given player using the provided connection.
     *
     * @param c database connection
     * @param uuid player UUID
     * @throws SQLException if database access fails
     */
    public void ensureAccount(Connection c, String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO economy_accounts (player_uuid, balance)
            VALUES (?, 0)
            ON CONFLICT(player_uuid) DO NOTHING
    """)) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    /**
     * Returns a player's current balance.
     *
     * @param uuid player UUID (string form)
     * @return balance (integer currency units)
     * @throws SQLException if query fails
     */
    public long getBalance(String uuid) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM economy_accounts WHERE player_uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    /**
     * Adds money to the account using the provided connection.
     *
     * @param c database connection
     * @param uuid player UUID
     * @param amount amount to add
     * @throws SQLException if database access fails
     */
    public void addBalance(Connection c, String uuid, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            UPDATE economy_accounts
            SET balance = balance + ?
            WHERE player_uuid = ?
    """)) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    /**
     * Adds money to a player's bank balance.
     *
     * @param uuid player UUID
     * @param amount amount to add
     * @throws SQLException if database access fails
     */
    public void addBalance(String uuid, long amount) throws SQLException {
        try (Connection c = db.getConnection()) {
            addBalance(c, uuid, amount);
        }
    }

    /**
     * Attempts to subtract money without allowing a negative balance.
     *
     * @param c database connection
     * @param uuid player UUID
     * @param amount amount to subtract
     * @return true if successful
     * @throws SQLException if database access fails
     */
    public boolean subtractBalanceFloorZero(Connection c, String uuid, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            UPDATE economy_accounts
            SET balance = balance - ?
            WHERE player_uuid = ?
              AND balance >= ?
    """)) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Attempts to subtract money from a player's bank balance without allowing it to go below zero.
     *
     * @param uuid player UUID
     * @param amount amount to subtract
     * @return true if the subtraction succeeded
     * @throws SQLException if database access fails
     */
    public boolean subtractBalanceFloorZero(String uuid, long amount) throws SQLException {
        try (Connection c = db.getConnection()) {
            return subtractBalanceFloorZero(c, uuid, amount);
        }
    }

    /**
     * Sets the balance for a player's account to an exact value.
     *
     * <p>This does not apply any clamping; callers should clamp to 0 if required.</p>
     *
     * @param uuid player UUID (string form)
     * @param newBalance new stored balance value
     * @throws SQLException if database access fails
     */
    public void setBalance(String uuid, long newBalance) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement("""
          UPDATE economy_accounts
          SET balance = ?
          WHERE player_uuid = ?
        """)) {
            ps.setLong(1, newBalance);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }
}
