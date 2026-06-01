package com.krishcpatel.realm.skills.model;

/**
 * Configured Nexo item reward for a skill level.
 *
 * @param itemId Nexo item id
 * @param amount amount to give
 */
public record SkillItemReward(String itemId, int amount) {
}
