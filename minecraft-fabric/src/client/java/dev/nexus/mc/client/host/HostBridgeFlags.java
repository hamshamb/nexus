/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.host;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one flag the publish mixins consult.
 *
 * <p>While a Nexus publish is in progress, {@code IntegratedServer.publishServer} is
 * modified to bind loopback-only and to skip LAN multicast advertising. When the flag is
 * clear -- i.e. the player used vanilla "Open to LAN" themselves -- the mixins change
 * nothing and vanilla behaves exactly as shipped.
 */
public final class HostBridgeFlags {

    private static final AtomicBoolean NEXUS_PUBLISH_ACTIVE = new AtomicBoolean();

    private HostBridgeFlags() {
    }

    /** True while {@link HostSessionService} is inside its publishServer call. */
    public static boolean isNexusPublishActive() {
        return NEXUS_PUBLISH_ACTIVE.get();
    }

    static void setNexusPublishActive(boolean active) {
        NEXUS_PUBLISH_ACTIVE.set(active);
    }
}
