package com.krishcpatel.realm.economy.model;

/**
 * Identifies the subsystem that initiated a money movement.
 *
 * <p>Used for consistent categorization in the economy ledger and monitoring.</p>
 */
public enum MoneySource {
    /** Admin-created changes such as /eco give, /eco take, /eco set. */
    ADMIN,
    /** Player-to-player payments such as /pay. */
    PAY,
    /** Rewards for jobs/professions activity. */
    JOBS,
    /** Player shops/marketplace purchases. */
    SHOP,
    /** Recurring upkeep fees (teams, plots, etc.). */
    UPKEEP,
    /** Plot/land related transactions. */
    PLOT,
    /** Server/system initiated transactions. */
    SYSTEM
}