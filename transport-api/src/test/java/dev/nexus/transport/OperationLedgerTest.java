/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-flight ownership contract that makes stop() truthful: work started before
 * drain is awaited; work attempted after drain never starts. These are the
 * deterministic stand-ins for "stop while a loopback dial is pending", "dial
 * completes after stop starts", and "many concurrent pending admissions during stop".
 */
@Timeout(30)
class OperationLedgerTest {

    @Test
    void drainWaitsForAPendingOperation() throws Exception {
        OperationLedger ledger = new OperationLedger();
        CompletableFuture<Void> pendingDial = new CompletableFuture<>();
        assertThat(ledger.track(pendingDial)).isTrue();

        CompletableFuture<Void> drained = ledger.drain();
        assertThat(drained.isDone())
                .as("stop must not complete while a dial is in flight")
                .isFalse();

        // The dial completes after stop started (success or failure - both count).
        pendingDial.complete(null);
        drained.get(5, TimeUnit.SECONDS);
        assertThat(ledger.pending()).isZero();
    }

    @Test
    void drainWaitsForAFailingOperationToo() throws Exception {
        OperationLedger ledger = new OperationLedger();
        CompletableFuture<Void> failingDial = new CompletableFuture<>();
        ledger.track(failingDial);

        CompletableFuture<Void> drained = ledger.drain();
        failingDial.completeExceptionally(new RuntimeException("dial failed after stop"));
        // A failed operation must not fail (or hang) the stop itself.
        drained.get(5, TimeUnit.SECONDS);
    }

    @Test
    void trackAfterDrainIsRefusedSoNothingStartsLate() {
        OperationLedger ledger = new OperationLedger();
        ledger.drain();
        AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<Void> late = new CompletableFuture<>();
        if (ledger.track(late)) {
            started.set(true);
        }
        assertThat(started).as("no operation may begin after stop has won").isFalse();
    }

    @Test
    void manyConcurrentOperationsRacingDrainAreEitherAwaitedOrRefused() throws Exception {
        for (int round = 0; round < 100; round++) {
            OperationLedger ledger = new OperationLedger();
            ExecutorService pool = Executors.newFixedThreadPool(9);
            try {
                AtomicInteger accepted = new AtomicInteger();
                AtomicInteger refused = new AtomicInteger();
                AtomicInteger completedBeforeDrainFinished = new AtomicInteger();
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch submitted = new CountDownLatch(8);

                for (int t = 0; t < 8; t++) {
                    pool.execute(() -> {
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        CompletableFuture<Void> op = new CompletableFuture<>();
                        if (ledger.track(op)) {
                            accepted.incrementAndGet();
                            // Simulate async completion slightly later.
                            CompletableFuture.runAsync(() -> {
                                completedBeforeDrainFinished.incrementAndGet();
                                op.complete(null);
                            });
                        } else {
                            refused.incrementAndGet();
                        }
                        submitted.countDown();
                    });
                }

                go.countDown();
                CompletableFuture<Void> drained = ledger.drain();
                assertThat(submitted.await(10, TimeUnit.SECONDS)).isTrue();
                // Whatever was accepted must be awaited by SOME drain call; a second
                // drain covers acceptances that raced past the first snapshot.
                ledger.drain().get(10, TimeUnit.SECONDS);
                drained.get(10, TimeUnit.SECONDS);

                assertThat(accepted.get() + refused.get()).isEqualTo(8);
                // Every accepted op ran its completion path (resources released).
                assertThat(completedBeforeDrainFinished.get()).isEqualTo(accepted.get());
                assertThat(ledger.pending()).isZero();
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
