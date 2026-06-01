package com.krishcpatel.realm.clans.model;

/**
 * Stored protected storage location owned by a clan.
 */
public record ClanProtectedStorageRecord(
        long id,
        long clanId,
        String world,
        int x,
        int y,
        int z,
        String createdBy,
        long createdAt
) {
}
