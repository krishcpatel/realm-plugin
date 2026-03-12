package com.krishcpatel.realm.skills.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.skills.event.SkillLevelUpEvent;
import com.krishcpatel.realm.skills.model.SkillActionContext;
import com.krishcpatel.realm.skills.model.SkillDefinition;
import com.krishcpatel.realm.skills.model.SkillProgress;
import com.krishcpatel.realm.skills.model.SkillProgressionSettings;
import com.krishcpatel.realm.skills.notify.SkillNotifier;
import com.krishcpatel.realm.skills.registry.SkillDefinitionRegistry;
import com.krishcpatel.realm.skills.repository.SkillsRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Applies skill xp actions and handles leveling progression.
 */
public final class SkillProgressService {
    private final Core core;
    private final SkillsRepository repo;
    private final SkillDefinitionRegistry registry;
    private final SkillNotifier notifier;

    /**
     * Creates the skill progression service.
     *
     * @param core plugin instance
     * @param repo skills repository
     * @param registry skill definition registry
     * @param notifier skill notification helper
     */
    public SkillProgressService(Core core, SkillsRepository repo, SkillDefinitionRegistry registry, SkillNotifier notifier) {
        this.core = core;
        this.repo = repo;
        this.registry = registry;
        this.notifier = notifier;
    }

    /**
     * Processes one normalized skill action for a player.
     *
     * @param action normalized skill action
     */
    public void handleAction(SkillActionContext action) {
        String skillId = SkillManager.normalizeSkillId(action.skillId());
        Optional<SkillDefinition> maybeDefinition = registry.get(skillId);
        if (maybeDefinition.isEmpty()) {
            return;
        }

        SkillDefinition definition = maybeDefinition.get();
        long xpPerAction = definition.xpForAction(action.actionKey());
        if (xpPerAction <= 0L) {
            return;
        }

        long amount = Math.max(1L, action.amount());
        long gainedXp = Math.max(0L, xpPerAction * amount);
        if (gainedXp <= 0L) {
            return;
        }

        String playerUuid = action.playerUuid().toString();
        long now = System.currentTimeMillis();
        int startingLevel = Math.max(1, core.skillsConfig().getInt("settings.starting-level", 1));

        try (Connection c = core.getDatabase().getConnection()) {
            boolean oldAuto = c.getAutoCommit();
            c.setAutoCommit(false);

            try {
                SkillProgress current = repo.getSkill(c, playerUuid, skillId);
                if (current == null) {
                    repo.ensureSkill(c, playerUuid, skillId, startingLevel, now);
                    current = repo.getSkill(c, playerUuid, skillId);
                }
                if (current == null) {
                    c.rollback();
                    return;
                }

                ProgressUpdate update = applyProgress(current, definition.progression(), gainedXp, now);
                repo.updateSkill(c, playerUuid, skillId, update.level(), update.xp(), update.totalXp(), update.updatedAt());
                c.commit();

                long requiredXp = definition.progression().requiredXpForLevel(update.level());
                notifier.notifyXpGain(
                        action.playerUuid(),
                        definition.displayName(),
                        gainedXp,
                        update.level(),
                        update.xp(),
                        requiredXp
                );

                if (update.level() > current.level()) {
                    core.events().publishAsync(new SkillLevelUpEvent(
                            action.playerUuid(),
                            action.playerName(),
                            skillId,
                            current.level(),
                            update.level()
                    ));
                    notifier.notifyLevelUp(action.playerUuid(), definition.displayName(), update.level());
                }
            } catch (SQLException e) {
                c.rollback();
                core.getLogger().severe("[skills] Failed to process action " + action.actionKey()
                        + " for " + action.playerName() + " skill=" + skillId);
                e.printStackTrace();
            } finally {
                c.setAutoCommit(oldAuto);
            }
        } catch (SQLException e) {
            core.getLogger().severe("[skills] Failed to get database connection for " + action.playerName());
            e.printStackTrace();
        }
    }

    private ProgressUpdate applyProgress(SkillProgress current, SkillProgressionSettings progression, long gainedXp, long updatedAt) {
        int level = Math.max(1, Math.min(current.level(), progression.maxLevel()));
        long xp = Math.max(0L, current.xp());
        long totalXp = Math.max(0L, current.totalXp()) + Math.max(0L, gainedXp);
        long remaining = Math.max(0L, gainedXp);

        while (remaining > 0 && level < progression.maxLevel()) {
            long required = progression.requiredXpForLevel(level);
            long needed = Math.max(1L, required - xp);

            if (remaining >= needed) {
                remaining -= needed;
                level++;
                xp = 0L;
            } else {
                xp += remaining;
                remaining = 0L;
            }
        }

        if (level >= progression.maxLevel()) {
            xp = 0L;
        }

        return new ProgressUpdate(level, xp, totalXp, updatedAt);
    }

    private record ProgressUpdate(int level, long xp, long totalXp, long updatedAt) {
    }
}
