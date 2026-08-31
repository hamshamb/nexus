/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import java.util.Objects;

/**
 * A transport failure carrying a {@link TransportFailure} category.
 *
 * <p>Transports must fail with this rather than with a bare {@code IOException}, so every
 * failure that reaches the UI can be rendered as an actionable message.
 */
public class TransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final TransportFailure failure;

    public TransportException(TransportFailure failure, String message) {
        this(failure, message, null);
    }

    public TransportException(TransportFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    /** The category this failure belongs to. */
    public TransportFailure failure() {
        return failure;
    }

    /**
     * Wraps an arbitrary throwable, preserving the category if it already has one.
     */
    public static TransportException wrap(TransportFailure fallback, Throwable cause) {
        if (cause instanceof TransportException existing) {
            return existing;
        }
        return new TransportException(fallback, String.valueOf(cause.getMessage()), cause);
    }
}
