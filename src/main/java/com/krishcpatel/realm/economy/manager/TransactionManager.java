package com.krishcpatel.realm.economy.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.data.TransactionResult;
import com.krishcpatel.realm.economy.event.LedgerRecordedEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Central gateway for all money movement in the server economy.
 *
 * <p>All balance mutations (player payments, shop purchases, job rewards, upkeep fees,
 * admin adjustments) must go through this class to ensure:</p>
 * <ul>
 *   <li>Atomic balance changes</li>
 *   <li>Append-only ledger recording</li>
 *   <li>Consistent monitoring hooks via events</li>
 * </ul>
 */
public class TransactionManager {
    private final Core core;
    private final DatabaseManager db;
    private final EconomyRepository economy;
    private final LedgerRepository ledger;

    /**
     * Creates a transaction manager.
     *
     * @param core plugin instance used for publishing events and scheduling work
     * @param db database manager
     * @param economy repository for account balances
     * @param ledger repository for ledger persistence
     */
    public TransactionManager(Core core, DatabaseManager db, EconomyRepository economy, LedgerRepository ledger) {
        this.core = core;
        this.db = db;
        this.economy = economy;
        this.ledger = ledger;
    }

    /**
     * Transfers currency from one player account to another.
     *
     * <p>This operation is atomic and will fail if the sender does not have enough funds.
     * A successful transfer is recorded in the ledger and may publish a ledger event.</p>
     *
     * @param fromUuid sender UUID (string form)
     * @param toUuid receiver UUID (string form)
     * @param amount positive amount to transfer
     * @param source subsystem source category
     * @param reference optional external reference id
     * @param reason optional human-readable reason
     * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
     * @return transaction result including success and ledger id
     * @throws SQLException if database access fails
     */
    public TransactionResult transfer(
            String fromUuid,
            String toUuid,
            long amount,
            MoneySource source,
            String reference,
            String reason,
            String actor
    ) throws SQLException {

        if (amount <= 0) return TransactionResult.fail("Amount must be > 0");
        if (fromUuid.equalsIgnoreCase(toUuid)) return TransactionResult.fail("Cannot pay yourself");

        Connection c = db.getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            economy.ensureAccount(fromUuid);
            economy.ensureAccount(toUuid);

            // Atomic withdraw (no negatives)
            boolean ok = subtractIfEnough(c, fromUuid, amount);
            if (!ok) {
                c.rollback();
                return TransactionResult.fail("Insufficient funds");
            }

            add(c, toUuid, amount);

            long ledgerId = ledger.insertLedgerRow(
                    c,
                    System.currentTimeMillis(),
                    "TRANSFER",
                    amount,
                    fromUuid,
                    toUuid,
                    source.name(),
                    reference,
                    reason,
                    actor
            );

            c.commit();

            // publish AFTER commit so listeners never see “phantom” ledger rows
            core.events().publishAsync(new LedgerRecordedEvent(
                    ledgerId,
                    "TRANSFER",
                    amount,
                    fromUuid,
                    toUuid,
                    source,
                    reference,
                    reason,
                    actor
            ));

            return TransactionResult.ok(ledgerId);

        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    /**
     * Mints currency into a player's account (creates money).
     *
     * <p>Used for job rewards, admin gives, or controlled system rewards.
     * A successful mint is recorded in the ledger and may publish a ledger event.</p>
     *
     * @param toUuid receiver UUID (string form)
     * @param amount positive amount to add
     * @param source subsystem source category
     * @param reference optional external reference id
     * @param reason optional human-readable reason
     * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
     * @return transaction result including success and ledger id
     * @throws SQLException if database access fails
     */
    public TransactionResult mint(
            String toUuid,
            long amount,
            MoneySource source,
            String reference,
            String reason,
            String actor
    ) throws SQLException {

        if (amount <= 0) return TransactionResult.fail("Amount must be > 0");

        Connection c = db.getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            economy.ensureAccount(toUuid);

            add(c, toUuid, amount);

            long ledgerId = ledger.insertLedgerRow(
                    c,
                    System.currentTimeMillis(),
                    "MINT",
                    amount,
                    null,
                    toUuid,
                    source.name(),
                    reference,
                    reason,
                    actor
            );

            c.commit();

            // publish AFTER commit so listeners never see “phantom” ledger rows
            String fromUuid = "";
            core.events().publishAsync(new LedgerRecordedEvent(
                    ledgerId,
                    "MINT",
                    amount,
                    fromUuid,
                    toUuid,
                    source,
                    reference,
                    reason,
                    actor
            ));

            return TransactionResult.ok(ledgerId);

        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    /**
     * Burns currency from a player's account (removes money).
     *
     * <p>Used for upkeep fees, admin takes, or controlled sinks.
     * The burn fails if the account has insufficient funds.</p>
     *
     * @param fromUuid sender UUID (string form)
     * @param amount positive amount to remove
     * @param source subsystem source category
     * @param reference optional external reference id
     * @param reason optional human-readable reason
     * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
     * @return transaction result including success and ledger id
     * @throws SQLException if database access fails
     */
    public TransactionResult burn(
            String fromUuid,
            long amount,
            MoneySource source,
            String reference,
            String reason,
            String actor
    ) throws SQLException {

        if (amount <= 0) return TransactionResult.fail("Amount must be > 0");

        Connection c = db.getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            economy.ensureAccount(fromUuid);

            boolean ok = subtractIfEnough(c, fromUuid, amount);
            if (!ok) {
                c.rollback();
                return TransactionResult.fail("Insufficient funds");
            }

            long ledgerId = ledger.insertLedgerRow(
                    c,
                    System.currentTimeMillis(),
                    "BURN",
                    amount,
                    fromUuid,
                    null,
                    source.name(),
                    reference,
                    reason,
                    actor
            );

            c.commit();

            // publish AFTER commit so listeners never see “phantom” ledger rows
            String toUuid = "";
            core.events().publishAsync(new LedgerRecordedEvent(
                    ledgerId,
                    "BURN",
                    amount,
                    fromUuid,
                    toUuid,
                    source,
                    reference,
                    reason,
                    actor
            ));

            return TransactionResult.ok(ledgerId);

        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    /**
     * Sets a player's balance to an exact value.
     *
     * <p>This is primarily intended for admin corrections. Implementations typically clamp
     * the value to a minimum of 0 and record an entry in the ledger.</p>
     *
     * @param targetUuid player UUID (string form)
     * @param newBalance new balance value
     * @param source subsystem source category
     * @param reference optional external reference id
     * @param reason optional human-readable reason
     * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
     * @return transaction result including success and ledger id
     * @throws SQLException if database access fails
     */
    public TransactionResult setBalance(
            String targetUuid,
            long newBalance,
            MoneySource source,
            String reference,
            String reason,
            String actor
    ) throws SQLException {

        long clamped = Math.max(0, newBalance);

        Connection c = db.getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            economy.ensureAccount(targetUuid);

            long old = economy.getBalance(targetUuid);
            economy.setBalance(targetUuid, clamped);

            long delta = Math.abs(clamped - old);

            long ledgerId = ledger.insertLedgerRow(
                    c,
                    System.currentTimeMillis(),
                    "SET",
                    delta,
                    old > clamped ? targetUuid : null,
                    old > clamped ? null : targetUuid,
                    source.name(),
                    reference,
                    reason + " (old=" + old + ", new=" + clamped + ")",
                    actor
            );

            c.commit();

            // publish AFTER commit so listeners never see “phantom” ledger rows
            String fromUuid = "";
            core.events().publishAsync(new LedgerRecordedEvent(
                    ledgerId,
                    "SET",
                    newBalance,
                    fromUuid,
                    targetUuid,
                    source,
                    reference,
                    reason,
                    actor
            ));

            return TransactionResult.ok(ledgerId);
        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    // --- internal helpers that operate using the SAME connection ---

    private boolean subtractIfEnough(Connection c, String uuid, long amount) throws SQLException {
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

    private void add(Connection c, String uuid, long amount) throws SQLException {
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
}

