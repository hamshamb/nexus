/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.mixin.client;

import io.netty.channel.ChannelFuture;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Reads the listener's private {@code channels} list so the bridge can learn which port
 * an ephemeral (port 0) publish actually bound, instead of probing for a free port first
 * and racing whatever else might claim it.
 */
@Mixin(ServerConnectionListener.class)
public interface ServerConnectionListenerAccessor {

    @Accessor("channels")
    List<ChannelFuture> nexus$getChannels();
}
