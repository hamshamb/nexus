/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.devtest;

import dev.nexus.core.session.HostSessionState;
import dev.nexus.mc.NexusMod;
import dev.nexus.mc.client.NexusTransports;
import dev.nexus.mc.client.guest.GuestSessionService;
import dev.nexus.mc.client.host.HostSessionService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;


/**
 * Development-only driver for the two-client bridge test, so the vertical slice can be
 * exercised repeatably from the command line instead of by clicking through the GUI.
 *
 * <p>Enabled solely by system properties; when absent (every normal launch, and every
 * production launch) {@link #install()} returns before registering anything, keeping the
 * no-hosting overhead at zero.
 *
 * <p><strong>Secret-logging policy</strong>: this property-gated dev harness logs the
 * <em>ephemeral invite code</em> of its own throwaway session, because the two-client
 * test genuinely needs the code to drive the guest from the command line. That is a
 * deliberate, documented exception scoped to dev-test mode only. Nothing anywhere logs
 * host tokens, admission keys, or capabilities — in dev mode or otherwise.
 *
 * <pre>
 *   -Dnexus.devtest.role=host  [-Dnexus.devtest.world=nexus-test]
 *       auto-creates/opens the named world, then starts hosting and logs the endpoint.
 *   -Dnexus.devtest.role=hostcycle
 *       like host, but runs START -> STOP -> START -> STOP without leaving the
 *       world, verifying the integrated server stays usable across cycles.
 *   -Dnexus.devtest.role=guest -Dnexus.devtest.joincode=XXXX-XXXX
 *       auto-joins by invite code from the title screen.
 * </pre>
 */
public final class NexusDevTest {

    private static final String ROLE = System.getProperty("nexus.devtest.role");

    private NexusDevTest() {
    }

    public static void install() {
        if (ROLE == null) {
            return;
        }
        NexusMod.LOGGER.warn("NEXUS DEV TEST MODE ACTIVE: role={}", ROLE);
        switch (ROLE) {
            case "host" -> installHost(false);
            case "hostcycle" -> installHost(true);
            case "guest" -> installGuest();
            default -> NexusMod.LOGGER.error("Unknown nexus.devtest.role {}", ROLE);
        }
    }

    // ------------------------------------------------------------------ host

    private static void installHost(boolean cycle) {
        String worldName = System.getProperty("nexus.devtest.world", "nexus-test");

        ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
            private boolean worldRequested;
            private boolean hostRequested;
            /** For role=hostcycle: how many start/stop rounds have completed. */
            private int cyclesDone;

            @Override
            public void onEndTick(Minecraft client) {
                // Step 1: from the title screen, create or open the test world.
                if (!worldRequested && client.gui.screen() instanceof TitleScreen) {
                    worldRequested = true;
                    openOrCreate(client, worldName);
                    return;
                }
                // Step 2: once the player is actually in the world -- publishServer
                // needs the client's own connection for prepareKeyPair, so hosting can
                // only start from in-game, exactly like vanilla Open to LAN.
                if (worldRequested && !hostRequested
                        && client.player != null && client.getConnection() != null) {
                    IntegratedServer integrated = client.getSingleplayerServer();
                    if (integrated == null) {
                        return;
                    }
                    hostRequested = true;
                    startSession(client, integrated);
                }
            }

            private void startSession(Minecraft client, IntegratedServer integrated) {
                try {
                    HostSessionService session = HostSessionService.start(
                            integrated, NexusTransports.directTcp(), 8);
                    session.onTransition((from, to, reason) -> {
                        if (to == HostSessionState.ONLINE) {
                            NexusMod.LOGGER.warn(
                                    "NEXUS DEV TEST: hosting online, endpoint={} port={} inviteCode={}",
                                    session.endpointDescription(), session.listenerPort(),
                                    session.inviteCode());
                            if (cycle) {
                                scheduleStop(client, session);
                            }
                        }
                    });
                } catch (RuntimeException e) {
                    NexusMod.LOGGER.error("NEXUS DEV TEST: hosting failed", e);
                }
            }

            private void scheduleStop(Minecraft client, HostSessionService session) {
                // Optional delay (-Dnexus.devtest.stopDelayMs) so a manual test can
                // attach pending/in-flight connections before the stop races them.
                long delayMs = Long.getLong("nexus.devtest.stopDelayMs", 0L);
                if (delayMs > 0) {
                    java.util.concurrent.CompletableFuture
                            .delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> doScheduledStop(client, session));
                    return;
                }
                doScheduledStop(client, session);
            }

            private void doScheduledStop(Minecraft client, HostSessionService session) {
                // Hop to the client thread; stop, await full cleanup, then either start
                // the next round or report the verdict.
                client.execute(() -> session.stop("devtest cycle")
                        .whenComplete((v, t) -> client.execute(() -> {
                            cyclesDone++;
                            IntegratedServer integrated = client.getSingleplayerServer();
                            boolean worldAlive = integrated != null
                                    && client.player != null && client.level != null;
                            NexusMod.LOGGER.warn(
                                    "NEXUS DEV TEST: cycle {} stopped; world alive={}",
                                    cyclesDone, worldAlive);
                            if (!worldAlive) {
                                NexusMod.LOGGER.error(
                                        "NEXUS DEV TEST: CYCLE FAILED - world not usable");
                            } else if (cyclesDone < 2) {
                                startSession(client, integrated);
                            } else {
                                NexusMod.LOGGER.warn(
                                        "NEXUS DEV TEST: CYCLE PASSED - start/stop x{} "
                                        + "with world loaded and server usable", cyclesDone);
                            }
                        })));
            }
        });
    }

    private static void openOrCreate(Minecraft client, String worldName) {
        boolean exists;
        try {
            exists = client.getLevelSource().levelExists(worldName);
        } catch (Exception e) {
            NexusMod.LOGGER.error("NEXUS DEV TEST: could not check world existence", e);
            return;
        }
        if (exists) {
            NexusMod.LOGGER.warn("NEXUS DEV TEST: opening world {}", worldName);
            client.createWorldOpenFlows().openWorld(worldName, () ->
                    NexusMod.LOGGER.error("NEXUS DEV TEST: world open aborted"));
        } else {
            NexusMod.LOGGER.warn("NEXUS DEV TEST: creating world {}", worldName);
            LevelSettings settings = new LevelSettings(
                    worldName,
                    GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true,
                    WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel(
                    worldName,
                    settings,
                    WorldOptions.defaultWithRandomSeed(),
                    WorldPresets::createNormalWorldDimensions,
                    null);
        }
    }

    // ----------------------------------------------------------------- guest

    private static void installGuest() {
        String code = System.getProperty("nexus.devtest.joincode");
        if (code == null) {
            NexusMod.LOGGER.error(
                    "NEXUS DEV TEST: guest role needs -Dnexus.devtest.joincode=XXXX-XXXX");
            return;
        }

        ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
            private boolean joinRequested;
            private int settleTicks;

            @Override
            public void onEndTick(Minecraft client) {
                if (joinRequested || !(client.gui.screen() instanceof TitleScreen)) {
                    return;
                }
                // Give the title screen a moment so the join happens on a settled client.
                if (settleTicks++ < 40) {
                    return;
                }
                joinRequested = true;
                NexusMod.LOGGER.warn("NEXUS DEV TEST: joining by invite code");
                GuestSessionService.joinByCode(client.gui.screen(), code,
                        NexusTransports::directDialer, NexusTransports.directTcp());
            }
        });
    }
}
