package com.krishcpatel.realm.skills.listener;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.nexo.NexoHook;
import com.krishcpatel.realm.skills.manager.SkillManager;
import com.krishcpatel.realm.skills.model.SkillProgress;
import com.krishcpatel.realm.skills.model.SkillUsageGate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * Enforces skill-level requirements for configured Nexo items.
 */
public final class SkillNexoUsageGateListener implements Listener {
    private final Core core;
    private final NexoHook nexo;
    private final SkillManager manager;

    /**
     * Creates the usage gate listener.
     *
     * @param core plugin instance
     * @param nexo Nexo API hook
     * @param manager skill read API
     */
    public SkillNexoUsageGateListener(Core core, NexoHook nexo, SkillManager manager) {
        this.core = core;
        this.nexo = nexo;
        this.manager = manager;
    }

    /**
     * Blocks combat with gated Nexo items when the player lacks the required skill level.
     *
     * @param event damage event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!canUse(player, player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks right/left click usage with gated Nexo items.
     *
     * @param event interact event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!canUse(event.getPlayer(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks block breaking with gated Nexo items.
     *
     * @param event block break event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!canUse(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    private boolean canUse(Player player, ItemStack item) {
        if (player.hasPermission("realm.nexo.bypass-gates")) {
            return true;
        }

        Optional<SkillUsageGate> maybeGate = gateFor(item);
        if (maybeGate.isEmpty()) {
            return true;
        }

        SkillUsageGate gate = maybeGate.get();
        int level = playerSkillLevel(player, gate.skillId());
        if (level >= gate.minLevel()) {
            return true;
        }

        player.sendMessage(core.msg("skills.nexo-gated", Map.of(
                "%item%", gate.itemId(),
                "%skill%", gate.skillId(),
                "%level%", String.valueOf(gate.minLevel())
        )));
        return false;
    }

    private Optional<SkillUsageGate> gateFor(ItemStack item) {
        String itemId = nexo.idFromItem(item);
        if (itemId.isEmpty()) {
            return Optional.empty();
        }

        String basePath = "settings.usage-gates.nexo-items." + itemId;
        ConfigurationSection section = core.skillsConfig().getConfigurationSection(basePath);
        if (section == null || !section.getBoolean("enabled", true)) {
            return Optional.empty();
        }

        String skillId = SkillManager.normalizeSkillId(section.getString("skill", ""));
        int minLevel = Math.max(1, section.getInt("min-level", 1));
        if (skillId.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new SkillUsageGate(itemId, skillId, minLevel));
    }

    private int playerSkillLevel(Player player, String skillId) {
        int startingLevel = Math.max(1, core.skillsConfig().getInt("settings.starting-level", 1));
        try {
            return manager.getSkill(player.getUniqueId(), skillId)
                    .map(SkillProgress::level)
                    .orElse(startingLevel);
        } catch (SQLException e) {
            core.getLogger().severe("[skills] Failed to check skill gate for "
                    + player.getName() + " skill=" + skillId);
            e.printStackTrace();
            return 0;
        }
    }
}
