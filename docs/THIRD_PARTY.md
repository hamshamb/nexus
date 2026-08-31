# Third-Party Dependencies

Every material dependency, its license, and why it is here. Licenses were read from the
artifact's own POM or repository, per `docs/CLEAN_ROOM.md`. Nexus itself is MPL-2.0
(© 2026 hamshamb); everything below is MPL-2.0-compatible.

## Maintenance rule

When a dependency changes, update this file in the same pull request. Verify the
license from the dependency's own POM or repository, review the transitive dependency
tree, and rerun the build. Planned dependencies must be rechecked before adoption;
their entries are research notes, not approval to add them unchanged.

## Runtime

| Dependency | Version | License (SPDX) | Why |
|---|---|---|---|
| io.netty:netty-buffer / netty-transport / netty-handler | 4.2.15.Final | Apache-2.0 | Transport plumbing: event loops, ByteBuf, watermark-based flow control. Pinned to the exact version Minecraft 26.2 bundles so the runtime has one Netty. 4.2 also carries the mainline QUIC codec planned for M4. |
| com.google.code.gson:gson | 2.14.0 | Apache-2.0 | Coordination-protocol JSON. Pinned to the exact version Minecraft 26.2 bundles. |
| net.fabricmc:fabric-loader | 0.19.3 | Apache-2.0 | Mod loader. |
| net.fabricmc.fabric-api:fabric-api | 0.158.0+26.2 | Apache-2.0 | Lifecycle events and screen API for the Minecraft integration. |

## Planned (M4/M5 — not yet added; re-verify versions before adding)

| Dependency | License (SPDX) | Notes |
|---|---|---|
| org.jitsi:ice4j (3.2-15-g6da2b08 at planning time) | Apache-2.0 | ICE/STUN/TURN client. License read from POM. Transitive Jitsi deps need a tree review when added. |
| io.netty:netty-codec-native-quic + netty-codec-classes-quic | Apache-2.0 | QUIC. Bundles quiche/BoringSSL natives; no windows-aarch_64 build (verified: classifier 404s on Maven Central) — Windows-on-ARM needs a fallback route. |
| coturn (server-side infrastructure, not a mod dependency) | BSD-3-Clause | TURN relay; supports ephemeral HMAC credentials. |

## Build / test only

| Dependency | Version | License (SPDX) | Why |
|---|---|---|---|
| Gradle | 9.5.1 | Apache-2.0 | Build. |
| fabric-loom | 1.17.20 | MIT | Fabric Gradle plugin (new no-remap line for MC 26.1+). |
| org.gradle.toolchains.foojay-resolver-convention | 1.0.0 | Apache-2.0 | Auto-provisions the JDK 25 toolchain. |
| org.junit.jupiter:junit-jupiter | 5.11.4 | EPL-2.0 | Tests. Test-only, not distributed. |
| org.assertj:assertj-core | 3.27.3 | Apache-2.0 | Test assertions. |

## Evaluated but not selected

| Dependency | Reason |
|---|---|
| `kwik` | Pure-Java QUIC, but LGPL-3.0-only and pre-1.0 at evaluation time. |
| `dev.onvoid.webrtc:webrtc-java` | Apache-2.0, but adds a large native libwebrtc maintenance burden without a browser-interoperability requirement. |
