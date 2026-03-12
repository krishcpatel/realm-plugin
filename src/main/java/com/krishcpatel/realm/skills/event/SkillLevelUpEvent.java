package com.krishcpatel.realm.skills.event;

import com.krishcpatel.realm.core.event.RealmEvent;

import java.util.UUID;

/**
 * Event published when a player levels up in a skill.
 */
public final class SkillLevelUpEvent implements RealmEvent {
    private final long createdAt = System.currentTimeMillis();
    private final UUID playerUuid;
    private final String playerName;
    private final String skillId;
    private final int oldLevel;
    private final int newLevel;

    /**
     * Creates a skill level-up event.
     *
     * @param playerUuid player UUID
     * @param playerName player name at event time
     * @param skillId skill identifier
     * @param oldLevel previous level
     * @param newLevel new level
     */
    public SkillLevelUpEvent(UUID playerUuid, String playerName, String skillId, int oldLevel, int newLevel) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.skillId = skillId;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    /** {@inheritDoc} */
    @Override
    public long createdAt() {
        return createdAt;
    }

    /**
     * Returns the UUID of the player who leveled up.
     *
     * @return player UUID
     */
    public UUID playerUuid() {
        return playerUuid;
    }

    /**
     * Returns the player name at the time of level-up.
     *
     * @return player name
     */
    public String playerName() {
        return playerName;
    }

    /**
     * Returns the skill id that leveled up.
     *
     * @return skill id
     */
    public String skillId() {
        return skillId;
    }

    /**
     * Returns the previous skill level.
     *
     * @return previous level
     */
    public int oldLevel() {
        return oldLevel;
    }

    /**
     * Returns the new skill level.
     *
     * @return new level
     */
    public int newLevel() {
        return newLevel;
    }
}
