package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
            sender.sendMessage(color("&cOnly players can use this command."));
            return true;
        }

        // Read balance async
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                String uuid = player.getUniqueId().toString();

                // Ensure account exists (safe even if it already exists)
                repo.ensureAccount(uuid);

                long bal = repo.getBalance(uuid);

                core.getServer().getScheduler().runTask(core, () -> {
                    player.sendMessage(color("&7Balance: &a$" + bal));
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to read balance for " + player.getName());
                e.printStackTrace();

                core.getServer().getScheduler().runTask(core, () -> {
                    player.sendMessage(color("&cFailed to load your balance. Check console."));
                });
            }
        });

        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
