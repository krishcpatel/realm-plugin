package com.krishcpatel.realm.skills.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable definition for a configured skill.
 *
 * @param id normalized skill id
 * @param displayName player-facing name
 * @param category skill category
 * @param progression leveling settings
 * @param actionXp xp awarded per action key
 */
public record SkillDefinition(
        String id,
        String displayName,
        SkillCategory category,
        SkillProgressionSettings progression,
        Map<String, Long> actionXp
) {
    /**
     * Creates an immutable skill definition with normalized action keys.
     */
    public SkillDefinition {
        Map<String, Long> normalized = new LinkedHashMap<>();
        actionXp.forEach((key, value) -> normalized.put(normalizeActionKey(key), Math.max(0L, value)));
        actionXp = Map.copyOf(normalized);
    }

    /**
     * Returns configured xp for an action key.
     *
     * @param actionKey normalized or raw action key
     * @return xp awarded for the action, or 0 when unconfigured
     */
    public long xpForAction(String actionKey) {
        return actionXp.getOrDefault(normalizeActionKey(actionKey), 0L);
    }

    /**
     * Normalizes action keys to a case-insensitive storage format.
     *
     * @param raw raw action key
     * @return normalized action key
     */
    public static String normalizeActionKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim()
                .replace(' ', '-')
                .replace('_', '-')
                .toLowerCase(Locale.ROOT);
    }
}
