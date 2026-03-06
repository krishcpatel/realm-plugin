package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.DatabaseManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Handles issuing and redeeming physical bank notes.
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
     * Withdraws money from a player's bank balance and creates a physical bank note.
     *
     * @param player player receiving the note item
     * @param amount amount to convert into a note
     * @return transaction result describing success or failure
     * @throws SQLException if database access fails
     */
    public TransactionResult issueNote(Player player, long amount) throws SQLException {
        if (amount <= 0) {
            return TransactionResult.fail("Amount must be > 0");
        }

        String playerUuid = player.getUniqueId().toString();
        String noteId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Connection c = db.getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            economy.ensureAccount(playerUuid);

            boolean ok = economy.subtractBalanceFloorZero(c, playerUuid, amount);
            if (!ok) {
                c.rollback();
                return TransactionResult.fail("Insufficient funds");
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

            ItemStack noteItem = createNoteItem(noteId, amount);
            var leftovers = player.getInventory().addItem(noteItem);
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            }

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

            return TransactionResult.ok(ledgerId);

        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    /**
     * Redeems the bank note currently held in the player's main hand.
     *
     * <p>The note is validated, marked as redeemed in the database,
     * and the corresponding amount is added back to the player's bank
     * balance. A ledger entry is also recorded.</p>
     *
     * @param player player redeeming the bank note
     * @return result describing whether the redemption succeeded
     * @throws SQLException if a database operation fails
     */
    public TransactionResult redeemHeldNote(Player player) throws SQLException {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isBankNote(item)) {
            return TransactionResult.fail("Hold a bank note in your main hand");
        }

        String noteId = getNoteId(item);
        if (noteId == null || noteId.isBlank()) {
            return TransactionResult.fail("Invalid bank note");
        }

        String playerUuid = player.getUniqueId().toString();
        long now = System.currentTimeMillis();

        Connection c = db.getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            BankNoteRecord note = notes.findById(c, noteId);
            if (note == null) {
                c.rollback();
                return TransactionResult.fail("Bank note not found");
            }

            if (note.redeemed()) {
                c.rollback();
                return TransactionResult.fail("This bank note has already been redeemed");
            }

            boolean marked = notes.markRedeemed(c, noteId, now, playerUuid);
            if (!marked) {
                c.rollback();
                return TransactionResult.fail("This bank note has already been redeemed");
            }

            economy.ensureAccount(playerUuid);
            economy.addBalance(c, playerUuid, note.amount());

            long ledgerId = ledger.insertLedgerRow(
                    c,
                    now,
                    "REDEEM_NOTE",
                    note.amount(),
                    null,
                    playerUuid,
                    MoneySource.SYSTEM.name(),
                    noteId,
                    "Redeem bank note",
                    playerUuid
            );

            c.commit();

            consumeOneFromMainHand(player);

            core.events().publishAsync(new LedgerRecordedEvent(
                    ledgerId,
                    "REDEEM_NOTE",
                    note.amount(),
                    null,
                    playerUuid,
                    MoneySource.SYSTEM,
                    noteId,
                    "Redeem bank note",
                    playerUuid
            ));

            return TransactionResult.ok(ledgerId);

        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    /**
     * Checks whether the provided item is a valid bank note item.
     *
     * @param item item to test
     * @return true if the item is a bank note
     */
    public boolean isBankNote(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(noteIdKey, PersistentDataType.STRING);
    }

    /**
     * Returns the unique bank note id stored in the item metadata.
     *
     * @param item bank note item
     * @return note id, or null if the item is not a valid bank note
     */
    public String getNoteId(ItemStack item) {
        if (!isBankNote(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(noteIdKey, PersistentDataType.STRING);
    }

    private ItemStack createNoteItem(String noteId, long amount) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(color("&6Bank Note &7- &e$" + amount));
        meta.setLore(List.of(
                color("&7Value: &e$" + amount),
                color("&7Redeem with &f/redeem"),
                color("&8ID: " + noteId.substring(0, 8))
        ));

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

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}