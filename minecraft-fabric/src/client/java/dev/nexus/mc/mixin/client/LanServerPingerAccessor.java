/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.mixin.client;

import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.net.DatagramSocket;

/**
 * Reaches the pinger's private {@code socket} so a suppressed (never-started) pinger's
 * socket can be closed instead of leaking; see
 * {@link IntegratedServerMixin#nexus$suppressLanAdvertising}.
 */
@Mixin(LanServerPinger.class)
public interface LanServerPingerAccessor {

    @Accessor("socket")
    DatagramSocket nexus$getSocket();
}
