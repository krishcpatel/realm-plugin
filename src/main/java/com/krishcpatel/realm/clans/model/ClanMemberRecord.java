package com.krishcpatel.realm.clans.model;

/**
 * Stored clan membership row.
 */
public record ClanMemberRecord(
        long clanId,
        String playerUuid,
        String role,
        long joinedAt,
        long lastFeePaidAt
) {
}
