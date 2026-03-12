package com.krishcpatel.realm.skills.model;

/**
 * Stored player progress for a single skill.
 *
 * @param skillId skill identifier
 * @param level current level
 * @param xp current progress toward next level
 * @param totalXp lifetime xp earned in the skill
 * @param updatedAt last update timestamp
 */
public record SkillProgress(
        String skillId,
        int level,
        long xp,
        long totalXp,
        long updatedAt
) {
}
