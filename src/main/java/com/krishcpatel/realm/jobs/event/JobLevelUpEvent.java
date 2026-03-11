package com.krishcpatel.realm.jobs.event;

import com.krishcpatel.realm.core.event.RealmEvent;

import java.util.UUID;

/**
 * Event published when a player levels up in a job.
 */
public final class JobLevelUpEvent implements RealmEvent {
    private final long createdAt = System.currentTimeMillis();
    private final UUID playerUuid;
    private final String playerName;
    private final String jobId;
    private final int oldLevel;
    private final int newLevel;

    /**
     * Creates a job level-up event.
     *
     * @param playerUuid player UUID
     * @param playerName player name at time of level-up
     * @param jobId job identifier
     * @param oldLevel previous level
     * @param newLevel new level
     */
    public JobLevelUpEvent(UUID playerUuid, String playerName, String jobId, int oldLevel, int newLevel) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.jobId = jobId;
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
     * Returns the player name at the time of the level-up.
     *
     * @return player name
     */
    public String playerName() {
        return playerName;
    }

    /**
     * Returns the job in which the player leveled up.
     *
     * @return job identifier
     */
    public String jobId() {
        return jobId;
    }

    /**
     * Returns the previous job level.
     *
     * @return old level
     */
    public int oldLevel() {
        return oldLevel;
    }

    /**
     * Returns the new job level.
     *
     * @return new level
     */
    public int newLevel() {
        return newLevel;
    }
}
