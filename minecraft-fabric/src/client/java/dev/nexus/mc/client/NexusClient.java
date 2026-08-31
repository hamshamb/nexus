/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client;

import dev.nexus.mc.NexusMod;
import dev.nexus.mc.client.guest.GuestSessionService;
import dev.nexus.mc.client.host.HostSessionService;
import dev.nexus.mc.client.ui.NexusHostScreen;
import dev.nexus.mc.client.ui.NexusJoinScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * Client entry point: wires the two Nexus entry buttons into vanilla screens and ties
 * session teardown to the game's own lifecycle.
 *
 * <p>Entry points (Phase 1 minimum, both replaced by the polished M8 flow):
 * <ul>
 *   <li>Pause screen → "Host World" (only while a single-player world is open);</li>
 *   <li>Multiplayer screen → "Join Hosted World".</li>
 * </ul>
 */
public final class NexusClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof PauseScreen && client.getSingleplayerServer() != null) {
                Screens.getWidgets(screen).add(Button.builder(
                                Component.literal(HostSessionService.current() == null
                                        ? "Host World" : "Nexus: Online"),
                                b -> client.gui.setScreen(new NexusHostScreen(screen)))
                        .bounds(5, 5, 98, 20)
                        .build());
            } else if (screen instanceof JoinMultiplayerScreen) {
                Screens.getWidgets(screen).add(Button.builder(
                                Component.literal("Join Hosted World"),
                                b -> client.gui.setScreen(new NexusJoinScreen(screen)))
                        .bounds(5, 5, 118, 20)
                        .build());
            }
        });

        // Guest teardown: whenever the client leaves a world for any reason, a live
        // guest session's resources must go with it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GuestSessionService guest = GuestSessionService.current();
            if (guest != null) {
                guest.close("left the world");
            }
        });

        // Host teardown: the integrated server stopping (world closed, quit to title,
        // crash-initiated shutdown) always ends the hosting session, even if the player
        // never pressed Stop Hosting.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            HostSessionService host = HostSessionService.current();
            if (host != null) {
                host.stop("world closing");
            }
        });

        // No-op unless the nexus.devtest.* system properties are present.
        dev.nexus.mc.client.devtest.NexusDevTest.install();

        NexusMod.LOGGER.info("Nexus loaded (client)");
    }
}
