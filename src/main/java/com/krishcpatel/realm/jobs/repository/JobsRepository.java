package com.krishcpatel.realm.jobs.repository;

import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.jobs.model.JobCapState;
import com.krishcpatel.realm.jobs.model.PlayerJob;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence layer for player jobs, progression, reward caps, and explorer progress.
 */
public final class JobsRepository {
    private final DatabaseManager db;

    /**
     * Creates the jobs repository.
     *
     * @param db database manager
     */
    public JobsRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Creates the jobs schema if it does not already exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initSchema() throws SQLException {
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS jobs_memberships (
                    player_uuid TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, job_id)
                );
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS jobs_progress (
                    player_uuid TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    level INTEGER NOT NULL DEFAULT 1,
                    xp INTEGER NOT NULL DEFAULT 0,
                    total_xp INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid, job_id)
                );
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS jobs_reward_caps (
                    player_uuid TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    reward_key TEXT NOT NULL,
                    day_key INTEGER NOT NULL,
                    money_earned INTEGER NOT NULL DEFAULT 0,
                    xp_earned INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid, job_id, reward_key, day_key)
                );
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS jobs_explorer_chunks (
                    player_uuid TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    world TEXT NOT NULL,
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    visited_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, job_id, world, chunk_x, chunk_z)
                );
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS jobs_placed_block_guards (
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    material TEXT NOT NULL,
                    placed_at INTEGER NOT NULL,
                    PRIMARY KEY (world, x, y, z)
                );
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_memberships_player ON jobs_memberships(player_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_progress_player ON jobs_progress(player_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_caps_day ON jobs_reward_caps(day_key);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_placed_blocks_player ON jobs_placed_block_guards(player_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_placed_blocks_placed_at ON jobs_placed_block_guards(placed_at);");
        }
    }

    /**
     * Returns all active jobs for a player in join order.
     *
     * @param playerUuid player UUID as a string
     * @return active jobs for the player
     * @throws SQLException if the query fails
     */
    public List<PlayerJob> getJobs(String playerUuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
            SELECT m.job_id, p.level, p.xp, p.total_xp, m.joined_at
            FROM jobs_memberships m
            JOIN jobs_progress p
              ON p.player_uuid = m.player_uuid
             AND p.job_id = m.job_id
            WHERE m.player_uuid = ?
            ORDER BY m.joined_at ASC
        """)) {
            ps.setString(1, playerUuid);

            List<PlayerJob> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapPlayerJob(rs));
                }
            }
            return out;
        }
    }

    /**
     * Returns a specific active job for a player.
     *
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @return matching player job, or {@code null} if none exists
     * @throws SQLException if the query fails
     */
    public PlayerJob getJob(String playerUuid, String jobId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
            SELECT m.job_id, p.level, p.xp, p.total_xp, m.joined_at
            FROM jobs_memberships m
            JOIN jobs_progress p
              ON p.player_uuid = m.player_uuid
             AND p.job_id = m.job_id
            WHERE m.player_uuid = ?
              AND m.job_id = ?
            LIMIT 1
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPlayerJob(rs) : null;
            }
        }
    }

    /**
     * Returns a specific active job for a player using an existing transaction connection.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @return matching player job, or {@code null} if none exists
     * @throws SQLException if the query fails
     */
    public PlayerJob getJob(Connection c, String playerUuid, String jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT m.job_id, p.level, p.xp, p.total_xp, m.joined_at
            FROM jobs_memberships m
            JOIN jobs_progress p
              ON p.player_uuid = m.player_uuid
             AND p.job_id = m.job_id
            WHERE m.player_uuid = ?
              AND m.job_id = ?
            LIMIT 1
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPlayerJob(rs) : null;
            }
        }
    }

    /**
     * Counts how many active jobs a player currently has.
     *
     * @param playerUuid player UUID as a string
     * @return number of active jobs
     * @throws SQLException if the query fails
     */
    public int countJobs(String playerUuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
            SELECT COUNT(*) AS cnt
            FROM jobs_memberships
            WHERE player_uuid = ?
        """)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("cnt") : 0;
            }
        }
    }

    /**
     * Joins a player to a job and initializes progress in a local transaction.
     *
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param joinedAt membership timestamp
     * @param startingLevel initial level row value
     * @return true if the membership row was inserted
     * @throws SQLException if the write fails
     */
    public boolean joinJob(String playerUuid, String jobId, long joinedAt, int startingLevel) throws SQLException {
        return db.executeWrite(() -> {
            try (Connection c = db.getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);

                try {
                    boolean inserted = joinJob(c, playerUuid, jobId, joinedAt, startingLevel);
                    c.commit();
                    return inserted;
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }
        });
    }

    /**
     * Joins a player to a job using an existing transaction connection.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param joinedAt membership timestamp
     * @param startingLevel initial level row value
     * @return true if the membership row was inserted
     * @throws SQLException if the write fails
     */
    public boolean joinJob(Connection c, String playerUuid, String jobId, long joinedAt, int startingLevel) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO jobs_memberships (player_uuid, job_id, joined_at)
            VALUES (?, ?, ?)
            ON CONFLICT(player_uuid, job_id) DO NOTHING
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            ps.setLong(3, joinedAt);
            boolean inserted = ps.executeUpdate() > 0;
            ensureProgress(c, playerUuid, jobId, startingLevel);
            return inserted;
        }
    }

    /**
     * Ensures a progress row exists for a player's job.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param startingLevel initial level if a row must be created
     * @throws SQLException if the write fails
     */
    public void ensureProgress(Connection c, String playerUuid, String jobId, int startingLevel) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO jobs_progress (player_uuid, job_id, level, xp, total_xp)
            VALUES (?, ?, ?, 0, 0)
            ON CONFLICT(player_uuid, job_id) DO NOTHING
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            ps.setInt(3, startingLevel);
            ps.executeUpdate();
        }
    }

    /**
     * Removes a single job membership from a player.
     *
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @return true if a membership row was removed
     * @throws SQLException if the delete fails
     */
    public boolean leaveJob(String playerUuid, String jobId) throws SQLException {
        return db.executeWrite(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
            DELETE FROM jobs_memberships
            WHERE player_uuid = ?
              AND job_id = ?
        """)) {
                ps.setString(1, playerUuid);
                ps.setString(2, jobId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * Removes all job memberships from a player.
     *
     * @param playerUuid player UUID as a string
     * @return number of membership rows removed
     * @throws SQLException if the delete fails
     */
    public int leaveAllJobs(String playerUuid) throws SQLException {
        return db.executeWrite(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
            DELETE FROM jobs_memberships
            WHERE player_uuid = ?
        """)) {
                ps.setString(1, playerUuid);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Returns the current daily cap usage for a reward rule.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param rewardKey unique rule reward key
     * @param dayKey UTC epoch day
     * @return current cap state, or {@link JobCapState#EMPTY} if none exists yet
     * @throws SQLException if the query fails
     */
    public JobCapState getCapState(Connection c, String playerUuid, String jobId, String rewardKey, long dayKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT money_earned, xp_earned
            FROM jobs_reward_caps
            WHERE player_uuid = ?
              AND job_id = ?
              AND reward_key = ?
              AND day_key = ?
            LIMIT 1
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            ps.setString(3, rewardKey);
            ps.setLong(4, dayKey);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return JobCapState.EMPTY;
                }
                return new JobCapState(
                        rs.getLong("money_earned"),
                        rs.getLong("xp_earned")
                );
            }
        }
    }

    /**
     * Adds earned amounts to a player's daily cap usage for a reward rule.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param rewardKey unique rule reward key
     * @param dayKey UTC epoch day
     * @param money money earned to add
     * @param xp xp earned to add
     * @throws SQLException if the write fails
     */
    public void addCapEarnings(Connection c, String playerUuid, String jobId, String rewardKey, long dayKey, long money, long xp) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO jobs_reward_caps (player_uuid, job_id, reward_key, day_key, money_earned, xp_earned)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid, job_id, reward_key, day_key) DO UPDATE SET
                money_earned = money_earned + excluded.money_earned,
                xp_earned = xp_earned + excluded.xp_earned
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            ps.setString(3, rewardKey);
            ps.setLong(4, dayKey);
            ps.setLong(5, money);
            ps.setLong(6, xp);
            ps.executeUpdate();
        }
    }

    /**
     * Updates a player's stored level and xp totals for a job.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param level new level
     * @param xp current xp toward the next level
     * @param totalXp lifetime xp total
     * @throws SQLException if the update fails
     */
    public void updateProgress(Connection c, String playerUuid, String jobId, int level, long xp, long totalXp) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            UPDATE jobs_progress
            SET level = ?,
                xp = ?,
                total_xp = ?
            WHERE player_uuid = ?
              AND job_id = ?
        """)) {
            ps.setInt(1, level);
            ps.setLong(2, xp);
            ps.setLong(3, totalXp);
            ps.setString(4, playerUuid);
            ps.setString(5, jobId);
            ps.executeUpdate();
        }
    }

    /**
     * Marks a chunk as explored for a player's job, returning whether it was a first visit.
     *
     * @param c active database connection
     * @param playerUuid player UUID as a string
     * @param jobId normalized job identifier
     * @param world world name
     * @param chunkX chunk x coordinate
     * @param chunkZ chunk z coordinate
     * @param visitedAt first-visit timestamp
     * @return true if the row was inserted for the first time
     * @throws SQLException if the write fails
     */
    public boolean markChunkExplored(Connection c, String playerUuid, String jobId, String world, int chunkX, int chunkZ, long visitedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO jobs_explorer_chunks (player_uuid, job_id, world, chunk_x, chunk_z, visited_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid, job_id, world, chunk_x, chunk_z) DO NOTHING
        """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, jobId);
            ps.setString(3, world);
            ps.setInt(4, chunkX);
            ps.setInt(5, chunkZ);
            ps.setLong(6, visitedAt);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Persists a placed block guard so the block cannot later be farmed for break rewards.
     *
     * @param world world name
     * @param x block x
     * @param y block y
     * @param z block z
     * @param playerUuid player who placed the block
     * @param material material name
     * @param placedAt placement timestamp
     * @throws SQLException if the write fails
     */
    public void markPlacedBlockGuard(
            String world,
            int x,
            int y,
            int z,
            String playerUuid,
            String material,
            long placedAt
    ) throws SQLException {
        db.executeWrite(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
            INSERT INTO jobs_placed_block_guards (world, x, y, z, player_uuid, material, placed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(world, x, y, z) DO UPDATE SET
                player_uuid = excluded.player_uuid,
                material = excluded.material,
                placed_at = excluded.placed_at
        """)) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, playerUuid);
                ps.setString(6, material);
                ps.setLong(7, placedAt);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Consumes a placed block guard if one exists for the given location.
     *
     * <p>This is used on block break to suppress rewards for player-placed blocks
     * while also removing the guard row once it has served its purpose.</p>
     *
     * @param world world name
     * @param x block x
     * @param y block y
     * @param z block z
     * @return true if the block was known to be player-placed
     * @throws SQLException if the delete fails
     */
    public boolean consumePlacedBlockGuard(String world, int x, int y, int z) throws SQLException {
        return db.executeWrite(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
            DELETE FROM jobs_placed_block_guards
            WHERE world = ?
              AND x = ?
              AND y = ?
              AND z = ?
        """)) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * Purges stale placed-block guard rows older than the provided cutoff.
     *
     * @param cutoffMillis epoch millis cutoff (rows older than this are deleted)
     * @return deleted row count
     * @throws SQLException if the delete fails
     */
    public int purgePlacedBlockGuardsOlderThan(long cutoffMillis) throws SQLException {
        return db.executeWrite(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
            DELETE FROM jobs_placed_block_guards
            WHERE placed_at < ?
        """)) {
                ps.setLong(1, cutoffMillis);
                return ps.executeUpdate();
            }
        });
    }

    private PlayerJob mapPlayerJob(ResultSet rs) throws SQLException {
        return new PlayerJob(
                rs.getString("job_id"),
                rs.getInt("level"),
                rs.getLong("xp"),
                rs.getLong("total_xp"),
                rs.getLong("joined_at")
        );
    }
}
