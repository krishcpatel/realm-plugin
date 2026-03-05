package com.krishcpatel.realm.economy;

/**
 * Result of a transaction performed by {@link TransactionManager}.
 *
 * @param success true if the transaction completed successfully
 * @param message failure reason or informational message
 * @param ledgerId id of the ledger row if successful, otherwise -1
 */
public record TransactionResult(boolean success, String message, long ledgerId) {

    /**
     * Creates a successful result.
     *
     * @param ledgerId inserted ledger row id
     * @return success result
     */
    public static TransactionResult ok(long ledgerId) {
        return new TransactionResult(true, "OK", ledgerId);
    }

    /**
     * Creates a failure result.
     *
     * @param message failure reason
     * @return failure result
     */
    public static TransactionResult fail(String message) {
        return new TransactionResult(false, message, -1L);
    }
}