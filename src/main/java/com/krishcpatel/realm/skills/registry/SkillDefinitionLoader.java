package com.krishcpatel.realm.skills.registry;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.skills.manager.SkillManager;
import com.krishcpatel.realm.skills.model.SkillCategory;
import com.krishcpatel.realm.skills.model.SkillDefinition;
import com.krishcpatel.realm.skills.model.SkillProgressionSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads skill definitions from {@code skills.yml}.
 */
public final class SkillDefinitionLoader {
    private final Core core;

    /**
     * Creates a loader for skills configuration.
     *
     * @param core plugin instance
     */
    public SkillDefinitionLoader(Core core) {
        this.core = core;
    }

    /**
     * Loads all enabled skills from config.
     *
     * @param config loaded skills configuration
     * @return immutable map of skill id to definition
     */
    public Map<String, SkillDefinition> load(FileConfiguration config) {
        ConfigurationSection skillsSection = config.getConfigurationSection("skills");
        if (skillsSection == null) {
            core.getLogger().warning("[skills] No skills section found in skills.yml.");
            return Map.of();
        }

        ConfigurationSection defaults = config.getConfigurationSection("settings.progression");
        Map<String, SkillDefinition> out = new LinkedHashMap<>();

        for (String rawSkillId : skillsSection.getKeys(false)) {
            ConfigurationSection skillSection = skillsSection.getConfigurationSection(rawSkillId);
            if (skillSection == null || !skillSection.getBoolean("enabled", true)) {
                continue;
            }

            String skillId = SkillManager.normalizeSkillId(rawSkillId);
            String displayName = skillSection.getString("display-name", prettifySkillId(skillId));
            SkillCategory category = SkillCategory.fromConfigKey(skillSection.getString("category", "other"));
            if (category == null) {
                core.getLogger().warning("[skills] Unknown category for skill " + skillId + ".");
                continue;
            }

            SkillProgressionSettings progression = parseProgression(defaults, skillSection.getConfigurationSection("progression"));
            Map<String, Long> xpMap = new LinkedHashMap<>();
            ConfigurationSection xpSection = skillSection.getConfigurationSection("xp");
            if (xpSection != null) {
                for (String actionKey : xpSection.getKeys(false)) {
                    xpMap.put(actionKey, Math.max(0L, xpSection.getLong(actionKey, 0L)));
                }
            }

            out.put(skillId, new SkillDefinition(skillId, displayName, category, progression, xpMap));
        }

        return Map.copyOf(out);
    }

    private SkillProgressionSettings parseProgression(ConfigurationSection defaults, ConfigurationSection section) {
        int maxLevel = getInt(section, defaults, "max-level", 100);
        long requiredXpBase = getLong(section, defaults, "required-xp-base", 100L);
        double requiredXpGrowth = getDouble(section, defaults, "required-xp-growth", 1.12D);

        return new SkillProgressionSettings(
                Math.max(1, maxLevel),
                Math.max(1L, requiredXpBase),
                Math.max(1.01D, requiredXpGrowth)
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

    private String prettifySkillId(String skillId) {
        String[] parts = skillId.split("[-_]");
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
