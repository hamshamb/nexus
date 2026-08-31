/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.mixin.client;

import dev.nexus.mc.client.host.HostBridgeFlags;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Adjusts {@code IntegratedServer.publishServer} while -- and only while -- a Nexus
 * publish is in progress.
 *
 * <p>Vanilla {@code publishServer(MultiplayerScope, int)} does two things Nexus must not
 * let happen (verified in the 26.2 bytecode):
 *
 * <ul>
 *   <li>{@code startTcpServerListener(null, port)} -- a wildcard bind, exposing the world
 *       to the whole LAN with no Nexus-level admission control;</li>
 *   <li>starting a {@link LanServerPinger}, which multicasts the world's existence and
 *       port to everyone on the network.</li>
 * </ul>
 *
 * <p>Nexus needs everything else the method does (published state so the tick loop keeps
 * running with a screen open, gamemode/commands wiring, scope), so rather than
 * reimplement it we redirect just those two calls. Vanilla "Open to LAN" is untouched:
 * when {@link HostBridgeFlags#isNexusPublishActive()} is false both redirects fall
 * through to stock behaviour.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Redirect(
            method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerConnectionListener;"
                            + "startTcpServerListener(Ljava/net/InetAddress;I)V"))
    private void nexus$bindLoopbackOnly(ServerConnectionListener listener,
                                        InetAddress address, int port) throws IOException {
        if (HostBridgeFlags.isNexusPublishActive()) {
            // Guests reach this port only through the Nexus bridge, never directly.
            listener.startTcpServerListener(InetAddress.getLoopbackAddress(), port);
        } else {
            listener.startTcpServerListener(address, port);
        }
    }

    @Redirect(
            method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/LanServerPinger;start()V"))
    private void nexus$suppressLanAdvertising(LanServerPinger pinger) {
        if (HostBridgeFlags.isNexusPublishActive()) {
            // Never started, so it never advertises. Its constructor already opened a
            // DatagramSocket, and interrupt() does not close it (verified in bytecode) --
            // close it here or it would outlive the session.
            pinger.interrupt();
            ((LanServerPingerAccessor) pinger).nexus$getSocket().close();
        } else {
            pinger.start();
        }
    }
}
