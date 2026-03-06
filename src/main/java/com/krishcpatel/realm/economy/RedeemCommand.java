package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles the {@code /redeem} command.
 *
 * <p>This command redeems the bank note currently held in the
 * player's main hand and converts it back into bank balance.</p>
 */
public final class RedeemCommand implements CommandExecutor {

    private final Core core;
    private final BankNoteManager notes;

    /**
     * Creates a redeem command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param notes bank note manager used to redeem notes
     */
    public RedeemCommand(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use this command."));
            return true;
        }

        if (args.length != 0) {
            player.sendMessage(color("&7Usage: &f/redeem"));
            return true;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                TransactionResult result = notes.redeemHeldNote(player);

                core.getServer().getScheduler().runTask(core, () -> {
                    if (!result.success()) {
                        player.sendMessage(color("&cRedeem failed: &f" + result.message()));
                        return;
                    }

                    player.sendMessage(color("&aBank note redeemed successfully."));
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to redeem bank note for " + player.getName());
                e.printStackTrace();

                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(color("&cRedeem failed. Check console."))
                );
            }
        });

        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}