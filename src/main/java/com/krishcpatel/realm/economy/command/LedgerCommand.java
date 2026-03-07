package com.krishcpatel.realm.economy.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.data.LedgerEntry;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Admin command to view recent ledger entries for a player.
 *
 * <p>Usage: {@code /ledger <player> [n]}</p>
 * <p>Reads from {@code economy_ledger} via {@link LedgerRepository}.</p>
 */
public final class LedgerCommand implements CommandExecutor {

    private final Core core;
    private final LedgerRepository ledger;

    /**
     * Creates a ledger command executor.
     *
     * @param core plugin instance used for scheduling and logging
     * @param ledger repository used to query ledger entries
     */
    public LedgerCommand(Core core, LedgerRepository ledger) {
        this.core = core;
        this.ledger = ledger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("realm.economy.ledger")) {
            sender.sendMessage(color("&cNo permission."));
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(color("&7Usage: &f/ledger <player> [n]"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        int n = 10;
        if (args.length == 2) {
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }

        String uuid = target.getUniqueId().toString();
        int limit = Math.max(1, Math.min(n, 50));

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                List<LedgerEntry> entries = ledger.getRecentForPlayer(core.getDatabase().getConnection(), uuid, limit);

                core.getServer().getScheduler().runTask(core, () -> {
                    sender.sendMessage(color("&7--- &fLedger for &e" + target.getName() + " &7(last " + limit + ") ---"));
                    if (entries.isEmpty()) {
                        sender.sendMessage(color("&7No ledger entries."));
                        return;
                    }

                    SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");

                    for (LedgerEntry e : entries) {
                        String when = fmt.format(new Date(e.createdAt()));
                        String dir;
                        if (uuid.equalsIgnoreCase(e.fromUuid())) dir = "&c-";
                        else if (uuid.equalsIgnoreCase(e.toUuid())) dir = "&a+";
                        else dir = "&7";

                        String other = "";
                        if ("TRANSFER".equalsIgnoreCase(e.type())) {
                            if (uuid.equalsIgnoreCase(e.fromUuid())) other = " -> " + shortUuid(e.toUuid());
                            else other = " <- " + shortUuid(e.fromUuid());
                        }

                        sender.sendMessage(color("&8#" + e.id() + " &7[" + when + "] "
                                + "&f" + e.source()
                                + " &7" + e.type()
                                + " " + dir + "$" + e.amount()
                                + "&7" + other
                        ));
                    }
                });

            } catch (Exception ex) {
                core.getLogger().severe("[economy] /ledger failed");
                ex.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        sender.sendMessage(color("&cFailed to load ledger. Check console."))
                );
            }
        });

        return true;
    }

    private String shortUuid(String uuid) {
        if (uuid == null) return "null";
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}