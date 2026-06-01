package com.krishcpatel.realm.gui;

import com.krishcpatel.realm.clans.model.ClanRecord;
import com.krishcpatel.realm.clans.repository.ClansRepository;
import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.jobs.manager.JobManager;
import com.krishcpatel.realm.jobs.model.JobCapState;
import com.krishcpatel.realm.jobs.model.JobDefinition;
import com.krishcpatel.realm.jobs.model.PlayerJob;
import com.krishcpatel.realm.jobs.registry.JobDefinitionRegistry;
import com.krishcpatel.realm.jobs.repository.JobsRepository;
import com.krishcpatel.realm.skills.manager.SkillManager;
import com.krishcpatel.realm.skills.model.SkillCategory;
import com.krishcpatel.realm.skills.model.SkillDefinition;
import com.krishcpatel.realm.skills.model.SkillProgress;
import com.krishcpatel.realm.skills.registry.SkillDefinitionRegistry;
import com.krishcpatel.realm.skills.repository.SkillsRepository;
import com.krishcpatel.realm.shop.model.ShopAreaRecord;
import com.krishcpatel.realm.shop.repository.ShopRepository;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Inventory based command hub for Realm.
 *
 * <p>Actions still route through existing command executors, while read-only
 * lists are rendered directly in inventory screens.</p>
 */
public final class GuiModule implements Module, Listener {
    private static final int INVENTORY_WIDTH = 9;
    private static final Material FRAME_SIDE_PANE = Material.GRAY_STAINED_GLASS_PANE;
    private static final Material FRAME_CORNER_PANE = Material.BLACK_STAINED_GLASS_PANE;
    private static final Material PAGE_INFO_MATERIAL = Material.BOOK;
    private static final int[] MAIN_SLOTS = {10, 12, 14, 16, 28, 30, 32};
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Core core;
    private boolean enabled;

    private JobDefinitionRegistry jobRegistry;
    private JobManager jobManager;
    private SkillDefinitionRegistry skillRegistry;
    private SkillManager skillManager;
    private ClansRepository clansRepo;
    private ShopRepository shopRepo;

    /**
     * Creates the GUI module.
     *
     * @param core plugin instance
     */
    public GuiModule(Core core) {
        this.core = core;
    }

    /** {@inheritDoc} */
    @Override
    public void enable() throws SQLException {
        enabled = core.config().getBoolean("modules.gui", true);
        if (!enabled) {
            core.getLogger().info("[gui] module disabled in config.");
            return;
        }

        refreshReadModels();
        core.getServer().getPluginManager().registerEvents(this, core);
        core.getLogger().info("[gui] enabled");
    }

    /** {@inheritDoc} */
    @Override
    public void disable() {
        enabled = false;
        core.getLogger().info("[gui] disabled");
    }

    /** {@inheritDoc} */
    @Override
    public void reload() {
        enabled = core.config().getBoolean("modules.gui", true);
        if (enabled) {
            try {
                refreshReadModels();
            } catch (SQLException e) {
                core.getLogger().severe("[gui] Failed to refresh read models during reload.");
                e.printStackTrace();
            }
        }
        core.getLogger().info(enabled ? "[gui] reloaded" : "[gui] disabled in config.");
    }

    /**
     * Opens the top-level Realm menu.
     *
     * @param player player opening the menu
     */
    public void openMainMenu(Player player) {
        if (!enabled) {
            player.sendMessage(color("&cThe Realm GUI is currently disabled."));
            return;
        }

        Menu menu = createMenu("&8Realm &7- &6Hub", 45, Material.BLACK_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.NETHER_STAR, "&6&lRealm Hub", List.of(
                "&7Manage server features from one place.",
                "&8Available sections depend on permissions."
        ));

        List<MenuButton> buttons = new ArrayList<>();
        if (moduleEnabled("economy", true)) {
            buttons.add(new MenuButton(item(Material.EMERALD, "&a&lEconomy", List.of(
                    "&7Balance, payments, and bank notes.",
                    "",
                    "&eClick to open economy tools."
            )), this::openEconomyMenu));
        }
        if (moduleEnabled("jobs", false) && player.hasPermission("realm.jobs.use")) {
            buttons.add(new MenuButton(item(Material.GOLDEN_PICKAXE, "&e&lJobs", List.of(
                    "&7Browse jobs, view progress, and caps.",
                    "",
                    "&eClick to open jobs."
            )), this::openJobsMenu));
        }
        if (moduleEnabled("skills", false) && player.hasPermission("realm.skills.use")) {
            buttons.add(new MenuButton(item(Material.EXPERIENCE_BOTTLE, "&b&lSkills", List.of(
                    "&7View skills by level and category.",
                    "",
                    "&eClick to open skills."
            )), this::openSkillsMenu));
        }
        if (moduleEnabled("clans", true) && player.hasPermission("realm.clans.use")) {
            buttons.add(new MenuButton(item(Material.WHITE_BANNER, "&f&lClans", List.of(
                    "&7Create, join, and manage clan systems.",
                    "",
                    "&eClick to open clans."
            )), this::openClansMenu));
        }
        if (moduleEnabled("shops", true) && player.hasPermission("realm.shop.use")) {
            buttons.add(new MenuButton(item(Material.CHEST, "&6&lShops", List.of(
                    "&7Claim areas and manage chest stores.",
                    "",
                    "&eClick to open shops."
            )), this::openShopsMenu));
        }
        if (player.hasPermission("realm.admin") || player.hasPermission("realm.economy.ledger")) {
            buttons.add(new MenuButton(item(Material.COMMAND_BLOCK, "&c&lAdmin", List.of(
                    "&7Reload and administrative shortcuts.",
                    "",
                    "&eClick to open admin tools."
            )), this::openAdminMenu));
        }

        int offset = Math.max(0, (MAIN_SLOTS.length - buttons.size()) / 2);
        for (int i = 0; i < buttons.size(); i++) {
            MenuButton button = buttons.get(i);
            set(menu, MAIN_SLOTS[offset + i], button.item(), button.action());
        }

        setClose(menu, 40, "&7Exit the Realm hub.");
        player.openInventory(menu.inventory());
    }

    private void openEconomyMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &aEconomy", 45, Material.GREEN_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.EMERALD_BLOCK, "&a&lEconomy", List.of(
                "&7Bank balance, payments, and bank notes."
        ));

        set(menu, 19, item(Material.GOLD_INGOT, "&eBalance", List.of(
                "&7View your bank balance.",
                "",
                "&eClick to run /balance."
        )), viewer -> runCommand(viewer, "balance"));

        if (core.config().getBoolean("economy.payments.enabled", true)) {
            set(menu, 21, item(Material.PLAYER_HEAD, "&aPay Player", List.of(
                    "&7Choose an online player and amount.",
                    "",
                    "&eClick to browse players."
            )), viewer -> openPayTargetMenu(viewer, 0));
        }

        if (core.config().getBoolean("economy.withdraw.enabled", true)) {
            set(menu, 23, item(Material.PAPER, "&fWithdraw Note", List.of(
                    "&7Choose a bank note amount.",
                    "",
                    "&eClick to pick an amount."
            )), this::openWithdrawMenu);
        }

        if (core.config().getBoolean("economy.redeem.enabled", true)
                && core.config().getBoolean("economy.redeem.command-enabled", true)) {
            set(menu, 25, item(Material.SUNFLOWER, "&6Redeem Held Note", List.of(
                    "&7Redeem the bank note in your hand.",
                    "",
                    "&eClick to run /redeem."
            )), viewer -> runCommand(viewer, "redeem"));
        }

        setBack(menu, 40, this::openMainMenu);
        player.openInventory(menu.inventory());
    }

    private void openWithdrawMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &fWithdraw", 36, Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.PAPER, "&f&lWithdraw Bank Note", List.of(
                "&7Pick a preset amount from your configured limits."
        ));

        List<Long> amounts = presetAmounts(
                core.config().getLong("economy.withdraw.min-amount", 1L),
                core.config().getLong("economy.withdraw.max-amount", 1_000_000L)
        );

        int[] slots = centeredSlots(amounts.size(), 19, 25);
        for (int i = 0; i < amounts.size(); i++) {
            long amount = amounts.get(i);
            set(menu, slots[i], item(Material.PAPER, "&f$" + amount, List.of(
                    "&7Create a bank note for this amount.",
                    "",
                    "&eClick to withdraw."
            )), viewer -> runCommand(viewer, "withdraw " + amount));
        }

        setBack(menu, 31, this::openEconomyMenu);
        player.openInventory(menu.inventory());
    }

    private void openPayTargetMenu(Player player, int page) {
        List<Player> targets = Bukkit.getOnlinePlayers().stream()
                .map(Player.class::cast)
                .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        renderPagedMenu(
                player,
                "&8Realm &7- &aPay Player",
                Material.GREEN_STAINED_GLASS_PANE,
                targets,
                page,
                this::openEconomyMenu,
                target -> {
                    OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(target.getUniqueId());
                    return item(Material.PLAYER_HEAD, "&f" + target.getName(), List.of(
                            "&7Choose an amount to pay this player.",
                            "",
                            "&eClick to select."
                    ), offlineTarget);
                },
                target -> viewer -> openPayAmountMenu(viewer, target.getName()),
                "&7There is nobody online to pay."
        );
    }

    private void openPayAmountMenu(Player player, String targetName) {
        Menu menu = createMenu("&8Realm &7- &aPay " + targetName, 36, Material.GREEN_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.EMERALD, "&a&lPay " + targetName, List.of(
                "&7Choose a preset payment amount."
        ));

        List<Long> amounts = presetAmounts(
                core.config().getLong("economy.payments.min-amount", 1L),
                core.config().getLong("economy.payments.max-amount", 1_000_000L)
        );

        int[] slots = centeredSlots(amounts.size(), 19, 25);
        for (int i = 0; i < amounts.size(); i++) {
            long amount = amounts.get(i);
            set(menu, slots[i], item(Material.EMERALD, "&a$" + amount, List.of(
                    "&7Pay &f" + targetName + " &7this amount.",
                    "",
                    "&eClick to pay."
            )), viewer -> runCommand(viewer, "pay " + targetName + " " + amount));
        }

        setBack(menu, 31, viewer -> openPayTargetMenu(viewer, 0));
        player.openInventory(menu.inventory());
    }

    private void openJobsMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &eJobs", 45, Material.YELLOW_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.GOLDEN_PICKAXE, "&e&lJobs", List.of(
                "&7Browse configured jobs and your progress."
        ));

        set(menu, 19, item(Material.BOOK, "&eJob Browser", List.of(
                "&7View every configured job in this GUI.",
                "",
                "&eClick to browse jobs."
        )), viewer -> openJobBrowser(viewer, 0));
        set(menu, 21, item(Material.WRITABLE_BOOK, "&eYour Jobs", List.of(
                "&7View your active jobs and levels.",
                "",
                "&eClick to view stats."
        )), viewer -> openJobStats(viewer, 0));
        set(menu, 23, item(Material.CLOCK, "&eDaily Caps", List.of(
                "&7View remaining money and XP caps.",
                "",
                "&eClick to view caps."
        )), viewer -> openJobCaps(viewer, 0));
        set(menu, 25, item(Material.ANVIL, "&aJoin Job", List.of(
                "&7Choose a configured job to join.",
                "",
                "&eClick to pick a job."
        )), viewer -> openJobPicker(viewer, "join", 0));

        if (core.jobsConfig().getBoolean("settings.allow-no-job", true)) {
            set(menu, 30, item(Material.RED_BED, "&cLeave Job", List.of(
                    "&7Choose a job to leave.",
                    "",
                    "&eClick to pick a job."
            )), viewer -> openJobPicker(viewer, "leave", 0));
            set(menu, 32, item(Material.BARRIER, "&cLeave All Jobs", List.of(
                    "&7Clear your active jobs.",
                    "",
                    "&eClick to leave all jobs."
            )), viewer -> runCommand(viewer, "job leave none"));
        }

        setBack(menu, 40, this::openMainMenu);
        player.openInventory(menu.inventory());
    }

    private void openJobBrowser(Player player, int page) {
        List<JobDefinition> jobs = sortedJobs();
        renderPagedMenu(
                player,
                "&8Realm &7- &eJob Browser",
                Material.YELLOW_STAINED_GLASS_PANE,
                jobs,
                page,
                this::openJobsMenu,
                job -> item(jobMaterial(job.id()), "&e" + job.displayName(), List.of(
                        "&7" + job.description(),
                        "",
                        "&6Money cap: &f" + formatCap(job.dailyMoneyCap()),
                        "&bXP cap: &f" + formatCap(job.dailyXpCap()),
                        "&8ID: " + job.id(),
                        "",
                        "&eClick to join this job."
                )),
                job -> viewer -> runCommand(viewer, "job join " + job.id()),
                "&7No jobs are currently configured."
        );
    }

    private void openJobPicker(Player player, String action, int page) {
        List<JobDefinition> jobs = sortedJobs();
        renderPagedMenu(
                player,
                action.equals("join") ? "&8Realm &7- &aJoin Job" : "&8Realm &7- &cLeave Job",
                action.equals("join") ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                jobs,
                page,
                this::openJobsMenu,
                job -> item(jobMaterial(job.id()), (action.equals("join") ? "&a" : "&c") + job.displayName(), List.of(
                        "&7" + job.description(),
                        "&8ID: " + job.id(),
                        "",
                        "&eClick to " + action + "."
                )),
                job -> viewer -> runCommand(viewer, "job " + action + " " + job.id()),
                "&7No jobs are currently configured."
        );
    }

    private void openJobStats(Player player, int page) {
        if (!jobsReady()) {
            openMessageMenu(player, "&8Realm &7- &eYour Jobs", Material.YELLOW_STAINED_GLASS_PANE,
                    "&cJobs are not available.", this::openJobsMenu);
            return;
        }

        openLoadingMenu(player, "&8Realm &7- &eYour Jobs", Material.YELLOW_STAINED_GLASS_PANE);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                List<PlayerJob> jobs = jobManager.getJobs(player.getUniqueId());
                core.getServer().getScheduler().runTask(core, () -> renderJobStats(player, jobs, page));
            } catch (Exception e) {
                core.getLogger().severe("[gui] Failed to load job stats for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        openMessageMenu(player, "&8Realm &7- &eYour Jobs", Material.YELLOW_STAINED_GLASS_PANE,
                                "&cFailed to load job stats.", this::openJobsMenu)
                );
            }
        });
    }

    private void renderJobStats(Player player, List<PlayerJob> jobs, int page) {
        renderPagedMenu(
                player,
                "&8Realm &7- &eYour Jobs",
                Material.YELLOW_STAINED_GLASS_PANE,
                jobs.stream().sorted(Comparator.comparing(job -> jobDisplayName(job.jobId()))).toList(),
                page,
                this::openJobsMenu,
                job -> {
                    JobDefinition definition = jobRegistry.get(job.jobId()).orElse(null);
                    String name = definition == null ? job.jobId() : definition.displayName();
                    long required = definition == null ? 0L : definition.leveling().requiredXpForLevel(job.level());
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Level: &f" + job.level());
                    lore.add("&7XP: &f" + job.xp() + (required > 0 ? "&7/&f" + required : ""));
                    lore.add("&7Total XP: &f" + job.totalXp());
                    lore.add("");
                    lore.add("&eClick to leave this job.");
                    return item(jobMaterial(job.jobId()), "&e" + name, lore);
                },
                job -> viewer -> runCommand(viewer, "job leave " + job.jobId()),
                "&7You do not currently have any jobs."
        );
    }

    private void openJobCaps(Player player, int page) {
        if (!jobsReady()) {
            openMessageMenu(player, "&8Realm &7- &eDaily Caps", Material.YELLOW_STAINED_GLASS_PANE,
                    "&cJobs are not available.", this::openJobsMenu);
            return;
        }

        openLoadingMenu(player, "&8Realm &7- &eDaily Caps", Material.YELLOW_STAINED_GLASS_PANE);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                long dayKey = LocalDate.now(ZoneOffset.UTC).toEpochDay();
                List<JobCapLine> lines = new ArrayList<>();
                for (PlayerJob job : jobManager.getJobs(player.getUniqueId())) {
                    JobDefinition definition = jobRegistry.get(job.jobId()).orElse(null);
                    JobCapState capState = jobManager.getDailyCapState(player.getUniqueId(), job.jobId(), dayKey);
                    lines.add(new JobCapLine(
                            job.jobId(),
                            definition == null ? job.jobId() : definition.displayName(),
                            capState.moneyEarned(),
                            definition == null ? 0L : definition.dailyMoneyCap(),
                            capState.xpEarned(),
                            definition == null ? 0L : definition.dailyXpCap()
                    ));
                }
                core.getServer().getScheduler().runTask(core, () -> renderJobCaps(player, lines, page));
            } catch (Exception e) {
                core.getLogger().severe("[gui] Failed to load job caps for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        openMessageMenu(player, "&8Realm &7- &eDaily Caps", Material.YELLOW_STAINED_GLASS_PANE,
                                "&cFailed to load job caps.", this::openJobsMenu)
                );
            }
        });
    }

    private void renderJobCaps(Player player, List<JobCapLine> lines, int page) {
        renderPagedMenu(
                player,
                "&8Realm &7- &eDaily Caps",
                Material.YELLOW_STAINED_GLASS_PANE,
                lines.stream().sorted(Comparator.comparing(JobCapLine::displayName, String.CASE_INSENSITIVE_ORDER)).toList(),
                page,
                this::openJobsMenu,
                line -> item(jobMaterial(line.jobId()), "&e" + line.displayName(), List.of(
                        "&6Money: &f" + line.moneyEarned() + "&7/&f" + formatCap(line.moneyCap()),
                        "&6Money left: &f" + formatRemaining(line.moneyEarned(), line.moneyCap()),
                        "&bXP: &f" + line.xpEarned() + "&7/&f" + formatCap(line.xpCap()),
                        "&bXP left: &f" + formatRemaining(line.xpEarned(), line.xpCap())
                )),
                line -> viewer -> {
                },
                "&7You do not currently have any jobs."
        );
    }

    private void openSkillsMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &bSkills", 45, Material.CYAN_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.EXPERIENCE_BOTTLE, "&b&lSkills", List.of(
                "&7Browse levels, XP, and skill categories."
        ));

        set(menu, 19, item(Material.EXPERIENCE_BOTTLE, "&bAll Skills", List.of(
                "&7View every configured skill in this GUI.",
                "",
                "&eClick to browse all skills."
        )), viewer -> openSkillList(viewer, definition -> true, "&8Realm &7- &bAll Skills", 0));
        set(menu, 21, item(Material.IRON_SWORD, "&cCombat", List.of(
                "&7View combat skills.",
                "",
                "&eClick to browse combat."
        )), viewer -> openSkillList(viewer, definition -> definition.category() == SkillCategory.COMBAT, "&8Realm &7- &cCombat Skills", 0));
        set(menu, 23, item(Material.IRON_PICKAXE, "&aGather", List.of(
                "&7View gathering skills.",
                "",
                "&eClick to browse gathering."
        )), viewer -> openSkillList(viewer, definition -> definition.category() == SkillCategory.GATHER, "&8Realm &7- &aGather Skills", 0));
        set(menu, 25, item(Material.CRAFTING_TABLE, "&eOther", List.of(
                "&7View utility skills.",
                "",
                "&eClick to browse utility."
        )), viewer -> openSkillList(viewer, definition -> definition.category() == SkillCategory.OTHER, "&8Realm &7- &eOther Skills", 0));

        setBack(menu, 40, this::openMainMenu);
        player.openInventory(menu.inventory());
    }

    private void openSkillList(Player player, Predicate<SkillDefinition> filter, String title, int page) {
        if (!skillsReady()) {
            openMessageMenu(player, title, Material.CYAN_STAINED_GLASS_PANE,
                    "&cSkills are not available.", this::openSkillsMenu);
            return;
        }

        openLoadingMenu(player, title, Material.CYAN_STAINED_GLASS_PANE);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                Map<String, SkillProgress> progressBySkill = skillManager.getSkills(player.getUniqueId())
                        .stream()
                        .collect(Collectors.toMap(SkillProgress::skillId, it -> it));
                List<SkillDefinition> definitions = sortedSkills().stream()
                        .filter(filter)
                        .toList();
                int startingLevel = Math.max(1, core.skillsConfig().getInt("settings.starting-level", 1));

                core.getServer().getScheduler().runTask(core, () ->
                        renderSkillList(player, title, definitions, progressBySkill, startingLevel, page, filter)
                );
            } catch (Exception e) {
                core.getLogger().severe("[gui] Failed to load skills for " + player.getName());
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        openMessageMenu(player, title, Material.CYAN_STAINED_GLASS_PANE,
                                "&cFailed to load skills.", this::openSkillsMenu)
                );
            }
        });
    }

    private void renderSkillList(
            Player player,
            String title,
            List<SkillDefinition> definitions,
            Map<String, SkillProgress> progressBySkill,
            int startingLevel,
            int page,
            Predicate<SkillDefinition> filter
    ) {
        renderPagedMenu(
                player,
                title,
                Material.CYAN_STAINED_GLASS_PANE,
                definitions,
                page,
                this::openSkillsMenu,
                definition -> {
                    SkillProgress progress = progressBySkill.getOrDefault(
                            definition.id(),
                            new SkillProgress(definition.id(), startingLevel, 0L, 0L, 0L)
                    );
                    long required = definition.progression().requiredXpForLevel(progress.level());
                    return item(skillMaterial(definition.category()), "&b" + definition.displayName(), List.of(
                            "&7Category: &f" + definition.category().displayName(),
                            "&7Level: &f" + progress.level(),
                            "&7XP: &f" + progress.xp() + "&7/&f" + required,
                            "&7Total XP: &f" + progress.totalXp(),
                            "&8ID: " + definition.id()
                    ));
                },
                definition -> viewer -> {
                },
                "&7No skills matched this view.",
                nextPage -> viewer -> openSkillList(viewer, filter, title, nextPage)
        );
    }

    private void openClansMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &fClans", 45, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.WHITE_BANNER, "&f&lClans", List.of(
                "&7Manage clan membership, flags, upgrades, and storage."
        ));

        set(menu, 19, item(Material.BOOK, "&fMy Clan Info", List.of(
                "&7View details for your current clan.",
                "",
                "&eClick to run /clan info."
        )), viewer -> runCommand(viewer, "clan info"));
        set(menu, 21, item(Material.COMPASS, "&bBrowse Clans", List.of(
                "&7See all existing clans and join one.",
                "",
                "&eClick to browse."
        )), viewer -> openClanBrowseMenu(viewer, 0));
        set(menu, 23, item(Material.WHITE_BANNER, "&fSet Clan Flag", List.of(
                "&7Hold your banner pattern in hand first.",
                "",
                "&eClick to run /clan flag set."
        )), viewer -> runCommand(viewer, "clan flag set"));
        set(menu, 25, item(Material.NETHER_STAR, "&6Upgrade Clan", List.of(
                "&7Pay the upgrade fee and increase limits.",
                "",
                "&eClick to run /clan upgrade."
        )), viewer -> runCommand(viewer, "clan upgrade"));
        set(menu, 30, item(Material.CHEST, "&eClan Bank", List.of(
                "&7Check your clan bank and balances.",
                "",
                "&eClick to run /clan bank."
        )), viewer -> runCommand(viewer, "clan bank"));
        set(menu, 32, item(Material.BARREL, "&aProtect Looked Chest", List.of(
                "&7Look at a chest or barrel first.",
                "",
                "&eClick to run /clan protect add."
        )), viewer -> runCommand(viewer, "clan protect add"));
        set(menu, 34, item(Material.REDSTONE_BLOCK, "&cUnprotect Looked Chest", List.of(
                "&7Look at a protected chest first.",
                "",
                "&eClick to run /clan protect remove."
        )), viewer -> runCommand(viewer, "clan protect remove"));
        set(menu, 39, item(Material.BARRIER, "&cLeave Clan", List.of(
                "&7Leave your current clan.",
                "",
                "&eClick to run /clan leave."
        )), viewer -> runCommand(viewer, "clan leave"));

        setBack(menu, 40, this::openMainMenu);
        player.openInventory(menu.inventory());
    }

    private void openClanBrowseMenu(Player player, int page) {
        if (clansRepo == null) {
            openMessageMenu(player, "&8Realm &7- &fClans", Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    "&cClans are not available.", this::openClansMenu);
            return;
        }

        openLoadingMenu(player, "&8Realm &7- &fClans", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                List<ClanRecord> clans = clansRepo.listClans();
                core.getServer().getScheduler().runTask(core, () -> renderPagedMenu(
                        player,
                        "&8Realm &7- &fBrowse Clans",
                        Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                        clans,
                        page,
                        this::openClansMenu,
                        clan -> item(Material.WHITE_BANNER, "&f[" + clan.tag() + "] " + clan.name(), List.of(
                                "&7Level: &f" + clan.level(),
                                "&7Members: &f" + clan.memberCap(),
                                "&7Bank: &a$" + clan.bankBalance(),
                                "",
                                "&eClick to join this clan."
                        )),
                        clan -> viewer -> runCommand(viewer, "clan join " + clan.tag()),
                        "&7No clans have been created yet."
                ));
            } catch (Exception e) {
                core.getLogger().severe("[gui] Failed to load clans.");
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        openMessageMenu(player, "&8Realm &7- &fClans", Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                                "&cFailed to load clans.", this::openClansMenu)
                );
            }
        });
    }

    public void openShopsMenu(Player player) {
        if (!enabled) {
            player.sendMessage(color("&cThe Realm GUI is currently disabled."));
            return;
        }
        if (!moduleEnabled("shops", true)) {
            player.sendMessage(color("&cThe shops module is currently disabled."));
            return;
        }

        Menu menu = createMenu("&8Realm &7- &6Shops", 45, Material.ORANGE_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.CHEST, "&6&lShops", List.of(
                "&7Claim areas, manage listings, and pay upkeep."
        ));

        set(menu, 19, item(Material.MAP, "&eBrowse Areas", List.of(
                "&7View claimable and claimed shop areas.",
                "",
                "&eClick to browse."
        )), viewer -> openShopAreasMenu(viewer, 0, false));
        set(menu, 21, item(Material.CHEST_MINECART, "&6My Areas", List.of(
                "&7View your currently owned shop areas.",
                "",
                "&eClick to manage."
        )), viewer -> openShopAreasMenu(viewer, 0, true));
        set(menu, 23, item(Material.ANVIL, "&aSetup Looked Chest", List.of(
                "&7Look at your shop chest first.",
                "&7Then configure prices per slot in a menu.",
                "",
                "&eClick to run /shop setup."
        )), viewer -> runCommand(viewer, "shop setup"));
        set(menu, 25, item(Material.BOOK, "&fView Looked Chest Listings", List.of(
                "&7Look at a shop chest first.",
                "",
                "&eClick to run /shop listings."
        )), viewer -> runCommand(viewer, "shop listings"));
        set(menu, 30, item(Material.EMERALD, "&aQuick Claim Here", List.of(
                "&7Claim the shop area you are standing in.",
                "",
                "&eClick to run /shop claim."
        )), viewer -> runCommand(viewer, "shop claim"));
        set(menu, 32, item(Material.CLOCK, "&6Pay Upkeep", List.of(
                "&7If you own one area, this pays it directly.",
                "&7If you own multiple, pick one from My Areas.",
                "",
                "&eClick to run /shop payupkeep."
        )), viewer -> runCommand(viewer, "shop payupkeep"));

        if (player.hasPermission("realm.shop.admin")) {
            set(menu, 34, item(Material.COMMAND_BLOCK, "&cShop Admin", List.of(
                    "&7Set area points and create/remove zones.",
                    "",
                    "&eClick to open admin tools."
            )), this::openShopAdminMenu);
        }

        setBack(menu, 40, this::openMainMenu);
        player.openInventory(menu.inventory());
    }

    private void openShopAreasMenu(Player player, int page, boolean ownedOnly) {
        if (shopRepo == null) {
            openMessageMenu(player, "&8Realm &7- &6Shops", Material.ORANGE_STAINED_GLASS_PANE,
                    "&cShops are not available.", this::openShopsMenu);
            return;
        }

        openLoadingMenu(player, ownedOnly ? "&8Realm &7- &6My Areas" : "&8Realm &7- &eShop Areas",
                Material.ORANGE_STAINED_GLASS_PANE);
        core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
            try {
                List<ShopAreaRecord> areas = ownedOnly
                        ? shopRepo.listAreasByOwner(player.getUniqueId().toString())
                        : shopRepo.listAreas();
                core.getServer().getScheduler().runTask(core, () -> renderPagedMenu(
                        player,
                        ownedOnly ? "&8Realm &7- &6My Areas" : "&8Realm &7- &eShop Areas",
                        Material.ORANGE_STAINED_GLASS_PANE,
                        areas,
                        page,
                        this::openShopsMenu,
                        area -> item(shopAreaMaterial(area, player), "&f" + area.areaKey(), List.of(
                                "&7Claim Fee: &a$" + area.claimFee(),
                                "&7Upkeep: &6$" + area.upkeepFee(),
                                "&7Status: " + shopAreaStatus(area, player),
                                area.isClaimed() ? "&7Owner: &f" + areaOwnerName(area) : "&7Owner: &aNone",
                                "",
                                shopAreaClickHint(area, player)
                        )),
                        area -> viewer -> handleShopAreaClick(viewer, area, ownedOnly),
                        ownedOnly ? "&7You do not own any shop areas." : "&7No shop areas are configured."
                ));
            } catch (Exception e) {
                core.getLogger().severe("[gui] Failed to load shop areas.");
                e.printStackTrace();
                core.getServer().getScheduler().runTask(core, () ->
                        openMessageMenu(player, "&8Realm &7- &6Shops", Material.ORANGE_STAINED_GLASS_PANE,
                                "&cFailed to load shop areas.", this::openShopsMenu)
                );
            }
        });
    }

    private void handleShopAreaClick(Player viewer, ShopAreaRecord area, boolean ownedOnlySource) {
        String viewerUuid = viewer.getUniqueId().toString();
        if (!area.isClaimed()) {
            runCommand(viewer, "shop claim " + area.areaKey());
            return;
        }
        if (viewerUuid.equals(area.ownerUuid())) {
            openShopAreaActions(viewer, area, ownedOnlySource);
            return;
        }
        viewer.sendMessage(color("&cThis area is already claimed by " + areaOwnerName(area) + "."));
    }

    private void openShopAreaActions(Player player, ShopAreaRecord area, boolean ownedOnlySource) {
        Menu menu = createMenu("&8Realm &7- &6Area: " + area.areaKey(), 36, Material.ORANGE_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.CHEST, "&6&l" + area.areaKey(), List.of(
                "&7Claim Fee: &a$" + area.claimFee(),
                "&7Upkeep Fee: &6$" + area.upkeepFee(),
                "&7Owner: &f" + areaOwnerName(area)
        ));

        String viewerUuid = player.getUniqueId().toString();
        if (!area.isClaimed()) {
            set(menu, 20, item(Material.EMERALD, "&aClaim Area", List.of(
                    "&7Claim this shop area now.",
                    "",
                    "&eClick to claim."
            )), viewer -> runCommand(viewer, "shop claim " + area.areaKey()));
        } else if (viewerUuid.equals(area.ownerUuid())) {
            set(menu, 20, item(Material.CLOCK, "&6Pay Upkeep", List.of(
                    "&7Pay upkeep for this area now.",
                    "",
                    "&eClick to pay."
            )), viewer -> runCommand(viewer, "shop payupkeep " + area.areaKey()));
            set(menu, 24, item(Material.ANVIL, "&aSetup Looked Chest", List.of(
                    "&7Look at a chest in this area first.",
                    "",
                    "&eClick to setup listings."
            )), viewer -> runCommand(viewer, "shop setup"));
        }

        if (player.hasPermission("realm.shop.admin")) {
            set(menu, 30, item(Material.BARRIER, "&cAdmin Remove Area", List.of(
                    "&7Removes this area definition.",
                    "",
                    "&eClick to run /shop admin remove."
            )), viewer -> runCommand(viewer, "shop admin remove " + area.areaKey()));
        }

        setBack(menu, 31, viewer -> openShopAreasMenu(viewer, 0, ownedOnlySource));
        player.openInventory(menu.inventory());
    }

    private void openShopAdminMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &cShop Admin", 45, Material.RED_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.COMMAND_BLOCK, "&c&lShop Admin", List.of(
                "&7Pick two X/Z points.",
                "&7Y range is applied automatically from world min to max."
        ));

        set(menu, 19, item(Material.LIME_DYE, "&aSet Pos1", List.of(
                "&7Store your current X/Z as point 1.",
                "&7Y uses world top height.",
                "",
                "&eClick to run /shop admin pos1."
        )), viewer -> runCommand(viewer, "shop admin pos1"));
        set(menu, 21, item(Material.YELLOW_DYE, "&eSet Pos2", List.of(
                "&7Store your current X/Z as point 2.",
                "&7Y uses world minimum height.",
                "",
                "&eClick to run /shop admin pos2."
        )), viewer -> runCommand(viewer, "shop admin pos2"));
        set(menu, 24, item(Material.EMERALD_BLOCK, "&aCreate Auto (Low)", List.of(
                "&7Auto-name using saved pos1/pos2.",
                "&7Claim fee: $1000, upkeep: $100",
                "",
                "&eClick to create."
        )), viewer -> runCommand(viewer, "shop admin createauto 1000 100"));
        set(menu, 25, item(Material.EMERALD_BLOCK, "&aCreate Auto (Mid)", List.of(
                "&7Auto-name using saved pos1/pos2.",
                "&7Claim fee: $5000, upkeep: $300",
                "",
                "&eClick to create."
        )), viewer -> runCommand(viewer, "shop admin createauto 5000 300"));
        set(menu, 26, item(Material.EMERALD_BLOCK, "&aCreate Auto (High)", List.of(
                "&7Auto-name using saved pos1/pos2.",
                "&7Claim fee: $15000, upkeep: $750",
                "",
                "&eClick to create."
        )), viewer -> runCommand(viewer, "shop admin createauto 15000 750"));

        setBack(menu, 40, this::openShopsMenu);
        player.openInventory(menu.inventory());
    }

    private void openAdminMenu(Player player) {
        Menu menu = createMenu("&8Realm &7- &cAdmin", 36, Material.RED_STAINED_GLASS_PANE);
        setInfo(menu, 4, Material.COMMAND_BLOCK, "&c&lAdmin", List.of(
                "&7Administrative Realm shortcuts."
        ));

        if (player.hasPermission("realm.admin")) {
            set(menu, 20, item(Material.REDSTONE, "&cReload Realm", List.of(
                    "&7Reload configuration and modules.",
                    "",
                    "&eClick to run /realm reload."
            )), viewer -> runCommand(viewer, "realm reload"));
        }

        if (moduleEnabled("economy", true) && player.hasPermission("realm.economy.ledger")) {
            set(menu, 24, item(Material.MAP, "&eYour Ledger", List.of(
                    "&7View your recent ledger entries.",
                    "",
                    "&eClick to run /ledger."
            )), viewer -> runCommand(viewer, "ledger " + viewer.getName() + " 10"));
        }

        setBack(menu, 31, this::openMainMenu);
        player.openInventory(menu.inventory());
    }

    @EventHandler
    private void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getView().getTopInventory().getHolder() instanceof RealmGuiHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        GuiClick action = holder.actions().get(event.getRawSlot());
        if (action != null) {
            action.click(player);
        }
    }

    @EventHandler
    private void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RealmGuiHolder)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void refreshReadModels() throws SQLException {
        if (moduleEnabled("jobs", false)) {
            JobsRepository jobsRepo = new JobsRepository(core.getDatabase());
            jobsRepo.initSchema();
            jobRegistry = new JobDefinitionRegistry(core);
            jobRegistry.reload();
            jobManager = new JobManager(core, jobsRepo, jobRegistry);
        } else {
            jobRegistry = null;
            jobManager = null;
        }

        if (moduleEnabled("skills", false)) {
            SkillsRepository skillsRepo = new SkillsRepository(core.getDatabase());
            skillsRepo.initSchema();
            skillRegistry = new SkillDefinitionRegistry(core);
            skillRegistry.reload();
            skillManager = new SkillManager(skillsRepo);
        } else {
            skillRegistry = null;
            skillManager = null;
        }

        if (moduleEnabled("clans", true)) {
            clansRepo = new ClansRepository(core.getDatabase());
            clansRepo.initSchema();
        } else {
            clansRepo = null;
        }

        if (moduleEnabled("shops", true)) {
            shopRepo = new ShopRepository(core.getDatabase());
            shopRepo.initSchema();
        } else {
            shopRepo = null;
        }
    }

    private <T> void renderPagedMenu(
            Player player,
            String title,
            Material border,
            List<T> entries,
            int page,
            GuiClick backAction,
            ItemRenderer<T> renderer,
            ActionFactory<T> actionFactory,
            String emptyMessage
    ) {
        renderPagedMenu(player, title, border, entries, page, backAction, renderer, actionFactory, emptyMessage,
                nextPage -> viewer -> renderPagedMenu(
                        viewer,
                        title,
                        border,
                        entries,
                        nextPage,
                        backAction,
                        renderer,
                        actionFactory,
                        emptyMessage
                ));
    }

    private <T> void renderPagedMenu(
            Player player,
            String title,
            Material border,
            List<T> entries,
            int page,
            GuiClick backAction,
            ItemRenderer<T> renderer,
            ActionFactory<T> actionFactory,
            String emptyMessage,
            PageActionFactory pageActionFactory
    ) {
        Menu menu = createMenu(title, 54, border);
        int safePage = Math.max(0, page);
        int pageSize = CONTENT_SLOTS.length;
        int start = safePage * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);

        if (entries.isEmpty()) {
            setInfo(menu, 22, Material.GRAY_DYE, "&7Nothing to show", List.of(emptyMessage));
        } else if (start >= entries.size()) {
            pageActionFactory.create(0).click(player);
            return;
        } else {
            for (int i = start; i < end; i++) {
                T entry = entries.get(i);
                set(menu, CONTENT_SLOTS[i - start], renderer.render(entry), actionFactory.create(entry));
            }
        }

        setBack(menu, 45, backAction);
        setClose(menu, 49, "&7Close this menu.");
        setInfo(menu, 53, PAGE_INFO_MATERIAL, "&ePage " + (safePage + 1) + "&7/&e" + totalPages, List.of(
                "&7Entries: &f" + entries.size()
        ));
        if (safePage > 0) {
            set(menu, 48, item(Material.ARROW, "&ePrevious Page", List.of("&7Page " + safePage)), pageActionFactory.create(safePage - 1));
        } else {
            setInfo(menu, 48, Material.GRAY_DYE, "&8Previous Page", List.of("&7Already on first page."));
        }
        if (end < entries.size()) {
            set(menu, 50, item(Material.ARROW, "&eNext Page", List.of("&7Page " + (safePage + 2))), pageActionFactory.create(safePage + 1));
        } else {
            setInfo(menu, 50, Material.GRAY_DYE, "&8Next Page", List.of("&7Already on last page."));
        }

        player.openInventory(menu.inventory());
    }

    private void openLoadingMenu(Player player, String title, Material border) {
        Menu menu = createMenu(title, 27, border);
        setInfo(menu, 13, Material.CLOCK, "&eLoading...", List.of("&7Fetching your latest Realm data."));
        player.openInventory(menu.inventory());
    }

    private void openMessageMenu(Player player, String title, Material border, String message, GuiClick backAction) {
        Menu menu = createMenu(title, 27, border);
        setInfo(menu, 13, Material.GRAY_DYE, "&7Notice", List.of(message));
        setBack(menu, 22, backAction);
        player.openInventory(menu.inventory());
    }

    private void runCommand(Player player, String command) {
        core.getServer().getScheduler().runTask(core, () -> player.performCommand(command));
    }

    private void setBack(Menu menu, int slot, GuiClick action) {
        set(menu, slot, item(Material.ARROW, "&eBack", List.of("&7Return to the previous menu.")), action);
    }

    private void setClose(Menu menu, int slot, String line) {
        set(menu, slot, item(Material.IRON_DOOR, "&cClose", List.of(line)), Player::closeInventory);
    }

    private void setInfo(Menu menu, int slot, Material material, String name, List<String> lore) {
        menu.inventory().setItem(slot, item(material, name, lore));
    }

    private void set(Menu menu, int slot, ItemStack item, GuiClick action) {
        menu.inventory().setItem(slot, item);
        menu.holder().actions().put(slot, action);
    }

    private Menu createMenu(String title, int size, Material border) {
        RealmGuiHolder holder = new RealmGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, color(title));
        holder.setInventory(inventory);
        fillFrame(inventory, border);
        return new Menu(inventory, holder);
    }

    private void fillFrame(Inventory inventory, Material border) {
        ItemStack topBottom = item(border, " ", List.of());
        ItemStack side = item(FRAME_SIDE_PANE, " ", List.of());
        ItemStack corner = item(FRAME_CORNER_PANE, " ", List.of());
        int rows = inventory.getSize() / INVENTORY_WIDTH;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / INVENTORY_WIDTH;
            int col = slot % INVENTORY_WIDTH;
            if (row == 0 || row == rows - 1 || col == 0 || col == INVENTORY_WIDTH - 1) {
                boolean topOrBottom = row == 0 || row == rows - 1;
                boolean sideColumn = col == 0 || col == INVENTORY_WIDTH - 1;
                if (topOrBottom && sideColumn) {
                    inventory.setItem(slot, corner);
                } else if (topOrBottom) {
                    inventory.setItem(slot, topBottom);
                } else {
                    inventory.setItem(slot, side);
                }
            }
        }
        if (inventory.getSize() >= 27) {
            inventory.setItem(4, item(border, " ", List.of()));
            inventory.setItem(inventory.getSize() - 5, item(border, " ", List.of()));
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, lore, null);
    }

    private ItemStack item(Material material, String name, List<String> lore, OfflinePlayer owner) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        if (owner != null && meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(owner);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private List<Long> presetAmounts(long min, long max) {
        long low = Math.max(1L, Math.min(min, max));
        long high = Math.max(min, max);
        Set<Long> amounts = new LinkedHashSet<>();
        amounts.add(low);
        amounts.add(10L);
        amounts.add(100L);
        amounts.add(1_000L);
        amounts.add(10_000L);

        return amounts.stream()
                .filter(amount -> amount >= low && amount <= high)
                .sorted()
                .toList();
    }

    private int[] centeredSlots(int count, int minSlot, int maxSlot) {
        int cappedCount = Math.max(0, Math.min(count, maxSlot - minSlot + 1));
        int[] slots = new int[cappedCount];
        int start = minSlot + ((maxSlot - minSlot + 1 - cappedCount) / 2);
        for (int i = 0; i < cappedCount; i++) {
            slots[i] = start + i;
        }
        return slots;
    }

    private List<JobDefinition> sortedJobs() {
        if (jobRegistry == null) {
            return List.of();
        }
        return jobRegistry.all().stream()
                .sorted(Comparator.comparing(JobDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<SkillDefinition> sortedSkills() {
        if (skillRegistry == null) {
            return List.of();
        }
        return skillRegistry.all().stream()
                .sorted(Comparator.comparing(SkillDefinition::category)
                        .thenComparing(SkillDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Material jobMaterial(String jobId) {
        String id = jobId == null ? "" : jobId.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "brewer" -> Material.BREWING_STAND;
            case "builder" -> Material.BRICKS;
            case "crafter" -> Material.CRAFTING_TABLE;
            case "digger" -> Material.IRON_SHOVEL;
            case "enchanter" -> Material.ENCHANTING_TABLE;
            case "explorer" -> Material.COMPASS;
            case "farmer" -> Material.WHEAT;
            case "fisherman" -> Material.FISHING_ROD;
            case "hunter" -> Material.BOW;
            case "miner" -> Material.DIAMOND_PICKAXE;
            case "weaponsmith" -> Material.ANVIL;
            case "woodcutter" -> Material.IRON_AXE;
            default -> Material.GOLDEN_PICKAXE;
        };
    }

    private Material skillMaterial(SkillCategory category) {
        if (category == null) {
            return Material.CRAFTING_TABLE;
        }
        return switch (category) {
            case COMBAT -> Material.IRON_SWORD;
            case GATHER -> Material.IRON_PICKAXE;
            case OTHER -> Material.CRAFTING_TABLE;
        };
    }

    private String jobDisplayName(String jobId) {
        if (jobRegistry == null) {
            return jobId;
        }
        return jobRegistry.get(jobId).map(JobDefinition::displayName).orElse(jobId);
    }

    private boolean jobsReady() {
        return jobManager != null && jobRegistry != null;
    }

    private boolean skillsReady() {
        return skillManager != null && skillRegistry != null;
    }

    private boolean moduleEnabled(String moduleName, boolean defaultValue) {
        return core.config().getBoolean("modules." + moduleName, defaultValue);
    }

    private Material shopAreaMaterial(ShopAreaRecord area, Player viewer) {
        if (area == null) {
            return Material.BARRIER;
        }
        if (!area.isClaimed()) {
            return Material.LIME_WOOL;
        }
        if (viewer.getUniqueId().toString().equals(area.ownerUuid())) {
            return Material.CHEST;
        }
        return Material.RED_WOOL;
    }

    private String shopAreaStatus(ShopAreaRecord area, Player viewer) {
        if (area == null) {
            return "&cunknown";
        }
        if (!area.isClaimed()) {
            return "&aunclaimed";
        }
        if (viewer.getUniqueId().toString().equals(area.ownerUuid())) {
            return "&eowned by you";
        }
        return "&cclaimed";
    }

    private String shopAreaClickHint(ShopAreaRecord area, Player viewer) {
        if (area == null) {
            return "&7";
        }
        if (!area.isClaimed()) {
            return "&eClick to claim this area.";
        }
        if (viewer.getUniqueId().toString().equals(area.ownerUuid())) {
            return "&eClick to open area actions.";
        }
        return "&7Already claimed by another player.";
    }

    private String areaOwnerName(ShopAreaRecord area) {
        if (area == null || area.ownerUuid() == null || area.ownerUuid().isBlank()) {
            return "None";
        }
        try {
            UUID uuid = UUID.fromString(area.ownerUuid());
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            return offlinePlayer.getName() == null ? area.ownerUuid() : offlinePlayer.getName();
        } catch (IllegalArgumentException ignored) {
            return area.ownerUuid();
        }
    }

    private String formatCap(long cap) {
        return cap <= 0L ? "unlimited" : String.valueOf(cap);
    }

    private String formatRemaining(long earned, long cap) {
        if (cap <= 0L) {
            return "unlimited";
        }
        return String.valueOf(Math.max(0L, cap - Math.max(0L, earned)));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private interface GuiClick {
        void click(Player player);
    }

    private interface ItemRenderer<T> {
        ItemStack render(T entry);
    }

    private interface ActionFactory<T> {
        GuiClick create(T entry);
    }

    private interface PageActionFactory {
        GuiClick create(int page);
    }

    private record Menu(Inventory inventory, RealmGuiHolder holder) {
    }

    private record MenuButton(ItemStack item, GuiClick action) {
    }

    private record JobCapLine(
            String jobId,
            String displayName,
            long moneyEarned,
            long moneyCap,
            long xpEarned,
            long xpCap
    ) {
    }

    private static final class RealmGuiHolder implements InventoryHolder {
        private final Map<Integer, GuiClick> actions = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private Map<Integer, GuiClick> actions() {
            return actions;
        }
    }
}
