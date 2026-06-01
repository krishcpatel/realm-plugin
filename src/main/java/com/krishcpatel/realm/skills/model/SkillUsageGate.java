package com.krishcpatel.realm.skills.model;

/**
 * Skill requirement for using a configured Nexo item.
 *
 * @param itemId Nexo item id
 * @param skillId normalized skill id
 * @param minLevel minimum required skill level
 */
public record SkillUsageGate(String itemId, String skillId, int minLevel) {
}
