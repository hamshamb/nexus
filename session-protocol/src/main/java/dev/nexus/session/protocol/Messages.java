/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import java.util.List;

/**
 * The coordination protocol's message shapes, used verbatim by backend and client.
 *
 * <p>Privacy is enforced by shape: the only route information that ever crosses the
 * wire is the host's registered addresses and listener port -- exactly what a guest
 * needs to establish the existing transport route, and nothing else. There is no field
 * for world data, chat, inventories, file paths, or any Microsoft/Mojang token, and the
 * backend rejects unknown-versioned or oversized requests before parsing further.
 */
public final class Messages {

    private Messages() {
    }

    /** Host registers a session. Addresses are the host's own reachable addresses. */
    public record CreateSessionRequest(int protocolVersion, List<String> addresses, int port) {
    }

    /**
     * @param admissionKey base64url per-session admission key; shared only with the
     *                     host that created the session
     */
    public record CreateSessionResponse(String sessionId, String hostToken,
                                        String inviteCode, String admissionKey,
                                        long expiresAtEpochSeconds, int heartbeatSeconds) {
    }

    /** Guest requests admission: route information plus a one-time capability. */
    public record JoinRequest(int protocolVersion, String inviteCode) {
    }

    public record JoinResponse(String sessionId, List<String> addresses, int port,
                               String capabilityToken, long capabilityExpiresAtEpochSeconds) {
    }

    public record HeartbeatRequest(int protocolVersion, String hostToken) {
    }

    public record HeartbeatResponse(long expiresAtEpochSeconds) {
    }

    public record CloseRequest(int protocolVersion, String hostToken) {
    }

    /** Uniform error shape; {@code error} is a {@link Protocol.ErrorCode} constant. */
    public record ErrorResponse(String error, String message) {
    }
}
