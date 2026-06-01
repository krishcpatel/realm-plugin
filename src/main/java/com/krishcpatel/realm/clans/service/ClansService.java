package com.krishcpatel.realm.clans.service;

import com.krishcpatel.realm.clans.model.ClanClaimRecord;
import com.krishcpatel.realm.clans.model.ClanMemberRecord;
import com.krishcpatel.realm.clans.model.ClanProtectedStorageRecord;
import com.krishcpatel.realm.clans.model.ClanRecord;
import com.krishcpatel.realm.clans.repository.ClansRepository;
import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Business logic for clans.
 */
public final class ClansService {
    private final Core core;
    private final DatabaseManager db;
    private final ClansRepository repo;
    private final EconomyRepository economy;
    private final LedgerRepository ledger;

    private final AtomicReference<List<ClanClaimRecord>> claimsCache = new AtomicReference<>(List.of());
    private final AtomicReference<Map<StorageKey, ClanProtectedStorageRecord>> storageCache =
            new AtomicReference<>(Map.of());
    private final AtomicReference<Map<Long, ClanRecord>> clansCache = new AtomicReference<>(Map.of());
    private final Map<UUID, Long> lastNotifiedClaim = new ConcurrentHashMap<>();

    /**
     * Creates a clans service.
     *
     * @param core plugin core
     * @param db database manager
     * @param repo clans repository
     * @param economy economy repository
     * @param ledger ledger repository
     */
    public ClansService(
            Core core,
            DatabaseManager db,
            ClansRepository repo,
            EconomyRepository economy,
            LedgerRepository ledger
    ) {
        this.core = core;
        this.db = db;
        this.repo = repo;
        this.economy = economy;
        this.ledger = ledger;
    }

    /**
     * Refreshes read caches.
     *
     * @throws SQLException if loading fails
     */
    public void refreshCaches() throws SQLException {
        List<ClanClaimRecord> claims = repo.listClaims();
        List<ClanProtectedStorageRecord> storage = repo.listProtectedStorage();
        List<ClanRecord> clans = repo.listClans();

        claimsCache.set(claims);
        Map<StorageKey, ClanProtectedStorageRecord> storageMap = new HashMap<>();
        for (ClanProtectedStorageRecord row : storage) {
            storageMap.put(new StorageKey(row.world(), row.x(), row.y(), row.z()), row);
        }
        storageCache.set(Map.copyOf(storageMap));

        Map<Long, ClanRecord> clanMap = clans.stream()
                .collect(Collectors.toMap(ClanRecord::id, c -> c));
        clansCache.set(Map.copyOf(clanMap));
    }

    /**
     * Finds clan for a player.
     *
     * @param playerUuid player UUID string
     * @return clan when present
     */
    public Optional<ClanRecord> findClanByPlayer(String playerUuid) {
        try {
            return repo.findClanByPlayer(playerUuid);
        } catch (SQLException e) {
            core.getLogger().severe("[clans] Failed to find clan for " + playerUuid);
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Finds a clan by tag.
     *
     * @param tag clan tag
     * @return clan when present
     */
    public Optional<ClanRecord> findClanByTag(String tag) {
        try {
            return repo.findClanByTag(tag);
        } catch (SQLException e) {
            core.getLogger().severe("[clans] Failed to find clan " + tag);
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Creates a clan and leader membership.
     *
     * @param creatorUuid creator UUID
     * @param tag tag
     * @param name name
     * @return operation result
     */
    public ActionResult createClan(String creatorUuid, String tag, String name) {
        try {
            Optional<ClanRecord> existing = repo.findClanByPlayer(creatorUuid);
            if (existing.isPresent()) {
                return ActionResult.fail("You are already in a clan.");
            }

            String cleanedTag = sanitizeTag(tag);
            if (cleanedTag.length() < 2 || cleanedTag.length() > 8) {
                return ActionResult.fail("Clan tag must be 2-8 letters/numbers.");
            }
            if (name == null || name.isBlank() || name.length() > 32) {
                return ActionResult.fail("Clan name must be 1-32 characters.");
            }

            int memberCap = levelMemberCap(1);
            int storageCap = levelStorageCap(1);
            long upkeepNextAt = System.currentTimeMillis() + upkeepIntervalMs();

            long clanId = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        long now = System.currentTimeMillis();
                        long inserted = repo.insertClan(c, cleanedTag, name.trim(), creatorUuid, memberCap, storageCap, upkeepNextAt, now);
                        if (inserted <= 0L) {
                            c.rollback();
                            return -1L;
                        }
                        repo.insertMember(c, inserted, creatorUuid, "LEADER", now, now);
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

            if (clanId <= 0L) {
                return ActionResult.fail("Could not create clan. Tag/name may already exist.");
            }

            refreshCaches();
            return ActionResult.ok("Clan created: [" + cleanedTag + "] " + name.trim());
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to create clan");
            e.printStackTrace();
            return ActionResult.fail("Failed to create clan.");
        }
    }

    /**
     * Joins a clan by tag.
     *
     * @param playerUuid player UUID
     * @param tag target clan tag
     * @return result
     */
    public ActionResult joinClan(String playerUuid, String tag) {
        try {
            if (repo.findClanByPlayer(playerUuid).isPresent()) {
                return ActionResult.fail("You are already in a clan.");
            }

            Optional<ClanRecord> targetOpt = repo.findClanByTag(tag);
            if (targetOpt.isEmpty()) {
                return ActionResult.fail("Clan not found.");
            }
            ClanRecord clan = targetOpt.get();

            return db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        int members = repo.countMembers(c, clan.id());
                        if (members >= clan.memberCap()) {
                            c.rollback();
                            return ActionResult.fail("Clan is full.");
                        }

                        long now = System.currentTimeMillis();
                        long fee = Math.max(0L, clan.feeAmount());
                        String feeType = normalizeFeeType(clan.feeType());
                        long lastPaid = 0L;

                        if (fee > 0L && !"NONE".equals(feeType)) {
                            economy.ensureAccount(c, playerUuid);
                            boolean charged = economy.subtractBalanceFloorZero(c, playerUuid, fee);
                            if (!charged) {
                                c.rollback();
                                return ActionResult.fail("You do not have enough money for the clan join fee.");
                            }
                            repo.addBank(c, clan.id(), fee);
                            lastPaid = now;
                            ledger.insertLedgerRow(
                                    c,
                                    now,
                                    "BURN",
                                    fee,
                                    playerUuid,
                                    null,
                                    MoneySource.UPKEEP.name(),
                                    "clan:" + clan.id(),
                                    "Clan member fee on join",
                                    playerUuid
                            );
                        }

                        repo.insertMember(c, clan.id(), playerUuid, "MEMBER", now, lastPaid);
                        c.commit();
                        return ActionResult.ok("Joined clan [" + clan.tag() + "].");
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to join clan");
            e.printStackTrace();
            return ActionResult.fail("Failed to join clan.");
        } finally {
            tryRefreshCaches();
        }
    }

    /**
     * Leaves current clan. If leader leaves and no members remain, clan is deleted.
     *
     * @param playerUuid player UUID
     * @return result
     */
    public ActionResult leaveClan(String playerUuid) {
        try {
            Optional<ClanMemberRecord> memberOpt = repo.findMember(playerUuid);
            if (memberOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanMemberRecord member = memberOpt.get();
            Optional<ClanRecord> clanOpt = repo.findClanById(member.clanId());
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("Clan not found.");
            }
            ClanRecord clan = clanOpt.get();

            return db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        repo.removeMember(c, clan.id(), playerUuid);
                        List<ClanMemberRecord> members = repo.listMembers(c, clan.id());
                        if (members.isEmpty()) {
                            repo.deleteClan(c, clan.id());
                            c.commit();
                            return ActionResult.ok("You left the clan. The clan was deleted because it became empty.");
                        }

                        if (playerUuid.equals(clan.leaderUuid())) {
                            ClanMemberRecord successor = members.stream()
                                    .min(Comparator.comparingLong(ClanMemberRecord::joinedAt))
                                    .orElse(members.get(0));
                            repo.updateLeader(c, clan.id(), successor.playerUuid(), System.currentTimeMillis());
                            repo.updateMemberRole(c, clan.id(), successor.playerUuid(), "LEADER");
                        }
                        c.commit();
                        return ActionResult.ok("You left clan [" + clan.tag() + "].");
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to leave clan");
            e.printStackTrace();
            return ActionResult.fail("Failed to leave clan.");
        } finally {
            tryRefreshCaches();
        }
    }

    /**
     * Disbands the clan owned by the leader.
     *
     * @param leaderUuid leader UUID
     * @return result
     */
    public ActionResult disbandClan(String leaderUuid) {
        try {
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(leaderUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();
            if (!leaderUuid.equals(clan.leaderUuid())) {
                return ActionResult.fail("Only the clan leader can disband the clan.");
            }

            boolean deleted = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.deleteClan(c, clan.id());
                }
            });
            if (!deleted) {
                return ActionResult.fail("Could not disband clan.");
            }

            refreshCaches();
            return ActionResult.ok("Clan [" + clan.tag() + "] disbanded.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to disband clan");
            e.printStackTrace();
            return ActionResult.fail("Failed to disband clan.");
        }
    }

    /**
     * Sets clan fee settings.
     *
     * @param playerUuid actor UUID
     * @param type fee type
     * @param amount fee amount
     * @return result
     */
    public ActionResult setClanFee(String playerUuid, String type, long amount) {
        try {
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(playerUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();
            if (!playerUuid.equals(clan.leaderUuid())) {
                return ActionResult.fail("Only the clan leader can set member fees.");
            }

            String normalized = normalizeFeeType(type);
            if (!normalized.equals("NONE")
                    && !normalized.equals("ONE_TIME")
                    && !normalized.equals("DAILY")
                    && !normalized.equals("WEEKLY")) {
                return ActionResult.fail("Fee type must be none, one_time, daily, or weekly.");
            }

            long safeAmount = Math.max(0L, amount);
            db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    repo.updateFeeSettings(c, clan.id(), normalized, safeAmount, System.currentTimeMillis());
                }
            });
            refreshCaches();
            return ActionResult.ok("Clan member fee updated.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to set clan fee");
            e.printStackTrace();
            return ActionResult.fail("Failed to set clan fee.");
        }
    }

    /**
     * Upgrades the clan by one level.
     *
     * @param leaderUuid actor UUID
     * @return result
     */
    public ActionResult upgradeClan(String leaderUuid) {
        try {
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(leaderUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();
            if (!leaderUuid.equals(clan.leaderUuid())) {
                return ActionResult.fail("Only the leader can upgrade the clan.");
            }

            int current = clan.level();
            int maxLevel = maxLevel();
            if (current >= maxLevel) {
                return ActionResult.fail("Clan is already max level.");
            }

            int next = current + 1;
            long cost = levelUpgradeCost(next);
            boolean updated = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        boolean paid = repo.subtractBankIfEnough(c, clan.id(), cost);
                        if (!paid) {
                            c.rollback();
                            return false;
                        }
                        repo.updateLevelAndCaps(
                                c,
                                clan.id(),
                                next,
                                levelMemberCap(next),
                                levelStorageCap(next),
                                System.currentTimeMillis()
                        );
                        c.commit();
                        return true;
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });
            if (!updated) {
                return ActionResult.fail("Clan bank does not have enough money for the upgrade.");
            }
            refreshCaches();
            return ActionResult.ok("Clan upgraded to level " + next + ".");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to upgrade clan");
            e.printStackTrace();
            return ActionResult.fail("Failed to upgrade clan.");
        }
    }

    /**
     * Upgrades the clan by one level after an external payment has already been collected.
     *
     * @param leaderUuid actor UUID
     * @param paidAmount amount collected from the payer
     * @return result
     */
    public ActionResult upgradeClanAfterPayment(String leaderUuid, long paidAmount) {
        try {
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(leaderUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();
            if (!leaderUuid.equals(clan.leaderUuid())) {
                return ActionResult.fail("Only the leader can upgrade the clan.");
            }

            int current = clan.level();
            int maxLevel = maxLevel();
            if (current >= maxLevel) {
                return ActionResult.fail("Clan is already max level.");
            }

            int next = current + 1;
            long expectedCost = levelUpgradeCost(next);
            if (paidAmount != expectedCost) {
                return ActionResult.fail("Upgrade payment amount no longer matches the current upgrade cost.");
            }

            db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    repo.updateLevelAndCaps(
                            c,
                            clan.id(),
                            next,
                            levelMemberCap(next),
                            levelStorageCap(next),
                            System.currentTimeMillis()
                    );
                }
            });

            refreshCaches();
            return ActionResult.ok("Clan upgraded to level " + next + ".");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to upgrade clan after payment");
            e.printStackTrace();
            return ActionResult.fail("Failed to upgrade clan.");
        }
    }

    /**
     * Saves the clan banner signature from an item in hand.
     *
     * @param playerUuid player UUID
     * @param item item in hand
     * @return result
     */
    public ActionResult saveFlagSignature(String playerUuid, ItemStack item) {
        try {
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(playerUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();
            if (!playerUuid.equals(clan.leaderUuid())) {
                return ActionResult.fail("Only the leader can set the clan flag.");
            }
            String signature = signatureFromItem(item);
            if (signature == null) {
                return ActionResult.fail("Hold a banner item to set the clan flag.");
            }

            db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    repo.updateFlagSignature(c, clan.id(), signature, System.currentTimeMillis());
                }
            });
            refreshCaches();
            return ActionResult.ok("Clan flag pattern saved.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to save clan flag");
            e.printStackTrace();
            return ActionResult.fail("Failed to save clan flag.");
        }
    }

    /**
     * Adds protected storage at a block location for the player's clan.
     *
     * @param playerUuid player UUID
     * @param world world name
     * @param x block x
     * @param y block y
     * @param z block z
     * @return result
     */
    public ActionResult addProtectedStorage(String playerUuid, String world, int x, int y, int z) {
        try {
            if (world == null || world.isBlank()) {
                return ActionResult.fail("Invalid storage world.");
            }

            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(playerUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();

            if (!isWithinAnyClanClaim(world, x, y, z, clan.id(), storageRadius())) {
                return ActionResult.fail("That storage is outside your clan flag radius.");
            }

            boolean inserted = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        int count = repo.countProtectedStorage(c, clan.id());
                        if (count >= clan.protectedStorageCap()) {
                            c.rollback();
                            return false;
                        }
                        repo.insertProtectedStorage(
                                c,
                                clan.id(),
                                world,
                                x,
                                y,
                                z,
                                playerUuid,
                                System.currentTimeMillis()
                        );
                        c.commit();
                        return true;
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });
            if (!inserted) {
                return ActionResult.fail("Protected storage cap reached for your clan level.");
            }

            refreshCaches();
            return ActionResult.ok("Protected storage added.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to add protected storage");
            e.printStackTrace();
            return ActionResult.fail("Failed to add protected storage.");
        }
    }

    /**
     * Removes protected storage at a block location.
     *
     * @param playerUuid player UUID
     * @param world world name
     * @param x block x
     * @param y block y
     * @param z block z
     * @return result
     */
    public ActionResult removeProtectedStorage(String playerUuid, String world, int x, int y, int z) {
        try {
            if (world == null || world.isBlank()) {
                return ActionResult.fail("Invalid storage world.");
            }
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(playerUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();
            boolean removed = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    Optional<ClanProtectedStorageRecord> storage = repo.findProtectedStorageAt(
                            world,
                            x,
                            y,
                            z
                    );
                    if (storage.isEmpty() || storage.get().clanId() != clan.id()) {
                        return false;
                    }
                    return repo.deleteProtectedStorageAt(
                            c,
                            world,
                            x,
                            y,
                            z
                    );
                }
            });
            if (!removed) {
                return ActionResult.fail("That block is not protected by your clan.");
            }
            refreshCaches();
            return ActionResult.ok("Protected storage removed.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to remove protected storage");
            e.printStackTrace();
            return ActionResult.fail("Failed to remove protected storage.");
        }
    }

    /**
     * Handles a banner block place and possibly creates a clan claim.
     *
     * @param player placing player
     * @param placedBlock placed block
     * @param itemInHand item used to place
     * @return result
     */
    public ActionResult handleFlagPlacement(Player player, Block placedBlock, ItemStack itemInHand) {
        try {
            if (!isBannerBlock(placedBlock.getType())) {
                return ActionResult.fail("Not a banner.");
            }
            String playerUuid = player.getUniqueId().toString();
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(playerUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();

            String expected = clan.flagSignature();
            if (expected == null || expected.isBlank()) {
                return ActionResult.fail("Your clan does not have a saved flag pattern yet.");
            }

            String placedSignature = signatureFromItem(itemInHand);
            if (placedSignature == null || !expected.equals(placedSignature)) {
                return ActionResult.fail("Placed banner does not match your clan flag pattern.");
            }

            if (tooCloseToSpawn(placedBlock.getLocation())) {
                return ActionResult.fail("This claim is too close to spawn.");
            }

            long proximity = claimProximityRadius();
            ClanClaimRecord nearby = findNearestClaim(placedBlock.getLocation(), proximity);
            if (nearby != null && nearby.clanId() != clan.id()) {
                return ActionResult.fail("Another clan claim is too close to this spot.");
            }

            int currentClaims = (int) claimsCache.get().stream().filter(c -> c.clanId() == clan.id()).count();
            int maxClaims = Math.max(1, core.config().getInt("clans.claim.max-claims-per-clan", 1));
            if (currentClaims >= maxClaims) {
                return ActionResult.fail("Your clan has reached its flag claim limit.");
            }

            long claimId = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.insertClaim(
                            c,
                            clan.id(),
                            placedBlock.getWorld().getName(),
                            placedBlock.getX(),
                            placedBlock.getY(),
                            placedBlock.getZ(),
                            playerUuid,
                            System.currentTimeMillis()
                    );
                }
            });

            if (claimId <= 0L) {
                return ActionResult.fail("Failed to create clan claim.");
            }

            refreshCaches();
            return ActionResult.ok("Clan claim created at this flag.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed handling flag placement");
            e.printStackTrace();
            return ActionResult.fail("Failed to create claim.");
        }
    }

    /**
     * Removes a claim by id.
     *
     * @param actorUuid actor UUID
     * @param claimId claim id
     * @param adminForce true when admin bypass is allowed
     * @return result
     */
    public ActionResult removeClaim(String actorUuid, long claimId, boolean adminForce) {
        try {
            Optional<ClanMemberRecord> member = repo.findMember(actorUuid);
            ClanClaimRecord target = claimsCache.get().stream()
                    .filter(row -> row.id() == claimId)
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return ActionResult.fail("Claim not found.");
            }

            boolean removed = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    if (!adminForce) {
                        if (member.isEmpty() || member.get().clanId() != target.clanId()) {
                            return false;
                        }
                    }
                    return repo.deleteClaimById(c, claimId);
                }
            });
            if (!removed) {
                return ActionResult.fail("Could not remove claim.");
            }
            refreshCaches();
            return ActionResult.ok("Claim removed.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed to remove claim");
            e.printStackTrace();
            return ActionResult.fail("Failed to remove claim.");
        }
    }

    /**
     * Removes claims near spawn across worlds.
     *
     * @param radius radius from spawn
     * @return number removed
     */
    public int purgeClaimsNearSpawn(double radius) {
        int removed = 0;
        try {
            List<ClanClaimRecord> snapshot = claimsCache.get();
            for (ClanClaimRecord claim : snapshot) {
                World world = core.getServer().getWorld(claim.world());
                if (world == null) {
                    continue;
                }
                Location spawn = world.getSpawnLocation();
                Location claimLoc = new Location(world, claim.x() + 0.5, claim.y(), claim.z() + 0.5);
                if (spawn.distanceSquared(claimLoc) <= radius * radius) {
                    boolean deleted = db.executeWrite(() -> {
                        try (Connection c = db.getConnection()) {
                            return repo.deleteClaimById(c, claim.id());
                        }
                    });
                    if (deleted) {
                        removed++;
                    }
                }
            }
            refreshCaches();
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed purging claims near spawn");
            e.printStackTrace();
        }
        return removed;
    }

    /**
     * Handles claim banner break.
     *
     * @param player player breaking
     * @param block block broken
     * @return result
     */
    public ActionResult handleClaimBreak(Player player, Block block) {
        ClanClaimRecord claim = claimAt(block.getLocation());
        if (claim == null) {
            return ActionResult.fail("No claim here.");
        }

        String playerUuid = player.getUniqueId().toString();
        try {
            Optional<ClanMemberRecord> member = repo.findMember(playerUuid);
            boolean sameClan = member.isPresent() && member.get().clanId() == claim.clanId();
            boolean admin = player.hasPermission("realm.clans.admin");
            if (!sameClan && !admin) {
                return ActionResult.fail("You cannot break another clan's flag.");
            }
            boolean removed = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    return repo.deleteClaimById(c, claim.id());
                }
            });
            if (!removed) {
                return ActionResult.fail("Could not remove claim.");
            }
            refreshCaches();
            return ActionResult.ok("Clan claim removed.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed claim break handling");
            e.printStackTrace();
            return ActionResult.fail("Failed to remove claim.");
        }
    }

    /**
     * Returns true when player can access the container location.
     *
     * @param playerUuid player UUID
     * @param block block
     * @return true if access allowed
     */
    public boolean canAccessStorage(String playerUuid, Block block) {
        ClanProtectedStorageRecord storage = storageCache.get().get(new StorageKey(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        ));
        if (storage == null) {
            return true;
        }
        try {
            Optional<ClanMemberRecord> member = repo.findMember(playerUuid);
            return member.isPresent() && member.get().clanId() == storage.clanId();
        } catch (SQLException e) {
            core.getLogger().severe("[clans] Failed to check storage access");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Returns true when player can break storage at location.
     *
     * @param playerUuid player UUID
     * @param block block
     * @return true if break is allowed
     */
    public boolean canBreakStorage(String playerUuid, Block block) {
        return canAccessStorage(playerUuid, block);
    }

    /**
     * Sends claim vicinity messages when crossing clan claim boundaries.
     *
     * @param player player
     * @param to destination location
     */
    public void handleVicinityTick(Player player, Location to) {
        if (to == null || to.getWorld() == null) {
            return;
        }
        ClanClaimRecord claim = findNearestClaim(to, notifyRadius());
        UUID playerId = player.getUniqueId();
        long newClaimId = claim == null ? -1L : claim.id();
        Long previous = lastNotifiedClaim.get(playerId);
        if (previous != null && previous == newClaimId) {
            return;
        }
        lastNotifiedClaim.put(playerId, newClaimId);

        if (claim == null) {
            player.sendActionBar(Component.text(""));
            return;
        }

        ClanRecord clan = clansCache.get().get(claim.clanId());
        if (clan == null) {
            return;
        }
        player.sendActionBar(Component.text("You are in [" + clan.tag() + "] " + clan.name() + "'s area"));
    }

    /**
     * Deposits money from player balance into clan bank.
     *
     * @param playerUuid player UUID
     * @param amount amount
     * @return result
     */
    public ActionResult depositToClanBank(String playerUuid, long amount) {
        try {
            if (amount <= 0L) {
                return ActionResult.fail("Amount must be greater than zero.");
            }
            Optional<ClanRecord> clanOpt = repo.findClanByPlayer(playerUuid);
            if (clanOpt.isEmpty()) {
                return ActionResult.fail("You are not in a clan.");
            }
            ClanRecord clan = clanOpt.get();

            boolean ok = db.executeWrite(() -> {
                try (Connection c = db.getConnection()) {
                    boolean oldAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        economy.ensureAccount(c, playerUuid);
                        boolean paid = economy.subtractBalanceFloorZero(c, playerUuid, amount);
                        if (!paid) {
                            c.rollback();
                            return false;
                        }
                        repo.addBank(c, clan.id(), amount);
                        c.commit();
                        return true;
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });
            if (!ok) {
                return ActionResult.fail("You do not have enough money.");
            }
            refreshCaches();
            return ActionResult.ok("Deposited $" + amount + " into clan bank.");
        } catch (Exception e) {
            core.getLogger().severe("[clans] Failed clan bank deposit");
            e.printStackTrace();
            return ActionResult.fail("Failed to deposit to clan bank.");
        }
    }

    /**
     * Returns claims snapshot.
     *
     * @return claims
     */
    public List<ClanClaimRecord> claimsSnapshot() {
        return claimsCache.get();
    }

    /**
     * Returns cached clan by id when available.
     *
     * @param clanId clan id
     * @return clan or null
     */
    public ClanRecord clanById(long clanId) {
        return clansCache.get().get(clanId);
    }

    /**
     * Returns cached clans.
     *
     * @return clans
     */
    public List<ClanRecord> clansSnapshot() {
        return new ArrayList<>(clansCache.get().values());
    }

    /**
     * Executes scheduled upkeep and member fee charging.
     */
    public void runScheduledBillingTick() {
        long now = System.currentTimeMillis();

        try {
            List<ClanRecord> due = repo.listUpkeepDueClans(now);
            for (ClanRecord clan : due) {
                db.executeWrite(() -> {
                    try (Connection c = db.getConnection()) {
                        boolean oldAuto = c.getAutoCommit();
                        c.setAutoCommit(false);
                        try {
                            int members = repo.countMembers(c, clan.id());
                            long upkeep = upkeepBaseCost() + (Math.max(0, members) * upkeepPerMemberCost());
                            if (upkeep > 0L) {
                                repo.subtractBankIfEnough(c, clan.id(), upkeep);
                            }
                            repo.updateUpkeepNextAt(c, clan.id(), now + upkeepIntervalMs());
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
            core.getLogger().severe("[clans] Failed upkeep billing tick");
            e.printStackTrace();
        }

        try {
            List<ClanRecord> clans = repo.listClans();
            for (ClanRecord clan : clans) {
                String feeType = normalizeFeeType(clan.feeType());
                long feeAmount = Math.max(0L, clan.feeAmount());
                if ("NONE".equals(feeType) || feeAmount <= 0L) {
                    continue;
                }

                db.executeWrite(() -> {
                    try (Connection c = db.getConnection()) {
                        boolean oldAuto = c.getAutoCommit();
                        c.setAutoCommit(false);
                        try {
                            List<ClanMemberRecord> members = repo.listMembers(c, clan.id());
                            for (ClanMemberRecord member : members) {
                                if (member.playerUuid().equals(clan.leaderUuid())
                                        && core.config().getBoolean("clans.fees.exclude-leader", true)) {
                                    continue;
                                }
                                if (!isFeeDue(feeType, member.lastFeePaidAt(), now)) {
                                    continue;
                                }

                                economy.ensureAccount(c, member.playerUuid());
                                boolean charged = economy.subtractBalanceFloorZero(c, member.playerUuid(), feeAmount);
                                if (!charged) {
                                    continue;
                                }
                                repo.addBank(c, clan.id(), feeAmount);
                                repo.updateMemberLastFeePaid(c, clan.id(), member.playerUuid(), now);
                                ledger.insertLedgerRow(
                                        c,
                                        now,
                                        "BURN",
                                        feeAmount,
                                        member.playerUuid(),
                                        null,
                                        MoneySource.UPKEEP.name(),
                                        "clan:" + clan.id(),
                                        "Clan member fee",
                                        "SYSTEM"
                                );
                            }
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
            core.getLogger().severe("[clans] Failed member fee billing tick");
            e.printStackTrace();
        }

        tryRefreshCaches();
    }

    /**
     * Returns clan detail text lines.
     *
     * @param clan clan row
     * @return lines
     */
    public List<String> describeClan(ClanRecord clan) {
        try {
            int members = repo.listMembers(clan.id()).size();
            int claimCount = (int) claimsCache.get().stream().filter(c -> c.clanId() == clan.id()).count();
            int storageCount = (int) storageCache.get().values().stream().filter(s -> s.clanId() == clan.id()).count();
            return List.of(
                    "&6[" + clan.tag() + "] &f" + clan.name(),
                    "&7Level: &f" + clan.level() + "&7/&f" + maxLevel(),
                    "&7Members: &f" + members + "&7/&f" + clan.memberCap(),
                    "&7Bank: &a$" + clan.bankBalance(),
                    "&7Claims: &f" + claimCount,
                    "&7Protected Storage: &f" + storageCount + "&7/&f" + clan.protectedStorageCap(),
                    "&7Fee: &f" + normalizeFeeType(clan.feeType()).toLowerCase(Locale.ROOT) + " ($" + clan.feeAmount() + ")",
                    "&7Next Upgrade Cost: &f$" + (clan.level() >= maxLevel() ? 0 : levelUpgradeCost(clan.level() + 1))
            );
        } catch (Exception e) {
            return List.of("&cFailed to load clan info.");
        }
    }

    /**
     * Returns configured clan creation fee.
     *
     * @return fee amount
     */
    public long creationFee() {
        return Math.max(0L, core.config().getLong("clans.create-fee", 5000L));
    }

    /**
     * Returns next-level upgrade cost for a clan.
     *
     * @param clan clan row
     * @return next upgrade cost, or 0 when max level
     */
    public long nextUpgradeCost(ClanRecord clan) {
        if (clan == null || clan.level() >= maxLevel()) {
            return 0L;
        }
        return levelUpgradeCost(clan.level() + 1);
    }

    /**
     * Returns claim at exact block location if present.
     *
     * @param location location
     * @return claim or null
     */
    public ClanClaimRecord claimAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        for (ClanClaimRecord claim : claimsCache.get()) {
            if (!claim.world().equals(world)) {
                continue;
            }
            if (claim.x() == x && claim.y() == y && claim.z() == z) {
                return claim;
            }
        }
        return null;
    }

    /**
     * Returns true when the block is protected storage owned by any clan.
     *
     * @param block block
     * @return true when protected
     */
    public boolean isProtectedStorage(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        return storageCache.get().containsKey(new StorageKey(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        ));
    }

    /**
     * Returns true if the item/block material is a banner.
     *
     * @param material material
     * @return true if banner
     */
    public boolean isBannerBlock(Material material) {
        return material != null && material.name().endsWith("_BANNER");
    }

    /**
     * Builds a flag signature from an item stack.
     *
     * @param item item
     * @return signature or null when not a banner
     */
    public String signatureFromItem(ItemStack item) {
        if (item == null || !isBannerBlock(item.getType()) || !(item.getItemMeta() instanceof BannerMeta meta)) {
            return null;
        }
        return item.getType().name() + "|" + patternSignature(meta.getPatterns());
    }

    /**
     * Builds a flag signature from placed banner block state.
     *
     * @param state block state
     * @return signature or null
     */
    public String signatureFromBlockState(BlockState state) {
        if (state == null || !isBannerBlock(state.getType())) {
            return null;
        }
        if (!(state instanceof org.bukkit.block.Banner bannerState)) {
            return null;
        }
        return state.getType().name() + "|" + patternSignature(bannerState.getPatterns());
    }

    private String patternSignature(List<Pattern> patterns) {
        return patterns.stream()
                .map(pattern -> pattern.getPattern().name() + ":" + pattern.getColor().name())
                .collect(Collectors.joining(","));
    }

    private boolean tooCloseToSpawn(Location location) {
        if (location == null || location.getWorld() == null) {
            return true;
        }
        long min = Math.max(0L, core.config().getLong("clans.claim.min-distance-from-spawn", 128L));
        if (min <= 0L) {
            return false;
        }
        Location spawn = location.getWorld().getSpawnLocation();
        return spawn.distanceSquared(location) < (double) (min * min);
    }

    private ClanClaimRecord findNearestClaim(Location center, long radius) {
        if (center == null || center.getWorld() == null) {
            return null;
        }
        String world = center.getWorld().getName();
        double maxSq = radius * radius;
        ClanClaimRecord nearest = null;
        double best = maxSq + 1.0;
        for (ClanClaimRecord claim : claimsCache.get()) {
            if (!world.equals(claim.world())) {
                continue;
            }
            double dx = (claim.x() + 0.5) - center.getX();
            double dy = claim.y() - center.getY();
            double dz = (claim.z() + 0.5) - center.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxSq) {
                continue;
            }
            if (distSq < best) {
                best = distSq;
                nearest = claim;
            }
        }
        return nearest;
    }

    private boolean isWithinAnyClanClaim(String world, int x, int y, int z, long clanId, long radius) {
        if (world == null || world.isBlank()) {
            return false;
        }
        double maxSq = radius * radius;
        for (ClanClaimRecord claim : claimsCache.get()) {
            if (claim.clanId() != clanId || !world.equals(claim.world())) {
                continue;
            }
            double dx = (claim.x() + 0.5) - x;
            double dy = claim.y() - y;
            double dz = (claim.z() + 0.5) - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= maxSq) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeTag(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private boolean isFeeDue(String feeType, long lastPaidAt, long now) {
        if ("NONE".equals(feeType)) {
            return false;
        }
        if ("ONE_TIME".equals(feeType)) {
            return lastPaidAt <= 0L;
        }
        if ("DAILY".equals(feeType)) {
            return lastPaidAt <= 0L || (now - lastPaidAt) >= 86_400_000L;
        }
        if ("WEEKLY".equals(feeType)) {
            return lastPaidAt <= 0L || (now - lastPaidAt) >= 604_800_000L;
        }
        return false;
    }

    private String normalizeFeeType(String value) {
        if (value == null) {
            return "NONE";
        }
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private long claimProximityRadius() {
        return Math.max(16L, core.config().getLong("clans.claim.proximity-radius", 96L));
    }

    private long notifyRadius() {
        return Math.max(16L, core.config().getLong("clans.claim.notify-radius", 64L));
    }

    private long storageRadius() {
        return Math.max(4L, core.config().getLong("clans.claim.storage-radius", 24L));
    }

    private int maxLevel() {
        return Math.max(1, core.config().getInt("clans.levels.max-level", 6));
    }

    private int levelMemberCap(int level) {
        return Math.max(1, core.config().getInt("clans.levels.data." + level + ".member-cap", 10 + ((level - 1) * 5)));
    }

    private int levelStorageCap(int level) {
        return Math.max(1, core.config().getInt("clans.levels.data." + level + ".protected-storage-cap", 4 + ((level - 1) * 2)));
    }

    private long levelUpgradeCost(int level) {
        return Math.max(0L, core.config().getLong("clans.levels.data." + level + ".upgrade-cost", 1000L * level));
    }

    private long upkeepBaseCost() {
        return Math.max(0L, core.config().getLong("clans.upkeep.base-cost", 250L));
    }

    private long upkeepPerMemberCost() {
        return Math.max(0L, core.config().getLong("clans.upkeep.per-member-cost", 25L));
    }

    private long upkeepIntervalMs() {
        long hours = Math.max(1L, core.config().getLong("clans.upkeep.interval-hours", 24L));
        return hours * 3_600_000L;
    }

    private void tryRefreshCaches() {
        try {
            refreshCaches();
        } catch (Exception e) {
            core.getLogger().warning("[clans] Failed to refresh caches after update.");
        }
    }

    /**
     * Result wrapper.
     *
     * @param success true when action succeeded
     * @param message result message
     */
    public record ActionResult(boolean success, String message) {
        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }
    }

    private record StorageKey(String world, int x, int y, int z) {
    }
}
