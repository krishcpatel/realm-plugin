package com.krishcpatel.realm.economy;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.Module;
import com.krishcpatel.realm.core.PlayerUpsertedEvent;

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
    private EconomyManager manager;

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

        manager = new EconomyManager(economyRepo);
        tx = new TransactionManager(core, core.getDatabase(), economyRepo, ledgerRepo);

        core.getCommand("balance").setExecutor(new BalanceCommand(core, economyRepo));
        core.getCommand("pay").setExecutor(new PayCommand(core, tx));
        core.getCommand("eco").setExecutor(new EconomyAdminCommand(core, tx));
        core.getCommand("ledger").setExecutor(new LedgerCommand(core, ledgerRepo));

        core.getLogger().info("[economy] enabled");

        // subscribe to events
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
            long largeThreshold = core.config().getLong("economy.large-transaction-threshold", 50_000);
            if (evt.amount() >= largeThreshold) {
                core.getLogger().warning("[economy] LARGE TX #" + evt.ledgerId()
                        + " $" + evt.amount()
                        + " type=" + evt.type()
                        + " src=" + evt.source()
                        + " actor=" + evt.actor());
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

        core.logger.info("[economy] enabled");
    }

    @Override
    public void disable() {
        core.logger.info("[economy] disabled");
    }

    @Override
    public void reload() {
        core.logger.info("[economy] reloaded");
    }
}
