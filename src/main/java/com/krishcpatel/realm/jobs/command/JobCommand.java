package com.krishcpatel.realm.jobs.command;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.jobs.manager.JobManager;
import com.krishcpatel.realm.jobs.model.JobDefinition;
import com.krishcpatel.realm.jobs.model.JobMembershipResult;
import com.krishcpatel.realm.jobs.model.PlayerJob;
import com.krishcpatel.realm.jobs.registry.JobDefinitionRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal jobs command surface for joining, leaving, and viewing jobs.
 */
public final class JobCommand implements CommandExecutor {
    private final Core core;
    private final JobManager manager;
    private final JobDefinitionRegistry registry;

    /**
     * Creates the primary jobs command handler.
     *
     * @param core plugin instance
     * @param manager player job manager
     * @param registry loaded job definitions
     */
    public JobCommand(Core core, JobManager manager, JobDefinitionRegistry registry) {
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

        if (!player.hasPermission("realm.jobs.use")) {
            player.sendMessage(core.msg("general.no-permission"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(core.msg("jobs.usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> showJobsList(player);
            case "stats" -> showStats(player);
            case "join" -> {
                if (args.length != 2) {
                    player.sendMessage(core.msg("jobs.usage"));
                    return true;
                }
                joinJob(player, args[1]);
            }
            case "leave" -> {
                if (args.length != 2) {
                    player.sendMessage(core.msg("jobs.usage"));
                    return true;
                }
                leaveJob(player, args[1]);
            }
            default -> player.sendMessage(core.msg("jobs.usage"));
        }

        return true;
    }

    private void showJobsList(Player player) {
        player.sendMessage(core.msg("jobs.list-header"));

        registry.all().stream()
                .sorted(Comparator.comparing(JobDefinition::displayName))
                .forEach(definition -> player.sendMessage(core.msg("jobs.list-item", Map.of(
                        "%job%", definition.displayName(),
                        "%id%", definition.id(),
                        "%description%", definition.description()
                ))));

        if (core.jobsConfig().getBoolean("settings.allow-no-job", true)) {
            player.sendMessage(core.msg("jobs.list-none"));
        }
    }

    private void showStats(Player player) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                List<PlayerJob> jobs = manager.getJobs(player.getUniqueId());

                core.getServer().getScheduler().runTask(core, () -> {
                    if (jobs.isEmpty()) {
                        player.sendMessage(core.msg("jobs.stats-empty"));
                        return;
                    }

                    player.sendMessage(core.msg("jobs.stats-header"));
                    for (PlayerJob job : jobs) {
                        String displayName = registry.get(job.jobId())
                                .map(JobDefinition::displayName)
                                .orElse(job.jobId());

                        player.sendMessage(core.msg("jobs.stats-item", Map.of(
                                "%job%", displayName,
                                "%level%", String.valueOf(job.level()),
                                "%xp%", String.valueOf(job.xp()),
                                "%total_xp%", String.valueOf(job.totalXp())
                        )));
                    }
                });
            } catch (Exception e) {
                core.getLogger().severe("[jobs] Failed to load /job stats for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("general.command-failed"))
                );
            }
        });
    }

    private void joinJob(Player player, String rawJob) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                JobMembershipResult result;
                if (isNoJobSelection(rawJob)) {
                    result = manager.leaveAllJobs(player.getUniqueId());
                } else {
                    result = manager.joinJob(player.getUniqueId(), rawJob);
                }

                JobMembershipResult finalResult = result;
                core.getServer().getScheduler().runTask(core, () -> {
                    if (!finalResult.success()) {
                        player.sendMessage(core.msg("jobs.join-failed", Map.of(
                                "%reason%", finalResult.message()
                        )));
                        return;
                    }

                    player.sendMessage(core.msg("jobs.join-success", Map.of(
                            "%message%", finalResult.message()
                    )));
                });
            } catch (Exception e) {
                core.getLogger().severe("[jobs] Failed to join job for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("general.command-failed"))
                );
            }
        });
    }

    private void leaveJob(Player player, String rawJob) {
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                JobMembershipResult result = isNoJobSelection(rawJob)
                        ? manager.leaveAllJobs(player.getUniqueId())
                        : manager.leaveJob(player.getUniqueId(), rawJob);

                core.getServer().getScheduler().runTask(core, () -> {
                    if (!result.success()) {
                        player.sendMessage(core.msg("jobs.leave-failed", Map.of(
                                "%reason%", result.message()
                        )));
                        return;
                    }

                    player.sendMessage(core.msg("jobs.leave-success", Map.of(
                            "%message%", result.message()
                    )));
                });
            } catch (Exception e) {
                core.getLogger().severe("[jobs] Failed to leave job for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        player.sendMessage(core.msg("general.command-failed"))
                );
            }
        });
    }

    private boolean isNoJobSelection(String value) {
        return List.of("none", "nojob", "no-job").contains(value.toLowerCase());
    }
}
