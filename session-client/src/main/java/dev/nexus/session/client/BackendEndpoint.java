/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.client;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * The plaintext policy for the coordination endpoint, enforced <em>before any request
 * is built</em> — no host token, admission key, capability, or route data can ever
 * travel over remote plaintext HTTP.
 *
 * <p>Invariant: {@code https://} is accepted for any host; {@code http://} is accepted
 * only for unambiguous local-development destinations decided by the URL text alone,
 * never by asking DNS:
 *
 * <ul>
 *   <li>a literal IPv4 address in {@code 127.0.0.0/8};</li>
 *   <li>the literal IPv6 address {@code ::1};</li>
 *   <li>the exact hostname {@code localhost} (case-insensitive) — a fixed, special-cased
 *       string, not a general hostname-resolution path.</li>
 * </ul>
 *
 * <p>Deliberately <strong>not</strong> accepted: any other DNS hostname, even one that
 * currently resolves only to loopback addresses. Resolving-then-trusting is a DNS
 * rebinding hazard — a name can answer loopback at validation time and something else
 * at connect time, and the two lookups are not the same request. Deciding from the URL
 * text alone removes that gap entirely. This also rejects deceptive names like
 * {@code 127.0.0.1.evil.example}, which is a hostname, not an address, regardless of
 * what it resolves to. URLs carrying userinfo are rejected outright, as is any scheme
 * other than {@code http}/{@code https}. TLS termination may live in front of the
 * backend in production; Nexus's job here is refusing to be the one who leaks.
 */
public final class BackendEndpoint {

    private BackendEndpoint() {
    }

    /**
     * Validates {@code url} against the plaintext policy.
     *
     * @return the validated URI
     * @throws SessionClientException with code {@code insecure_endpoint} on violation
     */
    public static URI validate(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
        if (url.getRawUserInfo() != null) {
            throw insecure("The Nexus service URL must not contain credentials");
        }
        if (scheme.equals("https")) {
            return url;
        }
        if (!scheme.equals("http")) {
            throw insecure("The Nexus service URL must be http (loopback only) or https");
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            throw insecure("The Nexus service URL has no host");
        }
        if (isUnambiguousLocalLiteral(host)) {
            return url;
        }
        throw insecure("Plain http:// is allowed only for a literal loopback address "
                + "(127.0.0.1, [::1]) or the hostname 'localhost'. Any other host, "
                + "including one that currently resolves to loopback, must use "
                + "https:// for a remote Nexus service.");
    }

    /**
     * True iff {@code host}, by its text alone, denotes only this machine. Never
     * consults DNS: a name is either the fixed literal {@code localhost} or it is
     * rejected, regardless of what it might resolve to.
     */
    static boolean isUnambiguousLocalLiteral(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        // URI brackets IPv6 literals; strip them for parsing.
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        InetAddress literal = parseLiteral(bare);
        return literal != null && literal.isLoopbackAddress();
    }

    /**
     * Parses strictly literal IPs, never resolving. {@code InetAddress.getByName}
     * accepts exotic numeric forms (hex, octal, dword) that browsers and the JDK
     * disagree about, so only canonical dotted-quad and IPv6 forms count as literals;
     * everything else is rejected rather than resolved.
     */
    private static InetAddress parseLiteral(String host) {
        try {
            if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return InetAddress.getByName(host);
            }
            if (host.contains(":") && host.matches("[0-9A-Fa-f:]+")) {
                InetAddress parsed = InetAddress.getByName(host);
                return parsed instanceof Inet6Address || parsed instanceof Inet4Address
                        ? parsed : null;
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    private static SessionClientException insecure(String message) {
        return new SessionClientException("insecure_endpoint", message);
    }
}
