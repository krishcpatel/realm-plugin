package com.krishcpatel.realm.clans.listener;

import com.krishcpatel.realm.clans.model.ClanRecord;
import com.krishcpatel.realm.clans.service.ClansService;
import com.krishcpatel.realm.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Clans gameplay listeners: claim flags, proximity notifications, and protected storage access.
 */
public final class ClansListener implements Listener {
    private final Core core;
    private final ClansService clans;

    /**
     * Creates a clans listener.
     *
     * @param core plugin core
     * @param clans clans service
     */
    public ClansListener(Core core, ClansService clans) {
        this.core = core;
        this.clans = clans;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onFlagPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        ItemStack item = event.getItemInHand();
        if (!clans.isBannerBlock(block.getType())) {
            return;
        }

        Optional<ClanRecord> clanOpt = clans.findClanByPlayer(player.getUniqueId().toString());
        if (clanOpt.isEmpty()) {
            return;
        }
        ClanRecord clan = clanOpt.get();
        String expected = clan.flagSignature();
        if (expected == null || expected.isBlank()) {
            return;
        }

        String placedSig = clans.signatureFromItem(item);
        if (placedSig == null || !expected.equals(placedSig)) {
            return;
        }

        ClansService.ActionResult result = clans.handleFlagPlacement(player, block, item);
        if (!result.success()) {
            event.setCancelled(true);
            player.sendMessage(color("&c" + result.message()));
            return;
        }

        player.sendMessage(color("&a" + result.message()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (clans.claimAt(block.getLocation()) != null) {
            ClansService.ActionResult result = clans.handleClaimBreak(player, block);
            if (!result.success()) {
                event.setCancelled(true);
                player.sendMessage(color("&c" + result.message()));
            } else {
                player.sendMessage(color("&a" + result.message()));
            }
            return;
        }

        if (clans.isProtectedStorage(block)) {
            boolean allowed = clans.canBreakStorage(player.getUniqueId().toString(), block);
            if (!allowed) {
                event.setCancelled(true);
                player.sendMessage(color("&cThat clan storage is protected."));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onProtectedStorageOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof Container container)) {
            return;
        }

        BlockState state = container.getBlock().getState();
        Block block = state.getBlock();
        if (!clans.isProtectedStorage(block)) {
            return;
        }

        boolean allowed = clans.canAccessStorage(player.getUniqueId().toString(), block);
        if (!allowed) {
            event.setCancelled(true);
            player.sendMessage(color("&cThat clan storage is protected."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getWorld() == event.getTo().getWorld()) {
            return;
        }
        clans.handleVicinityTick(event.getPlayer(), event.getTo());
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
