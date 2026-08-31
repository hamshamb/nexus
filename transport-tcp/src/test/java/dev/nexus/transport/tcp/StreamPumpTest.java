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
import dev.nexus.transport.StreamPump;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link StreamPump} over real sockets in the exact topology the Minecraft
 * bridge uses: an outer connection, a pump, and an inner loopback connection.
 */
@Timeout(30)
class StreamPumpTest {

    private final List<PeerTransport> transports = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (PeerTransport t : transports) {
            t.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Builds: guest --tcp--> bridge --pump--> tcp --> server.
     * Returns the guest's stream, the server's accepted stream, and the pump.
     */
    private Chain chain() throws Exception {
        InetSocketAddress loop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

        // The "integrated server" end.
        TcpTransport.TcpListener server = TcpTransport.listen(loop);
        transports.add(server);
        CompletableFuture<PeerStream> atServer = new CompletableFuture<>();
        server.listen(atServer::complete).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // The bridge: accept an outer stream, dial the server, pump the two together.
        TcpTransport.TcpListener bridge = TcpTransport.listen(loop);
        transports.add(bridge);
        CompletableFuture<StreamPump> pumpFuture = new CompletableFuture<>();
        bridge.listen(outer -> {
            PeerTransport.Dialer innerDialer =
                    TcpTransport.dial((InetSocketAddress) server.boundAddress());
            transports.add(innerDialer);
            innerDialer.open().whenComplete((inner, t) -> {
                if (t != null) {
                    outer.close();
                    pumpFuture.completeExceptionally(t);
                } else {
                    pumpFuture.complete(StreamPump.between(outer, inner));
                }
            });
        }).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // The "guest" end.
        PeerTransport.Dialer guestDialer =
                TcpTransport.dial((InetSocketAddress) bridge.boundAddress());
        transports.add(guestDialer);
        PeerStream guest = guestDialer.open().toCompletableFuture().get(5, TimeUnit.SECONDS);

        return new Chain(guest, atServer.get(5, TimeUnit.SECONDS),
                pumpFuture.get(5, TimeUnit.SECONDS));
    }

    private record Chain(PeerStream guest, PeerStream server, StreamPump pump) {
    }

    private static final class Collector implements PeerStreamHandler {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final CountDownLatch closed = new CountDownLatch(1);
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
            closed.countDown();
        }

        synchronized byte[] bytes() {
            return received.toByteArray();
        }
    }

    @Test
    void aLargePayloadCrossesThePumpIntactInBothDirections() throws Exception {
        Chain chain = chain();
        Collector atServer = new Collector();
        Collector atGuest = new Collector();
        chain.server().handler(atServer);
        chain.guest().handler(atGuest);

        // 16 MiB guest->server: far past the 256 KiB high-water mark, so this transfer
        // completes only if the pump's backpressure law actually cycles pause/resume.
        byte[] big = new byte[16 * 1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 131);
        }
        atServer.expectBytes(big.length);
        int chunk = 32 * 1024;
        for (int off = 0; off < big.length; off += chunk) {
            chain.guest().write(Unpooled.wrappedBuffer(big, off,
                    Math.min(chunk, big.length - off)));
        }

        byte[] reply = "server says hello".getBytes();
        atGuest.expectBytes(reply.length);
        chain.server().write(Unpooled.wrappedBuffer(reply));

        assertThat(atServer.expected.await(25, TimeUnit.SECONDS)).isTrue();
        assertThat(atGuest.expected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atServer.bytes()).isEqualTo(big);
        assertThat(atGuest.bytes()).isEqualTo(reply);
    }

    @Test
    void closingTheGuestTearsDownTheWholeChain() throws Exception {
        Chain chain = chain();
        Collector atServer = new Collector();
        Collector atGuest = new Collector();
        chain.server().handler(atServer);
        chain.guest().handler(atGuest);

        chain.guest().close();

        assertThat(atServer.closed.await(5, TimeUnit.SECONDS)).isTrue();
        chain.pump().done().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(chain.server().isOpen()).isFalse();
    }

    @Test
    void closingTheServerSideTearsDownTheWholeChain() throws Exception {
        Chain chain = chain();
        Collector atServer = new Collector();
        Collector atGuest = new Collector();
        chain.server().handler(atServer);
        chain.guest().handler(atGuest);

        chain.server().close();

        assertThat(atGuest.closed.await(5, TimeUnit.SECONDS)).isTrue();
        chain.pump().done().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(chain.guest().isOpen()).isFalse();
    }

    @Test
    void closingThePumpClosesBothEnds() throws Exception {
        Chain chain = chain();
        Collector atServer = new Collector();
        Collector atGuest = new Collector();
        chain.server().handler(atServer);
        chain.guest().handler(atGuest);

        chain.pump().close();

        assertThat(atServer.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atGuest.closed.await(5, TimeUnit.SECONDS)).isTrue();
        chain.pump().done().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
