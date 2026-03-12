package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.data.BankNoteIssueResult;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
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

        String playerUuid = player.getUniqueId().toString();

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                BankNoteIssueResult result = notes.issueNote(playerUuid, amount);

                core.getServer().getScheduler().runTask(core, () -> {
                    if (!result.success()) {
                        player.sendMessage(core.msg("withdraw.failed", Map.of(
                                "%reason%", result.message()
                        )));
                        return;
                    }

                    Player onlinePlayer = core.getServer().getPlayer(java.util.UUID.fromString(playerUuid));
                    if (onlinePlayer == null) {
                        core.getLogger().warning("[economy] Player went offline before bank note delivery, refunding: " + playerUuid);
                        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                            try {
                                boolean refunded = notes.refundIssuedNote(playerUuid, result.noteId(), result.amount());
                                if (!refunded) {
                                    core.getLogger().warning("[economy] Failed to refund undelivered bank note " + result.noteId()
                                            + " for " + playerUuid + " (note may have already been reconciled)");
                                }
                            } catch (Exception refundError) {
                                core.getLogger().severe("[economy] Failed to refund undelivered bank note " + result.noteId()
                                        + " for " + playerUuid);
                                refundError.printStackTrace();
                            }
                        });
                        return;
                    }

                    try {
                        notes.giveIssuedNote(onlinePlayer, result.noteId(), result.amount());
                        player.sendMessage(core.msg("withdraw.success", Map.of(
                                "%amount%", String.valueOf(amount)
                        )));
                    } catch (Exception deliveryError) {
                        core.getLogger().severe("[economy] Failed to deliver issued bank note " + result.noteId()
                                + " for " + playerUuid + ", refunding.");
                        deliveryError.printStackTrace();
                        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                            try {
                                notes.refundIssuedNote(playerUuid, result.noteId(), result.amount());
                            } catch (Exception refundError) {
                                core.getLogger().severe("[economy] Failed to refund undelivered bank note " + result.noteId()
                                        + " for " + playerUuid);
                                refundError.printStackTrace();
                            }
                        });
                        player.sendMessage(core.msg("general.command-failed"));
                    }
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
