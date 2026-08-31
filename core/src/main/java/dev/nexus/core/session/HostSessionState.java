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
 * The lifecycle of a hosted session, from the host's point of view.
 *
 * <p>Legal transitions are declared here rather than being implied by scattered booleans,
 * so that "can we stop hosting right now?" has one answer with one place to change it.
 *
 * <pre>
 *   IDLE -> STARTING -> GATHERING_CONNECTIVITY -> ONLINE -> STOPPING -> STOPPED
 *             |                   |                  |                     ^
 *             +-------------------+------------------+---------------------+
 *                          (failure or stop at any active stage)
 * </pre>
 */
public enum HostSessionState {

    /** Not hosting. The world is single-player and local. */
    IDLE,

    /** Preparing the integrated server to accept guests. */
    STARTING,

    /** Discovering how guests will be able to reach us. */
    GATHERING_CONNECTIVITY,

    /** Accepting guests. */
    ONLINE,

    /** Winding down: refusing new guests and releasing resources. */
    STOPPING,

    /**
     * Fully stopped. Terminal for this session object.
     *
     * <p>Reached both by an orderly stop and by a failure; {@code STOPPED} therefore does
     * not by itself mean the session succeeded. The reason is carried alongside the state
     * by {@link HostSessionMachine}.
     */
    STOPPED;

    private static final Set<HostSessionState> NONE = Collections.emptySet();

    /**
     * Whether the session is doing something that owns resources needing cleanup.
     *
     * <p>This is what "stop hosting" and the shutdown hooks test against.
     */
    public boolean isActive() {
        return this == STARTING || this == GATHERING_CONNECTIVITY || this == ONLINE;
    }

    /** Whether guests can connect in this state. */
    public boolean isAcceptingGuests() {
        return this == ONLINE;
    }

    /** Whether this state is terminal. */
    public boolean isTerminal() {
        return this == STOPPED;
    }

    /** The states reachable directly from this one. */
    public Set<HostSessionState> allowedSuccessors() {
        return switch (this) {
            case IDLE -> EnumSet.of(STARTING);
            // Any active stage can be abandoned, so every one of them may go to STOPPING.
            case STARTING -> EnumSet.of(GATHERING_CONNECTIVITY, STOPPING);
            case GATHERING_CONNECTIVITY -> EnumSet.of(ONLINE, STOPPING);
            case ONLINE -> EnumSet.of(STOPPING);
            case STOPPING -> EnumSet.of(STOPPED);
            case STOPPED -> NONE;
        };
    }

    /** Whether {@code next} may follow this state. */
    public boolean canTransitionTo(HostSessionState next) {
        return allowedSuccessors().contains(next);
    }
}
