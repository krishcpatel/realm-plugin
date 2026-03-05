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

    /** Ensures a wallet row exists for this player.
     *
     * @param uuid player UUID (string form)
     * @throws SQLException if query fails
     * */
    public void ensureAccount(String uuid) throws SQLException {
        Connection c = db.getConnection();
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
     * Adds an amount to a player's balance (may be negative).
     *
     * @param uuid player UUID (string form)
     * @param amount delta to apply
     * @throws SQLException if update fails
     */
    public void addBalance(String uuid, long amount) throws SQLException {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE economy_accounts SET balance = balance + ? WHERE player_uuid = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    /**
     * Attempts to withdraw funds from a player's account without allowing negative balances.
     *
     * <p>This operation is atomic at the SQL level: the withdrawal only occurs if
     * {@code balance >= amount}. If insufficient funds, no update is performed.</p>
     *
     * @param uuid player UUID (string form)
     * @param amount amount to withdraw (must be positive)
     * @return {@code true} if the withdrawal succeeded; {@code false} if insufficient funds
     * @throws SQLException if database access fails
     */
    public boolean subtractBalanceFloorZero(String uuid, long amount) throws SQLException {
        Connection c = db.getConnection();
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
