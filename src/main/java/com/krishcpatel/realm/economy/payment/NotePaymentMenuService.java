package com.krishcpatel.realm.economy.payment;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.data.BankNoteIssueResult;
import com.krishcpatel.realm.economy.data.BankNoteRedeemResult;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic bank-note payment menu used by feature modules that require
 * physical note deposits for fees and purchases.
 */
public final class NotePaymentMenuService implements Listener {
    private static final int MENU_SIZE = 45;
    private static final int CONFIRM_SLOT = 31;
    private static final int CANCEL_SLOT = 39;
    private static final int INFO_SLOT = 3;
    private static final int REQUIRED_SLOT = 5;
    private static final int[] DEPOSIT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };
    private static final Set<Integer> DEPOSIT_SLOT_SET = Set.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    );

    private final Core core;
    private final BankNoteManager notes;
    private final Map<UUID, ActivePayment> activePayments = new ConcurrentHashMap<>();

    /**
     * Creates a note payment service.
     *
     * @param core plugin core
     * @param notes bank note manager
     */
    public NotePaymentMenuService(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    /**
     * Opens a note deposit menu for the player.
     *
     * @param player payer
     * @param title inventory title
     * @param requiredAmount exact required amount
     * @param purpose human-readable purpose text
     * @param callback callback executed after notes are redeemed
     */
    public void openPaymentMenu(
            Player player,
            String title,
            long requiredAmount,
            String purpose,
            NotePaymentCallback callback
    ) {
        long safeRequired = Math.max(1L, requiredAmount);
        ActivePayment payment = new ActivePayment(safeRequired, purpose, callback);
        activePayments.put(player.getUniqueId(), payment);

        PaymentHolder holder = new PaymentHolder();
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, color(title));
        holder.setInventory(inventory);

        fillFrame(inventory);
        inventory.setItem(INFO_SLOT, item(Material.PAPER, "&6&lBank Note Deposit", List.of(
                "&7Purpose: &f" + purpose,
                "&7Insert bank notes into the center slots."
        )));
        inventory.setItem(REQUIRED_SLOT, item(Material.GOLD_INGOT, "&eRequired: &f$" + safeRequired, List.of(
                "&7You can overpay.",
                "&7Change is returned as a new bank note."
        )));
        inventory.setItem(CONFIRM_SLOT, item(Material.LIME_CONCRETE, "&aConfirm Payment", List.of(
                "&7Redeem deposited notes and process payment."
        )));
        inventory.setItem(CANCEL_SLOT, item(Material.BARRIER, "&cCancel", List.of(
                "&7Close this menu and get your notes back."
        )));

        player.openInventory(inventory);
    }

    @EventHandler
    private void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof PaymentHolder)) {
            return;
        }

        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (raw >= topSize) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        if (raw == CONFIRM_SLOT) {
            event.setCancelled(true);
            handleConfirm(player, event.getView().getTopInventory());
            return;
        }
        if (raw == CANCEL_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        if (!DEPOSIT_SLOT_SET.contains(raw)) {
            event.setCancelled(true);
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor != null && cursor.getType() != Material.AIR && !notes.isBankNote(cursor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PaymentHolder)) {
            return;
        }

        boolean touchesTop = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                touchesTop = true;
                if (!DEPOSIT_SLOT_SET.contains(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (!touchesTop) {
            return;
        }

        ItemStack cursor = event.getOldCursor();
        if (cursor != null && cursor.getType() != Material.AIR && !notes.isBankNote(cursor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof PaymentHolder)) {
            return;
        }

        ActivePayment payment = activePayments.get(player.getUniqueId());
        if (payment == null || payment.processing()) {
            return;
        }

        activePayments.remove(player.getUniqueId());
        returnDeposits(player, event.getInventory());
    }

    private void handleConfirm(Player player, Inventory inventory) {
        UUID playerId = player.getUniqueId();
        ActivePayment payment = activePayments.get(playerId);
        if (payment == null || payment.processing()) {
            return;
        }

        List<ItemStack> depositedItems = extractDeposits(inventory);
        if (depositedItems.isEmpty()) {
            player.sendMessage(color("&cDeposit at least one bank note."));
            return;
        }

        payment.setProcessing(true);
        player.closeInventory();

        String playerUuid = playerId.toString();
        String actor = playerUuid;
        String reason = "Module payment: " + payment.purpose();

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            long depositedTotal = 0L;
            boolean redeemed = false;
            try {
                List<String> noteIds = new ArrayList<>();

                for (ItemStack stack : depositedItems) {
                    if (stack == null || stack.getType() == Material.AIR) {
                        continue;
                    }
                    if (!notes.isBankNote(stack)) {
                        restoreItems(playerId, depositedItems);
                        sendSync(playerId, "&cOnly bank notes can be deposited.");
                        clearPayment(playerId);
                        return;
                    }
                    if (stack.getAmount() != 1) {
                        restoreItems(playerId, depositedItems);
                        sendSync(playerId, "&cStacked bank notes are not supported.");
                        clearPayment(playerId);
                        return;
                    }

                    String noteId = notes.getNoteId(stack);
                    long amount = notes.getUnredeemedNoteAmount(noteId);
                    if (amount <= 0L) {
                        restoreItems(playerId, depositedItems);
                        sendSync(playerId, "&cOne of the deposited notes is invalid or already redeemed.");
                        clearPayment(playerId);
                        return;
                    }

                    noteIds.add(noteId);
                    depositedTotal += amount;
                }

                if (depositedTotal < payment.requiredAmount()) {
                    restoreItems(playerId, depositedItems);
                    sendSync(playerId, "&cInsufficient deposited value. Required: $" + payment.requiredAmount());
                    clearPayment(playerId);
                    return;
                }

                BankNoteRedeemResult redeemResult = notes.redeemNotesToBalance(playerUuid, noteIds, reason, actor);
                if (!redeemResult.success()) {
                    restoreItems(playerId, depositedItems);
                    sendSync(playerId, "&cPayment failed: " + redeemResult.message());
                    clearPayment(playerId);
                    return;
                }
                redeemed = true;

                NotePaymentOutcome outcome = payment.callback().execute(playerUuid, payment.requiredAmount(), depositedTotal);
                if (!outcome.success()) {
                    boolean refundedAsNote = issueBankNoteToPlayer(playerId, playerUuid, depositedTotal);
                    if (refundedAsNote) {
                        sendSync(playerId, "&cPayment failed: " + outcome.message() + " &7Your deposit was returned as a bank note.");
                    } else {
                        sendSync(playerId, "&cPayment failed: " + outcome.message()
                                + " &eCould not convert the refund to a bank note.");
                    }
                    clearPayment(playerId);
                    return;
                }

                long change = Math.max(0L, depositedTotal - payment.requiredAmount());
                if (change > 0L) {
                    if (!issueBankNoteToPlayer(playerId, playerUuid, change)) {
                        sendSync(playerId, "&eChange of $" + change + " stayed in your bank balance.");
                    }
                }

                sendSync(playerId, "&a" + outcome.message());
                clearPayment(playerId);
            } catch (Exception ex) {
                if (!redeemed) {
                    restoreItems(playerId, depositedItems);
                } else if (depositedTotal > 0L) {
                    issueBankNoteToPlayer(playerId, playerUuid, depositedTotal);
                }
                core.getLogger().severe("[economy] Failed processing note payment menu for " + playerUuid);
                ex.printStackTrace();
                sendSync(playerId, "&cPayment failed. Check console.");
                clearPayment(playerId);
            }
        });
    }

    private void returnDeposits(Player player, Inventory inventory) {
        for (int slot : DEPOSIT_SLOTS) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }

            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack.clone());
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
            inventory.setItem(slot, null);
        }
        player.updateInventory();
    }

    private List<ItemStack> extractDeposits(Inventory inventory) {
        List<ItemStack> out = new ArrayList<>();
        for (int slot : DEPOSIT_SLOTS) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            out.add(stack.clone());
            inventory.setItem(slot, null);
        }
        return out;
    }

    private void restoreItems(UUID playerId, List<ItemStack> stacks) {
        core.getServer().getScheduler().runTask(core, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }

            for (ItemStack stack : stacks) {
                if (stack == null || stack.getType() == Material.AIR) {
                    continue;
                }
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack.clone());
                if (!leftovers.isEmpty()) {
                    leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                }
            }
            player.updateInventory();
        });
    }

    private void clearPayment(UUID playerId) {
        core.getServer().getScheduler().runTask(core, () -> activePayments.remove(playerId));
    }

    private void sendSync(UUID playerId, String message) {
        core.getServer().getScheduler().runTask(core, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                online.sendMessage(color(message));
            }
        });
    }

    private boolean issueBankNoteToPlayer(UUID playerId, String playerUuid, long amount) {
        long safeAmount = Math.max(0L, amount);
        if (safeAmount <= 0L) {
            return true;
        }

        try {
            BankNoteIssueResult issue = notes.issueNote(playerUuid, safeAmount);
            if (!issue.success()) {
                core.getLogger().warning("[economy] Could not issue refund note for " + playerUuid + ": " + issue.message());
                return false;
            }

            core.getServer().getScheduler().runTask(core, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online != null) {
                    notes.giveIssuedNote(online, issue.noteId(), issue.amount());
                    return;
                }

                core.getLogger().warning("[economy] Could not deliver refund note to offline player " + playerUuid);
                core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                    try {
                        notes.refundIssuedNote(playerUuid, issue.noteId(), issue.amount());
                    } catch (Exception refundError) {
                        core.getLogger().severe("[economy] Failed to refund undelivered note " + issue.noteId());
                        refundError.printStackTrace();
                    }
                });
            });
            return true;
        } catch (Exception e) {
            core.getLogger().severe("[economy] Failed to issue refund note for " + playerUuid);
            e.printStackTrace();
            return false;
        }
    }

    private void fillFrame(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        int rows = inventory.getSize() / 9;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int col = slot % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                inventory.setItem(slot, filler);
            }
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

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', Objects.requireNonNullElse(value, ""));
    }

    @FunctionalInterface
    public interface NotePaymentCallback {
        /**
         * Executes module-specific logic after note value has been redeemed
         * to the payer's bank balance.
         *
         * @param payerUuid payer UUID string
         * @param requiredAmount required payment amount
         * @param depositedAmount total redeemed value from deposited notes
         * @return action outcome
         * @throws Exception if execution fails
         */
        NotePaymentOutcome execute(String payerUuid, long requiredAmount, long depositedAmount) throws Exception;
    }

    public record NotePaymentOutcome(boolean success, String message) {
        public static NotePaymentOutcome ok(String message) {
            return new NotePaymentOutcome(true, message);
        }

        public static NotePaymentOutcome fail(String message) {
            return new NotePaymentOutcome(false, message);
        }
    }

    private static final class ActivePayment {
        private final long requiredAmount;
        private final String purpose;
        private final NotePaymentCallback callback;
        private volatile boolean processing;

        private ActivePayment(long requiredAmount, String purpose, NotePaymentCallback callback) {
            this.requiredAmount = requiredAmount;
            this.purpose = purpose;
            this.callback = callback;
            this.processing = false;
        }

        private long requiredAmount() {
            return requiredAmount;
        }

        private String purpose() {
            return purpose;
        }

        private NotePaymentCallback callback() {
            return callback;
        }

        private boolean processing() {
            return processing;
        }

        private void setProcessing(boolean processing) {
            this.processing = processing;
        }
    }

    private static final class PaymentHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
