# Implementation Status

This file separates implementation from evidence. A feature is never reported above
the strongest level actually demonstrated.

| Level | Meaning |
|---|---|
| Designed | The behavior and boundaries are documented. |
| Implemented | Code exists, but broader evidence may be missing. |
| Unit tested | Isolated automated tests pass. |
| Integration tested | Multiple real components pass together. |
| Manually verified | A documented hands-on scenario passed. |
| Production ready | Release, compatibility, security, and operational gates all pass. |

## Current milestone

| Milestone | State | Next gate |
|---|---|---|
| M0–M2 foundations and local bridge | Manually verified | Two-account authenticated join remains external verification. |
| M3 invite-code coordination | Implemented, security-hardened, and tested | Continue regression testing as transport work lands. |
| M4-A ICE→QUIC/TURN spike | Ready to begin | Prove socket handoff directly and through TURN. |
| M4 production Internet transport | Not started | Blocked on the M4-A result. |
| Production release | Not ready | Requires all release gates in `ARCHITECTURE.md`. |

## M3 security + lifecycle hardening (audit-driven)

| Audit item | Status |
|---|---|
| 1. Remote plaintext HTTP refused before any secret travels (loopback-only http, https elsewhere, deceptive hosts rejected) | UNIT TESTED (6 policy tests) + MANUALLY VERIFIED |
| 2. In-flight ownership: stop() awaits admissions, pending dials, bridges, listeners, event loops, coordination, unpublish (`OperationLedger`) | UNIT TESTED (4 deterministic race tests) + MANUALLY VERIFIED (stop during pending admission) |
| 3. Late backend registration race: a create completing after stop is closed immediately, never installed | IMPLEMENTED (ledger-owned registration; same drain path) |
| 4. Atomic session transitions (heartbeat/close/expiry via per-key compute; refreshed session never reaped on stale observation) | CONCURRENCY TESTED (5 tests × 2000 race rounds) |
| 5. Capability clock skew: ±10 s bounded both directions; window ≤ TTL+2·skew; replay retention independent | UNIT TESTED (7 boundary tests) |
| 6. Admission resource exhaustion: pending cap + per-source + global rate limits before any expensive work; validated configurable defaults | UNIT TESTED (5 tests incl. 500-connection concurrent flood) |
| 7. Backend executor lifecycle: stop() terminates every owned worker | LIFECYCLE TESTED (start→stop→start→stop, zero-thread assertion) + MANUALLY VERIFIED |
| 8. Route trust boundary: backend authoritative + guest re-validation via shared `RoutePolicy`; dev/production range modes | UNIT TESTED (hostile-route tests both sides) |
| 9. Operation-aware rate limiting; bounded fail-closed bucket storage; XFF only from configured trusted proxies | UNIT + INTEGRATION TESTED (join flood does not starve heartbeats) |
| 10. Replay guard hard-capped (4096), fail-closed, prune-recoverable | UNIT TESTED |
| 11. SessionClient response bodies bounded (64 KiB, typed failure) | UNIT TESTED (hostile-backend test) |
| 12. All security/resource config validated at startup (backend + host limits) | UNIT TESTED |
| 13. Secret logging: tokens/keys/capabilities never logged; dev-harness invite-code logging explicitly documented as the one exception | IMPLEMENTED + documented |
| 14. `/resolve` removed from the API (join is the only guest surface) | IMPLEMENTED + tested (404) |
| 15. Host token stored only as SHA-256 verifier, compared timing-safely | IMPLEMENTED + tested |
| 16. Coordination loss detected (not_found or 3 failed heartbeats); dead code no longer advertised; recovery on success | IMPLEMENTED + MANUALLY VERIFIED |

## M3 — Invite-code session coordination

| Item | Status |
|---|---|
| Backend (create/join/heartbeat/close/expiry, protocol validation, operation-aware rate limiting, 8 KiB payload cap — no `/resolve`: join alone carries the guest flow) | INTEGRATION TESTED (9 tests over real HTTP) + MANUALLY VERIFIED |
| Invite codes (40-bit, Crockford-style alphabet, `K7M2-PQ9X`, hashed storage, no network info) | UNIT TESTED (6 tests incl. 100k-draw collision sanity) |
| Admission capability (HMAC, 30 s TTL, session-bound, single-use, host-verified) | UNIT TESTED (7 tests) + INTEGRATION TESTED + MANUALLY VERIFIED (live replay rejected) |
| Admission handshake (stream preamble, handoff stream, timeout, garbage rejection) | UNIT TESTED (5 real-socket tests) + MANUALLY VERIFIED |
| Session client (async, typed errors, session-owned lifecycle) | INTEGRATION TESTED via backend tests + MANUALLY VERIFIED |
| Host wiring (register on start, heartbeat, close on stop folded into truthful stop) | MANUALLY VERIFIED |
| Guest joins by code only (invite code → `/join` → capability + route grant → admission handshake → `PeerTransport` bridge → vanilla Minecraft login) | MANUALLY VERIFIED — two clients; the wire probe received 0x01 through the code-resolved path |
| TTL expiry of an orphaned session (host hard-killed) | MANUALLY VERIFIED |
| Minimal UI (invite code + Copy on host; code field on guest) | IMPLEMENTED (M8 remains the polish milestone) |

## M0 — Skeleton

| Item | Status |
|---|---|
| Gradle 9.5.1 wrapper, version catalog, module graph | DONE |
| JDK 25 toolchain auto-provisioning (foojay) | DONE — verified: classes compile at major version 69 |
| MPL-2.0 license + headers | DONE |
| Docs (CLEAN_ROOM, THIRD_PARTY, ARCHITECTURE, LICENSE_DECISION, this file) | DONE |
| Fabric mod loads and logs | MANUALLY VERIFIED — mod + 4 client mixins load in the 26.2 dev client with zero mixin errors |

## Core / transport (M0–M2 foundations)

| Feature | Status |
|---|---|
| Host/guest session state machines (`core`) | UNIT TESTED (7 tests) |
| Invite codes | IMPLEMENTED in `session-protocol` during M3 (40-bit Crockford-style, `K7M2-PQ9X`, hashed storage) — intentionally not part of `core`, which stays pure session state machines with no protocol/wire concerns |
| `PeerTransport`/`PeerStream` contract with flow control | UNIT TESTED (deterministic backpressure law: 3 scripted-stream tests; plus exercised via TCP impl) |
| `StreamPump` lifecycle races (disconnect-before-handler, dead-stream pump) | UNIT TESTED (3 regression tests) |
| TCP transport (LAN route) | UNIT TESTED (11 tests: fidelity, fragmentation, backpressure, close propagation, failure categories, single-accept retirement, thread-leak assertion) |
| `BridgeTransportFactory` boundary (bridge never names a concrete transport; TCP composed only in `NexusTransports`) | IMPLEMENTED |
| Failure taxonomy (`TransportFailure`) | IMPLEMENTED |

## M1 — Host lifecycle

| Item | Status |
|---|---|
| Publish-with-loopback mixin + LAN-pinger suppression (incl. socket close) | IMPLEMENTED |
| Bound-port readback via channels accessor | IMPLEMENTED |
| HostSessionService (state machine, admission cap, teardown, leak report) | IMPLEMENTED |
| SERVER_STOPPING teardown hook | IMPLEMENTED |

## M2 — First vertical slice

| Item | Status |
|---|---|
| StreamPump (the bridge core, with the flow-control law) | UNIT TESTED (4 socket-level tests incl. 16 MiB through the chain) |
| GuestSessionService (dial, loopback listener, ConnectScreen handoff) | IMPLEMENTED |
| Minimal UI (Host/Join/Failure screens, pause + multiplayer buttons) | IMPLEMENTED |
| Dev-test driver (auto world create/host/join via system properties) | MANUALLY VERIFIED |
| Two-client bridge test (dev accounts) | MANUALLY VERIFIED — full chain worked: guest dialed the Nexus listener, vanilla client bridged through the tunnel, host server received the login. The unauthenticated dev account was then correctly REFUSED by Mojang auth (see below). |
| Authentication-preserved evidence | MANUALLY VERIFIED — wire-level probe (`scripts/auth_probe.py`) through the tunnel received packet 0x01 (encryption request) — the server demands RSA key exchange + Mojang session auth. Negative control: Minecraft's port refused connections from a LAN address (loopback bind held); the offline-profile shortcut never fired. |
| Authenticated end-to-end join (two real Microsoft accounts) | **BLOCKED (external — the one remaining Phase 1 item)**: requires two real accounts; dev accounts cannot complete Mojang session auth *precisely because* auth is preserved. Needs the project owner. |
| Host lifecycle on world close | MANUALLY VERIFIED — ONLINE→STOPPING→STOPPED on world close, "session resources released", no LEAK lines, all dimensions saved. |
| Start→Stop→Start→Stop without leaving the world | see hardening-pass table below |

## Phase 1 hardening pass (review-driven)

| Fix | Status |
|---|---|
| NettyPeerStream disconnect-before-handler race (late handler notified exactly once; pumps on dead streams complete) | UNIT TESTED (2 regression tests) |
| Guest single-loopback guarantee (atomic claim + immediate `stopAccepting`; second connection refused at TCP level; no second pump ever touches the host stream) | UNIT TESTED (1 regression test at transport level) + IMPLEMENTED in GuestSessionService |
| `HostSessionService.stop()` truthfulness (eager shared future; completes only after listener + pumps + dialers + event loops + unpublish; CURRENT cleared last) | IMPLEMENTED |
| No blocking `get()`/`join()` on render/server/transport threads (host + guest startup fully chained) | IMPLEMENTED |
| Transport boundary (`BridgeTransportFactory`; concrete TCP only in `NexusTransports`) | IMPLEMENTED |
| Deterministic backpressure law test (unwritable sink pauses source; writable resumes) | UNIT TESTED (3 tests, scripted streams) |
| Guest state semantics (CONNECTED_* only when the vanilla client is bridged; abnormal stream failure → FAILED, never DISCONNECTED) | IMPLEMENTED |
| Start→Stop→Start→Stop in one world session (`-Dnexus.devtest.role=hostcycle`) | MANUALLY VERIFIED — 2 full cycles, fresh loopback port each round, vanilla unpublish awaited, "session resources released" each stop, world and integrated server usable throughout, CYCLE PASSED verdict |

## Binding engineering guardrails

1. The Phase 1 bridge/auth-preservation gate is **satisfied**: the two-client Nexus
   transport test reached vanilla Minecraft login, the wire-level probe verified
   packet 0x01 (encryption request), and the offline-profile shortcut did not occur.
   This does not extend to the separate real-account happy-path verification below,
   which remains open — but it does not reopen or unapprove Phase 1 itself.
2. Only minimum functional UI before M8.
3. M4 production integration requires the ICE-socket→QUIC handoff spike first (M4-A,
   an isolated feasibility spike — see ARCHITECTURE decisions); M4-A itself does not
   wire anything into `HostSessionService` or the production transport selection.
4. Nexus-layer Mojang join/hasJoined admission was researched and deliberately
   rejected for M3 (see ARCHITECTURE: capability-based admission, vanilla auth
   untouched).
5. Leak = zero session-owned resources after hosting stops; process-lifetime runtime is
   not automatically a leak.

## Known blockers

- **External verification gate**: authenticated end-to-end join with two real
  Microsoft/Minecraft accounts is **PENDING EXTERNAL MANUAL VERIFICATION**. Current
  evidence *does* establish: the guest connection crosses the Nexus transport, the
  host integrated server sees a real socket path, a vanilla encryption request
  occurs, vanilla online authentication remains enforced, and an unauthenticated dev
  account is correctly refused. Current evidence does *not yet* establish:
  `hasJoinedServer`/session verification succeeding for a real guest account, an
  authenticated guest reaching PLAY state, or an authenticated guest visibly entering
  the hosted world. This item is not to be marked verified until that test is
   actually performed with two authenticated accounts.
- **M4 production integration** is gated on completion and review of the M4-A
  ICE→QUIC/TURN feasibility spike; no ICE/STUN/TURN/QUIC code exists yet.

## Evidence references

Minecraft 26.2 implementation facts are recorded in [ARCHITECTURE.md](ARCHITECTURE.md),
and the verified dependency matrix is in [THIRD_PARTY.md](THIRD_PARTY.md). Automated
evidence lives beside the relevant modules under `src/test`.
