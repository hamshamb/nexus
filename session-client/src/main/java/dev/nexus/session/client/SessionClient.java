/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import dev.nexus.session.protocol.Json;
import dev.nexus.session.protocol.Messages;
import dev.nexus.session.protocol.Protocol;
import dev.nexus.session.protocol.RoutePolicy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous client for the coordination backend.
 *
 * <p>Session-owned: each hosting or joining session creates one and {@link #close}s it
 * with the session, per the leak policy. All requests carry
 * {@link Protocol#VERSION} and a bounded timeout; every completion is either the typed
 * response or a {@link SessionClientException}. Nothing blocks the caller's thread.
 */
public final class SessionClient implements AutoCloseable {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Response-body cap. The largest legitimate response (a join grant) is well under
     * 2 KiB; a malicious or broken backend must not make the client buffer more.
     */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final URI baseUrl;
    private final ExecutorService executor;
    private final HttpClient http;

    /**
     * @throws SessionClientException {@code insecure_endpoint} if {@code baseUrl}
     *                                violates the plaintext policy (remote http://)
     */
    public SessionClient(URI baseUrl) {
        this.baseUrl = BackendEndpoint.validate(baseUrl);
        // Daemon threads: an unclosed client can never keep the JVM alive.
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "nexus-session-http");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** What the host holds for the lifetime of its session. */
    public record HostSession(String sessionId, String hostToken, String inviteCode,
                              byte[] admissionKey, int heartbeatSeconds) {
    }

    /** What a guest needs to establish the existing route: addresses, port, capability. */
    public record JoinGrant(String sessionId, List<String> addresses, int port,
                            String capabilityToken) {
    }

    public CompletableFuture<HostSession> createSession(List<String> addresses, int port) {
        return post("/v1/sessions",
                new Messages.CreateSessionRequest(Protocol.VERSION, addresses, port),
                Messages.CreateSessionResponse.class)
                .thenApply(r -> new HostSession(
                        r.sessionId(), r.hostToken(), r.inviteCode(),
                        Base64.getUrlDecoder().decode(r.admissionKey()),
                        r.heartbeatSeconds()));
    }

    public CompletableFuture<Void> heartbeat(String sessionId, String hostToken) {
        return post("/v1/sessions/" + sessionId + "/heartbeat",
                new Messages.HeartbeatRequest(Protocol.VERSION, hostToken),
                Messages.HeartbeatResponse.class)
                .thenApply(r -> null);
    }

    public CompletableFuture<Void> closeSession(String sessionId, String hostToken) {
        return post("/v1/sessions/" + sessionId + "/close",
                new Messages.CloseRequest(Protocol.VERSION, hostToken),
                Messages.ErrorResponse.class)
                .thenApply(r -> null);
    }

    /**
     * Resolves a code straight to a join grant. (There is deliberately no separate
     * resolve endpoint: it would be a second code-probing surface with no consumer.)
     *
     * <p>The grant's route data is re-validated here through {@link RoutePolicy} —
     * the client does not blindly dial whatever a backend returns. A grant with no
     * surviving address fails typed.
     */
    public CompletableFuture<JoinGrant> join(String inviteCode, RoutePolicy.Mode mode) {
        return post("/v1/join",
                new Messages.JoinRequest(Protocol.VERSION, inviteCode),
                Messages.JoinResponse.class)
                .thenApply(r -> {
                    List<String> safe = RoutePolicy.sanitize(r.addresses(), mode);
                    if (safe.isEmpty() || !RoutePolicy.validPort(r.port())
                            || r.capabilityToken() == null
                            || r.capabilityToken().length() > Protocol.MAX_CAPABILITY_BYTES) {
                        throw new SessionClientException(Protocol.ErrorCode.MALFORMED,
                                "The Nexus service returned an unusable route");
                    }
                    return new JoinGrant(r.sessionId(), safe, r.port(), r.capabilityToken());
                });
    }

    private <T> CompletableFuture<T> post(String path, Object body, Class<T> responseType) {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.encode(body)))
                .build();
        return http.sendAsync(request, info -> new BoundedStringSubscriber(MAX_RESPONSE_BYTES))
                .handle((response, error) -> {
                    if (error != null) {
                        // A typed failure from lower layers (e.g. the oversized-body
                        // guard) surfaces as itself, not as a generic transport error.
                        for (Throwable c = error; c != null; c = c.getCause()) {
                            if (c instanceof SessionClientException sce) {
                                throw sce;
                            }
                        }
                        throw new SessionClientException(SessionClientException.TRANSPORT,
                                "Could not reach the Nexus service", error);
                    }
                    if (response.statusCode() != 200) {
                        Messages.ErrorResponse er;
                        try {
                            er = Json.decode(response.body(), Messages.ErrorResponse.class);
                        } catch (RuntimeException e) {
                            er = new Messages.ErrorResponse(
                                    Protocol.ErrorCode.INTERNAL, "Unexpected reply");
                        }
                        throw new SessionClientException(er.error(), er.message());
                    }
                    return Json.decode(response.body(), responseType);
                });
    }

    @Override
    public void close() {
        http.close();
        executor.shutdownNow();
    }

    /**
     * Accumulates a response body up to a hard cap; beyond it, the transfer is
     * cancelled and the request fails with a typed error instead of buffering
     * whatever a hostile backend cares to stream.
     */
    private static final class BoundedStringSubscriber
            implements HttpResponse.BodySubscriber<String> {

        private final int maxBytes;
        private final java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private volatile java.util.concurrent.Flow.Subscription subscription;

        BoundedStringSubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (java.nio.ByteBuffer buffer : buffers) {
                if (received.size() + buffer.remaining() > maxBytes) {
                    java.util.concurrent.Flow.Subscription s = subscription;
                    if (s != null) {
                        s.cancel();
                    }
                    result.completeExceptionally(new SessionClientException(
                            SessionClientException.TRANSPORT,
                            "The Nexus service sent an oversized response"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                received.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(received.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
