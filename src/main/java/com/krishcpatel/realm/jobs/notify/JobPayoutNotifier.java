package com.krishcpatel.realm.jobs.notify;

import com.krishcpatel.realm.core.Core;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates short-lived job payouts into a single satisfying action-bar pulse.
 */
public final class JobPayoutNotifier {
    private final Core core;
    private final Map<UUID, PendingPayout> pending = new ConcurrentHashMap<>();

    /**
     * Creates the jobs payout notifier.
     *
     * @param core plugin instance
     */
    public JobPayoutNotifier(Core core) {
        this.core = core;
    }

    /**
     * Queues a payout notification for the player.
     *
     * @param playerUuid player uuid
     * @param jobName display name of the paying job
     * @param money awarded money
     * @param xp awarded xp
     */
    public void notifyPayout(UUID playerUuid, String jobName, long money, long xp) {
        if (!core.jobsConfig().getBoolean("settings.notifications.enabled", true)) {
            return;
        }
        if (money <= 0 && xp <= 0) {
            return;
        }

        boolean[] schedule = {false};
        pending.compute(playerUuid, (uuid, existing) -> {
            if (existing == null) {
                existing = new PendingPayout();
                schedule[0] = true;
            }
            existing.money += money;
            existing.xp += xp;
            existing.events++;
            existing.jobs.add(jobName);
            return existing;
        });

        if (!schedule[0]) {
            return;
        }

        long windowMs = Math.max(150L, core.jobsConfig().getLong("settings.notifications.aggregate-window-ms", 900L));
        long ticks = Math.max(1L, Math.round(windowMs / 50.0D));
        core.getServer().getScheduler().runTaskLater(core, () -> flush(playerUuid), ticks);
    }

    private void flush(UUID playerUuid) {
        PendingPayout payout = pending.remove(playerUuid);
        if (payout == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        String rewards = formatRewardParts(payout.money, payout.xp);
        String messageKey = payout.events <= 1 && payout.jobs.size() == 1
                ? "jobs.payout-action-bar"
                : "jobs.payout-summary-action-bar";
        String jobName = payout.jobs.isEmpty() ? "Jobs" : payout.jobs.iterator().next();

        String raw = core.messages().getString(messageKey, "&6Jobs &8• &f%rewards%");
        raw = raw.replace("%job%", jobName)
                .replace("%money%", String.valueOf(payout.money))
                .replace("%xp%", String.valueOf(payout.xp))
                .replace("%rewards%", rewards)
                .replace("%count%", String.valueOf(payout.events))
                .replace("%jobs%", String.valueOf(payout.jobs.size()));

        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
        playSound(player);
    }

    private String formatRewardParts(long money, long xp) {
        if (money > 0 && xp > 0) {
            return "&a+$" + money + " &8• &b+" + xp + " xp";
        }
        if (money > 0) {
            return "&a+$" + money;
        }
        return "&b+" + xp + " xp";
    }

    private void playSound(Player player) {
        if (!core.jobsConfig().getBoolean("settings.notifications.sound-enabled", true)) {
            return;
        }

        String soundName = core.jobsConfig().getString("settings.notifications.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        Sound sound = resolveSound(soundName);
        if (sound == null) {
            core.getLogger().warning("[jobs] Invalid jobs notification sound: " + soundName);
            return;
        }

        float volume = (float) core.jobsConfig().getDouble("settings.notifications.sound-volume", 0.35D);
        float pitch = (float) core.jobsConfig().getDouble("settings.notifications.sound-pitch", 1.35D);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private Sound resolveSound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim();
        String lowercase = normalized.toLowerCase(Locale.ROOT);

        if (Key.parseable(lowercase)) {
            Sound direct = Registry.SOUNDS.get(Key.key(lowercase));
            if (direct != null) {
                return direct;
            }

            if (!lowercase.contains(":")) {
                direct = Registry.SOUNDS.get(Key.key(Key.MINECRAFT_NAMESPACE, lowercase));
                if (direct != null) {
                    return direct;
                }
            }
        }

        return Registry.SOUNDS.stream()
                .filter(sound -> {
                    String key = Registry.SOUNDS.getKeyOrThrow(sound).getKey();
                    String namespacedKey = Registry.SOUNDS.getKeyOrThrow(sound).toString();
                    String legacyName = key.toUpperCase(Locale.ROOT).replace('.', '_');
                    return normalized.equalsIgnoreCase(namespacedKey)
                            || normalized.equalsIgnoreCase(key)
                            || normalized.equalsIgnoreCase(legacyName);
                })
                .findFirst()
                .orElse(null);
    }

    private static final class PendingPayout {
        private long money;
        private long xp;
        private int events;
        private final Set<String> jobs = new LinkedHashSet<>();
    }
}
