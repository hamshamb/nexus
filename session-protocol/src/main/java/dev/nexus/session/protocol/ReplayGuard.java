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
 * Makes admission capabilities single-use: the host records each capability id the
 * first time it is presented and refuses it thereafter.
 *
 * <p>Memory is hard-bounded two ways: entries are pruned lazily once past their
 * expiry (plus a retention margin comfortably above the acceptance clock-skew
 * tolerance, so replay memory always outlives acceptability), and the map has an
 * absolute capacity. At capacity the guard <strong>fails closed</strong> — new
 * capabilities are refused rather than evicting older ones, because evicting would
 * re-open a consumed capability to replay. A full guard means someone with a valid
 * invite is minting capabilities far faster than players join; refusing them is safe
 * (a legitimate guest simply requests a fresh capability once load subsides).
 */
public final class ReplayGuard {

    /** Retention past expiry; strictly greater than the acceptance skew tolerance. */
    private static final long RETENTION_SECONDS = 30;

    /** Absolute entry cap. 30 s of retention at this size is ~130 admissions/second. */
    public static final int MAX_ENTRIES = 4096;

    private final Map<String, Long> seenUntil = new ConcurrentHashMap<>();

    /**
     * @return {@code true} exactly once per capability id; {@code false} for replays
     *         and — fail-closed — for any new id while the guard is at capacity
     */
    public boolean firstUse(String capabilityId, long expiresAtEpochSeconds, Instant now) {
        prune(now);
        if (seenUntil.size() >= MAX_ENTRIES && !seenUntil.containsKey(capabilityId)) {
            return false;
        }
        return seenUntil.putIfAbsent(capabilityId,
                expiresAtEpochSeconds + RETENTION_SECONDS) == null;
    }

    private void prune(Instant now) {
        long cutoff = now.getEpochSecond();
        Iterator<Map.Entry<String, Long>> it = seenUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
            }
        }
    }

    /** Number of remembered ids, for tests and diagnostics. */
    public int size() {
        return seenUntil.size();
    }
}
