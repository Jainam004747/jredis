package com.jainam.jredis.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The keyspace: one big hash map from key to value, plus the expiry index.
 *
 * <p>A value is one of five Java types, matching the five Redis types:
 * <pre>
 *   string → String          list → {@link RList}       hash → HashMap&lt;String,String&gt;
 *   set    → HashSet&lt;String&gt;   zset → {@link ZSet}
 * </pre>
 *
 * <p><b>Not thread-safe on purpose.</b> In the default event-loop mode only one thread ever
 * touches it, so it needs no locks at all; that is the same design choice Redis makes. The
 * threaded server wraps every call in one global lock instead.
 *
 * <p><b>Expiry</b> happens two ways, like Redis:
 * <ul>
 *   <li><em>lazily</em>: every read checks the key's deadline and deletes it if passed, so an
 *       expired key is never returned;</li>
 *   <li><em>actively</em>: {@link #activeExpireCycle} runs about 10 times a second and samples
 *       random keys with a TTL, so expired keys nobody reads still free their memory.</li>
 * </ul>
 */
public final class Database {
    private final Map<String, Object> data = new HashMap<>();
    private final ExpiryIndex expires = new ExpiryIndex();
    private final LongSupplier clock;
    private final RandomGenerator random;
    private long expiredKeys;

    public Database() {
        this(System::currentTimeMillis, RandomGenerator.getDefault());
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public Database(LongSupplier clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    public long now() {
        return clock.getAsLong();
    }

    // ---- generic access ----

    /** Returns the live value for a key, deleting it first if it has expired. */
    public Object get(String key) {
        expireIfNeeded(key);
        return data.get(key);
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    /** Stores a value and clears any TTL, like SET does. */
    public void put(String key, Object value) {
        data.put(key, value);
        expires.remove(key);
    }

    /** Stores a value but keeps an existing TTL (used when a command modifies a value in place). */
    public void putKeepTtl(String key, Object value) {
        data.put(key, value);
    }

    public boolean delete(String key) {
        expires.remove(key);
        return data.remove(key) != null;
    }

    public int size() {
        return data.size();
    }

    public void clear() {
        data.clear();
        expires.clear();
    }

    /** All live keys. Expired keys found on the way are deleted. */
    public List<String> keys() {
        List<String> out = new ArrayList<>(data.size());
        for (String key : new ArrayList<>(data.keySet())) {
            if (!expireIfNeeded(key)) {
                out.add(key);
            }
        }
        return out;
    }

    public String type(String key) {
        Object v = get(key);
        return switch (v) {
            case null -> "none";
            case String s -> "string";
            case RList l -> "list";
            case HashMap<?, ?> h -> "hash";
            case HashSet<?> s -> "set";
            case ZSet z -> "zset";
            default -> throw new IllegalStateException("unknown value type " + v.getClass());
        };
    }

    // ---- typed access: throw WrongTypeException if the key holds another type ----

    public String getString(String key) {
        return typed(key, String.class);
    }

    public RList getList(String key, boolean create) {
        RList v = typed(key, RList.class);
        if (v == null && create) {
            v = new RList();
            data.put(key, v);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public HashMap<String, String> getHash(String key, boolean create) {
        HashMap<String, String> v = typed(key, HashMap.class);
        if (v == null && create) {
            v = new HashMap<>();
            data.put(key, v);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public HashSet<String> getSet(String key, boolean create) {
        HashSet<String> v = typed(key, HashSet.class);
        if (v == null && create) {
            v = new HashSet<>();
            data.put(key, v);
        }
        return v;
    }

    public ZSet getZSet(String key, boolean create) {
        ZSet v = typed(key, ZSet.class);
        if (v == null && create) {
            v = new ZSet();
            data.put(key, v);
        }
        return v;
    }

    /** Deletes the key if its collection became empty (Redis never keeps empty collections). */
    public void deleteIfEmpty(String key) {
        Object v = data.get(key);
        boolean empty = switch (v) {
            case RList l -> l.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            case Set<?> s -> s.isEmpty();
            case ZSet z -> z.size() == 0;
            case null, default -> false;
        };
        if (empty) {
            delete(key);
        }
    }

    private <T> T typed(String key, Class<T> type) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        if (!type.isInstance(v)) {
            throw new WrongTypeException();
        }
        return type.cast(v);
    }

    // ---- expiry ----

    /** Sets an absolute deadline. Returns false if the key does not exist. */
    public boolean expireAt(String key, long deadlineMillis) {
        if (!exists(key)) {
            return false;
        }
        if (deadlineMillis <= now()) {
            delete(key); // a deadline in the past deletes immediately, like Redis
        } else {
            expires.put(key, deadlineMillis);
        }
        return true;
    }

    /** Absolute deadline in millis, or -1 if the key has no TTL (caller checks existence). */
    public long deadline(String key) {
        Long d = expires.get(key);
        return d == null ? -1 : d;
    }

    public boolean persist(String key) {
        return exists(key) && expires.remove(key);
    }

    public int keysWithTtl() {
        return expires.size();
    }

    public long expiredKeys() {
        return expiredKeys;
    }

    /** Deletes the key if its deadline has passed. Returns true if it was deleted. */
    private boolean expireIfNeeded(String key) {
        Long deadline = expires.get(key);
        if (deadline == null || deadline > now()) {
            return false;
        }
        expires.remove(key);
        data.remove(key);
        expiredKeys++;
        return true;
    }

    /**
     * Active expiry, the same algorithm Redis uses: sample 20 random keys that have a TTL and
     * delete the expired ones. If more than a quarter were expired, there are probably many
     * more, so repeat, but stop after a small time budget so the server never stalls.
     *
     * @return number of keys deleted
     */
    public int activeExpireCycle() {
        final int sampleSize = 20;
        final long budgetNanos = 1_000_000; // 1 ms
        long startNanos = System.nanoTime();
        int deleted = 0;
        while (expires.size() > 0) {
            int expiredInSample = 0;
            int n = Math.min(sampleSize, expires.size());
            for (int i = 0; i < n && expires.size() > 0; i++) {
                if (expireIfNeeded(expires.randomKey(random))) {
                    expiredInSample++;
                }
            }
            deleted += expiredInSample;
            if (expiredInSample <= n / 4 || System.nanoTime() - startNanos > budgetNanos) {
                break;
            }
        }
        return deleted;
    }
}
