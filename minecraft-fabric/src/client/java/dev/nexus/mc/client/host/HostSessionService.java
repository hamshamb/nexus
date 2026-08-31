/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.host;

import dev.nexus.core.session.HostSessionState;
import dev.nexus.core.session.SessionMachine;
import dev.nexus.mc.NexusMod;
import dev.nexus.mc.client.NexusConfig;
import dev.nexus.mc.mixin.client.IntegratedServerAccessor;
import dev.nexus.mc.mixin.client.ServerConnectionListenerAccessor;
import dev.nexus.session.client.AdmissionGate;
import dev.nexus.session.client.AdmissionHandshake;
import dev.nexus.session.client.HostLimits;
import dev.nexus.session.client.SessionClient;
import dev.nexus.session.client.SessionClientException;
import dev.nexus.session.protocol.AdmissionCapability;
import dev.nexus.session.protocol.Protocol;
import dev.nexus.session.protocol.ReplayGuard;
import dev.nexus.transport.BridgeTransportFactory;
import dev.nexus.transport.OperationLedger;
import dev.nexus.transport.PeerStream;
import dev.nexus.transport.PeerTransport;
import dev.nexus.transport.StreamPump;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one hosting session: publishes the integrated server loopback-only, listens for
 * guest transport streams, and bridges each admitted guest to the server with a
 * {@link StreamPump}.
 *
 * <p>One instance per hosting session; a stopped service is never restarted (the state
 * machine's {@code STOPPED} is terminal), but once {@link #stop} completes a fresh
 * session may be started for the same world -- start/stop cycles without leaving the
 * world are supported.
 *
 * <p>Threading: {@link #start} must be called on the client (render) thread -- it calls
 * {@code publishServer}, exactly as the vanilla Open-to-LAN screen does (the loopback
 * bind it performs is the same synchronous work vanilla does there). Everything network
 * is chained asynchronously; nothing in this class blocks the render thread, the server
 * thread, or a transport event loop. {@link #stop} may be called from any thread.
 */
public final class HostSessionService {

    private static final AtomicReference<HostSessionService> CURRENT = new AtomicReference<>();

    private final IntegratedServer server;
    private final BridgeTransportFactory transports;
    private final SessionMachine<HostSessionState> machine = SessionMachine.forHost();
    private final Set<GuestBridge> guests = ConcurrentHashMap.newKeySet();
    private final AtomicInteger guestCount = new AtomicInteger();
    private final int maxGuests;

    /**
     * Completes when this session is fully stopped: listener, streams, pumps,
     * session-owned event loops released, and the integrated server unpublished.
     * Created eagerly so every caller of {@link #stop} -- winner or concurrent loser --
     * observes the same, truthful completion.
     */
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    private volatile PeerTransport.Listener listener;
    private volatile int minecraftPort = -1;

    // M3 coordination: the backend session, its heartbeat, and guest admission.
    private final SessionClient sessionClient = new SessionClient(NexusConfig.backendUrl());
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nexus-host-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    private final ReplayGuard replayGuard = new ReplayGuard();
    private volatile SessionClient.HostSession coordination;

    /**
     * Ownership of every in-flight async operation (admission handshakes, loopback
     * dials, backend registration): tracked before it starts, so stop() awaits work
     * that has begun but not yet produced a resource. See OperationLedger.
     */
    private final OperationLedger ledger = new OperationLedger();

    /** Pre-auth resource bounds: pending cap + per-source and global rate limits. */
    private final HostLimits limits = HostLimits.fromSystemProperties();
    private final AdmissionGate admissionGate = new AdmissionGate(limits);

    /** Coordination-loss detection (backend restart, session expiry, outage). */
    private final AtomicInteger heartbeatFailures = new AtomicInteger();
    private volatile boolean coordinationLost;

    /** One admitted guest: the transport-to-loopback pump and the dialer it rides on. */
    private record GuestBridge(StreamPump pump, PeerTransport.Dialer loopbackDialer) {
    }

    private HostSessionService(IntegratedServer server, BridgeTransportFactory transports,
                               int maxGuests) {
        this.server = server;
        this.transports = transports;
        this.maxGuests = maxGuests;
        machine.addListener((from, to, reason) ->
                NexusMod.LOGGER.info("Host session: {} -> {}{}", from, to,
                        reason == null ? "" : " (" + reason + ")"));
    }

    /** The active hosting session, or {@code null} when not hosting. */
    public static HostSessionService current() {
        return CURRENT.get();
    }

    /**
     * Starts hosting {@code server}'s world. Must run on the client thread.
     *
     * <p>Returns immediately with the service in {@code STARTING} or later; observe
     * {@link #state()} (or {@link #onTransition}) for {@code ONLINE}. If bringing the
     * listener up fails asynchronously, the session stops itself and ends
     * {@code STOPPED} without ever accepting a guest.
     *
     * @throws IllegalStateException if hosting is already active (including a session
     *                               still winding down) or publishing fails outright
     */
    public static HostSessionService start(IntegratedServer server,
                                           BridgeTransportFactory transports,
                                           int maxGuests) {
        HostSessionService service = new HostSessionService(server, transports, maxGuests);
        if (!CURRENT.compareAndSet(null, service)) {
            throw new IllegalStateException(
                    "Nexus is already hosting (or a previous session is still stopping)");
        }
        try {
            service.doStart();
            return service;
        } catch (RuntimeException e) {
            // Synchronous failure: tear down whatever came up. stop() clears CURRENT
            // when the cleanup actually finishes.
            service.stop("start failed");
            throw e;
        }
    }

    private void doStart() {
        machine.transitionTo(HostSessionState.STARTING, "Host World clicked");

        // Publish through vanilla for its side effects (published scope keeps the tick
        // loop running while the host has a screen open), with the two mixin redirects
        // making the bind loopback-only and suppressing LAN multicast. Port 0: let the
        // OS pick, then read the real port back off the bound channel.
        boolean published;
        HostBridgeFlags.setNexusPublishActive(true);
        try {
            published = server.publishServer(MinecraftServer.MultiplayerScope.LAN, null,
                    server.getWorldData().isAllowCommands(), 0);
        } finally {
            HostBridgeFlags.setNexusPublishActive(false);
        }
        if (!published) {
            throw new IllegalStateException(
                    "The integrated server could not be published (already published?)");
        }

        minecraftPort = readBoundPort();
        ((IntegratedServerAccessor) server).nexus$setPublishedPort(minecraftPort);
        NexusMod.LOGGER.info("Integrated server bound loopback-only on port {}", minecraftPort);

        machine.transitionTo(HostSessionState.GATHERING_CONNECTIVITY, null);

        PeerTransport.Listener guestListener = transports.createGuestListener();
        listener = guestListener;
        guestListener.listen(this::onGuestStream)
                .thenCompose(v -> registerWithBackend())
                .whenComplete((v, error) -> {
                    if (error != null) {
                        NexusMod.LOGGER.error("Could not bring hosting online", error);
                        stop("startup failed");
                        return;
                    }
                    // Guarded transition: a stop() racing startup may already have won.
                    if (machine.tryTransitionTo(HostSessionState.ONLINE, null)) {
                        NexusMod.LOGGER.info("Nexus hosting online: {} (invite code ready)",
                                guestListener.endpointDescription());
                    }
                });
    }

    /**
     * Registers the session with the coordination backend and starts heartbeats.
     *
     * <p>The registration is a ledger-owned operation: if stop() wins while the
     * create request is in flight, the late success is immediately closed on the
     * backend and never becomes this host's coordination state — and stop() awaits
     * exactly that cleanup through the ledger.
     */
    private CompletableFuture<Void> registerWithBackend() {
        int port = listenerPort();
        if (port <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("listener has no port"));
        }
        CompletableFuture<Void> op = new CompletableFuture<>();
        if (!ledger.track(op)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("session is stopping"));
        }
        CompletableFuture<Void> result = sessionClient.createSession(localAddresses(), port)
                .thenCompose(session -> {
                    if (stopping()) {
                        // Stop won the race: this session must die now, not at TTL.
                        NexusMod.LOGGER.info(
                                "Backend registration completed after stop; closing it");
                        return sessionClient.closeSession(
                                        session.sessionId(), session.hostToken())
                                .completeOnTimeout(null, 3, TimeUnit.SECONDS)
                                .handle((v, t) -> null);
                    }
                    coordination = session;
                    NexusMod.LOGGER.info("Session registered with the Nexus service");
                    scheduler.scheduleAtFixedRate(this::heartbeat,
                            session.heartbeatSeconds(), session.heartbeatSeconds(),
                            TimeUnit.SECONDS);
                    scheduler.scheduleAtFixedRate(
                            () -> admissionGate.prune(Instant.now()), 30, 30,
                            TimeUnit.SECONDS);
                    return CompletableFuture.<Void>completedFuture(null);
                });
        result.whenComplete((v, t) -> op.complete(null));
        return result;
    }

    private boolean stopping() {
        HostSessionState state = machine.state();
        return state == HostSessionState.STOPPING || state == HostSessionState.STOPPED;
    }

    private void heartbeat() {
        SessionClient.HostSession session = coordination;
        if (session == null || !machine.state().isAcceptingGuests()) {
            return;
        }
        sessionClient.heartbeat(session.sessionId(), session.hostToken())
                .whenComplete((v, error) -> {
                    if (error == null) {
                        heartbeatFailures.set(0);
                        if (coordinationLost) {
                            coordinationLost = false;
                            NexusMod.LOGGER.info("Nexus service connection recovered");
                        }
                        return;
                    }
                    // The world and connected guests are unaffected (they ride the
                    // transport, not the backend); only new joins suffer.
                    boolean sessionGone = error instanceof SessionClientException sce
                            && Protocol.ErrorCode.NOT_FOUND.equals(sce.code());
                    int failures = heartbeatFailures.incrementAndGet();
                    if ((sessionGone || failures >= 3) && !coordinationLost) {
                        coordinationLost = true;
                        NexusMod.LOGGER.warn(
                                "Nexus session coordination lost ({}). The world stays "
                                + "up for connected players, but the invite code is no "
                                + "longer valid - stop and re-host for a new code.",
                                sessionGone ? "session expired on the service"
                                        : "service unreachable");
                    }
                });
    }

    /**
     * False when the invite code is known dead (backend restarted, session expired,
     * service unreachable). The UI stops advertising the code when this is false.
     */
    public boolean coordinationHealthy() {
        return coordination != null && !coordinationLost;
    }

    /** All plausible route addresses for this machine, site-local first. */
    private static List<String> localAddresses() {
        List<String> siteLocal = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    (address.isSiteLocalAddress() ? siteLocal : other)
                            .add(address.getHostAddress());
                }
            }
        } catch (Exception e) {
            NexusMod.LOGGER.warn("Could not enumerate local addresses", e);
        }
        siteLocal.addAll(other);
        return siteLocal;
    }

    private int readBoundPort() {
        List<ChannelFuture> channels = ((ServerConnectionListenerAccessor)
                server.getConnection()).nexus$getChannels();
        synchronized (channels) {
            for (ChannelFuture future : channels) {
                // isOpen filters stale entries from earlier publish/unpublish cycles.
                if (future.channel().isOpen()
                        && future.channel().localAddress() instanceof InetSocketAddress inet
                        && inet.getAddress().isLoopbackAddress()) {
                    return inet.getPort();
                }
            }
        }
        throw new IllegalStateException("No loopback listener found after publishServer");
    }

    /**
     * Runs on a transport I/O thread for each connecting guest. Must not block.
     *
     * <p>Order of defenses, cheapest first: session gate → admission-gate limits
     * (per-source and global rate, pending cap — all before the preamble read even
     * starts) → capability HMAC → replay guard → guest cap → loopback dial. The whole
     * attempt is one ledger-owned operation from before its first async step, so
     * stop() awaits it wherever it is, and its gate slot releases exactly once.
     */
    private void onGuestStream(PeerStream rawStream) {
        if (!machine.state().isAcceptingGuests()) {
            NexusMod.LOGGER.info("Rejecting guest {} (session {})",
                    rawStream.id(), machine.state());
            rawStream.close();
            return;
        }
        AdmissionGate.Refusal refusal =
                admissionGate.tryAcquire(rawStream.remoteAddress(), Instant.now());
        if (refusal != null) {
            // debug, not info: a flood must not also flood the log.
            NexusMod.LOGGER.debug("Refusing connection {}: {}", rawStream.id(), refusal);
            rawStream.close();
            return;
        }
        // Cheap early cap check; the authoritative reserving check happens after
        // admission so the count keeps meaning "admitted guests".
        if (guestCount.get() >= maxGuests) {
            admissionGate.release();
            NexusMod.LOGGER.info("Rejecting guest {} (world full)", rawStream.id());
            rawStream.close();
            return;
        }

        CompletableFuture<Void> op = new CompletableFuture<>();
        op.whenComplete((v, t) -> admissionGate.release());
        if (!ledger.track(op)) {
            // stop() has already drained: nothing may start now.
            op.complete(null);
            rawStream.close();
            return;
        }

        // Admission: the stream's first bytes must be a valid, unexpired, unused
        // capability for THIS session. One HMAC decides -- no loopback connection, no
        // Minecraft resource, is touched for an unauthorized stream.
        AdmissionHandshake.readCapability(rawStream, scheduler,
                        Duration.ofSeconds(limits.handshakeTimeoutSeconds()))
                .whenComplete((admitted, error) -> {
                    if (error != null) {
                        NexusMod.LOGGER.info("Guest {} failed admission handshake: {}",
                                rawStream.id(), error.getMessage());
                        rawStream.close();
                        op.complete(null);
                        return;
                    }
                    if (!capabilityAccepted(admitted)) {
                        rawStream.close();
                        op.complete(null);
                        return;
                    }
                    bridgeGuest(admitted.stream(), op);
                });
    }

    /** One HMAC + the replay guard decide. Runs on a transport I/O thread. */
    private boolean capabilityAccepted(AdmissionHandshake.Admitted admitted) {
        SessionClient.HostSession session = coordination;
        if (session == null) {
            return false;
        }
        Instant now = Instant.now();
        String token = admitted.capabilityToken();
        AdmissionCapability.Verification verdict = AdmissionCapability.verify(
                session.admissionKey(), session.sessionId(), token, now);
        if (verdict != AdmissionCapability.Verification.OK) {
            NexusMod.LOGGER.info("Guest {} rejected: capability {}",
                    admitted.stream().id(), verdict);
            return false;
        }
        if (!replayGuard.firstUse(AdmissionCapability.capabilityId(token),
                AdmissionCapability.expiresAt(token), now)) {
            NexusMod.LOGGER.warn("Guest {} rejected: capability REPLAYED",
                    admitted.stream().id());
            return false;
        }
        return true;
    }

    /**
     * Dials the loopback and bridges. The operation {@code op} completes only when
     * this attempt's resources are either fully released (any failure path) or
     * transferred into {@link #guests} — so by the time stop() has drained the
     * ledger, the guest set is complete and nothing can attach late.
     */
    private void bridgeGuest(PeerStream guestStream, CompletableFuture<Void> op) {
        int count = guestCount.incrementAndGet();
        if (count > maxGuests) {
            guestCount.decrementAndGet();
            NexusMod.LOGGER.info("Rejecting guest {} (world full: {}/{})",
                    guestStream.id(), count - 1, maxGuests);
            guestStream.close();
            op.complete(null);
            return;
        }

        PeerTransport.Dialer loopback = transports.createLoopbackDialer(minecraftPort);
        loopback.open().whenComplete((serverStream, error) -> {
            if (error != null) {
                NexusMod.LOGGER.warn("Loopback dial for guest {} failed", guestStream.id(), error);
                guestCount.decrementAndGet();
                guestStream.close();
                loopback.close().whenComplete((v, t) -> op.complete(null));
                return;
            }
            if (stopping()) {
                // stop() won while this dial was in flight: release everything within
                // the operation, which stop is still awaiting.
                guestCount.decrementAndGet();
                guestStream.close();
                serverStream.close();
                loopback.close().whenComplete((v, t) -> op.complete(null));
                return;
            }
            StreamPump pump = StreamPump.between(guestStream, serverStream);
            GuestBridge bridge = new GuestBridge(pump, loopback);
            guests.add(bridge);
            op.complete(null);
            NexusMod.LOGGER.info("Guest {} bridged to integrated server ({}/{})",
                    guestStream.id(), guestCount.get(), maxGuests);
            pump.done().whenComplete((v, t) -> {
                guests.remove(bridge);
                guestCount.decrementAndGet();
                // The dialer owns its event loop; release it with its one stream.
                loopback.close();
                NexusMod.LOGGER.info("Guest {} disconnected ({}/{})",
                        guestStream.id(), guestCount.get(), maxGuests);
            });
        });
    }

    /** Current session state, for the UI. */
    public HostSessionState state() {
        return machine.state();
    }

    /** Registers a listener for session state changes (any thread; must not block). */
    public void onTransition(SessionMachine.TransitionListener<HostSessionState> l) {
        machine.addListener(l);
    }

    /** Where guests connect, e.g. {@code /0.0.0.0:54321}, once online. */
    public String endpointDescription() {
        PeerTransport.Listener l = listener;
        return l == null ? "(not listening)" : l.endpointDescription();
    }

    /** The transport listener's port, or -1 before it is bound. */
    public int listenerPort() {
        PeerTransport.Listener l = listener;
        if (l != null && l.localAddress() instanceof InetSocketAddress inet) {
            return inet.getPort();
        }
        return -1;
    }

    /** Connected guest count, for the UI. */
    public int guestCount() {
        return guestCount.get();
    }

    /** The formatted invite code, or {@code null} until the session is registered. */
    public String inviteCode() {
        SessionClient.HostSession session = coordination;
        return session == null ? null : session.inviteCode();
    }

    /**
     * Stops hosting. Idempotent and callable from any thread; every caller receives the
     * same future, which completes only when <em>all</em> session-owned resources are
     * released: the Nexus listener and its event loop, every guest stream and pump,
     * every loopback dialer and its event loop, and the integrated server's unpublish.
     * {@link #current()} stays set until that point, so a new session cannot start on
     * top of a half-stopped one.
     */
    public CompletableFuture<Void> stop(String reason) {
        if (machine.tryTransitionTo(HostSessionState.STOPPING, reason)) {
            performStop();
        }
        return stopped;
    }

    /**
     * The teardown sequence, each step gating the next so the final future is
     * truthful:
     * <ol>
     *   <li>STOPPING is already set (no new admissions or bridges pass the gates);</li>
     *   <li>close the listener — no new streams, in-flight streams die, the
     *       listener's event loop terminates;</li>
     *   <li>drain the operation ledger — every started admission handshake, loopback
     *       dial, and backend registration finishes its own cleanup (a late dial or
     *       late create-response releases its resources <em>inside</em> its tracked
     *       operation). After this, nothing can attach anywhere;</li>
     *   <li>snapshot the now-complete guest set; await each pump and dialer;</li>
     *   <li>close the backend session (bounded) and await the vanilla unpublish;</li>
     *   <li>shut down the scheduler and HTTP client, awaited;</li>
     *   <li>STOPPED, leak check, clear {@code CURRENT}, complete the future.</li>
     * </ol>
     */
    private void performStop() {
        PeerTransport.Listener l = listener;
        CompletableFuture<Void> listenerClosed = l == null
                ? CompletableFuture.completedFuture(null)
                : l.close().toCompletableFuture();

        CompletableFuture<Void> unpublished = unpublish();

        listenerClosed
                .thenCompose(v -> ledger.drain())
                .whenComplete((v, t) -> {
                    CompletableFuture<?>[] guestFutures = guests.stream()
                            .map(g -> g.pump().done().toCompletableFuture()
                                    .handle((x, e) -> null)
                                    .thenCompose(x -> g.loopbackDialer().close().toCompletableFuture())
                                    .handle((x, e) -> null))
                            .toArray(CompletableFuture[]::new);
                    CompletableFuture.allOf(guestFutures)
                            .thenCombine(closeBackendSession(), (a, b) -> null)
                            .thenCombine(unpublished, (a, b) -> null)
                            .whenComplete((v2, t2) -> {
                                releaseCoordination();
                                machine.transitionTo(HostSessionState.STOPPED, null);
                                verifyNoLeaks();
                                // Cleared last: only a fully-released session frees the slot.
                                CURRENT.compareAndSet(this, null);
                                stopped.complete(null);
                            });
                });
    }

    /**
     * Closes the backend session, bounded: an unreachable backend must not stall the
     * player's Stop button, and the session TTL is the safety net anyway.
     */
    private CompletableFuture<Void> closeBackendSession() {
        SessionClient.HostSession session = coordination;
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        return sessionClient.closeSession(session.sessionId(), session.hostToken())
                .completeOnTimeout(null, 3, TimeUnit.SECONDS)
                .handle((v, error) -> {
                    if (error != null) {
                        NexusMod.LOGGER.warn(
                                "Could not close the backend session (it will expire "
                                + "on its own): {}", error.getMessage());
                    }
                    return null;
                });
    }

    /** Shuts down session-owned coordination resources; awaited, per the leak policy. */
    private void releaseCoordination() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                NexusMod.LOGGER.error("LEAK: host scheduler did not terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sessionClient.close();
    }

    /** Unpublishes on the client thread; completes when done (or already unpublished). */
    private CompletableFuture<Void> unpublish() {
        if (!server.isPublished()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        // execute() runs inline when already on the client thread, so the Stop button
        // path completes this synchronously; other threads enqueue.
        Minecraft.getInstance().execute(() -> {
            try {
                if (server.isPublished()) {
                    server.unpublishServer();
                }
            } catch (RuntimeException e) {
                NexusMod.LOGGER.error("unpublishServer failed", e);
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    private void verifyNoLeaks() {
        if (!guests.isEmpty()) {
            NexusMod.LOGGER.error("LEAK: {} guest bridge(s) survived session stop", guests.size());
        }
        int remaining = guestCount.get();
        if (remaining != 0) {
            NexusMod.LOGGER.error("LEAK: guest count {} after session stop", remaining);
        }
        long threads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("nexus-tcp-"))
                .count();
        if (threads > 0) {
            // Guest-session threads may coexist; anything from this session is already
            // awaited above, so this is diagnostic only.
            NexusMod.LOGGER.debug("{} nexus-tcp thread(s) alive after stop", threads);
        }
        NexusMod.LOGGER.info("Nexus hosting stopped; session resources released");
    }
}
