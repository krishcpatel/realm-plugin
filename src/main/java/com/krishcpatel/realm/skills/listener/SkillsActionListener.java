package com.krishcpatel.realm.skills.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.skills.manager.SkillProgressService;
import com.krishcpatel.realm.skills.model.SkillActionContext;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Bukkit actions into normalized skill actions.
 */
public final class SkillsActionListener implements Listener {
    private static final String SKILL_UNARMED = "unarmed";
    private static final String SKILL_ARCHERY = "archery";
    private static final String SKILL_SWORDS = "swords";
    private static final String SKILL_AXES = "axes";
    private static final String SKILL_TAMING = "taming";
    private static final String SKILL_MINING = "mining";
    private static final String SKILL_WOODCUTTING = "woodcutting";
    private static final String SKILL_HERBALISM = "herbalism";
    private static final String SKILL_EXCAVATION = "excavation";
    private static final String SKILL_FISHING = "fishing";
    private static final String SKILL_REPAIR = "repair";
    private static final String SKILL_ACROBATICS = "acrobatics";
    private static final String SKILL_ALCHEMY = "alchemy";

    private final Core core;
    private final SkillProgressService progress;
    private final Map<String, BrewSession> brewSessions = new ConcurrentHashMap<>();

    /**
     * Creates the skill action listener.
     *
     * @param core plugin instance
     * @param progress skill progression service
     */
    public SkillsActionListener(Core core, SkillProgressService progress) {
        this.core = core;
        this.progress = progress;
    }

    /**
     * Handles player combat and projectile damage actions for combat skills.
     *
     * @param event damage event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player defender && !shouldIgnore(defender)) {
            maybeApplyAcrobaticsDodge(event, defender);
        }

        if (event.getDamager() instanceof Player player) {
            if (shouldIgnore(player)) {
                return;
            }

            Material weapon = player.getInventory().getItemInMainHand().getType();
            String name = weapon.name();
            if (weapon == Material.AIR) {
                dispatch(player.getUniqueId(), player.getName(), SKILL_UNARMED, "melee-hit", 1);
            } else if (name.endsWith("_SWORD")) {
                dispatch(player.getUniqueId(), player.getName(), SKILL_SWORDS, "melee-hit", 1);
            } else if (name.endsWith("_AXE")) {
                dispatch(player.getUniqueId(), player.getName(), SKILL_AXES, "melee-hit", 1);
            }
        }

        if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player player) {
            if (shouldIgnore(player)) {
                return;
            }
            dispatch(player.getUniqueId(), player.getName(), SKILL_ARCHERY, "ranged-hit", 1);
        }
    }

    /**
     * Handles successful taming for the taming skill.
     *
     * @param event tame event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player) || shouldIgnore(player)) {
            return;
        }

        dispatch(player.getUniqueId(), player.getName(), SKILL_TAMING, "tame-entity", 1);
    }

    /**
     * Handles acrobatics xp on fall damage and successful roll outcomes.
     *
     * @param event damage event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || shouldIgnore(player)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        maybeApplyAcrobaticsRoll(event, player);

        int halfHearts = (int) Math.ceil(event.getFinalDamage());
        if (halfHearts <= 0) {
            return;
        }

        dispatch(player.getUniqueId(), player.getName(), SKILL_ACROBATICS, "fall-damage-half-heart", halfHearts);
    }

    /**
     * Handles block breaking actions for gathering skills.
     *
     * @param event block break event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldIgnore(player)) {
            return;
        }

        Material type = event.getBlock().getType();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        if (isMiningOre(type)) {
            dispatch(uuid, name, SKILL_MINING, "ore-break", 1);
        }

        if (isMiningStone(type)) {
            dispatch(uuid, name, SKILL_MINING, "stone-break", 1);
        }

        if (Tag.LOGS.isTagged(type)) {
            dispatch(uuid, name, SKILL_WOODCUTTING, "log-break", 1);
        }

        if (isHerbalismHarvest(event.getBlock())) {
            dispatch(uuid, name, SKILL_HERBALISM, "crop-harvest", 1);
        }

        if (isUsingShovel(player.getInventory().getItemInMainHand().getType()) && isExcavationMaterial(type)) {
            dispatch(uuid, name, SKILL_EXCAVATION, "shovel-dig", 1);
        }
    }

    /**
     * Handles successful fishing catches for the fishing skill.
     *
     * @param event fishing event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof Item) || shouldIgnore(event.getPlayer())) {
            return;
        }

        dispatch(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName(),
                SKILL_FISHING,
                "catch-fish",
                1
        );
    }

    /**
     * Tracks the last brewing stand user to attribute brew completion to a player.
     *
     * @param event inventory click event
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
     * Handles completed brew actions for the alchemy skill.
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

        long expiryMs = Math.max(1_000L, core.skillsConfig().getLong("settings.brew-owner-expiry-ms", 300_000L));
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

        dispatch(player.getUniqueId(), player.getName(), SKILL_ALCHEMY, "brew", 1);
    }

    /**
     * Handles anvil result usage for the repair skill.
     *
     * @param event inventory click event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAnvilUse(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || shouldIgnore(player)) {
            return;
        }
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        if (event.getRawSlot() != 2) {
            return;
        }

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }

        ItemStack inputLeft = event.getView().getTopInventory().getItem(0);
        ItemStack inputRight = event.getView().getTopInventory().getItem(1);
        if (inputLeft == null || inputLeft.getType() == Material.AIR) {
            return;
        }
        if (inputRight == null || inputRight.getType() == Material.AIR) {
            return;
        }

        dispatch(player.getUniqueId(), player.getName(), SKILL_REPAIR, "anvil-use", 1);
    }

    private void dispatch(UUID playerUuid, String playerName, String skillId, String actionKey, int amount) {
        SkillActionContext context = new SkillActionContext(playerUuid, playerName, skillId, actionKey, amount);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> progress.handleAction(context));
    }

    private boolean shouldIgnore(Player player) {
        if (!core.skillsConfig().getBoolean("settings.ignore-creative", true)) {
            return false;
        }
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private String blockKey(Location location) {
        return location.getWorld().getName()
                + ":" + location.getBlockX()
                + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    private boolean isHerbalismHarvest(Block block) {
        Material type = block.getType();

        if (isHerbalismFlora(type)) {
            return true;
        }

        if (Tag.CROPS.isTagged(type)) {
            if (!(block.getBlockData() instanceof Ageable ageable)) {
                return true;
            }
            return ageable.getAge() >= ageable.getMaximumAge();
        }

        if (block.getBlockData() instanceof Ageable ageable) {
            if (type == Material.SWEET_BERRY_BUSH || type == Material.NETHER_WART || type == Material.COCOA) {
                return ageable.getAge() >= ageable.getMaximumAge();
            }
        }

        return switch (type) {
            case SUGAR_CANE, CACTUS, MELON, PUMPKIN, KELP_PLANT -> true;
            default -> false;
        };
    }

    private boolean isHerbalismFlora(Material type) {
        if (Tag.FLOWERS.isTagged(type)) {
            return true;
        }

        String name = type.name();
        return name.equals("SHORT_GRASS")
                || name.equals("TALL_GRASS")
                || name.equals("FERN")
                || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH")
                || name.equals("SEAGRASS")
                || name.equals("TALL_SEAGRASS");
    }

    private boolean isMiningStone(Material material) {
        return switch (material) {
            case STONE, DEEPSLATE, COBBLESTONE, COBBLED_DEEPSLATE,
                    GRANITE, DIORITE, ANDESITE, TUFF, CALCITE -> true;
            default -> false;
        };
    }

    private boolean isMiningOre(Material material) {
        return switch (material) {
            case COAL_ORE, DEEPSLATE_COAL_ORE,
                    COPPER_ORE, DEEPSLATE_COPPER_ORE,
                    IRON_ORE, DEEPSLATE_IRON_ORE,
                    GOLD_ORE, DEEPSLATE_GOLD_ORE,
                    REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE,
                    LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                    DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                    EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                    NETHER_GOLD_ORE, NETHER_QUARTZ_ORE, ANCIENT_DEBRIS -> true;
            default -> false;
        };
    }

    private boolean isUsingShovel(Material material) {
        return material.name().endsWith("_SHOVEL");
    }

    private boolean isExcavationMaterial(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, PODZOL, MYCELIUM, DIRT_PATH, COARSE_DIRT, ROOTED_DIRT,
                    MUD, CLAY, GRAVEL, SAND, RED_SAND, SNOW, SNOW_BLOCK,
                    SOUL_SAND, SOUL_SOIL, FARMLAND -> true;
            default -> false;
        };
    }

    private void maybeApplyAcrobaticsRoll(EntityDamageEvent event, Player player) {
        double minFallDistance = Math.max(0D, core.skillsConfig().getDouble("settings.acrobatics.min-roll-fall-distance", 4.0D));
        if (player.getFallDistance() < minFallDistance) {
            return;
        }

        double chance = clampChance(core.skillsConfig().getDouble("settings.acrobatics.roll-chance", 0.15D));
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        double damageMultiplier = core.skillsConfig().getDouble("settings.acrobatics.roll-damage-multiplier", 0.5D);
        damageMultiplier = Math.max(0D, Math.min(1D, damageMultiplier));
        event.setDamage(event.getDamage() * damageMultiplier);

        dispatch(player.getUniqueId(), player.getName(), SKILL_ACROBATICS, "roll-success", 1);
    }

    private void maybeApplyAcrobaticsDodge(EntityDamageByEntityEvent event, Player player) {
        if (event.getDamage() <= 0D) {
            return;
        }

        double chance = clampChance(core.skillsConfig().getDouble("settings.acrobatics.dodge-chance", 0.07D));
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        event.setCancelled(true);
        dispatch(player.getUniqueId(), player.getName(), SKILL_ACROBATICS, "dodge-success", 1);
    }

    private double clampChance(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private record BrewSession(UUID playerUuid, long lastTouchedAt) {
    }
}
