package com.krishcpatel.realm.skills.registry;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.skills.model.SkillDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of configured skills.
 */
public final class SkillDefinitionRegistry {
    private final Core core;
    private final SkillDefinitionLoader loader;
    private Map<String, SkillDefinition> definitions = Map.of();

    /**
     * Creates a new skills registry.
     *
     * @param core plugin instance
     */
    public SkillDefinitionRegistry(Core core) {
        this.core = core;
        this.loader = new SkillDefinitionLoader(core);
    }

    /**
     * Reloads definitions from {@code skills.yml}.
     */
    public void reload() {
        definitions = loader.load(core.skillsConfig());
    }

    /**
     * Returns the configured definition for a skill id.
     *
     * @param skillId normalized skill id
     * @return skill definition if present
     */
    public Optional<SkillDefinition> get(String skillId) {
        return Optional.ofNullable(definitions.get(skillId));
    }

    /**
     * Returns all configured skill definitions.
     *
     * @return immutable definitions collection
     */
    public Collection<SkillDefinition> all() {
        return definitions.values();
    }
}
