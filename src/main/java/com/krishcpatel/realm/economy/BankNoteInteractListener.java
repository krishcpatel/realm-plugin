package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.EquipmentSlot;

/**
 * Listener that allows players to redeem bank notes by shift-right-clicking
 * while holding them.
 */
public final class BankNoteInteractListener implements Listener {

    private final Core core;
    private final BankNoteManager notes;
    private final Set<UUID> redeeming = ConcurrentHashMap.newKeySet();

    /**
     * Creates a listener that allows players to redeem bank notes
     * by shift-right-clicking while holding them.
     *
     * @param core plugin instance used for scheduling and logging
     * @param notes bank note manager used to validate and redeem notes
     */
    public BankNoteInteractListener(Core core, BankNoteManager notes) {
        this.core = core;
        this.notes = notes;
    }

    /**
     * Handles player interaction for redeeming bank notes by shift-right-clicking.
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

        Player player = event.getPlayer();

        // Safer than plain right-click so players don't redeem by accident
        if (!player.isSneaking()) {
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
                            player.sendMessage(color("&cRedeem failed: &f" + result.message()));
                            return;
                        }

                        player.sendMessage(color("&aBank note redeemed successfully."));
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
                        player.sendMessage(color("&cRedeem failed. Check console."));
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