/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Invite codes: what the host reads out loud and the guest types in.
 *
 * <p>Eight symbols from a 32-character ambiguity-resistant alphabet (Crockford-style:
 * no I, L, O, or U) give exactly 40 bits of entropy, shown as {@code K7M2-PQ9X}. Codes
 * are generated server-side from a {@link SecureRandom}, carry no network information
 * whatsoever -- they are opaque lookup keys -- and are always handled as their SHA-256
 * hash outside of the create response, so a memory dump or log line never exposes a
 * joinable code.
 *
 * <p>Normalization is forgiving about what humans type: case-insensitive, separators
 * ignored, and the excluded look-alikes map to their intended symbol (I/L→1, O→0).
 */
public final class InviteCode {

    /** Crockford base32: 0-9 then the alphabet minus I, L, O, U. */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Symbols per code: 8 × 5 bits = 40 bits of entropy. */
    static final int LENGTH = 8;

    /** Bits of entropy per code; documented and asserted by tests. */
    public static final int ENTROPY_BITS = LENGTH * 5;

    private InviteCode() {
    }

    /** Generates a new raw (unformatted) code, e.g. {@code K7M2PQ9X}. */
    public static String generate(SecureRandom random) {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Formats a raw code for humans: {@code K7M2PQ9X -> K7M2-PQ9X}. */
    public static String format(String raw) {
        if (raw.length() != LENGTH) {
            throw new IllegalArgumentException("not a raw invite code");
        }
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    /**
     * Normalizes user input to raw form, or returns {@code null} if it cannot be a
     * code. Accepts any case, ignores {@code -}, {@code _} and whitespace, and maps
     * the excluded look-alike characters to their intended symbol.
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toUpperCase(input.charAt(i));
            if (c == '-' || c == '_' || Character.isWhitespace(c)) {
                continue;
            }
            c = switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            };
            if (ALPHABET.indexOf(c) < 0 || sb.length() == LENGTH) {
                return null;
            }
            sb.append(c);
        }
        return sb.length() == LENGTH ? sb.toString() : null;
    }

    /**
     * The SHA-256 of a raw code, hex-encoded: the only form codes are stored or
     * compared in. Hash lookup also sidesteps string-comparison timing concerns.
     */
    public static String hash(String rawCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawCode.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }
}
