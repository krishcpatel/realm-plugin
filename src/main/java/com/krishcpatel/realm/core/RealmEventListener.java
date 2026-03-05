package com.krishcpatel.realm.core;


/**
 * Listener callback for a specific {@link RealmEvent} type.
 *
 * <p>Listeners are registered with {@link EventSystem#subscribe(Class, RealmEventListener)}
 * and invoked when events are published.</p>
 *
 * @param <T> the concrete event type handled by this listener
 */
@FunctionalInterface
public interface RealmEventListener<T extends RealmEvent> {
    /**
     * Called when an event of type {@code T} is published.
     *
     * @param event event instance
     */
    void onEvent(T event);
}