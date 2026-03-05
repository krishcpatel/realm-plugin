package com.krishcpatel.realm.core;

import org.bukkit.ChatColor;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public final class Core extends JavaPlugin {

    public Logger logger = getLogger();

    private DatabaseManager database;
    private ConfigManager configManager;
    private PlayerRepository playerRepo;
    private EventSystem eventSystem;

    @Override
    public void onEnable() {
        logger.info("onEnable");

        database = new DatabaseManager(this);
        configManager = new ConfigManager(this);
        playerRepo = new PlayerRepository(database, this);
        this.eventSystem = new EventSystem(this);

        try {
            database.connect();
            logger.info("Database connected.");
        } catch (Exception e) {
            logger.severe("Failed to initialize database. Disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        configManager.loadAll();

        getCommand("realm").setExecutor(new RealmCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, playerRepo), this);

        eventSystem.subscribe(
                PlayerUpsertedEvent.class,
                evt -> logger.info("Player upserted: " + evt.username())
        );
    }

    @Override
    public void onDisable() {
        logger.info("onDisable");

        if (database != null) {
            database.close();
        }
    }

    public void reloadRealm() throws Exception {
        configManager.loadAll();

        boolean debug = config().getBoolean("debug", false);
        logger.info("Debug: " + debug);

        eventSystem.clearAll();
    }

    public String msg(String key) {
        String prefix = configManager.messages().getString("prefix", "&7[&6Realm&7]&r ");
        String val = configManager.messages().getString(key, "&cMissing message: " + key);
        return ChatColor.translateAlternateColorCodes('&', prefix + val);
    }

    public void debug(String msg) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] " + msg);
        }
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public org.bukkit.configuration.file.FileConfiguration config() {
        return configManager.config();
    }

    public EventSystem events() {
        return eventSystem;
    }
}
