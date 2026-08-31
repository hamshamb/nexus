/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import dev.nexus.session.protocol.Protocol;
import dev.nexus.transport.PeerStream;
import dev.nexus.transport.PeerStreamHandler;
import dev.nexus.transport.TransportException;
import dev.nexus.transport.TransportFailure;
import dev.nexus.transport.TransportRoute;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The on-stream admission preamble: the very first bytes a guest sends are its
 * capability token, and no byte reaches the Minecraft bridge until the host has
 * verified it.
 *
 * <p>Wire format (guest to host, once, before anything else):
 * <pre>  "NXSA"  u16 length  UTF-8 capability token   (length ≤ 512)</pre>
 * There is no response frame: an accepted guest simply starts receiving Minecraft
 * bytes; a rejected guest sees the stream close.
 *
 * <p>The host side returns a <em>handoff stream</em>: a {@link PeerStream} view of the
 * same connection whose data begins exactly one byte after the preamble, so the
 * existing {@code StreamPump} bridges it unchanged. While the handoff waits for its
 * consumer, reads are disabled -- backpressure, not buffering.
 */
public final class AdmissionHandshake {

    private static final byte[] MAGIC = "NXSA".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = MAGIC.length + 2;

    private AdmissionHandshake() {
    }

    /** Guest side: sends the capability preamble. Returns the stream's write future. */
    public static CompletionStage<Void> sendCapability(PeerStream stream, String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (tokenBytes.length > Protocol.MAX_CAPABILITY_BYTES) {
            throw new IllegalArgumentException("capability token too large");
        }
        ByteBuf frame = Unpooled.buffer(HEADER_BYTES + tokenBytes.length);
        frame.writeBytes(MAGIC);
        frame.writeShort(tokenBytes.length);
        frame.writeBytes(tokenBytes);
        return stream.write(frame);
    }

    /** A verified preamble: the token, and the stream with the preamble consumed. */
    public record Admitted(String capabilityToken, PeerStream stream) {
    }

    /**
     * Host side: reads the preamble off {@code raw}, then hands back a stream whose
     * data starts after it.
     *
     * <p>Fails (and closes {@code raw}) on a malformed or oversized preamble, on
     * disconnect, or after {@code timeout} -- a guest that connects and never sends its
     * capability may not hold host resources.
     */
    public static CompletionStage<Admitted> readCapability(PeerStream raw,
                                                           ScheduledExecutorService timer,
                                                           Duration timeout) {
        HandoffStream handoff = new HandoffStream(raw);
        CompletableFuture<Admitted> result = handoff.admitted;
        ScheduledFuture<?> deadline = timer.schedule(() -> {
            if (!result.isDone()) {
                result.completeExceptionally(new TransportException(
                        TransportFailure.TIMED_OUT, "guest sent no capability"));
                raw.close();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete((v, t) -> deadline.cancel(false));
        raw.handler(handoff);
        return result;
    }

    /**
     * The stream view handed to the bridge once the preamble is consumed.
     *
     * <p>It is the {@code raw} stream's one handler; it parses the preamble, pauses
     * reads, and forwards everything after the preamble to whichever handler the
     * bridge later installs -- including a close that happens in between, delivered
     * exactly once.
     */
    private static final class HandoffStream implements PeerStream, PeerStreamHandler {

        private final PeerStream raw;
        private final CompletableFuture<Admitted> admitted = new CompletableFuture<>();
        private final ByteArrayOutputStream preamble = new ByteArrayOutputStream();

        private PeerStreamHandler outer;
        private ByteArrayOutputStream residual = new ByteArrayOutputStream();
        private boolean parsed;
        /** True once every buffered byte has been handed to {@code outer}, in order. */
        private boolean flushed;
        private boolean closedDelivered;
        private Throwable closeCause;
        private boolean closeSeen;

        HandoffStream(PeerStream raw) {
            this.raw = raw;
        }

        // -------------------------------------------------- raw's handler side

        @Override
        public void dataReceived(PeerStream stream, ByteBuf data) {
            try {
                PeerStreamHandler direct;
                synchronized (this) {
                    if (!parsed) {
                        int take = Math.min(data.readableBytes(),
                                HEADER_BYTES + Protocol.MAX_CAPABILITY_BYTES + 1
                                        - preamble.size());
                        byte[] chunk = new byte[take];
                        data.readBytes(chunk);
                        preamble.writeBytes(chunk);
                        tryParse(data);
                        return;
                    }
                    if (outer == null || !flushed) {
                        // A buffer already in flight when reads were paused, or racing
                        // the hand-over flush: keep ordering by joining the queue.
                        byte[] chunk = new byte[data.readableBytes()];
                        data.readBytes(chunk);
                        residual.writeBytes(chunk);
                        return;
                    }
                    direct = outer;
                }
                data.retain();
                direct.dataReceived(this, data);
            } finally {
                data.release();
            }
        }

        /** Caller holds the monitor. {@code remainder} may hold bytes past our take. */
        private void tryParse(ByteBuf remainder) {
            byte[] bytes = preamble.toByteArray();
            if (bytes.length < HEADER_BYTES) {
                return;
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (bytes[i] != MAGIC[i]) {
                    fail("bad admission magic");
                    return;
                }
            }
            int length = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
            if (length > Protocol.MAX_CAPABILITY_BYTES) {
                fail("oversized capability");
                return;
            }
            if (bytes.length < HEADER_BYTES + length) {
                if (preamble.size() > HEADER_BYTES + Protocol.MAX_CAPABILITY_BYTES) {
                    fail("preamble overrun");
                }
                return;
            }
            String token = new String(bytes, HEADER_BYTES, length, StandardCharsets.UTF_8);

            // Anything after the frame -- ours from the accumulator plus whatever is
            // left in the current buffer -- belongs to Minecraft.
            residual.write(bytes, HEADER_BYTES + length, bytes.length - HEADER_BYTES - length);
            byte[] tail = new byte[remainder.readableBytes()];
            remainder.readBytes(tail);
            residual.writeBytes(tail);
            parsed = true;

            // Backpressure, not buffering: nothing more is read until the bridge is
            // attached and re-enables reads.
            raw.setReadEnabled(false);
            admitted.complete(new Admitted(token, this));
        }

        private void fail(String why) {
            admitted.completeExceptionally(new TransportException(
                    TransportFailure.PROTOCOL_VIOLATION, why));
            raw.close();
        }

        @Override
        public void writabilityChanged(PeerStream stream) {
            PeerStreamHandler h = outerHandler();
            if (h != null) {
                h.writabilityChanged(this);
            }
        }

        @Override
        public void closed(PeerStream stream, Throwable cause) {
            admitted.completeExceptionally(cause != null
                    ? cause
                    : new TransportException(TransportFailure.CONNECTION_LOST,
                            "closed during admission"));
            PeerStreamHandler h;
            synchronized (this) {
                closeSeen = true;
                closeCause = cause;
                h = deliverableCloseLocked();
            }
            if (h != null) {
                h.closed(this, cause);
            }
        }

        private synchronized PeerStreamHandler outerHandler() {
            return outer;
        }

        /**
         * Caller holds the monitor. A close is deliverable only once the buffered data
         * has been flushed, so the consumer never sees close-before-data.
         */
        private PeerStreamHandler deliverableCloseLocked() {
            if (closeSeen && outer != null && flushed && !closedDelivered) {
                closedDelivered = true;
                return outer;
            }
            return null;
        }

        // -------------------------------------------------- PeerStream view

        @Override
        public void handler(PeerStreamHandler h) {
            Objects.requireNonNull(h, "handler");
            synchronized (this) {
                if (outer != null) {
                    throw new IllegalStateException("handler already installed");
                }
                outer = h;
            }
            // Drain buffered bytes to the consumer in order. New arrivals racing this
            // loop keep joining the buffer until, under the lock, it is seen empty --
            // only then does the direct path open.
            PeerStreamHandler toClose;
            while (true) {
                byte[] pending;
                synchronized (this) {
                    if (residual.size() == 0) {
                        flushed = true;
                        toClose = deliverableCloseLocked();
                        break;
                    }
                    pending = residual.toByteArray();
                    residual = new ByteArrayOutputStream();
                }
                h.dataReceived(this, Unpooled.wrappedBuffer(pending));
            }
            if (toClose != null) {
                toClose.closed(this, closeCause);
            } else {
                raw.setReadEnabled(true);
            }
        }

        @Override
        public String id() {
            return raw.id() + "+admitted";
        }

        @Override
        public TransportRoute route() {
            return raw.route();
        }

        @Override
        public String remoteDescription() {
            return raw.remoteDescription();
        }

        @Override
        public java.net.SocketAddress remoteAddress() {
            return raw.remoteAddress();
        }

        @Override
        public CompletionStage<Void> write(ByteBuf data) {
            return raw.write(data);
        }

        @Override
        public boolean isWritable() {
            return raw.isWritable();
        }

        @Override
        public void setReadEnabled(boolean enabled) {
            raw.setReadEnabled(enabled);
        }

        @Override
        public boolean isOpen() {
            return raw.isOpen();
        }

        @Override
        public void close() {
            raw.close();
        }

        @Override
        public CompletionStage<Void> closeFuture() {
            return raw.closeFuture();
        }
    }
}
