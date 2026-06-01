package com.krishcpatel.realm.shop.gui;

import com.krishcpatel.realm.core.Core;
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
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Guided setup menu for shop chest listings.
 */
public final class ShopSetupMenuService implements Listener {
    private static final int INVENTORY_WIDTH = 9;
    private static final Material FRAME_SIDE_PANE = Material.GRAY_STAINED_GLASS_PANE;
    private static final Material FRAME_CORNER_PANE = Material.BLACK_STAINED_GLASS_PANE;
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Core core;
    private final ShopService shops;

    /**
     * Creates a shop setup menu service.
     *
     * @param core plugin core
     * @param shops shop service
     */
    public ShopSetupMenuService(Core core, ShopService shops) {
        this.core = core;
        this.shops = shops;
    }

    /**
     * Opens setup menu for the looked-at container.
     *
     * @param player owner player
     * @param targetBlock target container block
     */
    public void openSetupMenu(Player player, Block targetBlock) {
        if (!(targetBlock.getState() instanceof Container container)) {
            player.sendMessage(color("&cLook at a shop chest/container."));
            return;
        }

        ShopLocation location = new ShopLocation(
                targetBlock.getWorld().getName(),
                targetBlock.getX(),
                targetBlock.getY(),
                targetBlock.getZ()
        );

        List<ChestSlotEntry> entries = snapshotContainerEntries(container);
        if (entries.isEmpty()) {
            player.sendMessage(color("&cPut at least one item into the chest first."));
            return;
        }

        String playerUuid = player.getUniqueId().toString();
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            ShopService.ActionResult ownResult = shops.ensureOwnedShopLocation(
                    playerUuid,
                    location.world(),
                    location.x(),
                    location.y(),
                    location.z()
            );
            if (!ownResult.success()) {
                send(player.getUniqueId(), "&c" + ownResult.message());
                return;
            }

            Map<Integer, ShopListingRecord> listingsBySlot = shops.listingsForChestLocation(
                    location.world(),
                    location.x(),
                    location.y(),
                    location.z()
            ).stream().collect(Collectors.toMap(
                    row -> row.listing().slotIndex(),
                    row -> row.listing()
            ));

            core.getServer().getScheduler().runTask(core, () ->
                    openSetupMenuPage(player, location, entries, listingsBySlot, 0)
            );
        });
    }

    @EventHandler
    private void onSetupClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getView().getTopInventory().getHolder() instanceof SetupMenuHolder holder) {
            event.setCancelled(true);
            handleSetupMenuClick(player, event.getRawSlot(), event.isRightClick(), holder);
            return;
        }

        if (event.getView().getTopInventory().getHolder() instanceof PriceMenuHolder holder) {
            event.setCancelled(true);
            handlePriceMenuClick(player, event.getRawSlot(), holder);
        }
    }

    @EventHandler
    private void onSetupDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SetupMenuHolder
                || event.getView().getTopInventory().getHolder() instanceof PriceMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handleSetupMenuClick(Player player, int rawSlot, boolean rightClick, SetupMenuHolder holder) {
        if (rawSlot < 0 || rawSlot >= holder.getInventory().getSize()) {
            return;
        }
        if (rawSlot == 49) {
            player.closeInventory();
            return;
        }
        if (rawSlot == 45) {
            openShopHub(player);
            return;
        }
        if (rawSlot == 48 && holder.page() > 0) {
            openSetupMenuPage(player, holder.location(), holder.entries(), holder.listingsBySlot(), holder.page() - 1);
            return;
        }
        int maxPage = maxPage(holder.entries().size(), CONTENT_SLOTS.length);
        if (rawSlot == 50 && holder.page() < maxPage) {
            openSetupMenuPage(player, holder.location(), holder.entries(), holder.listingsBySlot(), holder.page() + 1);
            return;
        }

        ChestSlotEntry entry = holder.entryByMenuSlot().get(rawSlot);
        if (entry == null) {
            return;
        }

        if (rightClick) {
            runAsyncListingRemove(player, holder.location(), entry.slot(), holder.page());
            return;
        }

        openPriceMenu(player, holder.location(), entry, holder.page(), holder.listingsBySlot().get(entry.slot()));
    }

    private void handlePriceMenuClick(Player player, int rawSlot, PriceMenuHolder holder) {
        if (rawSlot < 0 || rawSlot >= holder.getInventory().getSize()) {
            return;
        }
        if (rawSlot == 49) {
            player.closeInventory();
            return;
        }
        if (rawSlot == 45) {
            reopenSetupFromPriceMenu(player, holder);
            return;
        }

        Long selectedPrice = holder.pricesByMenuSlot().get(rawSlot);
        if (selectedPrice == null) {
            return;
        }

        runAsyncListingSet(player, holder.location(), holder.entry(), selectedPrice, holder.returnPage());
    }

    private void runAsyncListingSet(Player player, ShopLocation location, ChestSlotEntry entry, long price, int returnPage) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            ShopService.ActionResult result = shops.setListing(
                    player.getUniqueId().toString(),
                    location.world(),
                    location.x(),
                    location.y(),
                    location.z(),
                    entry.slot(),
                    entry.material(),
                    entry.unitAmount(),
                    price
            );

            send(player.getUniqueId(), (result.success() ? "&a" : "&c") + result.message());
            core.getServer().getScheduler().runTask(core, () -> {
                if (!player.isOnline()) {
                    return;
                }
                refreshSetupMenu(player, location, returnPage);
            });
        });
    }

    private void runAsyncListingRemove(Player player, ShopLocation location, int slot, int returnPage) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            ShopService.ActionResult result = shops.removeListing(
                    player.getUniqueId().toString(),
                    location.world(),
                    location.x(),
                    location.y(),
                    location.z(),
                    slot
            );

            send(player.getUniqueId(), (result.success() ? "&a" : "&c") + result.message());
            core.getServer().getScheduler().runTask(core, () -> {
                if (!player.isOnline()) {
                    return;
                }
                refreshSetupMenu(player, location, returnPage);
            });
        });
    }

    private void refreshSetupMenu(Player player, ShopLocation location, int page) {
        Block block = blockAt(location);
        if (block == null || !(block.getState() instanceof Container container)) {
            player.sendMessage(color("&cContainer was not found."));
            openShopHub(player);
            return;
        }

        List<ChestSlotEntry> entries = snapshotContainerEntries(container);
        if (entries.isEmpty()) {
            player.sendMessage(color("&cThis chest has no items to list."));
            openShopHub(player);
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            Map<Integer, ShopListingRecord> listingsBySlot = shops.listingsForChestLocation(
                    location.world(),
                    location.x(),
                    location.y(),
                    location.z()
            ).stream().collect(Collectors.toMap(
                    row -> row.listing().slotIndex(),
                    row -> row.listing()
            ));

            core.getServer().getScheduler().runTask(core, () ->
                    openSetupMenuPage(player, location, entries, listingsBySlot, page)
            );
        });
    }

    private void openSetupMenuPage(
            Player player,
            ShopLocation location,
            List<ChestSlotEntry> entries,
            Map<Integer, ShopListingRecord> listingsBySlot,
            int page
    ) {
        int safePage = Math.max(0, Math.min(page, maxPage(entries.size(), CONTENT_SLOTS.length)));
        int totalPages = Math.max(1, (entries.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        SetupMenuHolder holder = new SetupMenuHolder(location, entries, listingsBySlot, safePage);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&8Shop Setup"));
        holder.setInventory(inv);
        fillFrame(inv, Material.BLUE_STAINED_GLASS_PANE);

        inv.setItem(4, item(Material.WRITABLE_BOOK, "&6&lShop Listing Setup", List.of(
                "&7Left click: set/update price",
                "&7Right click: remove listing",
                "&8Area: " + location.world() + " (" + location.x() + ", " + location.y() + ", " + location.z() + ")"
        )));

        int start = safePage * CONTENT_SLOTS.length;
        int end = Math.min(entries.size(), start + CONTENT_SLOTS.length);
        for (int i = start; i < end; i++) {
            ChestSlotEntry entry = entries.get(i);
            int slot = CONTENT_SLOTS[i - start];
            ShopListingRecord listing = listingsBySlot.get(entry.slot());

            ItemStack icon = entry.preview().clone();
            icon.setAmount(1);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("&7Chest Slot: &f" + (entry.slot() + 1));
                lore.add("&7Sell Amount: &f" + entry.unitAmount());
                if (listing == null) {
                    lore.add("&7Price: &cNot listed");
                } else {
                    lore.add("&7Price: &a$" + listing.price());
                }
                lore.add("");
                lore.add("&eLeft click to set price");
                lore.add("&cRight click to remove listing");
                meta.setLore(lore.stream().map(this::color).toList());
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                icon.setItemMeta(meta);
            }

            inv.setItem(slot, icon);
            holder.entryByMenuSlot().put(slot, entry);
        }

        inv.setItem(45, item(Material.ARROW, "&eBack", List.of("&7Return to shops menu.")));
        inv.setItem(49, item(Material.IRON_DOOR, "&cClose", List.of("&7Close setup menu.")));
        inv.setItem(53, item(Material.BOOK, "&ePage " + (safePage + 1) + "&7/&e" + totalPages, List.of(
                "&7Slots shown: &f" + (end - start),
                "&7Items in chest: &f" + entries.size()
        )));
        if (safePage > 0) {
            inv.setItem(48, item(Material.ARROW, "&ePrevious Page", List.of("&7Page " + safePage)));
        } else {
            inv.setItem(48, item(Material.GRAY_DYE, "&8Previous Page", List.of("&7Already on first page.")));
        }
        if (end < entries.size()) {
            inv.setItem(50, item(Material.ARROW, "&eNext Page", List.of("&7Page " + (safePage + 2))));
        } else {
            inv.setItem(50, item(Material.GRAY_DYE, "&8Next Page", List.of("&7Already on last page.")));
        }

        player.openInventory(inv);
    }

    private void openPriceMenu(
            Player player,
            ShopLocation location,
            ChestSlotEntry entry,
            int returnPage,
            ShopListingRecord existing
    ) {
        PriceMenuHolder holder = new PriceMenuHolder(location, entry, returnPage);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&8Set Price"));
        holder.setInventory(inv);
        fillFrame(inv, Material.GREEN_STAINED_GLASS_PANE);

        List<Long> presets = pricePresets();
        int cursor = 10;
        for (long price : presets) {
            if (cursor >= inv.getSize()) {
                break;
            }
            if (cursor % 9 == 8) {
                cursor += 2;
            }
            if (cursor >= inv.getSize()) {
                break;
            }

            inv.setItem(cursor, item(Material.EMERALD, "&a$" + price, List.of(
                    "&7Set slot " + (entry.slot() + 1) + " to this price."
            )));
            holder.pricesByMenuSlot().put(cursor, price);
            cursor++;
        }

        String current = existing == null ? "not listed" : "$" + existing.price();
        inv.setItem(4, item(entry.preview().getType(), "&f" + entry.material(), List.of(
                "&7Slot: &f" + (entry.slot() + 1),
                "&7Unit Amount: &f" + entry.unitAmount(),
                "&7Current: &f" + current
        )));
        inv.setItem(45, item(Material.ARROW, "&eBack", List.of("&7Return to chest setup.")));
        inv.setItem(49, item(Material.IRON_DOOR, "&cClose", List.of("&7Close setup menu.")));
        inv.setItem(53, item(Material.BOOK, "&aPreset Prices", List.of(
                "&7Options shown: &f" + presets.size(),
                "&7Click a value to set this slot."
        )));

        player.openInventory(inv);
    }

    private List<ChestSlotEntry> snapshotContainerEntries(Container container) {
        List<ChestSlotEntry> out = new ArrayList<>();
        for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
            ItemStack item = container.getInventory().getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            ItemStack preview = item.clone();
            preview.setAmount(1);
            out.add(new ChestSlotEntry(slot, item.getType().name(), Math.max(1, item.getAmount()), preview));
        }
        return out;
    }

    private List<Long> pricePresets() {
        List<Long> raw = core.config().getLongList("shops.setup.price-presets");
        if (raw == null || raw.isEmpty()) {
            raw = List.of(10L, 25L, 50L, 100L, 250L, 500L, 1000L, 2500L, 5000L, 10000L);
        }
        return raw.stream()
                .map(value -> Math.max(1L, value))
                .distinct()
                .sorted()
                .toList();
    }

    private int maxPage(int size, int pageSize) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, (size - 1) / pageSize);
    }

    private Block blockAt(ShopLocation location) {
        World world = Bukkit.getWorld(location.world());
        if (world == null) {
            return null;
        }
        return world.getBlockAt(location.x(), location.y(), location.z());
    }

    private void reopenSetupFromPriceMenu(Player player, PriceMenuHolder holder) {
        Block block = blockAt(holder.location());
        if (block == null) {
            player.sendMessage(color("&cContainer world is not loaded."));
            openShopHub(player);
            return;
        }
        refreshSetupMenu(player, holder.location(), holder.returnPage());
    }

    private void openShopHub(Player player) {
        if (core.gui() != null) {
            core.gui().openShopsMenu(player);
            return;
        }
        player.performCommand("shop menu");
    }

    private void fillFrame(Inventory inventory, Material material) {
        ItemStack topBottom = item(material, " ", List.of());
        ItemStack side = item(FRAME_SIDE_PANE, " ", List.of());
        ItemStack corner = item(FRAME_CORNER_PANE, " ", List.of());
        int rows = inventory.getSize() / INVENTORY_WIDTH;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / INVENTORY_WIDTH;
            int col = slot % INVENTORY_WIDTH;
            if (row == 0 || row == rows - 1 || col == 0 || col == INVENTORY_WIDTH - 1) {
                boolean topOrBottom = row == 0 || row == rows - 1;
                boolean sideColumn = col == 0 || col == INVENTORY_WIDTH - 1;
                if (topOrBottom && sideColumn) {
                    inventory.setItem(slot, corner);
                } else if (topOrBottom) {
                    inventory.setItem(slot, topBottom);
                } else {
                    inventory.setItem(slot, side);
                }
            }
        }
        if (inventory.getSize() >= 27) {
            inventory.setItem(4, item(material, " ", List.of()));
            inventory.setItem(inventory.getSize() - 5, item(material, " ", List.of()));
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        Material safe = material == null ? Material.BARRIER : material;
        ItemStack stack = new ItemStack(safe, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private void send(UUID playerId, String message) {
        core.getServer().getScheduler().runTask(core, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(color(message));
            }
        });
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private record ShopLocation(String world, int x, int y, int z) {
    }

    private record ChestSlotEntry(int slot, String material, int unitAmount, ItemStack preview) {
    }

    private static final class SetupMenuHolder implements InventoryHolder {
        private final ShopLocation location;
        private final List<ChestSlotEntry> entries;
        private final Map<Integer, ShopListingRecord> listingsBySlot;
        private final int page;
        private final Map<Integer, ChestSlotEntry> entryByMenuSlot = new HashMap<>();
        private Inventory inventory;

        private SetupMenuHolder(
                ShopLocation location,
                List<ChestSlotEntry> entries,
                Map<Integer, ShopListingRecord> listingsBySlot,
                int page
        ) {
            this.location = location;
            this.entries = entries;
            this.listingsBySlot = listingsBySlot;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private ShopLocation location() {
            return location;
        }

        private List<ChestSlotEntry> entries() {
            return entries;
        }

        private Map<Integer, ShopListingRecord> listingsBySlot() {
            return listingsBySlot;
        }

        private int page() {
            return page;
        }

        private Map<Integer, ChestSlotEntry> entryByMenuSlot() {
            return entryByMenuSlot;
        }
    }

    private static final class PriceMenuHolder implements InventoryHolder {
        private final ShopLocation location;
        private final ChestSlotEntry entry;
        private final int returnPage;
        private final Map<Integer, Long> pricesByMenuSlot = new HashMap<>();
        private Inventory inventory;

        private PriceMenuHolder(ShopLocation location, ChestSlotEntry entry, int returnPage) {
            this.location = location;
            this.entry = entry;
            this.returnPage = returnPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private ShopLocation location() {
            return location;
        }

        private ChestSlotEntry entry() {
            return entry;
        }

        private int returnPage() {
            return returnPage;
        }

        private Map<Integer, Long> pricesByMenuSlot() {
            return pricesByMenuSlot;
        }
    }
}
