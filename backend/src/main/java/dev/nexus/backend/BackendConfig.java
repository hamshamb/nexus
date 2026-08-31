/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import dev.nexus.session.protocol.Protocol;
import dev.nexus.session.protocol.RoutePolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backend configuration, from environment variables (see {@code .env.example}),
 * validated at startup — impossible or dangerous values fail fast rather than running
 * quietly misconfigured. No secrets live here: the backend holds no long-lived
 * credentials at all.
 *
 * @param bindAddress          address to bind; loopback by default — exposing the
 *                             backend beyond this machine is an explicit decision
 * @param port                 HTTP bind port
 * @param sessionTtlSeconds    session expiry without a heartbeat
 * @param heartbeatSeconds     interval hosts are told to heartbeat at
 * @param rateCapacity         guest-op token-bucket burst per remote address
 * @param rateRefillPerSecond  guest-op token-bucket refill per remote address
 * @param routeMode            which address ranges hosts may register as routes
 * @param trustedProxies       remote addresses whose X-Forwarded-For is believed;
 *                             empty (the default) means the header is ignored
 */
public record BackendConfig(String bindAddress, int port, int sessionTtlSeconds,
                            int heartbeatSeconds, int rateCapacity,
                            double rateRefillPerSecond, RoutePolicy.Mode routeMode,
                            Set<String> trustedProxies) {

    public BackendConfig {
        require(port >= 0 && port <= 65535, "port must be 0..65535");
        require(sessionTtlSeconds >= 10 && sessionTtlSeconds <= 3600,
                "session TTL must be 10..3600 seconds");
        require(heartbeatSeconds >= 5 && heartbeatSeconds * 2 <= sessionTtlSeconds,
                "heartbeat interval must be >=5s and at most half the session TTL");
        require(rateCapacity >= 1 && rateCapacity <= 10_000,
                "rate capacity must be 1..10000");
        require(rateRefillPerSecond > 0 && rateRefillPerSecond <= 10_000,
                "rate refill must be (0,10000]/s");
        require(Protocol.CAPABILITY_TTL_SECONDS < sessionTtlSeconds,
                "capability TTL must be shorter than the session TTL");
    }

    public static BackendConfig fromEnv(Map<String, String> env) {
        String modeName = env.getOrDefault("NEXUS_ROUTE_MODE", "development").trim();
        RoutePolicy.Mode mode = switch (modeName.toLowerCase()) {
            case "development" -> RoutePolicy.Mode.DEVELOPMENT;
            case "production" -> RoutePolicy.Mode.PRODUCTION;
            default -> throw new IllegalArgumentException(
                    "NEXUS_ROUTE_MODE must be development or production");
        };
        String proxies = env.getOrDefault("NEXUS_TRUSTED_PROXIES", "");
        Set<String> trusted = proxies.isBlank()
                ? Set.of()
                : List.of(proxies.split(",")).stream()
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        return new BackendConfig(
                env.getOrDefault("NEXUS_BACKEND_BIND", "127.0.0.1").trim(),
                intVar(env, "NEXUS_BACKEND_PORT", 8420),
                intVar(env, "NEXUS_SESSION_TTL_SECONDS", Protocol.DEFAULT_SESSION_TTL_SECONDS),
                intVar(env, "NEXUS_HEARTBEAT_SECONDS", Protocol.DEFAULT_HEARTBEAT_SECONDS),
                intVar(env, "NEXUS_RATE_CAPACITY", 10),
                intVar(env, "NEXUS_RATE_REFILL_PER_SECOND", 1),
                mode,
                trusted);
    }

    private static int intVar(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + value);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
