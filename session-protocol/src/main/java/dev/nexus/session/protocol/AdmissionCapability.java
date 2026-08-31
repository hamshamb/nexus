/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * The one-time guest admission capability.
 *
 * <p>Design (recorded against the review's authentication guardrail): Nexus does
 * <em>not</em> repurpose Mojang's joinServer/hasJoinedServer as an identity system.
 * That exchange is specified for the actual server-login handshake; invoking it a
 * second time with a synthetic server id immediately before the real login would race
 * the session server's per-user state, depend on out-of-flow access-token use, and buy
 * nothing that an unforgeable local capability doesn't already provide -- because
 * vanilla Minecraft authentication remains authoritative for player identity the
 * moment the guest reaches the integrated server's login. So admission is a plain
 * cryptographic capability, and Mojang's protocol is left entirely alone.
 *
 * <p>Shape: {@code NXC1.<sessionId>.<capabilityId>.<expiresAt>.<mac>} where the MAC is
 * HMAC-SHA256 over the first four fields, keyed by the session's admission key -- a
 * random per-session secret the backend generates at session creation and shares only
 * with that session's host. Properties:
 *
 * <ul>
 *   <li><strong>unpredictable</strong>: 128-bit random capability id, HMAC-signed;</li>
 *   <li><strong>short-lived</strong>: expires {@link Protocol#CAPABILITY_TTL_SECONDS}s
 *       after minting;</li>
 *   <li><strong>session-bound</strong>: the session id is under the MAC, and the host
 *       verifies with its own session's key -- a capability for any other session fails
 *       both ways;</li>
 *   <li><strong>single-use</strong>: the host tracks seen capability ids in a
 *       {@link ReplayGuard} until they expire;</li>
 *   <li><strong>cheap to reject</strong>: one HMAC on the host before any loopback
 *       connection or Minecraft resource is touched;</li>
 *   <li>contains no Microsoft/Mojang credentials and no backend-wide secret (the key is
 *       per-session and dies with it).</li>
 * </ul>
 */
public final class AdmissionCapability {

    private static final String PREFIX = "NXC1";
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /**
     * The bounded clock-skew tolerance for capability acceptance.
     *
     * <p>Policy (see ARCHITECTURE.md): a capability with TTL {@code T} minted at
     * backend time {@code m} is accepted by the host iff
     * {@code now <= expiresAt + SKEW} <em>and</em>
     * {@code expiresAt <= now + T + SKEW}. The first bound tolerates a host clock up
     * to {@code SKEW} slow; the second rejects future-dated tokens, so a fast host
     * clock or a forged far-future expiry cannot stretch validity — the acceptance
     * window is never longer than {@code T + 2·SKEW} (50 s) under any clock error.
     * 10 s is deliberate: NTP-synced consumer machines are within fractions of a
     * second, so 10 s only exists to keep badly drifting hosts working, while staying
     * far below the 30 s replay-guard scale. Replay retention is separate and keyed to
     * {@code expiresAt}, not to acceptance.
     */
    public static final int MAX_CLOCK_SKEW_SECONDS = 10;

    /** Outcome of {@link #verify}. Anything but {@code OK} means: close the stream. */
    public enum Verification {
        OK, MALFORMED, EXPIRED, NOT_YET_VALID, WRONG_SESSION, BAD_SIGNATURE
    }

    private AdmissionCapability() {
    }

    /** Generates a fresh per-session admission key (32 random bytes). */
    public static byte[] newAdmissionKey(SecureRandom random) {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return key;
    }

    /**
     * Mints a capability token for {@code sessionId}, valid until
     * {@code now + Protocol.CAPABILITY_TTL_SECONDS}.
     */
    public static String mint(byte[] admissionKey, String sessionId,
                              SecureRandom random, Instant now) {
        byte[] idBytes = new byte[16];
        random.nextBytes(idBytes);
        String capabilityId = B64E.encodeToString(idBytes);
        long expiresAt = now.getEpochSecond() + Protocol.CAPABILITY_TTL_SECONDS;
        String signedPart = PREFIX + "." + sessionId + "." + capabilityId + "." + expiresAt;
        return signedPart + "." + B64E.encodeToString(hmac(admissionKey, signedPart));
    }

    /**
     * Verifies {@code token} against the host's own session and key.
     *
     * <p>Order matters for information hygiene: the signature is checked before expiry
     * or session comparison, so an attacker without the key learns nothing from the
     * failure mode. Comparison is constant-time.
     */
    public static Verification verify(byte[] admissionKey, String expectedSessionId,
                                      String token, Instant now) {
        if (token == null || token.length() > Protocol.MAX_CAPABILITY_BYTES) {
            return Verification.MALFORMED;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || !PREFIX.equals(parts[0])) {
            return Verification.MALFORMED;
        }
        String signedPart = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        byte[] claimedMac;
        long expiresAt;
        try {
            claimedMac = B64D.decode(parts[4]);
            expiresAt = Long.parseLong(parts[3]);
        } catch (IllegalArgumentException e) {
            return Verification.MALFORMED;
        }
        if (!MessageDigest.isEqual(hmac(admissionKey, signedPart), claimedMac)) {
            return Verification.BAD_SIGNATURE;
        }
        if (!expectedSessionId.equals(parts[1])) {
            return Verification.WRONG_SESSION;
        }
        long nowSeconds = now.getEpochSecond();
        if (nowSeconds > expiresAt + MAX_CLOCK_SKEW_SECONDS) {
            return Verification.EXPIRED;
        }
        if (expiresAt > nowSeconds + Protocol.CAPABILITY_TTL_SECONDS + MAX_CLOCK_SKEW_SECONDS) {
            // Future-dated beyond any legitimate mint: forged expiry or a badly wrong
            // clock. Either way, not accepted -- validity must stay bounded.
            return Verification.NOT_YET_VALID;
        }
        return Verification.OK;
    }

    /** The capability id field, for {@link ReplayGuard}. Call only after verify == OK. */
    public static String capabilityId(String token) {
        return token.split("\\.", -1)[2];
    }

    /** The expiry field (epoch seconds). Call only after verify == OK. */
    public static long expiresAt(String token) {
        return Long.parseLong(token.split("\\.", -1)[3]);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new AssertionError("HmacSHA256 unavailable", e);
        }
    }
}
