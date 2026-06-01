package com.krishcpatel.realm.shop.service;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.economy.data.TransactionResult;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.shop.model.ShopAreaRecord;
import com.krishcpatel.realm.shop.model.ShopChestRecord;
import com.krishcpatel.realm.shop.model.ShopListingRecord;
import com.krishcpatel.realm.shop.repository.ShopRepository;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Business logic for shop areas, listings, and upkeep.
 */
public final class ShopService {
    private final Core core;
    private final DatabaseManager db;
    private final ShopRepository repo;
    private final TransactionManager tx;

    /**
     * Creates a shop service.
     *
     * @param core plugin core
     * @param db database manager
     * @param repo shop repository
     * @param tx transaction manager
     */
    public ShopService(Core core, DatabaseManager db, ShopRepository repo, TransactionManager tx) {
        this.core = core;
        this.db = db;
        this.repo = repo;
        this.tx = tx;
    }

    /**
     * Creates or replaces an admin area.
     *
     * @param areaKey area key
     * @param world world
     * @param pos1 pos1
     * @param pos2 pos2
     * @param claimFee claim fee
     * @param upkeepFee upkeep fee
     * @return result
     */
    public ActionResult createArea(
            String areaKey,
            String world,
            Location pos1,
            Location pos2,
            long claimFee,
            long upkeepFee
    ) {
        if (areaKey == null || areaKey.isBlank()) {
            return ActionResult.fail("Area key is required.");
        }
        if (pos1 == null || pos2 == null) {
            return ActionResult.fail("Set both area positions first.");
        }
        if (!pos1.getWorld().getName().equals(pos2.getWorld().getName())) {
            return ActionResult.fail("Area positions must be in the same world.");
        }

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        int worldMinY = pos1.getWorld().getMinHeight();
        int worldMaxY = pos1.getWorld().getMaxHeight() - 1;
        int minY = Math.min(worldMinY, worldMaxY);
        int maxY = Math.max(worldMinY, worldMaxY);

        try {
            List<ShopAreaRecord> areas = repo.listAreas();
            String normalized = areaKey.trim().toLowerCase(Locale.ROOT);
            for (ShopAreaRecord area : areas) {
                if (area.areaKey().equalsIgnoreCase(normalized)) {
                    continue;
                }
                if (!area.world().equals(world)) {
                    continue;
                }
                if (intersects(minX, maxX, minY, maxY, minZ, maxZ, area)) {
                    return ActionResult.fail("Area overlaps an existing shop area: " + area.areaKey());
                }
            }

            db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    repo.upsertArea(
                            c,
                            normalized,
                            world,
                            minX,
                            maxX,
                            minY,
                            maxY,
                            minZ,
                            maxZ,
                            claimFee,
                            upkeepFee
                    );
                }
            });
            return ActionResult.ok("Shop area '" + normalized + "' saved.");
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to create area");
            e.printStackTrace();
            return ActionResult.fail("Failed to create area.");
        }
    }

    /**
     * Removes an area.
     *
     * @param areaKey area key
     * @return result
     */
    public ActionResult removeArea(String areaKey) {
        try {
            boolean removed = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.deleteArea(c, areaKey);
                }
            });
            return removed
                    ? ActionResult.ok("Shop area removed.")
                    : ActionResult.fail("Shop area not found.");
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to remove area");
            e.printStackTrace();
            return ActionResult.fail("Failed to remove area.");
        }
    }

    /**
     * Lists all defined areas.
     *
     * @return areas
     */
    public List<ShopAreaRecord> listAreas() {
        try {
            return repo.listAreas();
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to list areas");
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Lists areas owned by a player.
     *
     * @param playerUuid player UUID
     * @return owned areas
     */
    public List<ShopAreaRecord> listOwnedAreas(String playerUuid) {
        try {
            return repo.listAreasByOwner(playerUuid);
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to list owned areas");
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Finds area by key.
     *
     * @param areaKey area key
     * @return area row
     */
    public Optional<ShopAreaRecord> findAreaByKey(String areaKey) {
        try {
            return repo.findAreaByKey(areaKey);
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to find area");
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Claims an area after payment has already been collected.
     *
     * @param playerUuid claimer UUID
     * @param areaKey area key
     * @return result
     */
    public ActionResult claimAreaAfterPayment(String playerUuid, String areaKey) {
        try {
            Optional<ShopAreaRecord> areaOpt = repo.findAreaByKey(areaKey);
            if (areaOpt.isEmpty()) {
                return ActionResult.fail("Shop area not found.");
            }
            ShopAreaRecord area = areaOpt.get();
            if (area.isClaimed()) {
                return ActionResult.fail("That area is already claimed.");
            }

            int maxOwned = Math.max(1, core.config().getInt("shops.claim.max-owned-areas", 1));
            if (repo.listAreasByOwner(playerUuid).size() >= maxOwned) {
                return ActionResult.fail("You already own the max number of shop areas.");
            }

            long now = System.currentTimeMillis();
            long next = now + upkeepIntervalMs();
            boolean claimed = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.claimArea(c, area.id(), playerUuid, now, next);
                }
            });
            if (!claimed) {
                return ActionResult.fail("Could not claim that area.");
            }
            return ActionResult.ok("Shop area '" + area.areaKey() + "' claimed.");
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to claim area");
            e.printStackTrace();
            return ActionResult.fail("Failed to claim area.");
        }
    }

    /**
     * Pays upkeep for an owned area after payment has been collected.
     *
     * @param playerUuid owner UUID
     * @param areaKey area key
     * @return result
     */
    public ActionResult payUpkeepAfterPayment(String playerUuid, String areaKey) {
        try {
            Optional<ShopAreaRecord> areaOpt = repo.findAreaByKey(areaKey);
            if (areaOpt.isEmpty()) {
                return ActionResult.fail("Area not found.");
            }
            ShopAreaRecord area = areaOpt.get();
            if (!playerUuid.equals(area.ownerUuid())) {
                return ActionResult.fail("You do not own this area.");
            }

            long base = Math.max(area.upkeepNextAt(), System.currentTimeMillis());
            long next = base + upkeepIntervalMs();
            boolean ok = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.updateUpkeepNext(c, area.id(), next);
                }
            });
            if (!ok) {
                return ActionResult.fail("Could not apply upkeep.");
            }
            return ActionResult.ok("Upkeep paid for area '" + area.areaKey() + "'.");
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to pay upkeep");
            e.printStackTrace();
            return ActionResult.fail("Failed to pay upkeep.");
        }
    }

    /**
     * Sets listing for a chest slot owned by the player.
     *
     * @param playerUuid owner UUID
     * @param chestBlock chest block
     * @param slot slot index
     * @param price listing price
     * @return result
     */
    public ActionResult setListing(String playerUuid, Block chestBlock, int slot, long price) {
        if (!(chestBlock.getState() instanceof Container container)) {
            return ActionResult.fail("Target block is not a container.");
        }
        if (slot < 0 || slot >= container.getInventory().getSize()) {
            return ActionResult.fail("Slot is out of range for this container.");
        }
        var sample = container.getInventory().getItem(slot);
        if (sample == null || sample.getType() == Material.AIR) {
            return ActionResult.fail("Put a sample item in that chest slot first.");
        }
        return setListing(
                playerUuid,
                chestBlock.getWorld().getName(),
                chestBlock.getX(),
                chestBlock.getY(),
                chestBlock.getZ(),
                slot,
                sample.getType().name(),
                Math.max(1, sample.getAmount()),
                price
        );
    }

    /**
     * Sets listing for a chest slot using resolved chest location and item data.
     *
     * @param playerUuid owner UUID
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @param slot chest slot
     * @param material material name
     * @param unitAmount quantity sold per purchase
     * @param price price
     * @return result
     */
    public ActionResult setListing(
            String playerUuid,
            String world,
            int x,
            int y,
            int z,
            int slot,
            String material,
            int unitAmount,
            long price
    ) {
        try {
            if (price <= 0L) {
                return ActionResult.fail("Price must be greater than 0.");
            }
            if (material == null || material.isBlank()) {
                return ActionResult.fail("Sample item is missing.");
            }
            if (Material.matchMaterial(material) == null) {
                return ActionResult.fail("Sample item material is invalid.");
            }
            if (slot < 0) {
                return ActionResult.fail("Slot is out of range.");
            }

            Optional<ShopAreaRecord> areaOpt = repo.findAreaContaining(world, x, y, z);
            if (areaOpt.isEmpty()) {
                return ActionResult.fail("This chest is not inside a shop area.");
            }
            ShopAreaRecord area = areaOpt.get();
            if (!playerUuid.equals(area.ownerUuid())) {
                return ActionResult.fail("You do not own this shop area.");
            }

            int safeUnitAmount = Math.max(1, unitAmount);
            db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        long chestId = repo.upsertChest(
                                c,
                                area.id(),
                                world,
                                x,
                                y,
                                z,
                                playerUuid
                        );
                        if (chestId <= 0L) {
                            c.rollback();
                            return null;
                        }
                        repo.upsertListing(c, chestId, slot, material, safeUnitAmount, price);
                        c.commit();
                        return null;
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });
            return ActionResult.ok("Listing set for slot " + (slot + 1) + " at $" + price + ".");
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to set listing");
            e.printStackTrace();
            return ActionResult.fail("Failed to set listing.");
        }
    }

    /**
     * Removes listing for a chest slot owned by the player.
     *
     * @param playerUuid owner UUID
     * @param chestBlock chest block
     * @param slot slot
     * @return result
     */
    public ActionResult removeListing(String playerUuid, Block chestBlock, int slot) {
        return removeListing(
                playerUuid,
                chestBlock.getWorld().getName(),
                chestBlock.getX(),
                chestBlock.getY(),
                chestBlock.getZ(),
                slot
        );
    }

    /**
     * Removes listing for a chest slot using coordinates.
     *
     * @param playerUuid owner UUID
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @param slot slot index
     * @return result
     */
    public ActionResult removeListing(String playerUuid, String world, int x, int y, int z, int slot) {
        try {
            Optional<ShopChestRecord> chestOpt = repo.findChestByLocation(world, x, y, z);
            if (chestOpt.isEmpty()) {
                return ActionResult.fail("That chest has no shop listings.");
            }
            ShopChestRecord chest = chestOpt.get();
            if (!playerUuid.equals(chest.ownerUuid())) {
                return ActionResult.fail("You do not own this shop chest.");
            }
            boolean removed = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.removeListing(c, chest.id(), slot);
                }
            });
            return removed
                    ? ActionResult.ok("Listing removed from slot " + slot + ".")
                    : ActionResult.fail("No listing exists for that slot.");
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to remove listing");
            e.printStackTrace();
            return ActionResult.fail("Failed to remove listing.");
        }
    }

    /**
     * Finds shop area at a world coordinate.
     *
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return containing area when present
     */
    public Optional<ShopAreaRecord> findAreaContaining(String world, int x, int y, int z) {
        try {
            return repo.findAreaContaining(world, x, y, z);
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to resolve containing area");
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Finds shop area containing a location.
     *
     * @param location location
     * @return containing area when present
     */
    public Optional<ShopAreaRecord> findAreaContaining(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return findAreaContaining(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    /**
     * Ensures a chest location belongs to an area owned by the player.
     *
     * @param playerUuid player UUID
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return validation result
     */
    public ActionResult ensureOwnedShopLocation(String playerUuid, String world, int x, int y, int z) {
        Optional<ShopAreaRecord> areaOpt = findAreaContaining(world, x, y, z);
        if (areaOpt.isEmpty()) {
            return ActionResult.fail("This chest is not inside a shop area.");
        }
        ShopAreaRecord area = areaOpt.get();
        if (!playerUuid.equals(area.ownerUuid())) {
            return ActionResult.fail("You do not own this shop area.");
        }
        return ActionResult.ok(area.areaKey());
    }

    /**
     * Loads listings by chest location.
     *
     * @param world world
     * @param x x
     * @param y y
     * @param z z
     * @return listing views
     */
    public List<ShopListingView> listingsForChestLocation(String world, int x, int y, int z) {
        try {
            Optional<ShopChestRecord> chestOpt = repo.findChestByLocation(world, x, y, z);
            if (chestOpt.isEmpty()) {
                return List.of();
            }
            ShopChestRecord chest = chestOpt.get();
            Optional<ShopAreaRecord> areaOpt = findAreaById(chest.areaId());
            String areaKey = areaOpt.map(ShopAreaRecord::areaKey).orElse("unknown");
            List<ShopListingRecord> listings = repo.listListingsForChest(chest.id());

            List<ShopListingView> out = new ArrayList<>();
            for (ShopListingRecord listing : listings) {
                out.add(new ShopListingView(chest, areaKey, listing));
            }
            return out;
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to load chest listings by location");
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Loads shop listings for a chest location.
     *
     * @param chestBlock chest block
     * @return listing view rows
     */
    public List<ShopListingView> listingsForChest(Block chestBlock) {
        try {
            Optional<ShopChestRecord> chestOpt = repo.findChestByLocation(
                    chestBlock.getWorld().getName(),
                    chestBlock.getX(),
                    chestBlock.getY(),
                    chestBlock.getZ()
            );
            if (chestOpt.isEmpty()) {
                return List.of();
            }
            ShopChestRecord chest = chestOpt.get();
            Optional<ShopAreaRecord> areaOpt = findAreaById(chest.areaId());
            String areaKey = areaOpt.map(ShopAreaRecord::areaKey).orElse("unknown");
            List<ShopListingRecord> listings = repo.listListingsForChest(chest.id());

            List<ShopListingView> out = new ArrayList<>();
            for (ShopListingRecord listing : listings) {
                out.add(new ShopListingView(chest, areaKey, listing));
            }
            return out;
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed to load chest listings");
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Gets a listing by id.
     *
     * @param listingId listing id
     * @return listing row
     */
    public Optional<ShopListingRecord> findListingById(long listingId) {
        try {
            return repo.findListingById(listingId);
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to find listing");
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Gets chest row for a listing.
     *
     * @param chestId chest id
     * @return chest row
     */
    public Optional<ShopChestRecord> findChestById(long chestId) {
        try {
            return repo.findChestById(chestId);
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to find chest");
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Gets area row by id.
     *
     * @param areaId area id
     * @return area row
     */
    public Optional<ShopAreaRecord> findAreaById(long areaId) {
        try {
            for (ShopAreaRecord area : repo.listAreas()) {
                if (area.id() == areaId) {
                    return Optional.of(area);
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            core.getLogger().severe("[shop] Failed to find area by id");
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Charges upkeep automatically and unclaims areas that cannot pay.
     */
    public void runUpkeepTick() {
        long now = System.currentTimeMillis();
        try {
            List<ShopAreaRecord> due = repo.listUpkeepDue(now);
            for (ShopAreaRecord area : due) {
                if (area.ownerUuid() == null || area.ownerUuid().isBlank()) {
                    continue;
                }

                long fee = Math.max(0L, area.upkeepFee());
                if (fee <= 0L) {
                    db.executeWrite(() -> {
                        try (Connection c = db.getConnection()) {
                            repo.updateUpkeepNext(c, area.id(), now + upkeepIntervalMs());
                        }
                    });
                    continue;
                }

                TransactionResult burn = tx.burn(
                        area.ownerUuid(),
                        fee,
                        MoneySource.UPKEEP,
                        "shop:" + area.areaKey(),
                        "Shop area upkeep",
                        "SYSTEM"
                );

                if (burn.success()) {
                    db.executeWrite(() -> {
                        try (Connection c = db.getConnection()) {
                            repo.updateUpkeepNext(c, area.id(), now + upkeepIntervalMs());
                        }
                    });
                    continue;
                }

                db.executeWrite(() -> {
                    try (Connection c = db.getConnection()) {
                        boolean oldAuto = c.getAutoCommit();
                        c.setAutoCommit(false);
                        try {
                            repo.deleteChestsForArea(c, area.id());
                            repo.unclaimArea(c, area.id());
                            c.commit();
                            return null;
                        } catch (SQLException ex) {
                            c.rollback();
                            throw ex;
                        } finally {
                            c.setAutoCommit(oldAuto);
                        }
                    }
                });
            }
        } catch (Exception e) {
            core.getLogger().severe("[shop] Failed upkeep tick");
            e.printStackTrace();
        }
    }

    /**
     * Collects ownership claim fee from a player.
     *
     * @param playerUuid owner UUID
     * @param amount amount
     * @param reference area key
     * @return transaction result
     */
    public TransactionResult collectClaimFee(String playerUuid, long amount, String reference) throws SQLException {
        return tx.burn(
                playerUuid,
                amount,
                MoneySource.SHOP,
                "shop:claim:" + reference,
                "Shop area claim fee",
                playerUuid
        );
    }

    /**
     * Collects upkeep fee from a player.
     *
     * @param playerUuid payer UUID
     * @param amount amount
     * @param reference area key
     * @return transaction result
     * @throws SQLException if transaction fails
     */
    public TransactionResult collectUpkeepFee(String playerUuid, long amount, String reference) throws SQLException {
        return tx.burn(
                playerUuid,
                amount,
                MoneySource.UPKEEP,
                "shop:upkeep:" + reference,
                "Shop area upkeep payment",
                playerUuid
        );
    }

    /**
     * Transfers purchase price from buyer to seller.
     *
     * @param buyerUuid buyer UUID
     * @param sellerUuid seller UUID
     * @param amount amount
     * @param listingId listing id
     * @return transaction result
     * @throws SQLException if transfer fails
     */
    public TransactionResult transferPurchase(String buyerUuid, String sellerUuid, long amount, long listingId) throws SQLException {
        return tx.transfer(
                buyerUuid,
                sellerUuid,
                amount,
                MoneySource.SHOP,
                "shop:listing:" + listingId,
                "Shop purchase",
                buyerUuid
        );
    }

    /**
     * Returns upkeep interval in milliseconds.
     *
     * @return interval millis
     */
    public long upkeepIntervalMs() {
        long hours = Math.max(1L, core.config().getLong("shops.upkeep.interval-hours", 24L));
        return hours * 3_600_000L;
    }

    private boolean intersects(
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            ShopAreaRecord existing
    ) {
        return minX <= existing.maxX() && maxX >= existing.minX()
                && minY <= existing.maxY() && maxY >= existing.minY()
                && minZ <= existing.maxZ() && maxZ >= existing.minZ();
    }

    /**
     * Generic action result.
     *
     * @param success true when successful
     * @param message response text
     */
    public record ActionResult(boolean success, String message) {
        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }
    }

    /**
     * Resolved listing view with chest context.
     *
     * @param chest chest row
     * @param areaKey area key
     * @param listing listing row
     */
    public record ShopListingView(
            ShopChestRecord chest,
            String areaKey,
            ShopListingRecord listing
    ) {
    }
}
