package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.data.TransactionResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;

/**
 * Admin economy command executor for {@code /eco}.
 *
 * <p>Supported actions typically include:</p>
 * <ul>
 *   <li>{@code /eco give <player> <amount>}</li>
 *   <li>{@code /eco take <player> <amount>}</li>
 *   <li>{@code /eco set <player> <amount>}</li>
 * </ul>
 *
 * <p>All balance mutations are routed through {@link TransactionManager}
 * so they are atomic and recorded in the ledger.</p>
 */
public final class EconomyAdminCommand implements CommandExecutor {

    private final Core core;
    private final TransactionManager tx;

    /**
     * Creates an admin economy command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param tx transaction gateway used to mutate balances with ledger logging
     */
    public EconomyAdminCommand(Core core, TransactionManager tx) {
        this.core = core;
        this.tx = tx;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("realm.economy.admin")) {
            sender.sendMessage(color("&cNo permission."));
            return true;
        }

        if (args.length != 3) {
            sender.sendMessage(color("&7Usage: &f/eco <give|take|set> <player> <amount>"));
            return true;
        }

        String action = args[0].toLowerCase();
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(color("&cAmount must be a number."));
            return true;
        }

        if (amount < 0) {
            sender.sendMessage(color("&cAmount cannot be negative."));
            return true;
        }

        String uuid = target.getUniqueId().toString();
        String actor = (sender instanceof org.bukkit.entity.Player p) ? p.getUniqueId().toString() : "CONSOLE";

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                TransactionResult res;

                switch (action) {
                    case "give" -> res = tx.mint(uuid, amount, MoneySource.ADMIN, null, "Admin give", actor);
                    case "take" -> res = tx.burn(uuid, amount, MoneySource.ADMIN, null, "Admin take", actor);
                    case "set" -> res = tx.setBalance(uuid, amount, MoneySource.ADMIN, null, "Admin set", actor);
                    default -> {
                        reply(sender, "&7Usage: &f/eco <give|take|set> <player> <amount>");
                        return;
                    }
                }

                if (!res.success()) {
                    reply(sender, "&cFailed: &f" + res.message());
                    return;
                }

                reply(sender, "&aOK. Ledger id: &f#" + res.ledgerId());

                core.getServer().getScheduler().runTask(core, () -> {
                    if (target.isOnline() && target.getPlayer() != null) {
                        target.getPlayer().sendMessage(color("&eYour balance was updated by an admin."));
                    }
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] /eco failed: " + sender.getName());
                e.printStackTrace();
                reply(sender, "&cCommand failed. Check console.");
            }
        });

        return true;
    }

    private void reply(CommandSender sender, String msg) {
        core.getServer().getScheduler().runTask(core, () -> sender.sendMessage(color(msg)));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
