package com.krishcpatel.realm.core.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.gui.GuiModule;
import com.krishcpatel.realm.nexo.NexoHook;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles the {@code /realm} command.
 *
 * <p>Currently supports administrative actions such as {@code /realm reload}.</p>
 */
public class RealmCommand implements CommandExecutor {

    private final Core plugin;
    private final GuiModule gui;
    private final NexoHook nexo;

    /**
     * Creates a command handler for the given plugin instance.
     *
     * @param plugin owning plugin
     */
    public RealmCommand(Core plugin, GuiModule gui, NexoHook nexo) {
        this.plugin = plugin;
        this.gui = gui;
        this.nexo = nexo;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            if (sender instanceof Player player) {
                gui.openMainMenu(player);
            } else {
                sender.sendMessage(color("&7Usage: &f/realm [reload]"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("realm.admin")) {
                sender.sendMessage(plugin.msg("general.no-permission"));
                return true;
            }

            try {
                plugin.reloadRealm();
                sender.sendMessage(plugin.msg("general.reload-success"));
            } catch (Exception e) {
                sender.sendMessage(color("&cReload failed. Check console."));
                e.printStackTrace();
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("giveitem")) {
            if (!sender.hasPermission("realm.admin")) {
                sender.sendMessage(plugin.msg("general.no-permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.msg("general.player-only"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(color("&7Usage: &f/realm giveitem <nexo_item_id> [amount]"));
                return true;
            }

            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(plugin.msg("general.invalid-number"));
                    return true;
                }
            }

            String itemId = NexoHook.normalizeItemId(args[1]);
            if (!nexo.itemsLoaded()) {
                sender.sendMessage(color("&cNexo items are not loaded yet. Try again after Nexo finishes loading."));
                return true;
            }
            if (!nexo.giveItem(player, itemId, amount)) {
                sender.sendMessage(color("&cUnknown Nexo item: &f" + itemId));
                return true;
            }

            sender.sendMessage(color("&aGave &f" + amount + "x " + itemId + "&a."));
            return true;
        }

        sender.sendMessage(color("&7Usage: &f/realm [reload|giveitem]"));
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
