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

import java.util.HashMap;
import java.util.Map;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies skill xp actions and handles leveling progression.
 */
public final class SkillProgressService {
    private final Core core;
    private final SkillsRepository repo;
    private final SkillDefinitionRegistry registry;
    private final SkillNotifier notifier;
    private final Queue<SkillActionContext> pendingActions = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

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
        enqueueAction(action);
    }

    /**
     * Queues one normalized skill action to be coalesced and persisted.
     *
     * @param action normalized skill action
     */
    public void enqueueAction(SkillActionContext action) {
        if (action == null) {
            return;
        }
        pendingActions.offer(action);
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }

        long windowMs = Math.max(50L, core.skillsConfig().getLong("settings.persistence.aggregate-window-ms", 200L));
        long delayTicks = Math.max(1L, (windowMs + 49L) / 50L);

        core.getServer().getScheduler().runTaskLaterAsynchronously(core, this::flushPendingActions, delayTicks);
    }

    private void flushPendingActions() {
        try {
            Map<PendingSkillAction, Integer> merged = new HashMap<>();
            SkillActionContext action;
            while ((action = pendingActions.poll()) != null) {
                PendingSkillAction key = new PendingSkillAction(
                        action.playerUuid(),
                        action.playerName(),
                        action.skillId(),
                        action.actionKey()
                );
                merged.merge(key, Math.max(1, action.amount()), Integer::sum);
            }

            for (Map.Entry<PendingSkillAction, Integer> entry : merged.entrySet()) {
                PendingSkillAction key = entry.getKey();
                processActionNow(new SkillActionContext(
                        key.playerUuid(),
                        key.playerName(),
                        key.skillId(),
                        key.actionKey(),
                        entry.getValue()
                ));
            }
        } finally {
            flushScheduled.set(false);
            if (!pendingActions.isEmpty()) {
                scheduleFlush();
            }
        }
    }

    private void processActionNow(SkillActionContext action) {
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

        try {
            SkillWriteResult writeResult = core.getDatabase().executeWrite(() -> {
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
                            return null;
                        }

                        ProgressUpdate update = applyProgress(current, definition.progression(), gainedXp, now);
                        repo.updateSkill(c, playerUuid, skillId, update.level(), update.xp(), update.totalXp(), update.updatedAt());
                        c.commit();

                        return new SkillWriteResult(current.level(), update.level(), update.xp(), update.totalXp());
                    } catch (SQLException e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(oldAuto);
                    }
                }
            });

            if (writeResult == null) {
                return;
            }

            long requiredXp = definition.progression().requiredXpForLevel(writeResult.newLevel());
            notifier.notifyXpGain(
                    action.playerUuid(),
                    definition.displayName(),
                    gainedXp,
                    writeResult.newLevel(),
                    writeResult.newXp(),
                    requiredXp
            );

            if (writeResult.newLevel() > writeResult.oldLevel()) {
                core.events().publishAsync(new SkillLevelUpEvent(
                        action.playerUuid(),
                        action.playerName(),
                        skillId,
                        writeResult.oldLevel(),
                        writeResult.newLevel()
                ));
                notifier.notifyLevelUp(action.playerUuid(), definition.displayName(), writeResult.newLevel());
            }
        } catch (SQLException e) {
            core.getLogger().severe("[skills] Failed to process action " + action.actionKey()
                    + " for " + action.playerName() + " skill=" + skillId);
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

    private record PendingSkillAction(UUID playerUuid, String playerName, String skillId, String actionKey) {
    }

    private record SkillWriteResult(int oldLevel, int newLevel, long newXp, long totalXp) {
    }
}
