/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

/**
 * Host-side admission resource limits, validated at construction (fail fast on
 * impossible or dangerous values). Overridable via system properties
 * ({@code nexus.host.maxPendingAdmissions} etc.) with safe defaults.
 *
 * @param maxPendingAdmissions  concurrent pre-auth handshakes per hosted session
 * @param handshakeTimeoutSeconds how long a silent guest may hold a slot
 * @param sourceBurst           per-source token-bucket capacity
 * @param sourceRefillPerSecond per-source sustained rate
 * @param globalBurst           all-sources token-bucket capacity
 * @param globalRefillPerSecond all-sources sustained rate
 * @param rateLimiterMaxKeys    bound on tracked source buckets
 */
public record HostLimits(int maxPendingAdmissions, int handshakeTimeoutSeconds,
                         int sourceBurst, double sourceRefillPerSecond,
                         int globalBurst, double globalRefillPerSecond,
                         int rateLimiterMaxKeys) {

    public HostLimits {
        require(maxPendingAdmissions >= 1 && maxPendingAdmissions <= 1024,
                "maxPendingAdmissions must be 1..1024");
        require(handshakeTimeoutSeconds >= 1 && handshakeTimeoutSeconds <= 60,
                "handshakeTimeoutSeconds must be 1..60");
        require(sourceBurst >= 1 && sourceBurst <= 1000, "sourceBurst must be 1..1000");
        require(sourceRefillPerSecond > 0 && sourceRefillPerSecond <= 1000,
                "sourceRefillPerSecond must be (0,1000]");
        require(globalBurst >= 1 && globalBurst <= 10_000, "globalBurst must be 1..10000");
        require(globalRefillPerSecond > 0 && globalRefillPerSecond <= 10_000,
                "globalRefillPerSecond must be (0,10000]");
        require(rateLimiterMaxKeys >= 1 && rateLimiterMaxKeys <= 1_000_000,
                "rateLimiterMaxKeys must be 1..1000000");
    }

    /** Safe defaults: far above legitimate join rates, far below flood scale. */
    public static HostLimits defaults() {
        return new HostLimits(16, 5, 5, 1.0, 20, 5.0, 10_000);
    }

    /** Defaults overridden by {@code nexus.host.*} system properties, then validated. */
    public static HostLimits fromSystemProperties() {
        HostLimits d = defaults();
        return new HostLimits(
                intProp("nexus.host.maxPendingAdmissions", d.maxPendingAdmissions()),
                intProp("nexus.host.handshakeTimeoutSeconds", d.handshakeTimeoutSeconds()),
                intProp("nexus.host.sourceBurst", d.sourceBurst()),
                doubleProp("nexus.host.sourceRefillPerSecond", d.sourceRefillPerSecond()),
                intProp("nexus.host.globalBurst", d.globalBurst()),
                doubleProp("nexus.host.globalRefillPerSecond", d.globalRefillPerSecond()),
                d.rateLimiterMaxKeys());
    }

    private static int intProp(String name, int fallback) {
        String value = System.getProperty(name);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    private static double doubleProp(String name, double fallback) {
        String value = System.getProperty(name);
        return value == null ? fallback : Double.parseDouble(value.trim());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
