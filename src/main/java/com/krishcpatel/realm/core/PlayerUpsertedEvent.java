package com.krishcpatel.realm.core;

import java.util.UUID;

public class PlayerUpsertedEvent implements RealmEvent {
    private final long createdAt;
    private final UUID uuid;
    private final String username;

    public PlayerUpsertedEvent(UUID uuid, String username) {
        this.createdAt = System.currentTimeMillis();
        this.uuid = uuid;
        this.username = username;
    }

    @Override
    public long createdAt() {
        return createdAt;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }
}
