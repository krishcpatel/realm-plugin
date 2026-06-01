package com.krishcpatel.realm.economy.data;

/**
 * Result of redeeming one or more bank notes into a player's bank balance.
 *
 * @param success true when redemption completed
 * @param message failure reason or informational status
 * @param totalAmount total currency value redeemed
 */
public record BankNoteRedeemResult(
        boolean success,
        String message,
        long totalAmount
) {
    /**
     * Creates a successful redemption result.
     *
     * @param totalAmount redeemed value
     * @return success result
     */
    public static BankNoteRedeemResult ok(long totalAmount) {
        return new BankNoteRedeemResult(true, "OK", totalAmount);
    }

    /**
     * Creates a failed redemption result.
     *
     * @param message failure reason
     * @return failure result
     */
    public static BankNoteRedeemResult fail(String message) {
        return new BankNoteRedeemResult(false, message, 0L);
    }
}
