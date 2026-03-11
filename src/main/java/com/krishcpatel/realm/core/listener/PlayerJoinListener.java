package com.krishcpatel.realm.core.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.player.PlayerRepository;
import com.krishcpatel.realm.core.event.player.PlayerUpsertedEvent;
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
        String uuid = p.getUniqueId().toString();
        String username = p.getName();

        plugin.debug("Join event: " + username + " uuid=" + uuid);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repo.upsertPlayer(uuid, username, now);

                plugin.debug("DB upsert OK for " + username);

                // If your internal event handlers touch Bukkit API, publish sync instead
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.events().publish(new PlayerUpsertedEvent(java.util.UUID.fromString(uuid), username))
                );

            } catch (Exception ex) {
                plugin.getLogger().severe("Failed to upsert player " + username);
                ex.printStackTrace();
            }
        });
    }
}
