/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Nexus session-coordination service: session creation, invite-code resolution,
 * admission-capability minting, heartbeats, expiry. Nothing else -- by design it can
 * never become a social platform, because it stores nothing a social platform would
 * need.
 *
 * <p>Binds loopback-only by default ({@code NEXUS_BACKEND_BIND} widens it
 * deliberately); anything remote is expected to sit behind TLS termination, and the
 * client refuses remote plaintext anyway. Owns every thread it starts:
 * {@link #stop()} stops accepting traffic, then shuts down and awaits the HTTP
 * executor and housekeeping, forcing termination if needed — zero backend-owned
 * workers survive it.
 *
 * <p>Run locally with {@code ./gradlew :backend:run}; configure via environment
 * variables (see {@code .env.example}).
 */
public final class NexusBackend {

    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final ScheduledExecutorService housekeeping;

    private NexusBackend(HttpServer server, ExecutorService httpExecutor,
                         ScheduledExecutorService housekeeping) {
        this.server = server;
        this.httpExecutor = httpExecutor;
        this.housekeeping = housekeeping;
    }

    /** Boots a backend; separated from main() so integration tests can embed it. */
    public static NexusBackend start(BackendConfig config, Clock clock) throws IOException {
        SessionStore store = new InMemorySessionStore(
                Duration.ofSeconds(config.sessionTtlSeconds()));

        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName(config.bindAddress()), config.port()), 0);
        ExecutorService httpExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "nexus-backend-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(httpExecutor);
        HttpApi api = new HttpApi(store, config, clock);
        api.mount(server);
        server.start();

        ScheduledExecutorService housekeeping =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "nexus-backend-housekeeping");
                    thread.setDaemon(true);
                    return thread;
                });
        housekeeping.scheduleAtFixedRate(() -> {
            store.expireStale(clock.instant());
            api.pruneLimiters(clock.instant());
        }, 15, 15, TimeUnit.SECONDS);

        return new NexusBackend(server, httpExecutor, housekeeping);
    }

    /** The actual bound port (relevant when configured with port 0 in tests). */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Full shutdown: stop accepting, then terminate every owned worker, bounded.
     * Safe to call more than once.
     */
    public void stop() {
        server.stop(0);
        shutdown(httpExecutor, "http");
        shutdown(housekeeping, "housekeeping");
    }

    private static void shutdown(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    System.err.println("[nexus-backend] " + name
                            + " executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws IOException {
        BackendConfig config = BackendConfig.fromEnv(System.getenv());
        NexusBackend backend = start(config, Clock.systemUTC());
        System.out.println("[nexus-backend] listening on " + config.bindAddress()
                + ":" + backend.port() + " (session TTL " + config.sessionTtlSeconds()
                + "s, route mode " + config.routeMode() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(backend::stop));
        // The worker threads are daemons; keep the process alive explicitly.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
