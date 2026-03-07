package com.krishcpatel.realm.economy.repository;

import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.economy.data.BankNoteRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Repository responsible for persistence of bank notes.
 *
 * <p>This class manages database operations related to issued
 * bank notes, including creation, lookup, and redemption state.</p>
 */
public final class BankNoteRepository {

    private final DatabaseManager db;

    /**
     * Creates a new bank note repository.
     *
     * @param db database manager used to obtain connections
     */
    public BankNoteRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Creates the bank note table if it does not already exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initSchema() throws SQLException {
        try (var st = db.getConnection().createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS bank_notes (
                    note_id TEXT PRIMARY KEY,
                    amount INTEGER NOT NULL,
                    issued_at INTEGER NOT NULL,
                    issued_by TEXT NOT NULL,
                    redeemed INTEGER NOT NULL DEFAULT 0,
                    redeemed_at INTEGER NOT NULL DEFAULT 0,
                    redeemed_by TEXT
                );
            """);
        }
    }

    /**
     * Inserts a newly issued bank note into the database.
     *
     * @param c active database connection
     * @param noteId unique note id
     * @param amount note value
     * @param issuedAt issue timestamp in epoch milliseconds
     * @param issuedBy issuer identifier
     * @throws SQLException if database access fails
     */
    public void insertNote(
            Connection c,
            String noteId,
            long amount,
            long issuedAt,
            String issuedBy
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bank_notes
                    (note_id, amount, issued_at, issued_by, redeemed, redeemed_at, redeemed_by)
                VALUES (?, ?, ?, ?, 0, 0, NULL)
        """)) {
            ps.setString(1, noteId);
            ps.setLong(2, amount);
            ps.setLong(3, issuedAt);
            ps.setString(4, issuedBy);
            ps.executeUpdate();
        }
    }

    /**
     * Finds a bank note by id.
     *
     * @param c active database connection
     * @param noteId note id
     * @return matching bank note record, or null if not found
     * @throws SQLException if database access fails
     */
    public BankNoteRecord findById(Connection c, String noteId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT note_id, amount, issued_at, issued_by, redeemed, redeemed_at, redeemed_by
                FROM bank_notes
                WHERE note_id = ?
        """)) {
            ps.setString(1, noteId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                return new BankNoteRecord(
                        rs.getString("note_id"),
                        rs.getLong("amount"),
                        rs.getLong("issued_at"),
                        rs.getString("issued_by"),
                        rs.getInt("redeemed") == 1,
                        rs.getLong("redeemed_at"),
                        rs.getString("redeemed_by")
                );
            }
        }
    }

    /**
     * Marks a bank note as redeemed if it has not already been redeemed.
     *
     * @param c active database connection
     * @param noteId note id
     * @param redeemedAt redemption timestamp in epoch milliseconds
     * @param redeemedBy redeemer identifier
     * @return true if the note was successfully marked redeemed
     * @throws SQLException if database access fails
     */
    public boolean markRedeemed(
            Connection c,
            String noteId,
            long redeemedAt,
            String redeemedBy
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE bank_notes
                SET redeemed = 1,
                    redeemed_at = ?,
                    redeemed_by = ?
                WHERE note_id = ?
                  AND redeemed = 0
        """)) {
            ps.setLong(1, redeemedAt);
            ps.setString(2, redeemedBy);
            ps.setString(3, noteId);
            return ps.executeUpdate() > 0;
        }
    }
}