/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client;

import java.net.URI;

/**
 * Client-side configuration. Deliberately tiny: the one thing a player (or a dev
 * environment) can point somewhere else is the coordination service.
 */
public final class NexusConfig {

    private static final String DEFAULT_BACKEND = "http://127.0.0.1:8420";

    private NexusConfig() {
    }

    /**
     * Which address ranges joined routes may dial: {@code -Dnexus.route.mode}
     * ({@code development} default — loopback/private allowed for local testing;
     * {@code production} refuses loopback and link-local).
     */
    public static dev.nexus.session.protocol.RoutePolicy.Mode routeMode() {
        String mode = System.getProperty("nexus.route.mode", "development");
        return switch (mode.toLowerCase().trim()) {
            case "production" -> dev.nexus.session.protocol.RoutePolicy.Mode.PRODUCTION;
            case "development" -> dev.nexus.session.protocol.RoutePolicy.Mode.DEVELOPMENT;
            default -> throw new IllegalArgumentException(
                    "nexus.route.mode must be development or production");
        };
    }

    /**
     * The coordination backend, from {@code -Dnexus.backend.url} or the
     * {@code NEXUS_BACKEND_URL} environment variable. The plaintext policy (remote
     * http:// refused) is enforced by SessionClient itself.
     */
    public static URI backendUrl() {
        String fromProperty = System.getProperty("nexus.backend.url");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return URI.create(fromProperty.trim());
        }
        String fromEnv = System.getenv("NEXUS_BACKEND_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return URI.create(fromEnv.trim());
        }
        return URI.create(DEFAULT_BACKEND);
    }
}
