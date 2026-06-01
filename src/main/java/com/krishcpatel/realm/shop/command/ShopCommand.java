package com.krishcpatel.realm.shop.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.data.TransactionResult;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.payment.NotePaymentMenuService;
import com.krishcpatel.realm.shop.gui.ShopSetupMenuService;
import com.krishcpatel.realm.shop.model.ShopAreaRecord;
import com.krishcpatel.realm.shop.service.ShopService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles {@code /shop} commands.
 */
public final class ShopCommand implements CommandExecutor {
    private final Core core;
    private final ShopService shops;
    private final NotePaymentMenuService payments;
    private final ShopSetupMenuService setupMenu;
    private final TransactionManager tx;

    private final Map<UUID, Location> pos1 = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2 = new ConcurrentHashMap<>();

    /**
     * Creates a shop command handler.
     *
     * @param core plugin core
     * @param shops shop service
     * @param payments note payment menu
     * @param setupMenu shop setup menu service
     * @param tx transaction manager
     */
    public ShopCommand(
            Core core,
            ShopService shops,
            NotePaymentMenuService payments,
            ShopSetupMenuService setupMenu,
            TransactionManager tx
    ) {
        this.core = core;
        this.shops = shops;
        this.payments = payments;
        this.setupMenu = setupMenu;
        this.tx = tx;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                openShopHub(player);
            } else {
                sender.sendMessage(color("&7Usage: &f" + usage()));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> {
                if (sender instanceof Player player) {
                    openShopHub(player);
                } else {
                    sender.sendMessage(core.msg("general.player-only"));
                }
            }
            case "list" -> handleList(sender);
            case "claim" -> handleClaim(sender, args);
            case "myareas" -> handleMyAreas(sender);
            case "payupkeep" -> handlePayUpkeep(sender, args);
            case "setup" -> handleSetup(sender);
            case "setprice" -> handleSetPrice(sender, args);
            case "removelisting" -> handleRemoveListing(sender, args);
            case "listing" -> handleListings(sender);
            case "listings" -> handleListings(sender);
            case "admin" -> handleAdmin(sender, args);
            default -> sender.sendMessage(color("&7Usage: &f" + usage()));
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            List<ShopAreaRecord> areas = shops.listAreas();
            if (areas.isEmpty()) {
                send(sender, "&7No shop areas have been configured.");
                return;
            }
            send(sender, "&7--- &6Shop Areas &7---");
            for (ShopAreaRecord area : areas) {
                String owner = (area.ownerUuid() == null || area.ownerUuid().isBlank())
                        ? "&aUnclaimed"
                        : "&eClaimed";
                send(sender, "&f" + area.areaKey()
                        + " &8| &7Claim: &a$" + area.claimFee()
                        + " &8| &7Upkeep: &6$" + area.upkeepFee()
                        + " &8| " + owner);
            }
        });
    }

    private void handleClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length > 2) {
            player.sendMessage(color("&7Usage: &f/shop claim [area]"));
            return;
        }

        String world = player.getWorld().getName();
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            var areaOpt = args.length == 2
                    ? shops.findAreaByKey(args[1])
                    : shops.findAreaContaining(world, x, y, z);
            if (areaOpt.isEmpty()) {
                send(player, "&cNo claimable shop area was found.");
                return;
            }
            startClaimFlow(player, areaOpt.get());
        });
    }

    private void handleMyAreas(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            List<ShopAreaRecord> areas = shops.listOwnedAreas(player.getUniqueId().toString());
            if (areas.isEmpty()) {
                send(player, "&7You do not own any shop areas.");
                return;
            }
            send(player, "&7--- &6Your Shop Areas &7---");
            for (ShopAreaRecord area : areas) {
                long remainingMs = Math.max(0L, area.upkeepNextAt() - System.currentTimeMillis());
                long remainingHours = (remainingMs + 3_599_999L) / 3_600_000L;
                send(player, "&f" + area.areaKey()
                        + " &8| &7Upkeep: &6$" + area.upkeepFee()
                        + " &8| &7Due in: &f" + remainingHours + "h");
            }
        });
    }

    private void handlePayUpkeep(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length > 2) {
            player.sendMessage(color("&7Usage: &f/shop payupkeep <area>"));
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            ShopAreaRecord area;
            if (args.length == 2) {
                var areaOpt = shops.findAreaByKey(args[1]);
                if (areaOpt.isEmpty()) {
                    send(player, "&cShop area not found.");
                    return;
                }
                area = areaOpt.get();
            } else {
                List<ShopAreaRecord> owned = shops.listOwnedAreas(player.getUniqueId().toString());
                if (owned.isEmpty()) {
                    send(player, "&cYou do not own any shop areas.");
                    return;
                }
                if (owned.size() > 1) {
                    send(player, "&eYou own multiple areas. Use &f/shop payupkeep <area>&e.");
                    return;
                }
                area = owned.get(0);
            }
            if (!player.getUniqueId().toString().equals(area.ownerUuid())) {
                send(player, "&cYou do not own this area.");
                return;
            }

            long fee = Math.max(0L, area.upkeepFee());
            if (fee <= 0L) {
                ShopService.ActionResult paid = shops.payUpkeepAfterPayment(player.getUniqueId().toString(), area.areaKey());
                send(player, (paid.success() ? "&a" : "&c") + paid.message());
                return;
            }
            core.getServer().getScheduler().runTask(core, () -> payments.openPaymentMenu(
                    player,
                    "&8Shop Upkeep Payment",
                    fee,
                    "Shop upkeep fee",
                    (payerUuid, requiredAmount, depositedAmount) -> {
                        TransactionResult charged = shops.collectUpkeepFee(payerUuid, requiredAmount, area.areaKey());
                        if (!charged.success()) {
                            return NotePaymentMenuService.NotePaymentOutcome.fail("Could not collect upkeep fee: " + charged.message());
                        }

                        ShopService.ActionResult paid = shops.payUpkeepAfterPayment(payerUuid, area.areaKey());
                        if (paid.success()) {
                            return NotePaymentMenuService.NotePaymentOutcome.ok(paid.message());
                        }

                        try {
                            tx.mint(
                                    payerUuid,
                                    requiredAmount,
                                    MoneySource.SYSTEM,
                                    "shop:upkeep:refund",
                                    "Refund failed shop upkeep payment",
                                    "SYSTEM"
                            );
                        } catch (Exception e) {
                            core.getLogger().severe("[shop] Failed upkeep refund after upkeep error.");
                            e.printStackTrace();
                        }
                        return NotePaymentMenuService.NotePaymentOutcome.fail(paid.message());
                    }
            ));
        });
    }

    private void handleSetup(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }

        Block target = player.getTargetBlockExact(8);
        if (target == null || !(target.getState() instanceof Container)) {
            player.sendMessage(color("&cLook at a shop chest/container first."));
            return;
        }

        setupMenu.openSetupMenu(player, target);
    }

    private void handleSetPrice(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length != 2 && args.length != 3) {
            player.sendMessage(color("&7Usage: &f/shop setprice <price> &7or &f/shop setprice <slot> <price>"));
            return;
        }

        long price;

        Block target = player.getTargetBlockExact(8);
        if (target == null || !(target.getState() instanceof Container container)) {
            player.sendMessage(color("&cLook at a shop chest/container."));
            return;
        }

        int slot;
        if (args.length == 2) {
            try {
                price = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(core.msg("general.invalid-number"));
                return;
            }
            slot = findFirstNonEmptySlot(container);
            if (slot < 0) {
                player.sendMessage(color("&cPut a sample item into the chest first."));
                return;
            }
        } else {
            try {
                slot = Integer.parseInt(args[1]) - 1;
                price = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(core.msg("general.invalid-number"));
                return;
            }
        }

        if (slot < 0 || slot >= container.getInventory().getSize()) {
            player.sendMessage(color("&cThat slot is outside this container."));
            return;
        }
        var sample = container.getInventory().getItem(slot);
        if (sample == null || sample.getType() == org.bukkit.Material.AIR) {
            player.sendMessage(color("&cPut a sample item in that slot first."));
            return;
        }

        String world = target.getWorld().getName();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        String material = sample.getType().name();
        int unitAmount = Math.max(1, sample.getAmount());

        int parsedSlot = slot;
        long parsedPrice = price;
        runAsync(player, () -> shops.setListing(
                player.getUniqueId().toString(),
                world,
                x,
                y,
                z,
                parsedSlot,
                material,
                unitAmount,
                parsedPrice
        ));
    }

    private void handleRemoveListing(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&7Usage: &f/shop removelisting <slot>"));
            return;
        }

        int slot;
        try {
            slot = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            player.sendMessage(core.msg("general.invalid-number"));
            return;
        }

        Block target = player.getTargetBlockExact(8);
        if (target == null || !(target.getState() instanceof Container)) {
            player.sendMessage(color("&cLook at a shop chest/container."));
            return;
        }

        String world = target.getWorld().getName();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        int parsedSlot = slot;
        runAsync(player, () -> shops.removeListing(player.getUniqueId().toString(), world, x, y, z, parsedSlot));
    }

    private void handleListings(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }

        Block target = player.getTargetBlockExact(8);
        if (target == null || !(target.getState() instanceof Container)) {
            player.sendMessage(color("&cLook at a shop chest/container."));
            return;
        }

        String world = target.getWorld().getName();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            var listings = shops.listingsForChestLocation(world, x, y, z);
            if (listings.isEmpty()) {
                send(player, "&7No listings configured for this chest.");
                return;
            }
            send(player, "&7--- &6Shop Listings (" + listings.size() + ") &7---");
            for (ShopService.ShopListingView view : listings) {
                send(player, "&fID " + view.listing().id()
                        + " &8| &7Slot &f" + (view.listing().slotIndex() + 1)
                        + " &8| &7" + view.listing().material()
                        + " x" + view.listing().unitAmount()
                        + " &8| &a$" + view.listing().price());
            }
        });
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realm.shop.admin")) {
            sender.sendMessage(core.msg("general.no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(color("&7Usage: &f/shop admin <pos1|pos2|create|createauto|remove> ..."));
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1" -> {
                Location raw = player.getLocation();
                Location adjusted = new Location(
                        raw.getWorld(),
                        raw.getBlockX(),
                        raw.getWorld().getMaxHeight() - 1,
                        raw.getBlockZ()
                );
                pos1.put(player.getUniqueId(), adjusted);
                player.sendMessage(color("&aSet shop area position 1 (X/Z with max build height Y)."));
            }
            case "pos2" -> {
                Location raw = player.getLocation();
                Location adjusted = new Location(
                        raw.getWorld(),
                        raw.getBlockX(),
                        raw.getWorld().getMinHeight(),
                        raw.getBlockZ()
                );
                pos2.put(player.getUniqueId(), adjusted);
                player.sendMessage(color("&aSet shop area position 2 (X/Z with world minimum Y)."));
            }
            case "create" -> {
                if (args.length != 5) {
                    player.sendMessage(color("&7Usage: &f/shop admin create <area> <claimFee> <upkeepFee>"));
                    return;
                }

                String areaKey = args[2];
                long claimFee;
                long upkeepFee;
                try {
                    claimFee = Long.parseLong(args[3]);
                    upkeepFee = Long.parseLong(args[4]);
                } catch (NumberFormatException e) {
                    player.sendMessage(core.msg("general.invalid-number"));
                    return;
                }

                Location p1 = pos1.get(player.getUniqueId());
                Location p2 = pos2.get(player.getUniqueId());
                if (p1 == null || p2 == null) {
                    player.sendMessage(color("&cSet both pos1 and pos2 first."));
                    return;
                }

                runAsync(player, () -> shops.createArea(
                        areaKey,
                        p1.getWorld().getName(),
                        p1,
                        p2,
                        claimFee,
                        upkeepFee
                ));
            }
            case "createauto" -> {
                if (args.length != 4) {
                    player.sendMessage(color("&7Usage: &f/shop admin createauto <claimFee> <upkeepFee>"));
                    return;
                }
                long claimFee;
                long upkeepFee;
                try {
                    claimFee = Long.parseLong(args[2]);
                    upkeepFee = Long.parseLong(args[3]);
                } catch (NumberFormatException e) {
                    player.sendMessage(core.msg("general.invalid-number"));
                    return;
                }

                Location p1 = pos1.get(player.getUniqueId());
                Location p2 = pos2.get(player.getUniqueId());
                if (p1 == null || p2 == null) {
                    player.sendMessage(color("&cSet both pos1 and pos2 first."));
                    return;
                }

                String key = "area-" + p1.getWorld().getName().toLowerCase(Locale.ROOT)
                        + "-" + (System.currentTimeMillis() % 100000);
                runAsync(player, () -> shops.createArea(
                        key,
                        p1.getWorld().getName(),
                        p1,
                        p2,
                        claimFee,
                        upkeepFee
                ));
            }
            case "remove" -> {
                if (args.length != 3) {
                    player.sendMessage(color("&7Usage: &f/shop admin remove <area>"));
                    return;
                }
                String areaKey = args[2];
                runAsync(player, () -> shops.removeArea(areaKey));
            }
            default -> player.sendMessage(color("&7Usage: &f/shop admin <pos1|pos2|create|createauto|remove> ..."));
        }
    }

    private void runAsync(Player player, ActionSupplier action) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            ShopService.ActionResult result = action.get();
            send(player, (result.success() ? "&a" : "&c") + result.message());
        });
    }

    private void startClaimFlow(Player player, ShopAreaRecord area) {
        if (area.isClaimed()) {
            send(player, "&cThat area is already claimed.");
            return;
        }

        long fee = Math.max(0L, area.claimFee());
        if (fee <= 0L) {
            ShopService.ActionResult claimed = shops.claimAreaAfterPayment(player.getUniqueId().toString(), area.areaKey());
            send(player, (claimed.success() ? "&a" : "&c") + claimed.message());
            return;
        }
        core.getServer().getScheduler().runTask(core, () -> payments.openPaymentMenu(
                player,
                "&8Shop Claim Payment",
                fee,
                "Shop area claim fee",
                (payerUuid, requiredAmount, depositedAmount) -> {
                    TransactionResult charged = shops.collectClaimFee(payerUuid, requiredAmount, area.areaKey());
                    if (!charged.success()) {
                        return NotePaymentMenuService.NotePaymentOutcome.fail("Could not collect claim fee: " + charged.message());
                    }

                    ShopService.ActionResult claimed = shops.claimAreaAfterPayment(payerUuid, area.areaKey());
                    if (claimed.success()) {
                        return NotePaymentMenuService.NotePaymentOutcome.ok(claimed.message());
                    }

                    try {
                        tx.mint(
                                payerUuid,
                                requiredAmount,
                                MoneySource.SYSTEM,
                                "shop:claim:refund",
                                "Refund failed shop claim",
                                "SYSTEM"
                        );
                    } catch (Exception e) {
                        core.getLogger().severe("[shop] Failed claim refund after claim error.");
                        e.printStackTrace();
                    }
                    return NotePaymentMenuService.NotePaymentOutcome.fail(claimed.message());
                }
        ));
    }

    private void send(CommandSender sender, String message) {
        core.getServer().getScheduler().runTask(core, () -> sender.sendMessage(color(message)));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private String usage() {
        return "/shop <menu|list|claim|myareas|payupkeep|setup|setprice|removelisting|listing|listings|admin>";
    }

    private void openShopHub(Player player) {
        if (core.gui() != null) {
            core.gui().openShopsMenu(player);
            return;
        }
        player.performCommand("realm");
    }

    private int findFirstNonEmptySlot(Container container) {
        for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
            var item = container.getInventory().getItem(slot);
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                return slot;
            }
        }
        return -1;
    }

    @FunctionalInterface
    private interface ActionSupplier {
        ShopService.ActionResult get();
    }
}
