package com.krishcpatel.realm.core;

import com.krishcpatel.realm.core.command.RealmCommand;
import com.krishcpatel.realm.core.config.ConfigManager;
import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.core.event.EventSystem;
import com.krishcpatel.realm.core.event.RealmEvent;
import com.krishcpatel.realm.core.event.player.PlayerUpsertedEvent;
import com.krishcpatel.realm.core.listener.PlayerJoinListener;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.core.player.PlayerRepository;
import com.krishcpatel.realm.economy.EconomyModule;
import com.krishcpatel.realm.jobs.JobsModule;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Main entry point for the Realm plugin.
 *
 * <p>This class initializes core systems such as:</p>
 * <ul>
 *   <li>Configuration loading</li>
 *   <li>Database connection</li>
 *   <li>Event bus</li>
 *   <li>Module lifecycle management</li>
 * </ul>
 */
public final class Core extends JavaPlugin {
    /**
     * Creates the Realm plugin instance.
     *
     * <p>Called automatically by the Paper/Bukkit server when the plugin loads.</p>
     */
    public Core() {
        super();
    }

    /**
     * Shared plugin logger.
     *
     * <p>This logger writes to the Minecraft server console with the
     * plugin prefix. It is provided as a convenience so modules can log
     * messages without repeatedly calling {@link JavaPlugin#getLogger()}.</p>
     */
    public Logger logger = getLogger();

    private DatabaseManager database;
    private ConfigManager configManager;
    private PlayerRepository playerRepo;
    private EventSystem eventSystem;

    private final List<com.krishcpatel.realm.core.module.Module> modules = new ArrayList<>();

    @Override
    public void onEnable() {
        logger.info("onEnable");

        database = new DatabaseManager(this);
        configManager = new ConfigManager(this);
        playerRepo = new PlayerRepository(database, this);
        eventSystem = new EventSystem(this);

        // create modules
        modules.add(new EconomyModule(this));
        modules.add(new JobsModule(this));

        try {
            database.connect();
            logger.info("Database connected.");
        } catch (Exception e) {
            logger.severe("Failed to initialize database. Disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // load config
        configManager.loadAll();

        // load realm command
        getCommand("realm").setExecutor(new RealmCommand(this));

        // load event system and subscribe to player upserted to db event
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, playerRepo), this);

        registerCoreEventSubscriptions();

        // enable modules
        for (com.krishcpatel.realm.core.module.Module module : modules) {
            try {
                module.enable();
            } catch (Exception e) {
                getLogger().severe("Failed to enable module");
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDisable() {
        logger.info("onDisable");

        // disable modules
        for (com.krishcpatel.realm.core.module.Module module : modules) {
            try {
                module.disable();
            } catch (Exception e) {
                getLogger().severe("Failed to enable module");
                e.printStackTrace();
            }
        }

        if (database != null) {
            database.close();
        }
    }

    /**
     * Reloads Realm configuration and re-applies any runtime settings.
     *
     * <p>This is invoked by {@code /realm reload}. Keep this method fast and safe
     * for use while the server is running.</p>
     *
     * @throws Exception if reload fails
     */
    public void reloadRealm() throws Exception {
        configManager.loadAll();

        boolean debug = config().getBoolean("plugin.debug", false);
        logger.info("Debug: " + debug);

        eventSystem.clearAll();
        registerCoreEventSubscriptions();

        for (Module module : modules) {
            module.reload();
        }
    }

    private void registerCoreEventSubscriptions() {
        eventSystem.subscribe(
                PlayerUpsertedEvent.class,
                evt -> logger.info("Player upserted: " + evt.username())
        );
    }

    /**
     * Retrieves a formatted message from {@code messages.yml}.
     *
     * <p>The configured plugin prefix is automatically prepended and
     * Minecraft color codes using {@code &} are translated.</p>
     *
     * <p>Placeholders in the message such as {@code %amount%} or
     * {@code %player%} will be replaced using the provided map.</p>
     *
     * @param key message key inside {@code messages.yml}
     * @param placeholders placeholder replacements
     * @return formatted and colorized message
     */
    public String msg(String key, Map<String, String> placeholders) {
        String prefix = messages().getString("prefix", "");
        String raw = messages().getString(key, "&cMissing message: " + key);

        String full = prefix + raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            full = full.replace(entry.getKey(), entry.getValue());
        }

        return ChatColor.translateAlternateColorCodes('&', full);
    }

    /**
     * Retrieves a formatted message from {@code messages.yml}
     * without any placeholder replacements.
     *
     * @param key message key inside {@code messages.yml}
     * @return formatted and colorized message
     */
    public String msg(String key) {
        return msg(key, Map.of());
    }

    /**
     * Logs a debug message if {@code debug: true} is enabled in {@code config.yml}.
     *
     * @param msg debug message
     */
    public void debug(String msg) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] " + msg);
        }
    }

    /**
     * Returns the active database manager.
     *
     * @return database manager
     */
    public DatabaseManager getDatabase() {
        return database;
    }


    /**
     * Returns the active configuration manager.
     *
     * @return configuration manager
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Convenience accessor for the loaded {@code config.yml}.
     *
     * @return plugin configuration
     */
    public FileConfiguration config() {
        return configManager.config();
    }

    /**
     * Returns the loaded {@code messages.yml} configuration.
     *
     * <p>This file contains all player-facing text such as
     * command responses and error messages.</p>
     *
     * @return messages configuration
     */
    public FileConfiguration messages() {
        return configManager.messages();
    }

    /**
     * Returns the loaded {@code jobs.yml} configuration.
     *
     * @return jobs configuration
     */
    public FileConfiguration jobsConfig() {
        return configManager.jobs();
    }

    /**
     * Returns the internal event bus used to publish and subscribe to {@link RealmEvent}s.
     *
     * @return event system
     */
    public EventSystem events() {
        return eventSystem;
    }
}
