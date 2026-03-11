package com.krishcpatel.realm.jobs.model;

import java.util.Set;
import java.util.UUID;

/**
 * A normalized player action emitted from Bukkit listeners.
 *
 * @param playerUuid player UUID
 * @param playerName player name at time of action
 * @param type normalized action type
 * @param target primary target key (material, entity, item, or ANY)
 * @param groups secondary group selectors such as {@code #LOGS} or {@code #ORES}
 * @param amount action amount used to scale rewards
 * @param worldName world the action happened in
 * @param chunkX chunk x for exploration actions
 * @param chunkZ chunk z for exploration actions
 */
public record JobActionContext(
        UUID playerUuid,
        String playerName,
        JobActionType type,
        String target,
        Set<String> groups,
        int amount,
        String worldName,
        int chunkX,
        int chunkZ
) {
}
