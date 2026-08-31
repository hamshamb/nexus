/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.backend;

import com.sun.net.httpserver.HttpServer;
import dev.nexus.session.protocol.AdmissionCapability;
import dev.nexus.session.protocol.Json;
import dev.nexus.session.protocol.Messages;
import dev.nexus.session.protocol.Protocol;
import dev.nexus.session.protocol.Protocol.ErrorCode;
import dev.nexus.session.protocol.RoutePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend behind a real HTTP server, with a mutable test clock so expiry is
 * deterministic rather than sleep-based.
 */
@Timeout(30)
class BackendIntegrationTest {

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        volatile Instant now = Instant.ofEpochSecond(1_800_000_000L);

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private final TestClock clock = new TestClock();
    private final BackendConfig config = new BackendConfig("127.0.0.1", 0, 90, 30,
            100, 50, RoutePolicy.Mode.DEVELOPMENT, Set.of());
    private HttpServer server;
    private InMemorySessionStore store;
    private URI base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startBackend() throws IOException {
        store = new InMemorySessionStore(Duration.ofSeconds(config.sessionTtlSeconds()));
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        new HttpApi(store, config, clock).mount(server);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopBackend() {
        server.stop(0);
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path))
                        .POST(HttpRequest.BodyPublishers.ofString(Json.encode(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postRaw(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Messages.CreateSessionResponse createSession() throws Exception {
        HttpResponse<String> response = post("/v1/sessions",
                new Messages.CreateSessionRequest(Protocol.VERSION,
                        List.of("192.168.1.20"), 54321));
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.decode(response.body(), Messages.CreateSessionResponse.class);
    }

    private HttpResponse<String> join(String code) throws Exception {
        return post("/v1/join", new Messages.JoinRequest(Protocol.VERSION, code));
    }

    private String errorCode(HttpResponse<String> response) {
        return Json.decode(response.body(), Messages.ErrorResponse.class).error();
    }

    @Test
    void createJoinHappyPathMintsAVerifiableSessionBoundCapability() throws Exception {
        Messages.CreateSessionResponse created = createSession();
        assertThat(created.inviteCode()).matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");

        HttpResponse<String> joined = join(created.inviteCode());
        assertThat(joined.statusCode()).isEqualTo(200);
        Messages.JoinResponse grant = Json.decode(joined.body(), Messages.JoinResponse.class);
        assertThat(grant.sessionId()).isEqualTo(created.sessionId());
        assertThat(grant.addresses()).containsExactly("192.168.1.20");
        assertThat(grant.port()).isEqualTo(54321);

        byte[] key = Base64.getUrlDecoder().decode(created.admissionKey());
        assertThat(AdmissionCapability.verify(key, created.sessionId(),
                grant.capabilityToken(), clock.instant()))
                .isEqualTo(AdmissionCapability.Verification.OK);
        assertThat(AdmissionCapability.verify(key, "some-other-session",
                grant.capabilityToken(), clock.instant()))
                .isEqualTo(AdmissionCapability.Verification.WRONG_SESSION);
    }

    @Test
    void thereIsNoResolveEndpoint() throws Exception {
        createSession();
        HttpResponse<String> response = postRaw("/v1/resolve",
                "{\"protocolVersion\":1,\"inviteCode\":\"K7M2-PQ9X\"}");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void invalidAndMalformedCodesAreIndistinguishableRejections() throws Exception {
        createSession();
        for (String bad : new String[]{"AAAA-AAAA", "not a code", ""}) {
            HttpResponse<String> response = join(bad);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo(ErrorCode.INVALID_CODE);
        }
    }

    @Test
    void sessionsExpireWithoutHeartbeatAndHeartbeatKeepsThemAlive() throws Exception {
        Messages.CreateSessionResponse created = createSession();

        for (int i = 0; i < 4; i++) {
            clock.advanceSeconds(60);
            HttpResponse<String> beat = post(
                    "/v1/sessions/" + created.sessionId() + "/heartbeat",
                    new Messages.HeartbeatRequest(Protocol.VERSION, created.hostToken()));
            assertThat(beat.statusCode()).isEqualTo(200);
        }

        clock.advanceSeconds(91);
        assertThat(join(created.inviteCode()).statusCode()).isEqualTo(404);
        HttpResponse<String> lateBeat = post(
                "/v1/sessions/" + created.sessionId() + "/heartbeat",
                new Messages.HeartbeatRequest(Protocol.VERSION, created.hostToken()));
        assertThat(lateBeat.statusCode()).isEqualTo(404);
    }

    @Test
    void explicitCloseRemovesTheSessionAndItsCodeAndWrongTokensCannot() throws Exception {
        Messages.CreateSessionResponse created = createSession();

        HttpResponse<String> forged = post("/v1/sessions/" + created.sessionId() + "/close",
                new Messages.CloseRequest(Protocol.VERSION, "wrong-token"));
        assertThat(forged.statusCode()).isEqualTo(404);

        HttpResponse<String> closed = post("/v1/sessions/" + created.sessionId() + "/close",
                new Messages.CloseRequest(Protocol.VERSION, created.hostToken()));
        assertThat(closed.statusCode()).isEqualTo(200);

        assertThat(join(created.inviteCode()).statusCode()).isEqualTo(404);
        assertThat(store.size()).isZero();
    }

    @Test
    void unknownProtocolVersionIsRejectedOnEveryEndpoint() throws Exception {
        Messages.CreateSessionResponse created = createSession();
        record Probe(String path, Object body) {
        }
        for (Probe probe : new Probe[]{
                new Probe("/v1/sessions",
                        new Messages.CreateSessionRequest(99, List.of("10.0.0.1"), 1)),
                new Probe("/v1/join", new Messages.JoinRequest(99, "K7M2-PQ9X")),
                new Probe("/v1/sessions/" + created.sessionId() + "/heartbeat",
                        new Messages.HeartbeatRequest(99, created.hostToken())),
                new Probe("/v1/sessions/" + created.sessionId() + "/close",
                        new Messages.CloseRequest(99, created.hostToken())),
        }) {
            HttpResponse<String> response = post(probe.path(), probe.body());
            assertThat(response.statusCode()).as(probe.path()).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo(ErrorCode.UNSUPPORTED_PROTOCOL);
        }
    }

    @Test
    void oversizedMalformedAndHostileRoutePayloadsAreRejected() throws Exception {
        HttpResponse<String> huge = postRaw("/v1/join",
                "x".repeat(Protocol.MAX_BODY_BYTES + 100));
        assertThat(huge.statusCode()).isEqualTo(413);
        assertThat(errorCode(huge)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);

        HttpResponse<String> garbage = postRaw("/v1/join", "{not json[[");
        assertThat(garbage.statusCode()).isEqualTo(400);
        assertThat(errorCode(garbage)).isEqualTo(ErrorCode.MALFORMED);

        for (List<String> hostile : List.of(
                List.of("../../etc/passwd", "javascript:alert(1)"),
                List.of("evil.example/path?q=1"),
                List.of("0.0.0.0"),
                List.of("fe80::1"))) {
            HttpResponse<String> response = post("/v1/sessions",
                    new Messages.CreateSessionRequest(Protocol.VERSION, hostile, 54321));
            assertThat(response.statusCode()).as(String.valueOf(hostile)).isEqualTo(400);
        }
    }

    @Test
    void guestFloodDoesNotStarveHostHeartbeats() throws Exception {
        // A limiter tight enough to trip on guest traffic.
        BackendConfig tight = new BackendConfig("127.0.0.1", 0, 90, 30,
                3, 0.5, RoutePolicy.Mode.DEVELOPMENT, Set.of());
        HttpServer limited = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        new HttpApi(store, tight, clock).mount(limited);
        limited.start();
        URI limitedBase = URI.create("http://127.0.0.1:" + limited.getAddress().getPort());
        try {
            HttpResponse<String> createResponse = http.send(
                    HttpRequest.newBuilder(limitedBase.resolve("/v1/sessions"))
                            .POST(HttpRequest.BodyPublishers.ofString(Json.encode(
                                    new Messages.CreateSessionRequest(Protocol.VERSION,
                                            List.of("192.168.1.20"), 54321))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(createResponse.statusCode()).isEqualTo(200);
            Messages.CreateSessionResponse created =
                    Json.decode(createResponse.body(), Messages.CreateSessionResponse.class);

            // Exhaust the guest bucket.
            int rejected = 0;
            for (int i = 0; i < 6; i++) {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(limitedBase.resolve("/v1/join"))
                                .POST(HttpRequest.BodyPublishers.ofString(Json.encode(
                                        new Messages.JoinRequest(Protocol.VERSION, "K7M2-PQ9X"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    rejected++;
                }
            }
            assertThat(rejected).isEqualTo(3);

            // The host's heartbeat draws from a separate, larger bucket: still fine.
            HttpResponse<String> beat = http.send(
                    HttpRequest.newBuilder(limitedBase.resolve(
                                    "/v1/sessions/" + created.sessionId() + "/heartbeat"))
                            .POST(HttpRequest.BodyPublishers.ofString(Json.encode(
                                    new Messages.HeartbeatRequest(Protocol.VERSION,
                                            created.hostToken()))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(beat.statusCode()).isEqualTo(200);
        } finally {
            limited.stop(0);
        }
    }

    @Test
    void hostTokenIsStoredOnlyAsAVerifier() throws Exception {
        Messages.CreateSessionResponse created = createSession();
        SessionStore.Session session =
                store.findByCodeHash(dev.nexus.session.protocol.InviteCode.hash(
                                dev.nexus.session.protocol.InviteCode.normalize(
                                        created.inviteCode())),
                        clock.instant()).orElseThrow();
        // The stored verifier is the SHA-256 of the token, not the token itself.
        assertThat(session.hostTokenVerifier())
                .isEqualTo(HttpApi.tokenVerifier(created.hostToken()));
        assertThat(new String(session.hostTokenVerifier(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .isNotEqualTo(created.hostToken());
    }
}
