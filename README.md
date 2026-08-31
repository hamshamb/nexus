# Nexus

Nexus is an experimental Fabric mod for hosting a Minecraft Java single-player world
over the Internet with an invite code: open a world, choose **Host World**, share the
code, and play.

> [!WARNING]
> Nexus is under active development. The local bridge and invite-code coordination
> flow work, but Internet transport is not implemented and a two-account authenticated
> join still needs manual verification. There is no production-ready release yet.

Nexus is independently engineered under documented
[clean-room rules](docs/CLEAN_ROOM.md). It focuses only on world hosting—there are no
cosmetics, chat, accounts, or social features.

## What works today

- Fabric mod UI for hosting and joining with an invite code.
- A loopback bridge that preserves Minecraft's normal login and Mojang authentication.
- A local coordination backend for session creation, joining, heartbeats, and expiry.
- Short-lived, single-use admission capabilities with replay and abuse protections.
- A reliable TCP transport used for local development and automated tests.
- Explicit lifecycle, backpressure, and resource-leak protections.

For evidence and exact confidence levels, see
[Implementation status](docs/IMPLEMENTATION_STATUS.md).

## What is planned

1. Prove the ICE-nominated socket can carry QUIC directly and through TURN.
2. Add secure direct Internet connectivity with relay fallback.
3. Verify the complete join flow using two authenticated Minecraft accounts.
4. Improve the player-facing setup, diagnostics, and accessibility.
5. Package reproducible releases once the security and compatibility gates pass.

The detailed constraints and design decisions are in
[Architecture](docs/ARCHITECTURE.md#roadmap-and-release-gates).

## Requirements

- Minecraft Java Edition 26.2.
- Fabric Loader 0.19.3 or newer.
- Fabric API 0.158.0+26.2.
- JDK 25 for development. Gradle can provision the toolchain automatically.

## Build from source

On macOS or Linux:

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The mod JAR is produced in `minecraft-fabric/build/libs/`. This is a development build;
do not treat it as a stable release.

Useful development commands:

| Task | Command |
|---|---|
| Run all tests | `./gradlew test` |
| Start a development client | `./gradlew :minecraft-fabric:runClient` |
| Start a second client | `./gradlew :minecraft-fabric:runGuestClient` |
| Start the local backend | `./gradlew :backend:run` |

Windows users can replace `./gradlew` with `.\gradlew.bat`.

## Local development flow

1. Start the backend. Its safe local defaults work without configuration.
2. Start the host development client and open a single-player world.
3. Choose **Host World** and copy the invite code.
4. Start the guest development client and join with that code.

Configuration is documented in [`.env.example`](.env.example). The client allows plain
HTTP only for an unambiguous loopback address; remote deployments must use HTTPS.

## Project layout

| Module | Responsibility |
|---|---|
| `core` | Pure host and guest session state machines. |
| `transport-api` | Reliable ordered stream and transport contracts. |
| `transport-tcp` | TCP implementation for local routes and tests. |
| `session-protocol` | Versioned messages, invite codes, rate limits, and admission capabilities. |
| `session-client` | Coordination client and admission handshake. |
| `backend` | In-memory session coordination service. |
| `minecraft-fabric` | Fabric integration, connection bridge, lifecycle, and UI. |

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — boundaries, protocol, threat model, and
  decisions.
- [Implementation status](docs/IMPLEMENTATION_STATUS.md) — completed work, evidence,
  blockers, and next milestones.
- [Clean-room rules](docs/CLEAN_ROOM.md) — permitted and prohibited source material.
- [Third-party dependencies](docs/THIRD_PARTY.md) — dependency purpose and licenses.
- [License decision](docs/LICENSE_DECISION.md) — why the project uses MPL-2.0.
- [Contributing](CONTRIBUTING.md) — development and pull-request expectations.
- [Security policy](SECURITY.md) — how to report vulnerabilities safely.

## Contributing

Contributions are welcome, but the clean-room and dependency-review rules are binding.
Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Please report
security issues privately as described in [SECURITY.md](SECURITY.md).

## License

Nexus is licensed under the [Mozilla Public License 2.0](LICENSE), © 2026 hamshamb.
