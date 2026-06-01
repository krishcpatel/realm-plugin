package com.krishcpatel.realm.clans;

import com.krishcpatel.realm.clans.command.ClanCommand;
import com.krishcpatel.realm.clans.listener.ClansListener;
import com.krishcpatel.realm.clans.repository.ClansRepository;
import com.krishcpatel.realm.clans.service.ClansService;
import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.payment.NotePaymentMenuService;
import com.krishcpatel.realm.economy.repository.BankNoteRepository;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;

/**
 * Bootstrapper for the clans module.
 */
public final class ClansModule implements Module {
    private final Core core;

    private ClansService clans;
    private BukkitTask billingTask;

    /**
     * Creates the clans module.
     *
     * @param core plugin core
     */
    public ClansModule(Core core) {
        this.core = core;
    }

    @Override
    public void enable() throws SQLException {
        if (!core.config().getBoolean("modules.clans", true)) {
            core.getLogger().info("[clans] module disabled in config.");
            return;
        }

        ClansRepository repo = new ClansRepository(core.getDatabase());
        repo.initSchema();

        EconomyRepository economy = new EconomyRepository(core.getDatabase());
        LedgerRepository ledger = new LedgerRepository(core.getDatabase());
        BankNoteRepository notesRepo = new BankNoteRepository(core.getDatabase());
        economy.initSchema();
        ledger.initSchema();
        notesRepo.initSchema();

        BankNoteManager notes = new BankNoteManager(core, core.getDatabase(), economy, ledger, notesRepo);
        TransactionManager tx = new TransactionManager(core, core.getDatabase(), economy, ledger);
        NotePaymentMenuService paymentMenu = new NotePaymentMenuService(core, notes);

        clans = new ClansService(core, core.getDatabase(), repo, economy, ledger);
        clans.refreshCaches();

        core.getServer().getPluginManager().registerEvents(paymentMenu, core);
        core.getServer().getPluginManager().registerEvents(new ClansListener(core, clans), core);
        if (core.getCommand("clan") != null) {
            core.getCommand("clan").setExecutor(new ClanCommand(core, clans, paymentMenu, tx));
        }

        long intervalMinutes = Math.max(1L, core.config().getLong("clans.billing.tick-minutes", 5L));
        long intervalTicks = intervalMinutes * 60L * 20L;
        billingTask = core.getServer().getScheduler().runTaskTimerAsynchronously(
                core,
                () -> {
                    if (clans != null) {
                        clans.runScheduledBillingTick();
                    }
                },
                intervalTicks,
                intervalTicks
        );

        core.getLogger().info("[clans] enabled");
    }

    @Override
    public void disable() {
        if (billingTask != null) {
            billingTask.cancel();
            billingTask = null;
        }
        core.getLogger().info("[clans] disabled");
    }

    @Override
    public void reload() {
        if (!core.config().getBoolean("modules.clans", true)) {
            return;
        }
        if (clans != null) {
            try {
                clans.refreshCaches();
            } catch (SQLException e) {
                core.getLogger().severe("[clans] Failed to refresh caches during reload.");
                e.printStackTrace();
            }
        }
        core.getLogger().info("[clans] reloaded");
    }
}
