package com.jainam.jredis.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Remembers which keys have a time-to-live and when each one expires.
 *
 * <p>Active expiry needs to pick <em>random</em> keys that have a TTL (see
 * {@link Database#activeExpireCycle}). A HashMap cannot give you a random entry quickly, so the
 * keys are also kept in an ArrayList, with a second map from key to its position in that list.
 * That gives O(1) for every operation:
 * <ul>
 *   <li>add: append to the list and record the position</li>
 *   <li>remove: move the last element into the hole ("swap-remove"), then shrink the list</li>
 *   <li>random pick: choose a random index</li>
 * </ul>
 */
final class ExpiryIndex {
    private final Map<String, Long> deadlines = new HashMap<>();
    private final List<String> keys = new ArrayList<>();
    private final Map<String, Integer> positions = new HashMap<>();

    /** Absolute expiry time in epoch milliseconds, or {@code null} if the key has no TTL. */
    Long get(String key) {
        return deadlines.get(key);
    }

    void put(String key, long deadlineMillis) {
        if (deadlines.put(key, deadlineMillis) == null) {
            positions.put(key, keys.size());
            keys.add(key);
        }
    }

    boolean remove(String key) {
        if (deadlines.remove(key) == null) {
            return false;
        }
        int pos = positions.remove(key);
        int lastIndex = keys.size() - 1;
        String last = keys.remove(lastIndex);
        if (pos != lastIndex) {
            keys.set(pos, last);
            positions.put(last, pos);
        }
        return true;
    }

    int size() {
        return keys.size();
    }

    String randomKey(RandomGenerator random) {
        return keys.get(random.nextInt(keys.size()));
    }

    void clear() {
        deadlines.clear();
        keys.clear();
        positions.clear();
    }
}
