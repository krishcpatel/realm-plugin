package com.krishcpatel.realm.economy.repository;

import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.economy.data.LedgerEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for creating and reading economy ledger entries.
 *
 * <p>The ledger is an append-only audit trail of all money movement.</p>
 */
public class LedgerRepository {
    private final DatabaseManager db;

    /**
     * Creates a ledger repository.
     *
     * @param db database manager used to obtain connections
     */
    public LedgerRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Creates the ledger schema if it does not already exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initSchema() throws SQLException {
        try (var st = db.getConnection().createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS economy_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                from_uuid TEXT,
                to_uuid TEXT,
                source TEXT NOT NULL,
                reference TEXT,
                reason TEXT,
                actor TEXT
              );
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_ledger_from ON economy_ledger(from_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ledger_to ON economy_ledger(to_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ledger_source ON economy_ledger(source);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ledger_created_at ON economy_ledger(created_at);");
        }
    }

    /**
     * Inserts a single row into {@code economy_ledger}.
     *
     * <p>This method should be called from within the same SQL transaction that updates
     * balances to ensure the ledger is consistent with account state.</p>
     *
     * @param c active database connection to use (may be part of a transaction)
     * @param createdAt timestamp in milliseconds since epoch
     * @param type transaction type (TRANSFER, MINT, BURN, SET, etc.)
     * @param amount positive amount moved
     * @param fromUuid sender UUID (nullable depending on type)
     * @param toUuid receiver UUID (nullable depending on type)
     * @param source subsystem source category
     * @param reference optional external reference id
     * @param reason optional human-readable reason
     * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
     * @return inserted ledger row id
     * @throws SQLException if database access fails
     */
    public long insertLedgerRow(
            Connection c,
            long createdAt,
            String type,
            long amount,
            String fromUuid,
            String toUuid,
            String source,
            String reference,
            String reason,
            String actor
    ) throws SQLException {

        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO economy_ledger
              (created_at, type, amount, from_uuid, to_uuid, source, reference, reason, actor)
            VALUES
              (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, PreparedStatement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, createdAt);
            ps.setString(2, type);
            ps.setLong(3, amount);
            ps.setString(4, fromUuid);
            ps.setString(5, toUuid);
            ps.setString(6, source);
            ps.setString(7, reference);
            ps.setString(8, reason);
            ps.setString(9, actor);

            ps.executeUpdate();

            try (var rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    /**
     * Returns the most recent ledger entries for a player.
     *
     * <p>Entries are returned where the player is either sender or receiver.</p>
     *
     * @param c database connection to use
     * @param uuid player UUID (string form)
     * @param limit max number of rows to return (implementation may clamp)
     * @return list of recent ledger entries, newest first
     * @throws SQLException if database access fails
     */
    public List<LedgerEntry> getRecentForPlayer(Connection c, String uuid, int limit) throws SQLException {
        int n = Math.max(1, Math.min(limit, 50)); // clamp 1..50

        try (PreparedStatement ps = c.prepareStatement("""
            SELECT id, created_at, type, amount, from_uuid, to_uuid, source, reference, reason, actor
            FROM economy_ledger
            WHERE from_uuid = ? OR to_uuid = ?
            ORDER BY id DESC
            LIMIT ?
        """)) {
            ps.setString(1, uuid);
            ps.setString(2, uuid);
            ps.setInt(3, n);

            List<LedgerEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LedgerEntry(
                            rs.getLong("id"),
                            rs.getLong("created_at"),
                            rs.getString("type"),
                            rs.getLong("amount"),
                            rs.getString("from_uuid"),
                            rs.getString("to_uuid"),
                            rs.getString("source"),
                            rs.getString("reference"),
                            rs.getString("reason"),
                            rs.getString("actor")
                    ));
                }
            }
            return out;
        }
    }
}
