/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import dev.nexus.session.protocol.RateLimiter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hard bounds on unauthenticated admission work, applied <em>before</em> any expensive
 * step (before the preamble read even starts, and long before a loopback dial).
 *
 * <p>A connection with no capability still costs a channel and a timeout task, so an
 * attacker doesn't need a valid invite to consume host resources — these limits keep
 * that consumption flat:
 * <ul>
 *   <li><strong>pending cap</strong>: at most {@code maxPending} handshakes in flight
 *       per hosted session, counted separately from admitted guests — thousands of
 *       pre-auth connections cannot bypass {@code maxGuests};</li>
 *   <li><strong>per-source rate</strong>: token bucket per remote address;</li>
 *   <li><strong>global rate</strong>: token bucket across all sources, for spoofed or
 *       distributed floods.</li>
 * </ul>
 *
 * <p>Every acquired slot must be released exactly once — on timeout, on a malformed
 * preamble, on rejection, or on successful admission — which callers get for free by
 * tying {@link #release()} to their tracked operation's completion.
 */
public final class AdmissionGate {

    /** Why an admission attempt was refused, for redacted diagnostics. */
    public enum Refusal {
        PENDING_LIMIT, SOURCE_RATE, GLOBAL_RATE
    }

    private final int maxPending;
    private final RateLimiter perSource;
    private final RateLimiter global;
    private final AtomicInteger pending = new AtomicInteger();

    public AdmissionGate(HostLimits limits) {
        this.maxPending = limits.maxPendingAdmissions();
        this.perSource = new RateLimiter(limits.sourceBurst(), limits.sourceRefillPerSecond(),
                limits.rateLimiterMaxKeys());
        this.global = new RateLimiter(limits.globalBurst(), limits.globalRefillPerSecond(), 1);
    }

    /**
     * Tries to admit one handshake. On {@code null} (accepted), the caller owns one
     * pending slot and must {@link #release()} it exactly once.
     *
     * @return the refusal reason, or {@code null} when accepted
     */
    public Refusal tryAcquire(SocketAddress source, Instant now) {
        if (!global.tryAcquire("global", now)) {
            return Refusal.GLOBAL_RATE;
        }
        if (!perSource.tryAcquire(sourceKey(source), now)) {
            return Refusal.SOURCE_RATE;
        }
        int count = pending.incrementAndGet();
        if (count > maxPending) {
            pending.decrementAndGet();
            return Refusal.PENDING_LIMIT;
        }
        return null;
    }

    /** Returns one pending slot. Must follow every successful {@link #tryAcquire}. */
    public void release() {
        pending.decrementAndGet();
    }

    /** Current in-flight handshake count, for diagnostics and tests. */
    public int pending() {
        return pending.get();
    }

    /** Periodic cleanup of idle rate-limit buckets. */
    public void prune(Instant now) {
        perSource.prune(now);
        global.prune(now);
    }

    private static String sourceKey(SocketAddress source) {
        if (source instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        // Unknown source (in-memory streams, future routes): one shared bucket, so
        // absence of an address can never mean absence of limits.
        return "unknown-source";
    }
}
