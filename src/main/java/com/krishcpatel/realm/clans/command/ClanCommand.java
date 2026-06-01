package com.krishcpatel.realm.clans.command;

import com.krishcpatel.realm.clans.model.ClanClaimRecord;
import com.krishcpatel.realm.clans.model.ClanRecord;
import com.krishcpatel.realm.clans.service.ClansService;
import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.data.TransactionResult;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.payment.NotePaymentMenuService;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles {@code /clan} commands.
 */
public final class ClanCommand implements CommandExecutor {
    private final Core core;
    private final ClansService clans;
    private final NotePaymentMenuService payments;
    private final TransactionManager tx;

    /**
     * Creates a clan command handler.
     *
     * @param core plugin core
     * @param clans clans service
     * @param payments note payment menu
     * @param tx transaction manager
     */
    public ClanCommand(Core core, ClansService clans, NotePaymentMenuService payments, TransactionManager tx) {
        this.core = core;
        this.clans = clans;
        this.payments = payments;
        this.tx = tx;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&7Usage: &f/clan <create|join|leave|disband|info|flag|protect|fee|bank|upgrade|claims|admin>"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> handleCreate(sender, args);
            case "join" -> handleJoin(sender, args);
            case "leave" -> handleLeave(sender);
            case "disband" -> handleDisband(sender);
            case "info" -> handleInfo(sender, args);
            case "flag" -> handleFlag(sender, args);
            case "protect" -> handleProtect(sender, args);
            case "fee" -> handleFee(sender, args);
            case "bank" -> handleBank(sender, args);
            case "upgrade" -> handleUpgrade(sender);
            case "claims" -> handleClaims(sender);
            case "admin" -> handleAdmin(sender, args);
            default -> sender.sendMessage(color("&7Usage: &f/clan <create|join|leave|disband|info|flag|protect|fee|bank|upgrade|claims|admin>"));
        }

        return true;
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(color("&7Usage: &f/clan create <tag> <name>"));
            return;
        }

        String tag = args[1];
        String name = String.join(" ", List.of(args).subList(2, args.length));
        long fee = clans.creationFee();
        if (fee <= 0L) {
            runAsync(player, () -> clans.createClan(player.getUniqueId().toString(), tag, name));
            return;
        }

        payments.openPaymentMenu(
                player,
                "&8Clan Creation Payment",
                fee,
                "Clan creation fee",
                (payerUuid, requiredAmount, depositedAmount) -> {
                    TransactionResult burn = tx.burn(
                            payerUuid,
                            requiredAmount,
                            MoneySource.UPKEEP,
                            "clan:create",
                            "Clan creation fee",
                            payerUuid
                    );
                    if (!burn.success()) {
                        return NotePaymentMenuService.NotePaymentOutcome.fail("Could not collect clan creation fee: " + burn.message());
                    }

                    ClansService.ActionResult created = clans.createClan(payerUuid, tag, name);
                    if (created.success()) {
                        return NotePaymentMenuService.NotePaymentOutcome.ok(created.message());
                    }

                    try {
                        tx.mint(
                                payerUuid,
                                requiredAmount,
                                MoneySource.SYSTEM,
                                "clan:create:refund",
                                "Refund failed clan creation fee",
                                "SYSTEM"
                        );
                    } catch (Exception e) {
                        core.getLogger().severe("[clans] Failed to refund clan creation fee after create error");
                        e.printStackTrace();
                    }
                    return NotePaymentMenuService.NotePaymentOutcome.fail(created.message());
                }
        );
    }

    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&7Usage: &f/clan join <tag>"));
            return;
        }
        String tag = args[1];
        runAsync(player, () -> clans.joinClan(player.getUniqueId().toString(), tag));
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        runAsync(player, () -> clans.leaveClan(player.getUniqueId().toString()));
    }

    private void handleDisband(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        runAsync(player, () -> clans.disbandClan(player.getUniqueId().toString()));
    }

    private void handleInfo(CommandSender sender, String[] args) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                Optional<ClanRecord> clanOpt;
                if (args.length >= 2) {
                    clanOpt = clans.findClanByTag(args[1]);
                } else if (sender instanceof Player player) {
                    clanOpt = clans.findClanByPlayer(player.getUniqueId().toString());
                } else {
                    send(sender, "&7Usage: &f/clan info <tag>");
                    return;
                }

                if (clanOpt.isEmpty()) {
                    send(sender, "&cClan not found.");
                    return;
                }

                ClanRecord clan = clanOpt.get();
                send(sender, "&7--- &6Clan Info &7---");
                for (String line : clans.describeClan(clan)) {
                    send(sender, line);
                }
            } catch (Exception e) {
                core.getLogger().severe("[clans] Failed /clan info");
                e.printStackTrace();
                send(sender, "&cFailed to load clan info.");
            }
        });
    }

    private void handleFlag(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("set")) {
            player.sendMessage(color("&7Usage: &f/clan flag set"));
            return;
        }
        runAsync(player, () -> clans.saveFlagSignature(
                player.getUniqueId().toString(),
                player.getInventory().getItemInMainHand()
        ));
    }

    private void handleProtect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&7Usage: &f/clan protect <add|remove>"));
            return;
        }

        Block target = player.getTargetBlockExact(8);
        if (target == null) {
            player.sendMessage(color("&cLook at a nearby container block."));
            return;
        }
        if (!(target.getState() instanceof Container)) {
            player.sendMessage(color("&cLook at a chest, barrel, or container block."));
            return;
        }

        String world = target.getWorld().getName();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (mode.equals("add")) {
            runAsync(player, () -> clans.addProtectedStorage(player.getUniqueId().toString(), world, x, y, z));
            return;
        }
        if (mode.equals("remove")) {
            runAsync(player, () -> clans.removeProtectedStorage(player.getUniqueId().toString(), world, x, y, z));
            return;
        }
        player.sendMessage(color("&7Usage: &f/clan protect <add|remove>"));
    }

    private void handleFee(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length < 2 || args.length > 3) {
            player.sendMessage(color("&7Usage: &f/clan fee <none|one_time|daily|weekly> [amount]"));
            return;
        }

        String type = args[1];
        long amount = 0L;
        if (args.length == 3) {
            try {
                amount = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(core.msg("general.invalid-number"));
                return;
            }
        }
        long parsedAmount = amount;
        runAsync(player, () -> clans.setClanFee(player.getUniqueId().toString(), type, parsedAmount));
    }

    private void handleBank(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length == 1) {
            core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                Optional<ClanRecord> clanOpt = clans.findClanByPlayer(player.getUniqueId().toString());
                if (clanOpt.isEmpty()) {
                    send(player, "&cYou are not in a clan.");
                    return;
                }
                ClanRecord clan = clanOpt.get();
                send(player, "&7Clan bank for &6[" + clan.tag() + "] " + clan.name() + "&7: &a$" + clan.bankBalance());
            });
            return;
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("deposit")) {
            long amount;
            try {
                amount = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(core.msg("general.invalid-number"));
                return;
            }
            long parsedAmount = amount;
            runAsync(player, () -> clans.depositToClanBank(player.getUniqueId().toString(), parsedAmount));
            return;
        }

        player.sendMessage(color("&7Usage: &f/clan bank [deposit <amount>]"));
    }

    private void handleUpgrade(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            Optional<ClanRecord> clanOpt = clans.findClanByPlayer(player.getUniqueId().toString());
            if (clanOpt.isEmpty()) {
                send(player, "&cYou are not in a clan.");
                return;
            }
            ClanRecord clan = clanOpt.get();
            if (!player.getUniqueId().toString().equals(clan.leaderUuid())) {
                send(player, "&cOnly the clan leader can upgrade.");
                return;
            }

            long cost = clans.nextUpgradeCost(clan);
            if (cost <= 0L) {
                send(player, "&cYour clan is already at max level.");
                return;
            }

            core.getServer().getScheduler().runTask(core, () -> payments.openPaymentMenu(
                    player,
                    "&8Clan Upgrade Payment",
                    cost,
                    "Clan upgrade fee",
                    (payerUuid, requiredAmount, depositedAmount) -> {
                        TransactionResult burn = tx.burn(
                                payerUuid,
                                requiredAmount,
                                MoneySource.UPKEEP,
                                "clan:upgrade",
                                "Clan upgrade fee",
                                payerUuid
                        );
                        if (!burn.success()) {
                            return NotePaymentMenuService.NotePaymentOutcome.fail("Could not collect clan upgrade fee: " + burn.message());
                        }

                        ClansService.ActionResult upgrade = clans.upgradeClanAfterPayment(payerUuid, requiredAmount);
                        if (!upgrade.success()) {
                            try {
                                tx.mint(
                                        payerUuid,
                                        requiredAmount,
                                        MoneySource.SYSTEM,
                                        "clan:upgrade:refund",
                                        "Refund failed clan upgrade payment",
                                        "SYSTEM"
                                );
                            } catch (Exception e) {
                                core.getLogger().severe("[clans] Failed to refund clan upgrade fee after upgrade error");
                                e.printStackTrace();
                            }
                            return NotePaymentMenuService.NotePaymentOutcome.fail(upgrade.message());
                        }
                        return NotePaymentMenuService.NotePaymentOutcome.ok(upgrade.message());
                    }
            ));
        });
    }

    private void handleClaims(CommandSender sender) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            List<ClanClaimRecord> claims = clans.claimsSnapshot();
            if (claims.isEmpty()) {
                send(sender, "&7No clan claims exist.");
                return;
            }
            send(sender, "&7--- &6Clan Claims &7---");
            for (ClanClaimRecord claim : claims) {
                ClanRecord clan = clans.clanById(claim.clanId());
                String clanName = clan == null ? ("#" + claim.clanId()) : ("[" + clan.tag() + "] " + clan.name());
                send(sender, "&7#" + claim.id() + " &f" + clanName + " &8- &7" + claim.world()
                        + " &f(" + claim.x() + ", " + claim.y() + ", " + claim.z() + ")");
            }
        });
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realm.clans.admin")) {
            sender.sendMessage(core.msg("general.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&7Usage: &f/clan admin <removeclaim|purgespawn> ..."));
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("removeclaim")) {
            if (args.length != 3) {
                sender.sendMessage(color("&7Usage: &f/clan admin removeclaim <claimId>"));
                return;
            }
            long claimId;
            try {
                claimId = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(core.msg("general.invalid-number"));
                return;
            }
            String actor = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
            core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                ClansService.ActionResult res = clans.removeClaim(actor, claimId, true);
                send(sender, (res.success() ? "&a" : "&c") + res.message());
            });
            return;
        }

        if (sub.equals("purgespawn")) {
            double radius = Math.max(0.0, core.config().getDouble("clans.claim.min-distance-from-spawn", 128.0));
            if (args.length == 3) {
                try {
                    radius = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(core.msg("general.invalid-number"));
                    return;
                }
            }
            double finalRadius = radius;
            core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                int removed = clans.purgeClaimsNearSpawn(finalRadius);
                send(sender, "&aRemoved &f" + removed + "&a claim(s) near spawn.");
            });
            return;
        }

        sender.sendMessage(color("&7Usage: &f/clan admin <removeclaim|purgespawn> ..."));
    }

    private void runAsync(Player player, ActionSupplier action) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            ClansService.ActionResult result = action.get();
            send(player, (result.success() ? "&a" : "&c") + result.message());
        });
    }

    private void send(CommandSender sender, String message) {
        core.getServer().getScheduler().runTask(core, () -> sender.sendMessage(color(message)));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    @FunctionalInterface
    private interface ActionSupplier {
        ClansService.ActionResult get();
    }
}
