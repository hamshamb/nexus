/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;

import static dev.nexus.session.protocol.AdmissionCapability.Verification;
import static org.assertj.core.api.Assertions.assertThat;

class AdmissionCapabilityTest {

    private final SecureRandom random = new SecureRandom();
    private final byte[] key = AdmissionCapability.newAdmissionKey(random);
    private final Instant now = Instant.ofEpochSecond(1_800_000_000L);

    @Test
    void mintedCapabilityVerifiesForItsOwnSessionWithinItsLifetime() {
        String token = AdmissionCapability.mint(key, "sess-1", random, now);
        assertThat(AdmissionCapability.verify(key, "sess-1", token, now))
                .isEqualTo(Verification.OK);
        assertThat(AdmissionCapability.verify(key, "sess-1", token,
                now.plusSeconds(Protocol.CAPABILITY_TTL_SECONDS - 1)))
                .isEqualTo(Verification.OK);
    }

    @Test
    void capabilityExpiresQuicklyBeyondTheSkewTolerance() {
        String token = AdmissionCapability.mint(key, "sess-1", random, now);
        assertThat(AdmissionCapability.verify(key, "sess-1", token,
                now.plusSeconds(Protocol.CAPABILITY_TTL_SECONDS
                        + AdmissionCapability.MAX_CLOCK_SKEW_SECONDS + 1)))
                .isEqualTo(Verification.EXPIRED);
    }

    @Test
    void wrongSessionIsRejectedBothWays() {
        String token = AdmissionCapability.mint(key, "sess-1", random, now);
        // Same key, different expected session id.
        assertThat(AdmissionCapability.verify(key, "sess-2", token, now))
                .isEqualTo(Verification.WRONG_SESSION);
        // Different session's key entirely: fails the signature before anything else.
        byte[] otherKey = AdmissionCapability.newAdmissionKey(random);
        assertThat(AdmissionCapability.verify(otherKey, "sess-1", token, now))
                .isEqualTo(Verification.BAD_SIGNATURE);
    }

    @Test
    void tamperingAnyFieldBreaksTheSignature() {
        String token = AdmissionCapability.mint(key, "sess-1", random, now);
        String[] p = token.split("\\.");
        // Extend expiry by a digit.
        String extended = p[0] + "." + p[1] + "." + p[2] + "." + p[3] + "9." + p[4];
        assertThat(AdmissionCapability.verify(key, "sess-1", extended, now))
                .isEqualTo(Verification.BAD_SIGNATURE);
        // Swap the session id.
        String resessioned = p[0] + ".sess-2." + p[2] + "." + p[3] + "." + p[4];
        assertThat(AdmissionCapability.verify(key, "sess-2", resessioned, now))
                .isEqualTo(Verification.BAD_SIGNATURE);
    }

    @Test
    void malformedTokensAreRejectedWithoutException() {
        for (String bad : new String[]{
                null, "", "NXC1", "NXC1.a.b.c", "NXC1.a.b.c.d.e",
                "XXXX.s.c.123.mac", "NXC1.s.c.notanumber.bWFj",
                "NXC1.s.c.123.!!notbase64!!", "N".repeat(600)}) {
            assertThat(AdmissionCapability.verify(key, "sess-1", bad, now))
                    .as("token %s", bad == null ? "null" : bad)
                    .isIn(Verification.MALFORMED, Verification.BAD_SIGNATURE);
        }
    }

    @Test
    void replayGuardAdmitsEachCapabilityExactlyOnceAndPrunes() {
        ReplayGuard guard = new ReplayGuard();
        String token = AdmissionCapability.mint(key, "sess-1", random, now);
        String id = AdmissionCapability.capabilityId(token);
        long expiresAt = AdmissionCapability.expiresAt(token);

        assertThat(guard.firstUse(id, expiresAt, now)).isTrue();
        assertThat(guard.firstUse(id, expiresAt, now)).isFalse();
        assertThat(guard.firstUse(id, expiresAt, now.plusSeconds(5))).isFalse();

        // Long after expiry (+skew) the entry is pruned; by then verify() would
        // already reject the token as EXPIRED, so re-admittance is unreachable.
        assertThat(guard.firstUse("other", expiresAt, now.plusSeconds(120))).isTrue();
        assertThat(guard.size()).isEqualTo(1);
    }

    @Test
    void twoMintsAreNeverIdentical() {
        String a = AdmissionCapability.mint(key, "sess-1", random, now);
        String b = AdmissionCapability.mint(key, "sess-1", random, now);
        assertThat(a).isNotEqualTo(b);
        assertThat(AdmissionCapability.capabilityId(a))
                .isNotEqualTo(AdmissionCapability.capabilityId(b));
    }
}
