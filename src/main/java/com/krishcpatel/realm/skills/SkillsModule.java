package com.krishcpatel.realm.skills;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.jobs.repository.JobsRepository;
import com.krishcpatel.realm.skills.command.SkillsCommand;
import com.krishcpatel.realm.skills.listener.SkillsActionListener;
import com.krishcpatel.realm.skills.manager.SkillManager;
import com.krishcpatel.realm.skills.manager.SkillProgressService;
import com.krishcpatel.realm.skills.notify.SkillNotifier;
import com.krishcpatel.realm.skills.registry.SkillDefinitionRegistry;
import com.krishcpatel.realm.skills.repository.SkillsRepository;

import java.sql.SQLException;

/**
 * Bootstrapper for the skills module.
 */
public final class SkillsModule implements Module {
    private final Core core;

    private SkillsRepository repo;
    private SkillDefinitionRegistry registry;
    private SkillManager manager;

    /**
     * Creates the skills module.
     *
     * @param core plugin instance
     */
    public SkillsModule(Core core) {
        this.core = core;
    }

    /** {@inheritDoc} */
    @Override
    public void enable() throws SQLException {
        if (!core.config().getBoolean("modules.skills", false)) {
            core.getLogger().info("[skills] module disabled in config.");
            return;
        }

        repo = new SkillsRepository(core.getDatabase());
        repo.initSchema();
        JobsRepository guardRepo = new JobsRepository(core.getDatabase());
        guardRepo.initSchema();
        purgeStalePlacedGuards(guardRepo);

        registry = new SkillDefinitionRegistry(core);
        registry.reload();

        manager = new SkillManager(repo);
        SkillNotifier notifier = new SkillNotifier(core);
        SkillProgressService progress = new SkillProgressService(core, repo, registry, notifier);

        core.getServer().getPluginManager().registerEvents(new SkillsActionListener(core, progress, guardRepo), core);
        if (core.getCommand("skills") != null) {
            core.getCommand("skills").setExecutor(new SkillsCommand(core, manager, registry));
        }

        core.getLogger().info("[skills] enabled with " + registry.all().size() + " configured skills.");
    }

    /** {@inheritDoc} */
    @Override
    public void disable() {
        core.getLogger().info("[skills] disabled");
    }

    /** {@inheritDoc} */
    @Override
    public void reload() {
        if (!core.config().getBoolean("modules.skills", false)) {
            return;
        }

        if (registry != null) {
            registry.reload();
            JobsRepository guardRepo = new JobsRepository(core.getDatabase());
            try {
                guardRepo.initSchema();
                purgeStalePlacedGuards(guardRepo);
            } catch (SQLException e) {
                core.getLogger().severe("[skills] Failed to initialize placed-block guard schema during reload.");
                e.printStackTrace();
            }
            core.getLogger().info("[skills] reloaded with " + registry.all().size() + " configured skills.");
        }
    }

    private void purgeStalePlacedGuards(JobsRepository guardRepo) {
        if (!core.skillsConfig().getBoolean("settings.anti-farm.ignore-player-placed-breaks", true)) {
            return;
        }

        long retentionDays = Math.max(1L, core.skillsConfig().getLong("settings.anti-farm.placed-block-guard-retention-days", 14L));
        long cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L);

        try {
            int purged = guardRepo.purgePlacedBlockGuardsOlderThan(cutoff);
            if (purged > 0) {
                core.getLogger().info("[skills] Purged " + purged + " stale placed-block guards.");
            }
        } catch (SQLException e) {
            core.getLogger().severe("[skills] Failed to purge stale placed-block guards.");
            e.printStackTrace();
        }
    }
}
