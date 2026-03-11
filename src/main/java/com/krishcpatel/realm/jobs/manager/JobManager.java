package com.krishcpatel.realm.jobs.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.jobs.model.JobDefinition;
import com.krishcpatel.realm.jobs.model.JobMembershipResult;
import com.krishcpatel.realm.jobs.model.PlayerJob;
import com.krishcpatel.realm.jobs.registry.JobDefinitionRegistry;
import com.krishcpatel.realm.jobs.repository.JobsRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Main API for assigning, removing, and querying player jobs.
 */
public final class JobManager {
    private final Core core;
    private final JobsRepository repo;
    private final JobDefinitionRegistry registry;

    /**
     * Creates the main jobs membership service.
     *
     * @param core plugin instance
     * @param repo jobs persistence layer
     * @param registry loaded job definitions
     */
    public JobManager(Core core, JobsRepository repo, JobDefinitionRegistry registry) {
        this.core = core;
        this.repo = repo;
        this.registry = registry;
    }

    /**
     * Returns the player's progress for a specific job if they currently have it.
     *
     * @param playerUuid player UUID
     * @param jobId requested job id
     * @return matching player job if assigned
     * @throws SQLException if the lookup fails
     */
    public Optional<PlayerJob> getJob(UUID playerUuid, String jobId) throws SQLException {
        return Optional.ofNullable(repo.getJob(playerUuid.toString(), normalizeJobId(jobId)));
    }

    /**
     * Returns all jobs currently assigned to a player.
     *
     * @param playerUuid player UUID
     * @return ordered list of active jobs
     * @throws SQLException if the lookup fails
     */
    public List<PlayerJob> getJobs(UUID playerUuid) throws SQLException {
        return repo.getJobs(playerUuid.toString());
    }

    /**
     * Attempts to join the player to a configured job.
     *
     * @param playerUuid player UUID
     * @param jobId requested job id
     * @return membership result describing success or failure
     * @throws SQLException if persistence fails
     */
    public JobMembershipResult joinJob(UUID playerUuid, String jobId) throws SQLException {
        String normalizedJobId = normalizeJobId(jobId);
        Optional<JobDefinition> definition = registry.get(normalizedJobId);

        if (definition.isEmpty()) {
            return JobMembershipResult.fail("That job does not exist.");
        }

        if (repo.getJob(playerUuid.toString(), normalizedJobId) != null) {
            return JobMembershipResult.fail("You already have that job.");
        }

        int maxJobs = Math.max(1, core.jobsConfig().getInt("settings.max-jobs-per-player", 3));
        if (repo.countJobs(playerUuid.toString()) >= maxJobs) {
            return JobMembershipResult.fail("You have reached the max number of jobs.");
        }

        int startingLevel = Math.max(1, core.jobsConfig().getInt("settings.starting-level", 1));
        boolean inserted = repo.joinJob(playerUuid.toString(), normalizedJobId, System.currentTimeMillis(), startingLevel);
        if (!inserted) {
            return JobMembershipResult.fail("You already have that job.");
        }

        return JobMembershipResult.ok("Joined " + definition.get().displayName() + ".");
    }

    /**
     * Removes a single active job from the player.
     *
     * @param playerUuid player UUID
     * @param jobId requested job id
     * @return membership result describing success or failure
     * @throws SQLException if persistence fails
     */
    public JobMembershipResult leaveJob(UUID playerUuid, String jobId) throws SQLException {
        String normalizedJobId = normalizeJobId(jobId);
        Optional<JobDefinition> definition = registry.get(normalizedJobId);

        boolean removed = repo.leaveJob(playerUuid.toString(), normalizedJobId);
        if (!removed) {
            return JobMembershipResult.fail("You do not currently have that job.");
        }

        String displayName = definition.map(JobDefinition::displayName).orElse(normalizedJobId);
        return JobMembershipResult.ok("Left " + displayName + ".");
    }

    /**
     * Removes all active jobs from the player when no-job mode is enabled.
     *
     * @param playerUuid player UUID
     * @return membership result describing success or failure
     * @throws SQLException if persistence fails
     */
    public JobMembershipResult leaveAllJobs(UUID playerUuid) throws SQLException {
        if (!core.jobsConfig().getBoolean("settings.allow-no-job", true)) {
            return JobMembershipResult.fail("No-job mode is disabled.");
        }

        int removed = repo.leaveAllJobs(playerUuid.toString());
        if (removed == 0) {
            return JobMembershipResult.fail("You do not currently have any jobs.");
        }

        return JobMembershipResult.ok("You now have no active jobs.");
    }

    /**
     * Normalizes user or config job identifiers to the stored format.
     *
     * @param jobId raw job id
     * @return normalized lowercase job id, or an empty string if null
     */
    public static String normalizeJobId(String jobId) {
        return jobId == null ? "" : jobId.trim().toLowerCase(Locale.ROOT);
    }
}
