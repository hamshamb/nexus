/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point.
 *
 * <p>Nexus is a client-hosted product: everything of substance lives in the client
 * entry point. This exists so the mod loads cleanly in every environment and so
 * hosting-disabled overhead stays at zero -- nothing is initialised here beyond a log
 * line.
 */
public final class NexusMod implements ModInitializer {

    public static final String MOD_ID = "nexus";
    public static final Logger LOGGER = LoggerFactory.getLogger("nexus");

    @Override
    public void onInitialize() {
        LOGGER.info("Nexus loaded (common)");
    }
}
