package com.krishcpatel.realm.skills.model;

import java.util.UUID;

/**
 * Normalized skill action emitted from Bukkit listeners.
 *
 * @param playerUuid player UUID
 * @param playerName player name at time of action
 * @param skillId normalized skill id
 * @param actionKey action key used for xp lookup
 * @param amount amount multiplier for the action
 */
public record SkillActionContext(
        UUID playerUuid,
        String playerName,
        String skillId,
        String actionKey,
        int amount
) {
}
