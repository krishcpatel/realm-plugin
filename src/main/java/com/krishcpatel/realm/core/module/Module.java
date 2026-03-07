package com.krishcpatel.realm.core.module;

import java.sql.SQLException;

/**
 * Represents a feature module that can be enabled/disabled/reloaded by the core plugin.
 *
 * <p>Modules are used to keep large systems (economy, teams, jobs, etc.) isolated
 * while sharing common services from core (database, config, event bus).</p>
 */
public interface Module {
    /**
     * Enables the module and registers any required resources (commands, listeners, tasks).
     *
     * @throws SQLException if module initialization requires database access and it fails
     */
    void enable() throws SQLException;
    /** Disables the module and releases any resources registered in {@link #enable()}. */
    void disable();
    /** Reloads module configuration/state without restarting the server. */
    void reload();
}
