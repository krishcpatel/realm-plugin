package com.krishcpatel.realm.jobs.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.jobs.manager.JobRewardService;
import com.krishcpatel.realm.jobs.model.JobActionContext;
import com.krishcpatel.realm.jobs.model.JobActionType;
import com.krishcpatel.realm.jobs.repository.JobsRepository;
import com.krishcpatel.realm.jobs.util.JobActionGroups;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Bukkit events into normalized job actions.
 */
public final class JobsActionListener implements Listener {
    private final Core core;
    private final JobRewardService rewards;
    private final JobsRepository repo;
    private final Set<String> placedBreakGuards = ConcurrentHashMap.newKeySet();
    private final Map<String, BrewSession> brewSessions = new ConcurrentHashMap<>();

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

        if (!core.jobsConfig().getBoolean("settings.anti-farm.ignore-player-placed-breaks", true)) {
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
                boolean playerPlaced = repo.consumePlacedBlockGuard(world, x, y, z);
                if (!playerPlaced) {
                    rewards.handleAction(context);
                }
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
        if (shouldTrackPlacedBreakGuard(type)) {
            placedBreakGuards.add(blockKey(event.getBlockPlaced().getLocation()));
            String world = event.getBlockPlaced().getWorld().getName();
            int x = event.getBlockPlaced().getX();
            int y = event.getBlockPlaced().getY();
            int z = event.getBlockPlaced().getZ();
            String playerUuid = event.getPlayer().getUniqueId().toString();
            String material = type.name();
            long placedAt = System.currentTimeMillis();

            core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                try {
                    repo.markPlacedBlockGuard(world, x, y, z, playerUuid, material, placedAt);
                } catch (Exception e) {
                    core.getLogger().severe("[jobs] Failed to persist placed-block guard for "
                            + world + " " + x + "," + y + "," + z);
                    e.printStackTrace();
                }
            });
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
     * Handles entity kills and dispatches job kill actions for valid player kills.
     *
     * @param event entity death event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
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
                event.getEntityType().name(),
                JobActionGroups.forEntity(event.getEntityType()),
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
        if (!core.jobsConfig().getBoolean("settings.anti-farm.ignore-player-placed-breaks", true)) {
            return false;
        }
        return placedBreakGuards.remove(blockKey(location));
    }

    private boolean shouldTrackPlacedBreakGuard(Material material) {
        Set<String> groups = JobActionGroups.forMaterial(material);
        return groups.contains("#LOGS") || groups.contains("#ORES") || groups.contains("#DIGGABLE");
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

    private record BrewSession(UUID playerUuid, long lastTouchedAt) {
    }
}
