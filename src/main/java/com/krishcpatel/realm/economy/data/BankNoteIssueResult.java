package com.krishcpatel.realm.economy.data;

/**
 * Result of issuing a new physical bank note.
 *
 * @param success true if issuance completed
 * @param message failure reason or informational message
 * @param ledgerId id of the ledger row if successful, otherwise -1
 * @param noteId unique note id if successful, otherwise null
 * @param amount note value
 */
public record BankNoteIssueResult(
        boolean success,
        String message,
        long ledgerId,
        String noteId,
        long amount
) {
    /**
     * Creates a successful note issuance result.
     *
     * @param ledgerId inserted ledger row id
     * @param noteId created note id
     * @param amount note value
     * @return success result
     */
    public static BankNoteIssueResult ok(long ledgerId, String noteId, long amount) {
        return new BankNoteIssueResult(true, "OK", ledgerId, noteId, amount);
    }

    /**
     * Creates a failed note issuance result.
     *
     * @param message failure reason
     * @return failure result
     */
    public static BankNoteIssueResult fail(String message) {
        return new BankNoteIssueResult(false, message, -1L, null, 0L);
    }
}
