# Nexus Architecture

Nexus has one product goal: open a world, choose **Host World**, send someone an invite
code, and play. Every layer below exists to serve that flow.

## Contents

- [Module boundaries](#module-boundaries)
- [Connection bridge](#the-connection-bridge)
- [Flow control](#flow-control-why-it-is-in-the-api)
- [Session lifecycle](#session-lifecycle)
- [Session coordination](#session-coordination-m3)
- [Security hardening](#m3-security-hardening-audit-driven)
- [Failure, threading, and leak policies](#failure-taxonomy)
- [Roadmap and release gates](#roadmap-and-release-gates)
- [Decision log](#decision-log)

## Module boundaries

```
core             pure JVM: session state machines (compatibility model arrives at M7).
                 No Minecraft, no Netty, no I/O.
session-protocol the coordination protocol, shared verbatim by backend and client:
                 versioned JSON messages, invite codes, the admission capability,
                 the replay guard. Pure JVM + Gson.
session-client   backend client (java.net.http) + the on-stream admission handshake.
backend          the coordination service: a small monolith on the JDK's built-in
                 HTTP server; in-memory store behind a SessionStore seam.
transport-api    PeerTransport / PeerStream: a reliable ordered bidirectional byte
                 stream with flow control and idempotent close. Expressed in Netty
                 ByteBuf so a Netty channel (e.g. a QUIC stream) can implement it
                 directly.
transport-tcp    Plain-TCP implementation: the LAN/direct-address route and the
                 transport the test-suite runs against. Permanent, not scaffolding.
minecraft-fabric The Fabric mod: integrated-server lifecycle, the connection bridge,
                 host controls, UI. The only module that imports Minecraft.
```

Rules: `minecraft-fabric` consumes only `transport-api` types; connectivity code never
imports Minecraft; UI code never sees ICE/STUN/relay details, only `TransportRoute` and
`TransportFailure`.

The bridge's transports are supplied through `BridgeTransportFactory` (transport-api):
the guest-facing listener is route-specific and will be swapped for ICE/QUIC/relay at
M4/M5, while the two loopback halves are bridge-local plumbing that stays TCP by design
(they carry the vanilla client's and integrated server's own localhost connections).
The guest's route to the host arrives as an already-built `PeerTransport.Dialer`. The
only class in the mod that names a concrete transport is `NexusTransports`, the
composition root.

## The connection bridge

Minecraft's wire format is varint-length-prefixed frames over a stream, so the transport
owes it exactly TCP semantics — no framing, no MTU, no reordering. Both ends of the
bridge therefore reduce to one primitive: pump bytes between a `PeerStream` and a local
TCP socket.

**Host:** `IntegratedServer.publishServer` is invoked for its side effects (published
state keeps the tick loop running while the host has a screen open), with two mixins:
the listener bind is redirected from wildcard to loopback, and the `LanServerPinger`
(which would multicast the world to the whole LAN) is suppressed. Each accepted
`PeerStream` is bridged to `127.0.0.1:<bound port>`.

**Guest:** a loopback listener accepts the vanilla client's own connection
(`ConnectScreen.startConnecting` is public static — no mixin), and bridges it to the
dialled `PeerStream`.

Why the loopback hop instead of injecting a synthetic Netty channel into Minecraft's
pipelines: the server then sees a genuine socket channel from its own initializer, so
`Connection.isMemoryConnection()` is false and the complete vanilla login path runs —
RSA key exchange, Mojang session auth, compression, encryption. Verified in the 26.2
bytecode: `ServerLoginPacketListenerImpl` gates real auth on
`usesAuthentication() && !isMemoryConnection()`, and falls back to
`UUIDUtil.createOfflineProfile` otherwise. A memory-classified bridge would therefore
*silently disable authentication while appearing to work*. The loopback hop makes that
failure mode structurally impossible, at a cost of ~tens of microseconds against a
30–150 ms WAN path. If per-guest addressing ever forces the synthetic-channel design,
it must use a custom `AbstractChannel` (never `LocalChannel`/`EmbeddedChannel`) and keep
the M2 authentication assertion green.

Verified 26.2 facts the bridge relies on (established by disassembling the
Mojang-published unobfuscated client jar):

- `IntegratedServer.initServer()` calls `setUsesAuthentication(true)` — online-mode auth
  is active from world load, independent of publishing.
- `publishServer(MultiplayerScope, int)` calls `startTcpServerListener(null, port)`
  (wildcard bind) and starts `LanServerPinger`; `MultiplayerScope` is only OFF/LAN.
- `ServerConnectionListener.startTcpServerListener/stopTcpServerListener/stop/
  getConnections` are public; the `channels` list is private (accessor mixin reads the
  bound port back, avoiding a probe-then-bind race).
- `ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean,
  TransferState)` is public static.
- `IntegratedServer.getMaxPlayers()` hardcodes 8 — raising the cap is a later mixin.

## Flow control (why it is in the API)

The pump connects streams of very different speeds; a guest on a slow link during chunk
streaming would otherwise make the host buffer without bound. `PeerStream` therefore
carries writability (`isWritable` + `writabilityChanged`) and read gating
(`setReadEnabled`). The pump's rule: when one side becomes unwritable, disable reads on
the other; re-enable when writability returns. Watermarks: writability off at 256 KiB
queued, on again at 64 KiB.

## Session lifecycle

Explicit state machines in `core` (`SessionMachine` enforces transitions, throws
`IllegalTransitionException` on bugs, and serialises racing stoppers so teardown runs
exactly once):

- Host: `IDLE → STARTING → GATHERING_CONNECTIVITY → ONLINE → STOPPING → STOPPED`, with
  every active stage abandonable to `STOPPING`.
- Guest: `RESOLVING → NEGOTIATING → CONNECTING_DIRECT|RELAY → CONNECTED_* →
  DISCONNECTED`, any non-terminal state may `FAIL`, and direct may fall back to relay as
  a designed transition rather than an error. `CONNECTED_*` means the vanilla client is
  bridged through the tunnel — transport-stream establishment alone is still
  `CONNECTING_*`, and the Minecraft login/play session on top is vanilla's own. An
  abnormal failure of an established stream ends the session `FAILED` with a
  `TransportFailure` category; `DISCONNECTED` is reserved for normal endings.

## Session coordination (M3)

The join flow: invite code → backend resolution → short-lived single-use guest
capability → host validates the capability → guest reaches Minecraft login → vanilla
Minecraft authentication remains authoritative for player identity.

**Protocol** — JSON over HTTP, version 1; every request versioned and size-capped
(8 KiB, rejected unread beyond), unknown versions rejected with
`unsupported_protocol`, unknown fields ignored (additive evolution):

```
POST /v1/sessions                {protocolVersion, addresses[], port}
  -> {sessionId, hostToken, inviteCode, admissionKey, expiresAt, heartbeatSeconds}
POST /v1/sessions/{id}/heartbeat {protocolVersion, hostToken}  -> {expiresAt}
POST /v1/sessions/{id}/close     {protocolVersion, hostToken}  -> ok
POST /v1/join                    {protocolVersion, inviteCode}
  -> {sessionId, addresses[], port, capabilityToken, capabilityExpiresAt}
```

(There is deliberately no `/resolve`: the guest flow needs only `/join`, and a second
code-probing endpoint would be pure attack surface.)

**Invite codes** — 8 symbols from a 32-char Crockford-style alphabet (no I/L/O/U) =
40 bits of entropy, shown as `K7M2-PQ9X`; generated server-side from SecureRandom,
normalized forgivingly (case, separators, look-alike mapping), stored and compared
only as SHA-256 hashes, never logged raw, carrying zero network information, expiring
with their session, and shielded by per-address token-bucket rate limiting (burst 10,
1/s sustained by default — enumerating a 2^40 space at that rate is hopeless).

**Admission capability** — `NXC1.<sessionId>.<capId>.<expiresAt>.<hmac>`:
HMAC-SHA256 keyed by a per-session admission key the backend generates at session
creation and shares only with that session's host. 128-bit random capability id,
30-second TTL, session-bound under the MAC, verified constant-time by the host itself
(no backend round-trip at admission time), single-use via a host-side replay guard,
and presented as the stream's very first bytes (`NXSA` + u16 length + token) so no
byte reaches the Minecraft bridge unadmitted. Rejection costs the host one HMAC.

**Why not Mojang joinServer/hasJoinedServer as the admission identity** (resolving the
review guardrail): that exchange is specified for the actual server-login handshake;
invoking it again with a synthetic server id immediately before the real login would
race the session server's per-user state, would use the guest's access token outside
the vanilla flow, and would buy nothing — vanilla authentication runs, authoritatively,
seconds later on the same connection. Mojang's protocol is left untouched; Nexus
admission is capability-based and identity-free.

**Backend data & retention** — per session: session id, invite-code hash, a one-way
SHA-256 **verifier** of the host bearer token (never the token — it is 256 random
bits, so a digest verifier compared timing-safely is correct and a password KDF would
be pointless cost), admission key, registered addresses + port, timestamps. There is
no field for world data, chat, inventories, mod JARs, filesystem paths, or
Microsoft/Mojang tokens. Sessions die on explicit close (Stop Hosting; bounded
best-effort so an unreachable backend cannot stall the Stop button) or after 90 s of
missed heartbeats; capabilities are never stored anywhere (the backend mints, the host
verifies). The store is in-memory behind the `SessionStore` interface — the seam for
persistent/distributed storage if scale ever demands it. Every store mutation is an
**atomic state transition** (per-key `compute`), never lookup-then-update: heartbeat
verifies token + liveness and refreshes in one step, close cannot be raced into
resurrection, and expiry removes a session only if it is *still* stale at the atomic
point of removal.

### M3 security hardening (audit-driven)

**Plaintext policy** — the client refuses to build any request unless the backend URL
is `https://` (any host) or `http://` with a host that is unambiguous *by URL text
alone*: a literal IPv4 address in 127.0.0.0/8, the literal IPv6 address `::1`, or the
exact hostname `localhost` (case-insensitive, allow-listed as a fixed string — not a
general resolution path). Any other hostname is rejected over `http://`, even one that
*currently* resolves only to loopback addresses: trusting a resolved-loopback hostname
would validate against one DNS answer and connect against another, which is exactly
the DNS-rebinding shape, so the policy never calls DNS to decide this at all. This also
rejects deceptive names like `127.0.0.1.evil.example` (a hostname, not an address,
regardless of what it resolves to) and URLs with userinfo. No host token, admission
key, capability, or route data can travel over remote plaintext. The backend binds
`127.0.0.1` by default (`NEXUS_BACKEND_BIND` widens deliberately); TLS termination for
remote deployments is external (reverse proxy), and that topology is the assumed
production shape.

**Clock skew** — capability acceptance tolerates ±10 s
(`AdmissionCapability.MAX_CLOCK_SKEW_SECONDS`): accepted iff
`now ≤ expiresAt + 10` and `expiresAt ≤ now + TTL + 10`. The second bound rejects
future-dated tokens, so validity is never longer than TTL + 2·skew = 50 s under any
clock error. 10 s keeps badly-drifting consumer machines working (NTP-synced ones are
within fractions of a second) while staying well inside the 30 s replay-guard
retention, which is keyed to `expiresAt` and deliberately independent of acceptance.
Trade-off accepted: a replayed token within the skew window is caught by the replay
guard, not by expiry — which is exactly what the replay guard is for.

**Admission resource bounds (host)** — before the preamble read even starts:
per-source token bucket (burst 5, 1/s), global bucket (burst 20, 5/s), and a pending
cap (16 concurrent pre-auth handshakes, counted separately from admitted guests, so
floods cannot bypass `maxGuests`); a silent guest holds its slot at most 5 s. All
configurable via `nexus.host.*` system properties, validated at startup
(`HostLimits`). The replay guard is hard-capped at 4096 entries and **fails closed**
— refusing new capabilities rather than evicting consumed ones.

**In-flight ownership** — every async host operation (admission handshake, loopback
dial, backend registration) is registered in an `OperationLedger` *before it starts*
and completes only when its resources are released or handed to a longer-lived owner.
`stop()` closes the gates, closes the listener, **drains the ledger**, then snapshots
the (now-complete) guest set — so a dial or registration completing after stop began
cleans itself up inside its tracked operation, and nothing attaches late. A backend
registration that lands after stop is immediately closed on the backend rather than
becoming coordination state.

**Route trust boundary** — the backend is authoritative for a session's routes and
validates them at registration (`RoutePolicy`: bare IPv4/IPv6/hostname only, no
URI-ish input, bounded count/length; DEVELOPMENT mode allows loopback/private,
PRODUCTION refuses loopback/link-local). The guest **re-validates** every granted
address through the same policy before dialing, so a compromised backend cannot make
Nexus dial arbitrary targets. ICE candidates at M4 get their own validator; the
mode split carries over.

**Rate limiting (backend)** — operation-aware buckets: guest joins, session creation,
and host heartbeat/close draw from separate per-address limiters, so join floods
cannot starve a host's keep-alive. Bucket storage is hard-capped (fail-closed for new
keys at capacity) and pruned safely under per-bucket locks. `X-Forwarded-For` is
ignored unless the connection comes from an explicitly configured trusted proxy
(`NEXUS_TRUSTED_PROXIES`), in which case only the last hop (the one that proxy
appended) is believed.

**Coordination loss** — if heartbeats hit a definitive `not_found` (backend restarted
or session expired) or fail 3 consecutive times, the host marks coordination lost:
the world stays up for connected players, the UI stops advertising the dead invite
code and says to re-host, and a later successful heartbeat (transient outage) clears
the flag.

**Secret logging** — nothing logs host tokens, admission keys, or capabilities,
anywhere. One documented exception: the property-gated dev-test harness logs the
ephemeral invite code of its own throwaway session, because the scripted two-client
test must hand that code to the guest.

## Failure taxonomy

Every player-visible failure carries a `TransportFailure` category with a jargon-free
summary, a consequence line, and a retryability flag. "Connection failed" with no
category is a bug by definition.

## Threading

Nexus owns its own daemon event-loop threads (`nexus-tcp-*`) and never schedules work on
Minecraft's Netty groups, the server thread, or the render thread. UI updates go through
`Minecraft.execute`. Blocking work (Mojang HTTP, ICE, relay negotiation) belongs on a
dedicated executor.

## Leak policy

Session-owned resources — channels, sockets, streams, pumps, per-session executors —
must all be released when hosting stops; `PeerTransport.close()` completes only when
that is true, and the test-suite asserts no `nexus-tcp-*` thread survives it.
`HostSessionService.stop()` follows the same contract: its future (one shared future,
created eagerly, returned to every caller including concurrent losers) completes only
after the listener, all pumps, all loopback dialers and their event loops, and the
integrated server's unpublish have finished — and `current()` is cleared only at that
point, so a new session can never start on top of a half-stopped one. A deliberately
process-lifetime runtime (if one is ever introduced) is not a leak, but must be
documented here.

## Roadmap and release gates

The next work is deliberately gated:

| Gate | Required evidence | Unlocks |
|---|---|---|
| M4-A transport spike | One ICE-nominated UDP socket carries QUIC directly and through a TURN allocation. | Production Internet transport design. |
| Authenticated join | Two real Microsoft/Minecraft accounts complete the invite-to-PLAY flow. | Claiming authenticated end-to-end verification. |
| M4/M5 integration | Direct route and relay fallback pass failure, cleanup, and abuse tests. | Broader external testing. |
| Release readiness | Reproducible artifact, compatibility matrix, security review, player documentation, and no open release-blocking defects. | First production-ready release. |

Until these gates pass, the TCP route and local backend are development infrastructure,
not a promise of Internet-ready hosting.

## Decision log

- **2026-08-31 — Target Minecraft 26.2 / JDK 25 / Fabric.** Minecraft 26.1+ ships
  unobfuscated with no mappings step. The toolchain auto-provisions JDK 25 via foojay.
- **2026-08-31 — MPL-2.0, © 2026 hamshamb.** Apache-2.0 dependencies are compatible.
- **2026-08-31 — Loopback-hop bridge over synthetic channels** for the auth-preservation
  reasons above.
- **2026-08-31 — Netty 4.2 mainline QUIC planned for direct Internet transport.** The
  incubator repository is archived. QUIC remains strictly behind `PeerTransport`
  because there is no windows-aarch_64 native.
- **2026-08-31 — Loom 1.17.20 stable** rather than the template's 1.17-SNAPSHOT: a
  pinned release beats a moving snapshot for reproducible builds.
- **Binding for M4:** before implementing direct Internet connectivity,
  a focused spike must prove that the exact ICE-nominated UDP socket can carry Netty
  QUIC without creating a different socket/NAT mapping, and that the same architecture
  works through a TURN allocation. Do not assume the handoff works because both halves
  work independently.
- **Binding for M3:** the proposed Nexus-layer use of Mojang
  joinServer/hasJoinedServer for session admission is UNVERIFIED and must be researched
  before implementation. Prefer a Nexus one-time session capability (e.g. a
  single-use token bound to the invite) followed by normal Minecraft authentication if
  that avoids duplicating Mojang auth.
