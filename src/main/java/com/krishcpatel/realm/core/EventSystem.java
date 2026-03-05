package com.krishcpatel.realm.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventSystem {
    private final JavaPlugin plugin;
    private final Map<Class<?>, List<RealmEventListener<?>>> listeners = new ConcurrentHashMap<>();

    public EventSystem(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public <T extends RealmEvent> void subscribe(Class<T> type, RealmEventListener<T> listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] Subscribed to " + type.getSimpleName());
        }
    }

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

    public <T extends RealmEvent> void publishAsync(T event) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<RealmEventListener<?>> list = listeners.get(event.getClass());
            if (list == null) return;

            for (RealmEventListener<?> l : list) {
                ((RealmEventListener<T>) l).onEvent(event);
            }
        });
    }

    public void clearAll() {
        listeners.clear();
    }
}
