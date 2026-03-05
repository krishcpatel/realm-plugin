package com.krishcpatel.realm.economy;

import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Economy business logic layer.
 *
 * <p>Contains rules for deposits/withdrawals/transfers and delegates
 * persistence to {@link EconomyRepository}.</p>
 */
public class EconomyManager {
    private final EconomyRepository repo;

    /**
     * Creates an economy manager using the given repository.
     *
     * @param repo economy repository
     */
    public EconomyManager(EconomyRepository repo) {
        this.repo = repo;
    }

    /**
     * Returns the player's current balance.
     *
     * @param uuid player UUID
     * @return balance (integer currency units)
     * @throws SQLException if query fails
     */
    public long getBalance(String uuid) throws SQLException {
        repo.ensureAccount(uuid);
        return repo.getBalance(uuid);
    }

    /**
     * Deposits currency into the player's balance.
     *
     * @param uuid player UUID
     * @param amount amount to deposit (must be positive)
     * @throws SQLException if update fails
     */
    public void deposit(String uuid, long amount) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be > 0");
        repo.ensureAccount(uuid);
        repo.addBalance(uuid, amount);
    }

    /**
     * Attempts to withdraw currency from the player's balance.
     *
     * @param uuid player UUID
     * @param amount amount to withdraw (must be positive)
     * @return true if successful, false if insufficient funds
     * @throws SQLException if query/update fails
     */
    public boolean withdraw(String uuid, long amount) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be > 0");
        repo.ensureAccount(uuid);
        return repo.subtractBalanceFloorZero(uuid, amount);
    }

    /**
     * Sets a player's balance to an exact value.
     *
     * <p>This method clamps the balance to a minimum of 0.</p>
     *
     * @param uuid player UUID (string form)
     * @param amount new balance value (negative values will be treated as 0)
     * @throws SQLException if database access fails
     */
    public void set(String uuid, long amount) throws SQLException {
        long clamped = Math.max(0, amount);
        repo.ensureAccount(uuid);
        repo.setBalance(uuid, clamped);
    }
}
