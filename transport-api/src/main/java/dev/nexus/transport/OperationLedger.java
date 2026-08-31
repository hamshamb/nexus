/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Ownership of in-flight asynchronous operations, so a session's stop can truthfully
 * await work that has <em>started</em> but not yet produced a trackable resource
 * (admission handshakes, loopback dials, backend registration).
 *
 * <p>The contract that closes the snapshot race:
 * <ul>
 *   <li>an operation is {@link #track tracked} <strong>before</strong> its async work
 *       begins, and its future completes only when the operation's resources are
 *       either fully released or handed over to a longer-lived owner;</li>
 *   <li>{@link #drain()} atomically flips the ledger closed and returns a future for
 *       every tracked operation. After drain, {@code track} refuses — the caller must
 *       clean up synchronously instead of starting work;</li>
 *   <li>therefore: every operation is either awaited by drain, or was never allowed
 *       to start. No late completion can attach a resource after stop has won.</li>
 * </ul>
 */
public final class OperationLedger {

    private final Set<CompletableFuture<?>> inFlight = new HashSet<>();
    private boolean closed;

    /**
     * Registers {@code operation} as owned in-flight work.
     *
     * @return {@code true} if accepted; {@code false} if the ledger is already
     *         drained — the caller must not start the operation and must release
     *         anything it already holds
     */
    public boolean track(CompletableFuture<?> operation) {
        synchronized (this) {
            if (closed) {
                return false;
            }
            inFlight.add(operation);
        }
        operation.whenComplete((v, t) -> {
            synchronized (this) {
                inFlight.remove(operation);
            }
        });
        return true;
    }

    /**
     * Closes the ledger and returns a future completing when every tracked operation
     * has completed (normally or exceptionally). Idempotent in effect: later calls
     * cover whatever remains.
     */
    public CompletableFuture<Void> drain() {
        List<CompletableFuture<?>> snapshot;
        synchronized (this) {
            closed = true;
            snapshot = new ArrayList<>(inFlight);
        }
        return CompletableFuture.allOf(snapshot.stream()
                .map(f -> f.handle((v, t) -> null))
                .toArray(CompletableFuture[]::new));
    }

    /** In-flight count, for tests and diagnostics. */
    public int pending() {
        synchronized (this) {
            return inFlight.size();
        }
    }
}
