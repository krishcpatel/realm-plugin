package com.krishcpatel.realm.economy.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
import com.krishcpatel.realm.economy.data.TransactionResult;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.EquipmentSlot;

/**
 * Listener that allows players to redeem banknotes by shift-right-clicking
 * while holding them.
 */
public final class BankNoteInteractListener implements Listener {

    private final Core core;
    private final BankNoteManager notes;
    private final Set<UUID> redeeming = ConcurrentHashMap.newKeySet();

    /**
     * Creates a listener that allows players to redeem banknotes
     * by shift-right-clicking while holding them.
     *
     * @param core plugin instance used for scheduling and logging
     * @param notes banknote manager used to validate and redeem notes
     */
    public BankNoteInteractListener(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    /**
     * Handles player interaction for redeeming banknotes by shift-right-clicking.
     *
     * @param event player interaction event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!core.config().getBoolean("economy.redeem.enabled", true)
                || !core.config().getBoolean("economy.redeem.right-click-enabled", true)) {
            return;
        }

        Player player = event.getPlayer();

        boolean requireSneak = core.config().getBoolean("economy.redeem.require-sneak", true);
        if (requireSneak && !player.isSneaking()) {
            return;
        }

        var item = player.getInventory().getItemInMainHand();
        if (!notes.isBankNote(item)) {
            return;
        }

        // Prevent normal paper interaction / duplicate handling
        event.setCancelled(true);

        UUID uuid = player.getUniqueId();
        if (!redeeming.add(uuid)) {
            return;
        }

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                TransactionResult result = notes.redeemHeldNote(player);

                core.getServer().getScheduler().runTask(core, () -> {
                    try {
                        if (!result.success()) {
                            player.sendMessage(core.msg("redeem.failed", Map.of(
                                    "%reason%", result.message()
                            )));
                            return;
                        }

                        player.sendMessage(core.msg("redeem.success"));
                        player.updateInventory();
                    } finally {
                        redeeming.remove(uuid);
                    }
                });

            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to redeem bank note by right-click for " + player.getName());
                e.printStackTrace();

                core.getServer().getScheduler().runTask(core, () -> {
                    try {
                        player.sendMessage(core.msg("general.command-failed"));
                    } finally {
                        redeeming.remove(uuid);
                    }
                });
            }
        });
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}