package com.krishcpatel.realm.skills;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
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

        registry = new SkillDefinitionRegistry(core);
        registry.reload();

        manager = new SkillManager(repo);
        SkillNotifier notifier = new SkillNotifier(core);
        SkillProgressService progress = new SkillProgressService(core, repo, registry, notifier);

        core.getServer().getPluginManager().registerEvents(new SkillsActionListener(core, progress), core);
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
            core.getLogger().info("[skills] reloaded with " + registry.all().size() + " configured skills.");
        }
    }
}
