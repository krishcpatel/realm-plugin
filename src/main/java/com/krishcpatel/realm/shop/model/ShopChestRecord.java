package com.krishcpatel.realm.shop.model;

/**
 * Stored mapped shop chest location inside an owned area.
 */
public record ShopChestRecord(
        long id,
        long areaId,
        String world,
        int x,
        int y,
        int z,
        String ownerUuid
) {
}
