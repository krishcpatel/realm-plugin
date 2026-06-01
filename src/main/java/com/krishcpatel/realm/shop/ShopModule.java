package com.krishcpatel.realm.shop;

import com.krishcpatel.realm.core.Core;
import com.krishcpatel.realm.core.module.Module;
import com.krishcpatel.realm.economy.manager.BankNoteManager;
import com.krishcpatel.realm.economy.manager.TransactionManager;
import com.krishcpatel.realm.economy.payment.NotePaymentMenuService;
import com.krishcpatel.realm.economy.repository.BankNoteRepository;
import com.krishcpatel.realm.economy.repository.EconomyRepository;
import com.krishcpatel.realm.economy.repository.LedgerRepository;
import com.krishcpatel.realm.shop.command.ShopCommand;
import com.krishcpatel.realm.shop.gui.ShopSetupMenuService;
import com.krishcpatel.realm.shop.listener.ShopListener;
import com.krishcpatel.realm.shop.repository.ShopRepository;
import com.krishcpatel.realm.shop.service.ShopService;
import org.bukkit.ChatColor;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;

/**
 * Bootstrapper for the shop module.
 */
public final class ShopModule implements Module {
    private final Core core;
    private ShopService shops;
    private BukkitTask upkeepTask;
    private NotePaymentMenuService paymentMenu;
    private ShopSetupMenuService setupMenu;
    private ShopListener shopListener;
    private ShopCommand shopCommand;

    /**
     * Creates a shop module.
     *
     * @param core plugin core
     */
    public ShopModule(Core core) {
        this.core = core;
    }

    @Override
    public void enable() throws SQLException {
        if (!core.config().getBoolean("modules.shops", true)) {
            core.getLogger().info("[shop] module disabled in config.");
            bindDisabledCommandExecutor();
            return;
        }
        if (shops != null) {
            return;
        }

        ShopRepository repo = new ShopRepository(core.getDatabase());
        repo.initSchema();

        EconomyRepository economy = new EconomyRepository(core.getDatabase());
        LedgerRepository ledger = new LedgerRepository(core.getDatabase());
        BankNoteRepository notesRepo = new BankNoteRepository(core.getDatabase());
        economy.initSchema();
        ledger.initSchema();
        notesRepo.initSchema();

        TransactionManager tx = new TransactionManager(core, core.getDatabase(), economy, ledger);
        BankNoteManager notes = new BankNoteManager(core, core.getDatabase(), economy, ledger, notesRepo);
        paymentMenu = new NotePaymentMenuService(core, notes);

        shops = new ShopService(core, core.getDatabase(), repo, tx);
        setupMenu = new ShopSetupMenuService(core, shops);
        shopListener = new ShopListener(core, shops, paymentMenu, setupMenu);
        shopCommand = new ShopCommand(core, shops, paymentMenu, setupMenu, tx);

        core.getServer().getPluginManager().registerEvents(paymentMenu, core);
        core.getServer().getPluginManager().registerEvents(setupMenu, core);
        core.getServer().getPluginManager().registerEvents(shopListener, core);
        if (core.getCommand("shop") != null) {
            core.getCommand("shop").setExecutor(shopCommand);
        }

        long tickMinutes = Math.max(1L, core.config().getLong("shops.upkeep.tick-minutes", 5L));
        long tickInterval = tickMinutes * 60L * 20L;
        upkeepTask = core.getServer().getScheduler().runTaskTimerAsynchronously(
                core,
                () -> {
                    if (shops != null) {
                        shops.runUpkeepTick();
                    }
                },
                tickInterval,
                tickInterval
        );

        core.getLogger().info("[shop] enabled");
    }

    @Override
    public void disable() {
        if (upkeepTask != null) {
            upkeepTask.cancel();
            upkeepTask = null;
        }
        if (paymentMenu != null) {
            HandlerList.unregisterAll(paymentMenu);
            paymentMenu = null;
        }
        if (setupMenu != null) {
            HandlerList.unregisterAll(setupMenu);
            setupMenu = null;
        }
        if (shopListener != null) {
            HandlerList.unregisterAll(shopListener);
            shopListener = null;
        }
        shopCommand = null;
        shops = null;
        bindDisabledCommandExecutor();
        core.getLogger().info("[shop] disabled");
    }

    @Override
    public void reload() {
        boolean enabledInConfig = core.config().getBoolean("modules.shops", true);
        if (!enabledInConfig) {
            if (shops != null) {
                disable();
            } else {
                bindDisabledCommandExecutor();
            }
            return;
        }

        if (shops == null) {
            try {
                enable();
            } catch (SQLException e) {
                core.getLogger().severe("[shop] Failed to enable module during reload.");
                e.printStackTrace();
            }
            return;
        }

        core.getLogger().info("[shop] reloaded");
    }

    private void bindDisabledCommandExecutor() {
        if (core.getCommand("shop") == null) {
            return;
        }
        core.getCommand("shop").setExecutor((sender, command, label, args) -> {
            sender.sendMessage(ChatColor.RED + "Shops are disabled.");
            sender.sendMessage(ChatColor.GRAY
                    + "Set modules.shops: true in plugins/realm/config.yml then run /realm reload.");
            return true;
        });
    }
}
