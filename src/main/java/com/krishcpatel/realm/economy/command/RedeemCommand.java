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
 * Handles the {@code /redeem} command.
 *
 * <p>This command redeems the banknote currently held in the
 * player's main hand and converts it back into bank balance.</p>
 */
public final class RedeemCommand implements CommandExecutor {

    private final Core core;
    private final BankNoteManager notes;

    /**
     * Creates a redeem command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param notes banknote manager used to redeem notes
     */
    public RedeemCommand(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return true;
        }

        if (!core.config().getBoolean("economy.redeem.enabled", true)
                || !core.config().getBoolean("economy.redeem.command-enabled", true)) {
            player.sendMessage(core.msg("redeem.disabled"));
            return true;
        }

        if (args.length != 0) {
            player.sendMessage(core.msg("redeem.usage"));
            return true;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                TransactionResult result = notes.redeemHeldNote(player);

                core.getServer().getScheduler().runTask(core, () -> {
                    if (!result.success()) {
                        player.sendMessage(core.msg("redeem.failed", Map.of(
                                "%reason%", result.message()
                        )));
                        return;
                    }

                    player.sendMessage(core.msg("redeem.success"));
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to redeem bank note for " + player.getName());
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