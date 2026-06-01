package com.krishcpatel.realm.shop.model;

/**
 * Stored listing row for an item sold from a configured chest slot.
 */
public record ShopListingRecord(
        long id,
        long chestId,
        int slotIndex,
        String material,
        int unitAmount,
        long price
) {
}
