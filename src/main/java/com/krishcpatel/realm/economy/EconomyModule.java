package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.core.event.player.PlayerUpsertedEvent;
import com.krishcpatel.realm.economy.command.*;
import com.krishcpatel.realm.economy.event.LedgerRecordedEvent;
import com.krishcpatel.realm.economy.listener.BankNoteInteractListener;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.repository.BankNoteRepository;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;

import java.sql.SQLException;

/**
 * Economy module bootstrapper.
 *
 * <p>Wires economy components (repository/manager), registers listeners/commands,
 * and subscribes to core events when needed.</p>
 */
public class EconomyModule implements Module {
    private final Core core;

    private EconomyRepository economyRepo;
    private LedgerRepository ledgerRepo;
    private TransactionManager tx;
    private BankNoteRepository bankNoteRepo;
    private BankNoteManager bankNoteManager;

    /**
     * Creates the economy module for the given core plugin.
     *
     * @param core core plugin instance
     */
    public EconomyModule(Core core) {
        this.core = core;
    }

    @Override
    public void enable() throws SQLException {
        // create tables / migrations
        economyRepo = new EconomyRepository(core.getDatabase());
        economyRepo.initSchema();

        ledgerRepo = new LedgerRepository(core.getDatabase());
        ledgerRepo.initSchema();

        bankNoteRepo = new BankNoteRepository(core.getDatabase());
        bankNoteRepo.initSchema();

        tx = new TransactionManager(core, core.getDatabase(), economyRepo, ledgerRepo);
        bankNoteManager = new BankNoteManager(core, core.getDatabase(), economyRepo, ledgerRepo, bankNoteRepo);

        core.getServer().getPluginManager().registerEvents(
                new BankNoteInteractListener(core, bankNoteManager),
                core
        );

        if (!core.config().getBoolean("modules.economy", true)) {
            core.getLogger().info("[economy] module disabled in config.");
            return;
        }

        core.getCommand("balance").setExecutor(new BalanceCommand(core, economyRepo));
        core.getCommand("pay").setExecutor(new PayCommand(core, tx));
        core.getCommand("eco").setExecutor(new EconomyAdminCommand(core, tx));
        core.getCommand("ledger").setExecutor(new LedgerCommand(core, ledgerRepo));
        core.getCommand("withdraw").setExecutor(new WithdrawCommand(core, bankNoteManager));
        core.getCommand("redeem").setExecutor(new RedeemCommand(core, bankNoteManager));

        core.getLogger().info("[economy] enabled");

        registerEventSubscriptions();
    }

    private void registerEventSubscriptions() {
        core.events().subscribe(PlayerUpsertedEvent.class, evt -> {
            try {
                economyRepo.ensureAccount(evt.uuid().toString());
            } catch (Exception e) {
                core.getLogger().severe("[economy] Failed to ensure account for " + evt.username());
                e.printStackTrace();
            }
        });

        core.events().subscribe(LedgerRecordedEvent.class, evt -> {

            // 1) basic audit log
            core.debug("[ledger] #" + evt.ledgerId()
                    + " " + evt.type()
                    + " $" + evt.amount()
                    + " src=" + evt.source()
                    + " from=" + evt.fromUuid()
                    + " to=" + evt.toUuid()
                    + " actor=" + evt.actor());

            // 2) simple “large tx” alert (config-driven later)
            long threshold = core.config().getLong("economy.ledger.large-transaction-threshold", 50000L);
            boolean logLarge = core.config().getBoolean("economy.ledger.log-large-transactions", true);

            if (logLarge && evt.amount() >= threshold) {
                core.getLogger().warning("[economy] LARGE TX #" + evt.ledgerId()
                        + " $" + evt.amount()
                        + " type=" + evt.type()
                        + " source=" + evt.source());
            }

            // 3) sanity checks (these help catch bugs fast)
            if (evt.amount() <= 0) {
                core.getLogger().severe("[economy] BAD LEDGER AMOUNT: #" + evt.ledgerId() + " amount=" + evt.amount());
            }

            if ("TRANSFER".equalsIgnoreCase(evt.type())) {
                if (evt.fromUuid() == null || evt.toUuid() == null) {
                    core.getLogger().severe("[economy] BAD TRANSFER LEDGER: #" + evt.ledgerId() + " missing from/to");
                }
            }
        });
    }

    @Override
    public void disable() {
        core.logger.info("[economy] disabled");
    }

    @Override
    public void reload() {
        registerEventSubscriptions();
        core.logger.info("[economy] reloaded");
    }
}
