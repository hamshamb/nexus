/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.transport;

/**
 * Why a transport operation failed, in categories a player-facing message can be written
 * against.
 *
 * <p>Every failure surfaced to a player must carry one of these. "Connection failed" with
 * no category is not an acceptable outcome: the category is what lets the UI say what
 * happened, whether the world is still safe, and whether retrying is worthwhile.
 *
 * <p>The messages here are intentionally free of networking jargon. Diagnostic detail
 * belongs in the logs and in the Connection Details screen, not in the primary message.
 */
public enum TransportFailure {

    /** The host is not reachable: they may have stopped hosting or gone offline. */
    HOST_UNREACHABLE(
            "Could not reach the host.",
            "They may have stopped hosting or lost their connection.",
            true),

    /** A direct peer-to-peer route could not be established. Relay is the next step. */
    DIRECT_UNAVAILABLE(
            "Direct connection unavailable.",
            "Nexus will use a relay instead. No action is needed.",
            true),

    /** The relay could not be reached or refused the allocation. */
    RELAY_UNAVAILABLE(
            "The relay service could not be reached.",
            "Your world is still safe and remains local.",
            true),

    /** The connection was established but then dropped. */
    CONNECTION_LOST(
            "The connection was lost.",
            "This is usually a temporary network problem.",
            true),

    /** The peer took too long to respond. */
    TIMED_OUT(
            "The host did not respond in time.",
            "They may be on a slow connection, or no longer hosting.",
            true),

    /** The host declined the connection: not on the allowlist, banned, or server full. */
    REJECTED(
            "The host did not accept the connection.",
            "You may not be on their allowlist, or their world may be full.",
            false),

    /** The peer sent data that violated the transport contract. */
    PROTOCOL_VIOLATION(
            "The connection was closed because of unexpected data.",
            "This can happen if the host runs a different version of Nexus.",
            false),

    /** The local machine refused the operation, e.g. a firewall blocked a bind. */
    LOCAL_NETWORK_ERROR(
            "Your system blocked the connection.",
            "A firewall or security tool may be preventing Nexus from connecting.",
            true),

    /** Anything not yet categorised. Prefer adding a category over using this. */
    INTERNAL_ERROR(
            "Something went wrong inside Nexus.",
            "Your world is still safe. The details are in the log.",
            true);

    private final String summary;
    private final String detail;
    private final boolean retryable;

    TransportFailure(String summary, String detail, boolean retryable) {
        this.summary = summary;
        this.detail = detail;
        this.retryable = retryable;
    }

    /** One short sentence saying what happened. */
    public String summary() {
        return summary;
    }

    /** One short sentence saying what it means for the player. */
    public String detail() {
        return detail;
    }

    /** Whether offering a Retry action makes sense for this failure. */
    public boolean isRetryable() {
        return retryable;
    }
}
