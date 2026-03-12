package com.krishcpatel.realm.jobs.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable definition of a job loaded from configuration.
 */
public final class JobDefinition {
    private final String id;
    private final String displayName;
    private final String description;
    private final LevelingSettings leveling;
    private final long dailyMoneyCap;
    private final long dailyXpCap;
    private final Map<JobActionType, List<RewardRule>> rewards;

    /**
     * Creates an immutable job definition.
     *
     * @param id internal job identifier
     * @param displayName player-facing display name
     * @param description short description shown in listings
     * @param leveling progression settings for the job
     * @param dailyMoneyCap max money this job can pay per day, {@code 0} for unlimited
     * @param dailyXpCap max xp this job can grant per day, {@code 0} for unlimited
     * @param rewards reward rules keyed by action type
     */
    public JobDefinition(
            String id,
            String displayName,
            String description,
            LevelingSettings leveling,
            long dailyMoneyCap,
            long dailyXpCap,
            Map<JobActionType, List<RewardRule>> rewards
    ) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.leveling = leveling;
        this.dailyMoneyCap = Math.max(0L, dailyMoneyCap);
        this.dailyXpCap = Math.max(0L, dailyXpCap);
        EnumMap<JobActionType, List<RewardRule>> copy = new EnumMap<>(JobActionType.class);
        rewards.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        this.rewards = Map.copyOf(copy);
    }

    /**
     * Returns the internal job identifier.
     *
     * @return job identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the player-facing display name.
     *
     * @return display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the short description for this job.
     *
     * @return job description
     */
    public String description() {
        return description;
    }

    /**
     * Returns the progression settings for this job.
     *
     * @return leveling configuration
     */
    public LevelingSettings leveling() {
        return leveling;
    }

    /**
     * Returns the max money this job can pay in a UTC day.
     *
     * @return daily money cap, or {@code 0} for unlimited
     */
    public long dailyMoneyCap() {
        return dailyMoneyCap;
    }

    /**
     * Returns the max xp this job can grant in a UTC day.
     *
     * @return daily xp cap, or {@code 0} for unlimited
     */
    public long dailyXpCap() {
        return dailyXpCap;
    }

    /**
     * Returns the configured reward rules grouped by action type.
     *
     * @return immutable reward map
     */
    public Map<JobActionType, List<RewardRule>> rewards() {
        return rewards;
    }

    /**
     * Resolves the best reward rule for a normalized action.
     *
     * <p>Exact target matches beat group matches, which beat {@code ANY}.</p>
     *
     * @param action player action
     * @return best matching reward rule if present
     */
    public Optional<RewardRule> resolveRule(JobActionContext action) {
        RewardRule best = null;
        int bestScore = -1;

        for (RewardRule rule : rewards.getOrDefault(action.type(), List.of())) {
            int score = rule.matchScore(action);
            if (score > bestScore) {
                best = rule;
                bestScore = score;
            }
        }

        return bestScore >= 0 ? Optional.of(best) : Optional.empty();
    }
}
