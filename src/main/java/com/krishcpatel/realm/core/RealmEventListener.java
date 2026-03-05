package com.krishcpatel.realm.core;

@FunctionalInterface
public interface RealmEventListener<T extends RealmEvent> {
    void onEvent(T event);
}