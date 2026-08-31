// The coordination protocol, shared verbatim by the backend and the session client:
// message shapes, invite codes, and the one-time admission capability. Pure JVM +
// Gson; no Minecraft, no Netty, no I/O.
dependencies {
    api(libs.gson)
}
