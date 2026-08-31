/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

/**
 * How a peer connection was established.
 *
 * <p>This is what the connection-details UI reports, so the names describe the route in
 * player-facing terms rather than in protocol jargon.
 */
public enum TransportRoute {

    /** A direct TCP connection over a LAN or an already-reachable address. */
    DIRECT_TCP("Direct", false),

    /** A direct peer-to-peer connection established by NAT traversal. */
    DIRECT_P2P("Direct", true),

    /** A connection forwarded by a relay because direct connectivity was unavailable. */
    RELAY("Relay", true);

    private final String displayName;
    private final boolean encrypted;

    TransportRoute(String displayName, boolean encrypted) {
        this.displayName = displayName;
        this.encrypted = encrypted;
    }

    /** The short label shown to players, e.g. {@code "Direct"} or {@code "Relay"}. */
    public String displayName() {
        return displayName;
    }

    /**
     * Whether this route encrypts traffic at the transport layer.
     *
     * <p>{@link #DIRECT_TCP} does not: it is a plain socket used on trusted networks and
     * for testing. Minecraft's own login encryption still applies on every route, but the
     * UI must not claim transport encryption where there is none.
     */
    public boolean isEncrypted() {
        return encrypted;
    }
}
