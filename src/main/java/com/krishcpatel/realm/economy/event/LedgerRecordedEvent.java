package com.krishcpatel.realm.economy.event;

import com.krishcpatel.realm.core.event.RealmEvent;
import com.krishcpatel.realm.economy.model.MoneySource;

/**
 * Event published after a ledger entry has been committed to the database.
 *
 * <p>This allows modules to react to money movement (monitoring, alerts,
 * analytics, anti-bug checks) without directly coupling to the transaction system.</p>
 */
public final class LedgerRecordedEvent implements RealmEvent {

    private final long createdAt = System.currentTimeMillis();

    private final long ledgerId;
    private final String type;     // TRANSFER, MINT, BURN, SET
    private final long amount;     // always positive
    private final String fromUuid; // nullable
    private final String toUuid;   // nullable
    private final MoneySource source;
    private final String reference; // nullable
    private final String reason;    // nullable
    private final String actor;     // player uuid, CONSOLE, SYSTEM

    /**
     * Creates a new ledger-recorded event.
     *
     * @param ledgerId id of the committed ledger row
     * @param type transaction type (TRANSFER, MINT, BURN, SET, etc.)
     * @param amount positive amount moved
     * @param fromUuid sender UUID (nullable depending on type)
     * @param toUuid receiver UUID (nullable depending on type)
     * @param source subsystem source category
     * @param reference optional external reference id
     * @param reason optional human-readable reason
     * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
     */
    public LedgerRecordedEvent(
            long ledgerId,
            String type,
            long amount,
            String fromUuid,
            String toUuid,
            MoneySource source,
            String reference,
            String reason,
            String actor
    ) {
        this.ledgerId = ledgerId;
        this.type = type;
        this.amount = amount;
        this.fromUuid = fromUuid;
        this.toUuid = toUuid;
        this.source = source;
        this.reference = reference;
        this.reason = reason;
        this.actor = actor;
    }

    @Override public long createdAt() { return createdAt; }

    /**
     * Returns the database id of the committed ledger row.
     *
     * @return ledger row id
     */
    public long ledgerId() { return ledgerId; }

    /**
     * Returns the transaction type (TRANSFER, MINT, BURN, SET).
     *
     * @return ledger entry type
     */
    public String type() { return type; }

    /**
     * Returns the positive amount moved in this transaction.
     *
     * @return amount moved
     */
    public long amount() { return amount; }

    /**
     * Returns the sender UUID for a transfer/burn, or {@code null} for mint.
     *
     * @return sender UUID or null
     */
    public String fromUuid() { return fromUuid; }

    /**
     * Returns the receiver UUID for a transfer/mint, or {@code null} for burn.
     *
     * @return receiver UUID or null
     */
    public String toUuid() { return toUuid; }

    /**
     * Returns the subsystem that initiated the transaction.
     *
     * @return money source category
     */
    public MoneySource source() { return source; }

    /**
     * Returns an optional external reference id (shop id, team id, plot id, etc.).
     *
     * @return reference id or null
     */
    public String reference() { return reference; }

    /**
     * Returns an optional human-readable reason for the transaction.
     *
     * @return reason text or null
     */
    public String reason() { return reason; }

    /**
     * Returns the actor that initiated the transaction (player UUID, CONSOLE, SYSTEM).
     *
     * @return actor identifier
     */
    public String actor() { return actor; }

}