package com.krishcpatel.realm.core.event;

/**
 * Base interface for all internal Realm events.
 *
 * <p>Realm events are published through {@link EventSystem} to decouple feature modules
 * (economy/teams/etc.) from Bukkit listeners.</p>
 */
public interface RealmEvent {
    /**
     * Timestamp when the event instance was created.
     *
     * @return epoch milliseconds
     */
    long createdAt();
}