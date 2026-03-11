package com.krishcpatel.realm.jobs.model;

/**
 * Daily earned totals used to clamp job rewards.
 *
 * @param moneyEarned money already earned for the day/rule
 * @param xpEarned xp already earned for the day/rule
 */
public record JobCapState(long moneyEarned, long xpEarned) {
    /** Shared zero-value cap state. */
    public static final JobCapState EMPTY = new JobCapState(0L, 0L);
}
