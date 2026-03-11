package com.krishcpatel.realm.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads and exposes Realm configuration files.
 *
 * <p>Typically manages:</p>
 * <ul>
 *   <li>{@code config.yml} (settings, feature toggles, debug flags)</li>
 *   <li>{@code messages.yml} (prefix + translatable messages)</li>
 * </ul>
 *
 * <p>Call {@link #loadAll()} on startup and during {@code /realm reload}.</p>
 */
public class ConfigManager {
    private final JavaPlugin plugin;

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration jobs;
    private File messagesFile;
    private File jobsFile;

    /**
     * Creates a new configuration manager for the given plugin.
     *
     * @param plugin owning plugin instance
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads (or reloads) {@code config.yml}, {@code messages.yml}, and {@code jobs.yml}
     * from the plugin data folder.
     *
     * <p>If defaults do not exist, they are saved from resources.</p>
     */
    public void loadAll() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        jobsFile = new File(plugin.getDataFolder(), "jobs.yml");
        if (!jobsFile.exists()) {
            plugin.saveResource("jobs.yml", false);
        }
        jobs = YamlConfiguration.loadConfiguration(jobsFile);
    }

    /**
     * Returns the loaded {@code config.yml}.
     *
     * @return main plugin configuration
     */
    public FileConfiguration config() {
        return config;
    }

    /**
     * Returns the loaded {@code messages.yml}.
     *
     * @return message configuration
     */
    public FileConfiguration messages() {
        return messages;
    }

    /**
     * Returns the loaded {@code jobs.yml}.
     *
     * @return jobs configuration
     */
    public FileConfiguration jobs() {
        return jobs;
    }
}
