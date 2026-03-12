package com.krishcpatel.realm.skills.model;

/**
 * Level progression settings for a skill.
 *
 * @param maxLevel highest reachable level
 * @param requiredXpBase base xp required for next level
 * @param requiredXpGrowth growth factor applied per level
 */
public record SkillProgressionSettings(
        int maxLevel,
        long requiredXpBase,
        double requiredXpGrowth
) {
    /**
     * Returns xp required to advance from the given level to the next level.
     *
     * @param level current level
     * @return required xp
     */
    public long requiredXpForLevel(int level) {
        double raw = requiredXpBase * Math.pow(requiredXpGrowth, Math.max(0, level - 1));
        return Math.max(1L, Math.round(raw));
    }
}
