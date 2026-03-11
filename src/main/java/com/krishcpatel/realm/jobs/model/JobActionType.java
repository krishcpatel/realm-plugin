package com.krishcpatel.realm.jobs.model;

import java.util.Locale;

/**
 * Normalized action types that can award job progress or payouts.
 */
public enum JobActionType {
    /** Breaking a block. */
    BREAK,
    /** Placing a block. */
    PLACE,
    /** Killing an entity. */
    KILL,
    /** Crafting an item. */
    CRAFT,
    /** Catching fish or fishing loot. */
    FISH,
    /** Completing a brewing action. */
    BREW,
    /** Enchanting an item. */
    ENCHANT,
    /** Discovering a new chunk or area. */
    EXPLORE;

    /**
     * Resolves an action type from config-friendly names such as {@code break} or {@code block-break}.
     *
     * @param raw raw config key
     * @return matching action type, or null if unsupported
     */
    public static JobActionType fromConfigKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        try {
            return JobActionType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
