/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic proof of the pump's flow-control law, with scripted streams and no
 * sockets, threads, or timing:
 *
 * <ul>
 *   <li>data arriving for an unwritable sink disables reads on the source;</li>
 *   <li>the sink's transition back to writable re-enables reads on the source.</li>
 * </ul>
 *
 * <p>The large real-socket transfer in {@code transport-tcp}'s {@code StreamPumpTest}
 * proves the same law end-to-end; this test pins the exact cause-and-effect.
 */
@Timeout(10)
class StreamPumpBackpressureTest {

    /** A fully scripted in-memory PeerStream. */
    private static final class FakeStream implements PeerStream {
        final String name;
        final List<String> events = new ArrayList<>();
        final AtomicReference<PeerStreamHandler> handler = new AtomicReference<>();
        final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean writable = true;
        volatile boolean readEnabled = true;

        FakeStream(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public TransportRoute route() {
            return TransportRoute.DIRECT_TCP;
        }

        @Override
        public String remoteDescription() {
            return name;
        }

        @Override
        public void handler(PeerStreamHandler h) {
            if (!handler.compareAndSet(null, h)) {
                throw new IllegalStateException("handler already installed");
            }
        }

        @Override
        public CompletionStage<Void> write(ByteBuf data) {
            events.add("write:" + data.readableBytes());
            data.release();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void setReadEnabled(boolean enabled) {
            readEnabled = enabled;
            events.add("readEnabled:" + enabled);
        }

        @Override
        public boolean isOpen() {
            return !closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                PeerStreamHandler h = handler.get();
                if (h != null) {
                    h.closed(this, null);
                }
                closeFuture.complete(null);
            }
        }

        @Override
        public CompletionStage<Void> closeFuture() {
            return closeFuture;
        }

        /** Test harness: deliver inbound bytes as the transport would. */
        void deliver(byte[] data) {
            handler.get().dataReceived(this, Unpooled.wrappedBuffer(data));
        }

        /** Test harness: change writability and fire the callback, as Netty would. */
        void setWritable(boolean w) {
            writable = w;
            handler.get().writabilityChanged(this);
        }
    }

    @Test
    void unwritableSinkPausesTheSourceAndWritableTransitionResumesIt() {
        FakeStream source = new FakeStream("source");
        FakeStream sink = new FakeStream("sink");
        StreamPump.between(source, sink);

        // Congest the sink, then deliver data from the source: the pump must forward
        // the bytes and immediately pause the source.
        sink.writable = false;
        source.deliver(new byte[100]);

        assertThat(sink.events).contains("write:100");
        assertThat(source.readEnabled)
                .as("an unwritable sink must disable reads on the source")
                .isFalse();
        assertThat(source.events).contains("readEnabled:false");

        // The sink drains: its writability callback must resume the source.
        sink.setWritable(true);

        assertThat(source.readEnabled)
                .as("a writable transition must re-enable reads on the source")
                .isTrue();
        assertThat(source.events).containsExactly("readEnabled:false", "readEnabled:true");
    }

    @Test
    void backpressureIsSymmetric() {
        FakeStream left = new FakeStream("left");
        FakeStream right = new FakeStream("right");
        StreamPump.between(left, right);

        // Same law in the other direction: right -> left with left congested.
        left.writable = false;
        right.deliver(new byte[42]);

        assertThat(left.events).contains("write:42");
        assertThat(right.readEnabled).isFalse();

        left.setWritable(true);
        assertThat(right.readEnabled).isTrue();
    }

    @Test
    void pumpCompletesWhenBothScriptedStreamsClose() throws Exception {
        FakeStream left = new FakeStream("left");
        FakeStream right = new FakeStream("right");
        StreamPump pump = StreamPump.between(left, right);

        left.close();

        pump.done().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(right.isOpen()).isFalse();
    }
}
