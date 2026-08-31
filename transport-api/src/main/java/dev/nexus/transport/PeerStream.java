/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import io.netty.buffer.ByteBuf;

import java.util.concurrent.CompletionStage;

/**
 * A reliable, ordered, bidirectional byte stream between two peers.
 *
 * <p>This is deliberately the same contract a TCP connection offers, because that is
 * exactly what Minecraft's wire protocol expects: length-prefixed frames over a stream.
 * Consequently a transport implementation owes us no framing, no MTU awareness, and no
 * reordering logic -- only these guarantees:
 *
 * <ul>
 *   <li>bytes written arrive in the order written,</li>
 *   <li>no bytes are lost or duplicated while the stream is open,</li>
 *   <li>boundaries between {@link #write} calls are <em>not</em> preserved -- readers
 *       must treat inbound data as a stream, never as messages.</li>
 * </ul>
 *
 * <h2>Flow control</h2>
 * Flow control is part of this contract rather than an implementation detail, because
 * the bridge pumps between two streams of wildly different speeds: a guest on a slow
 * link during chunk streaming would otherwise make the host buffer without bound.
 * Writers must consult {@link #isWritable()} and stop writing when it is {@code false},
 * resuming on {@link PeerStreamHandler#writabilityChanged}. Readers that cannot keep up
 * must call {@link #setReadEnabled(boolean) setReadEnabled(false)} so backpressure
 * propagates to the remote peer instead of accumulating in memory here.
 *
 * <h2>Threading</h2>
 * Every {@link PeerStreamHandler} callback for a given stream is delivered serially on
 * that stream's own I/O thread; handlers must not block it. All other methods are safe
 * to call from any thread.
 *
 * <h2>Lifecycle</h2>
 * {@link #close()} is idempotent and may be called concurrently from both directions.
 * {@link #closeFuture()} always completes exactly once.
 */
public interface PeerStream {

    /** Stable identifier for this stream, for logging and diagnostics. */
    String id();

    /**
     * How this stream reached us, for the connection-details UI.
     *
     * @return the route, never {@code null}
     */
    TransportRoute route();

    /**
     * A human-readable description of the remote peer, safe to log.
     *
     * <p>Implementations must not return anything that would disclose more than the
     * route inherently reveals; see {@code docs/ARCHITECTURE.md} on privacy.
     */
    String remoteDescription();

    /**
     * The remote socket address, or {@code null} when the route has none (in-memory
     * test streams). Used for source-keyed admission limits.
     */
    default java.net.SocketAddress remoteAddress() {
        return null;
    }

    /**
     * Installs the handler receiving inbound data and lifecycle events.
     *
     * <p>Must be called exactly once, before the stream begins delivering data. A stream
     * does not deliver anything until a handler is installed, so there is no race between
     * construction and the first inbound bytes.
     *
     * @throws IllegalStateException if a handler was already installed
     */
    void handler(PeerStreamHandler handler);

    /**
     * Queues {@code data} for transmission and takes ownership of it: the buffer is
     * released by the transport whether the write succeeds or fails.
     *
     * <p>Writing while {@link #isWritable()} is {@code false} is permitted but will grow
     * an unbounded queue; callers in the data path must honour writability instead.
     *
     * @return a stage completing when the bytes have been handed to the network, or
     *         completing exceptionally if they could not be
     */
    CompletionStage<Void> write(ByteBuf data);

    /**
     * Whether the outbound queue is below its high-water mark.
     *
     * <p>Transitions are reported via {@link PeerStreamHandler#writabilityChanged}.
     */
    boolean isWritable();

    /**
     * Enables or disables delivery of inbound data.
     *
     * <p>Disabling stops {@link PeerStreamHandler#dataReceived} callbacks and lets the
     * underlying flow-control window close, slowing the remote peer. This is the correct
     * response to a slow sink -- never buffer the excess locally.
     */
    void setReadEnabled(boolean enabled);

    /** Whether the stream is open in both directions. */
    boolean isOpen();

    /**
     * Closes the stream. Idempotent, safe to call concurrently, and never throws.
     *
     * <p>Minecraft does not use TCP half-closure, so implementations must treat this as a
     * full close in both directions.
     */
    void close();

    /**
     * Completes exactly once when the stream has fully closed and released its resources.
     *
     * <p>Completes normally on an orderly close and exceptionally when the stream ended
     * because of an error.
     */
    CompletionStage<Void> closeFuture();
}
