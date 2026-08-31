/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.core.session;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of joining a hosted session, from the guest's point of view.
 *
 * <pre>
 *   RESOLVING -> NEGOTIATING -+-> CONNECTING_DIRECT -> CONNECTED_DIRECT -+
 *                             |          |                              |
 *                             |          v (direct not possible)        |
 *                             +-> CONNECTING_RELAY  -> CONNECTED_RELAY -+-> DISCONNECTED
 *
 *   any non-terminal state -> FAILED
 * </pre>
 *
 * <p>Falling back from {@code CONNECTING_DIRECT} to {@code CONNECTING_RELAY} is a normal
 * transition, not an error: it is the expected path behind a symmetric NAT, and the UI
 * reports it as information rather than as a failure.
 */
public enum GuestSessionState {

    /** Turning an invite code into a session we can negotiate with. */
    RESOLVING,

    /** Exchanging the information needed to pick a route. */
    NEGOTIATING,

    /** Attempting a direct peer-to-peer connection. */
    CONNECTING_DIRECT,

    /** Connected directly to the host. */
    CONNECTED_DIRECT,

    /** Attempting to connect through a relay. */
    CONNECTING_RELAY,

    /** Connected to the host through a relay. */
    CONNECTED_RELAY,

    /** The connection ended normally, e.g. the player left or the host stopped. */
    DISCONNECTED,

    /** The connection could not be established or was lost unexpectedly. */
    FAILED;

    private static final Set<GuestSessionState> NONE = Collections.emptySet();

    /** Whether we are connected to the host in this state. */
    public boolean isConnected() {
        return this == CONNECTED_DIRECT || this == CONNECTED_RELAY;
    }

    /** Whether a connection attempt is in progress. */
    public boolean isConnecting() {
        return this == RESOLVING
                || this == NEGOTIATING
                || this == CONNECTING_DIRECT
                || this == CONNECTING_RELAY;
    }

    /** Whether this state is terminal. */
    public boolean isTerminal() {
        return this == DISCONNECTED || this == FAILED;
    }

    /** The states reachable directly from this one. */
    public Set<GuestSessionState> allowedSuccessors() {
        return switch (this) {
            case RESOLVING -> EnumSet.of(NEGOTIATING, FAILED);
            case NEGOTIATING -> EnumSet.of(CONNECTING_DIRECT, CONNECTING_RELAY, FAILED);
            // Direct may fall back to relay; that is the designed path, not a failure.
            case CONNECTING_DIRECT -> EnumSet.of(CONNECTED_DIRECT, CONNECTING_RELAY, FAILED);
            case CONNECTING_RELAY -> EnumSet.of(CONNECTED_RELAY, FAILED);
            case CONNECTED_DIRECT, CONNECTED_RELAY -> EnumSet.of(DISCONNECTED, FAILED);
            case DISCONNECTED, FAILED -> NONE;
        };
    }

    /** Whether {@code next} may follow this state. */
    public boolean canTransitionTo(GuestSessionState next) {
        return allowedSuccessors().contains(next);
    }
}
