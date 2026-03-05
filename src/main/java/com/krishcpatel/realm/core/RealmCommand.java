package com.krishcpatel.realm.core;

import com.krishcpatel.realm.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Handles the {@code /realm} command.
 *
 * <p>Currently supports administrative actions such as {@code /realm reload}.</p>
 */
public class RealmCommand implements CommandExecutor {

    private final Core plugin;

    /**
     * Creates a command handler for the given plugin instance.
     *
     * @param plugin owning plugin
     */
    public RealmCommand(Core plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(color("&7Usage: &f/realm reload"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("realm.admin")) {
                sender.sendMessage(plugin.msg("no-permission"));
                return true;
            }

            try {
                plugin.reloadRealm();
                sender.sendMessage(plugin.msg("reloaded"));
            } catch (Exception e) {
                sender.sendMessage(color("&cReload failed. Check console."));
                e.printStackTrace();
            }
            return true;
        }

        sender.sendMessage(color("&7Usage: &f/realm reload"));
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}