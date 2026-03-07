package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
import com.krishcpatel.realm.economy.data.TransactionResult;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

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
     * @param notes banknote manager used to issue notes
     */
    public WithdrawCommand(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return true;
        }

        if (!core.config().getBoolean("economy.withdraw.enabled", true)) {
            player.sendMessage(core.msg("withdraw.disabled"));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(core.msg("withdraw.usage"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(core.msg("general.invalid-number"));
            return true;
        }

        long min = core.config().getLong("economy.withdraw.min-amount", 1L);
        long max = core.config().getLong("economy.withdraw.max-amount", 1_000_000L);

        if (amount < min || amount > max) {
            player.sendMessage(core.msg("general.invalid-amount"));
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
                        player.sendMessage(core.msg("withdraw.failed", Map.of(
                                "%reason%", result.message()
                        )));
                        return;
                    }

                    player.sendMessage(core.msg("withdraw.success", Map.of(
                            "%amount%", String.valueOf(amount)
                    )));
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to withdraw bank note for " + player.getName());
                e.printStackTrace();

                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("general.command-failed"))
                );
            }
        });

        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}