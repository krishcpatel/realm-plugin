package com.krishcpatel.realm.clans.model;

/**
 * Stored clan flag claim location.
 */
public record ClanClaimRecord(
        long id,
        long clanId,
        String world,
        int x,
        int y,
        int z,
        String placedBy,
        long placedAt
) {
}
