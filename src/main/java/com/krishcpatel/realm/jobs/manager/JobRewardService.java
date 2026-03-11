package com.krishcpatel.realm.jobs.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.event.LedgerRecordedEvent;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import com.krishcpatel.realm.jobs.event.JobLevelUpEvent;
import com.krishcpatel.realm.jobs.model.JobActionContext;
import com.krishcpatel.realm.jobs.model.JobCapState;
import com.krishcpatel.realm.jobs.model.JobDefinition;
import com.krishcpatel.realm.jobs.model.LevelingSettings;
import com.krishcpatel.realm.jobs.model.PlayerJob;
import com.krishcpatel.realm.jobs.model.RewardRule;
import com.krishcpatel.realm.jobs.notify.JobPayoutNotifier;
import com.krishcpatel.realm.jobs.registry.JobDefinitionRegistry;
import com.krishcpatel.realm.jobs.repository.JobsRepository;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Converts normalized job actions into xp, levels, and money payouts.
 */
public final class JobRewardService {
    private final Core core;
    private final JobsRepository repo;
    private final JobDefinitionRegistry registry;
    private final EconomyRepository economy;
    private final LedgerRepository ledger;
    private final JobPayoutNotifier notifier;
    private final Map<String, Long> rewardCooldowns = new ConcurrentHashMap<>();

    /**
     * Creates the job reward processor and its economy dependencies.
     *
     * @param core plugin instance
     * @param repo jobs persistence layer
     * @param registry loaded job definitions
     * @param economy economy account repository
     * @param ledger economy ledger repository
     * @param notifier payout notification service
     */
    public JobRewardService(
            Core core,
            JobsRepository repo,
            JobDefinitionRegistry registry,
            EconomyRepository economy,
            LedgerRepository ledger,
            JobPayoutNotifier notifier
    ) {
        this.core = core;
        this.repo = repo;
        this.registry = registry;
        this.economy = economy;
        this.ledger = ledger;
        this.notifier = notifier;
    }

    /**
     * Processes a normalized job action for all jobs currently assigned to the player.
     *
     * @param action normalized player action
     */
    public void handleAction(JobActionContext action) {
        try {
            List<PlayerJob> jobs = repo.getJobs(action.playerUuid().toString());
            for (PlayerJob playerJob : jobs) {
                processActionForJob(action, playerJob);
            }
        } catch (Exception e) {
            core.getLogger().severe("[jobs] Failed to process action " + action.type() + " for " + action.playerName());
            e.printStackTrace();
        }
    }

    private void processActionForJob(JobActionContext action, PlayerJob playerJob) throws SQLException {
        Optional<JobDefinition> maybeDefinition = registry.get(playerJob.jobId());
        if (maybeDefinition.isEmpty()) {
            return;
        }

        JobDefinition definition = maybeDefinition.get();
        Optional<RewardRule> maybeRule = definition.resolveRule(action);
        if (maybeRule.isEmpty()) {
            return;
        }

        RewardRule rule = maybeRule.get();
        if (isOnCooldown(action, playerJob.jobId())) {
            return;
        }

        String playerUuid = action.playerUuid().toString();
        long now = System.currentTimeMillis();
        long dayKey = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        String rewardKey = playerJob.jobId() + ":" + action.type().name() + ":" + rule.selector();
        String rewardReason = "Job reward: " + definition.displayName() + " " + action.type().name();

        Connection c = core.getDatabase().getConnection();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        long ledgerId = -1L;

        try {
            if (action.type().name().equals("EXPLORE")) {
                boolean firstVisit = repo.markChunkExplored(
                        c,
                        playerUuid,
                        playerJob.jobId(),
                        action.worldName(),
                        action.chunkX(),
                        action.chunkZ(),
                        now
                );
                if (!firstVisit) {
                    c.rollback();
                    return;
                }
            }

            PlayerJob current = repo.getJob(c, playerUuid, playerJob.jobId());
            if (current == null) {
                c.rollback();
                return;
            }

            long money = scaleMoneyReward(rule, definition.leveling(), current.level(), action.amount());
            long xp = scaleXpReward(rule, definition.leveling(), current.level(), action.amount());

            JobCapState capState = repo.getCapState(c, playerUuid, playerJob.jobId(), rewardKey, dayKey);
            money = clampToCap(money, capState.moneyEarned(), rule.dailyMoneyCap());
            xp = clampToCap(xp, capState.xpEarned(), rule.dailyXpCap());

            if (money <= 0 && xp <= 0) {
                c.rollback();
                return;
            }

            ProgressUpdate update = applyProgress(current, definition.leveling(), xp);
            if (money > 0) {
                economy.ensureAccount(c, playerUuid);
                economy.addBalance(c, playerUuid, money);
                ledgerId = ledger.insertLedgerRow(
                        c,
                        now,
                        "MINT",
                        money,
                        null,
                        playerUuid,
                        MoneySource.JOBS.name(),
                        rewardKey,
                        rewardReason,
                        "SYSTEM"
                );
            }

            repo.updateProgress(c, playerUuid, playerJob.jobId(), update.level(), update.xp(), update.totalXp());
            repo.addCapEarnings(c, playerUuid, playerJob.jobId(), rewardKey, dayKey, money, xp);
            c.commit();

            if (ledgerId > 0) {
                core.events().publishAsync(new LedgerRecordedEvent(
                        ledgerId,
                        "MINT",
                        money,
                        null,
                        playerUuid,
                        MoneySource.JOBS,
                        rewardKey,
                        rewardReason,
                        "SYSTEM"
                ));
            }

            if (money > 0 || xp > 0) {
                notifier.notifyPayout(action.playerUuid(), definition.displayName(), money, xp);
            }

            if (update.level() > current.level()) {
                core.events().publishAsync(new JobLevelUpEvent(
                        action.playerUuid(),
                        action.playerName(),
                        playerJob.jobId(),
                        current.level(),
                        update.level()
                ));

                Bukkit.getScheduler().runTask(core, () -> {
                    var player = Bukkit.getPlayer(action.playerUuid());
                    if (player != null) {
                        player.sendMessage(core.msg("jobs.level-up", java.util.Map.of(
                                "%job%", definition.displayName(),
                                "%level%", String.valueOf(update.level())
                        )));
                    }
                });
            }
        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    private boolean isOnCooldown(JobActionContext action, String jobId) {
        long cooldownMs = Math.max(0L, core.jobsConfig().getLong(
                "settings.anti-farm.action-cooldown-ms." + action.type().name().toLowerCase(),
                0L
        ));
        if (cooldownMs <= 0L) {
            return false;
        }

        long now = System.currentTimeMillis();
        String key = action.playerUuid() + ":" + jobId + ":" + action.type().name() + ":" + action.target();
        Long previous = rewardCooldowns.get(key);
        if (previous != null && now - previous < cooldownMs) {
            return true;
        }

        rewardCooldowns.put(key, now);
        return false;
    }

    private long scaleMoneyReward(RewardRule rule, LevelingSettings leveling, int level, int amount) {
        long base = randomBetween(rule.moneyMin(), rule.moneyMax());
        return Math.max(0L, Math.round(base * Math.max(1, amount) * leveling.moneyMultiplier(level)));
    }

    private long scaleXpReward(RewardRule rule, LevelingSettings leveling, int level, int amount) {
        long base = randomBetween(rule.xpMin(), rule.xpMax());
        return Math.max(0L, Math.round(base * Math.max(1, amount) * leveling.xpMultiplier(level)));
    }

    private long randomBetween(long min, long max) {
        long normalizedMin = Math.max(0L, Math.min(min, max));
        long normalizedMax = Math.max(normalizedMin, Math.max(min, max));
        if (normalizedMin == normalizedMax) {
            return normalizedMin;
        }
        return ThreadLocalRandom.current().nextLong(normalizedMin, normalizedMax + 1L);
    }

    private long clampToCap(long value, long alreadyEarned, long cap) {
        if (cap <= 0) {
            return value;
        }
        return Math.max(0L, Math.min(value, cap - alreadyEarned));
    }

    private ProgressUpdate applyProgress(PlayerJob current, LevelingSettings leveling, long gainedXp) {
        int level = Math.max(1, Math.min(current.level(), leveling.maxLevel()));
        long xp = Math.max(0L, current.xp());
        long totalXp = Math.max(0L, current.totalXp()) + Math.max(0L, gainedXp);
        long remaining = Math.max(0L, gainedXp);

        while (remaining > 0 && level < leveling.maxLevel()) {
            long required = leveling.requiredXpForLevel(level);
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

        if (level >= leveling.maxLevel()) {
            xp = 0L;
        }

        return new ProgressUpdate(level, xp, totalXp);
    }

    private record ProgressUpdate(int level, long xp, long totalXp) {
    }
}
