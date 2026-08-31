/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.ui;

import dev.nexus.core.session.HostSessionState;
import dev.nexus.mc.NexusMod;
import dev.nexus.mc.client.NexusTransports;
import dev.nexus.mc.client.host.HostSessionService;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * The minimum functional Host World screen for Phase 1: start hosting, see where guests
 * connect, stop hosting. The polished flow (player cap, gamemode, difficulty, cheats)
 * is milestone M8; nothing here should grow before then.
 */
public final class NexusHostScreen extends Screen {

    /** Phase 1 default; host controls arrive at M6. */
    private static final int DEFAULT_MAX_GUESTS = 8;

    private final Screen parent;

    public NexusHostScreen(Screen parent) {
        super(Component.literal("Host This World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        HostSessionService session = HostSessionService.current();

        addRenderableWidget(new StringWidget(
                centerX - 150, 40, 300, 12, title, font));

        if (session == null) {
            addRenderableWidget(new StringWidget(
                    centerX - 150, 70, 300, 12,
                    Component.literal("Let friends join this world over the network."),
                    font));
            addRenderableWidget(Button.builder(Component.literal("Start Hosting"),
                            b -> startHosting())
                    .bounds(centerX - 100, 100, 200, 20)
                    .build());
        } else {
            HostSessionState state = session.state();
            String code = session.inviteCode();
            addRenderableWidget(new StringWidget(
                    centerX - 150, 70, 300, 12,
                    Component.literal(state == HostSessionState.ONLINE
                            ? "World online — players: "
                                    + session.guestCount() + " / " + DEFAULT_MAX_GUESTS
                            : "Starting… (" + state + ")"),
                    font));
            boolean codeUsable = code != null && session.coordinationHealthy();
            addRenderableWidget(new StringWidget(
                    centerX - 150, 92, 300, 12,
                    Component.literal(code == null
                            ? "Getting an invite code…"
                            : codeUsable
                            ? "Invite code: " + code
                            : "Invite code no longer active — stop and re-host for a new code"),
                    font));
            if (codeUsable) {
                addRenderableWidget(Button.builder(Component.literal("Copy"),
                                b -> minecraft.keyboardHandler.setClipboard(code))
                        .bounds(centerX - 100, 110, 200, 20)
                        .build());
            }
            addRenderableWidget(Button.builder(Component.literal("Stop Hosting"),
                            b -> stopHosting(session))
                    .bounds(centerX - 100, 140, 200, 20)
                    .build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"),
                        b -> onClose())
                .bounds(centerX - 100, height - 40, 200, 20)
                .build());
    }

    private void startHosting() {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            NexusMod.LOGGER.warn("Host World clicked with no singleplayer server");
            return;
        }
        try {
            HostSessionService session = HostSessionService.start(
                    server, NexusTransports.directTcp(), DEFAULT_MAX_GUESTS);
            // Startup is asynchronous; refresh this screen as the session advances.
            session.onTransition((from, to, reason) ->
                    minecraft.execute(this::rebuildIfShowing));
        } catch (RuntimeException e) {
            NexusMod.LOGGER.error("Could not start hosting", e);
        }
        rebuildWidgets();
    }

    private void stopHosting(HostSessionService session) {
        session.stop("Stop Hosting clicked")
                .whenComplete((v, t) -> minecraft.execute(this::rebuildIfShowing));
        rebuildWidgets();
    }

    private void rebuildIfShowing() {
        if (minecraft.gui.screen() == this) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
