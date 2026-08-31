/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The M3 session store. All mutations funnel through per-key atomic
 * {@link ConcurrentHashMap#compute} operations on {@code byId} — the single source of
 * truth — so heartbeat/close/expiry are genuine state transitions, never
 * lookup-then-update sequences. The code-hash index is a derived pointer: it is only
 * trusted after re-reading the session it points to, so its updates need no
 * cross-map transaction.
 */
public final class InMemorySessionStore implements SessionStore {

    private final Duration ttl;
    private final Map<String, Session> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByCodeHash = new ConcurrentHashMap<>();

    public InMemorySessionStore(Duration ttl) {
        this.ttl = ttl;
    }

    private boolean expired(Session session, Instant now) {
        return session.lastHeartbeat().plus(ttl).isBefore(now);
    }

    @Override
    public boolean put(Session session) {
        if (idByCodeHash.putIfAbsent(session.inviteCodeHash(), session.sessionId()) != null) {
            return false;
        }
        byId.put(session.sessionId(), session);
        return true;
    }

    @Override
    public Optional<Session> findByCodeHash(String codeHash, Instant now) {
        String id = idByCodeHash.get(codeHash);
        if (id == null) {
            return Optional.empty();
        }
        // Atomic per-entry liveness check with lazy expiry.
        Session live = byId.compute(id, (k, s) ->
                s == null || expired(s, now) ? null : s);
        if (live == null) {
            idByCodeHash.remove(codeHash, id);
            return Optional.empty();
        }
        return Optional.of(live);
    }

    @Override
    public boolean heartbeat(String sessionId, byte[] presentedTokenVerifier, Instant now) {
        boolean[] refreshed = new boolean[1];
        byId.computeIfPresent(sessionId, (k, s) -> {
            if (!MessageDigest.isEqual(s.hostTokenVerifier(), presentedTokenVerifier)) {
                return s;                      // wrong token: no side effects
            }
            if (expired(s, now)) {
                dropIndex(s);
                return null;                   // stale at the atomic point: remove
            }
            refreshed[0] = true;
            return s.withHeartbeat(now);       // live: refresh atomically
        });
        return refreshed[0];
    }

    @Override
    public boolean close(String sessionId, byte[] presentedTokenVerifier) {
        boolean[] closed = new boolean[1];
        byId.computeIfPresent(sessionId, (k, s) -> {
            if (!MessageDigest.isEqual(s.hostTokenVerifier(), presentedTokenVerifier)) {
                return s;
            }
            closed[0] = true;
            dropIndex(s);
            return null;                       // removed atomically; no resurrection
        });
        return closed[0];
    }

    @Override
    public void expireStale(Instant now) {
        for (String id : byId.keySet()) {
            // Removal happens inside compute: a session refreshed between iteration
            // and here survives, because staleness is re-checked at the atomic point.
            byId.computeIfPresent(id, (k, s) -> {
                if (expired(s, now)) {
                    dropIndex(s);
                    return null;
                }
                return s;
            });
        }
    }

    /** Called only from inside a byId.compute that is removing {@code session}. */
    private void dropIndex(Session session) {
        idByCodeHash.remove(session.inviteCodeHash(), session.sessionId());
    }

    @Override
    public int size() {
        return byId.size();
    }
}
