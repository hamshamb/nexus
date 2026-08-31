/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import io.netty.buffer.ByteBuf;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pumps bytes bidirectionally between two {@link PeerStream}s until either closes.
 *
 * <p>This one class is the whole connection bridge: on the host it joins a guest's
 * transport stream to a loopback connection into the integrated server, and on the guest
 * it joins the vanilla client's loopback connection to the transport stream. It contains
 * the project's flow-control law:
 *
 * <ul>
 *   <li>data received on one stream is written to the other;</li>
 *   <li>when a sink stops being writable, its source stops reading — backpressure
 *       propagates to the remote peer instead of accumulating in memory;</li>
 *   <li>when the sink drains, the source resumes;</li>
 *   <li>either side closing (or failing) closes both, exactly once.</li>
 * </ul>
 */
public final class StreamPump {

    private final PeerStream left;
    private final PeerStream right;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private StreamPump(PeerStream left, PeerStream right) {
        this.left = left;
        this.right = right;
    }

    /**
     * Starts pumping between {@code left} and {@code right}.
     *
     * <p>Both streams must be freshly connected with no handler installed; the pump
     * becomes their handler. From this point the pump owns both streams and closing
     * either through any path tears the pair down.
     */
    public static StreamPump between(PeerStream left, PeerStream right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        StreamPump pump = new StreamPump(left, right);
        left.handler(pump.new Side(right));
        right.handler(pump.new Side(left));
        return pump;
    }

    /**
     * Completes when both streams have closed. Completes exceptionally if the pump ended
     * because a stream failed.
     */
    public CompletionStage<Void> done() {
        return done;
    }

    /** Closes both streams. Idempotent. */
    public void close() {
        closeBoth(null);
    }

    private void closeBoth(Throwable cause) {
        if (closed.compareAndSet(false, true)) {
            left.close();
            right.close();
            // done() completes only when BOTH close futures settle, so "pump finished"
            // reliably means "all pump resources released" for the leak check.
            CompletableFuture<Void> l = left.closeFuture().toCompletableFuture()
                    .handle((v, t) -> null);
            CompletableFuture<Void> r = right.closeFuture().toCompletableFuture()
                    .handle((v, t) -> null);
            CompletableFuture.allOf(l, r).whenComplete((v, t) -> {
                if (cause != null) {
                    done.completeExceptionally(cause);
                } else {
                    done.complete(null);
                }
            });
        }
    }

    /** Handles one direction: everything received from this side goes to {@code sink}. */
    private final class Side implements PeerStreamHandler {

        private final PeerStream sink;

        Side(PeerStream sink) {
            this.sink = sink;
        }

        @Override
        public void dataReceived(PeerStream source, ByteBuf data) {
            // write() takes ownership of the buffer on every path.
            sink.write(data).whenComplete((v, t) -> {
                if (t != null) {
                    closeBoth(t);
                }
            });
            // The flow-control law: a congested sink pauses the source at the source,
            // pushing backpressure to the remote peer rather than buffering here.
            if (!sink.isWritable()) {
                source.setReadEnabled(false);
            }
        }

        @Override
        public void writabilityChanged(PeerStream source) {
            // 'source' here is the stream whose writability changed -- i.e. the one this
            // handler is installed on. When it drains, its opposite may resume reading.
            if (source.isWritable()) {
                sink.setReadEnabled(true);
            }
        }

        @Override
        public void closed(PeerStream source, Throwable cause) {
            closeBoth(cause);
        }
    }
}
