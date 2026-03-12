package com.krishcpatel.realm.skills.manager;

import com.krishcpatel.realm.skills.model.SkillProgress;
import com.krishcpatel.realm.skills.repository.SkillsRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-facing API for player skill progress.
 */
public final class SkillManager {
    private final SkillsRepository repo;

    /**
     * Creates a skill manager.
     *
     * @param repo skills repository
     */
    public SkillManager(SkillsRepository repo) {
        this.repo = repo;
    }

    /**
     * Returns a player's stored progress for one skill.
     *
     * @param playerUuid player UUID
     * @param skillId raw or normalized skill id
     * @return stored skill progress, if present
     * @throws SQLException if query fails
     */
    public Optional<SkillProgress> getSkill(UUID playerUuid, String skillId) throws SQLException {
        return Optional.ofNullable(repo.getSkill(playerUuid.toString(), normalizeSkillId(skillId)));
    }

    /**
     * Returns all stored skill progress rows for a player.
     *
     * @param playerUuid player UUID
     * @return stored skills
     * @throws SQLException if query fails
     */
    public List<SkillProgress> getSkills(UUID playerUuid) throws SQLException {
        return repo.getSkills(playerUuid.toString());
    }

    /**
     * Normalizes a skill id to lowercase with surrounding whitespace removed.
     *
     * @param skillId raw skill id
     * @return normalized skill id
     */
    public static String normalizeSkillId(String skillId) {
        return skillId == null ? "" : skillId.trim().toLowerCase(Locale.ROOT);
    }
}
