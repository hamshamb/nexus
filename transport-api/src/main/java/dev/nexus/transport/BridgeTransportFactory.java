/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

/**
 * Creates the transports the Minecraft bridge composes, so the bridge logic itself never
 * names a concrete transport.
 *
 * <p>Two of the roles are route-specific and change when ICE/QUIC/relay arrive; two are
 * bridge-local plumbing that stays plain TCP by design (they carry the vanilla client's
 * and integrated server's own localhost connections):
 *
 * <ul>
 *   <li>{@link #createGuestListener()} — route-specific: where remote guests reach the
 *       host. Phase 1 binds a direct TCP port; later routes listen on ICE/relay
 *       rendezvous instead.</li>
 *   <li>{@link #createLoopbackListener()} — bridge-local: the loopback socket the
 *       guest's own vanilla client dials.</li>
 *   <li>{@link #createLoopbackDialer(int)} — bridge-local: the loopback connection into
 *       the host's integrated server on the given port.</li>
 * </ul>
 *
 * <p>The guest's route to the host is not created here: the join flow receives an
 * already-built {@link PeerTransport.Dialer}, because how that dialer is constructed
 * (direct address today; resolved session candidates later) is the caller's concern.
 */
public interface BridgeTransportFactory {

    /** A listener remote guests can connect to. The caller owns it. */
    PeerTransport.Listener createGuestListener();

    /** A loopback-bound single-purpose listener for the local vanilla client. */
    PeerTransport.Listener createLoopbackListener();

    /** A dialer to the integrated server's loopback port. The caller owns it. */
    PeerTransport.Dialer createLoopbackDialer(int port);
}
