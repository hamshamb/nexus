/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The backend's session state, deliberately minimal and ephemeral.
 *
 * <p>A session record knows: an opaque id, the invite-code <em>hash</em> (never the
 * code), a one-way <em>verifier</em> of the host's bearer token (never the token —
 * the token is 256 random bits, so a plain SHA-256 digest compared timing-safely is
 * the correct verifier; a password KDF would add cost against a threat that doesn't
 * exist for high-entropy secrets), the per-session admission key, the registered
 * route addresses/port, and timestamps. Nothing outlives its session.
 *
 * <p>Every mutation is an <strong>atomic state transition</strong>, not a
 * lookup-then-update: heartbeat validates token + liveness and refreshes in one
 * atomic step; close validates and removes in one; expiry removes a session only if
 * it is <em>still</em> stale at the atomic point of removal. A successfully
 * refreshed session can never be reaped because of an earlier staleness observation,
 * and a closed session can never be resurrected by a racing heartbeat.
 *
 * <p>This interface is the persistence seam: {@link InMemorySessionStore} carries the
 * M3 vertical slice; a persistent implementation must preserve the same atomicity.
 */
public interface SessionStore {

    /** One hosted session. {@code hostTokenVerifier} is SHA-256(token), never the token. */
    record Session(String sessionId, String inviteCodeHash, byte[] hostTokenVerifier,
                   byte[] admissionKey, List<String> addresses, int port,
                   Instant createdAt, Instant lastHeartbeat) {

        Session withHeartbeat(Instant at) {
            return new Session(sessionId, inviteCodeHash, hostTokenVerifier, admissionKey,
                    addresses, port, createdAt, at);
        }
    }

    /**
     * Stores a new session.
     *
     * @return {@code false} if the invite-code hash collides with a live session
     *         (the caller then generates a fresh code and retries)
     */
    boolean put(Session session);

    /** Looks up a live (non-expired) session by invite-code hash. */
    Optional<Session> findByCodeHash(String codeHash, Instant now);

    /**
     * Atomically: verify the token against the stored verifier (timing-safe), verify
     * the session is live, refresh its activity. Fails — without side effects — for a
     * missing, closed, expired, or wrongly-authenticated session.
     */
    boolean heartbeat(String sessionId, byte[] presentedTokenVerifier, Instant now);

    /**
     * Atomically: verify the token and remove the session. After {@code true}, no
     * racing heartbeat can resurrect it.
     */
    boolean close(String sessionId, byte[] presentedTokenVerifier);

    /**
     * Removes sessions that are still stale at the atomic moment of removal. A
     * session refreshed concurrently is left alone.
     */
    void expireStale(Instant now);

    /** Live session count, for diagnostics and tests. */
    int size();
}
