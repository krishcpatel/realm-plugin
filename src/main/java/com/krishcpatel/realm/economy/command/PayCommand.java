package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.data.TransactionResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * Command executor for {@code /pay <player> <amount>}.
 *
 * <p>Routes all transfers through {@link TransactionManager} so the movement is
 * atomic and recorded in the ledger.</p>
 */
public final class PayCommand implements CommandExecutor {

    private final Core core;
    private final TransactionManager tx;

    /**
     * Creates a pay command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param tx transaction gateway used to perform the transfer
     */
    public PayCommand(Core core, TransactionManager tx) {
        this.core = core;
        this.tx = tx;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /pay."));
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(color("&7Usage: &f/pay <player> <amount>"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if ((!target.isOnline() && !target.hasPlayedBefore()) || target.getName() == null) {
            player.sendMessage(color("&cThat player does not exist or has never joined the server."));
            return true;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage(color("&cYou cannot pay yourself."));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(color("&cAmount must be a number."));
            return true;
        }

        if (amount <= 0) {
            player.sendMessage(color("&cAmount must be > 0."));
            return true;
        }

        String from = player.getUniqueId().toString();
        String to = target.getUniqueId().toString();

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                TransactionResult res = tx.transfer(
                        from,
                        to,
                        amount,
                        MoneySource.PAY,
                        null,
                        "Player payment",
                        from
                );

                core.getServer().getScheduler().runTask(core, () -> {
                    if (!res.success()) {
                        player.sendMessage(color("&cPayment failed: &f" + res.message()));
                        return;
                    }

                    player.sendMessage(color("&aPaid &f$" + amount + " &ato &f" + target.getName() + "&a."));

                    if (target.isOnline() && target.getPlayer() != null) {
                        target.getPlayer().sendMessage(color("&aYou received &f$" + amount + " &afrom &f" + player.getName() + "&a."));
                    }
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] /pay failed");
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(color("&cPayment failed. Check console."))
                );
            }
        });

        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}