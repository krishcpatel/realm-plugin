package com.krishcpatel.realm.skills.model;

import java.util.Locale;

/**
 * Top-level grouping for skill presentation and filtering.
 */
public enum SkillCategory {
    /** Combat-oriented skills. */
    COMBAT,
    /** Gathering/resource skills. */
    GATHER,
    /** Utility/support skills. */
    OTHER;

    /**
     * Parses a config value into a category.
     *
     * @param raw raw config value
     * @return matching category, or null if unsupported
     */
    public static SkillCategory fromConfigKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        if ("GATHERS".equals(normalized)) {
            normalized = "GATHER";
        }

        try {
            return SkillCategory.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Returns a friendly display name for chat output.
     *
     * @return display label
     */
    public String displayName() {
        return switch (this) {
            case COMBAT -> "Combat";
            case GATHER -> "Gather";
            case OTHER -> "Other";
        };
    }
}
