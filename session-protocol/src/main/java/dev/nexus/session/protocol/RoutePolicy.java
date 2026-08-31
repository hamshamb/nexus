/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The trust boundary for route data (the addresses a guest will dial).
 *
 * <p>Authority model: the <strong>backend</strong> is authoritative for which routes a
 * session advertises — it validates addresses at registration and returns only what
 * was validated. The <strong>guest</strong> does not extend that trust blindly: it
 * re-validates every address through this same policy before dialing, so neither a
 * compromised backend nor a tampered response can turn Nexus into an arbitrary
 * network dialer. Route strings are bare host literals or hostnames plus a separate
 * port — never URIs; no scheme, path, userinfo, or zone can smuggle behavior in.
 *
 * <p>Shape validation accepts exactly three forms: IPv4 literal, bracketless IPv6
 * literal, or a DNS hostname (letters/digits/hyphens, dot-separated, ≤63 per label,
 * ≤253 total). Range policy is explicit: {@link Mode#DEVELOPMENT} additionally allows
 * loopback and private ranges (host and guest on one machine or LAN — the M3 reality);
 * {@link Mode#PRODUCTION} refuses loopback and link-local, which are nonsense for a
 * remote guest and abusable for SSRF-style redirection.
 *
 * <p>M4 forward-compatibility: ICE candidates carry their own richer structure, so
 * they get their own validator; this class stays the policy for plain host:port
 * routes, and the {@link Mode} split (what ranges may be dialed) carries over as-is.
 */
public final class RoutePolicy {

    /** Which address ranges a dialable route may live in. */
    public enum Mode {
        DEVELOPMENT, PRODUCTION
    }

    private static final Pattern IPV4 =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,45}");
    private static final Pattern HOSTNAME_LABEL =
            Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private RoutePolicy() {
    }

    /** True iff {@code port} is a dialable TCP/UDP port. */
    public static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /**
     * Validates one route address under {@code mode}. Returns {@code false} for
     * anything malformed, control-character-laden, URI-like, out of the allowed
     * ranges, or over {@link Protocol#MAX_ADDRESS_LENGTH}.
     */
    public static boolean validAddress(String address, Mode mode) {
        if (address == null || address.isEmpty()
                || address.length() > Protocol.MAX_ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c <= ' ' || c == '/' || c == '\\' || c == '@' || c == '#'
                    || c == '?' || c == '%' || c == '[' || c == ']' || c >= 0x7F) {
                return false;
            }
        }
        Boolean ipv4 = asIpv4(address);
        if (ipv4 != null) {
            return ipv4 && ipv4RangeAllowed(address, mode);
        }
        if (address.indexOf(':') >= 0) {
            return IPV6.matcher(address).matches() && ipv6RangeAllowed(address, mode);
        }
        return validHostname(address);
    }

    /** Filters a route list: valid entries only, capped at the protocol maximum. */
    public static List<String> sanitize(List<String> addresses, Mode mode) {
        List<String> clean = new ArrayList<>();
        if (addresses == null) {
            return clean;
        }
        for (String address : addresses) {
            if (validAddress(address, mode)) {
                clean.add(address);
                if (clean.size() == Protocol.MAX_ADDRESSES) {
                    break;
                }
            }
        }
        return clean;
    }

    /** null = not IPv4-shaped at all; TRUE/FALSE = shaped, and valid/invalid. */
    private static Boolean asIpv4(String address) {
        var matcher = IPV4.matcher(address);
        if (!matcher.matches()) {
            return null;
        }
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(matcher.group(i));
            if (octet > 255) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    private static boolean ipv4RangeAllowed(String address, Mode mode) {
        int first = Integer.parseInt(address.substring(0, address.indexOf('.')));
        int second = Integer.parseInt(address.split("\\.")[1]);
        boolean loopback = first == 127;
        boolean linkLocal = first == 169 && second == 254;
        boolean unspecified = address.equals("0.0.0.0");
        boolean multicastOrReserved = first >= 224;
        if (unspecified || multicastOrReserved || linkLocal) {
            return false;
        }
        return mode == Mode.DEVELOPMENT || !loopback;
    }

    private static boolean ipv6RangeAllowed(String address, Mode mode) {
        String lower = address.toLowerCase();
        boolean loopback = lower.equals("::1");
        boolean unspecified = lower.equals("::");
        boolean linkLocal = lower.startsWith("fe80:");
        boolean multicast = lower.startsWith("ff");
        if (unspecified || linkLocal || multicast) {
            return false;
        }
        return mode == Mode.DEVELOPMENT || !loopback;
    }

    private static boolean validHostname(String address) {
        if (address.length() > 253 || address.endsWith(".")) {
            return false;
        }
        String[] labels = address.split("\\.", -1);
        for (String label : labels) {
            if (!HOSTNAME_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }
}
