/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport.tcp;

import dev.nexus.transport.PeerStream;
import dev.nexus.transport.PeerStreamHandler;
import dev.nexus.transport.PeerTransport;
import dev.nexus.transport.TransportException;
import dev.nexus.transport.TransportFailure;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@Timeout(30)
class TcpTransportTest {

    private final List<PeerTransport> transports = new ArrayList<>();

    private TcpTransport.TcpListener listener() {
        TcpTransport.TcpListener l = TcpTransport.listen(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        transports.add(l);
        return l;
    }

    private PeerTransport.Dialer dialer(InetSocketAddress target) {
        PeerTransport.Dialer d = TcpTransport.dial(target);
        transports.add(d);
        return d;
    }

    @AfterEach
    void closeAll() throws Exception {
        for (PeerTransport t : transports) {
            t.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        // The leak contract: once close() completes, no nexus-tcp threads survive.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && anyNexusTcpThreadAlive()) {
            Thread.sleep(50);
        }
        assertThat(anyNexusTcpThreadAlive())
                .as("no nexus-tcp threads may survive transport close")
                .isFalse();
    }

    private static boolean anyNexusTcpThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().startsWith("nexus-tcp-"));
    }

    /** Collects received bytes and lifecycle events for assertions. */
    private static final class Collector implements PeerStreamHandler {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Throwable> closeCause = new AtomicReference<>();
        volatile CountDownLatch expected;

        void expectBytes(int count) {
            expected = new CountDownLatch(count);
        }

        @Override
        public synchronized void dataReceived(PeerStream stream, ByteBuf data) {
            try {
                int n = data.readableBytes();
                byte[] bytes = new byte[n];
                data.readBytes(bytes);
                received.writeBytes(bytes);
                CountDownLatch latch = expected;
                if (latch != null) {
                    for (int i = 0; i < n; i++) {
                        latch.countDown();
                    }
                }
            } finally {
                data.release();
            }
        }

        @Override
        public void closed(PeerStream stream, Throwable cause) {
            closeCause.set(cause);
            closed.countDown();
        }

        synchronized byte[] bytes() {
            return received.toByteArray();
        }
    }

    private record Pair(PeerStream host, PeerStream guest) {
    }

    private Pair connect(TcpTransport.TcpListener l, Collector hostCollector,
                         Collector guestCollector) throws Exception {
        CompletableFuture<PeerStream> accepted = new CompletableFuture<>();
        l.listen(accepted::complete).toCompletableFuture().get(5, TimeUnit.SECONDS);

        InetSocketAddress addr = (InetSocketAddress) l.boundAddress();
        PeerStream guest = dialer(addr).open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        PeerStream host = accepted.get(5, TimeUnit.SECONDS);

        host.handler(hostCollector);
        guest.handler(guestCollector);
        return new Pair(host, guest);
    }

    @Test
    void bytesArriveIntactAndInOrderInBothDirections() throws Exception {
        Collector atHost = new Collector();
        Collector atGuest = new Collector();
        Pair pair = connect(listener(), atHost, atGuest);

        byte[] toHost = "hello from guest".getBytes();
        byte[] toGuest = "hello from host".getBytes();
        atHost.expectBytes(toHost.length);
        atGuest.expectBytes(toGuest.length);

        pair.guest().write(Unpooled.wrappedBuffer(toHost));
        pair.host().write(Unpooled.wrappedBuffer(toGuest));

        assertThat(atHost.expected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atGuest.expected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atHost.bytes()).isEqualTo(toHost);
        assertThat(atGuest.bytes()).isEqualTo(toGuest);
    }

    @Test
    void aLargeTransferSurvivesFragmentationIntact() throws Exception {
        // 4 MiB across many small writes: forces segmentation/coalescing so any framing
        // assumption in the transport would corrupt the reassembled stream.
        Collector atHost = new Collector();
        Collector atGuest = new Collector();
        Pair pair = connect(listener(), atHost, atGuest);

        byte[] payload = new byte[4 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        atHost.expectBytes(payload.length);

        int chunk = 8192;
        for (int off = 0; off < payload.length; off += chunk) {
            int len = Math.min(chunk, payload.length - off);
            pair.guest().write(Unpooled.wrappedBuffer(payload, off, len));
        }

        assertThat(atHost.expected.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(atHost.bytes()).isEqualTo(payload);
    }

    @Test
    void writabilityDropsUnderLoadWhenTheReaderIsPaused() throws Exception {
        Collector atHost = new Collector();
        Collector atGuest = new Collector();
        Pair pair = connect(listener(), atHost, atGuest);

        // Stop the host from reading: the guest's writes must eventually stop being
        // accepted for free instead of buffering without bound.
        pair.host().setReadEnabled(false);

        boolean sawUnwritable = false;
        byte[] block = new byte[64 * 1024];
        for (int i = 0; i < 256 && !sawUnwritable; i++) {
            pair.guest().write(Unpooled.wrappedBuffer(block));
            sawUnwritable = !pair.guest().isWritable();
        }
        assertThat(sawUnwritable)
                .as("writing 16 MiB at a paused reader must trip the high-water mark")
                .isTrue();

        // Draining restores writability -- the signal a pump uses to resume its source.
        pair.host().setReadEnabled(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !pair.guest().isWritable()) {
            Thread.sleep(20);
        }
        assertThat(pair.guest().isWritable()).isTrue();
    }

    @Test
    void closingOneEndClosesTheOtherAndCompletesCloseFutures() throws Exception {
        Collector atHost = new Collector();
        Collector atGuest = new Collector();
        Pair pair = connect(listener(), atHost, atGuest);

        pair.guest().close();

        assertThat(atHost.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atGuest.closed.await(5, TimeUnit.SECONDS)).isTrue();
        pair.host().closeFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pair.guest().closeFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(pair.host().isOpen()).isFalse();
        assertThat(pair.guest().isOpen()).isFalse();

        // An orderly close is not an error.
        assertThat(atGuest.closeCause.get()).isNull();
    }

    @Test
    void dialingAClosedPortFailsWithHostUnreachable() throws Exception {
        // Bind-then-close to get a port that is very likely unoccupied.
        TcpTransport.TcpListener l = TcpTransport.listen(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        l.listen(s -> {
        }).toCompletableFuture().get(5, TimeUnit.SECONDS);
        InetSocketAddress addr = (InetSocketAddress) l.boundAddress();
        l.close().toCompletableFuture().get(5, TimeUnit.SECONDS);

        PeerTransport.Dialer d = dialer(addr);
        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> d.open().toCompletableFuture().get(10, TimeUnit.SECONDS))
                .withCauseInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(
                        ((TransportException) e.getCause()).failure())
                        .isEqualTo(TransportFailure.HOST_UNREACHABLE));
    }

    @Test
    void closingTheListenerClosesAcceptedStreams() throws Exception {
        Collector atHost = new Collector();
        Collector atGuest = new Collector();
        TcpTransport.TcpListener l = listener();
        Pair pair = connect(l, atHost, atGuest);

        l.close().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // The transport owns its streams: closing it must close them.
        assertThat(atGuest.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pair.host().isOpen()).isFalse();
    }

    @Test
    void installingASecondHandlerIsRejected() throws Exception {
        Collector atHost = new Collector();
        Collector atGuest = new Collector();
        Pair pair = connect(listener(), atHost, atGuest);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> pair.guest().handler(new Collector()));
    }
}
