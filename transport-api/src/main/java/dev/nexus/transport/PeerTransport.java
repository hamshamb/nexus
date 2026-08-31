/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import java.util.concurrent.CompletionStage;

/**
 * Establishes {@link PeerStream}s between a host and its guests.
 *
 * <p>A transport is either <em>listening</em> (the host, accepting guests) or
 * <em>dialling</em> (a guest, opening one stream to a host). The Minecraft bridge sits
 * entirely on top of this interface and knows nothing about how a route was found -- that
 * separation is what lets NAT traversal and relay fallback arrive later without touching
 * the game integration.
 *
 * <h2>Lifecycle</h2>
 * A transport owns every stream it created. {@link #close()} closes them all and releases
 * every resource the transport allocated, and is idempotent.
 */
public interface PeerTransport {

    /** The route streams from this transport will report. */
    TransportRoute route();

    /**
     * Whether the transport is open and able to create new streams.
     */
    boolean isOpen();

    /**
     * Closes the transport and every stream it owns.
     *
     * <p>Idempotent and non-throwing. The returned stage completes once all owned
     * resources have actually been released, which is what the leak check asserts on.
     */
    CompletionStage<Void> close();

    /**
     * A transport that accepts inbound streams from guests.
     */
    interface Listener extends PeerTransport {

        /**
         * Begins accepting streams, handing each new one to {@code acceptor}.
         *
         * <p>The acceptor runs on an I/O thread and must not block. It is responsible for
         * installing a {@link PeerStreamHandler} on the stream, or closing it if the guest
         * is not admitted.
         *
         * @return a stage completing once the transport is ready to accept
         */
        CompletionStage<Void> listen(StreamAcceptor acceptor);

        /**
         * A description of where guests should connect, meaningful for this route --
         * for a TCP listener, the bound address.
         */
        String endpointDescription();

        /**
         * The local address this listener accepts on, or {@code null} if it is not bound
         * yet or the route has no meaningful socket address.
         */
        java.net.SocketAddress localAddress();

        /**
         * Stops accepting new streams while leaving already-accepted streams alive.
         *
         * <p>This is how a single-use listener is retired the moment its one expected
         * stream arrives; {@link #close()} remains the full teardown that also closes the
         * accepted streams. Idempotent.
         */
        void stopAccepting();
    }

    /**
     * A transport that opens a stream to a host.
     */
    interface Dialer extends PeerTransport {

        /**
         * Opens a single stream to the host.
         *
         * <p>The returned stage completes exceptionally with a {@link TransportException}
         * carrying a {@link TransportFailure} category, so the UI can show an actionable
         * message rather than a stack trace.
         */
        CompletionStage<PeerStream> open();
    }

    /**
     * Receives streams accepted by a {@link Listener}.
     */
    @FunctionalInterface
    interface StreamAcceptor {
        void accepted(PeerStream stream);
    }
}
