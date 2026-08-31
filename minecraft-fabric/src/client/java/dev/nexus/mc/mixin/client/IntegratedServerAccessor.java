/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.mixin.client;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Corrects {@code publishedPort} after an ephemeral publish: vanilla stores the port
 * that was requested (0), and everything that reports the port -- {@code getPort()},
 * the F3 debug line -- would otherwise show 0.
 */
@Mixin(IntegratedServer.class)
public interface IntegratedServerAccessor {

    @Accessor("publishedPort")
    void nexus$setPublishedPort(int port);
}
