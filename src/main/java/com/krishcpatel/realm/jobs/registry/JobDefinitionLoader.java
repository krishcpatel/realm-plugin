package com.krishcpatel.realm.jobs.registry;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.jobs.manager.JobManager;
import com.krishcpatel.realm.jobs.model.JobActionType;
import com.krishcpatel.realm.jobs.model.JobDefinition;
import com.krishcpatel.realm.jobs.model.LevelingSettings;
import com.krishcpatel.realm.jobs.model.RewardRule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads job definitions from {@code jobs.yml}.
 */
public final class JobDefinitionLoader {
    private final Core core;

    /**
     * Creates a loader that reads job definitions from plugin config.
     *
     * @param core plugin instance
     */
    public JobDefinitionLoader(Core core) {
        this.core = core;
    }

    /**
     * Loads all enabled jobs from {@code jobs.yml}.
     *
     * @param jobsConfig loaded jobs configuration
     * @return immutable map of job id to definition
     */
    public Map<String, JobDefinition> load(FileConfiguration jobsConfig) {
        ConfigurationSection jobsSection = jobsConfig.getConfigurationSection("jobs");
        if (jobsSection == null) {
            core.getLogger().warning("[jobs] No jobs section found in jobs.yml.");
            return Map.of();
        }

        ConfigurationSection settings = jobsConfig.getConfigurationSection("settings.progression");
        Map<String, JobDefinition> out = new LinkedHashMap<>();

        for (String rawJobId : jobsSection.getKeys(false)) {
            ConfigurationSection jobSection = jobsSection.getConfigurationSection(rawJobId);
            if (jobSection == null || !jobSection.getBoolean("enabled", true)) {
                continue;
            }

            String jobId = JobManager.normalizeJobId(rawJobId);
            String displayName = jobSection.getString("display-name", prettifyJobId(jobId));
            String description = jobSection.getString("description", "");
            LevelingSettings leveling = parseLeveling(settings, jobSection.getConfigurationSection("progression"));

            Map<JobActionType, List<RewardRule>> rewards = new EnumMap<>(JobActionType.class);
            ConfigurationSection rewardsSection = jobSection.getConfigurationSection("rewards");
            if (rewardsSection != null) {
                for (String rawAction : rewardsSection.getKeys(false)) {
                    JobActionType actionType = JobActionType.fromConfigKey(rawAction);
                    if (actionType == null) {
                        core.getLogger().warning("[jobs] Unknown action type for job " + jobId + ": " + rawAction);
                        continue;
                    }

                    ConfigurationSection actionSection = rewardsSection.getConfigurationSection(rawAction);
                    if (actionSection == null) {
                        continue;
                    }

                    List<RewardRule> rules = new ArrayList<>();
                    for (String selector : actionSection.getKeys(false)) {
                        ConfigurationSection ruleSection = actionSection.getConfigurationSection(selector);
                        if (ruleSection == null) {
                            continue;
                        }

                        rules.add(new RewardRule(
                                selector,
                                normalizeBound(ruleSection.getLong("money-min", 0L)),
                                normalizeBound(ruleSection.getLong("money-max", 0L)),
                                normalizeBound(ruleSection.getLong("xp-min", 0L)),
                                normalizeBound(ruleSection.getLong("xp-max", 0L)),
                                normalizeBound(ruleSection.getLong("daily-money-cap", 0L)),
                                normalizeBound(ruleSection.getLong("daily-xp-cap", 0L))
                        ));
                    }

                    if (!rules.isEmpty()) {
                        rewards.put(actionType, List.copyOf(rules));
                    }
                }
            }

            out.put(jobId, new JobDefinition(jobId, displayName, description, leveling, rewards));
        }

        return Map.copyOf(out);
    }

    private LevelingSettings parseLeveling(ConfigurationSection defaults, ConfigurationSection section) {
        int maxLevel = getInt(section, defaults, "max-level", 50);
        long requiredXpBase = getLong(section, defaults, "required-xp-base", 100L);
        double requiredXpGrowth = getDouble(section, defaults, "required-xp-growth", 1.12D);
        double moneyPerLevel = getDouble(section, defaults, "money-per-level", 0.01D);
        double maxMoneyMultiplier = getDouble(section, defaults, "max-money-multiplier", 1.5D);
        double xpPerLevel = getDouble(section, defaults, "xp-per-level", 0.005D);
        double maxXpMultiplier = getDouble(section, defaults, "max-xp-multiplier", 1.25D);

        return new LevelingSettings(
                Math.max(1, maxLevel),
                Math.max(1L, requiredXpBase),
                Math.max(1.01D, requiredXpGrowth),
                Math.max(0D, moneyPerLevel),
                Math.max(1D, maxMoneyMultiplier),
                Math.max(0D, xpPerLevel),
                Math.max(1D, maxXpMultiplier)
        );
    }

    private int getInt(ConfigurationSection section, ConfigurationSection defaults, String path, int fallback) {
        if (section != null && section.contains(path)) {
            return section.getInt(path, fallback);
        }
        if (defaults != null && defaults.contains(path)) {
            return defaults.getInt(path, fallback);
        }
        return fallback;
    }

    private long getLong(ConfigurationSection section, ConfigurationSection defaults, String path, long fallback) {
        if (section != null && section.contains(path)) {
            return section.getLong(path, fallback);
        }
        if (defaults != null && defaults.contains(path)) {
            return defaults.getLong(path, fallback);
        }
        return fallback;
    }

    private double getDouble(ConfigurationSection section, ConfigurationSection defaults, String path, double fallback) {
        if (section != null && section.contains(path)) {
            return section.getDouble(path, fallback);
        }
        if (defaults != null && defaults.contains(path)) {
            return defaults.getDouble(path, fallback);
        }
        return fallback;
    }

    private long normalizeBound(long value) {
        return Math.max(0L, value);
    }

    private String prettifyJobId(String jobId) {
        String[] parts = jobId.split("[-_]");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }
}
