package com.krishcpatel.realm.jobs.model;

/**
 * Per-job progression settings loaded from configuration.
 *
 * @param maxLevel maximum reachable level
 * @param requiredXpBase base xp required for the next level
 * @param requiredXpGrowth multiplicative growth per level
 * @param moneyPerLevel bonus added per level
 * @param maxMoneyMultiplier upper bound for money multiplier
 * @param xpPerLevel bonus added per level
 * @param maxXpMultiplier upper bound for xp multiplier
 */
public record LevelingSettings(
        int maxLevel,
        long requiredXpBase,
        double requiredXpGrowth,
        double moneyPerLevel,
        double maxMoneyMultiplier,
        double xpPerLevel,
        double maxXpMultiplier
) {
    /**
     * Returns xp required to advance from the given level to the next.
     *
     * @param level current level
     * @return required xp
     */
    public long requiredXpForLevel(int level) {
        double raw = requiredXpBase * Math.pow(requiredXpGrowth, Math.max(0, level - 1));
        return Math.max(1L, Math.round(raw));
    }

    /**
     * Returns the current level-based payout multiplier.
     *
     * @param level current job level
     * @return money multiplier
     */
    public double moneyMultiplier(int level) {
        double extra = Math.max(0, level - 1) * moneyPerLevel;
        return Math.min(maxMoneyMultiplier, 1.0D + extra);
    }

    /**
     * Returns the current level-based xp multiplier.
     *
     * @param level current job level
     * @return xp multiplier
     */
    public double xpMultiplier(int level) {
        double extra = Math.max(0, level - 1) * xpPerLevel;
        return Math.min(maxXpMultiplier, 1.0D + extra);
    }
}
