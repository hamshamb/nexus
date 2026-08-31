/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.ui;

import dev.nexus.transport.TransportFailure;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shows a categorised connection failure as two plain sentences: what happened and what
 * it means. Never a stack trace -- those go to the log.
 */
public final class NexusFailureScreen extends Screen {

    private final Screen parent;
    private final String summary;
    private final String detail;

    public NexusFailureScreen(Screen parent, TransportFailure failure) {
        this(parent, failure.summary(), failure.detail());
    }

    public NexusFailureScreen(Screen parent, String summary, String detail) {
        super(Component.literal("Connection Problem"));
        this.parent = parent;
        this.summary = summary;
        this.detail = detail;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        addRenderableWidget(new StringWidget(
                centerX - 150, height / 2 - 40, 300, 12,
                Component.literal(summary), font));
        addRenderableWidget(new StringWidget(
                centerX - 150, height / 2 - 24, 300, 12,
                Component.literal(detail), font));
        addRenderableWidget(Button.builder(Component.literal("Back"),
                        b -> minecraft.gui.setScreen(parent))
                .bounds(centerX - 75, height / 2 + 10, 150, 20)
                .build());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
