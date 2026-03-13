package com.krishcpatel.realm.core.guard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived cross-module cache for placed-block anti-farm observations.
 *
 * <p>Jobs and skills both consult the same database guard table. When one module
 * observes and clears a guard, the other module still needs a brief window to
 * see that the block was player-placed and suppress rewards as well.</p>
 */
public final class PlacedBlockGuardObservationCache {
    private static final long OBSERVATION_TTL_MS = 10_000L;
    private static final Map<String, Long> OBSERVED = new ConcurrentHashMap<>();

    private PlacedBlockGuardObservationCache() {
    }

    public static void markObserved(String world, int x, int y, int z) {
        OBSERVED.put(key(world, x, y, z), System.currentTimeMillis());
    }

    public static boolean wasRecentlyObserved(String world, int x, int y, int z) {
        String key = key(world, x, y, z);
        Long observedAt = OBSERVED.get(key);
        if (observedAt == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - observedAt <= OBSERVATION_TTL_MS) {
            return true;
        }

        OBSERVED.remove(key, observedAt);
        return false;
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
