package com.krishcpatel.realm.jobs.model;

/**
 * Current job membership and progression for a player.
 *
 * @param jobId job identifier
 * @param level current level
 * @param xp progress toward the next level
 * @param totalXp lifetime xp earned in the job
 * @param joinedAt membership timestamp
 */
public record PlayerJob(
        String jobId,
        int level,
        long xp,
        long totalXp,
        long joinedAt
) {
}
