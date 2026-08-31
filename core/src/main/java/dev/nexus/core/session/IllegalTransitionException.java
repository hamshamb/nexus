/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.core.session;

/**
 * Thrown when a session is asked to make a transition its lifecycle does not allow.
 *
 * <p>This always indicates a bug in Nexus rather than anything a player did, so it is
 * unchecked and must never be caught and ignored.
 */
public class IllegalTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(Enum<?> from, Enum<?> to) {
        super("Illegal session transition: " + from + " -> " + to);
    }
}
