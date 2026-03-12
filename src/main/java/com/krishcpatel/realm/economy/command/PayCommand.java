package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.data.TransactionResult;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Map;

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
            sender.sendMessage(core.msg("general.player-only"));
            return true;
        }

        if (!core.config().getBoolean("economy.payments.enabled", true)) {
            player.sendMessage(core.msg("pay.disabled"));
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(core.msg("pay.usage"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if ((!target.isOnline() && !target.hasPlayedBefore()) || target.getName() == null) {
            player.sendMessage(core.msg("pay.not-found"));
            return true;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage(core.msg("pay.self-pay"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(core.msg("general.invalid-number"));
            return true;
        }

        long min = core.config().getLong("economy.payments.min-amount", 1L);
        long max = core.config().getLong("economy.payments.max-amount", 1_000_000L);
        long low = Math.min(min, max);
        long high = Math.max(min, max);
        if (amount < low || amount > high) {
            player.sendMessage(core.msg("general.invalid-amount"));
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
                        player.sendMessage(core.msg("pay.failed", Map.of(
                                "%reason%", res.message()
                        )));
                        return;
                    }

                    player.sendMessage(core.msg("pay.success-sender", Map.of(
                            "%amount%", String.valueOf(amount),
                            "%target%", target.getName()
                    )));

                    if (target.isOnline() && target.getPlayer() != null) {
                        target.getPlayer().sendMessage(core.msg("pay.success-target", Map.of(
                                "%amount%", String.valueOf(amount),
                                "%sender%", player.getName()
                        )));
                    }
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] /pay failed");
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("general.command-failed"))
                );
            }
        });

        return true;
    }
}
