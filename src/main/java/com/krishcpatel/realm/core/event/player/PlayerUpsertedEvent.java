package com.krishcpatel.realm.core.event.player;

import com.krishcpatel.realm.core.event.RealmEvent;

import java.util.UUID;

/**
 * Internal event published after a player has been inserted/updated in the database.
 *
 * <p>Modules can listen for this event to initialize player-related state such as
 * wallets, team membership, progression stats, etc.</p>
 */
public class PlayerUpsertedEvent implements RealmEvent {
    private final long createdAt;
    private final UUID uuid;
    private final String username;

    /**
     * Creates a new event instance.
     *
     * @param uuid player UUID
     * @param username current player name
     */
    public PlayerUpsertedEvent(UUID uuid, String username) {
        this.createdAt = System.currentTimeMillis();
        this.uuid = uuid;
        this.username = username;
    }

    @Override
    public long createdAt() {
        return createdAt;
    }

    /**
     * Returns the player UUID.
     *
     * @return player UUID
     */
    public UUID uuid() {
        return uuid;
    }

    /**
     * Returns the player username at time of event.
     *
     * @return player username
     */
    public String username() {
        return username;
    }
}
