package com.krishcpatel.realm.economy.data;

/**
 * A single immutable row from the economy ledger.
 *
 * <p>Ledger entries record the flow of money through the system, enabling audits,
 * debugging, and monitoring.</p>
 *
 * @param id database primary key
 * @param createdAt timestamp in milliseconds since epoch
 * @param type transaction type (TRANSFER, MINT, BURN, SET, etc.)
 * @param amount positive amount of currency moved
 * @param fromUuid sender UUID (nullable depending on type)
 * @param toUuid receiver UUID (nullable depending on type)
 * @param source subsystem that initiated the transaction
 * @param reference optional external reference id (shop id, team id, etc.)
 * @param reason human-readable reason for the transaction
 * @param actor initiator identifier (player UUID, CONSOLE, SYSTEM)
 */
public record LedgerEntry(
        long id,
        long createdAt,
        String type,
        long amount,
        String fromUuid,
        String toUuid,
        String source,
        String reference,
        String reason,
        String actor
) {}