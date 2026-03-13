package com.krishcpatel.realm.jobs.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.guard.PlacedBlockGuardObservationCache;
import com.krishcpatel.realm.jobs.manager.JobRewardService;
import com.krishcpatel.realm.jobs.model.JobActionContext;
import com.krishcpatel.realm.jobs.model.JobActionType;
import com.krishcpatel.realm.jobs.repository.JobsRepository;
import com.krishcpatel.realm.jobs.util.JobActionGroups;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Bukkit events into normalized job actions.
 */
public final class JobsActionListener implements Listener {
    private static final String GROUP_BOSS_DAMAGE_SHARE = "#BOSS_DAMAGE_SHARE";

    private final Core core;
    private final JobRewardService rewards;
    private final JobsRepository repo;
    private final Set<String> placedBreakGuards = ConcurrentHashMap.newKeySet();
    private final Map<String, BrewSession> brewSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Double>> bossDamageByEntity = new ConcurrentHashMap<>();

    /**
     * Creates a listener that translates player activity into job rewards.
     *
     * @param core plugin instance
     * @param rewards reward processor
     * @param repo jobs persistence layer
     */
    public JobsActionListener(Core core, JobRewardService rewards, JobsRepository repo) {
        this.core = core;
        this.rewards = rewards;
        this.repo = repo;
    }

    /**
     * Tracks player damage contribution on configured bosses so payouts can be split by contribution.
     *
     * @param event entity damage event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBossDamage(EntityDamageByEntityEvent event) {
        BossTarget bossTarget = resolveTrackedBoss(event.getEntity());
        if (bossTarget == null) {
            return;
        }

        Player damager = resolveDamagingPlayer(event);
        if (damager == null || shouldIgnoreForBossPayout(damager)) {
            return;
        }

        double damage = Math.max(0D, event.getFinalDamage());
        if (damage <= 0D) {
            return;
        }

        bossDamageByEntity
                .computeIfAbsent(bossTarget.entityUuid(), ignored -> new ConcurrentHashMap<>())
                .merge(damager.getUniqueId(), damage, Double::sum);
    }

    /**
     * Handles block breaks and awards break rewards when the block is not protected by anti-farm guards.
     *
     * @param event block break event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (shouldIgnore(event.getPlayer())) {
            return;
        }

        Material type = event.getBlock().getType();
        JobActionContext context = buildContext(
                event.getPlayer(),
                JobActionType.BREAK,
                type.name(),
                JobActionGroups.forMaterial(type),
                1,
                event.getBlock().getWorld().getName(),
                event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ()
        );

        if (!antiFarmPlacedBreakEnabled()) {
            dispatch(context);
            return;
        }

        Location location = event.getBlock().getLocation();
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        if (shouldIgnorePlayerPlacedBreak(location)) {
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                boolean playerPlaced = repo.hasPlacedBlockGuard(world, x, y, z);
                if (playerPlaced) {
                    PlacedBlockGuardObservationCache.markObserved(world, x, y, z);
                    clearStoredPlacedBreakGuard(world, x, y, z);
                    return;
                }

                if (PlacedBlockGuardObservationCache.wasRecentlyObserved(world, x, y, z)) {
                    return;
                }

                rewards.handleAction(context);
            } catch (Exception e) {
                core.getLogger().severe("[jobs] Failed to check placed-block guard for "
                        + world + " " + x + "," + y + "," + z);
                e.printStackTrace();
            }
        });
    }

    /**
     * Handles block placement, persisting anti-farm guards and dispatching place rewards.
     *
     * @param event block place event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (shouldIgnore(event.getPlayer())) {
            return;
        }

        Material type = event.getBlockPlaced().getType();
        if (antiFarmPlacedBreakEnabled()) {
            markPlacedBreakGuard(event.getBlockPlaced().getLocation(), type, event.getPlayer().getUniqueId().toString());
        }

        dispatch(buildContext(
                event.getPlayer(),
                JobActionType.PLACE,
                type.name(),
                JobActionGroups.forMaterial(type),
                1,
                event.getBlockPlaced().getWorld().getName(),
                event.getBlockPlaced().getChunk().getX(),
                event.getBlockPlaced().getChunk().getZ()
        ));
    }

    /**
     * Marks fertilized blocks as player-generated to prevent bonemeal farm loops.
     *
     * @param event fertilize event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        if (!antiFarmPlacedBreakEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        if (player != null && shouldIgnore(player)) {
            return;
        }
        String source = player == null ? "SYSTEM" : player.getUniqueId().toString();

        for (BlockState blockState : event.getBlocks()) {
            markPlacedBreakGuard(blockState.getLocation(), blockState.getType(), source);
        }
    }

    /**
     * Marks bonemeal-grown structure blocks (for example trees) as player-generated.
     *
     * @param event structure grow event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!antiFarmPlacedBreakEnabled() || !event.isFromBonemeal()) {
            return;
        }

        Player player = event.getPlayer();
        if (player != null && shouldIgnore(player)) {
            return;
        }
        String source = player == null ? "SYSTEM" : player.getUniqueId().toString();

        for (BlockState blockState : event.getBlocks()) {
            markPlacedBreakGuard(blockState.getLocation(), blockState.getType(), source);
        }
    }

    /**
     * Handles entity kills and dispatches job kill actions for valid player kills.
     *
     * @param event entity death event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        EntityType entityType = event.getEntityType();
        UUID entityUuid = event.getEntity().getUniqueId();
        Map<UUID, Double> contributions = bossDamageByEntity.remove(entityUuid);

        if (isTrackedBoss(entityType)) {
            handleBossPayouts(event, entityType, contributions);
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null || shouldIgnore(killer)) {
            return;
        }

        if (core.jobsConfig().getBoolean("settings.anti-farm.ignore-spawner-kills", true)
                && event.getEntity().fromMobSpawner()) {
            return;
        }

        dispatch(buildContext(
                killer,
                JobActionType.KILL,
                entityType.name(),
                JobActionGroups.forEntity(entityType),
                1,
                event.getEntity().getWorld().getName(),
                event.getEntity().getChunk().getX(),
                event.getEntity().getChunk().getZ()
        ));
    }

    /**
     * Handles completed crafting actions and dispatches a normalized craft reward action.
     *
     * @param event craft event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || shouldIgnore(player)) {
            return;
        }

        if (event.getRecipe() == null || event.getRecipe().getResult() == null) {
            return;
        }

        Material type = event.getRecipe().getResult().getType();
        dispatch(buildContext(
                player,
                JobActionType.CRAFT,
                type.name(),
                JobActionGroups.forMaterial(type),
                1,
                player.getWorld().getName(),
                player.getChunk().getX(),
                player.getChunk().getZ()
        ));
    }

    /**
     * Handles enchanting actions and dispatches a normalized enchant reward action.
     *
     * @param event enchant event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEnchant(EnchantItemEvent event) {
        if (shouldIgnore(event.getEnchanter())) {
            return;
        }

        Material type = event.getItem().getType();
        dispatch(buildContext(
                event.getEnchanter(),
                JobActionType.ENCHANT,
                type.name(),
                JobActionGroups.forMaterial(type),
                1,
                event.getEnchantBlock().getWorld().getName(),
                event.getEnchantBlock().getChunk().getX(),
                event.getEnchantBlock().getChunk().getZ()
        ));
    }

    /**
     * Handles successful fishing catches and dispatches a normalized fishing reward action.
     *
     * @param event fishing event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || shouldIgnore(event.getPlayer())) {
            return;
        }

        String target = "ANY";
        Set<String> groups = new HashSet<>();
        groups.add("#FISH");

        if (event.getCaught() instanceof Item item) {
            target = item.getItemStack().getType().name();
            groups.addAll(JobActionGroups.forMaterial(item.getItemStack().getType()));
        }

        dispatch(buildContext(
                event.getPlayer(),
                JobActionType.FISH,
                target,
                Set.copyOf(groups),
                1,
                event.getPlayer().getWorld().getName(),
                event.getPlayer().getChunk().getX(),
                event.getPlayer().getChunk().getZ()
        ));
    }

    /**
     * Tracks the last player to interact with a brewing stand so completed brews can be attributed safely.
     *
     * @param event brewing inventory click event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBrewingInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || shouldIgnore(player)) {
            return;
        }

        if (event.getView().getTopInventory().getType() != InventoryType.BREWING) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() >= topSize && !event.isShiftClick()) {
            return;
        }

        Location location = event.getView().getTopInventory().getLocation();
        if (location == null) {
            return;
        }

        brewSessions.put(blockKey(location), new BrewSession(player.getUniqueId(), System.currentTimeMillis()));
    }

    /**
     * Handles brewing completion and dispatches brew rewards to the most recent valid brewing stand user.
     *
     * @param event brew completion event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBrew(BrewEvent event) {
        String key = blockKey(event.getBlock().getLocation());
        BrewSession session = brewSessions.get(key);
        if (session == null) {
            return;
        }

        long expiryMs = Math.max(1_000L, core.jobsConfig().getLong("settings.anti-farm.brew-owner-expiry-ms", 300_000L));
        if (System.currentTimeMillis() - session.lastTouchedAt() > expiryMs) {
            brewSessions.remove(key);
            return;
        }

        Player player = core.getServer().getPlayer(session.playerUuid());
        if (player == null || shouldIgnore(player)) {
            return;
        }

        ItemStack brewedItem = null;
        for (int slot = 0; slot <= 2; slot++) {
            ItemStack item = event.getContents().getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                brewedItem = item;
                break;
            }
        }

        if (brewedItem == null) {
            return;
        }

        Set<String> groups = new HashSet<>(JobActionGroups.forMaterial(brewedItem.getType()));
        groups.add("#POTIONS");

        dispatch(buildContext(
                player,
                JobActionType.BREW,
                brewedItem.getType().name(),
                Set.copyOf(groups),
                1,
                event.getBlock().getWorld().getName(),
                event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ()
        ));
    }

    /**
     * Handles chunk-to-chunk movement and dispatches explorer actions for newly entered chunks.
     *
     * @param event player movement event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || shouldIgnore(event.getPlayer())) {
            return;
        }

        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) {
            return;
        }

        dispatch(buildContext(
                event.getPlayer(),
                JobActionType.EXPLORE,
                event.getTo().getWorld().getName(),
                Set.of("#EXPLORE"),
                1,
                event.getTo().getWorld().getName(),
                event.getTo().getChunk().getX(),
                event.getTo().getChunk().getZ()
        ));
    }

    private JobActionContext buildContext(
            Player player,
            JobActionType type,
            String target,
            Set<String> groups,
            int amount,
            String worldName,
            int chunkX,
            int chunkZ
    ) {
        return new JobActionContext(
                player.getUniqueId(),
                player.getName(),
                type,
                target,
                groups,
                amount,
                worldName,
                chunkX,
                chunkZ
        );
    }

    private void dispatch(JobActionContext context) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> rewards.handleAction(context));
    }

    private boolean shouldIgnorePlayerPlacedBreak(Location location) {
        if (!antiFarmPlacedBreakEnabled()) {
            return false;
        }
        return placedBreakGuards.remove(blockKey(location));
    }

    private boolean antiFarmPlacedBreakEnabled() {
        return core.jobsConfig().getBoolean("settings.anti-farm.ignore-player-placed-breaks", true);
    }

    private void markPlacedBreakGuard(Location location, Material material, String playerUuid) {
        if (!shouldTrackPlacedBreakGuard(material) || location.getWorld() == null) {
            return;
        }

        String key = blockKey(location);
        placedBreakGuards.add(key);
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        long placedAt = System.currentTimeMillis();

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                repo.markPlacedBlockGuard(world, x, y, z, playerUuid, material.name(), placedAt);
                placedBreakGuards.remove(key);
            } catch (Exception e) {
                core.getLogger().severe("[jobs] Failed to persist placed-block guard for "
                        + world + " " + x + "," + y + "," + z);
                e.printStackTrace();
            }
        });
    }

    private void clearStoredPlacedBreakGuard(String world, int x, int y, int z) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                repo.deletePlacedBlockGuard(world, x, y, z);
            } catch (Exception e) {
                core.getLogger().severe("[jobs] Failed to clear placed-block guard for "
                        + world + " " + x + "," + y + "," + z);
                e.printStackTrace();
            }
        });
    }

    private boolean shouldTrackPlacedBreakGuard(Material material) {
        Set<String> groups = JobActionGroups.forMaterial(material);
        return groups.contains("#LOGS")
                || groups.contains("#ORES")
                || groups.contains("#DIGGABLE")
                || groups.contains("#CROPS");
    }

    private String blockKey(Location location) {
        return location.getWorld().getName()
                + ":" + location.getBlockX()
                + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    private boolean shouldIgnore(Player player) {
        if (!core.jobsConfig().getBoolean("settings.ignore-creative", true)) {
            return false;
        }
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean shouldIgnoreForBossPayout(Player player) {
        if (core.jobsConfig().getBoolean("settings.boss-payouts.testing.allow-creative", false)) {
            return false;
        }
        return shouldIgnore(player);
    }

    private void handleBossPayouts(EntityDeathEvent event, EntityType bossType, Map<UUID, Double> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            Player killer = event.getEntity().getKiller();
            if (killer == null || shouldIgnoreForBossPayout(killer)) {
                return;
            }

            dispatchBossShare(event, bossType, killer, 10_000);
            return;
        }

        Map<UUID, Player> eligiblePlayers = new HashMap<>();
        Map<UUID, Double> eligibleDamage = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
            Player player = core.getServer().getPlayer(entry.getKey());
            if (player == null || shouldIgnoreForBossPayout(player)) {
                continue;
            }

            double damage = Math.max(0D, entry.getValue());
            if (damage <= 0D) {
                continue;
            }

            eligiblePlayers.put(entry.getKey(), player);
            eligibleDamage.put(entry.getKey(), damage);
        }

        if (eligibleDamage.isEmpty()) {
            Player killer = event.getEntity().getKiller();
            if (killer == null || shouldIgnoreForBossPayout(killer)) {
                return;
            }
            dispatchBossShare(event, bossType, killer, 10_000);
            return;
        }

        Map<UUID, Integer> shares = toShareBasisPoints(eligibleDamage);
        for (Map.Entry<UUID, Integer> entry : shares.entrySet()) {
            Player player = eligiblePlayers.get(entry.getKey());
            if (player == null || shouldIgnoreForBossPayout(player)) {
                continue;
            }
            dispatchBossShare(event, bossType, player, entry.getValue());
        }
    }

    private void dispatchBossShare(EntityDeathEvent event, EntityType bossType, Player player, int shareBasisPoints) {
        Set<String> groups = buildBossGroups(bossType);
        int clampedShare = Math.max(1, Math.min(10_000, shareBasisPoints));

        dispatch(buildContext(
                player,
                JobActionType.KILL,
                bossType.name(),
                groups,
                clampedShare,
                event.getEntity().getWorld().getName(),
                event.getEntity().getChunk().getX(),
                event.getEntity().getChunk().getZ()
        ));
    }

    private Map<UUID, Integer> toShareBasisPoints(Map<UUID, Double> contributions) {
        if (contributions.isEmpty()) {
            return Map.of();
        }

        double totalDamage = contributions.values().stream()
                .mapToDouble(value -> Math.max(0D, value))
                .sum();
        if (totalDamage <= 0D) {
            return Map.of();
        }

        Map<UUID, Integer> shares = new HashMap<>();
        List<ShareRemainder> remainders = new ArrayList<>(contributions.size());
        int allocated = 0;

        for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
            double exactShare = (Math.max(0D, entry.getValue()) / totalDamage) * 10_000D;
            int baseShare = (int) Math.floor(exactShare);
            shares.put(entry.getKey(), baseShare);
            remainders.add(new ShareRemainder(entry.getKey(), exactShare - baseShare));
            allocated += baseShare;
        }

        int remainder = Math.max(0, 10_000 - allocated);
        remainders.sort(Comparator.comparingDouble(ShareRemainder::fractional).reversed());
        for (int i = 0; i < remainder && !remainders.isEmpty(); i++) {
            ShareRemainder target = remainders.get(i % remainders.size());
            shares.merge(target.playerUuid(), 1, Integer::sum);
        }

        shares.entrySet().removeIf(entry -> entry.getValue() <= 0);
        return shares;
    }

    private Set<String> buildBossGroups(EntityType bossType) {
        Set<String> groups = new HashSet<>(JobActionGroups.forEntity(bossType));
        groups.add(GROUP_BOSS_DAMAGE_SHARE);
        return Set.copyOf(groups);
    }

    private Player resolveDamagingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }

        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }

        return null;
    }

    private BossTarget resolveTrackedBoss(Entity entity) {
        if (entity instanceof LivingEntity livingEntity && isTrackedBoss(livingEntity.getType())) {
            return new BossTarget(livingEntity.getUniqueId());
        }

        if (entity instanceof ComplexEntityPart part) {
            Entity parent = part.getParent();
            if (parent instanceof LivingEntity livingParent && isTrackedBoss(livingParent.getType())) {
                return new BossTarget(livingParent.getUniqueId());
            }
        }

        return null;
    }

    private boolean isTrackedBoss(EntityType type) {
        return type == EntityType.WARDEN
                || type == EntityType.ELDER_GUARDIAN
                || type == EntityType.WITHER
                || type == EntityType.ENDER_DRAGON;
    }

    private record BossTarget(UUID entityUuid) {
    }

    private record ShareRemainder(UUID playerUuid, double fractional) {
    }

    private record BrewSession(UUID playerUuid, long lastTouchedAt) {
    }
}
