/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport.tcp;

import dev.nexus.transport.PeerStream;
import dev.nexus.transport.PeerTransport;
import dev.nexus.transport.TransportException;
import dev.nexus.transport.TransportFailure;
import dev.nexus.transport.TransportRoute;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plain-TCP {@link PeerTransport} implementations.
 *
 * <p>This is the LAN / direct-address route ({@link TransportRoute#DIRECT_TCP}) and the
 * transport the automated test-suite runs against. It carries no transport-layer
 * encryption -- Minecraft's own login encryption still applies -- which
 * {@link TransportRoute#isEncrypted()} reports honestly to the UI.
 *
 * <p>Each transport owns its own {@link EventLoopGroup} of daemon threads. Nexus never
 * schedules work on Minecraft's event loops, and a leaked group can never keep the JVM
 * alive.
 */
public final class TcpTransport {

    /**
     * Watermarks controlling {@link PeerStream#isWritable()}: writability turns off at
     * 256 KiB queued and back on at 64 KiB. Small enough to cap per-guest memory during
     * chunk streaming, large enough not to throttle normal play.
     */
    private static final WriteBufferWaterMark WATERMARKS =
            new WriteBufferWaterMark(64 * 1024, 256 * 1024);

    private static final AtomicLong STREAM_IDS = new AtomicLong();

    private TcpTransport() {
    }

    /** Creates a listener that will accept guest streams on {@code bindAddress}. */
    public static TcpListener listen(InetSocketAddress bindAddress) {
        return new TcpListener(bindAddress);
    }

    /** Creates a dialer that will open one stream to {@code hostAddress}. */
    public static PeerTransport.Dialer dial(InetSocketAddress hostAddress) {
        return new TcpDialer(hostAddress);
    }

    private static EventLoopGroup newGroup(String label) {
        // Daemon threads: a bug that leaks the group must never keep the JVM alive.
        return new MultiThreadIoEventLoopGroup(
                1, new DefaultThreadFactory("nexus-tcp-" + label, true),
                NioIoHandler.newFactory());
    }

    private static void configure(SocketChannel ch) {
        // Nagle off: Minecraft sends many small packets and 40ms coalescing delays are
        // visible in play.
        ch.config().setOption(ChannelOption.TCP_NODELAY, true);
        // Reading stays off until the stream has a handler; see NettyPeerStream.
        ch.config().setAutoRead(false);
        ch.config().setWriteBufferWaterMark(WATERMARKS);
        ch.config().setOption(ChannelOption.ALLOW_HALF_CLOSURE, false);
    }

    private static CompletableFuture<Void> shutdown(
            EventLoopGroup group, Set<NettyPeerStream> streams) {
        for (NettyPeerStream stream : streams) {
            stream.close();
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        group.shutdownGracefully(0, 3, TimeUnit.SECONDS)
                .addListener(f -> done.complete(null));
        return done;
    }

    /** The host side: accepts inbound guest connections. */
    public static final class TcpListener implements PeerTransport.Listener {

        private final InetSocketAddress bindAddress;
        private final EventLoopGroup group = newGroup("listen");
        private final Set<NettyPeerStream> streams = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        private volatile Channel serverChannel;

        TcpListener(InetSocketAddress bindAddress) {
            this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        }

        @Override
        public CompletionStage<Void> listen(PeerTransport.StreamAcceptor acceptor) {
            Objects.requireNonNull(acceptor, "acceptor");
            CompletableFuture<Void> ready = new CompletableFuture<>();

            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            configure(ch);
                            NettyPeerStream stream = new NettyPeerStream(
                                    "tcp-in-" + STREAM_IDS.incrementAndGet(),
                                    TransportRoute.DIRECT_TCP, ch);
                            streams.add(stream);
                            ch.closeFuture().addListener(f -> streams.remove(stream));
                            acceptor.accepted(stream);
                        }
                    });

            bootstrap.bind(bindAddress).addListener(f -> {
                if (f.isSuccess()) {
                    serverChannel = ((io.netty.channel.ChannelFuture) f).channel();
                    ready.complete(null);
                } else {
                    ready.completeExceptionally(TransportException.wrap(
                            TransportFailure.LOCAL_NETWORK_ERROR, f.cause()));
                }
            });
            return ready;
        }

        /** The actual bound address, available once {@link #listen} has completed. */
        public SocketAddress boundAddress() {
            Channel ch = serverChannel;
            return ch == null ? null : ch.localAddress();
        }

        @Override
        public SocketAddress localAddress() {
            return boundAddress();
        }

        @Override
        public void stopAccepting() {
            // Closes only the server (accepting) channel; accepted child channels live
            // on independently and are still owned and closed by close().
            Channel ch = serverChannel;
            if (ch != null) {
                ch.close();
            }
        }

        @Override
        public String endpointDescription() {
            return String.valueOf(boundAddress());
        }

        @Override
        public TransportRoute route() {
            return TransportRoute.DIRECT_TCP;
        }

        @Override
        public boolean isOpen() {
            Channel ch = serverChannel;
            return !closed.get() && ch != null && ch.isActive();
        }

        @Override
        public CompletionStage<Void> close() {
            if (!closed.compareAndSet(false, true)) {
                // Idempotent: the group's terminationFuture is the same signal for all.
                CompletableFuture<Void> done = new CompletableFuture<>();
                group.terminationFuture().addListener(f -> done.complete(null));
                return done;
            }
            Channel ch = serverChannel;
            if (ch != null) {
                ch.close();
            }
            return shutdown(group, streams);
        }
    }

    /** The guest side: opens one stream to the host. */
    private static final class TcpDialer implements PeerTransport.Dialer {

        private final InetSocketAddress hostAddress;
        private final EventLoopGroup group = newGroup("dial");
        private final Set<NettyPeerStream> streams = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        TcpDialer(InetSocketAddress hostAddress) {
            this.hostAddress = Objects.requireNonNull(hostAddress, "hostAddress");
        }

        @Override
        public CompletionStage<PeerStream> open() {
            CompletableFuture<PeerStream> result = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            configure(ch);
                        }
                    });

            bootstrap.connect(hostAddress).addListener(f -> {
                if (f.isSuccess()) {
                    Channel ch = ((io.netty.channel.ChannelFuture) f).channel();
                    NettyPeerStream stream = new NettyPeerStream(
                            "tcp-out-" + STREAM_IDS.incrementAndGet(),
                            TransportRoute.DIRECT_TCP, ch);
                    streams.add(stream);
                    ch.closeFuture().addListener(cf -> streams.remove(stream));
                    result.complete(stream);
                } else {
                    result.completeExceptionally(TransportException.wrap(
                            TransportFailure.HOST_UNREACHABLE, f.cause()));
                }
            });
            return result;
        }

        @Override
        public TransportRoute route() {
            return TransportRoute.DIRECT_TCP;
        }

        @Override
        public boolean isOpen() {
            return !closed.get();
        }

        @Override
        public CompletionStage<Void> close() {
            if (!closed.compareAndSet(false, true)) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                group.terminationFuture().addListener(f -> done.complete(null));
                return done;
            }
            return shutdown(group, streams);
        }
    }
}
