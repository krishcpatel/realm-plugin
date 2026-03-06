package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles the {@code /withdraw <amount>} command.
 *
 * <p>This command converts money from a player's bank account into a physical
 * bank note item.</p>
 */
public final class WithdrawCommand implements CommandExecutor {

    private final Core core;
    private final BankNoteManager notes;

    /**
     * Creates a withdraw command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param notes bank note manager used to issue notes
     */
    public WithdrawCommand(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use this command."));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(color("&7Usage: &f/withdraw <amount>"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(color("&cAmount must be a number."));
            return true;
        }

        if (amount <= 0) {
            player.sendMessage(color("&cAmount must be greater than 0."));
            return true;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                TransactionResult result = notes.issueNote(player, amount);

                core.getServer().getScheduler().runTask(core, () -> {
                    if (!result.success()) {
                        player.sendMessage(color("&cWithdraw failed: &f" + result.message()));
                        return;
                    }

                    player.sendMessage(color("&aCreated bank note worth &f$" + amount + "&a."));
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to withdraw bank note for " + player.getName());
                e.printStackTrace();

                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(color("&cWithdraw failed. Check console."))
                );
            }
        });

        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}