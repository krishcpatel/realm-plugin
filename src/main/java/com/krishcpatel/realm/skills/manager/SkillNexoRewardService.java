package com.krishcpatel.realm.skills.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.nexo.NexoHook;
import com.krishcpatel.realm.skills.event.SkillLevelUpEvent;
import com.krishcpatel.realm.skills.model.SkillItemReward;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grants configured Nexo item rewards when skills cross configured levels.
 */
public final class SkillNexoRewardService {
    private final Core core;
    private final NexoHook nexo;

    /**
     * Creates the reward service.
     *
     * @param core plugin instance
     * @param nexo Nexo API hook
     */
    public SkillNexoRewardService(Core core, NexoHook nexo) {
        this.core = core;
        this.nexo = nexo;
    }

    /**
     * Subscribes this service to Realm skill level-up events.
     */
    public void register() {
        core.events().subscribe(SkillLevelUpEvent.class, this::onSkillLevelUp);
    }

    private void onSkillLevelUp(SkillLevelUpEvent event) {
        Bukkit.getScheduler().runTask(core, () -> grantRewards(event));
    }

    private void grantRewards(SkillLevelUpEvent event) {
        List<SkillItemReward> rewards = rewardsCrossedBy(event.skillId(), event.oldLevel(), event.newLevel());
        if (rewards.isEmpty()) {
            return;
        }

        Player player = Bukkit.getPlayer(event.playerUuid());
        if (player == null) {
            core.getLogger().info("[skills] Skipped Nexo reward for offline player "
                    + event.playerName() + " skill=" + event.skillId() + " level=" + event.newLevel());
            return;
        }

        for (SkillItemReward reward : rewards) {
            if (nexo.giveItem(player, reward.itemId(), reward.amount())) {
                player.sendMessage(core.msg("skills.nexo-reward", Map.of(
                        "%item%", reward.itemId(),
                        "%amount%", String.valueOf(reward.amount())
                )));
            } else {
                core.getLogger().warning("[skills] Failed to give Nexo reward "
                        + reward.itemId() + " to " + player.getName());
            }
        }
    }

    private List<SkillItemReward> rewardsCrossedBy(String skillId, int oldLevel, int newLevel) {
        String basePath = "settings.rewards.skill-level-up." + SkillManager.normalizeSkillId(skillId);
        ConfigurationSection levels = core.skillsConfig().getConfigurationSection(basePath);
        if (levels == null) {
            return List.of();
        }

        List<SkillItemReward> rewards = new ArrayList<>();
        for (String rawLevel : levels.getKeys(false)) {
            int level = parseLevel(rawLevel);
            if (level <= oldLevel || level > newLevel) {
                continue;
            }

            for (Map<?, ?> itemConfig : levels.getMapList(rawLevel + ".nexo-items")) {
                Object rawItemId = itemConfig.get("id");
                String itemId = NexoHook.normalizeItemId(rawItemId == null ? "" : String.valueOf(rawItemId));
                int amount = parseAmount(itemConfig.get("amount"));
                if (!itemId.isEmpty()) {
                    rewards.add(new SkillItemReward(itemId, amount));
                }
            }
        }
        return rewards;
    }

    private int parseLevel(String rawLevel) {
        try {
            return Math.max(1, Integer.parseInt(rawLevel));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int parseAmount(Object rawAmount) {
        if (rawAmount instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (rawAmount instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }
}
