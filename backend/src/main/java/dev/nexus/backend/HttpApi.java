/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nexus.session.protocol.AdmissionCapability;
import dev.nexus.session.protocol.InviteCode;
import dev.nexus.session.protocol.Json;
import dev.nexus.session.protocol.Messages;
import dev.nexus.session.protocol.Protocol;
import dev.nexus.session.protocol.Protocol.ErrorCode;
import dev.nexus.session.protocol.RateLimiter;
import dev.nexus.session.protocol.RoutePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The coordination API: four endpoints, JSON in and out.
 *
 * <pre>
 *   POST   /v1/sessions                create hosted session (host)
 *   POST   /v1/sessions/{id}/heartbeat keep-alive (host, bearer hostToken)
 *   POST   /v1/sessions/{id}/close     explicit close (host, bearer hostToken)
 *   POST   /v1/join                    invite code -> route + one-time capability (guest)
 * </pre>
 *
 * <p>(There is deliberately no {@code /resolve}: the guest flow needs only
 * {@code /join}, and a second code-probing endpoint would be pure attack surface.)
 *
 * <p>Every request is bounded ({@link Protocol#MAX_BODY_BYTES}, rejected unread beyond
 * that), protocol-version-checked, and rate limited before any store work. Limits are
 * operation-aware — guest traffic (join), host session creation, and host keep-alive
 * each draw from separate buckets, so hostile join floods cannot starve a legitimate
 * host's heartbeats. Host tokens are stored only as SHA-256 verifiers and compared
 * timing-safely; invite codes cross this class only long enough to be hashed.
 *
 * <p>Deployment topology: the backend binds loopback by default and is intended to
 * sit behind TLS termination (reverse proxy) for anything remote. Client identity is
 * the socket's remote address unless that address is an explicitly configured trusted
 * proxy, in which case the last {@code X-Forwarded-For} entry (the one appended by
 * that proxy) is used. The header is never believed from anyone else.
 */
public final class HttpApi {

    private final SessionStore store;
    private final BackendConfig config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    // Operation-aware limiters: hostile guest traffic must not starve host upkeep.
    private final RateLimiter guestOps;
    private final RateLimiter createOps;
    private final RateLimiter hostOps;

    public HttpApi(SessionStore store, BackendConfig config, Clock clock) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.guestOps = new RateLimiter(config.rateCapacity(), config.rateRefillPerSecond());
        this.createOps = new RateLimiter(Math.max(1, config.rateCapacity() / 2),
                config.rateRefillPerSecond());
        this.hostOps = new RateLimiter(config.rateCapacity() * 4,
                config.rateRefillPerSecond() * 4);
    }

    /** Registers all routes on {@code server}. */
    public void mount(HttpServer server) {
        server.createContext("/v1/sessions", exchange -> handle(exchange, this::routeSessions));
        server.createContext("/v1/join", exchange -> handle(exchange, this::handleJoin));
    }

    /** Periodic limiter cleanup; called from the backend's housekeeping task. */
    void pruneLimiters(Instant now) {
        guestOps.prune(now);
        createOps.prune(now);
        hostOps.prune(now);
    }

    // ------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Route {
        Response apply(HttpExchange exchange, String body) throws IOException;
    }

    private record Response(int status, Object body) {
    }

    private static Response error(int status, String code, String message) {
        return new Response(status, new Messages.ErrorResponse(code, message));
    }

    private void handle(HttpExchange exchange, Route route) throws IOException {
        Response response;
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                response = error(405, ErrorCode.MALFORMED, "POST only");
            } else if (!limiterFor(exchange).tryAcquire(clientKey(exchange), clock.instant())) {
                response = error(429, ErrorCode.RATE_LIMITED, "Too many requests");
            } else {
                String body = readBounded(exchange.getRequestBody());
                response = body == null
                        ? error(413, ErrorCode.PAYLOAD_TOO_LARGE, "Request too large")
                        : route.apply(exchange, body);
            }
        } catch (JsonParseException e) {
            response = error(400, ErrorCode.MALFORMED, "Malformed request");
        } catch (Exception e) {
            // Never leak internals to the wire, and never log request content: the
            // class name identifies the bug without echoing bodies or tokens.
            System.err.println("[nexus-backend] internal error: "
                    + e.getClass().getName());
            response = error(500, ErrorCode.INTERNAL, "Internal error");
        }
        byte[] payload = Json.encode(response.body()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private RateLimiter limiterFor(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/v1/join")) {
            return guestOps;
        }
        if (path.equals("/v1/sessions") || path.equals("/v1/sessions/")) {
            return createOps;
        }
        return hostOps;
    }

    /** Reads at most MAX_BODY_BYTES; {@code null} signals an oversized request. */
    private static String readBounded(InputStream in) throws IOException {
        byte[] buffer = in.readNBytes(Protocol.MAX_BODY_BYTES + 1);
        if (buffer.length > Protocol.MAX_BODY_BYTES) {
            return null;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }

    /**
     * Rate-limit identity: the socket's remote address — unless it is an explicitly
     * trusted proxy, in which case the last X-Forwarded-For hop (the one that proxy
     * appended) is used. Anyone else's header is ignored.
     */
    private String clientKey(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        String address = remote.getAddress() == null
                ? remote.toString()
                : remote.getAddress().getHostAddress();
        if (config.trustedProxies().contains(address)) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty() && last.length() <= 64) {
                    return last;
                }
            }
        }
        return address;
    }

    private static boolean wrongVersion(int protocolVersion) {
        return protocolVersion != Protocol.VERSION;
    }

    private static Response unsupported() {
        return error(400, ErrorCode.UNSUPPORTED_PROTOCOL,
                "This Nexus speaks protocol version " + Protocol.VERSION);
    }

    static byte[] tokenVerifier(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------ host routes

    /** Dispatches /v1/sessions and /v1/sessions/{id}/(heartbeat|close). */
    private Response routeSessions(HttpExchange exchange, String body) {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        // "/v1/sessions" -> ["", "v1", "sessions"]
        if (parts.length == 3) {
            return handleCreate(body);
        }
        if (parts.length == 5 && "heartbeat".equals(parts[4])) {
            return handleHeartbeat(parts[3], body);
        }
        if (parts.length == 5 && "close".equals(parts[4])) {
            return handleClose(parts[3], body);
        }
        return error(404, ErrorCode.NOT_FOUND, "No such endpoint");
    }

    private Response handleCreate(String body) {
        Messages.CreateSessionRequest request =
                Json.decode(body, Messages.CreateSessionRequest.class);
        if (wrongVersion(request.protocolVersion())) {
            return unsupported();
        }
        List<String> addresses = RoutePolicy.sanitize(request.addresses(), config.routeMode());
        if (addresses.isEmpty() || !RoutePolicy.validPort(request.port())) {
            return error(400, ErrorCode.MALFORMED, "Invalid addresses or port");
        }

        Instant now = clock.instant();
        // Retry on the (astronomically unlikely) live-code collision.
        for (int attempt = 0; attempt < 5; attempt++) {
            String rawCode = InviteCode.generate(random);
            String hostToken = newToken();
            SessionStore.Session session = new SessionStore.Session(
                    UUID.randomUUID().toString(),
                    InviteCode.hash(rawCode),
                    tokenVerifier(hostToken),
                    AdmissionCapability.newAdmissionKey(random),
                    addresses,
                    request.port(),
                    now,
                    now);
            if (store.put(session)) {
                return new Response(200, new Messages.CreateSessionResponse(
                        session.sessionId(),
                        hostToken,
                        InviteCode.format(rawCode),
                        Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(session.admissionKey()),
                        now.getEpochSecond() + config.sessionTtlSeconds(),
                        config.heartbeatSeconds()));
            }
        }
        return error(500, ErrorCode.INTERNAL, "Could not allocate an invite code");
    }

    private Response handleHeartbeat(String sessionId, String body) {
        Messages.HeartbeatRequest request = Json.decode(body, Messages.HeartbeatRequest.class);
        if (wrongVersion(request.protocolVersion())) {
            return unsupported();
        }
        if (request.hostToken() == null) {
            return error(404, ErrorCode.NOT_FOUND, "No such session");
        }
        Instant now = clock.instant();
        // One atomic transition: verify + liveness + refresh. An unauthorized caller
        // cannot distinguish a wrong token from a dead session.
        if (!store.heartbeat(sessionId, tokenVerifier(request.hostToken()), now)) {
            return error(404, ErrorCode.NOT_FOUND, "No such session");
        }
        return new Response(200, new Messages.HeartbeatResponse(
                now.getEpochSecond() + config.sessionTtlSeconds()));
    }

    private Response handleClose(String sessionId, String body) {
        Messages.CloseRequest request = Json.decode(body, Messages.CloseRequest.class);
        if (wrongVersion(request.protocolVersion())) {
            return unsupported();
        }
        if (request.hostToken() == null
                || !store.close(sessionId, tokenVerifier(request.hostToken()))) {
            return error(404, ErrorCode.NOT_FOUND, "No such session");
        }
        return new Response(200, new Messages.ErrorResponse("ok", "closed"));
    }

    // ------------------------------------------------------------ guest routes

    private Response handleJoin(HttpExchange exchange, String body) {
        Messages.JoinRequest request = Json.decode(body, Messages.JoinRequest.class);
        if (wrongVersion(request.protocolVersion())) {
            return unsupported();
        }
        String raw = InviteCode.normalize(request.inviteCode());
        Optional<SessionStore.Session> found = raw == null
                ? Optional.empty()
                : store.findByCodeHash(InviteCode.hash(raw), clock.instant());
        if (found.isEmpty()) {
            return error(404, ErrorCode.INVALID_CODE,
                    "That invite code doesn't match an active world");
        }
        SessionStore.Session session = found.get();
        Instant now = clock.instant();
        String capability = AdmissionCapability.mint(
                session.admissionKey(), session.sessionId(), random, now);
        return new Response(200, new Messages.JoinResponse(
                session.sessionId(),
                session.addresses(),
                session.port(),
                capability,
                AdmissionCapability.expiresAt(capability)));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
