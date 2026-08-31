// The session-coordination backend: a deliberately boring, small modular monolith on
// the JDK's built-in HTTP server. In-memory session store for the M3 vertical slice;
// the SessionStore interface is the seam where persistent/distributed storage can
// arrive later, if scale ever proves it necessary.
plugins {
    application
}

dependencies {
    implementation(project(":session-protocol"))
}

application {
    mainClass.set("dev.nexus.backend.NexusBackend")
}
