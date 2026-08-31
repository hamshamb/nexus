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
import dev.nexus.transport.TransportException;
import dev.nexus.transport.TransportFailure;
import dev.nexus.transport.TransportRoute;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link PeerStream} backed by a Netty {@link Channel}.
 *
 * <p>Written against {@link Channel} rather than any socket type on purpose: the same
 * class will wrap a QUIC stream channel when the direct-Internet transport arrives, which
 * is what keeps the bridge unchanged across transports.
 *
 * <p>Inbound data is buffered only between construction and {@link #handler} installation
 * -- and then only because the channel's auto-read starts disabled, so nothing is read
 * from the socket until a handler exists. There is deliberately no queue in this class.
 */
final class NettyPeerStream implements PeerStream {

    private final String id;
    private final TransportRoute route;
    private final Channel channel;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final AtomicReference<PeerStreamHandler> handler = new AtomicReference<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    /**
     * Guards the one {@code closed()} callback. Both the channel-close listener and a
     * late {@link #handler} installation race to deliver it: whichever observes both
     * "channel terminated" and "handler present" first wins this flag, so a stream that
     * dies before its handler exists still notifies the eventual handler exactly once.
     */
    private final AtomicBoolean closedDelivered = new AtomicBoolean();

    /** Set once the channel has fully closed; read by the late-handler path. */
    private volatile boolean terminated;

    /** The error that ended the stream, if any, for closeFuture completion. */
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    /**
     * Wraps {@code channel}. The channel must have been created with
     * {@code autoRead=false}; reading starts when a handler is installed.
     */
    NettyPeerStream(String id, TransportRoute route, Channel channel) {
        this.id = Objects.requireNonNull(id, "id");
        this.route = Objects.requireNonNull(route, "route");
        this.channel = Objects.requireNonNull(channel, "channel");

        channel.pipeline().addLast("nexus-stream", new Bridge());
        channel.closeFuture().addListener(f -> {
            Throwable cause = failure.get();
            terminated = true;
            deliverClosedIfReady();
            if (cause != null) {
                closeFuture.completeExceptionally(cause);
            } else {
                closeFuture.complete(null);
            }
        });
    }

    /**
     * Delivers {@code closed()} iff the channel has terminated and a handler exists,
     * at most once. Safe to call from any thread and from both racing sides.
     */
    private void deliverClosedIfReady() {
        if (!terminated) {
            return;
        }
        PeerStreamHandler h = handler.get();
        if (h != null && closedDelivered.compareAndSet(false, true)) {
            h.closed(this, failure.get());
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TransportRoute route() {
        return route;
    }

    @Override
    public java.net.SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public String remoteDescription() {
        return String.valueOf(channel.remoteAddress());
    }

    @Override
    public void handler(PeerStreamHandler h) {
        Objects.requireNonNull(h, "handler");
        if (!handler.compareAndSet(null, h)) {
            throw new IllegalStateException("handler already installed on " + id);
        }
        // Only now does the socket start being read: backpressure applies from byte one,
        // and no data can arrive before anyone is listening.
        channel.config().setAutoRead(true);
        // The stream may already be dead (peer vanished between accept and handler
        // installation). The handler must still learn that, exactly once -- otherwise a
        // pump built on this stream would wait forever.
        deliverClosedIfReady();
    }

    @Override
    public CompletionStage<Void> write(ByteBuf data) {
        Objects.requireNonNull(data, "data");
        CompletableFuture<Void> done = new CompletableFuture<>();
        // writeAndFlush releases the buffer on both success and failure.
        channel.writeAndFlush(data).addListener(f -> {
            if (f.isSuccess()) {
                done.complete(null);
            } else {
                done.completeExceptionally(
                        TransportException.wrap(TransportFailure.CONNECTION_LOST, f.cause()));
            }
        });
        return done;
    }

    @Override
    public boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public void setReadEnabled(boolean enabled) {
        channel.config().setAutoRead(enabled);
    }

    @Override
    public boolean isOpen() {
        return channel.isActive();
    }

    @Override
    public void close() {
        if (closeRequested.compareAndSet(false, true)) {
            channel.close();
        }
    }

    @Override
    public CompletionStage<Void> closeFuture() {
        return closeFuture;
    }

    @Override
    public String toString() {
        return "NettyPeerStream[" + id + ", " + route + "]";
    }

    /** Translates channel events into {@link PeerStreamHandler} callbacks. */
    private final class Bridge extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            PeerStreamHandler h = handler.get();
            if (h == null) {
                // Cannot happen while autoRead stays off until handler installation,
                // but if it ever does, dropping bytes silently would corrupt the stream.
                ((ByteBuf) msg).release();
                exceptionCaught(ctx, new IllegalStateException(
                        "data arrived before a handler was installed on " + id));
                return;
            }
            h.dataReceived(NettyPeerStream.this, (ByteBuf) msg);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            PeerStreamHandler h = handler.get();
            if (h != null) {
                h.writabilityChanged(NettyPeerStream.this);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failure.compareAndSet(null,
                    TransportException.wrap(TransportFailure.CONNECTION_LOST, cause));
            ctx.close();
        }
    }
}
