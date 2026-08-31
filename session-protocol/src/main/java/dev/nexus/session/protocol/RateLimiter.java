/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key token buckets with hard-bounded storage, shared by the backend (per-address,
 * per-operation-class) and the host's admission gate (per-source).
 *
 * <p>Properties:
 * <ul>
 *   <li><strong>bounded</strong>: at most {@code maxKeys} buckets. At capacity, a
 *       request for an unknown key is refused (fail-closed) rather than evicting a
 *       tracked key — eviction would hand attackers fresh full buckets on demand;</li>
 *   <li><strong>concurrency-safe</strong>: acquisition is serialized per bucket, and
 *       an acquire racing a prune re-checks that its bucket is still the mapped one,
 *       so a pruned-then-recreated bucket cannot double-spend;</li>
 *   <li><strong>self-cleaning</strong>: buckets idle long enough to be full again are
 *       pruned, so memory tracks recently active keys.</li>
 * </ul>
 */
public final class RateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens;
        long lastRefillEpochMilli;

        Bucket(int capacity, Instant now) {
            this.tokens = capacity;
            this.lastRefillEpochMilli = now.toEpochMilli();
        }
    }

    public RateLimiter(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, 10_000);
    }

    public RateLimiter(int capacity, double refillPerSecond, int maxKeys) {
        if (capacity < 1 || refillPerSecond <= 0 || maxKeys < 1) {
            throw new IllegalArgumentException("invalid rate limiter configuration");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxKeys = maxKeys;
    }

    /** Takes one token for {@code key}; {@code false} means: reject the request. */
    public boolean tryAcquire(String key, Instant now) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                if (buckets.size() >= maxKeys) {
                    return false;
                }
                bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
            }
            synchronized (bucket) {
                // A prune may have removed this bucket between get and lock; spending
                // from an orphan would let a recreated bucket double-spend. Retry once
                // against the live mapping.
                if (buckets.get(key) != bucket) {
                    continue;
                }
                long nowMilli = now.toEpochMilli();
                double refilled =
                        (nowMilli - bucket.lastRefillEpochMilli) / 1000.0 * refillPerSecond;
                bucket.tokens = Math.min(capacity, bucket.tokens + Math.max(0, refilled));
                bucket.lastRefillEpochMilli = nowMilli;
                if (bucket.tokens < 1.0) {
                    return false;
                }
                bucket.tokens -= 1.0;
                return true;
            }
        }
        // Lost the race twice in a row; refuse rather than over-admit.
        return false;
    }

    /** Drops buckets idle long enough to be full again; called periodically. */
    public void prune(Instant now) {
        long idleMillis = (long) (capacity / refillPerSecond * 1000) + 60_000;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Bucket bucket = it.next().getValue();
            synchronized (bucket) {
                if (now.toEpochMilli() - bucket.lastRefillEpochMilli > idleMillis) {
                    it.remove();
                }
            }
        }
    }

    /** Tracked key count, for tests and diagnostics. */
    public int size() {
        return buckets.size();
    }
}
