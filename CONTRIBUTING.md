# Contributing to Nexus

Thanks for helping improve Nexus. The project is still experimental, so small,
well-tested changes are easier to review than broad rewrites.

## Before you begin

1. Read the [clean-room rules](docs/CLEAN_ROOM.md). They apply to code, assets,
   protocols, UI, documentation, and AI-assisted work.
2. Check the [implementation status](docs/IMPLEMENTATION_STATUS.md) and
   [architecture](docs/ARCHITECTURE.md) for current gates and decisions.
3. For a security problem, follow [SECURITY.md](SECURITY.md) instead of opening a
   public issue.

## Development setup

Nexus targets Minecraft Java 26.2 and JDK 25. The Gradle wrapper can provision the
required Java toolchain.

```bash
./gradlew build
```

On Windows, use `.\gradlew.bat build`.

Run the complete test suite before submitting a change:

```bash
./gradlew test
```

## Project expectations

- Keep Minecraft-specific code inside `minecraft-fabric`.
- Keep connectivity implementations behind the contracts in `transport-api`.
- Preserve normal Minecraft authentication; Nexus admission is an additional gate,
  not a replacement for Mojang authentication.
- Make resource ownership explicit and leave no session-owned threads, sockets, or
  streams after shutdown.
- Never log invite codes, host tokens, admission keys, capability tokens, account
  tokens, or credentials. The property-gated local test harness is the documented
  exception for its own temporary invite code.
- Validate untrusted network data before using it and keep all protocol inputs bounded.
- Do not commit `.env` files, access tokens, API keys, account data, run directories,
  or generated build output.

## Dependencies

Before adding or updating a dependency:

1. Verify its license from its own POM or repository.
2. Record the exact coordinates, version, SPDX identifier, and purpose in
   [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
3. Run the full build and review its transitive dependency tree.

## Pull requests

A pull request should:

- explain the user-visible or architectural reason for the change;
- include tests for new behavior and regressions;
- update relevant documentation and status claims;
- avoid unrelated formatting or refactoring;
- state what was manually verified and what remains unverified; and
- pass `./gradlew build`.

Use the status vocabulary in [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md).
Do not label work production-ready without the required evidence.
