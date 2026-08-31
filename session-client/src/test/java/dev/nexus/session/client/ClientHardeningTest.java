/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import com.sun.net.httpserver.HttpServer;
import dev.nexus.session.protocol.RoutePolicy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Client-side hardening: the remote-plaintext refusal, response-body bounds, and the
 * admission gate's resource limits.
 */
@Timeout(60)
class ClientHardeningTest {

    @Nested
    class PlaintextPolicy {

        private void accepted(String url) {
            assertThat(BackendEndpoint.validate(URI.create(url)).toString())
                    .isEqualTo(url);
        }

        private void rejected(String url) {
            assertThatExceptionOfType(SessionClientException.class)
                    .isThrownBy(() -> BackendEndpoint.validate(URI.create(url)))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("insecure_endpoint"));
        }

        @Test
        void loopbackHttpIsAccepted() {
            accepted("http://127.0.0.1:8420");          // IPv4 loopback
            accepted("http://127.5.9.1:8420");          // anywhere in 127/8
            accepted("http://[::1]:8420");              // IPv6 loopback
            accepted("http://localhost:8420");          // exact literal hostname
            accepted("http://LOCALHOST:8420");           // case-insensitive
        }

        @Test
        void remoteHttpIsRejectedBeforeAnySecretCouldTravel() {
            rejected("http://203.0.113.9:8420");
            rejected("http://192.168.1.20:8420");       // LAN is still not this machine
            rejected("http://nexus.example.com:8420");
            rejected("http://[2001:db8::5]:8420");
        }

        @Test
        void httpsRemoteIsAccepted() {
            accepted("https://nexus.example.com");
            accepted("https://203.0.113.9:8443");
        }

        @Test
        void deceptiveHostsAreRejected() {
            // A hostname that merely LOOKS like a loopback literal.
            rejected("http://127.0.0.1.evil.example:8420");
            // Credentials smuggled into the URL.
            rejected("http://user:pass@127.0.0.1:8420");
            // Unknown scheme.
            rejected("ftp://127.0.0.1:8420");
            // No host at all.
            rejected("http:///v1");
        }

        @Test
        void arbitraryHostnamesAreRejectedEvenIfCurrentDnsResolvesThemToLoopback() {
            // DNS rebinding hazard: validation and connection are two separate
            // lookups. A hostname must never be trusted just because it happens to
            // answer loopback right now -- only literal addresses and the fixed
            // "localhost" string are decided by text alone. "localhost" itself is a
            // deliberately allow-listed exact string, not a general resolution path.
            assertThat(BackendEndpoint.isUnambiguousLocalLiteral("localhost")).isTrue();
            // Any other hostname is rejected outright, regardless of what it
            // resolves to -- the policy never calls DNS to decide this.
            assertThat(BackendEndpoint.isUnambiguousLocalLiteral("some-host-that-currently-resolves-to-127.0.0.1"))
                    .isFalse();
            rejected("http://some-host-that-currently-resolves-to-127.0.0.1:8420");
            rejected("http://loopback.local:8420");
            rejected("http://localhost.evil.example:8420");
        }

        @Test
        void sessionClientConstructorEnforcesThePolicy() {
            assertThatExceptionOfType(SessionClientException.class)
                    .isThrownBy(() -> new SessionClient(
                            URI.create("http://203.0.113.9:8420")));
        }
    }

    @Nested
    class ResponseBounds {

        @Test
        void anOversizedBackendResponseFailsTypedInsteadOfBuffering() throws Exception {
            HttpServer hostile = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] flood = new byte[SessionClient.MAX_RESPONSE_BYTES * 4];
            java.util.Arrays.fill(flood, (byte) 'x');
            hostile.createContext("/v1/join", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, flood.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(flood);
                } catch (Exception ignored) {
                    // The client cancels mid-stream; that is the point.
                }
            });
            hostile.start();
            try (SessionClient client = new SessionClient(URI.create(
                    "http://127.0.0.1:" + hostile.getAddress().getPort()))) {
                assertThatExceptionOfType(Exception.class)
                        .isThrownBy(() -> client.join("K7M2-PQ9X", RoutePolicy.Mode.DEVELOPMENT)
                                .get(15, TimeUnit.SECONDS))
                        .withCauseInstanceOf(SessionClientException.class)
                        .satisfies(e -> assertThat(
                                ((SessionClientException) e.getCause()).getMessage())
                                .contains("oversized"));
            } finally {
                hostile.stop(0);
            }
        }

        @Test
        void aBackendReturningHostileRoutesFailsTyped() throws Exception {
            HttpServer lying = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            String body = """
                    {"sessionId":"s","addresses":["javascript:alert(1)","evil/path"],
                     "port":25565,"capabilityToken":"NXC1.s.c.1.mac",
                     "capabilityExpiresAtEpochSeconds":1}""";
            lying.createContext("/v1/join", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            lying.start();
            try (SessionClient client = new SessionClient(URI.create(
                    "http://127.0.0.1:" + lying.getAddress().getPort()))) {
                assertThatExceptionOfType(Exception.class)
                        .isThrownBy(() -> client.join("K7M2-PQ9X", RoutePolicy.Mode.DEVELOPMENT)
                                .get(15, TimeUnit.SECONDS))
                        .withCauseInstanceOf(SessionClientException.class)
                        .satisfies(e -> assertThat(
                                ((SessionClientException) e.getCause()).getMessage())
                                .contains("unusable route"));
            } finally {
                lying.stop(0);
            }
        }
    }

    @Nested
    class AdmissionGateLimits {

        private final Instant now = Instant.ofEpochSecond(1_800_000_000L);

        private AdmissionGate gate(int maxPending, int sourceBurst, int globalBurst) {
            return new AdmissionGate(new HostLimits(maxPending, 5,
                    sourceBurst, 0.001, globalBurst, 0.001, 100));
        }

        private InetSocketAddress source(String ip) {
            return new InetSocketAddress(ip, 12345);
        }

        @Test
        void pendingCapIsEnforcedAndSlotsRelease() {
            AdmissionGate gate = gate(2, 100, 100);
            assertThat(gate.tryAcquire(source("198.51.100.1"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.2"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.3"), now))
                    .isEqualTo(AdmissionGate.Refusal.PENDING_LIMIT);
            // Timeout / malformed preamble / success all funnel through release():
            gate.release();
            assertThat(gate.tryAcquire(source("198.51.100.3"), now)).isNull();
            assertThat(gate.pending()).isEqualTo(2);
        }

        @Test
        void perSourceRateLimitBitesWhileOtherSourcesContinue() {
            AdmissionGate gate = gate(100, 2, 100);
            assertThat(gate.tryAcquire(source("198.51.100.7"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.7"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.7"), now))
                    .isEqualTo(AdmissionGate.Refusal.SOURCE_RATE);
            assertThat(gate.tryAcquire(source("198.51.100.8"), now)).isNull();
        }

        @Test
        void globalRateLimitStopsDistributedFloods() {
            AdmissionGate gate = gate(1000, 1000, 3);
            assertThat(gate.tryAcquire(source("198.51.100.1"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.2"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.3"), now)).isNull();
            assertThat(gate.tryAcquire(source("198.51.100.4"), now))
                    .isEqualTo(AdmissionGate.Refusal.GLOBAL_RATE);
        }

        @Test
        void manyConcurrentBogusConnectionsNeverExceedTheCap() throws Exception {
            AdmissionGate gate = gate(8, 1000, 10_000);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                AtomicInteger admitted = new AtomicInteger();
                AtomicInteger peak = new AtomicInteger();
                CountDownLatch done = new CountDownLatch(500);
                for (int i = 0; i < 500; i++) {
                    final int n = i;
                    pool.execute(() -> {
                        AdmissionGate.Refusal refusal =
                                gate.tryAcquire(source("198.51.100." + (n % 200 + 1)), now);
                        if (refusal == null) {
                            admitted.incrementAndGet();
                            peak.accumulateAndGet(gate.pending(), Math::max);
                            gate.release();
                        }
                        done.countDown();
                    });
                }
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(peak.get()).isLessThanOrEqualTo(8);
                assertThat(gate.pending()).isZero();
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void limitsValidateAtConstruction() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new HostLimits(0, 5, 5, 1, 20, 5, 100));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new HostLimits(16, 0, 5, 1, 20, 5, 100));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new HostLimits(16, 5, 5, -1, 20, 5, 100));
            assertThat(HostLimits.defaults()).isNotNull();
        }
    }
}
