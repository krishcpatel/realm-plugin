package com.krishcpatel.realm.shop.model;

/**
 * Stored admin-defined shop area, optionally claimed by a player.
 */
public record ShopAreaRecord(
        long id,
        String areaKey,
        String world,
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ,
        long claimFee,
        long upkeepFee,
        String ownerUuid,
        long claimedAt,
        long upkeepNextAt,
        int active
) {
    public boolean isClaimed() {
        return ownerUuid != null && !ownerUuid.isBlank();
    }
}
