/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import dev.nexus.session.protocol.AdmissionCapability;
import dev.nexus.session.protocol.ReplayGuard;
import dev.nexus.transport.PeerStream;
import dev.nexus.transport.PeerStreamHandler;
import dev.nexus.transport.PeerTransport;
import dev.nexus.transport.StreamPump;
import dev.nexus.transport.TransportException;
import dev.nexus.transport.TransportFailure;
import dev.nexus.transport.tcp.TcpTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The admission preamble over real sockets: the token arrives intact, bytes after the
 * preamble reach the handed-off stream in order, silence times out, and the capability
 * verify + replay-guard flow composes with it end to end.
 */
@Timeout(30)
class AdmissionHandshakeTest {

    private final List<PeerTransport> transports = new ArrayList<>();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "test-timer");
                thread.setDaemon(true);
                return thread;
            });

    @AfterEach
    void closeAll() throws Exception {
        for (PeerTransport transport : transports) {
            transport.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        timer.shutdownNow();
    }

    private record Pair(PeerStream guest, PeerStream accepted) {
    }

    private Pair connect() throws Exception {
        TcpTransport.TcpListener listener = TcpTransport.listen(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        transports.add(listener);
        CompletableFuture<PeerStream> accepted = new CompletableFuture<>();
        listener.listen(accepted::complete).toCompletableFuture().get(5, TimeUnit.SECONDS);
        PeerTransport.Dialer dialer =
                TcpTransport.dial((InetSocketAddress) listener.boundAddress());
        transports.add(dialer);
        PeerStream guest = dialer.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return new Pair(guest, accepted.get(5, TimeUnit.SECONDS));
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
    void tokenArrivesAndBytesAfterThePreambleReachTheHandedOffStreamInOrder() throws Exception {
        Pair pair = connect();
        CompletableFuture<AdmissionHandshake.Admitted> admittedFuture =
                AdmissionHandshake.readCapability(pair.accepted(), timer, Duration.ofSeconds(5))
                        .toCompletableFuture();

        AdmissionHandshake.sendCapability(pair.guest(), "NXC1.s.c.123.mac");
        // Minecraft bytes immediately after the preamble, same connection.
        byte[] gameBytes = "first minecraft bytes".getBytes();
        pair.guest().write(Unpooled.wrappedBuffer(gameBytes));

        AdmissionHandshake.Admitted admitted = admittedFuture.get(5, TimeUnit.SECONDS);
        assertThat(admitted.capabilityToken()).isEqualTo("NXC1.s.c.123.mac");

        Collector consumer = new Collector();
        consumer.expectBytes(gameBytes.length);
        admitted.stream().handler(consumer);
        assertThat(consumer.expected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(consumer.bytes()).isEqualTo(gameBytes);

        // And the handed-off stream still works both ways afterwards.
        Collector atGuest = new Collector();
        atGuest.expectBytes(5);
        pair.guest().handler(atGuest);
        admitted.stream().write(Unpooled.wrappedBuffer("hello".getBytes()));
        assertThat(atGuest.expected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(atGuest.bytes()).isEqualTo("hello".getBytes());
    }

    @Test
    void handedOffStreamComposesWithAStreamPump() throws Exception {
        Pair outer = connect();
        Pair inner = connect();

        CompletableFuture<AdmissionHandshake.Admitted> admittedFuture =
                AdmissionHandshake.readCapability(outer.accepted(), timer, Duration.ofSeconds(5))
                        .toCompletableFuture();
        AdmissionHandshake.sendCapability(outer.guest(), "token");
        AdmissionHandshake.Admitted admitted = admittedFuture.get(5, TimeUnit.SECONDS);

        // Bridge exactly as HostSessionService does: admitted stream <-> inner dial.
        StreamPump pump = StreamPump.between(admitted.stream(), inner.guest());
        Collector atServer = new Collector();
        inner.accepted().handler(atServer);

        byte[] payload = new byte[256 * 1024];
        new SecureRandom().nextBytes(payload);
        atServer.expectBytes(payload.length);
        Collector atGuest = new Collector();
        outer.guest().handler(atGuest);
        outer.guest().write(Unpooled.wrappedBuffer(payload));

        assertThat(atServer.expected.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(atServer.bytes()).isEqualTo(payload);

        outer.guest().close();
        pump.done().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void aSilentGuestTimesOutAndTheStreamIsClosed() throws Exception {
        Pair pair = connect();
        CompletableFuture<AdmissionHandshake.Admitted> admittedFuture =
                AdmissionHandshake.readCapability(pair.accepted(), timer,
                        Duration.ofMillis(300)).toCompletableFuture();

        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> admittedFuture.get(5, TimeUnit.SECONDS))
                .withCauseInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(((TransportException) e.getCause()).failure())
                        .isEqualTo(TransportFailure.TIMED_OUT));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && pair.accepted().isOpen()) {
            Thread.sleep(20);
        }
        assertThat(pair.accepted().isOpen()).isFalse();
    }

    @Test
    void garbageInsteadOfThePreambleIsAProtocolViolation() throws Exception {
        Pair pair = connect();
        CompletableFuture<AdmissionHandshake.Admitted> admittedFuture =
                AdmissionHandshake.readCapability(pair.accepted(), timer, Duration.ofSeconds(5))
                        .toCompletableFuture();

        pair.guest().write(Unpooled.wrappedBuffer("GET / HTTP/1.1\r\n\r\n".getBytes()));

        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> admittedFuture.get(5, TimeUnit.SECONDS))
                .withCauseInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(((TransportException) e.getCause()).failure())
                        .isEqualTo(TransportFailure.PROTOCOL_VIOLATION));
    }

    @Test
    void endToEndCapabilityFlowAdmitsOnceAndRejectsReplayAndWrongSession() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] key = AdmissionCapability.newAdmissionKey(random);
        ReplayGuard guard = new ReplayGuard();
        Instant now = Instant.now();
        String token = AdmissionCapability.mint(key, "sess-1", random, now);

        // First presentation: verify + replay-guard admit.
        assertThat(AdmissionCapability.verify(key, "sess-1", token, now))
                .isEqualTo(AdmissionCapability.Verification.OK);
        assertThat(guard.firstUse(AdmissionCapability.capabilityId(token),
                AdmissionCapability.expiresAt(token), now)).isTrue();

        // Replay of the same token: rejected before any bridge resource is touched.
        assertThat(guard.firstUse(AdmissionCapability.capabilityId(token),
                AdmissionCapability.expiresAt(token), now)).isFalse();

        // Wrong-session capability: rejected by verification alone.
        String foreign = AdmissionCapability.mint(
                AdmissionCapability.newAdmissionKey(random), "sess-2", random, now);
        assertThat(AdmissionCapability.verify(key, "sess-1", foreign, now))
                .isEqualTo(AdmissionCapability.Verification.BAD_SIGNATURE);
    }
}
