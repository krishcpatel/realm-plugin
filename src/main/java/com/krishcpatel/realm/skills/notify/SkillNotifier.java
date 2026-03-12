package com.krishcpatel.realm.skills.notify;

import com.krishcpatel.realm.core.Core;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sends player-facing notifications for skill xp and level-up milestones.
 */
public final class SkillNotifier {
    private final Core core;

    /**
     * Creates the skills notifier.
     *
     * @param core plugin instance
     */
    public SkillNotifier(Core core) {
        this.core = core;
    }

    /**
     * Sends a lightweight alert each time skill xp is earned.
     *
     * @param playerUuid player UUID
     * @param skillName display name of the skill
     * @param gainedXp gained xp amount
     * @param level current level
     * @param currentXp current progress xp
     * @param requiredXp required xp for next level
     */
    public void notifyXpGain(UUID playerUuid, String skillName, long gainedXp, int level, long currentXp, long requiredXp) {
        if (!core.skillsConfig().getBoolean("settings.notifications.enabled", true)) {
            return;
        }

        Bukkit.getScheduler().runTask(core, () -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            if (core.skillsConfig().getBoolean("settings.notifications.xp-action-bar-enabled", true)) {
                String raw = core.messages().getString("skills.xp-action-bar", "&b+%xp% %skill% XP");
                raw = raw.replace("%skill%", skillName)
                        .replace("%xp%", String.valueOf(gainedXp))
                        .replace("%level%", String.valueOf(level))
                        .replace("%current%", String.valueOf(currentXp))
                        .replace("%required%", String.valueOf(requiredXp));
                player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
            }

            if (core.skillsConfig().getBoolean("settings.notifications.xp-sound-enabled", true)) {
                playSound(
                        player,
                        core.skillsConfig().getString("settings.notifications.xp-sound", "ENTITY_EXPERIENCE_ORB_PICKUP"),
                        (float) core.skillsConfig().getDouble("settings.notifications.xp-sound-volume", 0.2D),
                        (float) core.skillsConfig().getDouble("settings.notifications.xp-sound-pitch", 1.4D),
                        "[skills] Invalid skills xp sound: "
                );
            }
        });
    }

    /**
     * Sends a stronger level-up alert with chat, title, and sound.
     *
     * @param playerUuid player UUID
     * @param skillName display name of the skill
     * @param level new level
     */
    public void notifyLevelUp(UUID playerUuid, String skillName, int level) {
        if (!core.skillsConfig().getBoolean("settings.notifications.enabled", true)) {
            return;
        }

        Bukkit.getScheduler().runTask(core, () -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            player.sendMessage(core.msg("skills.level-up", Map.of(
                    "%skill%", skillName,
                    "%level%", String.valueOf(level)
            )));

            if (core.skillsConfig().getBoolean("settings.notifications.levelup-action-bar-enabled", true)) {
                String raw = core.messages().getString("skills.level-up-action-bar", "&6Level Up! &b%skill% &7-> &f%level%");
                raw = raw.replace("%skill%", skillName).replace("%level%", String.valueOf(level));
                player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
            }

            if (core.skillsConfig().getBoolean("settings.notifications.levelup-sound-enabled", true)) {
                playSound(
                        player,
                        core.skillsConfig().getString("settings.notifications.levelup-sound", "UI_TOAST_CHALLENGE_COMPLETE"),
                        (float) core.skillsConfig().getDouble("settings.notifications.levelup-sound-volume", 0.8D),
                        (float) core.skillsConfig().getDouble("settings.notifications.levelup-sound-pitch", 1.05D),
                        "[skills] Invalid skills level-up sound: "
                );
            }
        });
    }

    private void playSound(Player player, String soundName, float volume, float pitch, String invalidPrefix) {
        Sound sound = resolveSound(soundName);
        if (sound == null) {
            core.getLogger().warning(invalidPrefix + soundName);
            return;
        }
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
}
