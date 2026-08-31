/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.core.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SessionMachineTest {

    @Test
    void hostFollowsTheHappyPathToOnlineAndBackToStopped() {
        SessionMachine<HostSessionState> host = SessionMachine.forHost();
        assertThat(host.state()).isEqualTo(HostSessionState.IDLE);

        host.transitionTo(HostSessionState.STARTING, "host clicked Start Hosting");
        host.transitionTo(HostSessionState.GATHERING_CONNECTIVITY, null);
        host.transitionTo(HostSessionState.ONLINE, null);
        assertThat(host.state().isAcceptingGuests()).isTrue();

        host.transitionTo(HostSessionState.STOPPING, "host clicked Stop");
        host.transitionTo(HostSessionState.STOPPED, null);
        assertThat(host.state().isTerminal()).isTrue();
        assertThat(host.state().isActive()).isFalse();
    }

    @Test
    void hostCanAbandonFromEveryActiveStage() {
        for (HostSessionState active : List.of(
                HostSessionState.STARTING,
                HostSessionState.GATHERING_CONNECTIVITY,
                HostSessionState.ONLINE)) {
            assertThat(active.canTransitionTo(HostSessionState.STOPPING))
                    .as("%s must be abandonable", active)
                    .isTrue();
            assertThat(active.isActive())
                    .as("%s owns resources", active)
                    .isTrue();
        }
    }

    @Test
    void hostRejectsSkippingStagesAndRestartingAfterStop() {
        SessionMachine<HostSessionState> host = SessionMachine.forHost();

        // Cannot jump straight to ONLINE without preparing the server first.
        assertThatExceptionOfType(IllegalTransitionException.class)
                .isThrownBy(() -> host.transitionTo(HostSessionState.ONLINE, null));

        host.transitionTo(HostSessionState.STARTING, null);
        host.transitionTo(HostSessionState.STOPPING, null);
        host.transitionTo(HostSessionState.STOPPED, null);

        // STOPPED is terminal: a new session needs a new machine.
        assertThat(HostSessionState.STOPPED.allowedSuccessors()).isEmpty();
        assertThatExceptionOfType(IllegalTransitionException.class)
                .isThrownBy(() -> host.transitionTo(HostSessionState.STARTING, null));
    }

    @Test
    void guestMayFallBackFromDirectToRelayButNotBackwards() {
        SessionMachine<GuestSessionState> guest = SessionMachine.forGuest();
        guest.transitionTo(GuestSessionState.NEGOTIATING, null);
        guest.transitionTo(GuestSessionState.CONNECTING_DIRECT, null);

        // Falling back to relay is a designed path, not a failure.
        guest.transitionTo(GuestSessionState.CONNECTING_RELAY, "symmetric NAT");
        guest.transitionTo(GuestSessionState.CONNECTED_RELAY, null);
        assertThat(guest.state().isConnected()).isTrue();

        // Having settled on relay, we never silently claim to be direct.
        assertThat(GuestSessionState.CONNECTING_RELAY
                .canTransitionTo(GuestSessionState.CONNECTING_DIRECT)).isFalse();
        assertThat(GuestSessionState.CONNECTED_RELAY
                .canTransitionTo(GuestSessionState.CONNECTED_DIRECT)).isFalse();
    }

    @Test
    void everyNonTerminalGuestStateCanFail() {
        for (GuestSessionState state : GuestSessionState.values()) {
            if (state.isTerminal()) {
                assertThat(state.allowedSuccessors())
                        .as("%s is terminal", state)
                        .isEmpty();
            } else {
                assertThat(state.canTransitionTo(GuestSessionState.FAILED))
                        .as("%s must be able to fail", state)
                        .isTrue();
            }
        }
    }

    @Test
    void tryTransitionLetsExactlyOneOfTwoRacingStoppersWin() throws Exception {
        SessionMachine<HostSessionState> host = SessionMachine.forHost();
        host.transitionTo(HostSessionState.STARTING, null);
        host.transitionTo(HostSessionState.GATHERING_CONNECTIVITY, null);
        host.transitionTo(HostSessionState.ONLINE, null);

        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        AtomicInteger winners = new AtomicInteger();

        for (int i = 0; i < racers; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (host.tryTransitionTo(HostSessionState.STOPPING, "race")) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // Shutdown can legitimately be triggered from several places at once; exactly one
        // must actually perform it, or teardown would run twice.
        assertThat(winners.get()).isEqualTo(1);
        assertThat(host.state()).isEqualTo(HostSessionState.STOPPING);
    }

    @Test
    void listenersSeeEveryTransitionInOrderAndAreNotNotifiedOnRejection() {
        SessionMachine<HostSessionState> host = SessionMachine.forHost();
        List<String> observed = new ArrayList<>();
        host.addListener((from, to, reason) -> observed.add(from + "->" + to));

        host.transitionTo(HostSessionState.STARTING, null);
        assertThat(host.tryTransitionTo(HostSessionState.ONLINE, null)).isFalse();
        host.transitionTo(HostSessionState.GATHERING_CONNECTIVITY, null);

        assertThat(observed).containsExactly(
                "IDLE->STARTING",
                "STARTING->GATHERING_CONNECTIVITY");
    }
}
