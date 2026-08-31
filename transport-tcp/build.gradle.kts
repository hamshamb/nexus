// Plain-TCP PeerTransport implementation.
//
// This is a real, permanent transport -- not throwaway scaffolding. It is the
// LAN/direct-IP route, and it is what the transport test-suite runs against, so the
// bridge can be exercised and regression-tested without any NAT traversal,
// signalling backend, or relay infrastructure present.
dependencies {
    api(project(":transport-api"))
    implementation(project(":core"))
}
