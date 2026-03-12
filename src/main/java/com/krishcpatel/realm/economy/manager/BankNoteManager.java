package com.krishcpatel.realm.economy.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.database.DatabaseManager;
import com.krishcpatel.realm.economy.repository.BankNoteRepository;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.data.BankNoteRecord;
import com.krishcpatel.realm.economy.data.BankNoteIssueResult;
import com.krishcpatel.realm.economy.data.TransactionResult;
import com.krishcpatel.realm.economy.event.LedgerRecordedEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.List;
import java.util.UUID;

/**
 * Handles issuing and redeeming physical banknotes.
 *
 * <p>This manager coordinates bank balance changes, note persistence,
 * ledger recording, and item creation/validation.</p>
 */
public final class BankNoteManager {

    private final Core core;
    private final DatabaseManager db;
    private final EconomyRepository economy;
    private final LedgerRepository ledger;
    private final BankNoteRepository notes;
    private final NamespacedKey noteIdKey;

    /**
     * Creates a bank note manager.
     *
     * @param core plugin instance used for events and logging
     * @param db database manager
     * @param economy repository used for account balances
     * @param ledger repository used for ledger entries
     * @param notes repository used for note persistence
     */
    public BankNoteManager(
            Core core,
            DatabaseManager db,
            EconomyRepository economy,
            LedgerRepository ledger,
            BankNoteRepository notes
    ) {
        this.core = core;
        this.db = db;
        this.economy = economy;
        this.ledger = ledger;
        this.notes = notes;
        this.noteIdKey = new NamespacedKey(core, "bank_note_id");
    }

    /**
     * Withdraws money from a player's bank balance and creates a bank note record.
     *
     * <p>The actual item must be granted on the server thread by calling
     * {@link #giveIssuedNote(Player, String, long)}.</p>
     *
     * @param playerUuid player account UUID
     * @param amount amount to convert into a note
     * @return issue result describing success/failure and created note id
     * @throws SQLException if database access fails
     */
    public BankNoteIssueResult issueNote(String playerUuid, long amount) throws SQLException {
        if (amount <= 0) {
            return BankNoteIssueResult.fail("Amount must be > 0");
        }

        String noteId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        return db.executeWrite(() -> {
            try (Connection c = db.getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);

                try {
                    economy.ensureAccount(c, playerUuid);

                    boolean ok = economy.subtractBalanceFloorZero(c, playerUuid, amount);
                    if (!ok) {
                        c.rollback();
                        return BankNoteIssueResult.fail("Insufficient funds");
                    }

                    notes.insertNote(c, noteId, amount, now, playerUuid);

                    long ledgerId = ledger.insertLedgerRow(
                            c,
                            now,
                            "WITHDRAW_NOTE",
                            amount,
                            playerUuid,
                            null,
                            MoneySource.SYSTEM.name(),
                            noteId,
                            "Withdraw bank note",
                            playerUuid
                    );

                    c.commit();

                    core.events().publishAsync(new LedgerRecordedEvent(
                            ledgerId,
                            "WITHDRAW_NOTE",
                            amount,
                            playerUuid,
                            null,
                            MoneySource.SYSTEM,
                            noteId,
                            "Withdraw bank note",
                            playerUuid
                    ));

                    return BankNoteIssueResult.ok(ledgerId, noteId, amount);

                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }
        });
    }

    /**
     * Redeems the banknote currently held in the player's main hand.
     *
     * <p>The note is validated, marked as redeemed in the database,
     * and the corresponding amount is added back to the player's bank
     * balance. A ledger entry is also recorded.</p>
     *
     * @param playerUuid player redeeming the banknote
     * @return result describing whether the redemption succeeded
     * @throws Exception if database or sync inventory operations fail
     */
    public TransactionResult redeemHeldNote(UUID playerUuid) throws Exception {
        ConsumedNote consumed = consumeHeldNote(playerUuid);
        if (consumed == null) {
            return TransactionResult.fail("Hold a bank note in your main hand");
        }

        String noteId = consumed.noteId();
        if (noteId.isBlank()) {
            restoreConsumedNote(playerUuid, consumed.item());
            return TransactionResult.fail("Invalid bank note");
        }

        String playerUuidString = playerUuid.toString();
        long now = System.currentTimeMillis();

        return db.executeWrite(() -> {
            try (Connection c = db.getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);

                try {
                    BankNoteRecord note = notes.findById(c, noteId);
                    if (note == null) {
                        c.rollback();
                        restoreConsumedNote(playerUuid, consumed.item());
                        return TransactionResult.fail("Bank note not found");
                    }

                    if (note.redeemed()) {
                        c.rollback();
                        restoreConsumedNote(playerUuid, consumed.item());
                        return TransactionResult.fail("This bank note has already been redeemed");
                    }

                    boolean marked = notes.markRedeemed(c, noteId, now, playerUuidString);
                    if (!marked) {
                        c.rollback();
                        restoreConsumedNote(playerUuid, consumed.item());
                        return TransactionResult.fail("This bank note has already been redeemed");
                    }

                    economy.ensureAccount(c, playerUuidString);
                    economy.addBalance(c, playerUuidString, note.amount());

                    long ledgerId = ledger.insertLedgerRow(
                            c,
                            now,
                            "REDEEM_NOTE",
                            note.amount(),
                            null,
                            playerUuidString,
                            MoneySource.SYSTEM.name(),
                            noteId,
                            "Redeem bank note",
                            playerUuidString
                    );

                    c.commit();

                    core.events().publishAsync(new LedgerRecordedEvent(
                            ledgerId,
                            "REDEEM_NOTE",
                            note.amount(),
                            null,
                            playerUuidString,
                            MoneySource.SYSTEM,
                            noteId,
                            "Redeem bank note",
                            playerUuidString
                    ));

                    return TransactionResult.ok(ledgerId);

                } catch (SQLException ex) {
                    c.rollback();
                    restoreConsumedNote(playerUuid, consumed.item());
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }
        });
    }

    /**
     * Reverses an issued note when delivery to the player cannot be completed.
     *
     * <p>The note row is deleted (if still unredeemed) and the amount is refunded
     * to the player's balance in one transaction.</p>
     *
     * @param playerUuid player to refund
     * @param noteId issued note id
     * @param amount expected note amount
     * @return true when a refund was applied
     * @throws SQLException if database access fails
     */
    public boolean refundIssuedNote(String playerUuid, String noteId, long amount) throws SQLException {
        long now = System.currentTimeMillis();

        return db.executeWrite(() -> {
            try (Connection c = db.getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);

                try {
                    boolean deleted = notes.deleteUnredeemed(c, noteId, playerUuid);
                    if (!deleted) {
                        c.rollback();
                        return false;
                    }

                    economy.ensureAccount(c, playerUuid);
                    economy.addBalance(c, playerUuid, Math.max(0L, amount));

                    long ledgerId = ledger.insertLedgerRow(
                            c,
                            now,
                            "WITHDRAW_NOTE_REFUND",
                            Math.max(0L, amount),
                            null,
                            playerUuid,
                            MoneySource.SYSTEM.name(),
                            noteId,
                            "Refund undelivered bank note",
                            "SYSTEM"
                    );

                    c.commit();

                    core.events().publishAsync(new LedgerRecordedEvent(
                            ledgerId,
                            "WITHDRAW_NOTE_REFUND",
                            Math.max(0L, amount),
                            null,
                            playerUuid,
                            MoneySource.SYSTEM,
                            noteId,
                            "Refund undelivered bank note",
                            "SYSTEM"
                    ));
                    return true;
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }
        });
    }

    /**
     * Grants an already-issued bank note item to a player.
     *
     * <p>Must be called from the server thread.</p>
     *
     * @param player recipient
     * @param noteId unique note id
     * @param amount note value
     */
    public void giveIssuedNote(Player player, String noteId, long amount) {
        ItemStack noteItem = createNoteItem(noteId, amount);
        var leftovers = player.getInventory().addItem(noteItem);
        if (!leftovers.isEmpty()) {
            leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        player.updateInventory();
    }

    /**
     * Checks whether the provided item is a valid banknote item.
     *
     * @param item item to test
     * @return true if the item is a banknote
     */
    public boolean isBankNote(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(noteIdKey, PersistentDataType.STRING);
    }

    /**
     * Returns the unique banknote id stored in the item metadata.
     *
     * @param item banknote item
     * @return note id, or null if the item is not a valid banknote
     */
    public String getNoteId(ItemStack item) {
        if (!isBankNote(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(noteIdKey, PersistentDataType.STRING);
    }

    private ItemStack createNoteItem(String noteId, long amount) {
        String materialName = core.config().getString("economy.bank-notes.item-material", "PAPER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.PAPER;
        }

        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();

        String displayName = core.config().getString("economy.bank-notes.note-name", "&6Bank Note &7- &e$%amount%");
        displayName = displayName.replace("%amount%", String.valueOf(amount));

        List<String> lore = core.config().getStringList("economy.bank-notes.lore").stream()
                .map(line -> line
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%short_id%", noteId.substring(0, 8)))
                .toList();

        meta.setDisplayName(color(displayName));
        meta.setLore(lore.stream().map(this::color).toList());
        meta.getPersistentDataContainer().set(noteIdKey, PersistentDataType.STRING, noteId);

        item.setItemMeta(meta);
        return item;
    }

    private void consumeOneFromMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        }
    }

    private ConsumedNote consumeHeldNote(UUID playerUuid) throws Exception {
        return runSync(() -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) return null;

            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!isBankNote(hand)) return null;

            String noteId = getNoteId(hand);
            if (noteId == null || noteId.isBlank()) return null;

            ItemStack consumed = hand.clone();
            consumed.setAmount(1);
            consumeOneFromMainHand(player);
            player.updateInventory();

            return new ConsumedNote(noteId, consumed);
        });
    }

    private void restoreConsumedNote(UUID playerUuid, ItemStack item) {
        try {
            runSync(() -> {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player == null) {
                    core.getLogger().warning("[economy] Could not restore bank note because player is offline: " + playerUuid);
                    return null;
                }

                var leftovers = player.getInventory().addItem(item.clone());
                if (!leftovers.isEmpty()) {
                    leftovers.values().forEach(leftover ->
                            player.getWorld().dropItemNaturally(player.getLocation(), leftover)
                    );
                }

                player.updateInventory();
                return null;
            });
        } catch (Exception e) {
            core.getLogger().severe("[economy] Failed to restore consumed bank note for " + playerUuid);
            e.printStackTrace();
        }
    }

    private <T> T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        return core.getServer().getScheduler().callSyncMethod(core, task).get();
    }

    private record ConsumedNote(String noteId, ItemStack item) {
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
