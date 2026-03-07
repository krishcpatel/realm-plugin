package com.krishcpatel.realm.economy.data;

/**
 * Immutable banknote database record.
 *
 * @param noteId unique note id
 * @param amount note value
 * @param issuedAt issue timestamp
 * @param issuedBy issuer identifier
 * @param redeemed whether the note has been redeemed
 * @param redeemedAt redemption timestamp or 0 if not redeemed
 * @param redeemedBy redeemer identifier or null if not redeemed
 */
public record BankNoteRecord(
        String noteId,
        long amount,
        long issuedAt,
        String issuedBy,
        boolean redeemed,
        long redeemedAt,
        String redeemedBy
) {}