/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.mc.client.ui;

import dev.nexus.mc.client.NexusTransports;
import dev.nexus.mc.client.guest.GuestSessionService;
import dev.nexus.session.protocol.InviteCode;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The minimum functional Join screen for M3: enter the invite code the host shared,
 * press Join. Everything else -- resolution, capability, route -- is invisible.
 */
public final class NexusJoinScreen extends Screen {

    private final Screen parent;
    private EditBox codeField;
    private StringWidget problemLine;

    public NexusJoinScreen(Screen parent) {
        super(Component.literal("Join Hosted World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;

        addRenderableWidget(new StringWidget(centerX - 150, 40, 300, 12, title, font));
        addRenderableWidget(new StringWidget(
                centerX - 150, 70, 300, 12,
                Component.literal("Enter the invite code the host gave you:"), font));

        codeField = new EditBox(font, centerX - 100, 90, 200, 20,
                Component.literal("Invite code"));
        codeField.setMaxLength(12);
        codeField.setHint(Component.literal("K7M2-PQ9X"));
        addRenderableWidget(codeField);
        setInitialFocus(codeField);

        problemLine = new StringWidget(centerX - 150, 118, 300, 12,
                Component.empty(), font);
        addRenderableWidget(problemLine);

        addRenderableWidget(Button.builder(Component.literal("Join"), b -> join())
                .bounds(centerX - 100, 136, 200, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX - 100, height - 40, 200, 20)
                .build());
    }

    private void join() {
        String raw = InviteCode.normalize(codeField.getValue());
        if (raw == null) {
            problemLine.setMessage(Component.literal(
                    "That doesn't look like an invite code — expected something like K7M2-PQ9X"));
            return;
        }
        GuestSessionService.joinByCode(parent, raw,
                NexusTransports::directDialer, NexusTransports.directTcp());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
