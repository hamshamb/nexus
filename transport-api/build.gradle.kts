// The transport contract. Deliberately depends on Netty's buffer/transport types only:
// PeerStream is expressed in ByteBuf and Netty futures so a Netty channel (a QUIC
// stream channel, from M4) can implement it directly without an adapter layer.
// It must never depend on Minecraft or on any concrete transport.
dependencies {
    api(libs.bundles.netty)
}
