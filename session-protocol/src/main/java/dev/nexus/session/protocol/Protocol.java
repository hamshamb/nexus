/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

/**
 * Constants of the Nexus coordination protocol.
 *
 * <p>The protocol is versioned from day one: every request carries
 * {@link #VERSION}, and the backend rejects unknown versions with
 * {@link ErrorCode#UNSUPPORTED_PROTOCOL} rather than guessing. Unknown JSON fields are
 * ignored on both sides, so additive evolution does not need a version bump.
 */
public final class Protocol {

    /** Current protocol version. Bump only on incompatible changes. */
    public static final int VERSION = 1;

    /** Hard cap on any HTTP request body; larger requests are rejected unread. */
    public static final int MAX_BODY_BYTES = 8 * 1024;

    /** Hard cap on the admission-capability token, on the wire and in the preamble. */
    public static final int MAX_CAPABILITY_BYTES = 512;

    /** How long a minted admission capability stays valid. */
    public static final int CAPABILITY_TTL_SECONDS = 30;

    /** Session expires if the host misses heartbeats for this long. */
    public static final int DEFAULT_SESSION_TTL_SECONDS = 90;

    /** How often the host should heartbeat. */
    public static final int DEFAULT_HEARTBEAT_SECONDS = 30;

    /** Upper bound on how many route addresses a host may register. */
    public static final int MAX_ADDRESSES = 8;

    /** Upper bound on a single registered address string. */
    public static final int MAX_ADDRESS_LENGTH = 64;

    private Protocol() {
    }

    /** Machine-readable error identifiers returned by the backend. */
    public static final class ErrorCode {
        public static final String MALFORMED = "malformed";
        public static final String UNSUPPORTED_PROTOCOL = "unsupported_protocol";
        public static final String PAYLOAD_TOO_LARGE = "payload_too_large";
        public static final String RATE_LIMITED = "rate_limited";
        public static final String INVALID_CODE = "invalid_code";
        public static final String NOT_FOUND = "not_found";
        public static final String UNAUTHORIZED = "unauthorized";
        public static final String INTERNAL = "internal";

        private ErrorCode() {
        }
    }
}
