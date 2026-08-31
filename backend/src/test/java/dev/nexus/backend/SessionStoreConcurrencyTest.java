/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store's atomic-transition guarantees under contention, raced repeatedly:
 * a successfully refreshed session is never reaped for an earlier staleness
 * observation, a closed session never resurrects, and expiry versus close is
 * idempotent — the exact races the audit flagged.
 */
@Timeout(120)
class SessionStoreConcurrencyTest {

    private static final Duration TTL = Duration.ofSeconds(90);
    private static final Instant BASE = Instant.ofEpochSecond(1_800_000_000L);

    private static byte[] verifier(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static SessionStore.Session session(String id, Instant lastHeartbeat) {
        return new SessionStore.Session(id, "code-hash-" + id, verifier("token-" + id),
                new byte[32], List.of("192.168.1.20"), 54321, BASE, lastHeartbeat);
    }

    @Test
    void aRefreshedSessionIsNeverReapedByARacingExpiry() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2000; round++) {
                InMemorySessionStore store = new InMemorySessionStore(TTL);
                // Stale right now: both a reaper and a heartbeat will race for it.
                store.put(session("s", BASE.minusSeconds(91)));
                Instant now = BASE;

                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                boolean[] refreshed = new boolean[1];
                pool.execute(() -> {
                    await(go);
                    refreshed[0] = store.heartbeat("s", verifier("token-s"), now);
                    done.countDown();
                });
                pool.execute(() -> {
                    await(go);
                    store.expireStale(now);
                    done.countDown();
                });
                go.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

                if (refreshed[0]) {
                    // The heartbeat won: the refresh is real and must survive any
                    // number of reaper passes at the same instant.
                    store.expireStale(now);
                    assertThat(store.heartbeat("s", verifier("token-s"), now))
                            .as("round %d: refreshed session must still be alive", round)
                            .isTrue();
                } else {
                    // The reaper won: the session is gone and stays gone.
                    assertThat(store.size()).isZero();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aClosedSessionCanNeverBeResurrectedByARacingHeartbeat() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2000; round++) {
                InMemorySessionStore store = new InMemorySessionStore(TTL);
                store.put(session("s", BASE));

                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                boolean[] closed = new boolean[1];
                pool.execute(() -> {
                    await(go);
                    closed[0] = store.close("s", verifier("token-s"));
                    done.countDown();
                });
                pool.execute(() -> {
                    await(go);
                    store.heartbeat("s", verifier("token-s"), BASE.plusSeconds(1));
                    done.countDown();
                });
                go.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(closed[0]).as("close of a live session must succeed").isTrue();
                assertThat(store.size())
                        .as("round %d: nothing may survive a successful close", round)
                        .isZero();
                assertThat(store.heartbeat("s", verifier("token-s"), BASE.plusSeconds(2)))
                        .isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closeRacingExpiryLeavesExactlyNothing() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2000; round++) {
                InMemorySessionStore store = new InMemorySessionStore(TTL);
                store.put(session("s", BASE.minusSeconds(91)));

                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                pool.execute(() -> {
                    await(go);
                    store.close("s", verifier("token-s"));
                    done.countDown();
                });
                pool.execute(() -> {
                    await(go);
                    store.expireStale(BASE);
                    done.countDown();
                });
                go.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(store.size()).isZero();
                assertThat(store.findByCodeHash("code-hash-s", BASE)).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentHeartbeatsAllSucceedOnALiveSession() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore(TTL);
        store.put(session("s", BASE));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            AtomicInteger successes = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(800);
            for (int i = 0; i < 800; i++) {
                final int n = i;
                pool.execute(() -> {
                    if (store.heartbeat("s", verifier("token-s"), BASE.plusSeconds(n % 30))) {
                        successes.incrementAndGet();
                    }
                    done.countDown();
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(successes.get()).isEqualTo(800);
            assertThat(store.size()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void wrongTokenHasNoSideEffects() {
        InMemorySessionStore store = new InMemorySessionStore(TTL);
        store.put(session("s", BASE));
        assertThat(store.heartbeat("s", verifier("wrong"), BASE)).isFalse();
        assertThat(store.close("s", verifier("wrong"))).isFalse();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.close("s", verifier("token-s"))).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
