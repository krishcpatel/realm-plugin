package com.krishcpatel.realm.jobs.registry;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.jobs.model.JobDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of configured jobs.
 */
public final class JobDefinitionRegistry {
    private final Core core;
    private final JobDefinitionLoader loader;
    private Map<String, JobDefinition> definitions = Map.of();

    /**
     * Creates a new registry backed by the plugin jobs configuration.
     *
     * @param core plugin instance
     */
    public JobDefinitionRegistry(Core core) {
        this.core = core;
        this.loader = new JobDefinitionLoader(core);
    }

    /**
     * Reloads definitions from {@code jobs.yml}.
     */
    public void reload() {
        definitions = loader.load(core.jobsConfig());
    }

    /**
     * Returns the configured definition for a job id.
     *
     * @param jobId normalized job identifier
     * @return job definition if configured
     */
    public Optional<JobDefinition> get(String jobId) {
        return Optional.ofNullable(definitions.get(jobId));
    }

    /**
     * Returns all configured job definitions.
     *
     * @return immutable collection of definitions
     */
    public Collection<JobDefinition> all() {
        return definitions.values();
    }

    /**
     * Returns whether the registry currently has no configured jobs.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return definitions.isEmpty();
    }
}
