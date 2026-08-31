// Coordination client for the mod: talks to the backend over java.net.http (JDK
// built-in, no new dependencies) and performs the on-stream admission handshake.
dependencies {
    api(project(":session-protocol"))
    api(project(":transport-api"))

    // Real-socket tests for the admission handshake ride on the TCP transport.
    testImplementation(project(":transport-tcp"))
}
