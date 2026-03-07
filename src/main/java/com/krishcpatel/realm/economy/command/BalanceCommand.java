package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Command executor for {@code /bal} and {@code /balance}.
 *
 * <p>Reads the player's balance from the {@code economy_accounts} table via
 * {@link EconomyRepository}. Database work should be performed asynchronously
 * to avoid blocking the server thread.</p>
 */
public class BalanceCommand implements CommandExecutor {
    private final Core core;
    private final EconomyRepository repo;

    /**
     * Creates a balance command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param repo economy repository used to read balances
     */
    public BalanceCommand(Core core, EconomyRepository repo) {
        this.core = core;
        this.repo = repo;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return true;
        }

        // Read balance async
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                String uuid = player.getUniqueId().toString();
                repo.ensureAccount(uuid);
                long balance = repo.getBalance(uuid);

                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("balance.self", Map.of(
                                "%balance%", String.valueOf(balance)
                        )))
                );

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to get balance for " + player.getName());
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
