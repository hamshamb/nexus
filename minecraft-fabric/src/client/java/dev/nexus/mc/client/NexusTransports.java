/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client;

import dev.nexus.transport.BridgeTransportFactory;
import dev.nexus.transport.PeerTransport;
import dev.nexus.transport.tcp.TcpTransport;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * The one place in the mod that names concrete transports.
 *
 * <p>Everything else -- host session, guest session, UI -- works against
 * {@code transport-api} types, so swapping the guest-facing route for ICE/QUIC/relay in
 * M4 means changing this class (and the join flow's dialer construction), nothing in the
 * bridge.
 */
public final class NexusTransports {

    private NexusTransports() {
    }

    /** Phase 1 composition: direct TCP for the guest route, TCP for the loopback halves. */
    public static BridgeTransportFactory directTcp() {
        return new BridgeTransportFactory() {
            @Override
            public PeerTransport.Listener createGuestListener() {
                // All interfaces, ephemeral port: LAN guests connect here.
                return TcpTransport.listen(new InetSocketAddress((InetAddress) null, 0));
            }

            @Override
            public PeerTransport.Listener createLoopbackListener() {
                return TcpTransport.listen(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            }

            @Override
            public PeerTransport.Dialer createLoopbackDialer(int port) {
                return TcpTransport.dial(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            }
        };
    }

    /** Phase 1 guest route: a direct TCP dial to the address the host shared. */
    public static PeerTransport.Dialer directDialer(InetSocketAddress address) {
        return TcpTransport.dial(address);
    }
}
