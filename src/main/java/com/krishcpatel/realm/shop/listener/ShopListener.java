package com.krishcpatel.realm.shop.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.data.TransactionResult;
import com.krishcpatel.realm.economy.payment.NotePaymentMenuService;
import com.krishcpatel.realm.shop.gui.ShopSetupMenuService;
import com.krishcpatel.realm.shop.model.ShopChestRecord;
import com.krishcpatel.realm.shop.model.ShopListingRecord;
import com.krishcpatel.realm.shop.service.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Handles shop chest interactions and buyer GUI purchasing.
 */
public final class ShopListener implements Listener {
    private static final int INVENTORY_WIDTH = 9;
    private static final Material FRAME_SIDE_PANE = Material.GRAY_STAINED_GLASS_PANE;
    private static final Material FRAME_CORNER_PANE = Material.BLACK_STAINED_GLASS_PANE;

    private final Core core;
    private final ShopService shops;
    private final NotePaymentMenuService payments;
    private final ShopSetupMenuService setupMenu;

    /**
     * Creates a shop listener.
     *
     * @param core plugin core
     * @param shops shop service
     * @param payments note payment menu
     * @param setupMenu setup menu service
     */
    public ShopListener(
            Core core,
            ShopService shops,
            NotePaymentMenuService payments,
            ShopSetupMenuService setupMenu
    ) {
        this.core = core;
        this.shops = shops;
        this.payments = payments;
        this.setupMenu = setupMenu;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onShopInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof Container container)) {
            return;
        }

        Player player = event.getPlayer();

        if (player.isSneaking()) {
            var area = shops.findAreaContaining(clicked.getWorld().getName(), clicked.getX(), clicked.getY(), clicked.getZ());
            if (area.isPresent() && player.getUniqueId().toString().equals(area.get().ownerUuid())) {
                event.setCancelled(true);
                setupMenu.openSetupMenu(player, clicked);
                return;
            }
        }

        List<ShopService.ShopListingView> listings = shops.listingsForChest(clicked);
        if (listings.isEmpty()) {
            return;
        }

        String ownerUuid = listings.get(0).chest().ownerUuid();
        if (ownerUuid != null && ownerUuid.equals(player.getUniqueId().toString())) {
            return;
        }

        event.setCancelled(true);
        openBuyMenu(player, clicked, container, listings);
    }

    @EventHandler
    private void onBuyMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (raw == 49) {
            player.closeInventory();
            return;
        }

        ShopService.ShopListingView view = holder.listings().get(raw);
        if (view == null) {
            return;
        }

        ShopListingRecord listing = view.listing();

        payments.openPaymentMenu(
                player,
                "&8Shop Purchase",
                listing.price(),
                "Shop item purchase",
                (payerUuid, requiredAmount, depositedAmount) -> processPurchase(payerUuid, view)
        );
    }

    @EventHandler
    private void onBuyMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopMenuHolder) {
            event.setCancelled(true);
        }
    }

    private NotePaymentMenuService.NotePaymentOutcome processPurchase(String buyerUuid, ShopService.ShopListingView view) throws Exception {
        Optional<ShopListingRecord> listingOpt = shops.findListingById(view.listing().id());
        if (listingOpt.isEmpty()) {
            return NotePaymentMenuService.NotePaymentOutcome.fail("Listing no longer exists.");
        }
        ShopListingRecord listing = listingOpt.get();

        Optional<ShopChestRecord> chestOpt = shops.findChestById(listing.chestId());
        if (chestOpt.isEmpty()) {
            return NotePaymentMenuService.NotePaymentOutcome.fail("Shop chest no longer exists.");
        }
        ShopChestRecord chest = chestOpt.get();

        ItemStack purchased = runSync(() -> reserveItem(chest, listing));
        if (purchased == null) {
            return NotePaymentMenuService.NotePaymentOutcome.fail("That item is out of stock.");
        }

        TransactionResult payment = shops.transferPurchase(
                buyerUuid,
                chest.ownerUuid(),
                listing.price(),
                listing.id()
        );
        if (!payment.success()) {
            runSync(() -> {
                restoreReservedItem(chest, listing.slotIndex(), purchased);
                return null;
            });
            return NotePaymentMenuService.NotePaymentOutcome.fail("Payment failed: " + payment.message());
        }

        runSync(() -> {
            Player buyer = Bukkit.getPlayer(UUID.fromString(buyerUuid));
            if (buyer == null) {
                restoreReservedItem(chest, listing.slotIndex(), purchased);
                return null;
            }
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(purchased);
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(item -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), item));
            }
            buyer.updateInventory();
            return null;
        });

        return NotePaymentMenuService.NotePaymentOutcome.ok(
                "Purchased " + listing.material() + " x" + listing.unitAmount() + " for $" + listing.price() + "."
        );
    }

    private ItemStack reserveItem(ShopChestRecord chest, ShopListingRecord listing) {
        World world = Bukkit.getWorld(chest.world());
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(chest.x(), chest.y(), chest.z());
        if (!(block.getState() instanceof Container container)) {
            return null;
        }

        int slot = listing.slotIndex();
        if (slot < 0 || slot >= container.getInventory().getSize()) {
            return null;
        }

        ItemStack inSlot = container.getInventory().getItem(slot);
        if (inSlot == null || inSlot.getType() == Material.AIR) {
            return null;
        }

        Material expected = Material.matchMaterial(listing.material());
        if (expected == null || inSlot.getType() != expected) {
            return null;
        }
        if (inSlot.getAmount() < listing.unitAmount()) {
            return null;
        }

        ItemStack out = inSlot.clone();
        out.setAmount(listing.unitAmount());

        int newAmount = inSlot.getAmount() - listing.unitAmount();
        if (newAmount <= 0) {
            container.getInventory().setItem(slot, null);
        } else {
            inSlot.setAmount(newAmount);
            container.getInventory().setItem(slot, inSlot);
        }
        container.update();
        return out;
    }

    private void restoreReservedItem(ShopChestRecord chest, int slot, ItemStack item) {
        World world = Bukkit.getWorld(chest.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(chest.x(), chest.y(), chest.z());
        if (!(block.getState() instanceof Container container)) {
            return;
        }

        ItemStack existing = container.getInventory().getItem(slot);
        if (existing == null || existing.getType() == Material.AIR) {
            container.getInventory().setItem(slot, item.clone());
        } else if (existing.isSimilar(item) && existing.getAmount() + item.getAmount() <= existing.getMaxStackSize()) {
            existing.setAmount(existing.getAmount() + item.getAmount());
            container.getInventory().setItem(slot, existing);
        } else {
            container.getWorld().dropItemNaturally(container.getLocation(), item.clone());
        }
        container.update();
    }

    private void openBuyMenu(
            Player player,
            Block chestBlock,
            Container container,
            List<ShopService.ShopListingView> listings
    ) {
        ShopMenuHolder holder = new ShopMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, color("&8Shop - " + listings.get(0).areaKey()));
        holder.setInventory(inv);
        fillFrame(inv, Material.ORANGE_STAINED_GLASS_PANE);

        inv.setItem(4, item(Material.CHEST, "&6&lShop Listings", List.of(
                "&7Area: &f" + listings.get(0).areaKey(),
                "&7Available listings: &f" + listings.size(),
                "&7Click an item to purchase."
        )));

        int slot = 10;
        for (ShopService.ShopListingView view : listings) {
            if (slot >= inv.getSize()) {
                break;
            }
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= inv.getSize()) {
                break;
            }

            ShopListingRecord listing = view.listing();
            int chestSlot = listing.slotIndex();
            ItemStack chestItem = chestSlot >= 0 && chestSlot < container.getInventory().getSize()
                    ? container.getInventory().getItem(chestSlot)
                    : null;
            int stock = chestItem == null ? 0 : chestItem.getAmount();

            Material material = Material.matchMaterial(listing.material());
            if (material == null) {
                material = Material.BARRIER;
            }

            ItemStack icon = item(material, "&f" + listing.material(), List.of(
                    "&7Quantity: &f" + listing.unitAmount(),
                    "&7Price: &a$" + listing.price(),
                    "&7Stock in slot " + (listing.slotIndex() + 1) + ": &f" + stock,
                    "",
                    stock >= listing.unitAmount()
                            ? "&eClick to purchase"
                            : "&cOut of stock"
            ));
            inv.setItem(slot, icon);
            holder.listings().put(slot, view);
            slot++;
        }

        inv.setItem(49, item(Material.IRON_DOOR, "&cClose", List.of("&7Close shop menu.")));
        inv.setItem(53, item(Material.BOOK, "&eListings", List.of(
                "&7Entries shown: &f" + holder.listings().size()
        )));
        player.openInventory(inv);
    }

    private void fillFrame(Inventory inv, Material border) {
        ItemStack topBottom = item(border, " ", List.of());
        ItemStack side = item(FRAME_SIDE_PANE, " ", List.of());
        ItemStack corner = item(FRAME_CORNER_PANE, " ", List.of());

        int rows = inv.getSize() / INVENTORY_WIDTH;
        for (int i = 0; i < inv.getSize(); i++) {
            int row = i / INVENTORY_WIDTH;
            int col = i % INVENTORY_WIDTH;
            if (row == 0 || row == rows - 1 || col == 0 || col == INVENTORY_WIDTH - 1) {
                boolean topOrBottom = row == 0 || row == rows - 1;
                boolean sideColumn = col == 0 || col == INVENTORY_WIDTH - 1;
                if (topOrBottom && sideColumn) {
                    inv.setItem(i, corner);
                } else if (topOrBottom) {
                    inv.setItem(i, topBottom);
                } else {
                    inv.setItem(i, side);
                }
            }
        }
        if (inv.getSize() >= 27) {
            inv.setItem(4, item(border, " ", List.of()));
            inv.setItem(inv.getSize() - 5, item(border, " ", List.of()));
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private <T> T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        return core.getServer().getScheduler().callSyncMethod(core, task).get();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private static final class ShopMenuHolder implements InventoryHolder {
        private final Map<Integer, ShopService.ShopListingView> listings = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private Map<Integer, ShopService.ShopListingView> listings() {
            return listings;
        }
    }
}
