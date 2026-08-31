/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.session.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * The protocol's JSON codec: one shared Gson, lenient about unknown fields (additive
 * evolution) and strict about everything else.
 */
public final class Json {

    private static final Gson GSON = new Gson();

    private Json() {
    }

    public static String encode(Object message) {
        return GSON.toJson(message);
    }

    /**
     * Decodes {@code body} into {@code type}.
     *
     * @throws JsonParseException on malformed input; callers translate this to
     *                            {@link Protocol.ErrorCode#MALFORMED}
     */
    public static <T> T decode(String body, Class<T> type) {
        T value = GSON.fromJson(body, type);
        if (value == null) {
            throw new JsonParseException("empty body");
        }
        return value;
    }
}
