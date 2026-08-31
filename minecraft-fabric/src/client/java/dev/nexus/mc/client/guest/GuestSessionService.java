/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.guest;

import dev.nexus.core.session.GuestSessionState;
import dev.nexus.core.session.SessionMachine;
import dev.nexus.mc.NexusMod;
import dev.nexus.mc.client.NexusConfig;
import dev.nexus.session.client.AdmissionHandshake;
import dev.nexus.session.client.SessionClient;
import dev.nexus.session.client.SessionClientException;
import dev.nexus.session.protocol.Protocol;
import dev.nexus.transport.BridgeTransportFactory;
import dev.nexus.transport.PeerStream;
import dev.nexus.transport.PeerTransport;
import dev.nexus.transport.StreamPump;
import dev.nexus.transport.TransportException;
import dev.nexus.transport.TransportFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Joins a hosted world: opens the transport stream to the host, then hands the vanilla
 * client a loopback address that routes into that stream.
 *
 * <p>The vanilla client connects through its own public front door
 * ({@link ConnectScreen#startConnecting}), so its connection is a genuine TCP channel and
 * the complete vanilla login path -- encryption, Mojang session auth, compression --
 * runs unmodified. Nexus never touches the client's network pipeline.
 *
 * <p>The loopback listener exists for exactly one connection: the first accept claims it
 * atomically and immediately retires the listener ({@code stopAccepting}), so no second
 * local connection can ever reach the host stream; a straggler racing the retirement is
 * closed without touching the pump.
 *
 * <p>State semantics: {@code CONNECTED_DIRECT} means the vanilla client is bridged
 * through the tunnel -- the transport stream alone is only {@code CONNECTING_DIRECT}.
 * The Minecraft login/play session on top is vanilla's own and is reported by vanilla's
 * screens; an abnormal failure of the bridged stream ends the session {@code FAILED},
 * never {@code DISCONNECTED}.
 *
 * <p>Nothing here blocks the render thread or a transport event loop.
 */
public final class GuestSessionService {

    private static final AtomicReference<GuestSessionService> CURRENT = new AtomicReference<>();

    private final SessionMachine<GuestSessionState> machine = SessionMachine.forGuest();
    private final AtomicReference<PeerTransport.Listener> loopbackListener = new AtomicReference<>();
    private final AtomicReference<PeerTransport.Dialer> hostDialer = new AtomicReference<>();
    private final AtomicReference<StreamPump> pump = new AtomicReference<>();
    private final AtomicReference<SessionClient> sessionClient = new AtomicReference<>();
    private final AtomicBoolean loopbackClaimed = new AtomicBoolean();

    private GuestSessionService() {
        machine.addListener((from, to, reason) ->
                NexusMod.LOGGER.info("Guest session: {} -> {}{}", from, to,
                        reason == null ? "" : " (" + reason + ")"));
    }

    /** The active guest session, or {@code null}. */
    public static GuestSessionService current() {
        return CURRENT.get();
    }

    /**
     * Joins a hosted world by invite code: resolves the code with the coordination
     * backend, receives the route and a one-time admission capability, connects, and
     * presents the capability before any Minecraft byte flows. Must be called on the
     * client thread; everything network is chained asynchronously.
     *
     * @param parent      the screen to return to on failure
     * @param rawCode     the code exactly as the player typed it
     * @param routeDialer builds the route to an address the backend supplied
     *                    (direct TCP today; replaced wholesale at M4)
     * @param transports  supplies the loopback listener for the vanilla client
     */
    public static void joinByCode(Screen parent, String rawCode,
                                  Function<InetSocketAddress, PeerTransport.Dialer> routeDialer,
                                  BridgeTransportFactory transports) {
        GuestSessionService service = new GuestSessionService();
        GuestSessionService previous = CURRENT.getAndSet(service);
        if (previous != null) {
            previous.close("superseded by a new join");
        }
        service.doJoinByCode(parent, rawCode, routeDialer, transports);
    }

    private void doJoinByCode(Screen parent, String rawCode,
                              Function<InetSocketAddress, PeerTransport.Dialer> routeDialer,
                              BridgeTransportFactory transports) {
        Minecraft minecraft = Minecraft.getInstance();
        SessionClient client = new SessionClient(NexusConfig.backendUrl());
        sessionClient.set(client);

        // RESOLVING: turn the code into a session, route, and capability. The grant's
        // route data is re-validated inside join() -- see RoutePolicy.
        client.join(rawCode, NexusConfig.routeMode()).whenComplete((grant, error) -> {
            if (error != null) {
                fail(parent, minecraft, unwrap(error));
                return;
            }
            machine.transitionTo(GuestSessionState.NEGOTIATING, "invite resolved");
            machine.transitionTo(GuestSessionState.CONNECTING_DIRECT, null);
            tryAddresses(parent, minecraft, grant, routeDialer, transports, 0, null);
        });
    }

    /** Tries the granted addresses in order until one dials, then proceeds. */
    private void tryAddresses(Screen parent, Minecraft minecraft,
                              SessionClient.JoinGrant grant,
                              Function<InetSocketAddress, PeerTransport.Dialer> routeDialer,
                              BridgeTransportFactory transports,
                              int index, Throwable lastError) {
        List<String> addresses = grant.addresses();
        if (index >= addresses.size()) {
            fail(parent, minecraft, lastError != null ? lastError
                    : new TransportException(TransportFailure.HOST_UNREACHABLE,
                            "no address was reachable"));
            return;
        }
        PeerTransport.Dialer dialer = routeDialer.apply(
                InetSocketAddress.createUnresolved(addresses.get(index), grant.port()));
        PeerTransport.Dialer previous = hostDialer.getAndSet(dialer);
        if (previous != null) {
            previous.close();
        }
        dialer.open().whenComplete((hostStream, error) -> {
            if (error != null) {
                NexusMod.LOGGER.info("Address {} of {} unreachable, trying next",
                        index + 1, addresses.size());
                tryAddresses(parent, minecraft, grant, routeDialer, transports,
                        index + 1, error);
                return;
            }
            // Capability first: no Minecraft byte crosses before it.
            AdmissionHandshake.sendCapability(hostStream, grant.capabilityToken())
                    .thenCompose(v -> startLoopback(minecraft, parent, hostStream, transports))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            hostStream.close();
                            fail(parent, minecraft, unwrap(e));
                        }
                    });
        });
    }

    private static Throwable unwrap(Throwable error) {
        return (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null ? error.getCause() : error;
    }

    /**
     * Brings up the single-use loopback listener and points the vanilla client at it.
     * Runs on a transport I/O thread; every step is non-blocking.
     */
    private CompletableFuture<Void> startLoopback(Minecraft minecraft, Screen parent,
                                                  PeerStream hostStream,
                                                  BridgeTransportFactory transports) {
        PeerTransport.Listener listener = transports.createLoopbackListener();
        loopbackListener.set(listener);

        return listener.listen(clientStream -> acceptVanillaClient(listener, clientStream, hostStream))
                .thenRun(() -> {
                    if (!(listener.localAddress() instanceof InetSocketAddress bound)) {
                        throw new TransportException(TransportFailure.INTERNAL_ERROR,
                                "loopback listener has no bound address");
                    }
                    int port = bound.getPort();
                    minecraft.execute(() -> {
                        ServerData data = new ServerData("Nexus Hosted World",
                                "127.0.0.1:" + port, ServerData.Type.OTHER);
                        ConnectScreen.startConnecting(parent, minecraft,
                                new ServerAddress("127.0.0.1", port), data, false, null);
                    });
                })
                .toCompletableFuture();
    }

    /**
     * Claims the one expected vanilla-client connection. Runs on the listener's I/O
     * thread. The claim is atomic: exactly one caller ever constructs the pump or
     * touches {@code hostStream}; everyone else is closed and the listener is already
     * retired by then.
     */
    private void acceptVanillaClient(PeerTransport.Listener listener,
                                     PeerStream clientStream, PeerStream hostStream) {
        if (!loopbackClaimed.compareAndSet(false, true)) {
            NexusMod.LOGGER.warn("Refusing extra loopback connection {}", clientStream.id());
            clientStream.close();
            return;
        }
        // Retire the listener the moment its purpose is served: nothing else can even
        // complete a TCP handshake to this port from now on.
        listener.stopAccepting();

        StreamPump created = StreamPump.between(clientStream, hostStream);
        pump.set(created);
        created.done().whenComplete((v, t) -> {
            if (t != null) {
                closeAbnormal("bridged stream failed", t);
            } else {
                close("connection ended");
            }
        });
        machine.tryTransitionTo(GuestSessionState.CONNECTED_DIRECT,
                "vanilla client bridged");
        NexusMod.LOGGER.info("Guest bridged: {} <-> {}", clientStream.id(), hostStream.id());
    }

    private void fail(Screen parent, Minecraft minecraft, Throwable error) {
        NexusMod.LOGGER.warn("Join failed: {}", error.toString());
        machine.tryTransitionTo(GuestSessionState.FAILED, error.getClass().getSimpleName());
        releaseResources("join failed");
        String[] text = describeFailure(error);
        minecraft.execute(() -> minecraft.gui.setScreen(
                new dev.nexus.mc.client.ui.NexusFailureScreen(parent, text[0], text[1])));
    }

    /** Maps a failure to the two player-facing sentences: what happened, what it means. */
    private static String[] describeFailure(Throwable error) {
        if (error instanceof SessionClientException sce) {
            return switch (sce.code()) {
                case Protocol.ErrorCode.INVALID_CODE -> new String[]{
                        "That invite code didn't work.",
                        "It may have expired, or the world may no longer be hosted."};
                case Protocol.ErrorCode.RATE_LIMITED -> new String[]{
                        "Too many attempts.",
                        "Wait a moment and try again."};
                case Protocol.ErrorCode.UNSUPPORTED_PROTOCOL -> new String[]{
                        "This version of Nexus is out of date.",
                        "Update Nexus to join hosted worlds."};
                case SessionClientException.TRANSPORT -> new String[]{
                        "Could not reach the Nexus service.",
                        "Check your Internet connection and try again."};
                default -> new String[]{
                        "The Nexus service reported a problem.",
                        "Try again in a moment."};
            };
        }
        TransportFailure failure = error instanceof TransportException te
                ? te.failure() : TransportFailure.INTERNAL_ERROR;
        return new String[]{failure.summary(), failure.detail()};
    }

    /** Current state, for the UI. */
    public GuestSessionState state() {
        return machine.state();
    }

    /**
     * Ends the session normally (the player left, the host stopped, a new join
     * superseded this one). Idempotent, any thread.
     */
    public CompletableFuture<Void> close(String reason) {
        if (!machine.state().isTerminal()) {
            if (!machine.tryTransitionTo(GuestSessionState.DISCONNECTED, reason)) {
                // Not yet connected (e.g. cancelled mid-join): that is a failure to
                // establish, not a normal disconnect.
                machine.tryTransitionTo(GuestSessionState.FAILED, reason);
            }
        }
        return releaseResources(reason);
    }

    /** Ends the session because the established connection failed. */
    private void closeAbnormal(String reason, Throwable cause) {
        TransportFailure failure = cause instanceof TransportException te
                ? te.failure() : TransportFailure.CONNECTION_LOST;
        NexusMod.LOGGER.warn("Guest connection failed ({}): {}", failure, cause.toString());
        machine.tryTransitionTo(GuestSessionState.FAILED, failure.name());
        releaseResources(reason);
    }

    private CompletableFuture<Void> releaseResources(String reason) {
        CURRENT.compareAndSet(this, null);

        StreamPump p = pump.getAndSet(null);
        if (p != null) {
            p.close();
        }
        SessionClient client = sessionClient.getAndSet(null);
        if (client != null) {
            client.close();
        }
        PeerTransport.Listener listener = loopbackListener.getAndSet(null);
        PeerTransport.Dialer dialer = hostDialer.getAndSet(null);
        CompletableFuture<Void> listenerClosed = listener == null
                ? CompletableFuture.completedFuture(null)
                : listener.close().toCompletableFuture();
        CompletableFuture<Void> dialerClosed = dialer == null
                ? CompletableFuture.completedFuture(null)
                : dialer.close().toCompletableFuture();
        return CompletableFuture.allOf(listenerClosed, dialerClosed)
                .whenComplete((v, t) ->
                        NexusMod.LOGGER.info("Guest session closed ({})", reason));
    }
}
