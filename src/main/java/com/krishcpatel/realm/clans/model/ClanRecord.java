package com.krishcpatel.realm.clans.model;

/**
 * Stored clan metadata.
 */
public record ClanRecord(
        long id,
        String tag,
        String name,
        String leaderUuid,
        long bankBalance,
        int level,
        int memberCap,
        int protectedStorageCap,
        String feeType,
        long feeAmount,
        long upkeepNextAt,
        String flagSignature,
        long createdAt,
        long updatedAt
) {
}
