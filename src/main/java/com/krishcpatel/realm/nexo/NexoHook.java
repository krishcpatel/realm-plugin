package com.krishcpatel.realm.nexo;

import com.krishcpatel.realm.core.Core;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

/**
 * Thin wrapper around the Nexo API used by Realm features.
 */
public final class NexoHook implements Listener {
    public static final String TEST_ITEM_ID = "sword_of_the_realm";

    private final Core core;
    private volatile boolean itemsLoaded;

    /**
     * Creates the Nexo hook.
     *
     * @param core plugin instance
     */
    public NexoHook(Core core) {
        this.core = core;
    }

    /**
     * Tracks when Nexo has completed its async item load/reload.
     *
     * @param event Nexo item load event
     */
    @EventHandler
    public void onNexoItemsLoaded(NexoItemsLoadedEvent event) {
        itemsLoaded = true;
        core.getLogger().info("[nexo] items loaded. "
                + TEST_ITEM_ID + " registered=" + itemExists(TEST_ITEM_ID));
    }

    /**
     * Returns whether Nexo has fired its item-loaded event during this server session.
     *
     * @return true once item loading has completed
     */
    public boolean itemsLoaded() {
        return itemsLoaded;
    }

    /**
     * Checks whether a Nexo item id is registered.
     *
     * @param itemId Nexo item id
     * @return true when Nexo knows the item
     */
    public boolean itemExists(String itemId) {
        String normalized = normalizeItemId(itemId);
        if (normalized.isEmpty()) {
            return false;
        }
        try {
            return NexoItems.exists(normalized);
        } catch (RuntimeException ex) {
            core.getLogger().warning("[nexo] Failed to check item " + normalized + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Builds a Nexo item stack.
     *
     * @param itemId Nexo item id
     * @param amount stack amount
     * @return item stack, or null when unknown
     */
    public ItemStack buildItem(String itemId, int amount) {
        String normalized = normalizeItemId(itemId);
        if (normalized.isEmpty() || !itemExists(normalized)) {
            return null;
        }

        ItemBuilder builder = NexoItems.itemFromId(normalized);
        if (builder == null) {
            return null;
        }

        ItemStack item = builder.build();
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }

    /**
     * Gives a Nexo item to a player, dropping overflow at their location.
     *
     * @param player target player
     * @param itemId Nexo item id
     * @param amount amount to give
     * @return true when the item was built and offered to the inventory
     */
    public boolean giveItem(Player player, String itemId, int amount) {
        ItemStack item = buildItem(itemId, amount);
        if (item == null) {
            return false;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return true;
    }

    /**
     * Reads the Nexo id from an item stack.
     *
     * @param item stack to inspect
     * @return normalized Nexo item id, or empty string for non-Nexo items
     */
    public String idFromItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        try {
            String itemId = NexoItems.idFromItem(item);
            return normalizeItemId(itemId);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /**
     * Normalizes Nexo ids for config and API use.
     *
     * @param itemId raw item id
     * @return normalized item id
     */
    public static String normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        return itemId.trim().toLowerCase(Locale.ROOT);
    }
}
