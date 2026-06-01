package com.krishcpatel.realm.shop.repository;

import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.shop.model.ShopAreaRecord;
import com.krishcpatel.realm.shop.model.ShopChestRecord;
import com.krishcpatel.realm.shop.model.ShopListingRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for shop areas, chests, and listings.
 */
public final class ShopRepository {
    private final DatabaseManager db;

    /**
     * Creates a shop repository.
     *
     * @param db database manager
     */
    public ShopRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Initializes the shop schema.
     *
     * @throws SQLException if schema init fails
     */
    public void initSchema() throws SQLException {
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS shop_areas (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        area_key TEXT NOT NULL UNIQUE,
                        world TEXT NOT NULL,
                        min_x INTEGER NOT NULL,
                        max_x INTEGER NOT NULL,
                        min_y INTEGER NOT NULL,
                        max_y INTEGER NOT NULL,
                        min_z INTEGER NOT NULL,
                        max_z INTEGER NOT NULL,
                        claim_fee INTEGER NOT NULL DEFAULT 0,
                        upkeep_fee INTEGER NOT NULL DEFAULT 0,
                        owner_uuid TEXT,
                        claimed_at INTEGER NOT NULL DEFAULT 0,
                        upkeep_next_at INTEGER NOT NULL DEFAULT 0,
                        active INTEGER NOT NULL DEFAULT 1
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS shop_chests (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        area_id INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        UNIQUE(world, x, y, z),
                        FOREIGN KEY (area_id) REFERENCES shop_areas(id) ON DELETE CASCADE
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS shop_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chest_id INTEGER NOT NULL,
                        slot_index INTEGER NOT NULL,
                        material TEXT NOT NULL,
                        unit_amount INTEGER NOT NULL,
                        price INTEGER NOT NULL,
                        UNIQUE(chest_id, slot_index),
                        FOREIGN KEY (chest_id) REFERENCES shop_chests(id) ON DELETE CASCADE
                    );
                    """);

            st.execute("CREATE INDEX IF NOT EXISTS idx_shop_areas_world ON shop_areas(world);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_shop_areas_owner ON shop_areas(owner_uuid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_shop_areas_upkeep ON shop_areas(upkeep_next_at);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_shop_chests_area ON shop_chests(area_id);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_shop_listings_chest ON shop_listings(chest_id);");
        }
    }

    /**
     * Inserts or updates a shop area definition.
     *
     * @param c connection
     * @param areaKey area id/key
     * @param world world name
     * @param minX min x
     * @param maxX max x
     * @param minY min y
     * @param maxY max y
     * @param minZ min z
     * @param maxZ max z
     * @param claimFee claim fee
     * @param upkeepFee upkeep fee
     * @throws SQLException if write fails
     */
    public void upsertArea(
            Connection c,
            String areaKey,
            String world,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            long claimFee,
            long upkeepFee
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shop_areas (
                    area_key, world, min_x, max_x, min_y, max_y, min_z, max_z,
                    claim_fee, upkeep_fee, owner_uuid, claimed_at, upkeep_next_at, active
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, 0, 1)
                ON CONFLICT(area_key) DO UPDATE SET
                    world = excluded.world,
                    min_x = excluded.min_x,
                    max_x = excluded.max_x,
                    min_y = excluded.min_y,
                    max_y = excluded.max_y,
                    min_z = excluded.min_z,
                    max_z = excluded.max_z,
                    claim_fee = excluded.claim_fee,
                    upkeep_fee = excluded.upkeep_fee,
                    owner_uuid = NULL,
                    claimed_at = 0,
                    upkeep_next_at = 0,
                    active = 1
                """)) {
            ps.setString(1, areaKey);
            ps.setString(2, world);
            ps.setInt(3, minX);
            ps.setInt(4, maxX);
            ps.setInt(5, minY);
            ps.setInt(6, maxY);
            ps.setInt(7, minZ);
            ps.setInt(8, maxZ);
            ps.setLong(9, Math.max(0L, claimFee));
            ps.setLong(10, Math.max(0L, upkeepFee));
            ps.executeUpdate();
        }
    }

    /**
     * Deletes a shop area by key.
     *
     * @param c connection
     * @param areaKey area key
     * @return true if removed
     * @throws SQLException if delete fails
     */
    public boolean deleteArea(Connection c, String areaKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM shop_areas WHERE lower(area_key) = lower(?)")) {
            ps.setString(1, areaKey);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Lists all areas.
     *
     * @return areas
     * @throws SQLException if query fails
     */
    public List<ShopAreaRecord> listAreas() throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM shop_areas ORDER BY lower(area_key) ASC");
             ResultSet rs = ps.executeQuery()) {
            List<ShopAreaRecord> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapArea(rs));
            }
            return out;
        }
    }

    /**
     * Lists areas owned by a player.
     *
     * @param playerUuid player UUID
     * @return owned areas
     * @throws SQLException if query fails
     */
    public List<ShopAreaRecord> listAreasByOwner(String playerUuid) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM shop_areas
                     WHERE owner_uuid = ?
                     ORDER BY lower(area_key) ASC
                     """)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                List<ShopAreaRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapArea(rs));
                }
                return out;
            }
        }
    }

    /**
     * Finds area by key.
     *
     * @param areaKey area key
     * @return area row when present
     * @throws SQLException if query fails
     */
    public Optional<ShopAreaRecord> findAreaByKey(String areaKey) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM shop_areas
                     WHERE lower(area_key) = lower(?)
                     LIMIT 1
                     """)) {
            ps.setString(1, areaKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapArea(rs));
            }
        }
    }

    /**
     * Finds area containing a block coordinate.
     *
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return area when containing location
     * @throws SQLException if query fails
     */
    public Optional<ShopAreaRecord> findAreaContaining(String world, int x, int y, int z) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM shop_areas
                     WHERE world = ?
                       AND ? BETWEEN min_x AND max_x
                       AND ? BETWEEN min_y AND max_y
                       AND ? BETWEEN min_z AND max_z
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
                return Optional.of(mapArea(rs));
            }
        }
    }

    /**
     * Claims an area when it is not currently owned.
     *
     * @param c connection
     * @param areaId area id
     * @param ownerUuid owner UUID
     * @param claimedAt claim timestamp
     * @param upkeepNextAt upkeep due timestamp
     * @return true if claimed
     * @throws SQLException if update fails
     */
    public boolean claimArea(
            Connection c,
            long areaId,
            String ownerUuid,
            long claimedAt,
            long upkeepNextAt
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE shop_areas
                SET owner_uuid = ?, claimed_at = ?, upkeep_next_at = ?, active = 1
                WHERE id = ? AND (owner_uuid IS NULL OR owner_uuid = '')
                """)) {
            ps.setString(1, ownerUuid);
            ps.setLong(2, claimedAt);
            ps.setLong(3, upkeepNextAt);
            ps.setLong(4, areaId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Releases ownership for an area.
     *
     * @param c connection
     * @param areaId area id
     * @throws SQLException if update fails
     */
    public void unclaimArea(Connection c, long areaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE shop_areas
                SET owner_uuid = NULL, claimed_at = 0, upkeep_next_at = 0, active = 0
                WHERE id = ?
                """)) {
            ps.setLong(1, areaId);
            ps.executeUpdate();
        }
    }

    /**
     * Extends upkeep deadline for an owned area.
     *
     * @param c connection
     * @param areaId area id
     * @param upkeepNextAt next upkeep timestamp
     * @return true when updated
     * @throws SQLException if update fails
     */
    public boolean updateUpkeepNext(Connection c, long areaId, long upkeepNextAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE shop_areas
                SET upkeep_next_at = ?, active = 1
                WHERE id = ? AND owner_uuid IS NOT NULL AND owner_uuid <> ''
                """)) {
            ps.setLong(1, upkeepNextAt);
            ps.setLong(2, areaId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Lists claimed areas due for upkeep.
     *
     * @param now now timestamp
     * @return due areas
     * @throws SQLException if query fails
     */
    public List<ShopAreaRecord> listUpkeepDue(long now) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *
                     FROM shop_areas
                     WHERE owner_uuid IS NOT NULL
                       AND owner_uuid <> ''
                       AND upkeep_next_at > 0
                       AND upkeep_next_at <= ?
                     ORDER BY upkeep_next_at ASC
                     """)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<ShopAreaRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapArea(rs));
                }
                return out;
            }
        }
    }

    /**
     * Inserts or updates a shop chest mapping for an area.
     *
     * @param c connection
     * @param areaId area id
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @param ownerUuid owner UUID
     * @return chest id
     * @throws SQLException if write fails
     */
    public long upsertChest(
            Connection c,
            long areaId,
            String world,
            int x,
            int y,
            int z,
            String ownerUuid
    ) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement("""
                INSERT INTO shop_chests (area_id, world, x, y, z, owner_uuid)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(world, x, y, z) DO UPDATE SET
                    area_id = excluded.area_id,
                    owner_uuid = excluded.owner_uuid
                """)) {
            insert.setLong(1, areaId);
            insert.setString(2, world);
            insert.setInt(3, x);
            insert.setInt(4, y);
            insert.setInt(5, z);
            insert.setString(6, ownerUuid);
            insert.executeUpdate();
        }

        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id
                FROM shop_chests
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                LIMIT 1
                """)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : -1L;
            }
        }
    }

    /**
     * Finds chest mapping by location.
     *
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return chest row when present
     * @throws SQLException if query fails
     */
    public Optional<ShopChestRecord> findChestByLocation(String world, int x, int y, int z) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, area_id, world, x, y, z, owner_uuid
                     FROM shop_chests
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
                return Optional.of(new ShopChestRecord(
                        rs.getLong("id"),
                        rs.getLong("area_id"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("owner_uuid")
                ));
            }
        }
    }

    /**
     * Lists chest mappings owned by area.
     *
     * @param areaId area id
     * @return chest rows
     * @throws SQLException if query fails
     */
    public List<ShopChestRecord> listChestsForArea(long areaId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, area_id, world, x, y, z, owner_uuid
                     FROM shop_chests
                     WHERE area_id = ?
                     """)) {
            ps.setLong(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ShopChestRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ShopChestRecord(
                            rs.getLong("id"),
                            rs.getLong("area_id"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("owner_uuid")
                    ));
                }
                return out;
            }
        }
    }

    /**
     * Deletes all chest mappings (and cascaded listings) for an area.
     *
     * @param c connection
     * @param areaId area id
     * @throws SQLException if delete fails
     */
    public void deleteChestsForArea(Connection c, long areaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM shop_chests WHERE area_id = ?")) {
            ps.setLong(1, areaId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts or updates a slot listing for a chest.
     *
     * @param c connection
     * @param chestId chest id
     * @param slotIndex slot index
     * @param material material name
     * @param unitAmount item quantity per purchase
     * @param price price
     * @throws SQLException if write fails
     */
    public void upsertListing(
            Connection c,
            long chestId,
            int slotIndex,
            String material,
            int unitAmount,
            long price
    ) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shop_listings (chest_id, slot_index, material, unit_amount, price)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(chest_id, slot_index) DO UPDATE SET
                    material = excluded.material,
                    unit_amount = excluded.unit_amount,
                    price = excluded.price
                """)) {
            ps.setLong(1, chestId);
            ps.setInt(2, slotIndex);
            ps.setString(3, material);
            ps.setInt(4, unitAmount);
            ps.setLong(5, Math.max(0L, price));
            ps.executeUpdate();
        }
    }

    /**
     * Removes a listing from a chest slot.
     *
     * @param c connection
     * @param chestId chest id
     * @param slotIndex slot index
     * @return true if removed
     * @throws SQLException if write fails
     */
    public boolean removeListing(Connection c, long chestId, int slotIndex) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM shop_listings
                WHERE chest_id = ? AND slot_index = ?
                """)) {
            ps.setLong(1, chestId);
            ps.setInt(2, slotIndex);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Lists listings for a chest id.
     *
     * @param chestId chest id
     * @return listing rows
     * @throws SQLException if query fails
     */
    public List<ShopListingRecord> listListingsForChest(long chestId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, chest_id, slot_index, material, unit_amount, price
                     FROM shop_listings
                     WHERE chest_id = ?
                     ORDER BY slot_index ASC
                     """)) {
            ps.setLong(1, chestId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ShopListingRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ShopListingRecord(
                            rs.getLong("id"),
                            rs.getLong("chest_id"),
                            rs.getInt("slot_index"),
                            rs.getString("material"),
                            rs.getInt("unit_amount"),
                            rs.getLong("price")
                    ));
                }
                return out;
            }
        }
    }

    /**
     * Finds listing by id.
     *
     * @param listingId listing id
     * @return listing row when present
     * @throws SQLException if query fails
     */
    public Optional<ShopListingRecord> findListingById(long listingId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, chest_id, slot_index, material, unit_amount, price
                     FROM shop_listings
                     WHERE id = ?
                     LIMIT 1
                     """)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ShopListingRecord(
                        rs.getLong("id"),
                        rs.getLong("chest_id"),
                        rs.getInt("slot_index"),
                        rs.getString("material"),
                        rs.getInt("unit_amount"),
                        rs.getLong("price")
                ));
            }
        }
    }

    /**
     * Finds chest mapping by id.
     *
     * @param chestId chest id
     * @return chest row when present
     * @throws SQLException if query fails
     */
    public Optional<ShopChestRecord> findChestById(long chestId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, area_id, world, x, y, z, owner_uuid
                     FROM shop_chests
                     WHERE id = ?
                     LIMIT 1
                     """)) {
            ps.setLong(1, chestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ShopChestRecord(
                        rs.getLong("id"),
                        rs.getLong("area_id"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("owner_uuid")
                ));
            }
        }
    }

    private ShopAreaRecord mapArea(ResultSet rs) throws SQLException {
        return new ShopAreaRecord(
                rs.getLong("id"),
                rs.getString("area_key"),
                rs.getString("world"),
                rs.getInt("min_x"),
                rs.getInt("max_x"),
                rs.getInt("min_y"),
                rs.getInt("max_y"),
                rs.getInt("min_z"),
                rs.getInt("max_z"),
                rs.getLong("claim_fee"),
                rs.getLong("upkeep_fee"),
                rs.getString("owner_uuid"),
                rs.getLong("claimed_at"),
                rs.getLong("upkeep_next_at"),
                rs.getInt("active")
        );
    }
}
