package com.krishcpatel.realm.core;

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
    private File messagesFile;

    /**
     * Creates a new configuration manager for the given plugin.
     *
     * @param plugin owning plugin instance
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads (or reloads) {@code config.yml} and {@code messages.yml} from the plugin data folder.
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
}
