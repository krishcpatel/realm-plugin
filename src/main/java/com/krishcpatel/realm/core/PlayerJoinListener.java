package com.krishcpatel.realm.core;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Bukkit event adapter for player joins.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Upsert the joining player into the database</li>
 *   <li>Publish {@link PlayerUpsertedEvent} for modules to react to</li>
 * </ul>
 */
public class PlayerJoinListener implements Listener {
    private final Core plugin;
    private final PlayerRepository repo;

    /**
     * Creates a join listener using the given plugin and repository.
     *
     * @param plugin owning plugin instance
     * @param repo player repository used to write player records
     */
    public PlayerJoinListener(Core plugin, PlayerRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    /**
     * Handles {@link org.bukkit.event.player.PlayerJoinEvent} and syncs the player into the DB.
     *
     * @param e join event
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var p = e.getPlayer();
        long now = System.currentTimeMillis();

        plugin.debug("Join event: " + p.getName() + " uuid=" + p.getUniqueId());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repo.upsertPlayer(p.getUniqueId().toString(), p.getName(), now);

                plugin.debug("DB upsert OK for " + p.getName());

                // If your internal event handlers touch Bukkit API, publish sync instead
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.events().publish(new PlayerUpsertedEvent(p.getUniqueId(), p.getName()))
                );

            } catch (Exception ex) {
                plugin.getLogger().severe("Failed to upsert player " + p.getName());
                ex.printStackTrace();
            }
        });
    }
}
