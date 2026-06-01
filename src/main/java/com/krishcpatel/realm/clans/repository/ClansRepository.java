package com.krishcpatel.realm.clans.repository;

import com.krishcpatel.realm.clans.model.ClanClaimRecord;
import com.krishcpatel.realm.clans.model.ClanMemberRecord;
import com.krishcpatel.realm.clans.model.ClanProtectedStorageRecord;
import com.krishcpatel.realm.clans.model.ClanRecord;
import com.krishcpatel.realm.core.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for clans, claims, and protected storage.
 */
public final class ClansRepository {
    private final DatabaseManager db;

    /**
     * Creates a clans repository.
     *
     * @param db database manager
     */
    public ClansRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Creates clans module schema.
     *
     * @throws SQLException if schema init fails
     */
    public void initSchema() throws SQLException {
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS clans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tag TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL UNIQUE,
                        leader_uuid TEXT NOT NULL,
                        bank_balance INTEGER NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 1,
                        member_cap INTEGER NOT NULL,
                        protected_storage_cap INTEGER NOT NULL,
                        fee_type TEXT NOT NULL DEFAULT 'NONE',
                        fee_amount INTEGER NOT NULL DEFAULT 0,
                        upkeep_next_at INTEGER NOT NULL DEFAULT 0,
                        flag_signature TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS clan_members (
                        clan_id INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL UNIQUE,
                        role TEXT NOT NULL,
                        joined_at INTEGER NOT NULL,
                        last_fee_paid_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (clan_id, player_uuid),
                        FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS clan_claims (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        clan_id INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        placed_by TEXT NOT NULL,
                        placed_at INTEGER NOT NULL,
                        FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE,
                        UNIQUE(world, x, y, z)
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS clan_protected_storage (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        clan_id INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE,
                        UNIQUE(world, x, y, z)
                    );
                    """);

            st.execute("CREATE INDEX IF NOT EXISTS idx_clan_members_player ON clan_members(player_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_clan_claims_clan ON clan_claims(clan_id);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_clan_claims_world_xyz ON clan_claims(world, x, y, z);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_clan_storage_clan ON clan_protected_storage(clan_id);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_clans_upkeep_next ON clans(upkeep_next_at);");
        }
    }

    /**
     * Finds a clan by id.
     *
     * @param clanId clan id
     * @return clan when present
     * @throws SQLException if query fails
     */
    public Optional<ClanRecord> findClanById(long clanId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM clans
                     WHERE id = ?
                     """)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapClan(rs));
            }
        }
    }

    /**
     * Finds a clan by tag.
     *
     * @param tag clan tag
     * @return clan when present
     * @throws SQLException if query fails
     */
    public Optional<ClanRecord> findClanByTag(String tag) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM clans
                     WHERE lower(tag) = lower(?)
                     """)) {
            ps.setString(1, tag);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapClan(rs));
            }
        }
    }

    /**
     * Finds the clan containing the given player.
     *
     * @param playerUuid player UUID string
     * @return clan when present
     * @throws SQLException if query fails
     */
    public Optional<ClanRecord> findClanByPlayer(String playerUuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT c.*
                     FROM clans c
                     INNER JOIN clan_members m ON c.id = m.clan_id
                     WHERE m.player_uuid = ?
                     LIMIT 1
                     """)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapClan(rs));
            }
        }
    }

    /**
     * Lists all clans.
     *
     * @return clans
     * @throws SQLException if query fails
     */
    public List<ClanRecord> listClans() throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM clans ORDER BY lower(tag) ASC");
             ResultSet rs = ps.executeQuery()) {
            List<ClanRecord> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapClan(rs));
            }
            return out;
        }
    }

    /**
     * Inserts a clan row.
     *
     * @param c connection
     * @param tag tag
     * @param name name
     * @param leaderUuid leader UUID
     * @param memberCap initial member cap
     * @param storageCap initial protected storage cap
     * @param upkeepNextAt next upkeep timestamp
     * @param now now timestamp
     * @return inserted clan id
     * @throws SQLException if insert fails
     */
    public long insertClan(
            Connection c,
            String tag,
            String name,
            String leaderUuid,
            int memberCap,
            int storageCap,
            long upkeepNextAt,
            long now
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO clans (
                    tag, name, leader_uuid, bank_balance, level,
                    member_cap, protected_storage_cap, fee_type, fee_amount,
                    upkeep_next_at, flag_signature, created_at, updated_at
                )
                VALUES (?, ?, ?, 0, 1, ?, ?, 'NONE', 0, ?, NULL, ?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tag);
            ps.setString(2, name);
            ps.setString(3, leaderUuid);
            ps.setInt(4, memberCap);
            ps.setInt(5, storageCap);
            ps.setLong(6, upkeepNextAt);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    /**
     * Inserts a clan member row.
     *
     * @param c connection
     * @param clanId clan id
     * @param playerUuid player UUID
     * @param role role
     * @param joinedAt joined timestamp
     * @param lastFeePaidAt last fee paid timestamp
     * @throws SQLException if insert fails
     */
    public void insertMember(
            Connection c,
            long clanId,
            String playerUuid,
            String role,
            long joinedAt,
            long lastFeePaidAt
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO clan_members (clan_id, player_uuid, role, joined_at, last_fee_paid_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, clanId);
            ps.setString(2, playerUuid);
            ps.setString(3, role);
            ps.setLong(4, joinedAt);
            ps.setLong(5, lastFeePaidAt);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes a clan and all child rows.
     *
     * @param c connection
     * @param clanId clan id
     * @return true if deleted
     * @throws SQLException if delete fails
     */
    public boolean deleteClan(Connection c, long clanId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM clans WHERE id = ?")) {
            ps.setLong(1, clanId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Returns membership count.
     *
     * @param c connection
     * @param clanId clan id
     * @return member count
     * @throws SQLException if query fails
     */
    public int countMembers(Connection c, long clanId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) AS c
                FROM clan_members
                WHERE clan_id = ?
                """)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        }
    }

    /**
     * Lists members for a clan.
     *
     * @param clanId clan id
     * @return members
     * @throws SQLException if query fails
     */
    public List<ClanMemberRecord> listMembers(long clanId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT clan_id, player_uuid, role, joined_at, last_fee_paid_at
                     FROM clan_members
                     WHERE clan_id = ?
                     ORDER BY joined_at ASC
                     """)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ClanMemberRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ClanMemberRecord(
                            rs.getLong("clan_id"),
                            rs.getString("player_uuid"),
                            rs.getString("role"),
                            rs.getLong("joined_at"),
                            rs.getLong("last_fee_paid_at")
                    ));
                }
                return out;
            }
        }
    }

    /**
     * Lists members for a clan using an existing connection.
     *
     * @param c connection
     * @param clanId clan id
     * @return members
     * @throws SQLException if query fails
     */
    public List<ClanMemberRecord> listMembers(Connection c, long clanId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT clan_id, player_uuid, role, joined_at, last_fee_paid_at
                FROM clan_members
                WHERE clan_id = ?
                ORDER BY joined_at ASC
                """)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ClanMemberRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ClanMemberRecord(
                            rs.getLong("clan_id"),
                            rs.getString("player_uuid"),
                            rs.getString("role"),
                            rs.getLong("joined_at"),
                            rs.getLong("last_fee_paid_at")
                    ));
                }
                return out;
            }
        }
    }

    /**
     * Finds a member row by player.
     *
     * @param playerUuid player UUID
     * @return member row when present
     * @throws SQLException if query fails
     */
    public Optional<ClanMemberRecord> findMember(String playerUuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT clan_id, player_uuid, role, joined_at, last_fee_paid_at
                     FROM clan_members
                     WHERE player_uuid = ?
                     LIMIT 1
                     """)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ClanMemberRecord(
                        rs.getLong("clan_id"),
                        rs.getString("player_uuid"),
                        rs.getString("role"),
                        rs.getLong("joined_at"),
                        rs.getLong("last_fee_paid_at")
                ));
            }
        }
    }

    /**
     * Removes a member from a clan.
     *
     * @param c connection
     * @param clanId clan id
     * @param playerUuid player UUID
     * @return true if removed
     * @throws SQLException if delete fails
     */
    public boolean removeMember(Connection c, long clanId, String playerUuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM clan_members
                WHERE clan_id = ? AND player_uuid = ?
                """)) {
            ps.setLong(1, clanId);
            ps.setString(2, playerUuid);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Updates clan leader UUID.
     *
     * @param c connection
     * @param clanId clan id
     * @param leaderUuid leader UUID
     * @param now timestamp
     * @throws SQLException if update fails
     */
    public void updateLeader(Connection c, long clanId, String leaderUuid, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET leader_uuid = ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, leaderUuid);
            ps.setLong(2, now);
            ps.setLong(3, clanId);
            ps.executeUpdate();
        }
    }

    /**
     * Updates member role.
     *
     * @param c connection
     * @param clanId clan id
     * @param playerUuid player UUID
     * @param role role
     * @throws SQLException if update fails
     */
    public void updateMemberRole(Connection c, long clanId, String playerUuid, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clan_members
                SET role = ?
                WHERE clan_id = ? AND player_uuid = ?
                """)) {
            ps.setString(1, role);
            ps.setLong(2, clanId);
            ps.setString(3, playerUuid);
            ps.executeUpdate();
        }
    }

    /**
     * Updates member fee paid timestamp.
     *
     * @param c connection
     * @param clanId clan id
     * @param playerUuid member UUID
     * @param paidAt timestamp
     * @throws SQLException if update fails
     */
    public void updateMemberLastFeePaid(Connection c, long clanId, String playerUuid, long paidAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clan_members
                SET last_fee_paid_at = ?
                WHERE clan_id = ? AND player_uuid = ?
                """)) {
            ps.setLong(1, paidAt);
            ps.setLong(2, clanId);
            ps.setString(3, playerUuid);
            ps.executeUpdate();
        }
    }

    /**
     * Updates clan fee settings.
     *
     * @param c connection
     * @param clanId clan id
     * @param feeType fee type
     * @param feeAmount fee amount
     * @param now timestamp
     * @throws SQLException if update fails
     */
    public void updateFeeSettings(Connection c, long clanId, String feeType, long feeAmount, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET fee_type = ?, fee_amount = ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, feeType);
            ps.setLong(2, Math.max(0L, feeAmount));
            ps.setLong(3, now);
            ps.setLong(4, clanId);
            ps.executeUpdate();
        }
    }

    /**
     * Updates clan flag signature.
     *
     * @param c connection
     * @param clanId clan id
     * @param flagSignature serialized banner signature
     * @param now timestamp
     * @throws SQLException if update fails
     */
    public void updateFlagSignature(Connection c, long clanId, String flagSignature, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET flag_signature = ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, flagSignature);
            ps.setLong(2, now);
            ps.setLong(3, clanId);
            ps.executeUpdate();
        }
    }

    /**
     * Updates clan level and caps.
     *
     * @param c connection
     * @param clanId clan id
     * @param level new level
     * @param memberCap member cap
     * @param storageCap protected storage cap
     * @param now timestamp
     * @throws SQLException if update fails
     */
    public void updateLevelAndCaps(
            Connection c,
            long clanId,
            int level,
            int memberCap,
            int storageCap,
            long now
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET level = ?, member_cap = ?, protected_storage_cap = ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setInt(1, level);
            ps.setInt(2, memberCap);
            ps.setInt(3, storageCap);
            ps.setLong(4, now);
            ps.setLong(5, clanId);
            ps.executeUpdate();
        }
    }

    /**
     * Adds to clan bank balance.
     *
     * @param c connection
     * @param clanId clan id
     * @param amount amount to add
     * @throws SQLException if update fails
     */
    public void addBank(Connection c, long clanId, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET bank_balance = bank_balance + ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setLong(1, Math.max(0L, amount));
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, clanId);
            ps.executeUpdate();
        }
    }

    /**
     * Subtracts from clan bank when enough funds exist.
     *
     * @param c connection
     * @param clanId clan id
     * @param amount amount to subtract
     * @return true if updated
     * @throws SQLException if update fails
     */
    public boolean subtractBankIfEnough(Connection c, long clanId, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET bank_balance = bank_balance - ?, updated_at = ?
                WHERE id = ? AND bank_balance >= ?
                """)) {
            ps.setLong(1, Math.max(0L, amount));
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, clanId);
            ps.setLong(4, Math.max(0L, amount));
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Sets upkeep next timestamp.
     *
     * @param c connection
     * @param clanId clan id
     * @param upkeepNextAt next upkeep timestamp
     * @throws SQLException if update fails
     */
    public void updateUpkeepNextAt(Connection c, long clanId, long upkeepNextAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clans
                SET upkeep_next_at = ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setLong(1, upkeepNextAt);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, clanId);
            ps.executeUpdate();
        }
    }

    /**
     * Lists clans whose upkeep is due.
     *
     * @param now now timestamp
     * @return clans with due upkeep
     * @throws SQLException if query fails
     */
    public List<ClanRecord> listUpkeepDueClans(long now) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM clans
                     WHERE upkeep_next_at <= ?
                     ORDER BY upkeep_next_at ASC
                     """)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<ClanRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapClan(rs));
                }
                return out;
            }
        }
    }

    /**
     * Inserts a claim row.
     *
     * @param c connection
     * @param clanId clan id
     * @param world world name
     * @param x x
     * @param y y
     * @param z z
     * @param placedBy placer UUID
     * @param placedAt timestamp
     * @return inserted claim id
     * @throws SQLException if insert fails
     */
    public long insertClaim(
            Connection c,
            long clanId,
            String world,
            int x,
            int y,
            int z,
            String placedBy,
            long placedAt
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO clan_claims (clan_id, world, x, y, z, placed_by, placed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, clanId);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, placedBy);
            ps.setLong(7, placedAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    /**
     * Deletes a claim by id.
     *
     * @param c connection
     * @param claimId claim id
     * @return true if deleted
     * @throws SQLException if delete fails
     */
    public boolean deleteClaimById(Connection c, long claimId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM clan_claims WHERE id = ?")) {
            ps.setLong(1, claimId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Deletes a claim by exact location.
     *
     * @param c connection
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return true if deleted
     * @throws SQLException if delete fails
     */
    public boolean deleteClaimAt(Connection c, String world, int x, int y, int z) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM clan_claims
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                """)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Lists all claims.
     *
     * @return claims
     * @throws SQLException if query fails
     */
    public List<ClanClaimRecord> listClaims() throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, clan_id, world, x, y, z, placed_by, placed_at
                     FROM clan_claims
                     """);
             ResultSet rs = ps.executeQuery()) {
            List<ClanClaimRecord> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapClaim(rs));
            }
            return out;
        }
    }

    /**
     * Finds a claim by exact block position.
     *
     * @param world world name
     * @param x x
     * @param y y
     * @param z z
     * @return claim when present
     * @throws SQLException if query fails
     */
    public Optional<ClanClaimRecord> findClaimAt(String world, int x, int y, int z) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, clan_id, world, x, y, z, placed_by, placed_at
                     FROM clan_claims
                     WHERE world = ? AND x = ? AND y = ? AND z = ?
                     LIMIT 1
                     """)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapClaim(rs));
            }
        }
    }

    /**
     * Lists claims owned by a clan.
     *
     * @param clanId clan id
     * @return claims
     * @throws SQLException if query fails
     */
    public List<ClanClaimRecord> listClaimsForClan(long clanId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, clan_id, world, x, y, z, placed_by, placed_at
                     FROM clan_claims
                     WHERE clan_id = ?
                     ORDER BY placed_at ASC
                     """)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ClanClaimRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapClaim(rs));
                }
                return out;
            }
        }
    }

    /**
     * Inserts protected storage row.
     *
     * @param c connection
     * @param clanId clan id
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @param createdBy creator UUID
     * @param createdAt timestamp
     * @return inserted row id
     * @throws SQLException if insert fails
     */
    public long insertProtectedStorage(
            Connection c,
            long clanId,
            String world,
            int x,
            int y,
            int z,
            String createdBy,
            long createdAt
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO clan_protected_storage (clan_id, world, x, y, z, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, clanId);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, createdBy);
            ps.setLong(7, createdAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    /**
     * Deletes protected storage row at location.
     *
     * @param c connection
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return true if deleted
     * @throws SQLException if delete fails
     */
    public boolean deleteProtectedStorageAt(Connection c, String world, int x, int y, int z) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM clan_protected_storage
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                """)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Counts protected storage rows for a clan.
     *
     * @param c connection
     * @param clanId clan id
     * @return count
     * @throws SQLException if query fails
     */
    public int countProtectedStorage(Connection c, long clanId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) AS c
                FROM clan_protected_storage
                WHERE clan_id = ?
                """)) {
            ps.setLong(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        }
    }

    /**
     * Lists all protected storage rows.
     *
     * @return protected storage rows
     * @throws SQLException if query fails
     */
    public List<ClanProtectedStorageRecord> listProtectedStorage() throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, clan_id, world, x, y, z, created_by, created_at
                     FROM clan_protected_storage
                     """);
             ResultSet rs = ps.executeQuery()) {
            List<ClanProtectedStorageRecord> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapStorage(rs));
            }
            return out;
        }
    }

    /**
     * Finds protected storage by exact location.
     *
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return storage row when present
     * @throws SQLException if query fails
     */
    public Optional<ClanProtectedStorageRecord> findProtectedStorageAt(String world, int x, int y, int z) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, clan_id, world, x, y, z, created_by, created_at
                     FROM clan_protected_storage
                     WHERE world = ? AND x = ? AND y = ? AND z = ?
                     LIMIT 1
                     """)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapStorage(rs));
            }
        }
    }

    private ClanRecord mapClan(ResultSet rs) throws SQLException {
        return new ClanRecord(
                rs.getLong("id"),
                rs.getString("tag"),
                rs.getString("name"),
                rs.getString("leader_uuid"),
                rs.getLong("bank_balance"),
                rs.getInt("level"),
                rs.getInt("member_cap"),
                rs.getInt("protected_storage_cap"),
                rs.getString("fee_type"),
                rs.getLong("fee_amount"),
                rs.getLong("upkeep_next_at"),
                rs.getString("flag_signature"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private ClanClaimRecord mapClaim(ResultSet rs) throws SQLException {
        return new ClanClaimRecord(
                rs.getLong("id"),
                rs.getLong("clan_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("placed_by"),
                rs.getLong("placed_at")
        );
    }

    private ClanProtectedStorageRecord mapStorage(ResultSet rs) throws SQLException {
        return new ClanProtectedStorageRecord(
                rs.getLong("id"),
                rs.getLong("clan_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("created_by"),
                rs.getLong("created_at")
        );
    }
}
