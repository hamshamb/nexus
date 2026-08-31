/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import dev.nexus.session.protocol.RoutePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The backend owns every thread it starts: start→stop→start→stop leaves zero
 * backend-owned workers, and configuration is validated before anything runs.
 */
@Timeout(60)
class BackendLifecycleTest {

    private static BackendConfig config() {
        return new BackendConfig("127.0.0.1", 0, 90, 30, 100, 50,
                RoutePolicy.Mode.DEVELOPMENT, Set.of());
    }

    private static long backendThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith("nexus-backend-"))
                .count();
    }

    @Test
    void startStopStartStopLeavesZeroBackendThreads() throws Exception {
        for (int cycle = 1; cycle <= 2; cycle++) {
            NexusBackend backend = NexusBackend.start(config(), Clock.systemUTC());
            assertThat(backendThreads()).as("cycle %d running", cycle).isGreaterThan(0);

            // Prove it actually serves while up.
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + backend.port() + "/v1/join"))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"protocolVersion\":1,\"inviteCode\":\"K7M2-PQ9X\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);

            backend.stop();
            // Bounded settling for the last worker to unwind.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (backendThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(backendThreads())
                    .as("cycle %d: zero backend-owned workers after stop", cycle)
                    .isZero();
        }
    }

    @Test
    void stopIsIdempotent() throws Exception {
        NexusBackend backend = NexusBackend.start(config(), Clock.systemUTC());
        backend.stop();
        backend.stop();
        assertThat(backendThreads()).isZero();
    }

    @Test
    void dangerousConfigurationFailsFast() {
        // TTL out of range.
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new BackendConfig("127.0.0.1", 0, 5, 2, 100, 50,
                        RoutePolicy.Mode.DEVELOPMENT, Set.of()));
        // Heartbeat longer than half the TTL (sessions would flap).
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new BackendConfig("127.0.0.1", 0, 90, 60, 100, 50,
                        RoutePolicy.Mode.DEVELOPMENT, Set.of()));
        // Nonsense rate limits.
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new BackendConfig("127.0.0.1", 0, 90, 30, 0, 50,
                        RoutePolicy.Mode.DEVELOPMENT, Set.of()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new BackendConfig("127.0.0.1", 0, 90, 30, 100, -1,
                        RoutePolicy.Mode.DEVELOPMENT, Set.of()));
        // Invalid port.
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new BackendConfig("127.0.0.1", 70000, 90, 30, 100, 50,
                        RoutePolicy.Mode.DEVELOPMENT, Set.of()));
        // Env parsing: bad route mode fails fast too.
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                BackendConfig.fromEnv(java.util.Map.of("NEXUS_ROUTE_MODE", "wide-open")));
    }
}
