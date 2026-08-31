/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.nexus.session.protocol.AdmissionCapability.Verification;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit-driven hardening properties: the clock-skew acceptance window, replay-guard
 * memory bounds, rate-limiter bounds and concurrency, and hostile route data.
 */
@Timeout(60)
class HardeningTest {

    private final SecureRandom random = new SecureRandom();

    @Nested
    class ClockSkew {

        private final byte[] key = AdmissionCapability.newAdmissionKey(random);
        private final Instant mint = Instant.ofEpochSecond(1_800_000_000L);
        private final String token = AdmissionCapability.mint(key, "sess", random, mint);
        private final long expiry = AdmissionCapability.expiresAt(token);

        private Verification at(long hostEpochSecond) {
            return AdmissionCapability.verify(key, "sess", token,
                    Instant.ofEpochSecond(hostEpochSecond));
        }

        @Test
        void exactExpiryBoundaryStillAccepts() {
            assertThat(at(expiry)).isEqualTo(Verification.OK);
        }

        @Test
        void slightlySlowHostClockAccepts() {
            // Host clock behind: sees the token as expiring "later"; within skew.
            assertThat(at(expiry + AdmissionCapability.MAX_CLOCK_SKEW_SECONDS))
                    .isEqualTo(Verification.OK);
        }

        @Test
        void slightlyFastHostClockAccepts() {
            // Host clock ahead of the backend at mint time, within skew.
            assertThat(at(mint.getEpochSecond() - AdmissionCapability.MAX_CLOCK_SKEW_SECONDS))
                    .isEqualTo(Verification.OK);
        }

        @Test
        void beyondPositiveSkewExpires() {
            assertThat(at(expiry + AdmissionCapability.MAX_CLOCK_SKEW_SECONDS + 1))
                    .isEqualTo(Verification.EXPIRED);
        }

        @Test
        void beyondNegativeSkewIsNotYetValid() {
            // A host clock so far behind that the expiry looks future-dated beyond
            // any legitimate mint: a slow host cannot stretch validity indefinitely.
            assertThat(at(mint.getEpochSecond() - AdmissionCapability.MAX_CLOCK_SKEW_SECONDS - 1))
                    .isEqualTo(Verification.NOT_YET_VALID);
        }

        @Test
        void forgedFarFutureExpiryIsRejectedEvenBeforeSignatureWouldSaveUs() {
            // Signed with the right key but a fabricated far-future expiry can't
            // happen (MAC covers expiry); this asserts the window math directly: even
            // a validly-signed token is never accepted longer than TTL + 2*skew.
            long windowSeconds = (expiry + AdmissionCapability.MAX_CLOCK_SKEW_SECONDS)
                    - (mint.getEpochSecond() - AdmissionCapability.MAX_CLOCK_SKEW_SECONDS);
            assertThat(windowSeconds).isEqualTo(
                    Protocol.CAPABILITY_TTL_SECONDS + 2L * AdmissionCapability.MAX_CLOCK_SKEW_SECONDS);
        }

        @Test
        void replayGuardRetentionOutlivesSkewedAcceptance() {
            ReplayGuard guard = new ReplayGuard();
            String id = AdmissionCapability.capabilityId(token);
            assertThat(guard.firstUse(id, expiry, mint)).isTrue();
            // At the last skew-accepted moment, the replay is still remembered.
            Instant lastAccepted = Instant.ofEpochSecond(
                    expiry + AdmissionCapability.MAX_CLOCK_SKEW_SECONDS);
            assertThat(guard.firstUse(id, expiry, lastAccepted)).isFalse();
        }
    }

    @Nested
    class ReplayGuardBounds {

        @Test
        void atCapacityNewCapabilitiesAreRefusedNotEvicted() {
            ReplayGuard guard = new ReplayGuard();
            Instant now = Instant.ofEpochSecond(1_800_000_000L);
            long expiry = now.getEpochSecond() + 30;
            for (int i = 0; i < ReplayGuard.MAX_ENTRIES; i++) {
                assertThat(guard.firstUse("cap-" + i, expiry, now)).isTrue();
            }
            // Full: fail closed for a new id...
            assertThat(guard.firstUse("cap-overflow", expiry, now)).isFalse();
            // ...and a consumed id stays consumed (no eviction reopened it).
            assertThat(guard.firstUse("cap-0", expiry, now)).isFalse();
            assertThat(guard.size()).isEqualTo(ReplayGuard.MAX_ENTRIES);

            // After expiry + retention, pruning frees capacity again.
            Instant later = now.plusSeconds(120);
            assertThat(guard.firstUse("cap-overflow", later.getEpochSecond() + 30, later))
                    .isTrue();
            assertThat(guard.size()).isEqualTo(1);
        }
    }

    @Nested
    class RateLimiterHardening {

        @Test
        void boundedKeyStorageFailsClosedForNewKeys() {
            RateLimiter limiter = new RateLimiter(5, 1, 10);
            Instant now = Instant.ofEpochSecond(1_800_000_000L);
            for (int i = 0; i < 10; i++) {
                assertThat(limiter.tryAcquire("key-" + i, now)).isTrue();
            }
            assertThat(limiter.size()).isEqualTo(10);
            // New key at capacity: refused; existing keys still served.
            assertThat(limiter.tryAcquire("key-new", now)).isFalse();
            assertThat(limiter.tryAcquire("key-3", now)).isTrue();
        }

        @Test
        void pruneFreesIdleBucketsAndConcurrentAcquireStaysSafe() throws Exception {
            RateLimiter limiter = new RateLimiter(100, 10, 1000);
            Instant start = Instant.ofEpochSecond(1_800_000_000L);
            limiter.tryAcquire("idle", start);
            // 100/10 => full again after 10s; prune threshold adds 60s.
            limiter.prune(start.plusSeconds(120));
            assertThat(limiter.size()).isZero();

            // Hammer one bucket from many threads while pruning concurrently: total
            // admissions must never exceed capacity + refill during the window.
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                AtomicInteger admitted = new AtomicInteger();
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(8);
                for (int t = 0; t < 8; t++) {
                    pool.execute(() -> {
                        try {
                            go.await();
                            for (int i = 0; i < 1000; i++) {
                                if (limiter.tryAcquire("hot", start)) {
                                    admitted.incrementAndGet();
                                }
                                if (i % 100 == 0) {
                                    limiter.prune(start);
                                }
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                go.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
                // Zero elapsed time in the test clock: refill contributes nothing, so
                // the only tokens are the initial capacity. The orphan-retry rule
                // means concurrency may only under-admit, never over-admit.
                assertThat(admitted.get()).isLessThanOrEqualTo(100);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    class HostileRouteData {

        @Test
        void validRoutesPassInDevelopment() {
            for (String good : new String[]{
                    "192.168.1.20", "10.0.0.5", "203.0.113.9", "2001:db8::5",
                    "example.com", "my-host.local", "127.0.0.1"}) {
                assertThat(RoutePolicy.validAddress(good, RoutePolicy.Mode.DEVELOPMENT))
                        .as(good).isTrue();
            }
        }

        @Test
        void hostileRoutesAreRejectedInAnyMode() {
            for (String bad : new String[]{
                    null, "", "http://evil.example", "evil.example/path",
                    "host:port:extra/", "10.0.0.1/8", "..\\..\\windows",
                    "host name", "host\nname", "host@evil", "%41host",
                    "0.0.0.0", "255.999.1.1", "fe80::1", "ff02::1", "::",
                    "a".repeat(70), "-leadinghyphen.example", "trailing-.example",
                    "evil.example."}) {
                assertThat(RoutePolicy.validAddress(bad, RoutePolicy.Mode.DEVELOPMENT))
                        .as(String.valueOf(bad)).isFalse();
            }
        }

        @Test
        void productionModeAdditionallyRefusesLoopback() {
            assertThat(RoutePolicy.validAddress("127.0.0.1", RoutePolicy.Mode.PRODUCTION))
                    .isFalse();
            assertThat(RoutePolicy.validAddress("::1", RoutePolicy.Mode.PRODUCTION))
                    .isFalse();
            assertThat(RoutePolicy.validAddress("203.0.113.9", RoutePolicy.Mode.PRODUCTION))
                    .isTrue();
        }

        @Test
        void sanitizeCapsCountAndDropsGarbage() {
            List<String> mixed = new java.util.ArrayList<>();
            mixed.add("javascript:alert(1)");
            for (int i = 0; i < 20; i++) {
                mixed.add("10.0.0." + (i + 1));
            }
            List<String> clean = RoutePolicy.sanitize(mixed, RoutePolicy.Mode.DEVELOPMENT);
            assertThat(clean).hasSize(Protocol.MAX_ADDRESSES);
            assertThat(clean).allMatch(a -> a.startsWith("10.0.0."));
        }
    }

    @Nested
    class ConcurrentReplayGuard {

        @Test
        void exactlyOneWinnerUnderContention() throws Exception {
            ReplayGuard guard = new ReplayGuard();
            Instant now = Instant.ofEpochSecond(1_800_000_000L);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                for (int round = 0; round < 200; round++) {
                    String id = "contested-" + round;
                    Set<Boolean> results = ConcurrentHashMap.newKeySet();
                    AtomicInteger wins = new AtomicInteger();
                    CountDownLatch done = new CountDownLatch(8);
                    for (int t = 0; t < 8; t++) {
                        pool.execute(() -> {
                            if (guard.firstUse(id, now.getEpochSecond() + 30, now)) {
                                wins.incrementAndGet();
                            }
                            results.add(true);
                            done.countDown();
                        });
                    }
                    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(wins.get()).isEqualTo(1);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
