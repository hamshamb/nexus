/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import java.util.Objects;

/**
 * A coordination-backend failure with the backend's machine-readable error code
 * (a {@link dev.nexus.session.protocol.Protocol.ErrorCode} constant), or
 * {@link #TRANSPORT} when the backend could not be reached at all.
 */
public class SessionClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Pseudo-code for "the HTTP request itself failed". */
    public static final String TRANSPORT = "transport";

    private final String code;

    public SessionClientException(String code, String message) {
        this(code, message, null);
    }

    public SessionClientException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
