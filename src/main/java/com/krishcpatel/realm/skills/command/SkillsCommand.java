package com.krishcpatel.realm.skills.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.skills.manager.SkillManager;
import com.krishcpatel.realm.skills.model.SkillCategory;
import com.krishcpatel.realm.skills.model.SkillDefinition;
import com.krishcpatel.realm.skills.model.SkillProgress;
import com.krishcpatel.realm.skills.registry.SkillDefinitionRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Command for viewing player skill levels and xp.
 */
public final class SkillsCommand implements CommandExecutor {
    private final Core core;
    private final SkillManager manager;
    private final SkillDefinitionRegistry registry;

    /**
     * Creates the skills command handler.
     *
     * @param core plugin instance
     * @param manager skill manager
     * @param registry skill definitions registry
     */
    public SkillsCommand(Core core, SkillManager manager, SkillDefinitionRegistry registry) {
        this.core = core;
        this.manager = manager;
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.msg("general.player-only"));
            return true;
        }

        if (!player.hasPermission("realm.skills.use")) {
            player.sendMessage(core.msg("general.no-permission"));
            return true;
        }

        if (args.length > 1) {
            player.sendMessage(core.msg("skills.usage"));
            return true;
        }

        String filter = args.length == 1 ? args[0].trim().toLowerCase(Locale.ROOT) : "";
        SkillCategory categoryFilter = SkillCategory.fromConfigKey(filter);

        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                Map<String, SkillProgress> progressBySkill = manager.getSkills(player.getUniqueId())
                        .stream()
                        .collect(Collectors.toMap(SkillProgress::skillId, it -> it));

                Predicate<SkillDefinition> predicate = definition -> {
                    if (filter.isBlank()) {
                        return true;
                    }
                    if (categoryFilter != null) {
                        return definition.category() == categoryFilter;
                    }
                    return definition.id().contains(filter)
                            || definition.displayName().toLowerCase(Locale.ROOT).contains(filter);
                };

                List<SkillDefinition> definitions = registry.all().stream()
                        .filter(predicate)
                        .sorted(Comparator.comparing(SkillDefinition::category).thenComparing(SkillDefinition::displayName))
                        .toList();

                int startingLevel = Math.max(1, core.skillsConfig().getInt("settings.starting-level", 1));

                core.getServer().getScheduler().runTask(core, () -> {
                    if (definitions.isEmpty()) {
                        player.sendMessage(core.msg("skills.no-matches"));
                        return;
                    }

                    if (categoryFilter != null) {
                        player.sendMessage(core.msg("skills.category-header", Map.of(
                                "%category%", categoryFilter.displayName()
                        )));
                    } else {
                        player.sendMessage(core.msg("skills.header"));
                    }

                    for (SkillDefinition definition : definitions) {
                        SkillProgress current = progressBySkill.getOrDefault(
                                definition.id(),
                                new SkillProgress(definition.id(), startingLevel, 0L, 0L, 0L)
                        );

                        long requiredXp = definition.progression().requiredXpForLevel(current.level());
                        player.sendMessage(core.msg("skills.item", Map.of(
                                "%skill%", definition.displayName(),
                                "%level%", String.valueOf(current.level()),
                                "%xp%", String.valueOf(current.xp()),
                                "%required%", String.valueOf(requiredXp),
                                "%total_xp%", String.valueOf(current.totalXp())
                        )));
                    }
                });
            } catch (Exception e) {
                core.getLogger().severe("[skills] Failed to load /skills for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("general.command-failed"))
                );
            }
        });

        return true;
    }
}
