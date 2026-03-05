package com.krishcpatel.realm.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight internal event bus for Realm.
 *
 * <p>Used to decouple feature modules from Bukkit listeners. Core code can publish
 * {@link RealmEvent}s and modules can subscribe to event types.</p>
 */
public class EventSystem {
    private final JavaPlugin plugin;
    private final Map<Class<?>, List<RealmEventListener<?>>> listeners = new ConcurrentHashMap<>();

    /**
     * Creates a new event system.
     *
     * @param plugin owning plugin instance (used for async scheduling)
     */
    public EventSystem(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Subscribes a listener to a specific event class.
     *
     * @param type event type to subscribe to
     * @param listener handler called when the event is published
     * @param <T> event type
     */
    public <T extends RealmEvent> void subscribe(Class<T> type, RealmEventListener<T> listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] Subscribed to " + type.getSimpleName());
        }
    }

    /**
     * Publishes an event on the current thread.
     *
     * <p>If any listener touches Bukkit API, this should be called from the main thread.</p>
     *
     * @param event event instance
     * @param <T> event type
     */
    @SuppressWarnings("unchecked")
    public <T extends RealmEvent> void publish(T event) {
        List<RealmEventListener<?>> list = listeners.get(event.getClass());
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] Published " + event.getClass().getSimpleName()
                    + " listeners=" + (list == null ? 0 : list.size()));
        }
        if (list == null) return;
        for (RealmEventListener<?> l : list) {
            ((RealmEventListener<T>) l).onEvent(event);
        }
    }

    /**
     * Publishes an event asynchronously using the server scheduler.
     *
     * <p>Only use for listeners that do not touch Bukkit API (e.g., DB writes).</p>
     *
     * @param event event instance
     * @param <T> event type
     */
    public <T extends RealmEvent> void publishAsync(T event) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<RealmEventListener<?>> list = listeners.get(event.getClass());
            if (list == null) return;

            for (RealmEventListener<?> l : list) {
                ((RealmEventListener<T>) l).onEvent(event);
            }
        });
    }

    /**
     * Removes all registered listeners.
     *
     * <p>Useful during reload if modules are re-wired.</p>
     */
    public void clearAll() {
        listeners.clear();
    }
}
