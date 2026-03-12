package com.krishcpatel.realm.skills.repository;

import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.skills.model.SkillProgress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence layer for skill xp and levels.
 */
public final class SkillsRepository {
    private final DatabaseManager db;

    /**
     * Creates the skills repository.
     *
     * @param db database manager
     */
    public SkillsRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Creates the skills schema if it does not exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initSchema() throws SQLException {
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS skills_progress (
                    player_uuid TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    level INTEGER NOT NULL DEFAULT 1,
                    xp INTEGER NOT NULL DEFAULT 0,
                    total_xp INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, skill_id)
                );
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_skills_progress_player ON skills_progress(player_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_skills_progress_skill ON skills_progress(skill_id);");
        }
    }

    /**
     * Returns all stored skill progress rows for a player.
     *
     * @param playerUuid player UUID as string
     * @return stored skill progress rows
     * @throws SQLException if query fails
     */
    public List<SkillProgress> getSkills(String playerUuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
            SELECT skill_id, level, xp, total_xp, updated_at
            FROM skills_progress
            WHERE player_uuid = ?
            ORDER BY skill_id ASC
        """)) {
            ps.setString(1, playerUuid);

            List<SkillProgress> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapSkillProgress(rs));
                }
            }
            return out;
        }
    }

    /**
     * Returns a single skill progress row for a player.
     *
     * @param playerUuid player UUID as string
     * @param skillId normalized skill id
     * @return stored progress row, or null when missing
     * @throws SQLException if query fails
     */
    public SkillProgress getSkill(String playerUuid, String skillId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
            SELECT skill_id, level, xp, total_xp, updated_at
            FROM skills_progress
            WHERE player_uuid = ?
              AND skill_id = ?
            LIMIT 1
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSkillProgress(rs) : null;
            }
        }
    }

    /**
     * Returns a single skill progress row using an existing connection.
     *
     * @param c active connection
     * @param playerUuid player UUID as string
     * @param skillId normalized skill id
     * @return stored progress row, or null when missing
     * @throws SQLException if query fails
     */
    public SkillProgress getSkill(Connection c, String playerUuid, String skillId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT skill_id, level, xp, total_xp, updated_at
            FROM skills_progress
            WHERE player_uuid = ?
              AND skill_id = ?
            LIMIT 1
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSkillProgress(rs) : null;
            }
        }
    }

    /**
     * Ensures a progress row exists for a player and skill.
     *
     * @param c active connection
     * @param playerUuid player UUID as string
     * @param skillId normalized skill id
     * @param startingLevel initial level when inserting
     * @param updatedAt update timestamp
     * @throws SQLException if write fails
     */
    public void ensureSkill(Connection c, String playerUuid, String skillId, int startingLevel, long updatedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO skills_progress (player_uuid, skill_id, level, xp, total_xp, updated_at)
            VALUES (?, ?, ?, 0, 0, ?)
            ON CONFLICT(player_uuid, skill_id) DO NOTHING
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, skillId);
            ps.setInt(3, startingLevel);
            ps.setLong(4, updatedAt);
            ps.executeUpdate();
        }
    }

    /**
     * Updates persisted skill progress values.
     *
     * @param c active connection
     * @param playerUuid player UUID as string
     * @param skillId normalized skill id
     * @param level updated level
     * @param xp updated current xp
     * @param totalXp updated lifetime xp
     * @param updatedAt update timestamp
     * @throws SQLException if write fails
     */
    public void updateSkill(Connection c, String playerUuid, String skillId, int level, long xp, long totalXp, long updatedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            UPDATE skills_progress
            SET level = ?,
                xp = ?,
                total_xp = ?,
                updated_at = ?
            WHERE player_uuid = ?
              AND skill_id = ?
        """)) {
            ps.setInt(1, level);
            ps.setLong(2, xp);
            ps.setLong(3, totalXp);
            ps.setLong(4, updatedAt);
            ps.setString(5, playerUuid);
            ps.setString(6, skillId);
            ps.executeUpdate();
        }
    }

    private SkillProgress mapSkillProgress(ResultSet rs) throws SQLException {
        return new SkillProgress(
                rs.getString("skill_id"),
                rs.getInt("level"),
                rs.getLong("xp"),
                rs.getLong("total_xp"),
                rs.getLong("updated_at")
        );
    }
}
