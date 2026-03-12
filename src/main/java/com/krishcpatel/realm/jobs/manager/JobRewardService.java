package com.krishcpatel.realm.jobs.manager;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.economy.event.LedgerRecordedEvent;
import com.krishcpatel.realm.economy.model.MoneySource;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import com.krishcpatel.realm.jobs.event.JobLevelUpEvent;
import com.krishcpatel.realm.jobs.model.*;
import com.krishcpatel.realm.jobs.notify.JobPayoutNotifier;
import com.krishcpatel.realm.jobs.registry.JobDefinitionRegistry;
import com.krishcpatel.realm.jobs.repository.JobsRepository;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Converts normalized job actions into xp, levels, and money payouts.
 */
public final class JobRewardService {
    private static final String GROUP_BOSS_DAMAGE_SHARE = "#BOSS_DAMAGE_SHARE";

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
            if (isBossDamageShareAction(action)) {
                processGlobalBossPayout(action);
                return;
            }

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
        String dailyCapKey = JobManager.dailyCapKey(playerJob.jobId());
        String rewardReason = "Job reward: " + definition.displayName() + " " + action.type().name();

        JobWriteResult writeResult = core.getDatabase().executeWrite(() -> {
            try (Connection c = core.getDatabase().getConnection()) {
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
                            return null;
                        }
                    }

                    PlayerJob current = repo.getJob(c, playerUuid, playerJob.jobId());
                    if (current == null) {
                        c.rollback();
                        return null;
                    }

                    long money;
                    long xp;
                    money = scaleMoneyReward(rule, definition.leveling(), current.level(), action.amount());
                    xp = scaleXpReward(rule, definition.leveling(), current.level(), action.amount());

                    JobCapState capState = repo.getCapState(c, playerUuid, playerJob.jobId(), dailyCapKey, dayKey);
                    money = clampToCap(money, capState.moneyEarned(), definition.dailyMoneyCap());
                    xp = clampToCap(xp, capState.xpEarned(), definition.dailyXpCap());

                    if (money <= 0 && xp <= 0) {
                        c.rollback();
                        return null;
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
                    repo.addCapEarnings(c, playerUuid, playerJob.jobId(), dailyCapKey, dayKey, money, xp);
                    c.commit();

                    return new JobWriteResult(money, xp, current.level(), update.level(), ledgerId);
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }
        });

        if (writeResult == null) {
            return;
        }

        if (writeResult.ledgerId() > 0) {
            core.events().publishAsync(new LedgerRecordedEvent(
                    writeResult.ledgerId(),
                    "MINT",
                    writeResult.money(),
                    null,
                    playerUuid,
                    MoneySource.JOBS,
                    rewardKey,
                    rewardReason,
                    "SYSTEM"
            ));
        }

        if (writeResult.money() > 0 || writeResult.xp() > 0) {
            notifier.notifyPayout(action.playerUuid(), definition.displayName(), writeResult.money(), writeResult.xp());
        }

        if (writeResult.newLevel() > writeResult.oldLevel()) {
            core.events().publishAsync(new JobLevelUpEvent(
                    action.playerUuid(),
                    action.playerName(),
                    playerJob.jobId(),
                    writeResult.oldLevel(),
                    writeResult.newLevel()
            ));

            Bukkit.getScheduler().runTask(core, () -> {
                var player = Bukkit.getPlayer(action.playerUuid());
                if (player != null) {
                    player.sendMessage(core.msg("jobs.level-up", java.util.Map.of(
                            "%job%", definition.displayName(),
                            "%level%", String.valueOf(writeResult.newLevel())
                    )));
                }
            });
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

    private void processGlobalBossPayout(JobActionContext action) throws SQLException {
        if (!core.jobsConfig().getBoolean("settings.boss-payouts.enabled", true)) {
            return;
        }

        BossPayoutRule rule = resolveBossPayoutRule(action.target());
        if (rule == null) {
            return;
        }

        String playerUuid = action.playerUuid().toString();
        long now = System.currentTimeMillis();
        long dayKey = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        double share = bossDamageShare(action.amount());
        if (share <= 0D) {
            return;
        }

        String rewardKey = "boss:" + rule.bossType();
        String rewardReason = "Boss payout: " + prettifyBossType(rule.bossType());

        GlobalBossWriteResult writeResult = core.getDatabase().executeWrite(() -> {
            try (Connection c = core.getDatabase().getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                long ledgerId = -1L;

                try {
                    boolean bypassDailyClaim = core.jobsConfig().getBoolean(
                            "settings.boss-payouts.testing.bypass-daily-claim",
                            false
                    );
                    if (!bypassDailyClaim) {
                        boolean firstClaimToday = repo.markGlobalBossPayoutClaimed(
                                c,
                                playerUuid,
                                rule.bossType(),
                                dayKey,
                                now
                        );
                        if (!firstClaimToday) {
                            c.rollback();
                            return null;
                        }
                    }

                    long baseMoney = randomBetween(rule.moneyMin(), rule.moneyMax());
                    long money = Math.max(0L, Math.round(baseMoney * share));
                    if (money <= 0L && baseMoney > 0L && share > 0D) {
                        money = 1L;
                    }
                    if (money <= 0L) {
                        c.rollback();
                        return null;
                    }

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

                    c.commit();
                    return new GlobalBossWriteResult(money, ledgerId, rule.bossType());
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            }
        });

        if (writeResult == null) {
            return;
        }

        if (writeResult.ledgerId() > 0) {
            core.events().publishAsync(new LedgerRecordedEvent(
                    writeResult.ledgerId(),
                    "MINT",
                    writeResult.money(),
                    null,
                    playerUuid,
                    MoneySource.JOBS,
                    rewardKey,
                    rewardReason,
                    "SYSTEM"
            ));
        }

        notifier.notifyPayout(action.playerUuid(), "Boss Bounty", writeResult.money(), 0L);
    }

    private boolean isBossDamageShareAction(JobActionContext action) {
        return action.groups().contains(GROUP_BOSS_DAMAGE_SHARE);
    }

    private BossPayoutRule resolveBossPayoutRule(String rawBossType) {
        String bossType = normalizeBossType(rawBossType);
        ConfigurationSection rewardsSection = core.jobsConfig().getConfigurationSection("settings.boss-payouts.rewards");
        if (rewardsSection == null) {
            return null;
        }

        String matchedKey = rewardsSection.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase(bossType))
                .findFirst()
                .orElse(null);
        if (matchedKey == null) {
            return null;
        }

        String basePath = "settings.boss-payouts.rewards." + matchedKey;
        long min = Math.max(0L, core.jobsConfig().getLong(basePath + ".money-min", 0L));
        long max = Math.max(min, core.jobsConfig().getLong(basePath + ".money-max", min));
        return new BossPayoutRule(normalizeBossType(matchedKey), min, max);
    }

    private String normalizeBossType(String rawBossType) {
        if (rawBossType == null || rawBossType.isBlank()) {
            return "UNKNOWN";
        }
        return rawBossType.trim().toUpperCase(Locale.ROOT);
    }

    private String prettifyBossType(String bossType) {
        String[] parts = bossType.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.isEmpty() ? bossType : out.toString();
    }

    private double bossDamageShare(int amount) {
        if (amount <= 0) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, amount / 10_000D));
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

    private record BossPayoutRule(String bossType, long moneyMin, long moneyMax) {
    }

    private record GlobalBossWriteResult(long money, long ledgerId, String bossType) {
    }

    private record JobWriteResult(long money, long xp, int oldLevel, int newLevel, long ledgerId) {
    }
}
