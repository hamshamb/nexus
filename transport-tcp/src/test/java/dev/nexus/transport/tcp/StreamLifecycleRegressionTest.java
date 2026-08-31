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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Review-driven regression tests for two lifecycle races:
 *
 * <ol>
 *   <li>a stream whose peer disappears <em>before</em> a handler is installed must still
 *       deliver {@code closed()} to the eventual handler, exactly once -- and a pump
 *       built on such a stream must still complete. (A handlerless stream keeps reads
 *       disabled, so the disconnect only becomes observable when the handler arrives;
 *       delivery at installation is exactly what these tests pin.)</li>
 *   <li>a retired single-use listener ({@code stopAccepting}) must refuse new
 *       connections while its already-accepted stream stays alive.</li>
 * </ol>
 */
@Timeout(30)
class StreamLifecycleRegressionTest {

    private final List<PeerTransport> transports = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (PeerTransport t : transports) {
            t.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

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

    private static final class CloseCounter implements PeerStreamHandler {
        final AtomicInteger closedCalls = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void dataReceived(PeerStream stream, ByteBuf data) {
            data.release();
        }

        @Override
        public void closed(PeerStream stream, Throwable cause) {
            closedCalls.incrementAndGet();
            closed.countDown();
        }
    }

    @Test
    void handlerInstalledAfterPeerDisconnectStillObservesCloseExactlyOnce() throws Exception {
        TcpTransport.TcpListener l = listener();
        CompletableFuture<PeerStream> acceptedFuture = new CompletableFuture<>();
        l.listen(acceptedFuture::complete).toCompletableFuture().get(5, TimeUnit.SECONDS);

        PeerStream guest = dialer((InetSocketAddress) l.boundAddress())
                .open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        PeerStream accepted = acceptedFuture.get(5, TimeUnit.SECONDS);

        // Peer vanishes while the accepted stream has no handler yet. The guest's own
        // closeFuture confirms its FIN is on the wire. Note the accepted stream cannot
        // even observe the FIN yet: with no handler its reads are disabled by design --
        // installing the handler is what lets the close be detected and delivered.
        guest.close();
        guest.closeFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);

        CloseCounter late = new CloseCounter();
        accepted.handler(late);

        assertThat(late.closed.await(5, TimeUnit.SECONDS))
                .as("a late handler must still learn the stream is dead")
                .isTrue();
        // Give any racing duplicate delivery a moment to happen, then assert once.
        Thread.sleep(100);
        assertThat(late.closedCalls.get()).isEqualTo(1);
    }

    @Test
    void pumpBuiltOnAnAlreadyDeadStreamStillCompletes() throws Exception {
        TcpTransport.TcpListener l = listener();
        CompletableFuture<PeerStream> acceptedFuture = new CompletableFuture<>();
        l.listen(acceptedFuture::complete).toCompletableFuture().get(5, TimeUnit.SECONDS);

        PeerStream guest = dialer((InetSocketAddress) l.boundAddress())
                .open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        PeerStream accepted = acceptedFuture.get(5, TimeUnit.SECONDS);

        guest.close();
        guest.closeFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Second live pair to be the pump's other side.
        TcpTransport.TcpListener l2 = listener();
        CompletableFuture<PeerStream> accepted2 = new CompletableFuture<>();
        l2.listen(accepted2::complete).toCompletableFuture().get(5, TimeUnit.SECONDS);
        PeerStream live = dialer((InetSocketAddress) l2.boundAddress())
                .open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        accepted2.get(5, TimeUnit.SECONDS).handler(new CloseCounter());

        // Without the late-close fix this pump's done() never completes.
        StreamPump pump = StreamPump.between(accepted, live);
        pump.done().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(live.isOpen()).isFalse();
    }

    @Test
    void retiredListenerRefusesSecondConnectionButKeepsTheFirstAlive() throws Exception {
        // The guest-loopback pattern: accept exactly one, then stopAccepting.
        TcpTransport.TcpListener l = listener();
        CompletableFuture<PeerStream> firstFuture = new CompletableFuture<>();
        AtomicInteger accepts = new AtomicInteger();
        l.listen(stream -> {
            accepts.incrementAndGet();
            l.stopAccepting();
            firstFuture.complete(stream);
        }).toCompletableFuture().get(5, TimeUnit.SECONDS);
        InetSocketAddress addr = (InetSocketAddress) l.boundAddress();

        PeerStream firstClient = dialer(addr).open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        PeerStream firstAccepted = firstFuture.get(5, TimeUnit.SECONDS);

        // A second local connection attempt must fail at the TCP level: the accepting
        // socket no longer exists.
        PeerTransport.Dialer second = dialer(addr);
        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> second.open().toCompletableFuture().get(10, TimeUnit.SECONDS));

        // ...while the claimed connection still carries data both ways.
        CloseCounter atServer = new CloseCounter();
        CloseCounter atClient = new CloseCounter();
        firstAccepted.handler(atServer);
        firstClient.handler(atClient);
        assertThat(firstAccepted.isOpen()).isTrue();
        assertThat(firstClient.isOpen()).isTrue();
        assertThat(accepts.get()).isEqualTo(1);
    }
}
