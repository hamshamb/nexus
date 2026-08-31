/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import io.netty.buffer.ByteBuf;

/**
 * Receives inbound data and lifecycle events for a single {@link PeerStream}.
 *
 * <p>Callbacks for one stream are delivered serially on that stream's I/O thread. They
 * must not block: no disk access, no network round-trips, no waiting on game threads.
 */
public interface PeerStreamHandler {

    /**
     * Called when bytes arrive.
     *
     * <p><strong>The callee owns {@code data} and must release it</strong>, including on
     * every error path. Bytes are a stream, not a message: a single logical packet may
     * span several callbacks and one callback may contain several packets.
     */
    void dataReceived(PeerStream stream, ByteBuf data);

    /**
     * Called when {@link PeerStream#isWritable()} changes.
     *
     * <p>A transition back to writable is the signal to resume a paused source, typically
     * by re-enabling reads on the opposite stream of a pump.
     */
    default void writabilityChanged(PeerStream stream) {
    }

    /**
     * Called exactly once when the stream has closed.
     *
     * @param cause the error that ended the stream, or {@code null} for an orderly close
     */
    void closed(PeerStream stream, Throwable cause);
}
