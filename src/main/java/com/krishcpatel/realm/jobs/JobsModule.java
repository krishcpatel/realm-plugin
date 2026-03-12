package com.krishcpatel.realm.jobs;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import com.krishcpatel.realm.jobs.command.JobCommand;
import com.krishcpatel.realm.jobs.listener.JobsActionListener;
import com.krishcpatel.realm.jobs.manager.JobManager;
import com.krishcpatel.realm.jobs.manager.JobRewardService;
import com.krishcpatel.realm.jobs.notify.JobPayoutNotifier;
import com.krishcpatel.realm.jobs.registry.JobDefinitionRegistry;
import com.krishcpatel.realm.jobs.repository.JobsRepository;

import java.sql.SQLException;

/**
 * Bootstrapper for the jobs module.
 */
public final class JobsModule implements Module {
    private final Core core;

    private JobsRepository jobsRepo;
    private JobDefinitionRegistry registry;
    private JobManager manager;

    /**
     * Creates a jobs module bootstrapper for the plugin.
     *
     * @param core plugin instance
     */
    public JobsModule(Core core) {
        this.core = core;
    }

    /** {@inheritDoc} */
    @Override
    public void enable() throws SQLException {
        if (!core.config().getBoolean("modules.jobs", false)) {
            core.getLogger().info("[jobs] module disabled in config.");
            return;
        }

        jobsRepo = new JobsRepository(core.getDatabase());
        jobsRepo.initSchema();
        purgeStalePlacedGuards();

        registry = new JobDefinitionRegistry(core);
        registry.reload();

        EconomyRepository economyRepo = new EconomyRepository(core.getDatabase());
        LedgerRepository ledgerRepo = new LedgerRepository(core.getDatabase());
        economyRepo.initSchema();
        ledgerRepo.initSchema();

        manager = new JobManager(core, jobsRepo, registry);
        JobPayoutNotifier notifier = new JobPayoutNotifier(core);
        JobRewardService rewards = new JobRewardService(core, jobsRepo, registry, economyRepo, ledgerRepo, notifier);

        core.getServer().getPluginManager().registerEvents(new JobsActionListener(core, rewards, jobsRepo), core);
        core.getCommand("job").setExecutor(new JobCommand(core, manager, registry));

        core.getLogger().info("[jobs] enabled with " + registry.all().size() + " configured jobs.");
    }

    /** {@inheritDoc} */
    @Override
    public void disable() {
        core.getLogger().info("[jobs] disabled");
    }

    /** {@inheritDoc} */
    @Override
    public void reload() {
        if (!core.config().getBoolean("modules.jobs", false)) {
            return;
        }

        if (registry != null) {
            registry.reload();
            purgeStalePlacedGuards();
            core.getLogger().info("[jobs] reloaded with " + registry.all().size() + " configured jobs.");
        }
    }

    private void purgeStalePlacedGuards() {
        if (jobsRepo == null) {
            return;
        }
        if (!core.jobsConfig().getBoolean("settings.anti-farm.ignore-player-placed-breaks", true)) {
            return;
        }

        long retentionDays = Math.max(1L, core.jobsConfig().getLong("settings.anti-farm.placed-block-guard-retention-days", 14L));
        long cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L);

        try {
            int purged = jobsRepo.purgePlacedBlockGuardsOlderThan(cutoff);
            if (purged > 0) {
                core.getLogger().info("[jobs] Purged " + purged + " stale placed-block guards.");
            }
        } catch (SQLException e) {
            core.getLogger().severe("[jobs] Failed to purge stale placed-block guards.");
            e.printStackTrace();
        }
    }
}
